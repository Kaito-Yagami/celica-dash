package com.celica.dash.ui.garage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A manual page figure. The PNGs are 1-bit, black lines on white; they are drawn inverted so the
 * diagram floats as white lines on the dark panel, the same trick the site uses. Decoded off
 * the main thread at the requested sample size and kept in a small cache - the full pages are
 * 1,800 px wide and there are nearly two thousand of them.
 */
private val cache = LruCache<String, Bitmap>(24)

/** Invert: black on white becomes white on black. */
private val invert = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
)

@Composable
fun Figure(path: String, sample: Int = 1, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val ctx = LocalContext.current
    var bmp by remember(path, sample) { mutableStateOf(cache.get("$path@$sample")) }
    LaunchedEffect(path, sample) {
        if (bmp == null) {
            bmp = withContext(Dispatchers.IO) {
                runCatching {
                    // decode at half the requested reduction, then filter down: 1-bit scans
                    // sampled nearest-neighbour turn dense text into noise
                    val pre = if (sample >= 4) sample / 2 else sample
                    val raw = ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = pre; inPreferredConfig = Bitmap.Config.RGB_565 }) }
                    if (raw != null && pre != sample) Bitmap.createScaledBitmap(raw, raw.width / 2, raw.height / 2, true).also { if (it !== raw) raw.recycle() } else raw
                }.getOrNull()?.also { cache.put("$path@$sample", it) }
            }
        }
    }
    Box(modifier) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = contentScale, colorFilter = invert)
    }
}
