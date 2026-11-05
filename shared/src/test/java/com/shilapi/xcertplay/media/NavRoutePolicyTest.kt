package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.media.NavRoutePolicy.Route
import com.shilapi.xcertplay.media.NavRoutePolicy.Sink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavRoutePolicyTest {
    private val ms = 1_000_000L
    private var t = 10_000 * ms
    private val p = NavRoutePolicy()

    private fun chunk(speech: Boolean, host: Boolean) = p.onChunk(t, speech, host).also { t += 10 * ms }

    @Test
    fun leadingSilenceIsDroppedAndDecidesNothing() {
        repeat(20) { assertEquals(Sink.DROP, chunk(false, false).sink) }
        assertEquals(Route.NONE, p.route)
    }

    @Test
    fun streamOpenedBeforeMusicMixesTheFirstPromptDuringMusic() {
        assertEquals(Sink.DROP, chunk(false, false).sink)
        assertEquals(Route.MIX, chunk(true, true).switchedTo)
    }

    @Test
    fun routeIsChosenAgainForEachPrompt() {
        assertEquals(Route.OWN, chunk(true, false).switchedTo) // music paused
        repeat(30) { assertEquals(Sink.OWN, chunk(false, false).sink) }
        t += 1_500 * ms // gap: next prompt
        assertEquals(Route.MIX, chunk(true, true).switchedTo) // music playing now
        repeat(10) { assertEquals(Sink.MIX, chunk(true, true).sink) }
        // Silence inside the prompt keeps its timing; after the prompt it is dropped.
        assertEquals(Sink.MIX, chunk(false, true).sink)
        t += 1_500 * ms
        assertEquals(Sink.DROP, chunk(false, true).sink)
    }

    @Test
    fun routeDoesNotFlipInsideOnePrompt() {
        chunk(true, true)
        repeat(50) { assertNull(chunk(true, false).switchedTo) }
        assertEquals(Route.MIX, p.route)
    }

    @Test
    fun fallbackLastsOnlyForTheCurrentPrompt() {
        chunk(true, true)
        p.fellBackToOwn()
        assertEquals(Sink.OWN, chunk(true, false).sink)
        t += 2_000 * ms
        assertEquals(Route.MIX, chunk(true, true).switchedTo)
    }
}
