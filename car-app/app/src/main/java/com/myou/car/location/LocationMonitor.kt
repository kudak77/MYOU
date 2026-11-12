package com.myou.car.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Pleos FusedLocation SDK 래퍼. pleos.car.permission.FUSED_LOCATION + 위치 권한 필요. */
class LocationMonitor(context: Context) {
    private val fused = FusedLocation(context.applicationContext).apply { initialize() }

    fun release() = fused.release()

    /** 마지막 위치를 먼저 내보내고, 이후 위치가 바뀔 때마다 내보낸다. */
    val location: Flow<LocationData> = callbackFlow {
        // 마지막 위치가 아직 없을 수 있으니 이쪽 실패는 무시한다.
        fused.getLastKnownLocation(object : FusedLocationListener {
            override fun onChanged(location: LocationData?) { location?.let { trySend(it) } }
            override fun onError(p0: Exception) { Log.i(TAG, "no last location: ${p0.message}") }
        })
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) { location?.let { trySend(it) } }
            // 권한이 아직 없을 때 등: 흐름을 끝내 수집하는 쪽이 다시 시도하게 한다.
            override fun onError(p0: Exception) { Log.w(TAG, "location failed: ${p0.message}", p0); close(p0) }
        }
        fused.registerFusedLocationCallback(listener)
        awaitClose { fused.unregisterFusedLocationCallback(listener) }
    }

    private companion object { const val TAG = "LocationMonitor" }
}
