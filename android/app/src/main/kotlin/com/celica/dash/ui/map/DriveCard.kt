package com.celica.dash.ui.map

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Units
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.data.Drive
import com.celica.dash.location.bearingDeg
import com.celica.dash.location.haversineM
import com.celica.dash.obd.VehicleState
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Display
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.LabelWide
import com.celica.dash.ui.theme.Numeral
import com.celica.dash.ui.theme.Title
import com.celica.dash.ui.theme.panelWidth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val cardFmt get() = Units.date("EEE d MMM · HH:mm")

/** What a drive came to, worked out from its own samples. */
class DriveReport(
    val miles: Double, val durationMs: Long, val maxMph: Float, val avgMph: Float,
    val liftMs: Long, val liftCount: Int, val peakG: Float, val maxRpm: Int,
) {
    companion object {
        fun of(drive: Drive): DriveReport {
            val pts = drive.pts
            var lift = 0L
            var changes = 0
            var wasLift = false
            var peak = 0f
            var maxRpm = 0
            for (i in pts.indices) {
                val p = pts[i]
                maxRpm = maxOf(maxRpm, p.rpm)
                val on = CarSpec.liftRpm?.let { p.rpm >= it } ?: false
                if (on && i > 0) lift += (p.t - pts[i - 1].t).coerceAtMost(3000)
                if (on && !wasLift) changes++
                wasLift = on
                // how hard it was turned: the heading rate against the speed, as the dash does live
                if (i > 1) {
                    val dt = (p.t - pts[i - 1].t) / 1000.0
                    if (dt in 0.05..3.0 && p.mph > 5f) {
                        var d = p.brg - pts[i - 1].brg
                        while (d > 180f) d -= 360f
                        while (d < -180f) d += 360f
                        val g = (p.mph * 0.44704 * Math.toRadians(abs(d).toDouble()) / dt / 9.81).toFloat()
                        if (g < 2.5f) peak = maxOf(peak, g)
                    }
                }
            }
            val s = drive.summary
            val hours = s.durationMs / 3_600_000.0
            return DriveReport(
                miles = s.miles, durationMs = s.durationMs, maxMph = s.maxMph,
                avgMph = if (hours > 0) (s.miles / hours).toFloat() else 0f,
                liftMs = lift, liftCount = changes, peakG = peak, maxRpm = maxRpm,
            )
        }
    }
}

/**
 * The card that comes up when a recording is stopped: what the drive amounted to, in the numbers
 * worth bragging about, with the way back to watching it again.
 */
@Composable
fun BoxScope.DriveCard(drive: Drive, ghost: com.celica.dash.data.GhostResult?, onReplay: () -> Unit, onClose: () -> Unit) {
    val r = remember(drive) { DriveReport.of(drive) }
    Box(Modifier.matchParentSize().background(Ink.bg.copy(alpha = 0.72f)).clickable(onClick = onClose))
    Column(
        Modifier.align(Alignment.Center).width(panelWidth(520.dp, 0.82f)).background(Ink.s1)
            .drawBehind { drawRect(Ink.red, Offset.Zero, Size(size.width, 3.dp.toPx())) }
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(24.dp),
    ) {
        Text(if (ghost != null) "CHASE DONE" else "DRIVE DONE", style = LabelWide.copy(color = Ink.red, fontWeight = FontWeight.SemiBold))
        Text(cardFmt.format(Date(drive.summary.startMs)), style = Title.copy(fontSize = 17.sp), modifier = Modifier.padding(top = 4.dp))

        // how the chase went, when there was one
        ghost?.let { g ->
            val tint = if (g.beat) Ink.go else Ink.red
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp).background(tint.copy(alpha = 0.16f))
                    .drawBehind { drawRect(tint, Offset.Zero, Size(3.dp.toPx(), size.height)) }
                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (g.beat) "BEAT ${g.name.uppercase()} BY" else "DOWN ON ${g.name.uppercase()} BY",
                    style = Label.copy(color = Ink.tx, fontSize = 11.sp),
                )
                Text(
                    "%.1f".format(abs(g.byMs) / 1000f), style = Numeral.copy(fontSize = 24.sp, color = tint),
                    modifier = Modifier.padding(start = 12.dp),
                )
                Text(" S", style = Label.copy(fontSize = 10.sp))
                if (g.beat) Text("  ·  IT IS THE GHOST NOW", style = Label.copy(fontSize = 10.sp))
            }
        }

        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.Bottom) {
            Text(Units.dist1(r.miles * 1609.344), style = Numeral.copy(fontSize = 58.sp, fontFamily = Display, fontWeight = FontWeight.Black))
            Text("  ${Units.distWord.uppercase()}", style = Label.copy(fontSize = 12.sp), modifier = Modifier.padding(bottom = 10.dp))
            Box(Modifier.width(26.dp))
            Text(fmtDuration(r.durationMs), style = Numeral.copy(fontSize = 34.sp), modifier = Modifier.padding(bottom = 4.dp))
        }

        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Fact("Top speed", Units.speedInt(r.maxMph) ?: "—", Units.speedUnit)
            Fact("Average", Units.speedInt(r.avgMph) ?: "—", Units.speedUnit)
            Fact("Highest", if (r.maxRpm > 0) "%,d".format(r.maxRpm) else "—", "RPM")
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (CarSpec.hasLift) Fact("On the cams", if (r.liftMs > 0) fmtDuration(r.liftMs) else "—", "")
            if (CarSpec.hasLift) Fact("Changeovers", r.liftCount.toString(), "×")
            Fact("Hardest corner", "%.2f".format(r.peakG), "G")
        }

        Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlatButton("Watch it back", hot = true, onClick = onReplay)
            FlatButton("Done", onClick = onClose)
        }
    }
}

@Composable
private fun Fact(label: String, value: String, unit: String) {
    Column {
        Text(label.uppercase(), style = Label.copy(fontSize = 10.sp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
            Text(value, style = Numeral.copy(fontSize = 28.sp))
            if (unit.isNotEmpty()) Text(" $unit", style = Data.copy(fontSize = 11.sp), modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

/**
 * The bar that says how the run is going against the one being chased - ahead in green, behind
 * in red, and greyed while the car has wandered off the old line.
 */
@Composable
fun GhostBar(g: com.celica.dash.data.GhostState, modifier: Modifier = Modifier) {
    val behind = g.deltaMs > 0
    val tint = when {
        !g.onLine -> Ink.txFaint
        behind -> Ink.red
        else -> Ink.go
    }
    Row(
        modifier.background(Ink.bg.copy(alpha = 0.92f))
            .drawBehind { drawRect(tint, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(if (g.running) "CHASING" else "GHOST ARMED", style = Label.copy(fontSize = 9.sp))
            Text(g.name.uppercase(), style = Label.copy(color = Ink.tx, fontSize = 11.sp))
        }
        if (g.running) {
            Text(
                (if (behind) "+" else "\u2212") + "%.1f".format(abs(g.deltaMs) / 1000f),
                style = Numeral.copy(fontSize = 30.sp, color = tint),
                modifier = Modifier.padding(start = 18.dp),
            )
            Text("S", style = Label.copy(fontSize = 10.sp), modifier = Modifier.padding(start = 4.dp))
        } else {
            Text("DRIVE TO ITS START", style = Label.copy(fontSize = 11.sp), modifier = Modifier.padding(start = 20.dp))
        }
    }
}
