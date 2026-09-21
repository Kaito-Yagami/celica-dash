package com.celica.dash.ui.drive

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Units
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.obd.ObdStatus
import com.celica.dash.obd.VehicleState
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Display
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.panelWidth
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Mono
import com.celica.dash.ui.theme.Numeral
import com.celica.dash.ui.theme.NumeralUnit
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * The engine screen: a big tach with the lift zone, a LIFT lamp, the temperatures and trims
 * that say how the engine is doing, and a small map following the car. The engine side needs
 * the dongle; without one it says so and offers the demo. Fault codes live in Settings › Car.
 */
@Composable
fun DriveScreen(vm: DashViewModel) {
    val v by vm.vehicle.collectAsStateWithLifecycle()
    val status by vm.obdStatus.collectAsStateWithLifecycle()
    val info by vm.obd.info.collectAsStateWithLifecycle()
    val fix by vm.fix.collectAsStateWithLifecycle()
    val dtcs by vm.obd.dtcs.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val faceName by vm.driveFace.collectAsStateWithLifecycle()
    val backdrop by vm.backdrop.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val analogDial by vm.analogDial.collectAsStateWithLifecycle()
    val face = DriveFace.entries.firstOrNull { it.name == faceName } ?: DriveFace.CLASSIC
    val note = when (status) {
        ObdStatus.LIVE -> "ELM327 · ${info ?: "live"}"
        ObdStatus.CONNECTING -> "CONNECTING…"
        ObdStatus.FAILED -> "DONGLE NOT ANSWERING"
        ObdStatus.OFF -> if (v.obdLive) "DEMO" else "NO DONGLE"
    }
    val codes = dtcs

    if (face == DriveFace.CLASSIC) {
        Row(
            Modifier.fillMaxSize().padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Tile(Modifier.weight(1f).fillMaxHeight(), alert = v.lift) {
                TileLabel("TACH", CarSpec.tachLabel)
                TileNote(note)
                Tach(v, Modifier.fillMaxSize().padding(top = 28.dp, bottom = 30.dp))
                // stored codes roll along the bottom edge only when there are any
                if (!codes.isNullOrEmpty()) FaultTicker(codes, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(26.dp))
            }
            Column(Modifier.width(panelWidth(360.dp, 0.42f)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(Modifier.fillMaxWidth().weight(1f)) {
                    TileLabel("ENGINE", "temperatures · trims")
                    Column(Modifier.fillMaxSize().padding(top = 34.dp)) {
                        Gauge("Coolant", v.coolantC?.let { Units.temp(it).toString() }, Units.tempUnit, warm = v.coolantC != null && !v.warm, modifier = Modifier.weight(1f))
                        Gauge("Intake air", v.intakeC?.let { Units.temp(it).toString() }, Units.tempUnit, modifier = Modifier.weight(1f))
                        Gauge("Throttle", v.throttlePct?.toString(), "%", modifier = Modifier.weight(1f))
                        Gauge("Fuel trim", v.shortTrimPct?.let { "%+.1f".format(it) }, "ST", sub = v.longTrimPct?.let { "LT %+.1f".format(it) }, modifier = Modifier.weight(1f))
                    }
                }
                Tile(Modifier.fillMaxWidth().weight(0.72f), fill = false) {
                    com.celica.dash.ui.map.MiniMap(fix, Modifier.fillMaxSize().padding(1.dp))
                    TileLabel("MAP", fix?.let { "${Units.speedInt(it.speedMph)} ${Units.speedUnit}" } ?: "no fix")
                }
            }
        }
        return
    }

    // the other faces take the whole screen; the label and the ticker are the same on each
    Tile(Modifier.fillMaxSize().padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 14.dp), alert = v.lift) {
        TileLabel(face.title.uppercase(), CarSpec.tachLabel)
        TileNote(note)
        when (face) {
            DriveFace.SWEEP -> SweepFace(v, Modifier.fillMaxSize().padding(top = 26.dp, bottom = 10.dp))
            DriveFace.DATA -> DataFace(v, session, Modifier.fillMaxSize().padding(top = 36.dp, bottom = 16.dp))
            DriveFace.THEATRE -> TheatreFace(v, backdrop, Modifier.fillMaxSize().padding(top = 26.dp, bottom = 6.dp))
            DriveFace.ANALOG -> AnalogFace(v, np, analogDial, Modifier.fillMaxSize().padding(top = 30.dp, bottom = 14.dp))
            else -> TrackFace(v, session, fix, Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 18.dp))
        }
        if (!codes.isNullOrEmpty()) FaultTicker(codes, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(26.dp))
    }
}

private val grouped: NumberFormat get() = Units.integers()

/** What the common codes mean on this engine, so the ticker says something useful. */
private val codeWords = mapOf(
    "P0100" to "MAF circuit", "P0101" to "MAF range", "P0110" to "IAT circuit", "P0115" to "coolant sensor", "P0120" to "throttle sensor",
    "P0125" to "coolant slow to warm", "P0128" to "thermostat", "P0130" to "O2 sensor bank 1", "P0135" to "O2 heater bank 1", "P0136" to "O2 sensor 2",
    "P0141" to "O2 heater sensor 2", "P0171" to "running lean", "P0172" to "running rich", "P0300" to "random misfire", "P0301" to "misfire cyl 1",
    "P0302" to "misfire cyl 2", "P0303" to "misfire cyl 3", "P0304" to "misfire cyl 4", "P0325" to "knock sensor", "P0335" to "crank sensor",
    "P0340" to "cam sensor", "P0401" to "EGR flow", "P0420" to "cat efficiency", "P0440" to "evap system", "P0441" to "evap purge flow",
    "P0442" to "evap small leak", "P0446" to "evap vent", "P0455" to "evap large leak", "P0500" to "speed sensor", "P0505" to "idle control",
    "P1346" to "VVT-i timing bank 1", "P1349" to "VVT-i system", "P1354" to "VVT-i timing", "P1656" to "OCV circuit", "P0011" to "cam timing over-advanced",
    "P0012" to "cam timing retarded", "P0016" to "crank/cam correlation",
)

/** Stored codes as one slow line rolling right to left along the tile's bottom edge. */
@Composable
private fun FaultTicker(codes: List<String>, modifier: Modifier) {
    val text = codes.joinToString("     ·     ") { c -> c + (codeWords[c]?.let { "  $it" } ?: "") }.uppercase()
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "ticker")
    val x by t.animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween((text.length * 90).coerceAtLeast(6000), easing = androidx.compose.animation.core.LinearEasing)), label = "x",
    )
    Box(modifier.background(Ink.bg.copy(alpha = 0.8f)).drawBehind { drawRect(Ink.red.copy(alpha = 0.7f), Offset.Zero, Size(3.dp.toPx(), size.height)) }.clipToBounds(), contentAlignment = Alignment.CenterStart) {
        androidx.compose.ui.layout.Layout(content = {
            Text("$text          $text", fontFamily = Mono, fontSize = 12.sp, color = Ink.red, letterSpacing = 1.sp, maxLines = 1, softWrap = false)
        }) { m, c ->
            val p = m.first().measure(androidx.compose.ui.unit.Constraints())
            layout(c.maxWidth, c.maxHeight) { p.placeRelative((-x * p.width / 2).toInt() + 14.dp.roundToPx(), (c.maxHeight - p.height) / 2) }
        }
    }
}

/** The tach: a 240-degree arc, red across the lift zone, a needle, the number in the middle. */
@Composable
private fun Tach(v: VehicleState, modifier: Modifier) {
    val rpm = v.rpm ?: 0
    val f = (rpm / VehicleState.REDLINE.toFloat()).coerceIn(0f, 1f)
    val liftF = VehicleState.LIFT_RPM / VehicleState.REDLINE.toFloat()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
            val start = 150f; val sweep = 240f
            val r = size.minDimension / 2
            val stroke = 10.dp.toPx()
            val rect = Size(size.width - stroke, size.height - stroke)
            val tl = Offset(stroke / 2, stroke / 2)
            drawArc(Ink.line2, start, sweep * liftF.coerceAtMost(1f), false, tl, rect, style = Stroke(stroke))
            if (CarSpec.hasLift) drawArc(Ink.redDim, start + sweep * liftF, sweep * (1 - liftF), false, tl, rect, style = Stroke(stroke))
            if (f > 0f) drawArc(if (v.lift) Ink.red else Ink.tx, start, sweep * f, false, tl, rect, style = Stroke(stroke, cap = StrokeCap.Butt))
            // ticks every 1000
            for (k in 0..8) {
                val a = Math.toRadians((start + sweep * (k * 1000f / VehicleState.REDLINE).coerceAtMost(1f)).toDouble())
                val c = center
                val r0 = r - stroke - 6.dp.toPx(); val r1 = r0 - (if (k % 2 == 0) 14 else 8).dp.toPx()
                drawLine(if (k * 1000 >= VehicleState.LIFT_RPM) Ink.red else Ink.txDim, c + Offset((cos(a) * r0).toFloat(), (sin(a) * r0).toFloat()), c + Offset((cos(a) * r1).toFloat(), (sin(a) * r1).toFloat()), 1.5.dp.toPx())
            }
            // needle
            val a = Math.toRadians((start + sweep * f).toDouble())
            val c = center
            drawLine(if (v.lift) Ink.red else Ink.tx, c + Offset((cos(a) * r * 0.30f).toFloat(), (sin(a) * r * 0.30f).toFloat()), c + Offset((cos(a) * (r - stroke - 4.dp.toPx())).toFloat(), (sin(a) * (r - stroke - 4.dp.toPx())).toFloat()), 2.dp.toPx())
        }
        // sat a little below the middle, so the figure itself lands on the dial's centre; the
        // number carries its own shadow, or the needle shows through the counters of the glyphs
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 30.dp)) {
            Text(
                v.rpm?.let { grouped.format(it) } ?: "—",
                fontFamily = if (v.rpm == null) Mono else Display, fontWeight = FontWeight.Black, fontSize = 64.sp,
                color = if (v.lift) Ink.red else Ink.tx, letterSpacing = (-2).sp,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(Ink.bg, androidx.compose.ui.geometry.Offset.Zero, 26f),
                ),
            )
            Text("RPM", style = NumeralUnit)
            Box(
                Modifier.padding(top = 12.dp).background(if (v.lift) Ink.red else Ink.s2).padding(horizontal = 14.dp, vertical = 5.dp)
            ) { Text("LIFT", fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (v.lift) Ink.tx else Ink.txFaint, letterSpacing = 3.sp) }
            Text((v.speedMph?.toInt()?.toString() ?: "—") + " MPH", style = Data, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

@Composable
private fun Gauge(label: String, value: String?, unit: String, warm: Boolean = false, sub: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 18.dp).drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label.uppercase(), style = Label.copy(fontSize = 12.sp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (value == null) Text("—", style = Numeral.copy(fontSize = 34.sp, fontFamily = Mono, color = Ink.txFaint))
            else Text(value, style = Numeral.copy(fontSize = 34.sp, color = if (warm) Ink.chrome else Ink.tx))
            Text("  $unit", style = NumeralUnit.copy(fontSize = 13.sp), modifier = Modifier.padding(bottom = 5.dp))
            if (sub != null) Text("   $sub", style = NumeralUnit.copy(fontSize = 13.sp), modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}
