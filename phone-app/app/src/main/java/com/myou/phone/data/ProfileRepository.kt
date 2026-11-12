package com.myou.phone.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.profileStore by preferencesDataStore(name = "seat_presets")

/** 운전자 프로필과 디지털 키 이름을 DataStore 에 저장한다. 키 이름·JSON 형식은 차량 앱과 같다. */
class ProfileRepository(context: Context) {
    private val store = context.applicationContext.profileStore
    private val presetsKey = stringPreferencesKey("presets")
    private val keyNamesKey = stringPreferencesKey("key_names")
    private val restKey = stringPreferencesKey("rest_prefs")

    val profiles: Flow<List<SeatPreset>> = store.data.map { decode(it[presetsKey]) }

    val keyNames: Flow<Map<String, String>> = store.data.map { prefs ->
        prefs[keyNamesKey]?.let { json -> JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } ?: emptyMap()
    }

    val restPrefs: Flow<RestPrefs> = store.data.map { prefs ->
        prefs[restKey]?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { o ->
            RestPrefs(
                minutes = o.optInt("minutes", 15),
                temperatureC = o.optDouble("temperatureC", 22.0).toFloat(),
                fanSpeed = o.optInt("fanSpeed", 2),
                ambientPercent = o.optInt("ambientPercent", 20),
            )
        } ?: RestPrefs()
    }

    suspend fun saveRestPrefs(r: RestPrefs) = store.edit { it[restKey] = r.toJson().toString() }

    suspend fun save(preset: SeatPreset) = store.edit { prefs ->
        prefs[presetsKey] = encode(decode(prefs[presetsKey]).upsert(preset))
    }

    /** 차량에서 가져온 내용으로 프로필·키 이름·휴식 설정을 바꾼다. 형식이 틀리면 예외, 아무것도 바꾸지 않는다. 가져온 프로필 수. */
    suspend fun importFromCar(json: String): Int {
        val o = JSONObject(json)
        val list = decode(o.getJSONArray("presets").toString())
        val names = o.optJSONObject("keyNames") ?: JSONObject()
        val rest = o.optJSONObject("restSettings")
        store.edit { prefs ->
            prefs[presetsKey] = encode(list)
            prefs[keyNamesKey] = names.toString()
            if (rest != null) prefs[restKey] = rest.toString()
        }
        return list.size
    }

    suspend fun delete(id: String) = store.edit { prefs ->
        prefs[presetsKey] = encode(decode(prefs[presetsKey]).filterNot { it.id == id })
    }

    /** 빈 이름이면 기본 이름으로 되돌린다. */
    suspend fun renameKey(keyId: String, name: String) = store.edit { prefs ->
        val o = prefs[keyNamesKey]?.let { JSONObject(it) } ?: JSONObject()
        if (name.isBlank()) o.remove(keyId) else o.put(keyId, name)
        prefs[keyNamesKey] = o.toString()
    }

    private fun encode(list: List<SeatPreset>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("positions", JSONObject().apply { p.positions.forEach { (axis, v) -> put(axis.name, v) } })
                p.keyId?.let { put("keyId", it) }
                p.restPositions?.let { r -> put("restPositions", JSONObject().apply { r.forEach { (axis, v) -> put(axis.name, v) } }) }
            })
        }
    }.toString()

    private fun decode(json: String?): List<SeatPreset> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = JSONArray(json)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val pos = o.getJSONObject("positions")
            SeatPreset(
                id = o.getString("id"),
                name = o.getString("name"),
                positions = SeatAxis.entries.filter { pos.has(it.name) }.associateWith { pos.getInt(it.name) },
                keyId = if (o.has("keyId")) o.getString("keyId") else null,
                restPositions = o.optJSONObject("restPositions")?.let { r -> SeatAxis.entries.filter { r.has(it.name) }.associateWith { r.getInt(it.name) } },
            )
        }
    }
}

fun RestPrefs.toJson(): JSONObject = JSONObject().apply {
    put("minutes", minutes)
    put("temperatureC", temperatureC.toDouble())
    put("fanSpeed", fanSpeed)
    put("ambientPercent", ambientPercent)
}
