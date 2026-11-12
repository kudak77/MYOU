package com.myou.car.rest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestAreaTest {
    private val big = RestArea("a", "휴게소A", RestAreaType.SERVICE_AREA, 37.0, 127.0, 300.0)
    private val small = RestArea("b", "쉼터B", RestAreaType.DROWSY_SHELTER, 37.0, 127.003, 100.0)
    private val areas = listOf(big, small)

    @Test fun distanceIsAboutRight() {
        // 위도 0.001° ≈ 111m
        assertEquals(111.2, distanceMeters(37.0, 127.0, 37.001, 127.0), 0.5)
    }

    @Test fun insideRadiusArrives() {
        assertEquals("a", areas.arrivedAt(37.002, 127.0)?.id) // ≈222m < 300m
    }

    @Test fun outsideRadiusIsNull() {
        assertNull(areas.arrivedAt(37.003, 127.0)) // ≈334m > 300m
    }

    @Test fun overlappingPicksNearest() {
        // A까지 ≈240m(반경 안), B까지 ≈27m(반경 안) → 더 가까운 B
        assertEquals("b", areas.arrivedAt(37.0, 127.0027)?.id)
    }
}
