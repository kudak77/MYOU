package com.myou.phone.data

// 차량 앱(com.myou.car)과 같은 모델·저장 형식. 나중에 두 앱을 동기화할 때 그대로 주고받는다.

/** 시트 조절 축. 값은 0..100 */
enum class SeatAxis(val label: String) {
    FORE_AFT("앞뒤 위치"),
    BACKREST("등받이 각도"),
    HEIGHT("높이"),
    TILT("쿠션 기울기"),
}

/** 운전자 프로필 = 시트 프리셋 + 연결된 디지털 키 */
data class SeatPreset(
    val id: String,
    val name: String,
    val positions: Map<SeatAxis, Int>,
    /** 이 운전자의 디지털 키. null = 연결 안 됨 */
    val keyId: String? = null,
    /** 이 운전자의 휴식(무중력) 자세. null 이면 차량 기본 휴식 자세를 쓴다. */
    val restPositions: Map<SeatAxis, Int>? = null,
)

/** 휴식모드 설정 (차량 하나에 한 벌). 폰에서 정해 차량으로 보낸다. */
data class RestPrefs(
    val minutes: Int = 15,
    val temperatureC: Float = 22f,
    val fanSpeed: Int = 2,
    /** 무드등 밝기 % */
    val ambientPercent: Int = 20,
) {
    companion object { val MINUTE_CHOICES = listOf(1, 5, 10, 15, 20, 30, 45, 60) }
}

/** 차량 앱 기본 휴식(무중력) 자세와 같은 값 */
val ZERO_GRAVITY = mapOf(SeatAxis.FORE_AFT to 85, SeatAxis.BACKREST to 85, SeatAxis.HEIGHT to 20, SeatAxis.TILT to 75)

data class DigitalKey(val id: String, val label: String)

/** shortcut: 차량 앱과 같은 시연용 키 3개, 현대차 디지털 키 연동 후 실제 등록 키 목록으로 바꾼다. */
val DEMO_KEYS = listOf(
    DigitalKey("phone-key-1", "스마트폰 키 1"),
    DigitalKey("phone-key-2", "스마트폰 키 2"),
    DigitalKey("phone-key-3", "스마트폰 키 3"),
)

const val MAX_NAME = 12

val DEFAULT_POSITIONS = SeatAxis.entries.associateWith { 50 }

/**
 * [preset] 을 저장(추가 또는 교체)한다. 키 하나는 프로필 하나에만 연결되므로,
 * 같은 키를 쓰던 다른 프로필의 연결은 푼다.
 */
fun List<SeatPreset>.upsert(preset: SeatPreset): List<SeatPreset> {
    val others = filterNot { it.id == preset.id }.map { p ->
        if (preset.keyId != null && p.keyId == preset.keyId) p.copy(keyId = null) else p
    }
    val i = indexOfFirst { it.id == preset.id }
    return if (i < 0) others + preset else others.toMutableList().apply { add(i, preset) }
}
