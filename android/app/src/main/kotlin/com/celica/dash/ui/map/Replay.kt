package com.celica.dash.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.celica.dash.data.Drive

/** Where the replayed car is right now. */
class Pose(val lat: Double, val lon: Double, val bearing: Float, val mph: Float, val index: Int)

/**
 * The clock for a replayed drive: a position in the recording that runs forward at `rate` times
 * real time while `playing`, and reads out the car's pose between samples.
 */
class Player(val drive: Drive) {
    private val pts = drive.pts
    private val t0 = pts.first().t
    val lengthMs = pts.last().t - t0

    var tMs by mutableLongStateOf(0L)
    var playing by mutableStateOf(true)
    var rate by mutableFloatStateOf(1f)
    private var cursor = 1

    fun advance(dtMs: Long) {
        if (!playing) return
        tMs = (tMs + (dtMs * rate).toLong())
        if (tMs >= lengthMs) { tMs = lengthMs; playing = false }
    }

    fun seek(frac: Float) { tMs = (lengthMs * frac.coerceIn(0f, 1f)).toLong(); cursor = 1 }
    fun cycleRate() { rate = when (rate) { 1f -> 4f; 4f -> 8f; 8f -> 16f; else -> 1f } }
    fun restart() { tMs = 0; cursor = 1; playing = true }

    val fraction get() = if (lengthMs > 0) tMs.toFloat() / lengthMs else 1f

    /** Interpolates between the two samples either side of the clock. */
    fun pose(): Pose {
        val t = t0 + tMs
        while (cursor < pts.size - 1 && pts[cursor].t < t) cursor++
        while (cursor > 1 && pts[cursor - 1].t > t) cursor--
        val a = pts[cursor - 1]; val b = pts[cursor]
        val span = (b.t - a.t).coerceAtLeast(1)
        val u = ((t - a.t).toFloat() / span).coerceIn(0f, 1f)
        var db = b.brg - a.brg
        if (db > 180) db -= 360 else if (db < -180) db += 360
        return Pose(a.lat + (b.lat - a.lat) * u, a.lon + (b.lon - a.lon) * u, (a.brg + db * u + 360f) % 360f, a.mph + (b.mph - a.mph) * u, cursor)
    }
}
