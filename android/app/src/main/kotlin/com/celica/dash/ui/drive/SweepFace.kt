package com.celica.dash.ui.drive

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Units
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.obd.VehicleState
import com.celica.dash.obd.gearOf
import com.celica.dash.ui.theme.Display
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Mono
import com.celica.dash.ui.theme.NumeralUnit
import kotlin.math.cos
import kotlin.math.sin

private const val START = 150f          // where the dial begins, degrees clockwise from east
private const val SWEEP = 240f

/**
 * One dial, the whole screen. The arc carries its own light, the needle is swept rather than
 * stepped, and the colour walks from white through amber to red as the revs climb. The outer
 * ring is the shift light: it fills from the lift point and strobes at the stop.
 *
 * The cam changeover is the event of the face - two rings go out, the dial takes the colour, and
 * a chip slides in - because on this engine it is the thing worth knowing.
 */
@Composable
fun SweepFace(v: VehicleState, modifier: Modifier = Modifier) {

    // the changeover: counted, so each one runs its own animation
    var changes by remember { mutableIntStateOf(0) }
    LaunchedEffect(v.lift) { if (v.lift) changes++ }
    val flare = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(changes) { if (changes > 0) { flare.snapTo(0f); flare.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) } }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // the sides of a wide screen, which a round dial can never fill: the two readings that
        // move while driving, as columns that rise and fall with them
        SideBar("Coolant", v.coolantC?.let { (it / 120f).coerceIn(0f, 1f) } ?: 0f, v.coolantC?.let { "$it°" } ?: "—",
            if (v.coolantC != null && !v.warm) Ink.chrome else Ink.ember, Modifier.width(96.dp).fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
        ModernRevDial(v, Modifier.fillMaxHeight())

        // ---- the numbers in the middle ------------------------------------------------------
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
            Text(
                Units.speedInt(v.speedMph) ?: "—",
                fontFamily = if (v.speedMph == null) Mono else Display, fontWeight = FontWeight.Black, fontSize = 116.sp,
                color = if (v.lift) Ink.red else Ink.tx, letterSpacing = (-5).sp,
            )
            Text(Units.speedUnit, style = Label.copy(fontSize = 11.sp, letterSpacing = 5.sp), modifier = Modifier.offset(y = (-2).dp))
            // the gear and the revs are the same size, weight and baseline - they read as a pair
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 24.dp)) {
                val gear = gearOf(v.rpm, v.speedMph)
                Text(
                    gear?.toString() ?: "–",
                    fontFamily = Mono, fontSize = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                    color = if (gear == null) Ink.txFaint else Ink.chrome,
                )
                Text("GEAR", style = Label.copy(fontSize = 10.sp), modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                Text(
                    v.rpm?.let { "%,d".format(it) } ?: "—",
                    fontFamily = Mono, fontSize = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                    color = if (v.lift) Ink.ember else Ink.txDim,
                    modifier = Modifier.padding(start = 26.dp),
                )
                Text("RPM", style = Label.copy(fontSize = 10.sp), modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
            }
        }

        }
        SideBar("Throttle", (v.throttlePct ?: 0) / 100f, v.throttlePct?.let { "$it%" } ?: "—", Ink.tx, Modifier.width(96.dp).fillMaxHeight())
    }
    Box(modifier) {
        // ---- the chip that slides in on a changeover ------------------------------------------
        val fl = flare.value
        if (v.lift || fl < 1f) {
            val slide = (1f - (fl * 3f).coerceAtMost(1f)) * 40f
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 4.dp)
                    .offset(x = slide.dp)
                    .background(if (v.lift) Ink.red else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                if (CarSpec.hasLift) Text(
                    if (CarSpec.preset == CarSpec.PRESET_2ZZ) "VVTL-i" else CarSpec.liftWord.uppercase(), fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 15.sp,
                    color = if (v.lift) Ink.tx else Ink.red, letterSpacing = 4.sp,
                )
            }
        }
    }
}

/** White up to the lift point, warming through amber, red once the cams are over. */
private fun revColour(f: Float, lift: Boolean): Color {
    if (lift) return Ink.red
    val liftF = if (CarSpec.hasLift) VehicleState.LIFT_RPM / VehicleState.REDLINE.toFloat() else 1f
    val k = (f / liftF).coerceIn(0f, 1f)
    return lerpColour(Ink.tx, Ink.amber, (k - 0.55f).coerceIn(0f, 1f) / 0.45f)
}

private fun lerpColour(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

/** An arc on a circle of this radius about the centre, since every ring here wants the same. */
private fun DrawScope.drawDialArc(radius: Float, stroke: Float, start: Float, sweep: Float, colour: Color) {
    if (sweep <= 0f) return
    drawArc(
        colour, start, sweep, false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(stroke, cap = StrokeCap.Butt),
    )
}

/**
 * One reading as a column that fills from the bottom - the throttle dancing with the right foot
 * is half of why this face is worth looking at.
 */
@Composable
private fun SideBar(label: String, fraction: Float, value: String, tint: Color, modifier: Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium), label = label)
    Column(modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = Label.copy(fontSize = 10.sp))
        Canvas(Modifier.width(12.dp).weight(1f).padding(vertical = 10.dp)) {
            val r = 6.dp.toPx()
            drawRoundRect(Ink.s2, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
            val h = size.height * f
            if (h > 1f) {
                drawRoundRect(
                    tint.copy(alpha = 0.22f), topLeft = Offset(-3.dp.toPx(), size.height - h),
                    size = Size(size.width + 6.dp.toPx(), h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                )
                drawRoundRect(
                    tint, topLeft = Offset(0f, size.height - h), size = Size(size.width, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                )
            }
        }
        Text(value, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}


/**
 * The Sweep dial on its own: the lit arc with its own glow, the shift ring outside it, the ticks
 * and thousands, and the blade riding the rim so nothing crosses the middle.
 *
 * Lifted out of SweepFace so the Analogue face can show the very same instrument rather than a
 * second drawing of it that would drift away from this one.
 */
@Composable
fun ModernRevDial(v: VehicleState, modifier: Modifier = Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val rpm = (v.rpm ?: 0).toFloat()
    // the needle is sprung, so a reading every fifth of a second still reads as a sweep
    val shown by animateFloatAsState(rpm, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "needle")
    val f = (shown / VehicleState.REDLINE).coerceIn(0f, 1f)
    val liftF = VehicleState.LIFT_RPM / VehicleState.REDLINE.toFloat()

    var changes by remember { mutableIntStateOf(0) }
    LaunchedEffect(v.lift) { if (v.lift) changes++ }
    val flare = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(changes) { if (changes > 0) { flare.snapTo(0f); flare.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) } }

    val t = rememberInfiniteTransition(label = "sweep")
    val strobe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(160, easing = LinearEasing), RepeatMode.Reverse), label = "strobe")

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
            val w = size.minDimension
            val ringGap = w * 0.045f
            val arcStroke = w * 0.055f
            val outer = w * 0.5f

            // ---- the shift ring, outside everything ---------------------------------------
            val shiftR = outer - ringGap * 0.35f
            drawDialArc(shiftR, 3.dp.toPx(), START, SWEEP, Ink.line.copy(alpha = 0.7f))
            if (f > liftF) {
                val on = (f - liftF) / (1 - liftF)
                val hot = f > 0.94f
                val c = if (hot) Ink.tx.copy(alpha = 0.55f + 0.45f * strobe) else Ink.ember
                drawDialArc(shiftR, if (hot) 6.dp.toPx() else 4.dp.toPx(), START + SWEEP * liftF, SWEEP * (1 - liftF) * on, c)
            }

            // ---- the track, and the lit arc over it ---------------------------------------
            val r = outer - ringGap - arcStroke / 2
            drawDialArc(r, arcStroke, START, SWEEP, Ink.s2)
            drawDialArc(r, 1.dp.toPx(), START + SWEEP * liftF, SWEEP * (1 - liftF), Ink.redDim.copy(alpha = 0.9f))
            if (f > 0.002f) {
                // drawn three times, widening and fading, so the arc carries its own glow
                val col = revColour(f, v.lift)
                drawDialArc(r, arcStroke * 2.1f, START, SWEEP * f, col.copy(alpha = 0.10f))
                drawDialArc(r, arcStroke * 1.4f, START, SWEEP * f, col.copy(alpha = 0.16f))
                drawDialArc(r, arcStroke, START, SWEEP * f, col)
            }

            // ---- ticks: a hairline every 500, a longer one and a number every 1000 ---------
            for (k in 0..16) {
                val at = (k * 500f / VehicleState.REDLINE).coerceAtMost(1f)
                if (k * 500 > VehicleState.REDLINE) break
                val a = Math.toRadians((START + SWEEP * at).toDouble())
                val major = k % 2 == 0
                val r0 = r - arcStroke / 2 - 4.dp.toPx()
                val r1 = r0 - (if (major) 13 else 7).dp.toPx()
                val tint = if (k * 500 >= VehicleState.LIFT_RPM) Ink.red.copy(alpha = 0.75f) else Ink.txFaint
                drawLine(
                    tint,
                    center + Offset((cos(a) * r0).toFloat(), (sin(a) * r0).toFloat()),
                    center + Offset((cos(a) * r1).toFloat(), (sin(a) * r1).toFloat()),
                    if (major) 2.dp.toPx() else 1.dp.toPx(), StrokeCap.Round,
                )
            }

            // ---- the rings that go out when the cams change over ----------------------------
            val fl = flare.value
            if (fl < 1f) {
                for (d in 0..1) {
                    val p = (fl - d * 0.18f).coerceIn(0f, 1f)
                    if (p <= 0f || p >= 1f) continue
                    drawCircle(Ink.red.copy(alpha = (1f - p) * 0.5f), r * (0.5f + 0.75f * p), center, style = Stroke((7f * (1f - p)).dp.toPx()))
                }
            }

            // ---- the needle: a tapered blade with a trail behind it -------------------------
            val ang = START + SWEEP * f
            val tail = 26f * (0.35f + 0.65f * f)
            if (f > 0.01f) drawDialArc(r - arcStroke * 1.3f, 3.dp.toPx(), (ang - tail).coerceAtLeast(START), tail.coerceAtMost(SWEEP * f), revColour(f, v.lift).copy(alpha = 0.35f))
            rotate(ang, center) {
                // a blade riding the rim rather than a spindle across the face, so the numbers
                // in the middle are never crossed
                val tip = r - arcStroke * 0.62f
                val heel = r - arcStroke * 2.9f
                val p = Path().apply {
                    moveTo(center.x + tip, center.y)
                    lineTo(center.x + heel, center.y - w * 0.016f)
                    lineTo(center.x + heel, center.y + w * 0.016f)
                    close()
                }
                drawPath(p, revColour(f, v.lift))
                drawPath(p, Ink.bg.copy(alpha = 0.35f), style = Stroke(1.dp.toPx()))
            }
        }

        // the thousands, set just inside the ticks
        Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
            val w = size.minDimension
            val r = w * 0.5f - w * 0.045f - w * 0.055f / 2 - 26.dp.toPx()
            for (k in 1..8) {
                val at = k * 1000f / VehicleState.REDLINE
                val a = Math.toRadians((START + SWEEP * at).toDouble())
                val p = center + Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat())
                val lay = measurer.measure(
                    androidx.compose.ui.text.AnnotatedString(k.toString()),
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (k * 1000 >= VehicleState.LIFT_RPM) Ink.red else Ink.txDim,
                    ),
                )
                drawText(lay, topLeft = Offset(p.x - lay.size.width / 2f, p.y - lay.size.height / 2f))
            }
        }
    }
}
