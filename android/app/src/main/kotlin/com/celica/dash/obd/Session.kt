package com.celica.dash.obd

import com.celica.dash.location.Fix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** What this session of driving has amounted to - the numbers worth looking at afterwards. */
data class Session(
    val maxMph: Float = 0f,
    val maxRpm: Int = 0,
    val liftMs: Long = 0,
    val liftCount: Int = 0,
    val miles: Double = 0.0,
    val best0to60Ms: Long? = null,
    val litres: Double = 0.0,
    val lateralG: Float = 0f,
    val peakG: Float = 0f,
    val startedAt: Long = System.currentTimeMillis(),
) {
    /** Miles to the gallon across the whole session, once there is enough of it to mean anything. */
    val avgMpg: Float? get() = if (litres > 0.15 && miles > 0.3) (miles / (litres / 4.54609)).toFloat() else null
}

/**
 * Keeps the running tally for the Drive screen: how fast, how far, how long the cams were on the
 * second lobe, the quickest run to sixty, and how hard the car is being cornered. Nothing is
 * written down - it starts again with the dash, which is what a session means.
 *
 * The cornering figure comes from how quickly the heading is turning and how fast the car is
 * going, which is what a lateral accelerometer would tell you anyway: v * (d heading / dt) / g.
 */
class SessionTracker {
    private val _state = MutableStateFlow(Session())
    val state: StateFlow<Session> = _state.asStateFlow()

    private var lastAt = 0L
    private var inLift = false
    private var rolling = false          // has moved since the last standstill
    private var launchAt: Long? = null
    private var lastBearing: Float? = null
    private var lastFixAt = 0L

    fun reset() {
        _state.value = Session()
        lastAt = 0L; inLift = false; rolling = false; launchAt = null; lastBearing = null; lastFixAt = 0L
    }

    /** Every reading from the car or the GPS, however often they arrive. */
    fun onVehicle(v: VehicleState) {
        val now = System.currentTimeMillis()
        val mph = v.speedMph ?: 0f
        val dt = if (lastAt == 0L) 0L else (now - lastAt).coerceAtMost(2000)
        lastAt = now
        var s = _state.value

        if (mph > s.maxMph) s = s.copy(maxMph = mph)
        v.rpm?.let { if (it > s.maxRpm) s = s.copy(maxRpm = it) }
        if (dt > 0) s = s.copy(miles = s.miles + mph * (dt / 3_600_000.0))
        // fuel burnt: the air drawn in, over the ideal mixture, over the weight of a litre
        v.mafGps?.let { maf -> if (dt > 0 && maf > 0.2f) s = s.copy(litres = s.litres + maf * (dt / 1000.0) / 14.7 / 737) }

        // the cams: time on the second lobe, and how many times it got there
        val lift = v.lift
        if (lift && dt > 0) s = s.copy(liftMs = s.liftMs + dt)
        if (lift && !inLift) s = s.copy(liftCount = s.liftCount + 1)
        inLift = lift

        // a standing start: armed at rest, timed from the first movement, taken at sixty
        when {
            mph < 1f -> { rolling = false; launchAt = null }
            !rolling -> { rolling = true; launchAt = now }
            launchAt != null && mph >= 60f -> {
                val t = now - launchAt!!
                if (t in 2_000..30_000 && (s.best0to60Ms == null || t < s.best0to60Ms!!)) s = s.copy(best0to60Ms = t)
                launchAt = null
            }
        }
        _state.value = s
    }

    /** The GPS, for how hard the car is being turned. */
    fun onFix(f: Fix) {
        val prev = lastBearing
        val prevAt = lastFixAt
        lastBearing = f.bearing
        lastFixAt = f.atMs
        if (prev == null || prevAt == 0L) return
        val dt = (f.atMs - prevAt) / 1000.0
        if (dt <= 0.05 || dt > 3.0 || f.speedMph < 5f) { if (f.speedMph < 5f) _state.value = _state.value.copy(lateralG = 0f); return }
        var d = f.bearing - prev
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        val rate = Math.toRadians(abs(d).toDouble()) / dt          // radians a second
        val g = (f.speedMph * 0.44704 * rate / 9.81).toFloat()
        if (g > 2.5f) return                                        // a jumping fix, not a corner
        val s = _state.value
        _state.value = s.copy(lateralG = g, peakG = maxOf(s.peakG, g))
    }
}
