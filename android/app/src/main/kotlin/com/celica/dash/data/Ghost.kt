package com.celica.dash.data

import com.celica.dash.data.Units
import com.celica.dash.location.Fix
import com.celica.dash.location.haversineM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The state of a chase: which run is being chased, whether the car has reached its start line
 * yet, how far up or down it is, and where the ghost itself is on the road right now.
 */
class GhostState(
    val name: String,
    val running: Boolean,
    val deltaMs: Long = 0,
    val onLine: Boolean = true,
    val lat: Double? = null,
    val lon: Double? = null,
)

/** How a chase ended, for the card. */
class GhostResult(val name: String, val ourMs: Long, val theirMs: Long) {
    val beat get() = ourMs < theirMs
    val byMs get() = theirMs - ourMs
}

/**
 * Chasing a run of your own. One recorded drive is armed as the ghost; the chase starts by
 * itself when the car arrives at that drive's start, and from then the dash follows the old run
 * sample by sample and says by how much the car is up or down on it - and where the ghost is.
 *
 * Beat it and the armed ghost becomes the new run, so the target is always your best.
 *
 * The matching is deliberately simple - the nearest sample within fifty metres, searched forward
 * only - because a drive is a line, not a lap: the car either follows it or leaves it.
 */
class Ghost {
    private val _state = MutableStateFlow<GhostState?>(null)
    val state: StateFlow<GhostState?> = _state.asStateFlow()

    /** Told when the car crosses the start, and when the chase ends one way or the other. */
    @Volatile var onStart: (() -> Unit)? = null
    @Volatile var onFinish: ((GhostResult) -> Unit)? = null
    @Volatile var onAbort: (() -> Unit)? = null

    private var target: Drive? = null
    private var name = ""
    private var idx = 0
    private var ourStartAt = 0L
    private var lost = 0

    val armedId: Long? get() = target?.summary?.id

    fun arm(drive: Drive) {
        target = drive
        name = drive.summary.name ?: shortDate(drive.summary.startMs)
        idx = 0
        lost = 0
        ourStartAt = 0
        _state.value = GhostState(name, running = false)
    }

    fun disarm() {
        target = null
        _state.value = null
    }

    /** Every fix. Waits at the start line, then follows. */
    fun onFix(f: Fix) {
        val d = target ?: return
        if (d.pts.size < 4) return
        if (ourStartAt == 0L) {
            // the chase begins where the old run began, once the car is actually moving
            val start = d.pts.first()
            if (f.speedMph >= 3f && haversineM(f.lat, f.lon, start.lat, start.lon) < 45.0) {
                ourStartAt = System.currentTimeMillis()
                idx = 0
                lost = 0
                _state.value = GhostState(name, running = true, deltaMs = 0, lat = start.lat, lon = start.lon)
                onStart?.invoke()
            }
            return
        }
        follow(f, d)
    }

    private fun follow(f: Fix, d: Drive) {
        var at = -1
        var near = 50.0
        val until = minOf(d.pts.size - 1, idx + 150)
        for (i in idx..until) {
            val m = haversineM(f.lat, f.lon, d.pts[i].lat, d.pts[i].lon)
            if (m < near) { near = m; at = i }
        }
        val ours = System.currentTimeMillis() - ourStartAt
        val ghostAt = whereAt(d, ours)
        if (at < 0) {
            lost++
            if (lost > 8) {
                reset()
                onAbort?.invoke()
                _state.value = GhostState(name, running = false)
            } else {
                _state.value = _state.value?.let { GhostState(it.name, true, it.deltaMs, false, ghostAt?.first, ghostAt?.second) }
            }
            return
        }
        lost = 0
        idx = at
        val theirs = d.pts[at].t - d.pts.first().t
        _state.value = GhostState(name, running = true, deltaMs = ours - theirs, onLine = true, lat = ghostAt?.first, lon = ghostAt?.second)
        if (at >= d.pts.size - 2) {
            val theirTotal = d.pts.last().t - d.pts.first().t
            reset()
            _state.value = GhostState(name, running = false)
            onFinish?.invoke(GhostResult(name, ours, theirTotal))
        }
    }

    /** Where the ghost is on the road after this long into its run. */
    private fun whereAt(d: Drive, elapsed: Long): Pair<Double, Double>? {
        val t0 = d.pts.first().t
        if (elapsed <= 0) return d.pts.first().let { it.lat to it.lon }
        if (elapsed >= d.pts.last().t - t0) return d.pts.last().let { it.lat to it.lon }
        var i = 1
        while (i < d.pts.size && d.pts[i].t - t0 < elapsed) i++
        val a = d.pts[i - 1]
        val b = d.pts[i]
        val span = (b.t - a.t).coerceAtLeast(1)
        val k = ((elapsed - (a.t - t0)).toDouble() / span).coerceIn(0.0, 1.0)
        return (a.lat + (b.lat - a.lat) * k) to (a.lon + (b.lon - a.lon) * k)
    }

    private fun reset() {
        ourStartAt = 0
        idx = 0
        lost = 0
    }

    private fun shortDate(ms: Long) =
        Units.date("d MMM HH:mm").format(java.util.Date(ms))
}
