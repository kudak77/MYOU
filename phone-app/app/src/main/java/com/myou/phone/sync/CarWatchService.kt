package com.myou.phone.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myou.phone.MainActivity
import com.myou.phone.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 차량과 연결된 동안 앱을 포그라운드로 붙잡아 둔다.
 * 앱이 백그라운드로 가면 안드로이드가 프로세스를 얼려 [CarLink]의 상태 확인이 멈추고, 휴식 종료 알림을 놓친다.
 * 차량이 1분 넘게 연결되지 않거나, 시동이 꺼지고 휴식 중이 아니면 스스로 끝난다.
 * 백그라운드에서는 포그라운드 서비스를 시작할 수 없으므로 화면이 보일 때(MainActivity.onStart) 시작한다.
 * shortcut: 시연용 폴링 유지, 실제 서비스에서는 서버 푸시(FCM)로 바꾸고 이 서비스는 없앤다.
 */
class CarWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "차량 연결", NotificationManager.IMPORTANCE_LOW))
        val n = build("차량 연결을 확인하고 있어요.")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (watch == null) watch = scope.launch { watchCar() }
        return START_NOT_STICKY
    }

    private suspend fun watchCar() {
        var lostSince: Long? = null
        var lastText: String? = null
        while (true) {
            val s = CarLink.status.value
            val now = System.currentTimeMillis()
            if (s == null) {
                if (lostSince == null) lostSince = now
                if (now - lostSince > LOST_TIMEOUT_MS) break
            } else {
                lostSince = null
                if (s.ignitionOn == false && s.restPhase == "Idle") break
                val text = when (s.restPhase) {
                    "Resting" -> "휴식 중 · ${s.restArea ?: "휴게소"} · 끝나면 알려 드려요."
                    "Finished" -> "휴식 시간이 끝났어요."
                    else -> "차량과 연결됨" + (s.driver?.let { " · 운전자 $it" } ?: "")
                }
                if (text != lastText) {
                    lastText = text
                    runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(text)) }
                }
            }
            delay(CHECK_MS)
        }
        stopSelf()
    }

    private fun build(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("M:YOU")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "car_watch"
        private const val NOTIFICATION_ID = 2002
        private const val CHECK_MS = 3_000L
        private const val LOST_TIMEOUT_MS = 60_000L

        /** 화면이 보일 때만 부른다. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CarWatchService::class.java)) }
        }
    }
}
