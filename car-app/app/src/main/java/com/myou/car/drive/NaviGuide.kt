package com.myou.car.drive

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.myou.car.rest.RestArea
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Pleos NaviHelper SDK 래퍼: 휴게소로 경로 안내를 요청하고 경로 시작·도착 소식을 받는다. NAVI_ROUTE 권한 필요. */
class NaviGuide(context: Context) {
    private val navi = NaviHelper(context.applicationContext).apply {
        initialize({ Log.i(TAG, "NaviHelper ready") }, { e -> Log.w(TAG, "NaviHelper init failed: ${e.message}") })
    }

    fun release() = runCatching { navi.release() }

    /** [area] 를 목적지로 내비 경로를 요청한다. 결과는 [events] 로 온다. */
    fun guideTo(area: RestArea) = runCatching {
        navi.requestRoute(RouteInfo(latitude = area.lat, longitude = area.lng, displayLatitude = null, displayLongitude = null, poiName = area.name, poiId = area.id, address = area.name, poiSubId = ""))
    }.onFailure { Log.w(TAG, "requestRoute failed: ${it.message}") }.isSuccess

    /** 경로 시작·목적지 도착·오류를 화면에 보여줄 문구로 내보낸다. */
    val events: Flow<String> = callbackFlow {
        val listener = object : NaviHelperEventListener {
            override fun onRouteStarted(info: RouteStartInfo) {
                trySend("${info.name}로 안내 시작 · %.1fkm · 약 %d분".format(info.distance / 1000.0, info.duration / 60))
            }
            override fun onDestinationArrived(info: DestinationArrivedInfo) { trySend("${info.name}에 도착했어요.") }
            override fun onRouteCancelled() { trySend("경로 안내가 취소됐어요.") }
            override fun onError(code: NaviErrorCode) { trySend("내비게이션 오류: $code") }
        }
        navi.addListener(listener)
        awaitClose { navi.removeListener(listener) }
    }

    private companion object { const val TAG = "NaviGuide" }
}
