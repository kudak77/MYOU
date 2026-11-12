package com.myou.phone

import android.Manifest
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.myou.phone.sync.CarWatchService
import com.myou.phone.ui.MyouApp
import com.myou.phone.ui.theme.MyouTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 차량 휴식이 끝났을 때 폰 알림을 띄우려면 필요하다.
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { MyouTheme { MyouApp() } }
    }

    override fun onStart() {
        super.onStart()
        // 백그라운드로 가도 휴식 종료 알림을 받도록, 화면이 보일 때 차량 확인 서비스를 켠다.
        CarWatchService.start(this)
    }
}
