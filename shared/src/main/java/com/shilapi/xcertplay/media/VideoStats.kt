package com.shilapi.xcertplay.media

import android.util.Log

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var rendered = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private var dropped = 0
    private var decodeSamples = 0
    private var decodeSumNs = 0L
    private var maxDecodeNs = 0L
    private var maxQueueWaitNs = 0L

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onRendered() { rendered++ }

    /** A decoded picture skipped because a newer one was already ready (stale frame not shown). */
    @Synchronized fun onDropped() { dropped++ }

    /** Time from queueInputBuffer to the decoded output buffer. */
    @Synchronized fun onDecoded(latencyNs: Long) {
        if (latencyNs < 0 || latencyNs > IDLE_GAP_NS) return
        decodeSamples++
        decodeSumNs += latencyNs
        if (latencyNs > maxDecodeNs) maxDecodeNs = latencyNs
    }

    /** Time a received frame waited in the decode queue before it was fed to the codec. */
    @Synchronized fun onQueueWait(waitNs: Long) {
        if (waitNs > maxQueueWaitNs) maxQueueWaitNs = waitNs
    }

    @Synchronized fun onRecovery() { recoveries++ }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && touchSamples == 0) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        )
        TouchLatencyProbe.maxSendNs = 0
        val decodeAvgMs = if (decodeSamples == 0) -1 else decodeSumNs / decodeSamples / 1_000_000
        val full = line + " dropped=%d decode avg=%dms max=%dms queueWaitMax=%dms".format(
            dropped, decodeAvgMs, maxDecodeNs / 1_000_000, maxQueueWaitNs / 1_000_000,
        )
        if (label.isEmpty()) {
            TouchLatencyProbe.rollWindow()
            VideoStatsBoard.publish(
                rxFps = received / seconds, shownFps = rendered / seconds, droppedFps = dropped / seconds,
                decodeAvgMs = decodeAvgMs, decodeMaxMs = maxDecodeNs / 1_000_000,
                maxGapMs = maxArrivalGapNs / 1_000_000, recoveries = recoveries,
                touchAvgMs = touchAvgMs, kbps = (bytes * 8 / 1000 / seconds).toLong(),
            )
        }
        Log.i(TAG, full)
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        dropped = 0; decodeSamples = 0; decodeSumNs = 0; maxDecodeNs = 0; maxQueueWaitNs = 0
        return full
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}
