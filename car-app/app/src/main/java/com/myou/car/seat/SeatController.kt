package com.myou.car.seat

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import ai.pleos.playground.vehicle.listener.SeatForeAftPositionListener
import ai.pleos.playground.vehicle.listener.SeatHeightPositionListener
import ai.pleos.playground.vehicle.listener.SeatTiltPositionListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Pleos Vehicle SDK의 Seat 도메인 래퍼 (운전석 = ROW_1_LEFT).
 *
 * Seat SDK는 절대 위치 Set API가 없고 moveSeatXxx(FORWARD/BACKWARD/STOP)만 제공하므로,
 * 프리셋 적용은 현재 위치를 읽으면서 목표값까지 움직인 뒤 STOP 하는 폐루프 방식으로 구현한다.
 */
/** 운전석 시트 조회/제어. 실차는 [SeatController], 디버그 에뮬레이터는 [ConsoleSeatController]. */
interface SeatControl {
    /** 시트 위치를 실시간으로 받는다. 수집을 시작하면 현재 값을 먼저 한 번 내보낸다. */
    fun observe(axis: SeatAxis): Flow<Int>
    /** 축의 지원 여부와 범위. 지원하지 않으면 null. */
    suspend fun bounds(axis: SeatAxis): AreaBounds<Int>?
    /** 현재 위치. 읽기 실패 시 null. */
    suspend fun read(axis: SeatAxis): Int?
    fun move(axis: SeatAxis, control: MoveControl)
    fun release()

    /** 이동 중 위치 확인 간격. 정지 명령이 늦을수록 목표를 지나친다. */
    val pollMs: Long get() = POLL_MS

    /** [axis]를 [target]까지 이동하고 시트가 실제로 멈추면 반환한다. 성공하면 true. */
    suspend fun moveTo(axis: SeatAxis, target: Int, tolerance: Int = 1): Boolean =
        driveSeatTo(target, tolerance, read = { read(axis) }, move = { move(axis, it) }, pollMs = pollMs)
}

class SeatController(context: Context) : SeatControl {
    private val vehicle = Vehicle(context.applicationContext).apply { initialize() }
    private val seat get() = vehicle.getSeat()
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)

    override fun release() = vehicle.release()

    /**
     * 사용자가 차량의 전동 시트 스위치로 조절할 때 바뀌는 위치를 실시간으로 받는다.
     * 수집을 시작하면 현재 값을 먼저 한 번 내보낸다.
     */
    override fun observe(axis: SeatAxis): Flow<Int> = callbackFlow {
        read(axis)?.let { trySend(it) }
        val emit: (Int?) -> Unit = { v -> if (v != null) trySend(v) }
        val fail: (Exception) -> Unit = { e -> log("observe $axis", e) }
        val unregister: () -> Unit = when (axis) {
            SeatAxis.FORE_AFT -> object : SeatForeAftPositionListener {
                override fun onSeatForeAftPositionUpdated(p0: Int?) = emit(p0)
                override fun onFailed(p0: Exception) = fail(p0)
            }.let { l -> seat.registerSeatForeAftPosition(driver, l); { seat.unregisterSeatForeAftPosition(driver, l) } }
            SeatAxis.BACKREST -> object : SeatBackrestAnglePositionListener {
                override fun onSeatBackrestAnglePositionUpdated(p0: Int?) = emit(p0)
                override fun onFailed(p0: Exception) = fail(p0)
            }.let { l -> seat.registerSeatBackrestAnglePosition(driver, l); { seat.unregisterSeatBackrestAnglePosition(driver, l) } }
            SeatAxis.HEIGHT -> object : SeatHeightPositionListener {
                override fun onSeatHeightPositionUpdated(p0: Int?) = emit(p0)
                override fun onFailed(p0: Exception) = fail(p0)
            }.let { l -> seat.registerSeatHeightPosition(driver, l); { seat.unregisterSeatHeightPosition(driver, l) } }
            SeatAxis.TILT -> object : SeatTiltPositionListener {
                override fun onSeatTiltPositionUpdated(p0: Int?) = emit(p0)
                override fun onFailed(p0: Exception) = fail(p0)
            }.let { l -> seat.registerSeatTiltPosition(driver, l); { seat.unregisterSeatTiltPosition(driver, l) } }
        }
        awaitClose { unregister() }
    }.distinctUntilChanged()

    /** 축의 지원 여부와 범위. 지원하지 않으면 null. */
    override suspend fun bounds(axis: SeatAxis): AreaBounds<Int>? = suspendCancellableCoroutine { c ->
        val ok: (Map<SeatArea, AreaBounds<Int>>) -> Unit = { map ->
            if (c.isActive) c.resume(map[driver] ?: map.entries.firstOrNull { SeatValue.ROW_1_LEFT in it.key.values }?.value)
        }
        val fail: (Exception) -> Unit = { e -> log("bounds $axis", e); if (c.isActive) c.resume(null) }
        when (axis) {
            SeatAxis.FORE_AFT -> seat.checkSeatForeAftCapabilities(ok, fail)
            SeatAxis.BACKREST -> seat.checkSeatBackrestAngleCapabilities(ok, fail)
            SeatAxis.HEIGHT -> seat.checkSeatHeightCapabilities(ok, fail)
            SeatAxis.TILT -> seat.checkSeatTiltCapabilities(ok, fail)
        }
    }

    /** 현재 위치. 읽기 실패 시 null. */
    override suspend fun read(axis: SeatAxis): Int? = suspendCancellableCoroutine { c ->
        val ok: (Int?) -> Unit = { v -> if (c.isActive) c.resume(v) }
        val fail: (Exception) -> Unit = { e -> log("read $axis", e); if (c.isActive) c.resume(null) }
        when (axis) {
            SeatAxis.FORE_AFT -> seat.getCurrentSeatForeAftPosition(driver, ok, fail)
            SeatAxis.BACKREST -> seat.getCurrentSeatBackrestAnglePosition(driver, ok, fail)
            SeatAxis.HEIGHT -> seat.getCurrentSeatHeightPosition(driver, ok, fail)
            SeatAxis.TILT -> seat.getCurrentSeatTiltPosition(driver, ok, fail)
        }
    }

    override fun move(axis: SeatAxis, control: MoveControl) {
        val done: (SeatArea, MoveControl?) -> Unit = { _, _ -> }
        val fail: (Exception) -> Unit = { e -> log("move $axis $control", e) }
        when (axis) {
            SeatAxis.FORE_AFT -> seat.moveSeatForeAft(driver, control, done, fail)
            SeatAxis.BACKREST -> seat.moveSeatBackrestAngle(driver, control, done, fail)
            SeatAxis.HEIGHT -> seat.moveSeatHeight(driver, control, done, fail)
            SeatAxis.TILT -> seat.moveSeatTilt(driver, control, done, fail)
        }
    }

    /**
     * [axis]를 [target]까지 이동한다. 성공하면 true.
     * FORWARD가 값을 키우는 방향인지 차량마다 다를 수 있어, 반대로 움직이면 한 번 방향을 뒤집는다.
     */
    private fun log(what: String, e: Exception) = Log.w(TAG, "$what failed: ${e.message}", e)

    private companion object {
        const val TAG = "SeatController"
    }
}

internal const val POLL_MS = 100L
/** 명령 후 모터가 움직이기 시작할 때까지 기다리는 최대 시간 */
internal const val START_GRACE_MS = 5_000L
/** 움직이던 시트가 이 시간 동안 멈춰 있으면 끝단 도달로 본다 */
internal const val STALL_MS = 2_000L
/** shortcut: 한 축 최대 이동 시간을 60초로 가정, 실차에서 끝↔끝 이동 시간을 재서 조정한다. */
internal const val MOVE_TIMEOUT_MS = 60_000L

/**
 * 시트 한 축을 [target]까지 움직이고, 시트가 실제로 멈출 때 반환한다 (로딩 표시 = 실제 이동 시간).
 * FORWARD가 값을 키우는 방향인지 차량마다 다를 수 있어, 반대로 움직이면 한 번 방향을 뒤집는다.
 */
internal suspend fun driveSeatTo(
    target: Int,
    tolerance: Int,
    read: suspend () -> Int?,
    move: (MoveControl) -> Unit,
    pollMs: Long = POLL_MS,
): Boolean {
    val start = read() ?: return false
    if (abs(start - target) <= tolerance) return true

    val wantUp = target > start
    var control = if (wantUp) MoveControl.FORWARD else MoveControl.BACKWARD
    var flipped = false
    var started = false
    var last = start
    var idleMs = 0L
    var reached = false

    move(control)
    try {
        withTimeout(MOVE_TIMEOUT_MS) {
            while (true) {
                delay(pollMs)
                val now = read() ?: continue
                if (abs(now - target) <= tolerance || (wantUp && now >= target) || (!wantUp && now <= target)) {
                    reached = true
                    break
                }
                if (!flipped && abs(now - start) > tolerance && (now > start) != wantUp) {
                    flipped = true
                    control = if (control == MoveControl.FORWARD) MoveControl.BACKWARD else MoveControl.FORWARD
                    move(control)
                }
                if (now != last) {
                    last = now
                    idleMs = 0
                    started = true
                } else {
                    idleMs += pollMs
                    // 출발 전에는 모터 지연을 넉넉히 기다리고, 움직이던 중 멈추면 끝단으로 본다.
                    if (idleMs >= if (started) STALL_MS else START_GRACE_MS) break
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        // 아래에서 정지
    } finally {
        withContext(NonCancellable) { move(MoveControl.STOP) }
    }

    // 정지 명령 뒤에도 관성으로 조금 더 갈 수 있어, 값이 한 번 그대로일 때까지 기다린다.
    if (started || reached) {
        var prev = read() ?: return reached
        repeat((STALL_MS / pollMs).toInt()) {
            delay(pollMs)
            val now = read() ?: return@repeat
            if (now == prev) return reached
            prev = now
        }
    }
    return reached
}
