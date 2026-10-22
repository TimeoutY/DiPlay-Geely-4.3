package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCoalescingTest {
    private fun c(id: Int, x: Double, down: Boolean = true) = AirPlayContact(id, x, 0.5, down)

    @Test
    fun movesOfTheSameFingersMerge() {
        assertTrue(TouchCoalescing.isMoveOf(listOf(c(0, 0.1)), listOf(c(0, 0.2))))
        assertTrue(TouchCoalescing.isMoveOf(listOf(c(0, 0.1), c(1, 0.3)), listOf(c(0, 0.2), c(1, 0.4))))
    }

    @Test
    fun pressesReleasesAndFingerChangesNeverMerge() {
        assertFalse(TouchCoalescing.isMoveOf(listOf(c(0, 0.1)), listOf(c(0, 0.1, down = false))))
        assertFalse(TouchCoalescing.isMoveOf(listOf(c(0, 0.1, down = false)), listOf(c(0, 0.1))))
        assertFalse(TouchCoalescing.isMoveOf(listOf(c(0, 0.1)), listOf(c(0, 0.1), c(1, 0.2))))
        assertFalse(TouchCoalescing.isMoveOf(listOf(c(0, 0.1)), listOf(c(1, 0.1))))
        assertFalse(TouchCoalescing.isMoveOf(emptyList(), emptyList()))
    }
}
