package com.celica.dash.ui.home

import com.celica.dash.data.Units
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Display
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.weather.Weather
import kotlin.math.roundToInt

/**
 * The weather module: big temperature, conditions and wind in mono, an overnight low with an ice
 * warning when it matters, and how old the forecast is - because it usually is.
 */
@Composable
fun BoxScope.WeatherTile(w: Weather?) {
    if (w == null) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("WEATHER", style = Label)
            Text("no forecast yet · needs a hotspot once", style = Data.copy(color = Ink.txFaint), modifier = Modifier.padding(top = 6.dp))
        }
        return
    }
    TileNote(age(w.fetchedAt))
    Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(Units.temp(w.tempC).roundToInt().toString(), fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 52.sp, color = Ink.tx, letterSpacing = (-2).sp, lineHeight = 52.sp)
                Text(Units.tempUnit, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Ink.txDim, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
            }
            Row(Modifier.padding(top = 6.dp)) {
                Text(w.description.uppercase(), style = Label.copy(color = Ink.tx, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp))
                Text(" · WIND ${Units.wind(w.windMph)} ${Units.windUnit} ${w.windDir}", style = Label.copy(letterSpacing = 0.1.sp))
            }
            if (w.minNextC != null) {
                val ice = w.iceLikely
                Text(
                    "${Units.temp(w.minNextC).roundToInt()} ${Units.tempUnit} BY ${w.minAtHour ?: "—"}" + if (ice) " — ICE LIKELY" else "",
                    style = Label.copy(color = if (ice) Ink.red else Ink.txDim, letterSpacing = 0.1.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        WeatherGlyph(w.code, Modifier.size(46.dp).padding(bottom = 4.dp))
    }
}

private fun age(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60000
    return when {
        m < 2 -> "JUST NOW"
        m < 60 -> "$m MIN AGO"
        m < 60 * 36 -> "${m / 60} H AGO"
        else -> "${m / 1440} D AGO"
    }
}

/** A line-drawn glyph in the site's idiom: a cloud outline, with rain, sun or snow as strokes. */
@Composable
private fun WeatherGlyph(code: Int, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = Stroke(width = 1.3.dp.toPx())
        val cloudy = code >= 2
        if (code <= 1) {
            // sun
            drawCircle(Ink.chrome, radius = s * 0.2f, center = center, style = stroke)
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                val r0 = s * 0.28f; val r1 = s * 0.4f
                drawLine(Ink.chrome, center + Offset((Math.cos(a) * r0).toFloat(), (Math.sin(a) * r0).toFloat()), center + Offset((Math.cos(a) * r1).toFloat(), (Math.sin(a) * r1).toFloat()), stroke.width)
            }
        }
        if (cloudy) {
            val p = Path().apply {
                moveTo(s * 0.28f, s * 0.62f)
                cubicTo(s * 0.05f, s * 0.62f, s * 0.08f, s * 0.32f, s * 0.32f, s * 0.36f)
                cubicTo(s * 0.38f, s * 0.12f, s * 0.72f, s * 0.14f, s * 0.74f, s * 0.38f)
                cubicTo(s * 0.95f, s * 0.36f, s * 0.95f, s * 0.62f, s * 0.72f, s * 0.62f)
                close()
            }
            drawPath(p, Ink.chrome, style = stroke)
        }
        if (code in 51..67 || code in 80..82 || code >= 95) {
            for (i in 0 until 3) {
                val x = s * (0.36f + 0.16f * i)
                drawLine(Ink.chrome, Offset(x, s * 0.72f), Offset(x - s * 0.05f, s * 0.9f), stroke.width)
            }
        }
        if (code in 71..77 || code in 85..86) {
            for (i in 0 until 3) {
                val x = s * (0.36f + 0.16f * i)
                drawCircle(Ink.chrome, radius = s * 0.03f, center = Offset(x, s * 0.8f))
            }
        }
    }
}
