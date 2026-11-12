package com.myou.car.seat

import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.control.MoveControl
import android.content.Context
import android.util.Log
import com.myou.car.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import kotlin.math.abs
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 시연 전용(BuildConfig.DEMO_FEATURES): 에뮬레이터 안의 Pleos Virtual Console(127.0.0.1:8088) 시트 시뮬레이터와 직접 연동한다.
 *
 * shortcut: 에뮬레이터 v2.1.0 VHAL이 콘솔의 시트 위치를 100으로 한 번 더 나눠 SDK에는 항상 0이 들어온다.
 * 이 버그가 고쳐진 이미지가 나오면 이 클래스와 res/xml/virtual_console_network.xml 을 지운다.
 */
class ConsoleSeatController : SeatControl {
    // 이동 명령(FORWARD → STOP)이 보낸 순서대로 도착하도록 한 줄로 보낸다.
    private val sender = Executors.newSingleThreadScheduledExecutor()
    private val sessionId = "myou-" + UUID.randomUUID()

    init {
        // 콘솔은 웹 화면이 1초마다 보내는 세션 신호가 있어야 시트 시뮬레이터를 켠다(없으면 emulator_inactive).
        sender.scheduleWithFixedDelay({ runCatching { request("POST", "/browser/session?client=$sessionId") } }, 0, 1, TimeUnit.SECONDS)
    }

    // 시뮬레이터가 100ms마다 5%씩 움직여서, 더 자주 읽어야 다음 틱 전에 정지 명령이 도착한다.
    override val pollMs = 20L

    override fun observe(axis: SeatAxis): Flow<Int> = flow {
        while (true) {
            emit(read(axis))
            delay(POLL_MS)
        }
    }.filterNotNull().distinctUntilChanged()

    // 콘솔 카탈로그 기준 모든 축이 0..100 %
    override suspend fun bounds(axis: SeatAxis): AreaBounds<Int>? = if (read(axis) != null) AreaBounds(0, 100) else null

    override suspend fun read(axis: SeatAxis): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val seat = JSONObject(request("GET", "/vehicle/state")).getJSONObject("seat")
            seat.getJSONObject("positions").getJSONObject(axis.consoleName).getInt(AREA)
        }.onFailure { Log.w(TAG, "read $axis failed: ${it.message}") }.getOrNull()
    }

    override fun move(axis: SeatAxis, control: MoveControl) {
        val value = when (control) {
            MoveControl.FORWARD -> 1
            MoveControl.BACKWARD -> -1
            else -> 0
        }
        sender.execute {
            runCatching { request("POST", "/vehicle/seat?kind=move&control=${axis.consoleName}&area=$AREA&value=$value") }
                .onFailure { Log.w(TAG, "move $axis $control failed: ${it.message}") }
        }
    }

    /**
     * 시뮬레이터는 0.1초마다 5%씩 움직여서, PC가 느리면 정지 명령이 몇 틱 늦어 목표를 지나친다.
     * 실제 시트처럼 움직인 뒤, 남은 차이는 콘솔에 위치를 바로 지정해 맞춘다 (디버그 전용, 실차 경로와 무관).
     */
    override suspend fun moveTo(axis: SeatAxis, target: Int, tolerance: Int): Boolean {
        val moved = super.moveTo(axis, target, tolerance)
        val now = read(axis) ?: return moved
        if (abs(now - target) <= tolerance) return moved
        return withContext(Dispatchers.IO) {
            runCatching { request("POST", "/vehicle/seat?kind=position&control=${axis.consoleName}&area=$AREA&value=$target") }.isSuccess
        }
    }

    override fun release() {
        SeatAxis.entries.forEach { move(it, MoveControl.STOP) }
        sender.execute { runCatching { request("POST", "/browser/session?client=$sessionId&closing=1") } }
        sender.shutdown()
    }

    companion object {
        private const val TAG = "ConsoleSeat"
        private const val BASE = "http://127.0.0.1:8088"
        private const val AREA = "driver"

        private val SeatAxis.consoleName
            get() = when (this) {
                SeatAxis.FORE_AFT -> "slide"
                SeatAxis.BACKREST -> "recline"
                SeatAxis.HEIGHT -> "height"
                SeatAxis.TILT -> "tilt"
            }

        internal fun request(method: String, path: String): String {
            val c = URL(BASE + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 1_000
                c.readTimeout = 2_000
                check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }

        /** 시연 기능이 켜져 있고 콘솔이 응답할 때만 true. 실차에는 콘솔이 없어 false. */
        suspend fun isAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
            if (!BuildConfig.DEMO_FEATURES) return false
            return withContext(Dispatchers.IO) {
                runCatching { request("GET", "/vehicle/state") }
                    .onSuccess { Log.i(TAG, "Virtual Console 시트 연동 사용") }
                    .onFailure { Log.i(TAG, "Virtual Console 없음, Pleos SDK 사용: ${it.message}") }
                    .isSuccess
            }
        }
    }
}
