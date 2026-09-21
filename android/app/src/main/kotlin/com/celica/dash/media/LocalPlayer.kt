package com.celica.dash.media

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** One song on the unit's own storage. */
data class Track(val id: Long, val uri: Uri, val title: String, val artist: String?, val album: String?, val durationMs: Long)

data class LibraryState(
    val tracks: List<Track> = emptyList(),
    val index: Int = -1,                // in [order]; -1 when nothing is loaded
    val order: List<Int> = emptyList(), // indices into tracks, shuffled or not
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    val loaded: Boolean = false,
    val needsStorage: Boolean = false,
) {
    val current: Track? get() = order.getOrNull(index)?.let { tracks.getOrNull(it) }
}

/**
 * Music without the phone: the songs copied onto the unit (`tools/car.py songs`, into
 * /sdcard/Music/Celica) played by the dash itself. The unit's media scanner has them, so they
 * come from MediaStore; MediaPlayer plays them; and a MediaSession of our own carries what is
 * playing, so the tile and the media page - which follow whichever session is live - show it
 * and drive it with no special case. Audio focus is taken while playing and given back after,
 * which is what pauses the phone's music when this starts and lets it back in when this stops.
 *
 * Reading other apps' files under Music needs storage permission; on the unit root grants it
 * (`tools/car.py install`), elsewhere the page says so.
 */
class LocalPlayer(private val ctx: Context) {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var pausedByFocus = false

    /** READ_MEDIA_AUDIO from Android 13, READ_EXTERNAL_STORAGE before it - the same question, asked the way each version wants. */
    fun hasStorage(): Boolean = com.celica.dash.ui.setup.Permissions.runtime(com.celica.dash.ui.setup.Permissions.Kind.STORAGE).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /** The library from MediaStore, once; call again after the songs folder changes. */
    fun load() {
        if (!hasStorage()) { _state.value = _state.value.copy(loaded = true, needsStorage = true); return }
        io.execute {
            val list = ArrayList<Track>()
            val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA)
            runCatching {
                ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(5) ?: ""
                        if (!path.contains("/Music/")) continue
                        val id = c.getLong(0)
                        val artist = c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        val album = c.getString(3)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        // a downloader's "Artist - Title.mp3" with no tags: split the name
                        var title = c.getString(1) ?: path.substringAfterLast('/').substringBeforeLast('.')
                        var art = artist
                        if (art == null && title.contains(" - ")) { art = title.substringBefore(" - ").trim(); title = title.substringAfter(" - ").trim() }
                        list += Track(id, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), title, art, album, c.getLong(4))
                    }
                }
            }
            val sorted = list.sortedWith(compareBy({ it.artist?.lowercase() ?: "￿" }, { it.title.lowercase() }))
            main.post {
                val s = _state.value
                _state.value = s.copy(tracks = sorted, order = sorted.indices.toList(), loaded = true, needsStorage = false)
            }
        }
    }

    /** Play this track (by its place in [LibraryState.tracks]), in the current order. */
    fun play(trackIndex: Int) {
        val s = _state.value
        val pos = s.order.indexOf(trackIndex)
        if (pos < 0) return
        start(pos)
    }

    /** Everything, in a fresh random order, from the top. */
    fun shuffleAll() {
        val s = _state.value
        if (s.tracks.isEmpty()) return
        _state.value = s.copy(order = s.tracks.indices.shuffled(), shuffle = true)
        start(0)
    }

    fun setShuffle(on: Boolean) {
        val s = _state.value
        val cur = s.current
        val order = if (on) s.tracks.indices.shuffled() else s.tracks.indices.toList()
        val idx = cur?.let { t -> order.indexOf(s.tracks.indexOf(t)) } ?: -1
        _state.value = s.copy(order = order, index = idx, shuffle = on)
    }

    fun playPause() {
        val p = player ?: return
        if (p.isPlaying) { p.pause(); publish(false) } else if (takeFocus()) { p.start(); publish(true) }
    }

    fun next() { val s = _state.value; if (s.order.isNotEmpty()) start((s.index + 1) % s.order.size) }
    fun previous() {
        val p = player; val s = _state.value
        if (p != null && p.currentPosition > 4000) { p.seekTo(0); publish(p.isPlaying); return }
        if (s.order.isNotEmpty()) start((s.index - 1 + s.order.size) % s.order.size)
    }

    /** Done: the player and the session go, and audio focus is handed back to whoever wants it. */
    fun stop() {
        player?.release(); player = null
        session?.let { it.isActive = false; it.release() }; session = null
        focus?.let { am.abandonAudioFocusRequest(it) }; focus = null
        _state.value = _state.value.copy(index = -1, playing = false)
    }

    private fun start(pos: Int) {
        val s = _state.value
        val t = s.tracks.getOrNull(s.order.getOrNull(pos) ?: return) ?: return
        if (!takeFocus()) return
        val p = (player ?: MediaPlayer().also { player = it }).apply {
            reset()
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setOnCompletionListener { next() }
            setOnErrorListener { _, _, _ -> main.postDelayed({ next() }, 500); true }
        }
        val ok = runCatching { p.setDataSource(ctx, t.uri); p.prepare(); p.start() }.isSuccess
        _state.value = _state.value.copy(index = pos, playing = ok)
        if (!ok) { main.postDelayed({ next() }, 500); return }
        announce(t)
        publish(true)
    }

    private fun takeFocus(): Boolean {
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                val p = player ?: return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { if (p.isPlaying) p.pause(); pausedByFocus = false; publish(false) }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { if (p.isPlaying) { p.pause(); pausedByFocus = true; publish(false) } }
                    AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) { pausedByFocus = false; p.start(); publish(true) }
                }
            }, main).build().also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** The session's word on the track: title, artist, album, length, and the cover from the file. */
    private fun announce(t: Track) {
        val ses = session ?: MediaSession(ctx, "celica-library").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = playPause()
                override fun onPause() = playPause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onStop() = stop()
            })
            isActive = true
        }.also { session = it }
        ses.setMetadata(metadata(t, null))
        io.execute {
            val r = MediaMetadataRetriever()
            val art = runCatching {
                r.setDataSource(ctx, t.uri); r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }.getOrNull()?.let { if (it.width > 600) Bitmap.createScaledBitmap(it, 600, 600 * it.height / it.width, true) else it }
            runCatching { r.release() }
            if (art != null) main.post { if (_state.value.current == t) session?.setMetadata(metadata(t, art)) }
        }
    }

    private fun metadata(t: Track, art: Bitmap?) = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist ?: "")
        .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album ?: "")
        .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
        .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
        .build()

    private fun publish(playing: Boolean) {
        _state.value = _state.value.copy(playing = playing)
        val p = player
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, (p?.currentPosition ?: 0).toLong(), if (playing) 1f else 0f)
                .build()
        )
    }

    fun describe(): String = _state.value.let { "${it.tracks.size} tracks · ${if (it.needsStorage) "NO storage permission (Settings › Phone › Access › Music on the unit)" else if (!it.loaded) "not loaded" else "loaded"} · ${it.current?.title ?: "nothing"} · playing ${it.playing}" }
}
