package com.celica.dash.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The unit's own GPS: a fix, ground speed, heading. Speed feeds the dash when no OBD is present. */
data class Fix(
    val lat: Double, val lon: Double,
    val speedMph: Float, val bearing: Float,
    val atMs: Long,
)

class LocationRepository(private val ctx: Context) {
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listening = false
    private var providers: List<String> = emptyList()
    private var satsSeen = 0
    private var satsUsed = 0
    private val gnss = object : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            satsSeen = status.satelliteCount
            satsUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }
    // Spelled out rather than a lambda: on Android 10 the provider callbacks are still abstract
    // (their defaults came with 11), and a listener without them crashed the dash the moment a
    // provider was switched off in the unit's settings (23:17, 20 Sep).
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            _fix.value = Fix(
                l.latitude, l.longitude,
                if (l.hasSpeed()) l.speed * 2.23694f else 0f,
                if (l.hasBearing()) l.bearing else _fix.value?.bearing ?: 0f,
                System.currentTimeMillis(),
            )
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Safe to call repeatedly; starts once permission exists. */
    /**
     * Asks every provider the unit has - GPS first, but also network, fused and passive, since
     * some head-unit firmwares hand the puck's fixes out under another name.
     */
    fun start() {
        if (listening || !granted()) return
        val wanted = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused", LocationManager.PASSIVE_PROVIDER)
        providers = wanted.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return
        try {
            for (p in providers) {
                lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                lm.getLastKnownLocation(p)?.let { if (System.currentTimeMillis() - it.time < 120_000) listener.onLocationChanged(it) }
            }
            runCatching { lm.registerGnssStatusCallback(gnss, android.os.Handler(Looper.getMainLooper())) }
            listening = true
        } catch (e: SecurityException) {
            // not granted after all
        }
    }

    fun stop() { if (listening) { lm.removeUpdates(listener); runCatching { lm.unregisterGnssStatusCallback(gnss) }; listening = false } }

    /** One line for the diagnostics page: permission, the provider, and how old the last fix is. */
    fun status(): String {
        if (!granted()) return "location permission NOT granted"
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val on = runCatching { lm.isLocationEnabled }.getOrDefault(false)
        val f = _fix.value
        val age = f?.let { "${(System.currentTimeMillis() - it.atMs) / 1000}s ago" } ?: "none yet"
        return "permission ok · location ${if (on) "on" else "OFF"} · gps ${if (gps) "on" else "OFF"} · providers ${providers.joinToString("+").ifEmpty { "none" }} · satellites $satsSeen seen, $satsUsed used · fix $age"
    }
}
