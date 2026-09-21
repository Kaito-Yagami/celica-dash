package com.celica.dash.routing

import com.celica.dash.location.haversineM
import kotlin.math.max

/** What the turn card shows, recomputed on every fix. */
data class NavState(
    val route: Route,
    val destName: String,
    val destLat: Double, val destLon: Double,
    val pointIdx: Int = 0,                 // the route point the car has reached
    val turn: Turn? = null,                // the next turn
    val toTurnM: Double = 0.0,             // road distance to it
    val remainingM: Double = 0.0,
    val remainingMs: Long = 0L,
    val offRoute: Boolean = false,
    val arrived: Boolean = false,
    val rerouting: Boolean = false,
)

/**
 * Keeps the car's place on a route: projects each fix onto the line near where it last was,
 * works out the next turn and the distances, and says when the car has left the route (four
 * fixes more than 60 m off it) so a new one can be asked for.
 */
class Navigator(route: Route, private val destName: String, private val destLat: Double, private val destLon: Double) {
    var state = NavState(route, destName, destLat, destLon, turn = route.turns.firstOrNull { it.pointIdx > 0 } ?: route.turns.lastOrNull(), remainingM = route.distM, remainingMs = route.timeMs)
        private set
    private var offCount = 0

    /** Swaps in a new route from the current position after a reroute. */
    fun replace(route: Route) {
        offCount = 0
        state = NavState(route, destName, destLat, destLon, remainingM = route.distM, remainingMs = route.timeMs, turn = route.turns.firstOrNull { it.pointIdx > 0 } ?: route.turns.lastOrNull())
    }

    fun update(lat: Double, lon: Double): NavState {
        val r = state.route
        val pts = r.points
        if (pts.size < 2) return state
        // nearest point on the segments around where we were; a little behind in case of a jump back
        val from = max(0, state.pointIdx - 5)
        val to = minOf(pts.size - 2, state.pointIdx + 80)
        var bestD = Double.MAX_VALUE; var bestI = state.pointIdx; var bestT = 0.0
        for (i in from..to) {
            val (t, d) = project(lat, lon, pts[i], pts[i + 1])
            if (d < bestD) { bestD = d; bestI = i; bestT = t }
        }
        val segLen = r.cumM[bestI + 1] - r.cumM[bestI]
        val along = r.cumM[bestI] + segLen * bestT
        val off = bestD > 60.0
        offCount = if (off) offCount + 1 else 0
        val remaining = (r.distM - along).coerceAtLeast(0.0)
        val turn = r.turns.firstOrNull { it.pointIdx > bestI || (it.pointIdx == bestI + 1) } ?: r.turns.lastOrNull()
        val toTurn = turn?.let { (r.cumM[it.pointIdx] - along).coerceAtLeast(0.0) } ?: 0.0
        val arrived = haversineM(lat, lon, destLat, destLon) < 35.0 || (remaining < 25.0 && !off)
        val speedFactor = if (r.distM > 0) r.timeMs / r.distM else 0.0
        state = state.copy(
            pointIdx = bestI, turn = turn, toTurnM = toTurn, remainingM = remaining, remainingMs = (remaining * speedFactor).toLong(),
            offRoute = offCount >= 4, arrived = arrived,
        )
        return state
    }

    /** Fraction along a→b of the closest point to p, and the distance to it in metres. */
    private fun project(lat: Double, lon: Double, a: DoubleArray, b: DoubleArray): Pair<Double, Double> {
        val kx = Math.cos(Math.toRadians(lat)) * 111_320.0; val ky = 111_320.0
        val ax = (a[1] - lon) * kx; val ay = (a[0] - lat) * ky
        val bx = (b[1] - lon) * kx; val by = (b[0] - lat) * ky
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-6) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
        val px = ax + dx * t; val py = ay + dy * t
        return t to Math.sqrt(px * px + py * py)
    }
}

/** Distances in the owner's units: yards or metres close up, miles or kilometres beyond. */
fun fmtDist(m: Double): String = com.celica.dash.data.Units.roadDist(m)

/** The words for a turn sign, with the road name when there is one. */
fun turnWords(t: Turn): String {
    val base = when (t.sign) {
        -8, 8, -98 -> "Make a U-turn"
        -7 -> "Keep left"; 7 -> "Keep right"
        -3 -> "Sharp left"; -2 -> "Turn left"; -1 -> "Bear left"
        1 -> "Bear right"; 2 -> "Turn right"; 3 -> "Sharp right"
        4 -> "Arrive"
        6 -> if (t.exit > 0) "Roundabout, exit ${t.exit}" else "Roundabout"
        -6 -> "Leave the roundabout"
        else -> "Continue"
    }
    return if (t.name.isNotBlank() && t.sign != 4) "$base onto ${t.name}" else base
}
