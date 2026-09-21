package com.celica.dash.ui.lights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.lights.StarLights
import com.celica.dash.ui.shell.Chip
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.shell.Rule
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label

/** The colours on offer: the dash's own red first, then the controller's eight. */
private val SWATCHES = listOf(
    "DASH" to Color(0xFFD8433C), "RED" to Color(0xFFFF0000), "GREEN" to Color(0xFF00FF00), "BLUE" to Color(0xFF0000FF),
    "WHITE" to Color(0xFFFFFFFF), "YELLOW" to Color(0xFFFFFF00), "ICE" to Color(0xFF00FFFF), "PINK" to Color(0xFFFF60A0), "MAGENTA" to Color(0xFFFF00FF),
)

/**
 * The star ceiling's controls, over the dash: power, the swatches, brightness, the built-in
 * patterns and their speed. The last line is what the link is doing, in words, since a light
 * that does not answer is a Bluetooth question before it is a lights question.
 */
@Composable
fun StarsSheet(lights: StarLights, onClose: () -> Unit) {
    val st by lights.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Ink.bg.copy(alpha = 0.92f)).clickable(onClick = onClose)) {
        Tile(Modifier.align(Alignment.Center).fillMaxWidth(0.82f).fillMaxHeight(0.86f).pointerInput(Unit) { detectTapGestures {} }) {
            TileLabel(if (st.connected) "●" else "○", "STARS")
            TileNote("TAP OUTSIDE TO CLOSE")
            Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 44.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlatButton(if (st.on) "On" else "Off", hot = st.on, enabled = st.connected) { if (st.on) lights.off() else lights.on() }
                    Text(st.status.uppercase(), style = Label)
                }
                Rule()
                Text("COLOUR", style = Label)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SWATCHES.forEach { (name, c) ->
                        val cur = Color(st.red, st.green, st.blue)
                        val on = st.mode == 0 && cur == c
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(52.dp).background(c).border(if (on) 2.dp else 1.dp, if (on) Ink.tx else Ink.line2).clickable(enabled = st.connected) {
                                lights.color((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
                            })
                            Text(name, style = Label.copy(color = if (on) Ink.tx else Ink.txFaint), modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Text("BRIGHTNESS · ${st.brightness}", style = Label)
                Bar(st.brightness / 100f, enabled = st.connected) { lights.brightness((it * 100).toInt()) }
                Rule()
                Text("PATTERN", style = Label)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("STILL", color = if (st.mode == 0) Ink.tx else Ink.txDim, fill = if (st.mode == 0) Ink.redDim else Ink.s2, modifier = Modifier.clickable(enabled = st.connected) { lights.color(st.red, st.green, st.blue) })
                    (1..12).forEach { n ->
                        Chip("$n", color = if (st.mode == n) Ink.tx else Ink.txDim, fill = if (st.mode == n) Ink.redDim else Ink.s2, modifier = Modifier.clickable(enabled = st.connected) { lights.mode(n) })
                    }
                }
                Text("SPEED · ${st.speed}", style = Label)
                Bar(st.speed / 100f, enabled = st.connected) { lights.speed((it * 100).toInt()) }
                Box(Modifier.weight(1f))
                Text(lights.describe(), style = Data.copy(color = Ink.txFaint), maxLines = 3)
            }
        }
    }
}

/** A flat bar: tap or drag anywhere along it. */
@Composable
private fun Bar(value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { p -> onChange((p.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(onDragEnd = {}) { change, _ -> onChange((change.position.x / size.width).coerceIn(0f, 1f)) }
            }
            .drawBehind {
                val y = size.height / 2
                drawRect(Ink.line2, Offset(0f, y - 2.dp.toPx()), Size(size.width, 4.dp.toPx()))
                drawRect(if (enabled) Ink.red else Ink.txFaint, Offset(0f, y - 2.dp.toPx()), Size(size.width * value, 4.dp.toPx()))
                drawCircle(if (enabled) Ink.tx else Ink.txFaint, radius = 7.dp.toPx(), center = Offset(size.width * value, y))
            }
    )
}
