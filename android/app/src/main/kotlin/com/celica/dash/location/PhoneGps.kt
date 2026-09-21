package com.celica.dash.location

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The phone's GPS, when the unit's own aerial has nothing: an app on the phone (GPS2IP on
 * iPhone, or any NMEA-over-TCP sender) streams standard NMEA sentences over the hotspot, and
 * this reads them. The phone is the hotspot's gateway, so with no address set that is where
 * it looks, on GPS2IP's port 11123. Only $GPRMC / $GNRMC and $GPGGA / $GNGGA are needed.
 */
class PhoneGps(private val ctx: Context) {
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()
    private val _status = MutableStateFlow("off")
    val status: StateFlow<String> = _status.asStateFlow()
    @Volatile var endpoint: String = ""          // "host:port", or blank for the gateway:11123
    @Volatile var enabled = false

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                if (!enabled) { _status.value = "off"; _fix.value = null; delay(3000); continue }
                val target = endpoint.ifBlank { gateway()?.let { "$it:11123" } ?: "" }
                if (target.isBlank()) { _status.value = "no hotspot gateway"; delay(5000); continue }
                val host = target.substringBefore(":"); val port = target.substringAfter(":", "11123").toIntOrNull() ?: 11123
                _status.value = "connecting $target"
                runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), 4000); s.soTimeout = 8000
                        _status.value = "listening $target"
                        val r = s.getInputStream().bufferedReader()
                        while (enabled) {
                            val line = r.readLine() ?: break
                            parse(line)
                        }
                    }
                }.onFailure { _status.value = "no answer from $target" }
                delay(3000)
            }
        }
    }

    private fun gateway(): String? = runCatching {
        @Suppress("DEPRECATION") val g = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo.gateway
        if (g == 0) null else "${g and 0xff}.${g shr 8 and 0xff}.${g shr 16 and 0xff}.${g shr 24 and 0xff}"
    }.getOrNull()

    /** $xxRMC: time, status, lat, N/S, lon, E/W, speed (knots), course. */
    private fun parse(line: String) {
        if (!line.startsWith("$")) return
        val f = line.substringBefore("*").split(",")
        val type = f[0].drop(3)
        if (type == "RMC" && f.size > 8 && f[2] == "A") {
            val lat = deg(f[3], f[4]) ?: return; val lon = deg(f[5], f[6]) ?: return
            val kn = f[7].toFloatOrNull() ?: 0f
            val brg = f[8].toFloatOrNull() ?: _fix.value?.bearing ?: 0f
            _fix.value = Fix(lat, lon, kn * 1.15078f, brg, System.currentTimeMillis())
        }
    }

    private fun deg(v: String, hemi: String): Double? {
        if (v.length < 4) return null
        val dot = v.indexOf('.'); val degLen = if (dot < 0) v.length - 2 else dot - 2
        val d = v.substring(0, degLen).toDoubleOrNull() ?: return null
        val m = v.substring(degLen).toDoubleOrNull() ?: return null
        val out = d + m / 60.0
        return if (hemi == "S" || hemi == "W") -out else out
    }
}
