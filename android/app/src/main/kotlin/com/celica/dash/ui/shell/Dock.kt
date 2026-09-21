package com.celica.dash.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.LabelWide

/** The screens reachable from the dock, in dock order. */
enum class Section(val label: String) {
    HOME("Home"), DRIVE("Drive"), MAP("Map"), MEDIA("Media"), PHONE("Phone"), GARAGE("Garage"), MANUAL("Manual");
}

/**
 * Bottom navigation: seven equal cells, the active one with a red rule and glow along its top
 * edge, and the hazard stripe running under the lot.
 */
@Composable
fun Dock(current: Section, onSelect: (Section) -> Unit, modifier: Modifier = Modifier, sections: List<Section> = Section.entries) {
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Ink.s1)
            .drawBehind {
                val w = 1.dp.toPx()
                drawLine(Ink.line, Offset(0f, w / 2), Offset(size.width, w / 2), w)
                // hazard stripe: red bars at 45 degrees, kept quiet
                val h = 3.dp.toPx()
                val step = 20.dp.toPx()
                clipRect(top = size.height - h) {
                    var x = -size.height
                    while (x < size.width + size.height) {
                        val p = Path().apply {
                            moveTo(x, size.height); lineTo(x + step / 2, size.height)
                            lineTo(x + step / 2 + h, size.height - h); lineTo(x + h, size.height - h); close()
                        }
                        drawPath(p, Ink.red.copy(alpha = 0.45f))
                        x += step
                    }
                }
            }
    ) {
        sections.forEachIndexed { i, s ->
            val on = s == current
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelect(s) }
                    .drawBehind {
                        val w = 1.dp.toPx()
                        if (i < Section.entries.size - 1) {
                            drawLine(Ink.line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                        }
                        if (on) {
                            drawRect(Ink.glow, Offset.Zero, Size(size.width, 8.dp.toPx()))
                            drawLine(Ink.red, Offset(0f, w), Offset(size.width, w), 2.dp.toPx())
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(s.label.uppercase(), style = LabelWide.copy(color = if (on) Ink.tx else Ink.txDim))
            }
        }
    }
}
