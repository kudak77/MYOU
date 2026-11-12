package com.myou.car

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import com.myou.car.ui.SeatPresetScreen
import com.myou.car.ui.theme.MyouTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 휴게소 도착 감지(위치)와 휴식 종료 알림에 필요. 거부해도 시트 기능은 그대로 쓸 수 있다.
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(
            arrayOf(
                "pleos.car.permission.FUSED_LOCATION", // Pleos 위치 서비스도 런타임 허용이 필요하다
                // 휴게소 경로 안내(NaviHelper)와 음성 안내(TTS)
                "pleos.car.permission.NAVI_ROUTE",
                "pleos.car.permission.NAVI_ROUTE_SEARCH",
                "pleos.car.permission.TTS_SERVICE",
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
        setContent {
            MyouTheme {
                Surface { SeatPresetScreen() }
            }
        }
    }
}
