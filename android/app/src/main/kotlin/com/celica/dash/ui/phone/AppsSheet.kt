package com.celica.dash.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.apps.AppEntry
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label

/**
 * Every other app on the unit as a grid, over the dash. Tap opens one - or, when picking,
 * hands it back. The dash is the launcher, so this is the app drawer.
 */
@Composable
fun AppsSheet(apps: List<AppEntry>, title: String, note: String, highlight: String?, onPick: (AppEntry) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink.bg.copy(alpha = 0.92f)).clickable(onClick = onClose)) {
        Tile(Modifier.align(Alignment.Center).fillMaxWidth(0.82f).fillMaxHeight(0.84f).pointerInput(Unit) { detectTapGestures {} }) {
            TileLabel(title, note)
            TileNote("TAP OUTSIDE TO CLOSE")
            if (apps.isEmpty()) {
                Text("No other apps can be seen.", style = Body, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(GridCells.Fixed(7), Modifier.fillMaxSize().padding(top = 40.dp, start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                    items(apps, key = { it.pkg }) { a ->
                        val on = a.pkg == highlight
                        Column(
                            Modifier.padding(6.dp).background(if (on) Ink.redDim.copy(alpha = 0.35f) else Ink.s2).clickable { onPick(a) }.padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val icon = a.icon
                            if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(40.dp))
                            else Box(Modifier.size(40.dp).background(Ink.line2))
                            Text(a.label.uppercase(), style = Label.copy(color = if (on) Ink.tx else Ink.txDim, fontSize = 9.sp, letterSpacing = 0.08.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
