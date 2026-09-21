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

private val Context.garageStore: DataStore<Preferences> by preferencesDataStore("garage")

/** One line of the service log. */
data class ServiceEntry(val id: Long, val date: String, val miles: Int?, val title: String, val notes: String)

/**
 * What the owner records: issue statuses, ticked checklist items and the service log. Small,
 * so it lives in DataStore as JSON rather than a database.
 */
class GarageStore(private val ctx: Context) {
    private val kStatus = stringPreferencesKey("issue_status")     // {id: status}
    private val kChecks = stringPreferencesKey("checks")           // ["group:index", ...]
    private val kLog = stringPreferencesKey("log")                 // [ {id,date,miles,title,notes} ]

    val issueStatus: Flow<Map<String, String>> = ctx.garageStore.data.map { p ->
        val j = JSONObject(p[kStatus] ?: "{}")
        j.keys().asSequence().associateWith { j.getString(it) }
    }
    suspend fun setIssueStatus(id: String, status: String) = ctx.garageStore.edit { p ->
        p[kStatus] = JSONObject(p[kStatus] ?: "{}").put(id, status).toString()
    }

    val ticked: Flow<Set<String>> = ctx.garageStore.data.map { p ->
        val a = JSONArray(p[kChecks] ?: "[]"); (0 until a.length()).map { a.getString(it) }.toSet()
    }
    suspend fun setTicked(key: String, on: Boolean) = ctx.garageStore.edit { p ->
        val a = JSONArray(p[kChecks] ?: "[]")
        val set = (0 until a.length()).map { a.getString(it) }.toMutableSet()
        if (on) set += key else set -= key
        p[kChecks] = JSONArray(set.toList()).toString()
    }
    suspend fun clearTicked() = ctx.garageStore.edit { it[kChecks] = "[]" }

    val log: Flow<List<ServiceEntry>> = ctx.garageStore.data.map { p ->
        val a = JSONArray(p[kLog] ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            ServiceEntry(it.getLong("id"), it.getString("date"), it.optInt("miles", -1).takeIf { m -> m >= 0 }, it.getString("title"), it.optString("notes"))
        }.sortedByDescending { it.date + it.id }
    }
    suspend fun addLog(e: ServiceEntry) = ctx.garageStore.edit { p ->
        val a = JSONArray(p[kLog] ?: "[]")
        a.put(JSONObject().put("id", e.id).put("date", e.date).put("miles", e.miles ?: -1).put("title", e.title).put("notes", e.notes))
        p[kLog] = a.toString()
    }
    suspend fun removeLog(id: Long) = ctx.garageStore.edit { p ->
        val a = JSONArray(p[kLog] ?: "[]")
        val keep = JSONArray()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.getLong("id") != id) keep.put(o) }
        p[kLog] = keep.toString()
    }
}
