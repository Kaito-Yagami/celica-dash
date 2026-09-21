package com.celica.dash.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Mono

/** The glyphs on the map's buttons, drawn rather than shipped as a font. */
enum class Glyph { PLUS, MINUS, RECENTRE, REC, STOP, LIST, PLAY, PAUSE, CLOSE, PIN, TRASH, PREV, NEXT, SEARCH, SPEAKER, MUTED, GHOST }

/**
 * A soft square button for the map: translucent ink, rounded, a hairline. `hot` fills it red.
 * Buttons come in a column or a row with a little air between them.
 */
@Composable
fun MapButton(glyph: Glyph? = null, text: String? = null, hot: Boolean = false, enabled: Boolean = true, size: Int = 50, onClick: () -> Unit) {
    val shape = RoundedCornerShape((size * 0.36f).dp)
    Box(
        Modifier.size(size.dp).clip(shape)
            .background(if (hot) Ink.red.copy(alpha = 0.92f) else Ink.bg.copy(alpha = 0.82f))
            .border(1.dp, if (hot) Ink.red else Ink.line2, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) Text(text, fontFamily = Mono, fontSize = (size * 0.3f).sp, fontWeight = FontWeight.SemiBold, color = Ink.tx, letterSpacing = 0.5.sp)
        if (glyph != null) Canvas(Modifier.size((size * 0.42f).dp)) { draw(glyph, if (hot) Ink.tx else Ink.tx, if (hot) Ink.tx else Ink.red) }
    }
}

/** A round button for the transport: ink on a dark disc, or a red disc for the main one. */
@Composable
fun RoundButton(glyph: Glyph, size: Int = 64, hot: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (hot) Ink.red else Ink.s2)
            .border(1.dp, if (hot) Ink.red else Ink.line2, androidx.compose.foundation.shape.CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size((size * 0.36f).dp)) { draw(glyph, Ink.tx, if (hot) Ink.tx else Ink.red) }
    }
}

private fun DrawScope.draw(g: Glyph, ink: Color, red: Color) {
    val w = 1.8.dp.toPx()
    val s = Stroke(w, cap = StrokeCap.Round)
    val c = center; val r = size.minDimension / 2
    when (g) {
        Glyph.PLUS -> { drawLine(ink, Offset(c.x - r, c.y), Offset(c.x + r, c.y), w, StrokeCap.Round); drawLine(ink, Offset(c.x, c.y - r), Offset(c.x, c.y + r), w, StrokeCap.Round) }
        Glyph.MINUS -> drawLine(ink, Offset(c.x - r, c.y), Offset(c.x + r, c.y), w, StrokeCap.Round)
        Glyph.RECENTRE -> {
            drawCircle(ink, r * 0.62f, c, style = s)
            drawCircle(red, r * 0.16f, c)
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                drawLine(ink, Offset(c.x + dx * r * 0.62f, c.y + dy * r * 0.62f), Offset(c.x + dx * r, c.y + dy * r), w, StrokeCap.Round)
            }
        }
        Glyph.REC -> { drawCircle(ink, r * 0.95f, c, style = s); drawCircle(red, r * 0.5f, c) }
        Glyph.STOP -> { drawCircle(ink, r * 0.95f, c, style = s); drawRect(ink, Offset(c.x - r * 0.38f, c.y - r * 0.38f), Size(r * 0.76f, r * 0.76f)) }
        Glyph.LIST -> for (i in -1..1) drawLine(ink, Offset(c.x - r, c.y + i * r * 0.62f), Offset(c.x + r, c.y + i * r * 0.62f), w, StrokeCap.Round)
        Glyph.PLAY -> drawPath(Path().apply { moveTo(c.x - r * 0.6f, c.y - r * 0.85f); lineTo(c.x + r * 0.9f, c.y); lineTo(c.x - r * 0.6f, c.y + r * 0.85f); close() }, ink)
        Glyph.PREV -> {
            drawRect(ink, Offset(c.x - r, c.y - r * 0.8f), Size(w * 1.1f, r * 1.6f))
            drawPath(Path().apply { moveTo(c.x + r * 0.05f, c.y - r * 0.8f); lineTo(c.x - r * 0.75f, c.y); lineTo(c.x + r * 0.05f, c.y + r * 0.8f); close() }, ink)
            drawPath(Path().apply { moveTo(c.x + r * 0.95f, c.y - r * 0.8f); lineTo(c.x + r * 0.15f, c.y); lineTo(c.x + r * 0.95f, c.y + r * 0.8f); close() }, ink)
        }
        Glyph.NEXT -> {
            drawRect(ink, Offset(c.x + r - w * 1.1f, c.y - r * 0.8f), Size(w * 1.1f, r * 1.6f))
            drawPath(Path().apply { moveTo(c.x - r * 0.05f, c.y - r * 0.8f); lineTo(c.x + r * 0.75f, c.y); lineTo(c.x - r * 0.05f, c.y + r * 0.8f); close() }, ink)
            drawPath(Path().apply { moveTo(c.x - r * 0.95f, c.y - r * 0.8f); lineTo(c.x - r * 0.15f, c.y); lineTo(c.x - r * 0.95f, c.y + r * 0.8f); close() }, ink)
        }
        Glyph.PAUSE -> { drawRect(ink, Offset(c.x - r * 0.7f, c.y - r * 0.8f), Size(r * 0.45f, r * 1.6f)); drawRect(ink, Offset(c.x + r * 0.25f, c.y - r * 0.8f), Size(r * 0.45f, r * 1.6f)) }
        Glyph.CLOSE -> { drawLine(ink, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Offset(c.x + r * 0.8f, c.y + r * 0.8f), w, StrokeCap.Round); drawLine(ink, Offset(c.x + r * 0.8f, c.y - r * 0.8f), Offset(c.x - r * 0.8f, c.y + r * 0.8f), w, StrokeCap.Round) }
        Glyph.PIN -> {
            drawLine(ink, Offset(c.x, c.y - r * 0.2f), Offset(c.x, c.y + r), w, StrokeCap.Round)
            drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r * 0.62f, c.y - r * 0.4f); lineTo(c.x, c.y + r * 0.2f); lineTo(c.x - r * 0.62f, c.y - r * 0.4f); close() }, red)
        }
        Glyph.SPEAKER, Glyph.MUTED -> {
            // the cone, then either two waves coming off it or a cross through them
            drawPath(Path().apply {
                moveTo(c.x - r * 0.75f, c.y - r * 0.3f); lineTo(c.x - r * 0.35f, c.y - r * 0.3f); lineTo(c.x + r * 0.05f, c.y - r * 0.85f)
                lineTo(c.x + r * 0.05f, c.y + r * 0.85f); lineTo(c.x - r * 0.35f, c.y + r * 0.3f); lineTo(c.x - r * 0.75f, c.y + r * 0.3f); close()
            }, ink)
            if (g == Glyph.SPEAKER) {
                for (k in listOf(0.45f, 0.8f)) drawArc(red, -55f, 110f, useCenter = false, topLeft = Offset(c.x + r * 0.05f - r * k, c.y - r * k), size = Size(r * k * 2, r * k * 2), style = s)
            } else {
                drawLine(red, Offset(c.x + r * 0.35f, c.y - r * 0.4f), Offset(c.x + r * 0.9f, c.y + r * 0.4f), w, StrokeCap.Round)
                drawLine(red, Offset(c.x + r * 0.9f, c.y - r * 0.4f), Offset(c.x + r * 0.35f, c.y + r * 0.4f), w, StrokeCap.Round)
            }
        }
        Glyph.GHOST -> {
            // a domed head with a wavy hem, and two eyes
            val p = Path().apply {
                moveTo(c.x - r * 0.72f, c.y + r * 0.85f)
                lineTo(c.x - r * 0.72f, c.y - r * 0.1f)
                cubicTo(c.x - r * 0.72f, c.y - r * 1.1f, c.x + r * 0.72f, c.y - r * 1.1f, c.x + r * 0.72f, c.y - r * 0.1f)
                lineTo(c.x + r * 0.72f, c.y + r * 0.85f)
                lineTo(c.x + r * 0.36f, c.y + r * 0.5f)
                lineTo(c.x, c.y + r * 0.85f)
                lineTo(c.x - r * 0.36f, c.y + r * 0.5f)
                close()
            }
            drawPath(p, ink, style = s)
            drawCircle(red, r * 0.13f, Offset(c.x - r * 0.28f, c.y - r * 0.18f))
            drawCircle(red, r * 0.13f, Offset(c.x + r * 0.28f, c.y - r * 0.18f))
        }
        Glyph.SEARCH -> {
            drawCircle(ink, r * 0.62f, Offset(c.x - r * 0.18f, c.y - r * 0.18f), style = s)
            drawLine(red, Offset(c.x + r * 0.28f, c.y + r * 0.28f), Offset(c.x + r * 0.9f, c.y + r * 0.9f), w * 1.3f, StrokeCap.Round)
        }
        Glyph.TRASH -> {
            drawLine(ink, Offset(c.x - r, c.y - r * 0.6f), Offset(c.x + r, c.y - r * 0.6f), w, StrokeCap.Round)
            drawPath(Path().apply { moveTo(c.x - r * 0.7f, c.y - r * 0.6f); lineTo(c.x - r * 0.5f, c.y + r); lineTo(c.x + r * 0.5f, c.y + r); lineTo(c.x + r * 0.7f, c.y - r * 0.6f) }, ink, style = s)
            drawLine(ink, Offset(c.x - r * 0.3f, c.y - r), Offset(c.x + r * 0.3f, c.y - r), w, StrokeCap.Round)
        }
    }
}
