package com.celica.dash.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.media.LibraryState
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.shell.Rule
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Title

/**
 * The songs on the unit, as a list over the media page: artist and title, the one playing in
 * red, and the whole lot on shuffle from one button. Tap a row to play from there.
 */
@Composable
fun LibrarySheet(lib: LibraryState, onPlay: (Int) -> Unit, onShuffleAll: () -> Unit, onShuffle: (Boolean) -> Unit, onStop: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink.bg.copy(alpha = 0.92f)).clickable(onClick = onClose)) {
        Tile(Modifier.align(Alignment.Center).fillMaxWidth(0.82f).fillMaxHeight(0.86f).pointerInput(Unit) { detectTapGestures {} }) {
            TileLabel("LIBRARY", if (lib.tracks.isEmpty()) "on the unit" else "${lib.tracks.size} tracks on the unit")
            TileNote("TAP OUTSIDE TO CLOSE")
            Column(Modifier.fillMaxSize().padding(top = 38.dp)) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlatButton("Shuffle all", hot = true, enabled = lib.tracks.isNotEmpty(), onClick = onShuffleAll)
                    FlatButton(if (lib.shuffle) "Shuffle · on" else "Shuffle · off", enabled = lib.tracks.isNotEmpty()) { onShuffle(!lib.shuffle) }
                    if (lib.index >= 0) FlatButton("Stop", onClick = onStop)
                    lib.current?.let { Text("${it.artist ?: ""} · ${it.title}".trimStart(' ', '·'), style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp)) }
                }
                Rule()
                when {
                    lib.needsStorage -> {
                        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                        Column(Modifier.padding(18.dp)) {
                            Text("The dash may not read the unit's music yet.", style = Body)
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FlatButton("Allow", hot = true) {
                                    activity?.let { androidx.core.app.ActivityCompat.requestPermissions(it, com.celica.dash.ui.setup.Permissions.runtime(com.celica.dash.ui.setup.Permissions.Kind.STORAGE), 9) }
                                }
                                // refused twice, Android stops asking; the app's own page is the way back
                                FlatButton("App settings") {
                                    activity?.let { runCatching { it.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + it.packageName))) } }
                                }
                            }
                            Text("Music and audio - allow. If Android no longer asks, App settings › Permissions.", style = Body.copy(color = Ink.txFaint), modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    !lib.loaded -> Text("Reading the library…", style = Body, modifier = Modifier.padding(18.dp))
                    lib.tracks.isEmpty() -> Text("Nothing in the unit's Music folder yet. RUN ME on the PC copies songs over; a USB stick's files count too once copied there.", style = Body, modifier = Modifier.padding(18.dp))
                    else -> {
                        val listState = rememberLazyListState()
                        val playingTrack = lib.order.getOrNull(lib.index) ?: -1
                        LaunchedEffect(Unit) { if (playingTrack >= 0) listState.scrollToItem((playingTrack - 3).coerceAtLeast(0)) }
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            itemsIndexed(lib.tracks, key = { _, t -> t.id }) { i, t ->
                                val on = i == playingTrack
                                Row(
                                    Modifier.fillMaxWidth().background(if (on) Ink.redDim.copy(alpha = 0.25f) else Ink.s1).clickable { onPlay(i) }.padding(horizontal = 18.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (on) "●" else "${i + 1}", style = Data.copy(color = if (on) Ink.red else Ink.txFaint), modifier = Modifier.width(44.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(t.title, style = Title.copy(fontSize = 15.sp, color = if (on) Ink.tx else Ink.tx), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(t.artist, t.album).joinToString("  ·  ").ifBlank { "—" }, style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(clock(t.durationMs), style = Data)
                                }
                                Rule()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
