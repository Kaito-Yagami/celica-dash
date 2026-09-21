package com.celica.dash.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.routing.NavState
import com.celica.dash.routing.fmtDist
import com.celica.dash.routing.turnWords
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.panelWidth
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Numeral
import com.celica.dash.ui.theme.Title
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val etaFmt get() = com.celica.dash.data.Units.date("HH:mm")

/**
 * The turn card, top centre while a route is set: the next turn as an arrow with the distance
 * to it and the road, then the whole trip underneath - miles, minutes, arrival - with the way
 * out and the voice toggle at the end.
 */
@Composable
fun BoxScope.NavCard(st: NavState, voice: Boolean, onVoice: () -> Unit, onEnd: () -> Unit) {
    val t = st.turn
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = 34.dp).background(Ink.bg.copy(alpha = 0.92f))
            .drawBehind { drawRect(Ink.red, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(46.dp)) { if (t != null) turnGlyph(t.sign, t.exit) }
        Column(Modifier.width(panelWidth(400.dp, 0.7f))) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (t != null) fmtDist(st.toTurnM) else "—", style = Numeral.copy(fontSize = 26.sp))
                if (st.rerouting) Text("  REROUTING…", style = Label.copy(color = Ink.red), modifier = Modifier.padding(bottom = 5.dp))
                else if (st.offRoute) Text("  OFF ROUTE", style = Label.copy(color = Ink.red), modifier = Modifier.padding(bottom = 5.dp))
            }
            Text(if (t != null) turnWords(t) else "Follow the road", style = Title.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${fmtDist(st.remainingM)}  ·  ${fmtMinutes(st.remainingMs)}  ·  ETA ${etaFmt.format(Date(System.currentTimeMillis() + st.remainingMs))}  ·  ${st.destName.uppercase()}",
                style = Data, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
            )
        }
        MapButton(if (voice) Glyph.SPEAKER else Glyph.MUTED, hot = false, size = 36, onClick = onVoice)
        MapButton(Glyph.CLOSE, size = 36, onClick = onEnd)
    }
}

private fun fmtMinutes(ms: Long): String {
    val m = (ms / 60000).toInt()
    return if (m >= 60) "${m / 60} H ${m % 60} MIN" else "$m MIN"
}

/** The arrow for a GraphHopper turn sign, drawn in the card's ink with a red head. */
fun DrawScope.turnGlyph(sign: Int, exit: Int) {
    val w = 3.dp.toPx()
    val s = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val c = center; val r = size.minDimension / 2
    fun arrow(angleDeg: Float, bend: Float) {
        // a stem up from the bottom, then a leg at the angle, red head on the end
        val stemTop = Offset(c.x, c.y + r * 0.15f)
        drawLine(Ink.tx, Offset(c.x, c.y + r * 0.95f), stemTop, w, StrokeCap.Round)
        val a = Math.toRadians(angleDeg.toDouble())
        val len = r * bend
        val end = Offset(stemTop.x + (sin(a) * len).toFloat(), stemTop.y - (cos(a) * len).toFloat())
        drawLine(Ink.tx, stemTop, end, w, StrokeCap.Round)
        val hl = r * 0.42f
        val la = a + Math.toRadians(150.0); val ra = a - Math.toRadians(150.0)
        val head = Path().apply {
            moveTo(end.x, end.y)
            lineTo(end.x + (sin(la) * hl).toFloat(), end.y - (cos(la) * hl).toFloat())
            lineTo(end.x + (sin(ra) * hl).toFloat(), end.y - (cos(ra) * hl).toFloat())
            close()
        }
        drawPath(head, Ink.red)
    }
    when (sign) {
        0 -> arrow(0f, 1.0f)
        -1 -> arrow(-40f, 0.95f); 1 -> arrow(40f, 0.95f)
        -2 -> arrow(-90f, 0.8f); 2 -> arrow(90f, 0.8f)
        -3 -> arrow(-135f, 0.85f); 3 -> arrow(135f, 0.85f)
        -7 -> { drawLine(Ink.txFaint, Offset(c.x, c.y + r * 0.6f), Offset(c.x + r * 0.5f, c.y - r * 0.7f), w, StrokeCap.Round); arrow(-30f, 0.9f) }
        7 -> { drawLine(Ink.txFaint, Offset(c.x, c.y + r * 0.6f), Offset(c.x - r * 0.5f, c.y - r * 0.7f), w, StrokeCap.Round); arrow(30f, 0.9f) }
        -8, 8, -98 -> {
            val p = Path().apply { moveTo(c.x + r * 0.35f, c.y + r * 0.9f); lineTo(c.x + r * 0.35f, c.y - r * 0.2f); arcTo(androidx.compose.ui.geometry.Rect(c.x - r * 0.35f, c.y - r * 0.9f, c.x + r * 0.35f, c.y - r * 0.2f + r * 0.7f), 0f, -180f, false); lineTo(c.x - r * 0.35f, c.y + r * 0.3f) }
            drawPath(p, Ink.tx, style = s)
            drawPath(Path().apply { moveTo(c.x - r * 0.35f, c.y + r * 0.7f); lineTo(c.x - r * 0.7f, c.y + r * 0.2f); lineTo(c.x, c.y + r * 0.2f); close() }, Ink.red)
        }
        6, -6 -> {
            drawCircle(Ink.tx, r * 0.42f, c, style = Stroke(w * 0.8f))
            drawLine(Ink.tx, Offset(c.x, c.y + r * 0.95f), Offset(c.x, c.y + r * 0.42f), w, StrokeCap.Round)
            // the exit as a red arrow at its angle round the circle: 1 -> right, 2 -> ahead, 3 -> left
            val ang = when (exit) { 1 -> 90.0; 2 -> 0.0; 3 -> -90.0; 4 -> -135.0; else -> 0.0 }
            rotate(ang.toFloat(), c) {
                drawLine(Ink.tx, Offset(c.x, c.y - r * 0.42f), Offset(c.x, c.y - r * 0.85f), w, StrokeCap.Round)
                drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x - r * 0.28f, c.y - r * 0.6f); lineTo(c.x + r * 0.28f, c.y - r * 0.6f); close() }, Ink.red)
            }
        }
        4 -> {
            // the flag
            drawLine(Ink.tx, Offset(c.x - r * 0.5f, c.y + r * 0.9f), Offset(c.x - r * 0.5f, c.y - r * 0.9f), w, StrokeCap.Round)
            val cell = r * 0.3f
            for (i in 0 until 4) for (j in 0 until 3) {
                if ((i + j) % 2 == 0) drawRect(Ink.red, Offset(c.x - r * 0.5f + i * cell, c.y - r * 0.9f + j * cell), Size(cell, cell))
                else drawRect(Ink.tx, Offset(c.x - r * 0.5f + i * cell, c.y - r * 0.9f + j * cell), Size(cell, cell))
            }
        }
        else -> arrow(0f, 1.0f)
    }
}
