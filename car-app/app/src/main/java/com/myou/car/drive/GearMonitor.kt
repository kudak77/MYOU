package com.myou.car.drive

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Pleos Vehicle SDK의 Gear 도메인 래퍼. CAR_DRIVE 권한 필요. */
class GearMonitor(context: Context) {
    private val vehicle = Vehicle(context.applicationContext).apply { initialize() }
    private val gearApi get() = vehicle.getGear()

    fun release() = vehicle.release()

    /** 현재 기어를 먼저 한 번 내보내고, 바뀔 때마다 내보낸다. 읽기 실패는 GEAR_UNKNOWN. */
    val gear: Flow<VehicleGear> = callbackFlow {
        gearApi.getCurrentGearSelectionState(
            { trySend(it ?: VehicleGear.GEAR_UNKNOWN) },
            { e -> Log.w(TAG, "gear read failed: ${e.message}", e); trySend(VehicleGear.GEAR_UNKNOWN) },
        )
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) { trySend(gear ?: VehicleGear.GEAR_UNKNOWN) }
            override fun onFailed(p0: Exception) { Log.w(TAG, "gear listen failed: ${p0.message}", p0); trySend(VehicleGear.GEAR_UNKNOWN) }
        }
        gearApi.registerGearSelection(listener)
        awaitClose { gearApi.unregisterGearSelection(listener) }
    }.distinctUntilChanged()

    private companion object { const val TAG = "GearMonitor" }
}
