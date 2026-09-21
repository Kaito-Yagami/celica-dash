package com.celica.dash.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One GPS sample of a recorded drive; rpm is 0 when there was no dongle. */
class DrivePoint(val t: Long, val lat: Double, val lon: Double, val mph: Float, val brg: Float, val rpm: Int)

/** What the list shows without opening the file. */
class DriveSummary(
    val id: Long, val startMs: Long, val endMs: Long, val distM: Double, val maxMph: Float,
    val liftMs: Long, val points: Int, val name: String?,
) {
    val durationMs get() = endMs - startMs
    val miles get() = distM / 1609.344
}

class Drive(val summary: DriveSummary, val pts: List<DrivePoint>)

/**
 * Recorded drives, one JSON file each under files/drives plus an index of summaries. A drive is
 * a few hundred bytes a minute, so a year of commuting is a handful of megabytes.
 */
class DriveStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "drives").apply { mkdirs() }
    private val index = File(dir, "index.json")

    private val _drives = MutableStateFlow(readIndex())
    val drives: StateFlow<List<DriveSummary>> = _drives.asStateFlow()

    suspend fun save(s: DriveSummary, pts: List<DrivePoint>) = withContext(Dispatchers.IO) {
        val p = JSONArray()
        for (x in pts) p.put(JSONArray().put(x.t).put(x.lat).put(x.lon).put(x.mph.toDouble()).put(x.brg.toDouble()).put(x.rpm))
        File(dir, "${s.id}.json").writeText(JSONObject().put("s", s.toJson()).put("p", p).toString())
        writeIndex((_drives.value + s).sortedByDescending { it.startMs })
    }

    suspend fun load(id: Long): Drive? = withContext(Dispatchers.IO) {
        val f = File(dir, "$id.json")
        if (!f.exists()) return@withContext null
        runCatching {
            val j = JSONObject(f.readText())
            val p = j.getJSONArray("p")
            Drive(fromJson(j.getJSONObject("s")), (0 until p.length()).map { i ->
                val a = p.getJSONArray(i)
                DrivePoint(a.getLong(0), a.getDouble(1), a.getDouble(2), a.getDouble(3).toFloat(), a.getDouble(4).toFloat(), a.getInt(5))
            })
        }.getOrNull()
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        writeIndex(_drives.value.filter { it.id != id })
    }

    suspend fun rename(id: Long, name: String?) = withContext(Dispatchers.IO) {
        writeIndex(_drives.value.map { if (it.id == id) DriveSummary(it.id, it.startMs, it.endMs, it.distM, it.maxMph, it.liftMs, it.points, name) else it })
    }

    private fun readIndex(): List<DriveSummary> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.sortedByDescending { it.startMs }
    }.getOrDefault(emptyList())

    private fun writeIndex(list: List<DriveSummary>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        index.writeText(a.toString())
        _drives.value = list
    }

    private fun DriveSummary.toJson() = JSONObject().put("id", id).put("start", startMs).put("end", endMs).put("dist", distM)
        .put("max", maxMph.toDouble()).put("lift", liftMs).put("n", points).put("name", name ?: JSONObject.NULL)
    private fun fromJson(j: JSONObject) = DriveSummary(
        j.getLong("id"), j.getLong("start"), j.getLong("end"), j.getDouble("dist"), j.getDouble("max").toFloat(),
        j.optLong("lift"), j.optInt("n"), j.optString("name").takeIf { it.isNotEmpty() && it != "null" },
    )
}
