package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseLoopGuardTest {
    private var t = 1_000_000L
    private val guard = PauseLoopGuard { t }
    private val play = PauseLoopGuard.Key.PLAY
    private val pause = PauseLoopGuard.Key.PAUSE
    private val toggle = PauseLoopGuard.Key.TOGGLE

    @Test
    fun keysRightAfterFocusChangeAreCarEchoes() {
        guard.onFocusChange()
        t += 2_000
        assertFalse(guard.keyDecision(toggle, true, true).send)
        t += 1_500
        assertTrue(guard.keyDecision(toggle, true, true).send)
    }

    @Test
    fun keysRightAfterMediaStartAreIgnored() {
        guard.onMediaStart()
        t += 500
        assertFalse(guard.keyDecision(pause, true, true).send)
    }

    @Test
    fun redundantExplicitKeysNeverFlipTheIphone() {
        // The qa1 loop: a car PAUSE while already paused used to be sent as a toggle and play again.
        assertFalse(guard.keyDecision(pause, false, true).send)
        assertFalse(guard.keyDecision(play, true, true).send)
        assertTrue(guard.keyDecision(pause, true, true).send)
    }

    @Test
    fun debounceAndStormBreaker() {
        assertTrue(guard.keyDecision(toggle, null, true).send)
        t += 300
        assertFalse(guard.keyDecision(toggle, null, true).reason.isEmpty())
        assertFalse(guard.keyDecision(toggle, null, true).send)
        t += 2_000
        assertTrue(guard.keyDecision(toggle, null, true).send)
        t += 2_000
        assertTrue(guard.keyDecision(toggle, null, true).send)
        t += 2_000
        val storm = guard.keyDecision(toggle, null, true)
        assertFalse(storm.send)
        assertTrue(storm.reason.startsWith("key storm"))
        t += 10_000
        assertFalse(guard.keyDecision(toggle, null, true).send)
        t += 21_000
        assertTrue(guard.keyDecision(toggle, null, true).send)
    }

    @Test
    fun guardOffForwardsEverything() {
        guard.onFocusChange()
        assertTrue(guard.keyDecision(pause, false, false).send)
    }

    @Test
    fun focusReRequestsAreThrottledAndSlowerUnderContention() {
        assertTrue(guard.mayReRequestFocus().send)
        t += 5_000
        assertFalse(guard.mayReRequestFocus().send)
        t += 6_000
        assertTrue(guard.mayReRequestFocus().send)
        repeat(3) { guard.onFocusLoss(); t += 1_000 }
        assertTrue(guard.contention())
        t += 15_000
        assertFalse(guard.mayReRequestFocus().send)
        t += 50_000
        assertTrue(guard.mayReRequestFocus().send)
        t += 200_000
        assertFalse(guard.contention())
    }

    @Test
    fun autoResumeAtMostOncePerFiveMinutes() {
        PauseLoopGuard.fightMode = false
        PauseLoopGuard.lastAutoResumeAt = 0L
        assertTrue(PauseLoopGuard.autoResumeAllowed(PauseLoopGuard.AUTO_RESUME_MIN_GAP_MS))
        assertFalse(PauseLoopGuard.autoResumeAllowed(PauseLoopGuard.AUTO_RESUME_MIN_GAP_MS - 1))
        PauseLoopGuard.lastAutoResumeAt = Long.MIN_VALUE / 2
        assertEquals(true, PauseLoopGuard.autoResumeAllowed(0L))
    }

    @Test
    fun fightModeStopsFurtherAutoResumes() {
        PauseLoopGuard.fightMode = false
        PauseLoopGuard.lastAutoResumeAt = Long.MIN_VALUE / 2
        PauseLoopGuard.noteAutoResumeSent(1_000L)
        assertTrue(PauseLoopGuard.notePostResumePause(1_000L + 3_000L))
        assertTrue(PauseLoopGuard.fightMode)
        assertFalse(PauseLoopGuard.autoResumeAllowed(1_000L + PauseLoopGuard.AUTO_RESUME_MIN_GAP_MS))
        PauseLoopGuard.fightMode = false
    }
}
