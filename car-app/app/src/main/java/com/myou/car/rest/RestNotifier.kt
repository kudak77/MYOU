package com.myou.car.rest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myou.car.R
import java.util.Locale

/**
 * 휴식 종료 알림: 시스템 알림 + 음성 안내.
 * 음성은 Pleos TextToSpeech SDK(ON_DEVICE 모드: 앱 등록 불필요)를 쓰고, 초기화에 실패하면 안드로이드 기본 TTS 로 대신한다.
 */
class RestNotifier(context: Context) {
    private val app = context.applicationContext
    private var pleosReady = false
    private val pleos = runCatching {
        PleosTts(app, Mode.ON_DEVICE).apply {
            initialize(object : OnInitializeListener {
                override fun onSuccess() { pleosReady = true; Log.i(TAG, "Pleos TTS ready") }
                override fun onFailure(p0: ErrorType) { Log.w(TAG, "Pleos TTS init failed: $p0") }
            })
        }
    }.onFailure { Log.w(TAG, "Pleos TTS unavailable: ${it.message}") }.getOrNull()

    private var androidReady = false
    private val androidTts = TextToSpeech(app) { status ->
        androidReady = status == TextToSpeech.SUCCESS
    }.apply { language = Locale.KOREAN }

    init {
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "휴식모드 알림", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun notify(title: String, text: String) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(app).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(app, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        speak(text)
    }

    fun speak(text: String) {
        when {
            pleosReady && pleos != null -> runCatching { pleos?.speak(text) }.onFailure { Log.w(TAG, "Pleos speak failed: ${it.message}") }
            androidReady -> androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rest")
        }
    }

    fun release() {
        runCatching { pleos?.release() }
        androidTts.shutdown()
    }

    private companion object {
        const val TAG = "RestNotifier"
        const val CHANNEL = "rest_mode"
        const val NOTIFICATION_ID = 1001
    }
}
