package com.myou.car.sync

import android.content.Context
import android.util.Log
import com.myou.car.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.coroutines.coroutineContext

/**
 * 시연 전용(BuildConfig.DEMO_FEATURES): 스마트폰 앱(MYOU - Phone)이 보낸 운전자 프로필을 받는다.
 * 인증이 없어 같은 기기의 다른 앱도 접근할 수 있다. 실차 배포 전에 서버 동기화로 바꾸고 이 클래스를 지운다.
 * 기기 내부 주소(127.0.0.1)에서만 받으므로 adb forward 로 연결한 같은 PC의 에뮬레이터만 보낼 수 있다.
 *   adb -s <차량 에뮬레이터> forward tcp:9000 tcp:9000   → 폰 에뮬레이터는 http://10.0.2.2:9000 으로 보냄
 * shortcut: 시연용 로컬 연결, 실제 서비스에서는 서버(클라우드) 또는 블루투스 동기화로 바꾼다.
 */
class PhoneSyncServer(
    private val onProfiles: suspend (presetsJson: String, keyNames: Map<String, String>, restJson: String?) -> Int,
    /** GET /status: 차량 상태(휴식 단계·남은 시간·시동·현재 운전자) JSON */
    private val status: suspend () -> String,
    /** GET /profiles: 차량에 저장된 프로필·키 이름·휴식 설정 JSON (폰으로 가져가기) */
    private val export: suspend () -> String,
) {

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEMO_FEATURES) return@withContext
        ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            // accept() 는 취소에 반응하지 않으므로, 화면(ViewModel)이 사라지면 소켓을 닫아 포트를 풀어 준다.
            coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { server.close() }
            Log.i(TAG, "listening on 127.0.0.1:$PORT")
            while (true) {
                coroutineContext.ensureActive()
                val socket = runCatching { server.accept() }.getOrElse { if (server.isClosed) break else continue }
                // 한 연결의 실패(예: 폰이 먼저 끊어 응답을 못 씀)가 수신기 전체를 멈추지 않게 연결마다 따로 처리한다.
                runCatching { socket.use { socket ->
                    socket.soTimeout = 3_000
                    val (status, body) = runCatching { handle(socket.getInputStream()) }
                        .getOrElse { e -> Log.w(TAG, "bad request: ${e.message}"); 400 to """{"ok":false}""" }
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                } }.onFailure { e -> Log.w(TAG, "connection failed: ${e.message}") }
            }
        }
    }

    private suspend fun handle(input: java.io.InputStream): Pair<Int, String> {
        val requestLine = readAsciiLine(input) ?: return 400 to """{"ok":false}"""
        var length = 0
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        when {
            method == "GET" && path == "/status" -> return 200 to status()
            method == "GET" && path == "/profiles" -> return 200 to export()
            method != "POST" || path != "/profiles" -> return 404 to """{"ok":false}"""
        }
        require(length in 1..MAX_BODY) { "body length $length" }
        // Content-Length 는 바이트 수다. 한글은 한 글자가 여러 바이트라 글자 단위로 읽으면 끝을 기다리다 시간 초과가 난다.
        val buf = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(buf, read, length - read); if (n < 0) break; read += n }
        val o = JSONObject(String(buf, 0, read, Charsets.UTF_8))
        val names = o.optJSONObject("keyNames")?.let { k -> k.keys().asSequence().associateWith { k.getString(it) } } ?: emptyMap()
        val count = onProfiles(o.getJSONArray("presets").toString(), names, o.optJSONObject("restSettings")?.toString())
        return 200 to """{"ok":true,"count":$count}"""
    }

    /** 헤더 한 줄(CRLF 까지)을 바이트 단위로 읽는다. 스트림이 끝나면 null. */
    private fun readAsciiLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private companion object {
        const val TAG = "PhoneSync"
        const val PORT = 9000
        const val MAX_BODY = 64 * 1024
    }
}
