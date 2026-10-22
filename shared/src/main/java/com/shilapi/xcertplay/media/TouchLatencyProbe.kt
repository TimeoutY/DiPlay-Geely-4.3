package com.shilapi.xcertplay.media

/** Diagnostic link between the touch uplink and the next video frame, read by [VideoStats]. */
internal object TouchLatencyProbe {
    @Volatile private var pendingTouchNs = 0L
    @Volatile var maxSendNs = 0L
    /** Max time from the touch being handed to the controller until it was written to the socket. */
    @Volatile private var maxEndToEndNs = 0L
    @Volatile var lastWindowMaxEndToEndNs = 0L
        private set

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long, queuedAtNs: Long = sentAtNs) {
        if (pendingTouchNs == 0L) pendingTouchNs = queuedAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
        val endToEnd = sentAtNs + sendDurationNs - queuedAtNs
        if (endToEnd > maxEndToEndNs) maxEndToEndNs = endToEnd
    }

    /** Called when a stats window closes. */
    fun rollWindow() {
        lastWindowMaxEndToEndNs = maxEndToEndNs
        maxEndToEndNs = 0L
    }

    /** Returns touch-to-frame latency for the first frame after a touch, or -1. */
    fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        if (touch == 0L) return -1
        pendingTouchNs = 0L
        return nowNs - touch
    }
}
