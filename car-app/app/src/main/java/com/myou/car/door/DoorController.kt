package com.myou.car.door

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Pleos Vehicle SDK의 Door 도메인 래퍼. CONTROL_CAR_DOORS 권한 필요. */
class DoorController(context: Context) {
    private val vehicle = Vehicle(context.applicationContext).apply { initialize() }
    private val door get() = vehicle.getDoor()

    fun release() = vehicle.release()

    /** 승객 문 잠금을 모두 푼다. 후드(프렁크)·트렁크는 건드리지 않는다. 하나라도 실패하면 false. */
    suspend fun unlockAll(): Boolean {
        val areas = suspendCancellableCoroutine<List<DoorArea>> { c ->
            door.checkDoorLockCapabilities(
                { areas: List<DoorArea> -> if (c.isActive) c.resume(areas) },
                { e -> log("capabilities", e); if (c.isActive) c.resume(emptyList()) },
            )
        }.filter { area -> area.values.none { it == DoorValue.HOOD || it == DoorValue.REAR } }
        if (areas.isEmpty()) return false
        return areas.map { area ->
            suspendCancellableCoroutine { c ->
                door.setDoorLock(
                    area, false,
                    { _, _ -> if (c.isActive) c.resume(true) },
                    { e -> log("unlock $area", e); if (c.isActive) c.resume(false) },
                )
            }
        }.all { it }
    }

    private fun log(what: String, e: Exception) = Log.w("DoorController", "$what failed: ${e.message}", e)
}
