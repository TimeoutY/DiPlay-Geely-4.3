package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSessionFocusTest {
    @Test
    fun sessionEndRunsRegisteredHooksUntilRemoved() {
        var calls = 0
        val hook: () -> Unit = { calls++ }
        AudioSessionFocus.addSessionEndHook(hook)
        AudioSessionFocus.addSessionEndHook { throw IllegalStateException("must not break other hooks") }
        AudioSessionFocus.onSessionEnded()
        assertEquals(1, calls)
        AudioSessionFocus.removeSessionEndHook(hook)
        AudioSessionFocus.onSessionEnded()
        assertEquals(1, calls)
    }
}
