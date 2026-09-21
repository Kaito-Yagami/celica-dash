package com.celica.dash.ui.media

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.media.NowPlaying
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Mono
import com.celica.dash.ui.theme.Title
import kotlinx.coroutines.delay
import android.os.SystemClock

/**
 * The now-playing module for the home screen: art, title, artist, a progress hairline and
 * three transport buttons. When notification access is missing it says so and opens the
 * settings page on tap - that is a one-time thing.
 */
@Composable
fun BoxScope.MediaTile(np: NowPlaying, onPlayPause: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit) {
    val ctx = LocalContext.current
    TileLabel(if (np.playing) "●" else "○", np.source ?: "Bluetooth")

    if (np.needsAccess) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp).clickable {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("NOW PLAYING NEEDS", style = Label)
            Text("NOTIFICATION ACCESS", style = Label.copy(color = Ink.tx))
            Text("tap to allow · once", style = Body, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // art: it takes whatever room is left over, so the transport below is never squeezed off
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 34.dp), contentAlignment = Alignment.Center) {
            val side = minOf(140.dp, maxWidth - 24.dp, maxHeight)
            if (side >= 40.dp) Box(Modifier.size(side).border(1.dp, Ink.line2).background(Ink.s2)) {
                val art = np.art
                if (art != null) {
                    Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize().padding(16.dp).border(1.dp, Ink.redDim), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(18.dp).background(Ink.red, androidx.compose.foundation.shape.CircleShape))
                    }
                }
            }
        }
        // words
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp, bottom = 12.dp)) {
            Text(np.title ?: if (np.hasSession) "—" else "Nothing playing", style = Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(np.artist ?: if (np.hasSession) "" else "Connect a phone over Bluetooth", style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Progress(np, Modifier.fillMaxWidth().padding(top = 10.dp).height(2.dp))
        }
        // transport
        Row(
            Modifier.fillMaxWidth().height(44.dp).drawBehind {
                val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, w / 2), Offset(size.width, w / 2), w)
            }
        ) {
            listOf("◀◀" to onPrev, (if (np.playing) "▌▌" else "▶") to onPlayPause, "▶▶" to onNext).forEachIndexed { i, (t, act) ->
                Box(
                    Modifier.weight(1f).fillMaxSize().clickable(onClick = act).drawBehind {
                        if (i < 2) { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t, fontFamily = Mono, fontSize = if (i == 1) 15.sp else 13.sp, color = if (i == 1) Ink.red else Ink.tx, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** A red hairline that keeps moving between position updates. */
@Composable
private fun Progress(np: NowPlaying, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(np.playing) { while (np.playing) { now = SystemClock.uptimeMillis(); delay(500) } }
    val pos = if (np.playing) np.positionMs + (now - np.positionAt) else np.positionMs
    val f = if (np.durationMs > 0) (pos.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
    Box(modifier.background(Ink.line2).drawBehind {
        if (f > 0f) drawRect(Ink.red, Offset.Zero, Size(size.width * f, size.height))
    })
}
