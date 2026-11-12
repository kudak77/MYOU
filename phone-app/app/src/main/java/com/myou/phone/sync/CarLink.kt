package com.myou.phone.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myou.phone.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 차량 상태를 주기적으로 확인하고, 휴식이 끝나면 폰에 알림을 띄운다 (앱 프로세스가 살아 있는 동안).
 * shortcut: 시연용으로 3초마다 차량에 직접 물어본다, 실제 서비스는 서버 푸시(FCM 등)로 받는다.
 */
object CarLink {
    private val _status = MutableStateFlow<CarStatus?>(null)
    /** 마지막으로 받은 차량 상태. 연결이 안 되면 null */
    val status: StateFlow<CarStatus?> = _status.asStateFlow()

    private const val CHANNEL = "rest_end"
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "휴식 종료 알림", NotificationManager.IMPORTANCE_HIGH))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var lastPhase: String? = null
            while (true) {
                val s = runCatching { CarSync.fetchStatus() }.getOrNull()
                _status.value = s
                if (s != null) {
                    if (lastPhase == "Resting" && s.restPhase == "Finished") notifyRestEnd(app, s)
                    lastPhase = s.restPhase
                }
                delay(3_000)
            }
        }
    }

    private fun notifyRestEnd(app: Context, s: CarStatus) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(app).notify(
            2001,
            NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("휴식 시간이 끝났어요")
                .setContentText("${s.restArea ?: "휴게소"} · 차량에서 '주행 복귀'를 누르면 시트가 운전 자세로 돌아가요.")
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build(),
        )
    }
}
