package com.celica.dash.ui.media

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.media.NowPlaying
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.map.Glyph
import com.celica.dash.ui.map.RoundButton
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Numeral
import com.celica.dash.ui.theme.Title
import kotlinx.coroutines.delay

/**
 * The media section: the art large on the left, the words and a big round transport on the
 * right, and the way to the unit's own Bluetooth music app for units that only stream while
 * it is running.
 */
@Composable
fun MediaScreen(vm: DashViewModel) {
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val lib by vm.library.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var shelf by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!lib.loaded) vm.library.load() }
    Tile(Modifier.fillMaxSize().padding(20.dp, 14.dp, 20.dp, 14.dp)) {
        TileLabel(if (np.playing) "●" else "○", np.source ?: "Bluetooth")
        TileNote(if (np.hasSession) "SESSION" else "NO SESSION")
        // the songs on the unit itself: music with no phone in the car
        FlatButton(if (lib.tracks.isEmpty()) "Library" else "Library · ${lib.tracks.size}", modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 18.dp)) { shelf = true }
        Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 46.dp, bottom = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            // art
            Box(Modifier.size(300.dp).border(1.dp, Ink.line2).background(Ink.s2)) {
                val art = np.art
                if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize().padding(34.dp).border(1.dp, Ink.redDim), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(34.dp).background(Ink.red, CircleShape))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 44.dp), verticalArrangement = Arrangement.Center) {
                if (np.needsAccess) {
                    Text("NOW PLAYING NEEDS NOTIFICATION ACCESS", style = Label.copy(color = Ink.tx))
                    FlatButton("Allow · once", hot = true, modifier = Modifier.padding(top = 12.dp)) {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } else {
                    Text(np.title ?: if (np.hasSession) "—" else "Nothing playing", style = Numeral.copy(fontSize = 30.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(np.artist ?: if (np.hasSession) "" else "Play something on the phone", style = Title.copy(fontSize = 17.sp, color = Ink.txDim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    np.album?.let { Text(it, style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)) }
                    Progress(np, Modifier.fillMaxWidth().padding(top = 18.dp))
                }
                Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Glyph.PREV, size = 68) { vm.media.previous() }
                    RoundButton(if (np.playing) Glyph.PAUSE else Glyph.PLAY, size = 92, hot = true) { vm.media.playPause() }
                    RoundButton(Glyph.NEXT, size = 68) { vm.media.next() }
                }
            }
        }
    }
    if (shelf) LibrarySheet(
        lib,
        onPlay = { vm.library.play(it) }, onShuffleAll = { vm.library.shuffleAll() }, onShuffle = { vm.library.setShuffle(it) },
        onStop = { vm.library.stop() }, onClose = { shelf = false },
    )
}

/** The red hairline that keeps moving between position updates. */
@Composable
private fun Progress(np: NowPlaying, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(np.playing) { while (np.playing) { now = SystemClock.uptimeMillis(); delay(500) } }
    val pos = if (np.playing) np.positionMs + (now - np.positionAt) else np.positionMs
    val f = if (np.durationMs > 0) (pos.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Ink.line2).drawBehind {
            if (f > 0f) drawRect(Ink.red, Offset.Zero, Size(size.width * f, size.height))
        })
        if (np.durationMs > 0) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(pos), style = Data); Text(clock(np.durationMs), style = Data)
        }
    }
}

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
