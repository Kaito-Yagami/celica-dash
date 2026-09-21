package com.celica.dash.ui.drive

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Units
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.obd.VehicleState
import com.celica.dash.obd.gearOf
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Display
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Mono
import java.io.File

/** Where the films live, fetched from tools/faces on the PC. */
fun backdropDir(ctx: Context): File = File(ctx.filesDir, "faces").apply { mkdirs() }

fun backdrops(ctx: Context): List<File> =
    backdropDir(ctx).listFiles()?.filter { it.isFile && it.extension.lowercase() == "mp4" }?.sortedBy { it.name } ?: emptyList()

/**
 * The face that is not for the driver: a film behind, and the car's numbers over it.
 *
 * The film runs at the engine's pace - its playback speed is tied to the revs, so it crawls at
 * idle and plays at close to double at the stop. That is the difference between a wallpaper and a
 * window. Without a dongle the road speed stands in, so it still means something on GPS alone.
 */
@Composable
fun TheatreFace(v: VehicleState, chosen: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val films = remember { backdrops(ctx) }
    val film = films.firstOrNull { it.name == chosen } ?: films.firstOrNull()
    val rev = ((v.rpm ?: 0) / VehicleState.REDLINE.toFloat()).coerceIn(0f, 1f)

    // the revs drive it when there is a dongle, the road speed when there is not
    val effort = when {
        v.rpm != null -> rev
        v.speedMph != null -> (v.speedMph / 85f).coerceIn(0f, 1f)
        else -> 0.3f
    }
    val paceNow by animateFloatAsState(0.45f + 1.75f * effort, tween(600), label = "pace")
    val liftNow by animateFloatAsState(if (v.lift) 1f else 0f, tween(if (v.lift) 200 else 500), label = "lift")

    Box(modifier) {
        if (film != null) VideoBackdrop(film, paceNow, Modifier.fillMaxSize()) else NoFilm()

        // only as much shade as the figures need
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.52f to Color.Transparent, 1f to Ink.bg.copy(alpha = 0.82f),
                ),
            )
            drawRect(
                Brush.verticalGradient(0f to Ink.bg.copy(alpha = 0.55f), 1f to Color.Transparent),
                size = Size(size.width, size.height * 0.18f),
            )
        }

        if (CarSpec.hasLift) LiftBar(liftNow, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(4.dp))

        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 6.dp)) {
            Column(Modifier.align(Alignment.BottomStart)) {
                Text("GEAR", style = Label.copy(fontSize = 10.sp))
                Text(
                    gearOf(v.rpm, v.speedMph)?.toString() ?: "–",
                    fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                    color = if (v.lift) Ink.red else Ink.chrome,
                )
            }
            // the unit is balanced by an equal gap on the left, so the figure itself sits on the
            // centre line of the screen rather than the pair of them together
            Row(Modifier.align(Alignment.BottomCenter), verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.width(58.dp))
                Text(
                    Units.speedInt(v.speedMph ?: 0f) ?: "0",
                    fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 104.sp,
                    color = if (v.lift) Ink.red else Ink.tx, letterSpacing = (-4).sp,
                )
                Text(
                    Units.speedUnit, style = Label.copy(fontSize = 11.sp, letterSpacing = 4.sp),
                    modifier = Modifier.width(58.dp).padding(bottom = 22.dp, start = 10.dp),
                )
            }
            Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
                Text("RPM", style = Label.copy(fontSize = 10.sp))
                Text(
                    v.rpm?.let { "%,d".format(it) } ?: "–",
                    fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                    color = if (v.lift) Ink.ember else Ink.txDim,
                )
                RevTicks(rev, v.lift, Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * The changeover, marked along the top edge rather than thrown across the middle: a hairline that
 * is always there, and a red bar that runs out from the centre of it while the second lobe is in.
 */
@Composable
private fun LiftBar(on: Float, modifier: Modifier) {
    Canvas(modifier) {
        val h = 2.dp.toPx()
        val y = size.height - h
        drawRect(Ink.line, Offset(0f, y + h * 0.4f), Size(size.width, h * 0.6f))
        if (on <= 0.01f) return@Canvas
        val half = size.width / 2f * on
        // the light it throws down the frame, so the bar does not sit on its own
        drawRect(
            Brush.verticalGradient(0f to Ink.red.copy(alpha = 0.22f * on), 1f to Color.Transparent),
            Offset(size.width / 2f - half, y), Size(half * 2, size.height * 5),
        )
        drawRect(Ink.red, Offset(size.width / 2f - half, y), Size(half * 2, h))
    }
}

/** What the face says before any film has been put on it. */
@Composable
private fun NoFilm() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NO BACKDROP", style = Label.copy(color = Ink.tx))
            Text(
                "Put an MP4 in tools/faces on the PC, fetch it from Settings › Developer, then pick it under Style.",
                style = Body, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** A short ladder of marks that fills with the revs, for the corner of the frame. */
@Composable
private fun RevTicks(rev: Float, lift: Boolean, modifier: Modifier) {
    Canvas(modifier.width(104.dp).height(9.dp)) {
        val n = 13
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val at = (i + 1f) / n
            val hot = CarSpec.hasLift && at > VehicleState.LIFT_RPM.toFloat() / VehicleState.REDLINE
            drawRect(
                when {
                    rev < at -> Ink.tx.copy(alpha = 0.25f)
                    hot || lift -> Ink.red
                    else -> Ink.tx
                },
                Offset(i * (w + gap), 0f), Size(w, size.height),
            )
        }
    }
}
