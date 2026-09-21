package com.celica.dash.ui.drive

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Units
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.location.Fix
import com.celica.dash.obd.Session
import com.celica.dash.obd.VehicleState
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Display
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Mono
import com.celica.dash.ui.theme.Numeral
import com.celica.dash.ui.theme.NumeralUnit
import kotlin.math.cos
import kotlin.math.sin

/** The faces the Drive screen can wear. The first is the one the dash has always had. */
enum class DriveFace(val title: String, val note: String) {
    CLASSIC("Classic", "the tach, the engine column and the map"),
    SWEEP("Sweep", "one enormous tach, a shift light across the top, and the cams announced"),
    DATA("Numbers", "no dials at all - every reading as a figure, for those who prefer them"),
    TRACK("Track", "the session: how fast, how hard, how long on the second lobe"),
    THEATRE("Theatre", "the car on a road that runs to the horizon - for the passenger, not the driver"),
    ANALOG("Analogue", "two real instruments under glass - alloy bezels, printed dials, needles on springs"),
}

// ---------------------------------------------------------------------------------------------
// Numbers: no dials, just the readings, large
// ---------------------------------------------------------------------------------------------

@Composable
fun DataFace(v: VehicleState, s: Session, modifier: Modifier = Modifier) {
    val cells = listOf(
        Triple("Speed", Units.speedInt(v.speedMph), Units.speedUnit),
        Triple("Engine", v.rpm?.toString(), "RPM"),
        Triple("Throttle", v.throttlePct?.toString(), "%"),
        Triple("Coolant", v.coolantC?.let { Units.temp(it).toString() }, Units.tempUnit),
        Triple("Intake air", v.intakeC?.let { Units.temp(it).toString() }, Units.tempUnit),
        Triple("Short trim", v.shortTrimPct?.let { "%+.1f".format(it) }, "%"),
        Triple("Long trim", v.longTrimPct?.let { "%+.1f".format(it) }, "%"),
        Triple(Units.econWord, Units.econ(v.mpg), Units.econUnit),
        Triple(if (CarSpec.hasLift) "On the cams" else "Past the redline", if (s.liftMs > 0) fmtClock(s.liftMs) else null, "MIN"),
    ).let { if (CarSpec.hasLift) it else it.dropLast(1) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        for (row in 0 until 3) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (col in 0 until 3) {
                    val (label, value, unit) = cells[row * 3 + col]
                    Cell(label, value, unit, Modifier.weight(1f).fillMaxHeight(), rule = col < 2, under = row < 2)
                }
            }
        }
    }
}

@Composable
private fun Cell(label: String, value: String?, unit: String, modifier: Modifier, rule: Boolean, under: Boolean) {
    Column(
        modifier.drawBehind {
            val w = 1.dp.toPx()
            if (rule) drawLine(Ink.line, Offset(size.width - w / 2, 8f), Offset(size.width - w / 2, size.height - 8f), w)
            if (under) drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
        }.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label.uppercase(), style = Label.copy(fontSize = 11.sp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
            if (value == null) Text("—", style = Numeral.copy(fontSize = 42.sp, fontFamily = Mono, color = Ink.txFaint))
            else Text(value, style = Numeral.copy(fontSize = 42.sp))
            Text("  $unit", style = NumeralUnit.copy(fontSize = 12.sp), modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Track: what the drive has amounted to, and how hard it is being taken right now
// ---------------------------------------------------------------------------------------------

@Composable
fun TrackFace(v: VehicleState, s: Session, fix: Fix?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text(
                Units.speedInt(v.speedMph ?: fix?.speedMph) ?: "—",
                fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 116.sp,
                color = if (v.lift) Ink.red else Ink.tx, letterSpacing = (-4).sp,
            )
            Text(Units.speedUnit, style = NumeralUnit.copy(letterSpacing = 6.sp))
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                Stat(if (Units.metric) "0-100" else "0-60", s.best0to60Ms?.let { "%.1f".format(it / 1000f) } ?: "—", "S")
                Stat("Top", s.maxMph.takeIf { it > 0 }?.let { Units.speedInt(it) } ?: "—", Units.speedUnit)
                Stat("Distance", Units.dist1(s.miles * 1609.344), Units.distUnit)
                Stat("Average", Units.econ(s.avgMpg) ?: "—", Units.econUnit)
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                if (CarSpec.hasLift) Stat("On the cams", fmtClock(s.liftMs), "MIN")
                if (CarSpec.hasLift) Stat("Changes", s.liftCount.toString(), "×")
                Stat("Peak", "%.2f".format(s.peakG), "G")
            }
        }
        Box(Modifier.width(210.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            GMeter(s.lateralG, s.peakG, Modifier.fillMaxWidth().aspectRatio(1f))
            Text("CORNERING", style = Label, modifier = Modifier.align(Alignment.TopCenter))
        }
        RevBar(v, Modifier.width(54.dp).fillMaxHeight())
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String) {
    Column {
        Text(label.uppercase(), style = Label.copy(fontSize = 11.sp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Numeral.copy(fontSize = 30.sp))
            Text(" $unit", style = NumeralUnit.copy(fontSize = 11.sp), modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

/** A dot that swings out as the car is turned, with a ring at the hardest it has been taken. */
@Composable
private fun GMeter(g: Float, peak: Float, modifier: Modifier) {
    val shown by animateFloatAsState(g, tween(220), label = "g")
    Canvas(modifier) {
        val r = size.minDimension / 2 - 6.dp.toPx()
        for (k in 1..3) {
            drawCircle(if (k == 3) Ink.redDim else Ink.line2, r * k / 3f, center, style = Stroke(1.dp.toPx()))
        }
        drawLine(Ink.line2, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1.dp.toPx())
        drawLine(Ink.line2, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1.dp.toPx())
        if (peak > 0f) drawCircle(Ink.redDim, (peak / 1.0f).coerceAtMost(1f) * r, center, style = Stroke(1.5.dp.toPx()))
        val x = (shown / 1.0f).coerceIn(-1f, 1f) * r
        drawCircle(if (shown > 0.6f) Ink.red else Ink.tx, 7.dp.toPx(), Offset(center.x + x, center.y))
    }
}

/** The revs as a column, since the tach is not the centrepiece on this face. */
@Composable
private fun RevBar(v: VehicleState, modifier: Modifier) {
    val f = ((v.rpm ?: 0) / VehicleState.REDLINE.toFloat()).coerceIn(0f, 1f)
    Canvas(modifier.padding(vertical = 10.dp)) {
        val liftAt = if (CarSpec.hasLift) VehicleState.LIFT_RPM.toFloat() / VehicleState.REDLINE else 1f
        drawRect(Ink.line2, Offset(0f, size.height * (1 - liftAt)), Size(size.width, size.height * liftAt))
        if (CarSpec.hasLift) drawRect(Ink.redDim.copy(alpha = 0.5f), Offset.Zero, Size(size.width, size.height * (1 - liftAt)))
        drawRect(if (v.lift) Ink.red else Ink.tx, Offset(0f, size.height * (1 - f)), Size(size.width, size.height * f))
    }
}

/** Minutes and seconds, for a stretch of time that is usually short. */
fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
