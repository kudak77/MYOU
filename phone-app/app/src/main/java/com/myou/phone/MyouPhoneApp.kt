package com.myou.phone

import android.app.Application
import com.myou.phone.sync.CarLink

class MyouPhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CarLink.start(this) // 차량 상태 확인, 휴식 종료 알림
    }
}
