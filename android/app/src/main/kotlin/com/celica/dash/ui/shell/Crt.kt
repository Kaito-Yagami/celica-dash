package com.celica.dash.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The whole dash behind the glass of a tube: a shadow mask, and nothing else. The picture is
 * the same across the whole screen - no glare caught in the glass, no darkening into the
 * corners - because on a small dash display both read as a smear over the instruments rather
 * than as a tube. The lines alone carry the look.
 *
 * Deliberately still. A real tube rolls and flickers, and both are easy to draw, but this is a
 * screen glanced at from a moving car - a dash that will not sit still is worse than one that is
 * merely plain, and an animation over the whole window costs a redraw of everything every frame on
 * a unit with little to spare. So the mask is painted once and left alone.
 *
 * Nothing here takes touches: it is only ever drawn over the top.
 */
@Composable
fun CrtOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // the scan lines, the part your eye actually reads as a tube
        val pitch = 3.dp.toPx().coerceAtLeast(2f)
        var y = 0f
        while (y < size.height) {
            drawRect(Color.Black.copy(alpha = 0.26f), Offset(0f, y), Size(size.width, pitch * 0.42f))
            y += pitch
        }
        // and the grille behind them, far fainter - enough to grain the picture, not to stripe it
        val slot = 3.dp.toPx().coerceAtLeast(2f)
        var x = 0f
        while (x < size.width) {
            drawRect(Color.Black.copy(alpha = 0.04f), Offset(x, 0f), Size(slot * 0.34f, size.height))
            x += slot
        }
    }
}
