package com.celica.dash.ui.drive

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Units
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.media.NowPlaying
import com.celica.dash.obd.VehicleState
import com.celica.dash.obd.gearOf
import com.celica.dash.ui.theme.Display
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Mono
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START = 138f          // where the needle rests, clock-wise from three o'clock
private const val SWEEP = 264f
private const val BANDS = 16
private const val ROWS = 13

/**
 * The retro face: a real tachometer beside the fascia of an old head unit.
 *
 * The dial is drawn in angles - a turned bezel, a face that falls away to black at its edge, a
 * needle on a spring that throws a shadow. Beside it is everything those units did with segments
 * instead of type: the speed in seven-segment digits, a row of legends that light, a segment
 * spectrum, and the track stepping across a bar at the foot.
 *
 * Only the palette is ours. Black, white and the one red - no amber, which is the one thing about
 * those fascias worth leaving behind.
 */
@Composable
fun AnalogFace(v: VehicleState, np: NowPlaying, dial: String = "retro", modifier: Modifier = Modifier) {
    val type = rememberTextMeasurer()
    val rpm by animateFloatAsState(
        (v.rpm ?: 0).toFloat(), spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow), label = "rpm",
    )

    // the spectrum. There is no real one to be had - the music arrives already mixed over the
    // vendor's Bluetooth stack, where nothing this app can reach ever sees a sample - so these are
    // sixteen bands of shaped motion that start and stop with the track, with peaks that hang and
    // fall the way the real ones did. It moves with the music being on, not with the music itself.
    val levels = remember { FloatArray(BANDS) }
    val peaks = remember { FloatArray(BANDS) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(np.playing) {
        while (true) {
            withFrameNanos { n ->
                val t = n / 1_000_000_000f
                for (i in 0 until BANDS) {
                    val want = if (np.playing) shaped(i, t) else 0f
                    levels[i] += (want - levels[i]) * if (want > levels[i]) 0.55f else 0.14f
                    peaks[i] = maxOf(peaks[i] - 0.009f, levels[i])
                }
                tick++
            }
            if (!np.playing && levels.all { it < 0.005f } && peaks.all { it < 0.005f }) break
        }
    }

    Row(modifier) {
        // the rev counter, either the turned instrument or the Sweep face's own, on a square of
        // its own so both get the same room and the fascia takes whatever is left
        // padded before it is squared, so the instrument has air around it rather than running
        // into the top and bottom of the tile
        Box(Modifier.fillMaxHeight().padding(vertical = 12.dp, horizontal = 10.dp).aspectRatio(1f)) {
            if (dial == "modern") {
                ModernRevDial(v, Modifier.fillMaxSize())
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    instrument(center, size.minDimension * 0.47f, type, rpm, v.lift)
                }
            }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            tick.let { }                                  // redrawn as the bands move
            fascia(
                Rect(0f, size.height * 0.035f, size.width, size.height * 0.965f),
                type, v, np, levels, peaks,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// the instrument
// ---------------------------------------------------------------------------------------------

private val FACE_EDGE = Color(0xFF07070A)
private val FACE_MID = Color(0xFF17161B)
private val RING_LIT = Color(0xFFB9BCC2)
private val RING_DARK = Color(0xFF2A2A30)

private fun DrawScope.instrument(c: Offset, r: Float, type: TextMeasurer, value: Float, hot: Boolean) {
    val max = 9000f
    val ring = r * 0.085f
    drawCircle(
        Brush.sweepGradient(
            listOf(RING_DARK, RING_LIT, RING_DARK, Color(0xFF15151A), RING_LIT, RING_DARK), c,
        ),
        radius = r - ring / 2f, center = c, style = Stroke(ring),
    )
    drawCircle(Color.Black.copy(alpha = 0.85f), r - ring, c, style = Stroke(r * 0.02f))

    val fr = r - ring * 1.2f
    drawCircle(
        Brush.radialGradient(
            0f to FACE_MID, 0.62f to Color(0xFF101014), 1f to FACE_EDGE,
            center = c - Offset(0f, fr * 0.2f), radius = fr * 1.25f,
        ),
        radius = fr, center = c,
    )

    val band = fr * 0.955f
    fun arc(from: Float, to: Float, col: Color, w: Float) {
        drawArc(
            col, START + SWEEP * (from / max), SWEEP * ((to - from) / max), false,
            topLeft = Offset(c.x - band, c.y - band), size = Size(band * 2, band * 2), style = Stroke(w),
        )
    }
    if (CarSpec.hasLift) arc(VehicleState.LIFT_RPM.toFloat(), VehicleState.REDLINE.toFloat(), Ink.tx.copy(alpha = 0.45f), fr * 0.032f)
    arc(VehicleState.REDLINE.toFloat(), max, Ink.red, fr * 0.045f)

    val numStyle = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = (r * 0.14f).toSp(), color = Ink.tx,
    )
    for (i in 0..45) {
        val at = i / 45f
        val ang = Math.toRadians((START + SWEEP * at).toDouble())
        val dx = cos(ang).toFloat()
        val dy = sin(ang).toFloat()
        val major = i % 5 == 0
        val outer = fr * 0.90f
        val inner = outer - if (major) fr * 0.13f else fr * 0.065f
        val red = at * max >= VehicleState.REDLINE - 1f
        drawLine(
            if (red) Ink.red else if (major) Ink.tx else Ink.txFaint,
            c + Offset(dx * inner, dy * inner), c + Offset(dx * outer, dy * outer),
            strokeWidth = if (major) r * 0.022f else r * 0.011f,
        )
        if (major) {
            val m = type.measure((at * max / 1000f).toInt().toString(), numStyle)
            val nr = inner - fr * 0.14f
            drawText(
                m, topLeft = c + Offset(dx * nr - m.size.width / 2f, dy * nr - m.size.height / 2f),
                color = if (red) Ink.red else Ink.tx,
            )
        }
    }

    val capStyle = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = (r * 0.082f).toSp(),
        letterSpacing = (r * 0.03f).toSp(), color = Ink.txDim,
    )
    // above the hub, clear of the numerals: the sweep starts low on the left, so the foot of the
    // dial is where the zero sits and there is no room for print there
    val cap = type.measure("RPM x1000", capStyle)
    drawText(cap, topLeft = c + Offset(-cap.size.width / 2f, -fr * 0.34f))

    val a = START + SWEEP * (value / max).coerceIn(0f, 1f)
    rotate(a + 2.5f, c) { needle(c, fr, Color.Black.copy(alpha = 0.55f), r * 0.012f) }
    rotate(a, c) { needle(c, fr, if (hot) Ink.ember else Ink.red, 0f) }

    val hub = fr * 0.125f
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFF6E727A), Color(0xFF26262C), Color(0xFF101014)),
            center = c - Offset(hub * 0.3f, hub * 0.3f), radius = hub * 1.6f,
        ),
        radius = hub, center = c,
    )
    clipPath(Path().apply { addOval(Rect(c - Offset(fr, fr), Size(fr * 2, fr * 2))) }) {
        drawCircle(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.085f), 1f to Color.Transparent,
                center = c - Offset(fr * 0.45f, fr * 0.62f), radius = fr * 1.05f,
            ),
            radius = fr, center = c,
        )
    }
}

private fun DrawScope.needle(c: Offset, fr: Float, col: Color, lift: Float) {
    val len = fr * 0.86f
    val w = fr * 0.032f
    drawPath(
        Path().apply {
            moveTo(c.x + len, c.y + lift)
            lineTo(c.x + fr * 0.10f, c.y - w + lift)
            lineTo(c.x - fr * 0.20f, c.y - w * 1.5f + lift)
            lineTo(c.x - fr * 0.20f, c.y + w * 1.5f + lift)
            lineTo(c.x + fr * 0.10f, c.y + w + lift)
            close()
        },
        col,
    )
}

// ---------------------------------------------------------------------------------------------
// the fascia
// ---------------------------------------------------------------------------------------------

private fun DrawScope.fascia(
    b: Rect, type: TextMeasurer, v: VehicleState, np: NowPlaying,
    levels: FloatArray, peaks: FloatArray,
) {
    val pad = b.height * 0.055f
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF141318), Color(0xFF0A0A0D))),
        b.topLeft, b.size, CornerRadius(b.height * 0.05f),
    )
    drawRoundRect(Ink.line, b.topLeft, b.size, CornerRadius(b.height * 0.05f), style = Stroke(1.5.dp.toPx()))

    val x0 = b.left + pad
    val w = b.width - pad * 2

    // --- the legends that light ------------------------------------------------------------------
    val legStyle = TextStyle(
        fontFamily = Mono, fontSize = (b.height * 0.048f).toSp(),
        letterSpacing = (b.height * 0.010f).toSp(), fontWeight = FontWeight.Medium, color = Ink.tx,
    )
    var lx = x0
    listOf(
        "BT" to np.hasSession,
        "A2DP" to (np.source != null),
        "PLAY" to np.playing,
        "OBD" to (v.rpm != null),
        "LIFT" to v.lift,
    ).forEach { (name, on) ->
        val m = type.measure(name, legStyle)
        drawText(
            m, topLeft = Offset(lx, b.top + pad),
            color = if (!on) Ink.tx.copy(alpha = 0.13f) else if (name == "LIFT") Ink.red else Ink.tx,
        )
        lx += m.size.width + b.height * 0.055f
    }

    // --- the speed, in segments ------------------------------------------------------------------
    val digitsTop = b.top + b.height * 0.155f
    val dh = b.height * 0.29f
    val dw = dh * 0.56f
    val gap = dw * 0.20f
    val mph = (v.speedMph ?: 0f).toInt().coerceIn(0, 999)
    val text = if (v.speedMph == null) "---" else "%3d".format(mph)
    text.forEachIndexed { i, ch ->
        sevenSeg(
            x0 + i * (dw + gap), digitsTop, dw, dh, ch,
            on = if (v.lift) Ink.red else Ink.tx, off = Ink.tx.copy(alpha = 0.055f),
        )
    }
    val unitStyle = legStyle.copy(fontSize = (b.height * 0.065f).toSp(), color = Ink.txDim)
    val afterDigits = x0 + 3 * (dw + gap) - gap + b.height * 0.035f
    val unit = type.measure(Units.speedUnit, unitStyle)
    drawText(unit, topLeft = Offset(afterDigits, digitsTop + dh - unit.size.height))
    // the gear sits on the same line as the unit, in its own segment cell
    val gh = dh * 0.50f
    val gdw = gh * 0.56f
    val gx = b.right - pad - gdw
    val gearM = type.measure("GEAR", unitStyle)
    drawText(gearM, topLeft = Offset(gx - gearM.size.width - b.height * 0.03f, digitsTop + gh - gearM.size.height))
    sevenSeg(
        gx, digitsTop, gdw, gh,
        gearOf(v.rpm, v.speedMph)?.toString()?.first() ?: '-',
        on = Ink.tx, off = Ink.tx.copy(alpha = 0.055f),
    )

    // --- the spectrum, across the whole fascia -------------------------------------------------------
    spectrum(Rect(x0, b.top + b.height * 0.50f, b.right - pad, b.top + b.height * 0.74f), levels, peaks)

    // --- the track, stepping across the foot ---------------------------------------------------------
    val trackY = b.bottom - pad - b.height * 0.145f
    val tStyle = TextStyle(
        fontFamily = Mono, fontSize = (b.height * 0.068f).toSp(), color = Ink.tx,
        letterSpacing = (b.height * 0.008f).toSp(),
    )
    val name = np.title?.let { t -> np.artist?.let { "$t · $it" } ?: t } ?: "NO SOURCE"
    val m = type.measure(name.uppercase(), tStyle, maxLines = 1)
    clipPath(Path().apply { addRect(Rect(x0, trackY - b.height * 0.02f, b.right - pad, trackY + m.size.height.toFloat())) }) {
        drawText(m, topLeft = Offset(x0, trackY), color = if (np.title == null) Ink.txFaint else Ink.tx)
    }

    val barY = b.bottom - pad - b.height * 0.045f
    val cells = 40
    val cw = (w - (cells - 1) * (w * 0.004f)) / cells
    val cg = w * 0.004f
    val at = if (np.durationMs > 0) (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
    for (i in 0 until cells) {
        val lit = i < (at * cells).roundToInt()
        drawRect(
            if (lit) Ink.red else Ink.tx.copy(alpha = 0.08f),
            Offset(x0 + i * (cw + cg), barY), Size(cw, b.height * 0.030f),
        )
    }
}

/** Sixteen bands of segments, with a peak that hangs above each and falls back onto it. */
private fun DrawScope.spectrum(r: Rect, levels: FloatArray, peaks: FloatArray) {
    val bw = r.width / BANDS
    val cw = bw * 0.62f
    val ch = r.height / ROWS
    val bh = ch * 0.58f
    for (i in 0 until BANDS) {
        val x = r.left + i * bw + (bw - cw) / 2f
        val lit = (levels[i] * ROWS).roundToInt()
        val peak = (peaks[i] * ROWS).roundToInt().coerceIn(0, ROWS)
        for (row in 0 until ROWS) {
            val y = r.bottom - (row + 1) * ch + (ch - bh) / 2f
            val hot = row >= ROWS - 4
            drawRect(
                when {
                    row == peak - 1 && peak > lit -> if (hot) Ink.ember else Ink.tx
                    row < lit -> if (hot) Ink.red else Ink.tx.copy(alpha = 0.92f)
                    else -> Ink.tx.copy(alpha = 0.06f)
                },
                Offset(x, y), Size(cw, bh),
            )
        }
    }
}

/**
 * One seven-segment digit, unlit segments left faintly showing - which is what made those displays
 * look like displays rather than like type.
 */
private fun DrawScope.sevenSeg(x: Float, y: Float, w: Float, h: Float, ch: Char, on: Color, off: Color) {
    val t = h * 0.135f
    val g = t * 0.32f
    val lit = when (ch) {
        '0' -> "abcdef"; '1' -> "bc"; '2' -> "abdeg"; '3' -> "abcdg"; '4' -> "bcfg"
        '5' -> "acdfg"; '6' -> "acdefg"; '7' -> "abc"; '8' -> "abcdefg"; '9' -> "abcdfg"
        '-' -> "g"; else -> ""
    }
    val mid = y + h / 2f
    fun hbar(cy: Float): Path {
        val a = x + t * 0.5f + g
        val bx = x + w - t * 0.5f - g
        return Path().apply {
            moveTo(a, cy); lineTo(a + t * 0.5f, cy - t * 0.5f); lineTo(bx - t * 0.5f, cy - t * 0.5f)
            lineTo(bx, cy); lineTo(bx - t * 0.5f, cy + t * 0.5f); lineTo(a + t * 0.5f, cy + t * 0.5f); close()
        }
    }
    fun vbar(cx: Float, y0: Float, y1: Float): Path {
        val a = y0 + g
        val bY = y1 - g
        return Path().apply {
            moveTo(cx, a); lineTo(cx + t * 0.5f, a + t * 0.5f); lineTo(cx + t * 0.5f, bY - t * 0.5f)
            lineTo(cx, bY); lineTo(cx - t * 0.5f, bY - t * 0.5f); lineTo(cx - t * 0.5f, a + t * 0.5f); close()
        }
    }
    listOf(
        'a' to hbar(y + t * 0.5f),
        'g' to hbar(mid),
        'd' to hbar(y + h - t * 0.5f),
        'f' to vbar(x + t * 0.5f, y, mid),
        'b' to vbar(x + w - t * 0.5f, y, mid),
        'e' to vbar(x + t * 0.5f, mid, y + h),
        'c' to vbar(x + w - t * 0.5f, mid, y + h),
    ).forEach { (k, p) -> drawPath(p, if (k in lit) on else off) }
}

/** A band's level: three slow waves against each other, with the low bands carrying more. */
private fun shaped(i: Int, t: Float): Float {
    val a = sin(t * (1.7f + i * 0.23f) + i * 2.1f)
    val b = sin(t * (3.3f - i * 0.06f) + i * 5.7f)
    val c = sin(t * (0.6f + i * 0.05f) + i * 1.3f)
    val env = (a * 0.46f + b * 0.32f + c * 0.22f + 1f) / 2f
    val tilt = 1f - i / (BANDS * 1.6f)
    return (env * tilt * 1.15f).coerceIn(0f, 1f)
}
