package com.myou.car.seat

import ai.pleos.playground.vehicle.constant.control.MoveControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가상 시간에서 도는 가짜 시트: 명령 후 [latencyMs] 뒤 출발, 100ms마다 1칸, STOP 후 [coast]칸 더 감. */
private class FakeSeat(scope: CoroutineScope, var pos: Int, latencyMs: Long, private val coast: Int = 0) {
    private var control = MoveControl.STOP
    private var commandAt = 0L
    private var coastLeft = 0
    private var clock = 0L

    init {
        scope.launch {
            while (true) {
                delay(100); clock += 100
                when {
                    control == MoveControl.STOP && coastLeft > 0 -> { pos += lastDir; coastLeft-- }
                    control != MoveControl.STOP && clock - commandAt >= latencyMs -> pos += dir(control)
                }
            }
        }
    }

    private var lastDir = 0
    private fun dir(c: MoveControl) = if (c == MoveControl.FORWARD) 1 else -1

    fun move(c: MoveControl) {
        if (c == MoveControl.STOP && control != MoveControl.STOP) { lastDir = dir(control); coastLeft = coast }
        if (c != control) commandAt = clock
        control = c
    }
}

class DriveSeatToTest {
    private suspend fun drive(seat: FakeSeat, target: Int) =
        driveSeatTo(target, tolerance = 0, read = { seat.pos }, move = seat::move)

    @Test fun slowMotorStartStillReachesTarget() = runTest {
        val seat = FakeSeat(backgroundScope, pos = 0, latencyMs = 3_000) // 예전 2초 기준이면 실패했을 지연
        assertTrue(drive(seat, 20))
        assertEquals(20, seat.pos)
        // 3초 지연 + 20칸 × 0.1초 ≈ 5초. 로딩이 실제 이동 시간과 0.3초 안에서 맞아야 한다.
        assertEquals(5_000.0, testScheduler.currentTime.toDouble(), 300.0)
    }

    @Test fun waitsForCoastingToFinish() = runTest {
        val seat = FakeSeat(backgroundScope, pos = 0, latencyMs = 0, coast = 3)
        assertTrue(drive(seat, 10))
        val returnedAt = testScheduler.currentTime
        val posAtReturn = seat.pos
        testScheduler.advanceTimeBy(1_000)
        assertEquals("반환 뒤에는 시트가 더 움직이지 않아야 한다", posAtReturn, seat.pos)
        assertTrue(returnedAt > 1_000) // 10칸 이동(1초) 뒤 관성 구간까지 기다림
    }

    @Test fun seatThatNeverMovesFailsAfterGrace() = runTest {
        val seat = FakeSeat(backgroundScope, pos = 0, latencyMs = Long.MAX_VALUE)
        assertFalse(drive(seat, 20))
        assertEquals(START_GRACE_MS.toDouble(), testScheduler.currentTime.toDouble(), 200.0)
    }

    @Test fun alreadyThereReturnsImmediately() = runTest {
        val seat = FakeSeat(backgroundScope, pos = 7, latencyMs = 0)
        assertTrue(drive(seat, 7))
        assertEquals(0L, testScheduler.currentTime)
    }
}
