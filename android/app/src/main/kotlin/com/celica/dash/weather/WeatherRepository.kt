package com.celica.dash.weather

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val Context.weatherStore: DataStore<Preferences> by preferencesDataStore("weather")

/**
 * Open-Meteo, no key, for the last known position. The car has no internet most of the time, so
 * the last forecast is kept and shown with its age; a fetch only happens when the unit actually
 * has a validated route to the internet (a hotspot, usually) and the cache is over half an hour
 * old.
 */
class WeatherRepository(private val ctx: Context) {
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()

    private val kJson = stringPreferencesKey("json")
    private val kAt = longPreferencesKey("at")

    suspend fun loadCached() {
        val p = ctx.weatherStore.data.first()
        val j = p[kJson] ?: return
        val at = p[kAt] ?: return
        _weather.value = runCatching { parse(JSONObject(j), at) }.getOrNull()
    }

    fun online(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Refresh if online and stale. Returns true when a new forecast landed. */
    suspend fun refresh(lat: Double, lon: Double, force: Boolean = false): Boolean {
        val cur = _weather.value
        val stale = cur == null || System.currentTimeMillis() - cur.fetchedAt > 30 * 60 * 1000
        if (!force && !stale) return false
        if (!online()) return false
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", lat, lon) +
                        "&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m" +
                        "&hourly=temperature_2m&forecast_hours=12&wind_speed_unit=mph&timezone=auto"
                )
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                val body = c.inputStream.bufferedReader().readText()
                val now = System.currentTimeMillis()
                _weather.value = parse(JSONObject(body), now)
                ctx.weatherStore.edit { it[kJson] = body; it[kAt] = now }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun parse(j: JSONObject, at: Long): Weather {
        val cur = j.getJSONObject("current")
        val hourly = j.optJSONObject("hourly")
        var minC: Float? = null
        var minAt: String? = null
        if (hourly != null) {
            val temps = hourly.getJSONArray("temperature_2m")
            val times = hourly.getJSONArray("time")
            for (i in 0 until temps.length()) {
                if (temps.isNull(i)) continue
                val t = temps.getDouble(i).toFloat()
                if (minC == null || t < minC) { minC = t; minAt = times.getString(i).substringAfter("T") }
            }
        }
        return Weather(
            tempC = cur.getDouble("temperature_2m").toFloat(),
            code = cur.getInt("weather_code"),
            windMph = cur.getDouble("wind_speed_10m").toFloat(),
            windDir = Weather.compass(cur.getDouble("wind_direction_10m").toFloat()),
            minNextC = minC, minAtHour = minAt, fetchedAt = at,
        )
    }
}
