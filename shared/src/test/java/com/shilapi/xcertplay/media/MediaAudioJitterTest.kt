package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Car evidence (2019 帝豪GL, Android 4.4.2, rv1 log page): "audio track prepared ... buffer=46ms start=34"
 * although the 500 ms music buffer was selected, and 性能统计 shows Wi-Fi gaps (网络抖动) of 270-420 ms.
 * These tests replay that setup: AAC 44.1 kHz stereo, AudioTrack built with the 700 ms plan.
 */
class MediaAudioJitterTest {
    private val rate = 44_100
    private val frameBytes = 4
    // AudioTrack.getMinBufferSize(44100, STEREO, PCM_16BIT) on the GKUI unit: ~46 ms.
    private val minBuffer = 8_192
    private val plan = MediaAudioBuffer.plan(true, rate, 2, minBuffer, MediaAudioBuffer.DEFAULT_MILLIS)

    /** API 18/19: bufferSizeInFrames does not exist (-1). */
    private fun startBytesOnApi19(nativeFrames: Int): Int {
        val capacity = MediaAudioBuffer.trackCapacityBytes(
            reportedFrames = -1, nativeFrames = nativeFrames, frameBytes = frameBytes,
            requestedBytes = plan.trackBufferBytes, minBufferBytes = minBuffer,
        )
        return MediaAudioBuffer.startBytesFor(plan.startBytes, capacity, 2_048)
    }

    @Test
    fun `api 19 keeps the selected 500 ms start level`() {
        val nativeFrames = plan.trackBufferBytes / frameBytes
        assertEquals(plan.startBytes, startBytesOnApi19(nativeFrames))
        // getNativeFrameCount unavailable: trust the requested size, never getMinBufferSize.
        assertEquals(plan.startBytes, startBytesOnApi19(0))
    }

    @Test
    fun `api 23 still respects a smaller granted buffer`() {
        val granted = MediaAudioBuffer.trackCapacityBytes(20_000, 0, frameBytes, plan.trackBufferBytes, minBuffer)
        assertEquals(80_000, granted)
        assertEquals(80_000 - 2_048, MediaAudioBuffer.startBytesFor(plan.startBytes, granted, 2_048))
    }

    @Test
    fun `music rides through 300-420 ms wifi gaps without underrun on api 19`() {
        val capacity = MediaAudioBuffer.trackCapacityBytes(-1, plan.trackBufferBytes / frameBytes, frameBytes,
            plan.trackBufferBytes, minBuffer)
        val start = MediaAudioBuffer.startBytesFor(plan.startBytes, capacity, 2_048)
        val result = PlayoutSim(rate, frameBytes, capacity, start).run(
            durationMs = 27_000, gapsMs = listOf(3_000L to 313L, 7_600L to 421L, 12_000L to 330L, 18_000L to 279L),
        )
        assertEquals("silent ms after playback start", 0, result.underrunMs)
        assertEquals(0, result.underrunEvents)
    }

    @Test
    fun `playout model reproduces the car dropouts with a 34 ms start level`() {
        // What rv1 actually ran with on the car: start=34 ms (6 KB). Each Wi-Fi gap is audible.
        val result = PlayoutSim(rate, frameBytes, plan.trackBufferBytes, 6_144).run(
            durationMs = 27_000, gapsMs = listOf(3_000L to 313L, 7_600L to 421L, 12_000L to 330L, 18_000L to 279L),
        )
        // The first gap alone empties the 34 ms cushion (the backlog then refills it, so later gaps
        // only hurt when longer than every earlier one, or when burst packets are lost).
        assertTrue("underrunMs=${result.underrunMs}", result.underrunMs >= 313 - 35)
        assertTrue("events=${result.underrunEvents}", result.underrunEvents >= 1)
    }

    @Test
    fun `a briefly busy aac decoder keeps the access unit`() {
        // rv1: one 10 ms dequeueInputBuffer, then the packet was dropped. Now: drain + retry up to 200 ms.
        var time = 0L
        var busy = 5 // mediaserver's AAC thread descheduled for ~50 ms by video decoding
        var drains = 0
        val index = VideoInputPump.acquire(
            running = { true }, drain = { drains++ },
            dequeue = { time += 10_000_000L; if (busy-- > 0) -1 else 3 },
            nanoTime = { time }, timeoutNs = 200_000_000L,
        )
        assertEquals(3, index)
        assertEquals(6, drains)
    }

    @Test
    fun `cushion meter counts each dry spell once and reports the minimum`() {
        val meter = AudioCushionMeter()
        assertEquals(-1, meter.minMs())
        listOf(480L, 300L, 0L, 0L, 0L, 120L, 0L, 250L).forEach(meter::sample)
        assertEquals(0, meter.minMs())
        assertEquals(2, meter.starves)
        meter.roll()
        meter.sample(410L)
        assertEquals(410, meter.minMs())
        assertEquals(0, meter.starves)
    }

    @Test
    fun `rtp gaps count lost packets across wrap and ignore duplicates or reordering`() {
        val gaps = RtpSequenceGaps()
        listOf(65_533, 65_534, 1, 2, 2, 1, 6).forEach(gaps::onSequence)
        // 65535 and 0 missing; 3,4,5 missing; the duplicate 2 and late 1 are not losses.
        assertEquals(2 + 3, gaps.lost)
        gaps.roll()
        assertEquals(0, gaps.lost)
    }
}

/**
 * 1 ms step model of the receive -> AudioTrack path: AAC packets (1024 frames) arrive in real time
 * except during [gapsMs] (start to length), when they are held and then delivered as one burst,
 * like Wi-Fi retries. The track plays [rate] frames/s once [startBytes] are queued (Android 4.4
 * keeps a streaming track in play state across an underrun), and writes block when it is full.
 */
internal class PlayoutSim(rate: Int, private val frameBytes: Int, private val capacity: Int, private val startBytes: Int) {
    data class Result(val underrunMs: Int, val underrunEvents: Int)

    private val packetBytes = 1024 * frameBytes
    private val packetPeriodUs = 1024L * 1_000_000L / rate
    private val bytesPerMs = rate * frameBytes / 1000.0

    fun run(durationMs: Int, gapsMs: List<Pair<Long, Long>>): Result {
        var queued = 0.0
        var pending = 0L
        var playing = false
        var underrunMs = 0
        var events = 0
        var starved = false
        var nextPacketUs = 0L
        for (ms in 0 until durationMs) {
            val nowUs = ms * 1000L
            while (nextPacketUs <= nowUs) { pending++; nextPacketUs += packetPeriodUs }
            val inGap = gapsMs.any { (s, len) -> ms >= s && ms < s + len }
            if (!inGap) {
                while (pending > 0 && queued + packetBytes <= capacity) { queued += packetBytes; pending-- }
            }
            if (!playing && queued >= startBytes) playing = true
            if (playing) {
                if (queued >= bytesPerMs) {
                    queued -= bytesPerMs
                    starved = false
                } else {
                    queued = 0.0
                    underrunMs++
                    if (!starved) events++
                    starved = true
                }
            }
        }
        return Result(underrunMs, events)
    }
}
