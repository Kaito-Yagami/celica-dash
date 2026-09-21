package com.celica.dash.data

import android.content.ContentValues
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * The setup helper on the PC (`RUN ME` starts it): a small web server on port 8787 that
 * serves a payload folder - the map tiles, the routing graph, the search index, music, the
 * backdrops, a newer build of the dash - and the owner's answers as `setup.json`. It says
 * where it is with a UDP beacon on 8788 once a second, so the unit finds it on the Wi-Fi
 * without anyone typing an address; the address can still be typed.
 */
object Helper {
    const val PORT = 8787
    const val BEACON_PORT = 8788
    const val BEACON = "CELICADASH-HELPER"

    class Item(val kind: String, val path: String, val bytes: Long, val folder: String?, val label: String, val count: Int)
    class Info(val host: String, val name: String, val version: String, val items: List<Item>, val setup: JSONObject?)

    /** Listens for the beacon for a few seconds; the first helper heard, or null. */
    fun listen(ctx: Context, ms: Int = 5000): String? {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("celicadash-helper")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.broadcast = true
                s.soTimeout = ms
                s.bind(java.net.InetSocketAddress(BEACON_PORT))
                val buf = ByteArray(256)
                val until = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < until) {
                    val p = DatagramPacket(buf, buf.size)
                    runCatching { s.receive(p) }.getOrNull() ?: break
                    val text = String(p.data, 0, p.length).trim()
                    // "CELICADASH-HELPER 192.168.1.20:8787" - the address as the helper knows it, port and all
                    if (text.startsWith(BEACON)) return text.removePrefix(BEACON).trim().ifBlank { p.address.hostAddress } ?: p.address.hostAddress
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { lock?.release() }
        }
        return null
    }

    /** "http://host:port" from "host" or "host:port"; the default port when none is given. */
    fun base(host: String): String = "http://" + host.trim().removePrefix("http://").trimEnd('/').let { if (it.contains(':')) it else "$it:$PORT" }

    /** The ports a helper may be on: its own, then the ones it falls back to when that is taken. */
    val PORTS = listOf(PORT) + (8790..8799)

    /**
     * Asks at an address. Given a bare address, tries the helper's port and then each of its
     * fallbacks - so the owner types the PC's address and never a port.
     */
    fun probe(host: String): Info? {
        val h = host.trim().removePrefix("http://").trimEnd('/')
        if (h.contains(':')) return hello(h)
        for (port in PORTS) hello("$h:$port", quick = true)?.let { return it }
        return null
    }

    /** Asks the helper what it has. */
    fun hello(host: String, quick: Boolean = false): Info? = runCatching {
        val c = URL(base(host) + "/hello").openConnection() as HttpURLConnection
        c.connectTimeout = if (quick) 1200 else 3000; c.readTimeout = 5000
        if (c.responseCode != 200) return null
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val items = j.optJSONArray("items")?.let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Item(o.getString("kind"), o.getString("path"), o.optLong("bytes"), o.optString("folder").takeIf { it.isNotBlank() }, o.optString("label"), o.optInt("count"))
            }
        } ?: emptyList()
        Info(host.trim().removePrefix("http://").trimEnd('/'), j.optString("name", "the PC"), j.optString("version"), items, j.optJSONObject("setup"))
    }.getOrNull()

    fun open(host: String, path: String): Pair<InputStream, Long> {
        val c = URL(base(host) + "/payload/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 60000
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        return java.io.BufferedInputStream(c.inputStream, 1 shl 20) to c.contentLengthLong
    }

    /**
     * Music into the unit's shared Music folder, where the dash's own player (and the vendor's)
     * find it: through MediaStore from Android 10, straight onto the card before that.
     */
    fun saveMusic(ctx: Context, folder: String, zip: InputStream, onProgress: (Int) -> Unit): Int {
        var n = 0
        java.util.zip.ZipInputStream(zip).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val name = e.name.substringAfterLast('/')
                val ext = name.substringAfterLast('.', "").lowercase()
                if (!e.isDirectory && ext in setOf("mp3", "m4a", "flac", "ogg", "wav", "aac")) {
                    val mime = when (ext) { "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "flac" -> "audio/flac"; "ogg" -> "audio/ogg"; else -> "audio/wav" }
                    if (Build.VERSION.SDK_INT >= 29) {
                        val r = ctx.contentResolver
                        val v = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                            put(MediaStore.Audio.Media.MIME_TYPE, mime)
                            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$folder")
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                        // a song already there with this name is replaced, not doubled
                        r.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf(name, "Music/$folder%"))
                        val uri = r.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)
                        if (uri != null) {
                            r.openOutputStream(uri)?.use { z.copyTo(it, 1 shl 20) }
                            v.clear(); v.put(MediaStore.Audio.Media.IS_PENDING, 0); r.update(uri, v, null, null)
                            n++
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), folder).apply { mkdirs() }
                        val f = File(dir, name)
                        f.outputStream().use { z.copyTo(it, 1 shl 20) }
                        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf(mime), null)
                        n++
                    }
                    onProgress(n)
                }
                z.closeEntry(); e = z.nextEntry
            }
        }
        return n
    }

    /** The unit's own address on the Wi-Fi, so the helper's page can show it. */
    fun unitAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
    }.getOrNull()

    /**
     * `setup.json`, the owner's answers from the PC wizard, written into the dash's own settings.
     * Every key is optional; what is there wins over what the unit's Setup would ask.
     */
    suspend fun applySetup(prefs: Prefs, j: JSONObject) {
        j.optJSONObject("car")?.let { c ->
            prefs.setCar(CarSpec.Saved(
                preset = c.optString("preset", CarSpec.PRESET_2ZZ), name = c.optString("name"), alt = c.optString("alt").takeIf { c.has("alt") },
                code = c.optString("code"), sub = c.optString("sub"), note = c.optString("note"),
                liftRpm = c.optInt("lift", -1).takeIf { it > 0 }, noLift = c.optBoolean("noLift", false), redline = c.optInt("redline", -1).takeIf { it > 0 },
                gears = c.optString("gears").takeIf { c.has("gears") }, finalDrive = c.optDouble("final").takeIf { !it.isNaN() },
                circumferenceM = c.optDouble("circumference").takeIf { !it.isNaN() }, lengthM = c.optDouble("length").takeIf { !it.isNaN() }?.toFloat(),
                model = c.optString("model"),
            ))
        }
        j.optJSONObject("look")?.let { l ->
            l.optString("look").takeIf { it.isNotBlank() }?.let { prefs.setLook(it) }
            prefs.setColours(l.optString("accent").takeIf { it.isNotBlank() }, l.optString("background").takeIf { it.isNotBlank() })
            if (l.has("crt")) prefs.setCrt(l.optBoolean("crt"))
            l.optDouble("zoom").takeIf { !it.isNaN() }?.let { prefs.setZoom(it.toFloat()) }
        }
        j.optJSONObject("region")?.let { r ->
            r.optString("units").takeIf { it.isNotBlank() }?.let { prefs.setUnits(it) }
            r.optString("locale").takeIf { it.isNotBlank() }?.let { prefs.setLocale(it) }
            r.optString("name").takeIf { it.isNotBlank() }?.let { prefs.setRegion(it) }
            val lat = r.optDouble("lat"); val lon = r.optDouble("lon")
            if (!lat.isNaN() && !lon.isNaN()) prefs.setMapHome(lat, lon, r.optDouble("zoom", 6.0))
        }
        j.optJSONObject("phone")?.let { p ->
            p.optString("mode").takeIf { it.isNotBlank() }?.let { prefs.setPhoneMode(it) }
            if (p.has("gps")) prefs.setPhoneGps(p.optString("gps"))
        }
    }
}
