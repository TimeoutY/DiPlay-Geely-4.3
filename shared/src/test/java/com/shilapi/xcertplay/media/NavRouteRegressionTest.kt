package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Review rv1 regressions (STICKY-A, STICKY-B, CLIP-C): real AndroidMediaSink renderers fed LPCM RTP
 * in real time; the route is read back from the DiPlay-AudioStats log lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class NavRouteRegressionTest {
    private val navId = AudioStreamId(100, "default")
    private val navFmt = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 100, "default")
    private val musId = AudioStreamId(101, "media")
    private val musFmt = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 100, "media")

    /** 10 ms of stereo LPCM RTP (12-byte header, big-endian s16 on the wire). */
    private fun rtp(value: Int, frames: Int = 480): ByteArray {
        val out = ByteArray(12 + frames * 4)
        for (i in 0 until frames * 2) {
            out[12 + i * 2] = (value shr 8).toByte(); out[13 + i * 2] = value.toByte()
        }
        return out
    }

    private fun stats() = ShadowLog.getLogs().filter { it.tag == "DiPlay-AudioStats" }.map { it.msg }
    private fun routes() = stats().filter { it.startsWith("nav route") }.map { it.substringBefore(" audioType") }

    private fun sink() = AndroidMediaSink(context = RuntimeEnvironment.getApplication(), navMixIntoMusic = true)

    @Test
    fun navStreamOpenedBeforeMusicMixesOncePromptsPlayDuringMusic() {
        ShadowLog.clear()
        val sink = sink()
        sink.onAudioStarted(navId, navFmt, 0)
        repeat(5) { sink.onAudioRtp(navId, navFmt, rtp(0), it * 480); Thread.sleep(10) } // keep-alive silence
        Thread.sleep(300)
        sink.onAudioStarted(musId, musFmt, 0)
        repeat(100) { sink.onAudioRtp(musId, musFmt, rtp(8000), it * 480); Thread.sleep(10) }
        repeat(50) {
            sink.onAudioRtp(musId, musFmt, rtp(8000), it * 480)
            sink.onAudioRtp(navId, navFmt, rtp(6000), it * 480); Thread.sleep(10)
        }
        Thread.sleep(300)
        sink.close()
        val routes = routes()
        assertTrue("routes=$routes", routes.isNotEmpty())
        assertTrue("prompt during music must mix; routes=$routes", routes.last().startsWith("nav route=MIX"))
        assertTrue("no own track for that prompt; routes=$routes", routes.none { it.contains("OWN_TRACK") })
    }

    @Test
    fun oneMusicStallDoesNotPinTheStreamToItsOwnTrack() {
        ShadowLog.clear()
        val sink = sink()
        sink.onAudioStarted(musId, musFmt, 0)
        repeat(50) { sink.onAudioRtp(musId, musFmt, rtp(8000), it * 480); Thread.sleep(10) }
        sink.onAudioStarted(navId, navFmt, 0)
        repeat(30) { sink.onAudioRtp(musId, musFmt, rtp(8000), it); sink.onAudioRtp(navId, navFmt, rtp(6000), it); Thread.sleep(10) }
        // 1.5 s music gap (track change / Wi-Fi) while the nav stream keeps sending silence.
        repeat(150) { sink.onAudioRtp(navId, navFmt, rtp(0), it); Thread.sleep(10) }
        // Music back, then the next prompt.
        repeat(30) { sink.onAudioRtp(musId, musFmt, rtp(8000), it); sink.onAudioRtp(navId, navFmt, rtp(0), it); Thread.sleep(10) }
        repeat(50) { sink.onAudioRtp(musId, musFmt, rtp(8000), it); sink.onAudioRtp(navId, navFmt, rtp(6000), it); Thread.sleep(10) }
        Thread.sleep(300)
        sink.close()
        val routes = routes()
        assertTrue("next prompt with music must mix again; routes=$routes", routes.last().startsWith("nav route=MIX"))
        println("STICKY-B routes=$routes other=${stats().filter { it.startsWith("nav-mix") }}")
    }

    @Test
    fun promptStartingRightAfterMusicStopsIsNotClipped() {
        ShadowLog.clear()
        val sink = sink()
        sink.onAudioStarted(musId, musFmt, 0)
        repeat(50) { sink.onAudioRtp(musId, musFmt, rtp(8000), it); Thread.sleep(10) }
        Thread.sleep(100) // music paused; a prompt starts 100 ms later
        sink.onAudioStarted(navId, navFmt, 0)
        repeat(150) { sink.onAudioRtp(navId, navFmt, rtp(6000), it); Thread.sleep(10) }
        Thread.sleep(300)
        sink.close()
        val dropped = stats().filter { it.startsWith("nav-mix cleared") && !it.contains("only silence") }
        assertEquals("guidance must never be discarded; routes=${routes()}", emptyList<String>(), dropped)
        assertTrue("prompt must play on the own track; routes=${routes()}", routes().any { it.contains("OWN_TRACK") })
        println("CLIP-C routes=${routes()} handoff=${stats().filter { it.startsWith("nav-mix handed off") }}")
    }
}
