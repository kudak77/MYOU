package com.myou.car.rest

/** 휴식모드 진행 단계: 대기 → 제안 → 휴식 중 → 휴식 끝(주행 복귀 대기) → 대기 */
sealed interface RestPhase {
    data object Idle : RestPhase
    data class Suggested(val area: RestArea) : RestPhase
    data class Resting(val area: RestArea, val endsAtMillis: Long) : RestPhase
    data class Finished(val area: RestArea) : RestPhase
}
