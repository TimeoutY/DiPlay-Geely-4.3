package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review rv1 STALE-DUCK. */
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

}
