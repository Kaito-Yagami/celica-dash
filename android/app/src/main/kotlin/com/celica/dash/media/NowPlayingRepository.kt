package com.celica.dash.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Follows the active media session - Spotify on a phone over Bluetooth shows up here through
 * the head unit's Bluetooth audio app, if that app registers a session (most do). Metadata,
 * playback state and transport controls come off the MediaController.
 *
 * When there is no session at all, transport falls back to media key events through
 * AudioManager, which the vendor's Bluetooth app should honour even if it publishes nothing.
 */
class NowPlayingRepository(private val ctx: Context, private val qf: QfBluetooth) {
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()
    private val artLookup = ArtLookup(ctx)
    private var lookedUp: String? = null           // "title|artist" last asked for
    private var foundArt: Bitmap? = null

    /** Art the phone did not send: the cached or looked-up cover for the current track. */
    private fun artFor(title: String?, artist: String?, sent: Bitmap?): Bitmap? {
        if (sent != null || title == null) return sent
        val k = "$title|$artist"
        if (lookedUp != k) {
            lookedUp = k; foundArt = artLookup.cached(title, artist)
            if (foundArt == null) kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val b = artLookup.fetch(title, artist)
                if (b != null && lookedUp == k) { foundArt = b; main.post { publish() } }
            }
        }
        return foundArt
    }

    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listener = ComponentName(ctx, DashNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { attach(null); refresh() }
    }

    fun hasAccess(): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true

    private var qfWatched = false

    fun start() {
        qf.start()
        if (!qfWatched) {
            qfWatched = true
            // the vendor service's word arrives on its own flow; fold it in whenever it changes
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) { qf.state.collect { publish() } }
        }
        if (!hasAccess()) { publish(); return }
        msm.addOnActiveSessionsChangedListener({ refresh() }, listener, main)
        refresh()
    }

    fun refresh() {
        if (!hasAccess()) { publish(); return }
        val sessions = try { msm.getActiveSessions(listener) } catch (e: SecurityException) { emptyList() }
        // the one that knows what it is playing wins; among those, the one actually playing.
        // On the head unit that is Android's own AVRCP session (com.android.bluetooth), which
        // carries the phone's title, artist and art - the vendor's registers an empty one.
        fun titled(c: MediaController) = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null
        fun playing(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val pick = sessions.firstOrNull { titled(it) && playing(it) }
            ?: sessions.firstOrNull { titled(it) }
            ?: sessions.firstOrNull { playing(it) }
            ?: sessions.firstOrNull { it.playbackState != null }
            ?: sessions.firstOrNull()
        if (pick?.sessionToken != controller?.sessionToken) attach(pick)
        publish()
    }

    private fun attach(c: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback, main)
        com.celica.dash.data.LinkLog.note("media", c?.let { "following ${it.packageName}" } ?: "no session")
    }

    private fun publish() {
        val q = qf.state.value
        // a session that says nothing (Zlink registers an empty one) loses to the vendor service
        val c = controller?.takeIf { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null || q.title == null }
        if (c == null) {
            // no Android session: the unit's own Bluetooth service, when it has one, is the source
            _state.value = if (q.available && (q.connected || q.title != null)) NowPlaying(
                title = q.title, artist = q.artist, album = q.album, art = artFor(q.title, q.artist, null),
                playing = if (SystemClock.uptimeMillis() - tapAt < 3000) _state.value.playing else q.playing,
                positionMs = q.positionMs, durationMs = q.durationMs, positionAt = q.positionAt,
                source = "Bluetooth", hasSession = q.title != null, needsAccess = false,
            ) else NowPlaying(hasSession = false, needsAccess = !hasAccess() && !q.available)
            return
        }
        val md = c.metadata
        val ps = c.playbackState
        // the dash's own library session: its clock is its own, the vendor's progress is the phone's
        val ours = c.packageName == ctx.packageName
        _state.value = NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art = artFor(md?.getString(MediaMetadata.METADATA_KEY_TITLE), md?.getString(MediaMetadata.METADATA_KEY_ARTIST), md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)),
            // the session's own state, held to what the last tap asked for a moment while the
            // sources catch up; the vendor's moving progress only decides when there is no state
            playing = if (SystemClock.uptimeMillis() - tapAt < 3000) _state.value.playing
                else if (ps != null) ps.state == PlaybackState.STATE_PLAYING else q.playing,
            positionMs = if (!ours && q.available && q.durationMs > 0) q.positionMs else ps?.position ?: 0,
            durationMs = if (!ours && q.available && q.durationMs > 0) q.durationMs else md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionAt = if (!ours && q.available && q.durationMs > 0) q.positionAt else SystemClock.uptimeMillis(),
            source = if (ours) "Library" else label(c.packageName),
            hasSession = true,
        )
    }

    /** For the diagnostics page: every session the system shows us, and its state. */
    /**
     * Which session the transport buttons are wired to, and whether it is going anywhere. A dead
     * session keeps its last title and still calls itself playing, so the position is the only
     * honest sign: read this twice, and if the number has not moved, nothing is listening.
     */
    fun describeControl(): String {
        val c = controller ?: return "nothing attached"
        val pos = runCatching { c.playbackState?.position }.getOrNull()
        return "${c.packageName} · ${c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "no title"} · at ${pos ?: -1}ms" +
            (if (live == null) "  ·  transport withheld" else "")
    }

    fun describeSessions(): String {
        if (!hasAccess()) return "notification access NOT granted"
        val sessions = try { msm.getActiveSessions(listener) } catch (e: SecurityException) { return "no access (security)" }
        if (sessions.isEmpty()) return "access ok · no media sessions at all"
        return sessions.joinToString(" | ") { c ->
            val st = when (c.playbackState?.state) { PlaybackState.STATE_PLAYING -> "playing"; PlaybackState.STATE_PAUSED -> "paused"; null -> "no state"; else -> "state ${c.playbackState?.state}" }
            "${c.packageName} ($st, ${c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "no title"})"
        }
    }

    private fun label(pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { "Bluetooth audio" }

    /** The controller worth sending transport to: one that at least knows what it is playing. */
    private val live: MediaController? get() = controller?.takeIf { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null || qf.state.value.title == null }

    private var tapAt = 0L

    fun playPause() {
        // flip at once; the sources confirm within a second or two
        tapAt = SystemClock.uptimeMillis()
        _state.value = _state.value.copy(playing = !_state.value.playing)
        val c = live
        when {
            c != null -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
            qf.state.value.available -> qf.playPause()
            else -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }
    fun next() { val c = live; when { c != null -> c.transportControls.skipToNext(); qf.state.value.available -> qf.next(); else -> key(KeyEvent.KEYCODE_MEDIA_NEXT) } }
    fun previous() { val c = live; when { c != null -> c.transportControls.skipToPrevious(); qf.state.value.available -> qf.previous(); else -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS) } }

    private fun key(code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
