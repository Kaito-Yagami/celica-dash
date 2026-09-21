package com.celica.dash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

private fun scheme() = darkColorScheme(
    primary = Ink.red,
    onPrimary = Ink.tx,
    background = Ink.bg,
    onBackground = Ink.tx,
    surface = Ink.s1,
    onSurface = Ink.tx,
    surfaceVariant = Ink.s2,
    outline = Ink.line,
)

/** The mockup was drawn at 1024 wide. Everything in the app is measured in those units. */
const val DESIGN_WIDTH = 1024f

/**
 * One theme, dark only. The interesting part is the density: the head unit reports ~112 dpi,
 * which would make Android scale everything up by 1.4x and squeeze the layout. Instead the app
 * declares its own density so that the screen is always exactly DESIGN_WIDTH dp wide - on the
 * unit that is 1 dp = 1 px, the same numbers as the mockup; on a phone or the emulator it just
 * scales the whole dash proportionally.
 */
@Composable
fun CelicaTheme(zoom: Float = 1f, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val widthPx = ctx.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    // zoom above 1 draws everything larger, so the dash is laid out as if the screen were
    // narrower than the mockup; the layouts flex to fit
    val scale = widthPx / DESIGN_WIDTH * zoom
    CompositionLocalProvider(LocalDensity provides Density(density = scale, fontScale = 1f)) {
        MaterialTheme(colorScheme = scheme(), content = content)
    }
}

/**
 * The screen in the app's own units - 1024 dp wide at 100%, and narrower as the size is turned
 * up. Layouts that would otherwise pin a panel to a fixed width take their share from this.
 */
@Composable
fun screenDp(): DpSize {
    val m = LocalContext.current.resources.displayMetrics
    val d = LocalDensity.current.density
    return DpSize((m.widthPixels / d).dp, (m.heightPixels / d).dp)
}

/** A side panel's width, never more than `share` of the screen however large the size is set. */
@Composable
fun panelWidth(ideal: Dp, share: Float = 0.5f): Dp = minOf(ideal, screenDp().width * share)
