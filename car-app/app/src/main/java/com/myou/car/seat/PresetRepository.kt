package com.myou.car.seat

import android.content.Context
import com.myou.car.rest.RestSettings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.presetStore by preferencesDataStore(name = "seat_presets")

/** 시트 프리셋 목록과 현재 선택된 운전자 프리셋을 DataStore에 저장한다. */
class PresetRepository(context: Context) {
    private val store = context.applicationContext.presetStore
    private val presetsKey = stringPreferencesKey("presets")
    private val activeKey = stringPreferencesKey("active_preset_id")
    private val restKey = stringPreferencesKey("rest_settings")
    private val keyNamesKey = stringPreferencesKey("key_names")

    val presets: Flow<List<SeatPreset>> = store.data.map { decode(it[presetsKey]) }
    val activePresetId: Flow<String?> = store.data.map { it[activeKey] }

    suspend fun upsert(preset: SeatPreset) = store.edit { prefs ->
        val list = decode(prefs[presetsKey]).filterNot { it.id == preset.id } + preset
        prefs[presetsKey] = encode(list)
    }

    suspend fun delete(id: String) = store.edit { prefs ->
        prefs[presetsKey] = encode(decode(prefs[presetsKey]).filterNot { it.id == id })
        if (prefs[activeKey] == id) prefs.remove(activeKey)
    }

    suspend fun setActive(id: String) = store.edit { it[activeKey] = id }

    /** 폰으로 보낼 프로필·키 이름·휴식 설정 (importFromPhone 과 같은 모양) */
    suspend fun exportForPhone(): String {
        val prefs = store.data.first()
        val rest = prefs[restKey]?.let { runCatching { decodeRest(it) }.getOrNull() } ?: RestSettings()
        return JSONObject().apply {
            put("presets", JSONArray(encode(decode(prefs[presetsKey]))))
            put("keyNames", prefs[keyNamesKey]?.let { JSONObject(it) } ?: JSONObject())
            put("restSettings", JSONObject().apply {
                put("minutes", rest.minutes)
                rest.temperatureC?.let { put("temperatureC", it.toDouble()) }
                rest.fanSpeed?.let { put("fanSpeed", it) }
                rest.ambientPercent?.let { put("ambientPercent", it) }
            })
        }.toString()
    }

    /**
     * 스마트폰 앱이 보낸 프로필 목록과 키 이름으로 통째로 바꾼다 (폰이 원본). 받은 프로필 수를 돌려준다.
     * 형식이 틀리면 예외를 던지고 아무것도 바꾸지 않는다.
     */
    suspend fun importFromPhone(presetsJson: String, keyNames: Map<String, String>, restJson: String? = null): Int {
        val list = decode(presetsJson)
        // 폰은 시간·온도·풍량·무드등만 정한다. 기본 휴식 자세는 차량 값을 유지한다.
        val rest = restJson?.let { JSONObject(it) }
        store.edit { prefs ->
            prefs[presetsKey] = encode(list)
            prefs[keyNamesKey] = JSONObject(keyNames).toString()
            if (rest != null) {
                val cur = prefs[restKey]?.let { runCatching { decodeRest(it) }.getOrNull() } ?: RestSettings()
                prefs[restKey] = encodeRest(cur.copy(
                    minutes = rest.optInt("minutes", cur.minutes).coerceIn(1, 120),
                    temperatureC = if (rest.has("temperatureC")) rest.getDouble("temperatureC").toFloat().coerceIn(16f, 30f) else cur.temperatureC,
                    fanSpeed = if (rest.has("fanSpeed")) rest.getInt("fanSpeed").coerceIn(0, 8) else cur.fanSpeed,
                    ambientPercent = if (rest.has("ambientPercent")) rest.getInt("ambientPercent").coerceIn(0, 100) else cur.ambientPercent,
                ))
            }
            if (list.none { it.id == prefs[activeKey] }) prefs.remove(activeKey)
        }
        return list.size
    }

    /** [presetId] 에 [keyId] 를 연결한다. 키 하나는 프리셋 하나에만 연결되므로 다른 프리셋의 같은 키 연결은 푼다. */
    suspend fun linkKey(presetId: String, keyId: String?) = store.edit { prefs ->
        prefs[presetsKey] = encode(decode(prefs[presetsKey]).map { p ->
            when {
                p.id == presetId -> p.copy(keyId = keyId)
                keyId != null && p.keyId == keyId -> p.copy(keyId = null)
                else -> p
            }
        })
    }

    /** 사용자가 바꾼 디지털 키 이름 (키 id → 이름). 바꾸지 않은 키는 없다. */
    val keyNames: Flow<Map<String, String>> = store.data.map { prefs ->
        prefs[keyNamesKey]?.let { json -> JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } ?: emptyMap()
    }

    /** 빈 이름이면 기본 이름으로 되돌린다. */
    suspend fun renameKey(keyId: String, name: String) = store.edit { prefs ->
        val o = prefs[keyNamesKey]?.let { JSONObject(it) } ?: JSONObject()
        if (name.isBlank()) o.remove(keyId) else o.put(keyId, name)
        prefs[keyNamesKey] = o.toString()
    }

    // shortcut: 휴식 설정은 차량 하나에 한 벌, 운전자별로 나누려면 SeatPreset 에 옮긴다.
    val restSettings: Flow<RestSettings> = store.data.map { prefs ->
        prefs[restKey]?.let { runCatching { decodeRest(it) }.getOrNull() } ?: RestSettings()
    }

    suspend fun saveRestSettings(r: RestSettings) = store.edit { it[restKey] = encodeRest(r) }

    private fun encodeRest(r: RestSettings): String = JSONObject().apply {
        put("minutes", r.minutes)
        put("seat", JSONObject().apply { r.seat.forEach { (axis, v) -> put(axis.name, v) } })
        r.temperatureC?.let { put("temperatureC", it.toDouble()) }
        r.fanSpeed?.let { put("fanSpeed", it) }
        r.ambientPercent?.let { put("ambientPercent", it) }
    }.toString()

    private fun decodeRest(json: String): RestSettings {
        val o = JSONObject(json)
        val seat = o.getJSONObject("seat")
        return RestSettings(
            minutes = o.getInt("minutes"),
            seat = SeatAxis.entries.filter { seat.has(it.name) }.associateWith { seat.getInt(it.name) },
            temperatureC = if (o.has("temperatureC")) o.getDouble("temperatureC").toFloat() else null,
            fanSpeed = if (o.has("fanSpeed")) o.getInt("fanSpeed") else null,
            ambientPercent = if (o.has("ambientPercent")) o.getInt("ambientPercent") else null,
        )
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
