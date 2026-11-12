package com.myou.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpsertTest {
    private val a = SeatPreset("a", "출퇴근", DEFAULT_POSITIONS, keyId = "k1")
    private val b = SeatPreset("b", "주말", DEFAULT_POSITIONS, keyId = "k2")

    @Test fun addsNewAtEnd() {
        val c = SeatPreset("c", "가족", DEFAULT_POSITIONS)
        assertEquals(listOf("a", "b", "c"), listOf(a, b).upsert(c).map { it.id })
    }

    @Test fun replacesInPlace() {
        val list = listOf(a, b).upsert(a.copy(name = "출근"))
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals("출근", list[0].name)
    }

    @Test fun takingAKeyUnlinksItFromOthers() {
        val list = listOf(a, b).upsert(b.copy(keyId = "k1"))
        assertNull(list.first { it.id == "a" }.keyId)
        assertEquals("k1", list.first { it.id == "b" }.keyId)
    }

    @Test fun clearingKeyLeavesOthersAlone() {
        val list = listOf(a, b).upsert(a.copy(keyId = null))
        assertNull(list.first { it.id == "a" }.keyId)
        assertEquals("k2", list.first { it.id == "b" }.keyId)
    }
}
