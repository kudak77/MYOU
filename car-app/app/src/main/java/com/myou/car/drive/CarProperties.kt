package com.myou.car.drive

import android.car.Car
import android.car.VehicleIgnitionState
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * 안드로이드 차량 표준 API(android.car)로 시동 상태와 주차 브레이크를 읽는다.
 * android.car 는 기기에 따라 없을 수 있어(예: Pleos Connect 에뮬레이터 v2.1.0), 이 클래스만 그 API 를 참조한다.
 * 다른 클래스는 [create] 로만 얻고, 라이브러리가 없으면 null 을 받는다.
 */
class CarProperties private constructor(private val car: Car) {
    private val props get() = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager

    fun disconnect() = car.disconnect()

    /** 시동 ON/OFF 를 [onValue] 로 알린다. 등록에 실패하면 null, 성공하면 해제 함수. */
    fun watchIgnition(onValue: (Boolean) -> Unit): (() -> Unit)? =
        watch(VehiclePropertyIds.IGNITION_STATE) { v ->
            (v as? Int)?.let { onValue(it == VehicleIgnitionState.ON || it == VehicleIgnitionState.START) }
        }

    /** 주차 브레이크 체결 여부를 [onValue] 로 알린다. 등록에 실패하면 null, 성공하면 해제 함수. */
    fun watchParkingBrake(onValue: (Boolean) -> Unit): (() -> Unit)? =
        watch(VehiclePropertyIds.PARKING_BRAKE_ON) { v -> (v as? Boolean)?.let(onValue) }

    private fun watch(propertyId: Int, onValue: (Any?) -> Unit): (() -> Unit)? {
        val p = props ?: return null
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) = onValue(value.value)
            override fun onErrorEvent(propertyId: Int, areaId: Int) { Log.w(TAG, "property $propertyId error") }
        }
        val ok = runCatching { p.registerCallback(cb, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE) }.getOrDefault(false)
        return if (ok) ({ p.unregisterCallback(cb) }) else null
    }

    companion object {
        private const val TAG = "CarProperties"

        /** android.car 가 없거나 연결에 실패하면 null. */
        fun create(context: Context): CarProperties? = try {
            Class.forName("android.car.Car")
            CarProperties(Car.createCar(context.applicationContext))
        } catch (t: Throwable) {
            Log.i(TAG, "android.car 사용 불가: ${t.message}")
            null
        }
    }
}
