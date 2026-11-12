package com.myou.car.rest

import com.myou.car.seat.SeatAxis

/** 휴식모드 설정. null 인 공조·조명 값은 건드리지 않는다. */
data class RestSettings(
    val minutes: Int = 15,
    /** 무중력 휴식 자세 (0..100) */
    val seat: Map<SeatAxis, Int> = ZERO_GRAVITY,
    val temperatureC: Float? = 22f,
    val fanSpeed: Int? = 2,
    /** 무드등 밝기 % */
    val ambientPercent: Int? = 20,
) {
    companion object {
        /** shortcut: 기본 무중력 자세는 가정값, 실차 시트 범위를 확인해 조정한다 (사용자는 '지금 자세를 휴식 자세로'로 바꿀 수 있음). */
        val ZERO_GRAVITY = mapOf(SeatAxis.FORE_AFT to 85, SeatAxis.BACKREST to 85, SeatAxis.HEIGHT to 20, SeatAxis.TILT to 75)
        val MINUTE_CHOICES = listOf(1, 5, 10, 15, 20, 30, 45, 60)
    }
}
