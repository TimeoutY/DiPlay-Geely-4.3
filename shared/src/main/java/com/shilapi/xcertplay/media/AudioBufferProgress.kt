package com.shilapi.xcertplay.media

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) { writtenBytes += bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    fun shouldRebuffer(isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int): Boolean =
        isMedia && playing && underrunSinceStart && compressedQueueEmpty && queuedBytes(rawHead) == 0L
}

/**
 * Music cushion over one stats window: the smallest queued AudioTrack audio (ms) and how many
 * times it ran dry. Android 4.4 keeps an empty streaming track "playing" and outputs silence, so a
 * dry track is what the driver hears as a dropout.
 */
internal class AudioCushionMeter {
    private var min = Long.MAX_VALUE
    private var dry = false
    var starves = 0
        private set

    fun sample(queuedMs: Long) {
        if (queuedMs < min) min = queuedMs
        if (queuedMs <= 0L) {
            if (!dry) starves++
            dry = true
        } else {
            dry = false
        }
    }

    /** Not playing (prebuffering or stopped): neither a sample nor the end of a dry spell. */
    fun idle() = Unit

    /** -1 when the track was never sampled in this window. */
    fun minMs(): Long = if (min == Long.MAX_VALUE) -1 else min

    fun roll() {
        min = Long.MAX_VALUE
        starves = 0
    }
}

/** RTP sequence numbers skipped by the network (UDP audio has no retransmission). */
internal class RtpSequenceGaps {
    private var last = -1
    @Volatile var lost = 0
        private set

    @Synchronized fun onSequence(sequence: Int) {
        val current = sequence and 0xffff
        val previous = last
        if (previous < 0) { last = current; return }
        val delta = (current - previous - 1) and 0xffff
        if (delta >= 0x8000) return // duplicate or late packet: not a loss, keep the newest seen
        lost += delta
        last = current
    }

    @Synchronized fun roll() { lost = 0 }
}
