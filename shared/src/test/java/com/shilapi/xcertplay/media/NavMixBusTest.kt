package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMixBusTest {
    private var now = 1_000_000_000L
    private val lines = mutableListOf<String>()
    private val bus = NavMixBus(nowNs = { now }, log = { lines += it })

    private fun pcm(frames: Int, channels: Int, value: Int): ByteArray {
        val out = ByteArray(frames * channels * 2)
        for (i in 0 until frames * channels) {
            out[i * 2] = value.toByte(); out[i * 2 + 1] = (value shr 8).toByte()
        }
        return out
    }

    private fun sample(data: ByteArray, index: Int): Int =
        ((data[index * 2].toInt() and 0xff) or (data[index * 2 + 1].toInt() shl 8)).toShort().toInt()

    @Test
    fun pushWithoutHostReturnsFalse() {
        assertFalse(bus.pushNav(pcm(100, 1, 5000), 0, 200, 16000, 1, NavMixBus.Resampler()))
    }

    @Test
    fun resamplerKeepsDurationAcrossChunks() {
        val r = NavMixBus.Resampler()
        var total = 0
        repeat(10) { total += r.convert(pcm(160, 1, 1000), 0, 320, 16000, 1, 48000, 2).size }
        // 10 x 10 ms of 16 kHz mono -> ~100 ms of 48 kHz stereo = 4800 frames * 2 samples.
        assertTrue("got $total", total in 9500..9700)
    }

    @Test
    fun musicRampsDownSmoothlyAndGuidanceIsMixed() {
        val host = Any()
        bus.attachHost(host, 48000, 2)
        val music = pcm(480, 2, 10000)
        bus.processHost(host, music, 0, music.size)
        assertTrue(bus.hostFlowing())
        assertTrue(bus.pushNav(pcm(1600, 1, 3000), 0, 3200, 16000, 1, NavMixBus.Resampler()))
        assertTrue(NavPromptState.active)
        val first = pcm(480, 2, 10000)
        bus.processHost(host, first, 0, first.size)
        // First 10 ms: gain only partly down (ramp, no jump) and guidance added on top.
        val firstSample = sample(first, 0)
        assertTrue("first=$firstSample", firstSample in 12000..13010)
        repeat(30) {
            val b = pcm(480, 2, 10000)
            bus.processHost(host, b, 0, b.size)
        }
        now += 2_000_000_000L
        val ducked = pcm(480, 2, 10000)
        bus.processHost(host, ducked, 0, ducked.size)
        // Guidance consumed; music held at ~30% while speech hang is over -> ramps back up.
        val v = sample(ducked, 0)
        assertTrue("v=$v", v in 2900..3400)
        repeat(100) {
            val b = pcm(480, 2, 10000)
            bus.processHost(host, b, 0, b.size)
        }
        val restored = pcm(480, 2, 10000)
        bus.processHost(host, restored, 0, restored.size)
        assertEquals(10000, sample(restored, 0))
        assertFalse(NavPromptState.active)
        assertTrue(lines.any { it.startsWith("nav prompt START mode=mix") })
        assertTrue(lines.any { it.startsWith("nav prompt END mode=mix") })
    }

    @Test
    fun remoteDuckHoldsUntilUnduck() {
        val host = Any()
        bus.attachHost(host, 44100, 2)
        bus.onRemoteDuck(0.25, 500)
        repeat(50) {
            val b = pcm(441, 2, 8000)
            bus.processHost(host, b, 0, b.size)
        }
        val d = pcm(441, 2, 8000)
        bus.processHost(host, d, 0, d.size)
        assertEquals(2000.0, sample(d, 0).toDouble(), 30.0)
        bus.onRemoteUnduck(null)
        repeat(100) {
            val b = pcm(441, 2, 8000)
            bus.processHost(host, b, 0, b.size)
        }
        val u = pcm(441, 2, 8000)
        bus.processHost(host, u, 0, u.size)
        assertEquals(8000, sample(u, 0))
    }
}
