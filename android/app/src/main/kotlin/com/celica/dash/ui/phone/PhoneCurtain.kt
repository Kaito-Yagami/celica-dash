package com.celica.dash.ui.phone

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.apps.PhoneLink
import com.celica.dash.apps.PhoneLink.Phase
import com.celica.dash.data.CarSpec
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.LabelWide
import com.celica.dash.ui.theme.Numeral
import com.celica.dash.ui.theme.Title
import com.celica.dash.ui.theme.WordmarkStyle
import kotlinx.coroutines.delay

/**
 * The curtain over zlink's handshake: the phone on the left, the unit on the right, and the
 * link between them carrying a pulse until the session is up. What it says is the phase, from
 * [com.celica.dash.apps.PhoneLink]; what it looks like is the dash, so the eye never leaves it.
 */
@Composable
fun PhoneCurtain(phase: Phase, mode: PhoneLink.Mode, unitName: String?, onBack: () -> Unit, onRetry: () -> Unit) {
    val name = unitName ?: "the unit"
    val word = mode.word
    val phone = mode.phone
    val up = phase is Phase.Up
    val silent = phase is Phase.Silent
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        // the way back, always
        Row(
            Modifier.align(Alignment.TopStart).padding(24.dp, 22.dp).clickable(onClick = onBack).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("‹", style = Title.copy(fontSize = 22.sp, color = Ink.txDim))
            Text("DASH", style = Label)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(28.dp, 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(word.uppercase(), style = LabelWide)
            Box(Modifier.size(7.dp).background(if (up) Ink.red else Ink.txFaint, androidx.compose.foundation.shape.CircleShape))
        }

        Column(Modifier.align(Alignment.Center).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LinkDiagram(up = up, still = silent)
            Text(
                when (phase) {
                    is Phase.Up -> "${phone.uppercase()} LINKED"
                    is Phase.Silent -> "NOTHING ANSWERED"
                    else -> "WAITING FOR YOUR ${phone.uppercase()}"
                },
                style = Numeral.copy(fontSize = 26.sp), modifier = Modifier.padding(top = 34.dp),
            )
            Text(
                when (phase) {
                    is Phase.Up -> "Opening $word"
                    is Phase.Silent -> "Is Bluetooth on the phone on, and $word allowed for $name?"
                    else -> "It shows on the $phone as $name and comes on its own once it has been here before."
                },
                style = Body.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (silent) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FlatButton("Keep waiting", hot = true, onClick = onRetry)
                FlatButton("Back to dash", onClick = onBack)
            }
        } else {
            Row(Modifier.align(Alignment.BottomStart).padding(28.dp, 30.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("FIRST TIME", style = Label.copy(color = Ink.txFaint))
                Text(if (mode == PhoneLink.Mode.CARPLAY) "iPhone › Settings › General › CarPlay › $name" else "Phone › Settings › Connected devices › Android Auto, then pair $name", style = Data.copy(color = Ink.txFaint))
            }
            // the seconds, since a wait with no clock on it feels longer
            var elapsed by remember { mutableLongStateOf(0L) }
            LaunchedEffect(phase) {
                val since = (phase as? Phase.Waiting)?.since ?: return@LaunchedEffect
                while (true) { elapsed = (System.currentTimeMillis() - since) / 1000; delay(250) }
            }
            if (phase is Phase.Waiting) Text("${elapsed}s", style = Data.copy(color = Ink.txFaint), modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp, 30.dp))
        }
    }
}

/** iPhone, link, unit. The pulse runs phone-to-unit while waiting and the line fills once up. */
@Composable
private fun LinkDiagram(up: Boolean, still: Boolean) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        // the phone: a slab with a notch, the badge on its screen
        Canvas(Modifier.width(78.dp).height(150.dp)) {
            val w = size.width; val h = size.height
            drawRoundRect(Ink.s1, cornerRadius = CornerRadius(w * 0.16f))
            drawRoundRect(Ink.line2, cornerRadius = CornerRadius(w * 0.16f), style = Stroke(2.dp.toPx()))
            drawRoundRect(Ink.line2, topLeft = Offset(w * 0.32f, h * 0.045f), size = Size(w * 0.36f, h * 0.03f), cornerRadius = CornerRadius(h * 0.015f))
            carPlayGlyph(Offset(w / 2, h / 2), w * 0.22f, Color.White)
        }
        Canvas(Modifier.width(240.dp).height(36.dp)) {
            val dots = 13
            val gap = size.width / (dots + 1)
            val cy = size.height / 2
            if (up) drawLine(Ink.red, Offset(gap, cy), Offset(size.width - gap, cy), strokeWidth = 2.dp.toPx())
            for (i in 1..dots) drawCircle(if (up) Ink.red else Ink.line2, radius = 2.2.dp.toPx(), center = Offset(gap * i, cy))
            if (!up && !still) {
                val x = gap + pulse * (size.width - 2 * gap)
                drawCircle(Ink.glow, radius = 11.dp.toPx(), center = Offset(x, cy))
                drawCircle(Ink.red, radius = 4.dp.toPx(), center = Offset(x, cy))
            }
        }
        // the unit: a wide screen with the wordmark on it
        Box(Modifier.width(210.dp).height(124.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(Ink.s1, cornerRadius = CornerRadius(10.dp.toPx()))
                drawRoundRect(if (up) Ink.red else Ink.line2, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(CarSpec.name.replace("*", ""), style = WordmarkStyle.copy(fontSize = 15.sp))
                if (CarSpec.code.isNotBlank()) Text(CarSpec.code, style = Label.copy(fontSize = 8.sp, color = Ink.txFaint), modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/**
 * The CarPlay mark: an open ring - a C, cut at the right - with the play triangle inside it, as
 * on the phone's settings page and on every "works with CarPlay" sticker. Drawn rather than
 * shipped as an image so it takes the dash's colours; the button and the curtain share it.
 */
fun DrawScope.carPlayGlyph(center: Offset, radius: Float, color: Color) {
    val r = radius
    val stroke = r * 0.19f
    drawArc(
        color, startAngle = 33f, sweepAngle = 294f, useCenter = false,
        topLeft = Offset(center.x - r + stroke / 2, center.y - r + stroke / 2), size = Size(2 * r - stroke, 2 * r - stroke),
        style = Stroke(stroke, cap = StrokeCap.Butt),
    )
    // the triangle sits a little right of centre, its corners softened by a round stroke
    val corner = r * 0.07f
    val tri = Path().apply {
        moveTo(center.x - r * 0.27f + corner, center.y - r * 0.50f + corner)
        lineTo(center.x + r * 0.60f - corner, center.y)
        lineTo(center.x - r * 0.27f + corner, center.y + r * 0.50f - corner)
        close()
    }
    drawPath(tri, color)
    drawPath(tri, color, style = Stroke(corner * 2, join = StrokeJoin.Round))
}

/** The mark on a rounded tile, as the phone's settings page shows it. */
fun DrawScope.carPlayBadge(at: Offset, side: Float, tile: Color, mark: Color) {
    drawRoundRect(tile, topLeft = at, size = Size(side, side), cornerRadius = CornerRadius(side * 0.24f))
    carPlayGlyph(Offset(at.x + side / 2, at.y + side / 2), side * 0.36f, mark)
}

/**
 * The button on the phone tab. The badge and the word, lit when there is an app to open; it
 * sinks a touch under the finger, sends a ring out from the badge when let go, and breathes
 * a little while a session is up so the state can be read from across the car.
 */
@Composable
fun PhoneButton(enabled: Boolean, live: Boolean, word: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.955f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "sink")
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "breath",
    )
    // one ring per tap, 0..1 from the badge outwards, then nothing
    var tapAt by remember { mutableLongStateOf(0L) }
    val ring = remember { Animatable(1f) }
    LaunchedEffect(tapAt) { if (tapAt != 0L) { ring.snapTo(0f); ring.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) } }
    Row(
        Modifier
            .graphicsLayer { scaleX = sink; scaleY = sink }
            .background(if (enabled) Ink.s2 else Ink.s1)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { tapAt = System.currentTimeMillis(); onClick() }
            .padding(start = 10.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(28.dp)) {
            val r = size.minDimension
            val c = Offset(r / 2, r / 2)
            if (live && enabled) drawCircle(Ink.glow.copy(alpha = 0.10f + 0.16f * breath), radius = r * (0.68f + 0.10f * breath), center = c)
            if (ring.value < 1f) drawCircle(Ink.red.copy(alpha = 0.55f * (1f - ring.value)), radius = r * (0.55f + 0.75f * ring.value), center = c, style = Stroke(1.5.dp.toPx()))
            if (enabled && !live) drawRoundRect(Ink.line2, cornerRadius = CornerRadius(r * 0.24f), style = Stroke(1.dp.toPx()))
            carPlayBadge(Offset.Zero, r, tile = if (live) Ink.red else if (enabled) Ink.s1 else Ink.s1, mark = if (enabled) Color.White else Ink.txFaint)
        }
        Text(word, style = Title.copy(fontSize = 15.sp, color = if (enabled) Ink.tx else Ink.txFaint, fontWeight = FontWeight.SemiBold))
        if (live) Text("UP", style = Label.copy(color = Ink.red))
    }
}
