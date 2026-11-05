package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review rv1 STALE-DUCK and STICKY-B/CLIP-C bus support. */
class NavMixBusLifecycleTest {
    private var now = 1_000_000_000L
    private val bus = NavMixBus(nowNs = { now }, log = {})

    private fun pcm(frames: Int, value: Int): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames * 2) { out[i * 2] = value.toByte(); out[i * 2 + 1] = (value shr 8).toByte() }
        return out
    }

    private fun lastSample(b: ByteArray) = ((b[b.size - 4].toInt() and 0xff) or (b[b.size - 3].toInt() shl 8)).toShort().toInt()

    @Test
    fun remoteDuckDoesNotSurviveSessionTeardown() {
        val old = Any()
        bus.attachHost(old, 48000, 2)
        bus.onRemoteDuck(0.2, null) // session drops before unduckAudio
        bus.detachHost(old)
        now += 3_000_000_000L
        val fresh = Any()
        bus.attachHost(fresh, 48000, 2)
        val music = pcm(48000, 16384)
        bus.processHost(fresh, music, 0, music.size)
        assertEquals(16384, lastSample(music))
    }

    @Test
    fun guidanceSpeechStateDoesNotLeakIntoNewHost() {
        val old = Any()
        bus.attachHost(old, 48000, 2)
        val speech = pcm(4800, 6000)
        bus.pushNav(speech, 0, speech.size, 48000, 2, NavMixBus.Resampler())
        bus.detachHost(old)
        val fresh = Any()
        bus.attachHost(fresh, 48000, 2)
        val music = pcm(4800, 16384)
        bus.processHost(fresh, music, 0, music.size)
        assertEquals(16384, lastSample(music))
    }

    @Test
    fun silenceIsNotPendingSpeechButSpeechIs() {
        val host = Any()
        bus.attachHost(host, 48000, 2)
        val r = NavMixBus.Resampler()
        val silence = pcm(4800, 0)
        bus.pushNav(silence, 0, silence.size, 48000, 2, r)
        assertTrue(bus.pendingNavFrames() > 0)
        assertFalse(bus.hasPendingSpeech())
        val speech = pcm(4800, 6000)
        bus.pushNav(speech, 0, speech.size, 48000, 2, r)
        assertTrue(bus.hasPendingSpeech())
    }

    @Test
    fun pendingGuidanceIsHandedOffNotLost() {
        val host = Any()
        bus.attachHost(host, 48000, 2)
        val speech = pcm(4800, 6000)
        bus.pushNav(speech, 0, speech.size, 48000, 2, NavMixBus.Resampler())
        val taken = bus.takePendingNav("test")
        assertTrue("took ${taken.size}", taken.size in 9000..9700)
        assertTrue(taken.any { it.toInt() == 6000 })
        assertEquals(0, bus.pendingNavFrames())
        assertFalse(bus.hasPendingSpeech())
    }

    @Test
    fun hostWroteWithinTracksRecentMusicWrites() {
        val host = Any()
        bus.attachHost(host, 48000, 2)
        assertFalse(bus.hostWroteWithin(NavRoutePolicy.DECIDE_HOST_RECENT_NS))
        val music = pcm(480, 100)
        bus.processHost(host, music, 0, music.size)
        assertTrue(bus.hostWroteWithin(NavRoutePolicy.DECIDE_HOST_RECENT_NS))
        now += 300_000_000L
        assertFalse(bus.hostWroteWithin(NavRoutePolicy.DECIDE_HOST_RECENT_NS))
        assertTrue(bus.hostFlowing())
    }
}

class NavMixBusHandoffTrimTest {
    private val bus = NavMixBus(nowNs = { 1_000_000_000L }, log = {})

    private fun pcm(frames: Int, value: Int): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames * 2) { out[i * 2] = value.toByte(); out[i * 2 + 1] = (value shr 8).toByte() }
        return out
    }

    @Test
    fun trailingSilenceIsNotHandedOffAndAWordTailIsNotPendingSpeech() {
        val host = Any()
        bus.attachHost(host, 48000, 2)
        val r = NavMixBus.Resampler()
        val tail = pcm(240, 6000) // 5 ms of a word
        bus.pushNav(tail, 0, tail.size, 48000, 2, r)
        val silence = pcm(48000, 0)
        bus.pushNav(silence, 0, silence.size, 48000, 2, r)
        assertFalse(bus.hasPendingSpeech())
        val taken = bus.takePendingNav("test")
        assertTrue("took ${taken.size}", taken.size <= 2 * 260)
    }
}
