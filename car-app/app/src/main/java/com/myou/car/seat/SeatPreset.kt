package com.myou.car.seat

/** 프리셋에 저장하는 시트 조절 축. 차량이 지원하지 않는 축은 값이 null로 저장된다. */
enum class SeatAxis(val label: String) {
    FORE_AFT("앞뒤 위치"),
    BACKREST("등받이 각도"),
    HEIGHT("높이"),
    TILT("쿠션 기울기"),
}

/** 운전자 한 명의 시트 프리셋. */
data class SeatPreset(
    val id: String,
    val name: String,
    val positions: Map<SeatAxis, Int>,
    /** 이 운전자의 디지털 키. 키로 접근하면 이 프리셋으로 시트를 맞춘다. null = 연결 안 됨 */
    val keyId: String? = null,
    /** 이 운전자의 휴식(무중력) 자세. null 이면 차량 기본 휴식 자세를 쓴다. */
    val restPositions: Map<SeatAxis, Int>? = null,
)
