package com.myou.car.rest

import android.content.Context
import org.json.JSONArray
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class RestAreaType(val label: String) { SERVICE_AREA("휴게소"), DROWSY_SHELTER("졸음쉼터") }

data class RestArea(
    val id: String,
    val name: String,
    val type: RestAreaType,
    val lat: Double,
    val lng: Double,
    /** 이 반경 안에 들어오면 도착으로 본다 */
    val radiusM: Double,
)

/** 반경 안에 들어간 휴게소 중 가장 가까운 곳. 없으면 null. */
fun List<RestArea>.arrivedAt(lat: Double, lng: Double): RestArea? =
    map { it to distanceMeters(lat, lng, it.lat, it.lng) }
        .filter { (area, d) -> d <= area.radiusM }
        .minByOrNull { it.second }
        ?.first

/** 두 좌표 사이 거리(m), 하버사인 공식 */
fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * 6_371_000.0 * asin(sqrt(h))
}

/**
 * assets/rest_areas.json 의 휴게소·졸음쉼터 목록.
 * 졸음쉼터 253곳: 공공데이터포털 '한국도로공사_졸음쉼터'(2026-02-25 기준), tools/rest-areas/build_rest_areas.py 로 생성.
 * shortcut: 휴게소 3곳("sample": true)은 시연용 좌표, 한국도로공사 오픈API(data.ex.co.kr) 키를 받으면 실제 목록으로 교체한다.
 */
fun loadRestAreas(context: Context): List<RestArea> {
    val arr = JSONArray(context.assets.open("rest_areas.json").bufferedReader().use { it.readText() })
    return List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        RestArea(
            id = o.getString("id"),
            name = o.getString("name"),
            type = RestAreaType.valueOf(o.getString("type")),
            lat = o.getDouble("lat"),
            lng = o.getDouble("lng"),
            radiusM = o.getDouble("radiusM"),
        )
    }
}
