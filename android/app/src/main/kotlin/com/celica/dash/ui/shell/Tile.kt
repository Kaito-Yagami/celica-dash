package com.celica.dash.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label

/**
 * The panel every module sits in: hairline border and two chrome corner ticks (top-left,
 * bottom-right) - the hardware-panel look from the mockup. `alert` turns the ticks red.
 */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    alert: Boolean = false,
    fill: Boolean = true,          // false when a SurfaceView inside paints its own background
    content: @Composable BoxScope.() -> Unit,
) {
    val tick = if (alert) Ink.red else Ink.chrome
    Box(
        modifier
            .then(if (fill) Modifier.background(Ink.s1) else Modifier)
            .border(1.dp, Ink.line)
            .drawBehind {
                val t = 10.dp.toPx()
                val w = 2.dp.toPx()
                drawLine(tick, Offset(0f, w / 2), Offset(t, w / 2), w)
                drawLine(tick, Offset(w / 2, 0f), Offset(w / 2, t), w)
                drawLine(tick, Offset(size.width - t, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                drawLine(tick, Offset(size.width - w / 2, size.height - t), Offset(size.width - w / 2, size.height), w)
            },
        content = content,
    )
}

/** The small mono caption in a tile's top-left corner; the leading word is red. */
@Composable
fun BoxScope.TileLabel(lead: String, text: String, modifier: Modifier = Modifier) {
    Row(modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 10.dp)) {
        Text(lead, style = Label.copy(color = Ink.red, fontWeight = FontWeight.SemiBold))
        Text("  $text", style = Label)
    }
}

/** Right-aligned counterpart, fainter. */
@Composable
fun BoxScope.TileNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Label.copy(color = Ink.txFaint, letterSpacing = androidx.compose.ui.unit.TextUnit(0.14f, androidx.compose.ui.unit.TextUnitType.Em)),
        modifier = modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 10.dp),
    )
}

/** A hairline. */
@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = Ink.line) {
    Box(modifier.background(color))
}
