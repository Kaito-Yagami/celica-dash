package com.celica.dash.search

import android.content.Context
import com.celica.dash.location.Fix
import com.celica.dash.location.haversineM
import java.io.File

/**
 * Looking things up with nothing but the unit. tools/map/make_search.py reads the whole OSM
 * extract - not the thinned basemap the tiles are drawn from, which keeps only a couple of
 * petrol stations a town - and writes one file the dash holds in memory and scans byte by byte.
 * The same file feeds the search box and the icons the map shows for whatever is on screen.
 *
 * "CSCH" 2, the kinds, then the count, the latitude bands, and the entries in latitude order:
 * lat e6 (i32) lon e6 (i32) kind (u8) nameLen (u8) name (utf-8), all little-endian. The bands
 * are so the map can read the strip it is showing rather than the country.
 */
object PlaceSearch {
    /** One answer: what it is called, what it is, where it is, and how far off. */
    class Hit(val name: String, val kind: String, val lat: Double, val lon: Double, val metres: Double)

    /** The buttons above the results, and the icons the map can be asked to show. */
    val CATEGORIES: List<Pair<String, List<String>>> = listOf(
        "FUEL" to listOf("fuel", "charging_station"),
        "FOOD" to listOf("restaurant", "fast_food", "cafe", "pub", "bar", "bakery", "ice_cream", "food_court"),
        "SHOP" to listOf("supermarket", "convenience", "mall", "department_store", "marketplace", "variety_store", "newsagent"),
        "PARK" to listOf("parking"),
        "LOO" to listOf("toilets"),
        "STAY" to listOf("hotel", "motel", "guest_house", "hostel"),
        "GARAGE" to listOf("car_repair", "car_parts", "car_wash", "tyres", "car"),
        "GYM" to listOf("fitness_centre", "sports_centre", "swimming_pool"),
        "CARE" to listOf("hospital", "pharmacy", "doctors", "clinic", "dentist"),
    )

    fun file(ctx: Context): File = File(ctx.filesDir, "search.idx")

    @Volatile private var bytes: ByteArray? = null
    @Volatile private var kinds: Array<String> = emptyArray()
    @Volatile private var firstRecord = 0
    @Volatile private var count = 0
    @Volatile private var lat0 = 0
    @Volatile private var step = 1
    @Volatile private var bands = 0
    @Volatile private var bandAt = 0                 // where the band table starts

    fun status(ctx: Context): String {
        val f = file(ctx)
        if (!f.exists() || f.length() < 16) return "none"
        return "%.1f MB".format(f.length() / 1e6) + (if (bytes != null) " · $count entries" else " · not read yet")
    }

    /** True once the file is read and understood; false when it is missing or of an older make. */
    fun usable(ctx: Context): Boolean = load(ctx)

    /** Reads the file in, once. Returns false when there is nothing to read. */
    @Synchronized
    private fun load(ctx: Context): Boolean {
        if (bytes != null) return true
        val f = file(ctx)
        if (!f.exists() || f.length() < 16) return false
        val b = runCatching { f.readBytes() }.getOrNull() ?: return false
        if (b.size < 16 || b[0] != 'C'.code.toByte() || b[1] != 'S'.code.toByte() || b[2] != 'C'.code.toByte() || b[3] != 'H'.code.toByte()) return false
        if (b[4].toInt() != 2) return false
        var i = 6
        val names = ArrayList<String>(b[5].toInt() and 0xFF)
        repeat(b[5].toInt() and 0xFF) {
            val n = b[i].toInt() and 0xFF; i++
            names += String(b, i, n, Charsets.UTF_8); i += n
        }
        count = int32(b, i); i += 4
        lat0 = int32(b, i); i += 4
        step = int32(b, i).coerceAtLeast(1); i += 4
        bands = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8); i += 2
        bandAt = i
        firstRecord = i + 4 * bands
        kinds = names.toTypedArray()
        bytes = b
        return true
    }

    /**
     * Everything whose name holds `query`, or everything of a kind in `onlyKinds`, nearest first.
     * `near` is the car; without it the answers come in name order.
     */
    fun find(ctx: Context, query: String, onlyKinds: List<String> = emptyList(), near: Fix? = null, limit: Int = 60): List<Hit> {
        if (query.isBlank() && onlyKinds.isEmpty()) return emptyList()
        if (!load(ctx)) return emptyList()
        val b = bytes ?: return emptyList()
        val q = query.trim().lowercase().toByteArray(Charsets.UTF_8)
        val wanted = kindMask(onlyKinds)
        // a category on its own only makes sense close by, so only those bands are read
        val band = near != null && q.isEmpty()
        val from = if (band) bandStart(near!!.lat - 0.45) else firstRecord
        val until = if (band) latE6(near!!.lat + 0.45) else Int.MAX_VALUE
        val found = ArrayList<Cand>(256)
        var i = from
        while (i + 10 <= b.size) {
            val lat = int32(b, i)
            if (lat > until) break
            val lon = int32(b, i + 4)
            val kind = b[i + 8].toInt() and 0xFF
            val len = b[i + 9].toInt() and 0xFF
            val at = i + 10
            i = at + len
            if (wanted != null && (kind >= wanted.size || !wanted[kind])) continue
            val rank = if (q.isEmpty()) 2 else rankOf(b, at, len, q)
            if (rank < 0) continue
            val m = if (near != null) haversineM(near.lat, near.lon, lat / 1e6, lon / 1e6) else len.toDouble()
            if (q.isEmpty() && m > 40_000) continue                 // 25 miles is far enough for a coffee
            found += Cand(at, len, kind, lat, lon, rank, m)
            if (found.size > limit * 40) { found.sortWith(order); while (found.size > limit) found.removeAt(found.size - 1) }
        }
        found.sortWith(order)
        return found.take(limit).map { hitOf(b, it) }
    }

    /**
     * Everything of the given kinds inside a box, for the map's own icons. Only the latitude
     * bands the box covers are read, so this is cheap enough to run whenever the map settles.
     */
    fun inBox(ctx: Context, south: Double, west: Double, north: Double, east: Double, onlyKinds: List<String>, limit: Int = 400): List<Hit> {
        if (onlyKinds.isEmpty() || !load(ctx)) return emptyList()
        val b = bytes ?: return emptyList()
        val wanted = kindMask(onlyKinds) ?: return emptyList()
        val top = latE6(north)
        val w = (west * 1e6).toInt()
        val e = (east * 1e6).toInt()
        val out = ArrayList<Hit>(64)
        var i = bandStart(south)
        while (i + 10 <= b.size && out.size < limit) {
            val lat = int32(b, i)
            if (lat > top) break
            val lon = int32(b, i + 4)
            val kind = b[i + 8].toInt() and 0xFF
            val len = b[i + 9].toInt() and 0xFF
            val at = i + 10
            i = at + len
            if (lon < w || lon > e || lat < latE6(south)) continue
            if (kind >= wanted.size || !wanted[kind]) continue
            out += Hit(String(b, at, len, Charsets.UTF_8), kinds.getOrElse(kind) { "" }, lat / 1e6, lon / 1e6, 0.0)
        }
        return out
    }

    /** Which category a kind belongs to, for the icon the map draws. */
    fun categoryOf(kind: String): String? = CATEGORIES.firstOrNull { kind in it.second }?.first

    private fun kindMask(onlyKinds: List<String>): BooleanArray? {
        if (onlyKinds.isEmpty()) return null
        val m = BooleanArray(kinds.size)
        for ((n, k) in kinds.withIndex()) if (k in onlyKinds) m[n] = true
        return m
    }

    private fun latE6(lat: Double) = (lat * 1e6).toInt()

    /** Where in the file the band holding this latitude begins. */
    private fun bandStart(lat: Double): Int {
        val b = bytes ?: return firstRecord
        if (bands == 0) return firstRecord
        val n = ((latE6(lat) - lat0) / step).coerceIn(0, bands - 1)
        return firstRecord + int32(b, bandAt + 4 * n)
    }

    private fun hitOf(b: ByteArray, c: Cand) =
        Hit(String(b, c.at, c.len, Charsets.UTF_8), kinds.getOrElse(c.kind) { "" }, c.lat / 1e6, c.lon / 1e6, c.metres)

    private class Cand(val at: Int, val len: Int, val kind: Int, val lat: Int, val lon: Int, val rank: Int, val metres: Double)

    private val order = Comparator<Cand> { a, c -> if (a.rank != c.rank) a.rank - c.rank else a.metres.compareTo(c.metres) }

    /** 0 the whole name, 1 the start of it, 2 the start of a word in it, 3 anywhere, -1 not at all. */
    private fun rankOf(b: ByteArray, at: Int, len: Int, q: ByteArray): Int {
        if (q.size > len) return -1
        var best = -1
        for (s in 0..len - q.size) {
            var k = 0
            while (k < q.size && fold(b[at + s + k]) == q[k].toInt()) k++
            if (k < q.size) continue
            val r = when {
                s == 0 && len == q.size -> 0
                s == 0 -> 1
                b[at + s - 1] == ' '.code.toByte() || b[at + s - 1] == '-'.code.toByte() -> 2
                else -> 3
            }
            if (best < 0 || r < best) best = r
            if (best == 0) break
        }
        return best
    }

    private fun fold(x: Byte): Int {
        val c = x.toInt() and 0xFF
        return if (c in 65..90) c + 32 else c
    }

    private fun int32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24)

    /** Called when a new file lands, so the next search reads it rather than the old one. */
    fun forget() { bytes = null }
}
