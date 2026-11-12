package com.shilapi.xcertplay.media

/**
 * Latest main-screen video/touch numbers for the on-screen 性能统计 overlay and the log page.
 * Written once per stats window by the decoder thread; read by the UI at most once a second.
 */
object VideoStatsBoard {
    @Volatile private var video: String? = null
    @Volatile private var audio: String? = null
    @Volatile private var audioAtMs = 0L

    /** Video line plus, while music played in the last 12 s, the audio cushion line. */
    val summary: String?
        get() {
            val v = video ?: return null
            val a = audio?.takeIf { nowMs() - audioAtMs < 12_000L }
            return if (a == null) v else "$v · $a"
        }
    @Volatile var updatedAtMs: Long = 0L
        private set

    internal fun publish(
        rxFps: Double, shownFps: Double, droppedFps: Double,
        decodeAvgMs: Long, decodeMaxMs: Long, maxGapMs: Long, recoveries: Int,
        touchAvgMs: Long, kbps: Long,
    ) {
        val touchSendMaxMs = TouchLatencyProbe.lastWindowMaxEndToEndNs / 1_000_000
        video = "收%.0f 显%.0f 丢%.0f fps · 解码%d/%dms · 网络抖动%dms · 恢复%d · 触摸发送%dms 触摸→画面%s · %dkbps".format(
            rxFps, shownFps, droppedFps, decodeAvgMs, decodeMaxMs, maxGapMs, recoveries,
            touchSendMaxMs, if (touchAvgMs < 0) "-" else "${touchAvgMs}ms", kbps,
        ) + if (recoveries >= 2) " · ⚠解码跟不上，建议降低帧率/分辨率" else ""
        updatedAtMs = nowMs()
    }

    /**
     * Music renderer, once per 5 s window: smallest queued audio (ms), times the track ran dry, and
     * RTP packets lost on Wi-Fi. "音频缓冲≥0ms 欠载N" with 丢包0 means our side starved the track;
     * 丢包>0 means the network dropped audio outright.
     */
    internal fun publishAudio(cushionMinMs: Long, starves: Int, rtpLost: Int) {
        audio = "音频缓冲≥${if (cushionMinMs < 0) "-" else "${cushionMinMs}ms"} 欠载$starves 丢包$rtpLost"
        audioAtMs = nowMs()
    }

    fun clear() {
        video = null
        audio = null
        updatedAtMs = 0L
        audioAtMs = 0L
    }

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
}
