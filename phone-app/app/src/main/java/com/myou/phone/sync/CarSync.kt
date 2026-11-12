package com.myou.phone.sync

import com.myou.phone.data.DigitalKey
import com.myou.phone.data.RestPrefs
import com.myou.phone.data.SeatPreset
import com.myou.phone.data.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 프로필과 키 이름을 차량 앱으로 보낸다. 차량 앱이 받은 프로필 수를 돌려준다.
 * shortcut: 시연용으로 같은 PC의 차량 에뮬레이터(10.0.2.2:9000 → adb forward)로 보낸다,
 * 실제 서비스에서는 서버(클라우드) 또는 블루투스 동기화로 바꾼다.
 */
object CarSync {
    private const val BASE = "http://10.0.2.2:9000"
    private const val URL_PROFILES = "$BASE/profiles"

    /** 차량 상태(GET /status). 연결 안 되면 예외. */
    suspend fun fetchStatus(): CarStatus = withContext(Dispatchers.IO) {
        val o = JSONObject(get("$BASE/status"))
        val rest = o.getJSONObject("rest")
        CarStatus(
            restPhase = rest.optString("phase", "Idle"),
            restArea = rest.optString("area").ifBlank { null },
            // 차량 시계 기준 종료 시각을 남은 밀리초로 바꿔 둔다 (두 기기 시계가 달라도 맞게).
            restRemainingMs = if (rest.has("endsAtMillis")) rest.getLong("endsAtMillis") - o.getLong("nowMillis") else null,
            ignitionOn = if (o.has("ignitionOn")) o.getBoolean("ignitionOn") else null,
            driver = o.optString("driver").ifBlank { null },
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** 차량에 저장된 프로필·키 이름·휴식 설정 원문(GET /profiles). */
    suspend fun fetchProfiles(): String = withContext(Dispatchers.IO) { get(URL_PROFILES) }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3_000
            c.readTimeout = 5_000
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun send(profiles: List<SeatPreset>, keys: List<DigitalKey>, rest: RestPrefs): Int = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("presets", JSONArray().apply {
                profiles.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("positions", JSONObject().apply { p.positions.forEach { (axis, v) -> put(axis.name, v) } })
                        p.keyId?.let { put("keyId", it) }
                        p.restPositions?.let { r -> put("restPositions", JSONObject().apply { r.forEach { (axis, v) -> put(axis.name, v) } }) }
                    })
                }
            })
            put("keyNames", JSONObject().apply { keys.forEach { put(it.id, it.label) } })
            put("restSettings", rest.toJson())
        }.toString().toByteArray()

        val c = URL(URL_PROFILES).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 5_000
            // 차량 쪽 저장이 끝나야 응답이 오므로 넉넉히 기다린다 (먼저 끊으면 받았는지 알 수 없다).
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getInt("count")
        } finally {
            c.disconnect()
        }
    }
}

data class CarStatus(
    val restPhase: String,
    val restArea: String?,
    val restRemainingMs: Long?,
    val ignitionOn: Boolean?,
    val driver: String?,
    val fetchedAt: Long,
)
