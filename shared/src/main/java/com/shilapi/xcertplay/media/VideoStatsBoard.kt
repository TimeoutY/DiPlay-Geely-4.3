package com.shilapi.xcertplay.media

/**
 * Latest main-screen video/touch numbers for the on-screen 性能统计 overlay and the log page.
 * Written once per stats window by the decoder thread; read by the UI at most once a second.
 */
object VideoStatsBoard {
    @Volatile var summary: String? = null
        private set
    @Volatile var updatedAtMs: Long = 0L
        private set

    internal fun publish(
        rxFps: Double, shownFps: Double, droppedFps: Double,
        decodeAvgMs: Long, decodeMaxMs: Long, maxGapMs: Long, recoveries: Int,
        touchAvgMs: Long, kbps: Long,
    ) {
        val touchSendMaxMs = TouchLatencyProbe.lastWindowMaxEndToEndNs / 1_000_000
        summary = "收%.0f 显%.0f 丢%.0f fps · 解码%d/%dms · 网络抖动%dms · 恢复%d · 触摸发送%dms 触摸→画面%s · %dkbps".format(
            rxFps, shownFps, droppedFps, decodeAvgMs, decodeMaxMs, maxGapMs, recoveries,
            touchSendMaxMs, if (touchAvgMs < 0) "-" else "${touchAvgMs}ms", kbps,
        ) + if (recoveries >= 2) " · ⚠解码跟不上，建议降低帧率/分辨率" else ""
        updatedAtMs = android.os.SystemClock.elapsedRealtime()
    }

    fun clear() {
        summary = null
        updatedAtMs = 0L
    }
}
