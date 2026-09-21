package com.celica.dash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.mapStore: DataStore<Preferences> by preferencesDataStore("map")

/** A pin the owner dropped on the map. */
data class Place(val id: Long, val name: String, val lat: Double, val lon: Double)

/** Pins, as a small JSON list in DataStore. */
class PlaceStore(private val ctx: Context) {
    private val kPlaces = stringPreferencesKey("places")

    val places: Flow<List<Place>> = ctx.mapStore.data.map { p -> parse(p[kPlaces]) }

    suspend fun add(place: Place) = ctx.mapStore.edit { p -> p[kPlaces] = encode(parse(p[kPlaces]) + place) }
    suspend fun remove(id: Long) = ctx.mapStore.edit { p -> p[kPlaces] = encode(parse(p[kPlaces]).filter { it.id != id }) }
    suspend fun rename(id: Long, name: String) = ctx.mapStore.edit { p ->
        p[kPlaces] = encode(parse(p[kPlaces]).map { if (it.id == id) it.copy(name = name) else it })
    }

    private fun parse(s: String?): List<Place> = runCatching {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { Place(it.getLong("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lon")) }
    }.getOrDefault(emptyList())
    private fun encode(l: List<Place>) = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon)) } }.toString()
}
