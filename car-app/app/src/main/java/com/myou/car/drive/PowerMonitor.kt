package com.myou.car.drive

import android.content.Context
import android.util.Log
import com.myou.car.seat.ConsoleSeatController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

/**
 * 시동 ON/OFF 와 주차 브레이크.
 * - 실제 차: 안드로이드 차량 표준 IGNITION_STATE / PARKING_BRAKE_ON ([CarProperties], CAR_POWERTRAIN 권한).
 *   Pleos: Vehicle SDK 2.3.2 에는 IgnitionState 상수만 있고 읽는 API 가 없으며, Brake 도메인은 페달 상태만 준다.
 * - 에뮬레이터(시연 기능): v2.1.0 은 android.car 를 앱에 제공하지 않아 Virtual Console 상태를 읽는다.
 *   shortcut: 콘솔 전원(Ready/On/Driving)을 시동 ON 으로, 주차 브레이크 경고등을 체결 상태로 본다, 에뮬레이터가 지원하면 이 경로를 지운다.
 */
class PowerMonitor(context: Context) {
    private val app = context.applicationContext
    private val car: CarProperties? = CarProperties.create(app)

    fun release() { car?.disconnect() }

    /**
     * 시연용 시동 버튼: 에뮬레이터에는 누를 시동 버튼이 없어 Virtual Console 전원을 켜고 끈다.
     * 결과는 [ignitionOn] 으로 들어온다. 성공하면 true.
     */
    suspend fun pressTestStartButton(on: Boolean): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching { ConsoleSeatController.request("POST", "/vehicle/power-system/simulator?state=${if (on) "On" else "Off"}&area_id=0") }.isSuccess
    }

    /** 시동이 켜졌으면 true. 상태를 알게 되면 바로 한 번 내보낸다. */
    val ignitionOn: Flow<Boolean> = channelFlow {
        val stop = car?.watchIgnition { trySend(it) }
        if (stop != null) {
            awaitClose(stop)
            return@channelFlow
        }
        check(ConsoleSeatController.isAvailable(app)) { "IGNITION_STATE not supported" }
        Log.i(TAG, "IGNITION_STATE 미지원: Virtual Console 전원 상태 사용")
        while (true) {
            runCatching {
                JSONObject(ConsoleSeatController.request("GET", "/vehicle/state"))
                    .getJSONObject("power_system").getJSONObject("actual").optJSONObject("0")?.optString("state")
            }.getOrNull()?.let { send(it in CONSOLE_ON_STATES) }
            delay(500)
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    /** 주차 브레이크 체결 여부. 에뮬레이터 콘솔에는 주차 브레이크 조작이 없어 계기판 경고등 상태로 대신한다. */
    val parkingBrakeOn: Flow<Boolean> = channelFlow {
        val stop = car?.watchParkingBrake { trySend(it) }
        if (!ConsoleSeatController.isAvailable(app)) {
            checkNotNull(stop) { "PARKING_BRAKE_ON not supported" }
            awaitClose(stop)
            return@channelFlow
        }
        try {
            while (true) {
                runCatching {
                    JSONObject(ConsoleSeatController.request("GET", "/vehicle/state"))
                        .getJSONObject("telltales").getJSONObject("actual").optJSONObject("ParkingBrake")?.optString("state")
                }.getOrNull()?.let { send(it == "On" || it == "Blink") }
                delay(500)
            }
        } finally {
            stop?.invoke()
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    private companion object {
        const val TAG = "PowerMonitor"
        val CONSOLE_ON_STATES = setOf("Ready", "On", "Driving")
    }
}
