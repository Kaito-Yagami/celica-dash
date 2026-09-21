package com.celica.dash.routing

import android.content.Context
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.util.Instruction
import com.graphhopper.util.RoundaboutInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** One turn on a route: where on the line it happens and what to do there. */
class Turn(
    val sign: Int,                // GraphHopper's Instruction sign: -3..3 turns, 4 finish, 6 roundabout, ±7 keep, ±8 u-turn
    val name: String,             // the road after the turn
    val distM: Double,            // this leg's length, to the next turn
    val pointIdx: Int,            // index into Route.points where the turn is
    val exit: Int,                // roundabout exit number, else 0
)

class Route(val points: List<DoubleArray>, val turns: List<Turn>, val distM: Double, val timeMs: Long) {
    /** Route distance from the start to each point, so "how far to the turn" is a subtraction. */
    val cumM: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + com.celica.dash.location.haversineM(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1])
    }
}

/**
 * On-board routing over a GraphHopper 1.0 graph of the owner's region, made on the PC by the
 * setup helper and fetched or imported through Setup. Contraction hierarchies, so a route
 * across a country is well under a second; memory-mapped, so it costs little RAM.
 */
class Router(ctx: Context) {
    val dir: File = File(ctx.filesDir, "routing/graph")
    private var hopper: GraphHopper? = null
    private val lock = Mutex()

    /** True when a graph has been imported; loading is lazy and happens on first use. */
    fun available() = File(dir, "properties").exists() && File(dir, "nodes").exists()

    private suspend fun hopper(): GraphHopper? = lock.withLock {
        hopper ?: if (!available()) null else runCatching {
            GraphHopper().forMobile().apply {
                setProfiles(Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(false))
                chPreparationHandler.setCHProfiles(CHProfile("car"))
                load(dir.path)
            }
        }.getOrNull().also { hopper = it }
    }

    /** Forgets the loaded graph - after a fresh import, so the next route reads the new files. */
    suspend fun reset() = lock.withLock { hopper?.close(); hopper = null }

    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route? = withContext(Dispatchers.Default) {
        val h = hopper() ?: return@withContext null
        val req = GHRequest(fromLat, fromLon, toLat, toLon).setProfile("car").setLocale(com.celica.dash.data.Units.locale)
        val rsp = runCatching { h.route(req) }.getOrNull() ?: return@withContext null
        if (rsp.hasErrors()) return@withContext null
        val best = rsp.best
        val pl = best.points
        val points = (0 until pl.size).map { doubleArrayOf(pl.getLat(it), pl.getLon(it)) }
        var idx = 0
        val turns = best.instructions.map { ins: Instruction ->
            val t = Turn(ins.sign, ins.name ?: "", ins.distance, idx.coerceAtMost(points.size - 1), (ins as? RoundaboutInstruction)?.exitNumber ?: 0)
            idx += ins.points.size
            t
        }
        Route(points, turns, best.distance, best.time)
    }

    /** The name of the road under a position, or null when nothing is near or no graph. */
    suspend fun roadName(lat: Double, lon: Double): String? = withContext(Dispatchers.Default) {
        val h = hopper() ?: return@withContext null
        runCatching {
            val q = h.locationIndex.findClosest(lat, lon, EdgeFilter.ALL_EDGES)
            if (!q.isValid || q.queryDistance > 60.0) null else q.closestEdge.name.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
