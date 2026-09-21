package com.celica.dash.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Where the cached art lives, so a prepared pack can be unzipped straight into it. */
fun artDir(ctx: Context): File = File(ctx.filesDir, "art").apply { mkdirs() }

/**
 * Cover art the phone cannot send: this unit's Bluetooth (Android 10) has no AVRCP cover art,
 * so when the unit is online at all - a hotspot, a SIM - the art is looked up by title and
 * artist in Apple's public song catalogue and cached, one JPEG per track, for every time after.
 */
class ArtLookup(private val ctx: Context) {
    private val dir = artDir(ctx)
    private val missing = HashSet<String>()      // looked up this session and not found

    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun key(title: String, artist: String?) = md5("${title.lowercase()}|${(artist ?: "").lowercase()}")

    /**
     * The same track can arrive spelled several ways - "(feat. X)", " - Remastered 2011", a
     * second artist after a comma - so the art is filed under a plainer name as well: the title
     * without its brackets or its version suffix, the first artist only, letters and digits
     * alone. tools/art/fetch_art.py files a prepared pack under exactly this name.
     */
    private fun softKey(title: String, artist: String?) = md5(plain(strip(title)) + "|" + plain(artist?.substringBefore(",")?.substringBefore(" & ") ?: ""))

    private fun strip(title: String): String {
        val cut = Regex("\\s+-\\s+[^-]*\\b(remaster|remastered|live|version|edit|mix|mono|stereo|anniversary|deluxe|radio|single|bonus)\\b.*$", RegexOption.IGNORE_CASE)
        return title.replace(Regex("\\(.*?\\)"), " ").replace(Regex("\\[.*?]"), " ").replace(cut, "")
    }

    private fun plain(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    fun cached(title: String, artist: String?): Bitmap? =
        listOf(key(title, artist), softKey(title, artist))
            .asSequence().map { File(dir, "$it.jpg") }.firstOrNull { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.path) }

    private fun online(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Fetches and caches the art, or null when offline or unknown. Runs on IO. */
    suspend fun fetch(title: String, artist: String?): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(title, artist)
        cached(title, artist)?.let { return@withContext it }
        if (k in missing || !online()) return@withContext null
        runCatching {
            val term = URLEncoder.encode(listOfNotNull(artist, title).joinToString(" "), "UTF-8")
            val c = URL("https://itunes.apple.com/search?term=$term&entity=song&limit=1").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val url = j.getJSONArray("results").optJSONObject(0)?.optString("artworkUrl100")?.takeIf { it.isNotEmpty() }
                ?: run { missing += k; return@withContext null }
            val big = url.replace("100x100", "600x600")
            val bytes = (URL(big).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 15000 }.inputStream.readBytes()
            File(dir, "$k.jpg").writeBytes(bytes)
            File(dir, softKey(title, artist) + ".jpg").writeBytes(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrElse { null }
    }
}
