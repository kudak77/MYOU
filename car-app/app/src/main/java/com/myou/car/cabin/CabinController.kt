package com.myou.car.cabin

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK의 HVAC·Light 도메인 래퍼. 각 함수는 성공하면 true.
 * 공조·조명은 운전석 단독이 아니라 묶인 구역(예: 1열 전체)으로 제어되는 차가 있어, 지원 구역을 먼저 조회해 운전석이 든 구역을 쓴다.
 */
class CabinController(context: Context) {
    private val vehicle = Vehicle(context.applicationContext).apply { initialize() }
    private val hvac get() = vehicle.getHVAC()
    private val light get() = vehicle.getLight()

    fun release() = vehicle.release()

    suspend fun setTemperature(celsius: Float): Boolean {
        powerOn()
        val (zone, bounds) = driverZone<Float> { ok, fail -> hvac.checkHvacTemperatureCapabilities(ok, fail) } ?: return false
        return call("temperature") { ok, fail ->
            hvac.setHvacTemperature(zone, celsius.coerceIn(bounds.minValue, bounds.maxValue), TemperatureUnit.CELSIUS, { _, _ -> ok() }, fail)
        }
    }

    /** 공조가 꺼져 있으면 풍량 설정이 거부되므로 먼저 켠다. */
    private suspend fun powerOn(): Boolean = setPower(true)

    private suspend fun setPower(on: Boolean): Boolean {
        val zone = powerZone() ?: return false
        return call("power") { ok, fail -> hvac.setHvacPower(zone, on, { _, _ -> ok() }, fail) }
    }

    /** 전원 구역도 차마다 묶여 있을 수 있어 조회해서 쓴다. */
    private suspend fun powerZone(): SeatArea? = suspendCancellableCoroutine { c ->
        hvac.checkHvacPowerCapabilities({ areas: List<SeatArea> ->
            if (c.isActive) c.resume(areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: areas.firstOrNull())
        }, { e -> Log.w(TAG, "power capabilities failed: ${e.message}"); if (c.isActive) c.resume(null) })
    }

    /** 휴식 전 실내 환경. 읽지 못한 값은 null 이고, 되돌릴 때 건드리지 않는다. */
    data class Snapshot(val power: Boolean?, val temperatureC: Float?, val fanSpeed: Int?, val ambientPercent: Int?, val cabinLight: LightSwitch?)

    suspend fun snapshot(): Snapshot {
        val power = powerZone()?.let { z -> read<Boolean> { ok, fail -> hvac.getHvacPowerState(z, ok, fail) } }
        val temp = driverZone<Float> { ok, fail -> hvac.checkHvacTemperatureCapabilities(ok, fail) }
            ?.let { (z, _) -> read<Float> { ok, fail -> hvac.getHvacTemperature(z, TemperatureUnit.CELSIUS, ok, fail) } }
        val fan = driverZone<Int> { ok, fail -> hvac.checkHvacFanSpeedCapabilities(ok, fail) }
            ?.let { (z, _) -> read<Int> { ok, fail -> hvac.getHvacFanSpeed(z, ok, fail) } }
        val ambient = driverZone<Int> { ok, fail -> light.checkAmbientLightBrightnessCapabilities({ _, areas -> ok(areas) }, fail) }
            ?.let { (z, _) -> read<Int> { ok, fail -> light.getAmbientLightBrightness(z, ok, fail) } }
        val cabinLight = read<LightSwitch> { ok, fail -> light.getCabinLightSwitch(ok, fail) }
        return Snapshot(power, temp, fan, ambient, cabinLight)
    }

    /** 휴식 전 실내 환경으로 되돌린다. 공조 전원은 값을 맞춘 뒤 마지막에 원래대로 둔다. */
    suspend fun restore(s: Snapshot) {
        s.temperatureC?.let { setTemperature(it) }
        s.fanSpeed?.let { setFanSpeed(it) }
        s.ambientPercent?.let { pct ->
            driverZone<Int> { ok, fail -> light.checkAmbientLightBrightnessCapabilities({ _, areas -> ok(areas) }, fail) }
                ?.let { (z, _) -> call("ambient restore") { ok, fail -> light.setAmbientLightBrightness(z, pct, { _, _ -> ok() }, fail) } }
        }
        s.cabinLight?.let { sw -> call("cabin light restore") { ok, fail -> light.setCabinLightSwitch(sw, { ok() }, fail) } }
        s.power?.let { setPower(it) }
    }

    private suspend fun <T> read(block: (ok: (T?) -> Unit, fail: (Exception) -> Unit) -> Unit): T? =
        suspendCancellableCoroutine { c ->
            block({ v -> if (c.isActive) c.resume(v) }, { e -> Log.w(TAG, "read failed: ${e.message}"); if (c.isActive) c.resume(null) })
        }

    suspend fun setFanSpeed(level: Int): Boolean {
        powerOn()
        val (zone, bounds) = driverZone<Int> { ok, fail -> hvac.checkHvacFanSpeedCapabilities(ok, fail) } ?: return false
        return call("fan") { ok, fail ->
            hvac.setHvacFanSpeed(zone, level.coerceIn(bounds.minValue, bounds.maxValue), { _, _ -> ok() }, fail)
        }
    }

    /** 무드등 밝기(%)를 맞추고, 휴식 중 눈부시지 않게 실내등은 끈다. */
    suspend fun setRestLighting(ambientPercent: Int): Boolean {
        light.setCabinLightSwitch(LightSwitch.OFF, { }, { e -> Log.w(TAG, "cabin light failed: ${e.message}") })
        val (zone, bounds) = driverZone<Int> { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities({ _, areas -> ok(areas) }, fail)
        } ?: return false
        return call("ambient") { ok, fail ->
            light.setAmbientLightBrightness(zone, ambientPercent.coerceIn(bounds.minValue, bounds.maxValue), { _, _ -> ok() }, fail)
        }
    }

    /** 지원 구역 중 운전석(1열 왼쪽)이 들어 있는 구역과 그 범위. 지원하지 않으면 null. */
    private suspend fun <T> driverZone(
        check: (ok: (Map<SeatArea, AreaBounds<T>>) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): Pair<SeatArea, AreaBounds<T>>? = suspendCancellableCoroutine { c ->
        check({ areas ->
            val hit = areas.entries.firstOrNull { SeatValue.ROW_1_LEFT in it.key.values } ?: areas.entries.firstOrNull()
            if (c.isActive) c.resume(hit?.toPair())
        }, { e ->
            Log.w(TAG, "capabilities failed: ${e.message}")
            if (c.isActive) c.resume(null)
        })
    }

    private suspend fun call(what: String, block: (ok: () -> Unit, fail: (Exception) -> Unit) -> Unit): Boolean =
        suspendCancellableCoroutine { c ->
            block({ if (c.isActive) c.resume(true) }, { e ->
                Log.w(TAG, "$what failed: ${e.message}", e)
                if (c.isActive) c.resume(false)
            })
        }

    private companion object { const val TAG = "CabinController" }
}
