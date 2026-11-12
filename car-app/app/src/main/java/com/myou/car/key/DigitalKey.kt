package com.myou.car.key

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** 사용자가 정하는 키 이름의 최대 길이 */
const val MAX_KEY_NAME = 12

/** 이 차량에 등록된 디지털 키 */
data class DigitalKey(val id: String, val label: String)

/** shortcut: 시연용 고정 키 3개, 현대차 디지털 키 연동 후 차량에 등록된 키 목록으로 바꾼다. */
val DEMO_KEYS = listOf(
    DigitalKey("phone-key-1", "스마트폰 키 1"),
    DigitalKey("phone-key-2", "스마트폰 키 2"),
    DigitalKey("phone-key-3", "스마트폰 키 3"),
)

/** 차량이 감지한 디지털 키 이벤트. [keyId]는 인증된 키의 식별자. */
sealed interface DigitalKeyEvent {
    val keyId: String
    data class Approaching(override val keyId: String, val distanceM: Double) : DigitalKeyEvent
    data class Authenticated(override val keyId: String) : DigitalKeyEvent
}

/**
 * 스마트폰 디지털 키(BLE/UWB)의 접근 감지와 인증 결과.
 * Pleos: 공개 SDK에는 디지털 키 인증 인터페이스가 없다. 현대차 디지털 키 시스템과 연동 협의 후 이 인터페이스를 구현한다.
 */
interface DigitalKeyGateway {
    val events: Flow<DigitalKeyEvent>
}

/** 시연용: 운전자가 다가와(10m → 5m → 2m) 인증되는 과정을 흉내 낸다. */
class SimulatedDigitalKeyGateway : DigitalKeyGateway {
    private val _events = MutableSharedFlow<DigitalKeyEvent>(extraBufferCapacity = 8)
    override val events: Flow<DigitalKeyEvent> = _events

    suspend fun simulateApproach(keyId: String) {
        for (d in listOf(10.0, 5.0, 2.0)) {
            _events.emit(DigitalKeyEvent.Approaching(keyId, d))
            delay(700)
        }
        _events.emit(DigitalKeyEvent.Authenticated(keyId))
    }
}
