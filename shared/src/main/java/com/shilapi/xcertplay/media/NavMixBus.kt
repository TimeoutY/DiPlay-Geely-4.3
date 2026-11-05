package com.shilapi.xcertplay.media

/**
 * Navigation guidance mixed into the CarPlay music track, with a smooth software duck.
 *
 * Why: on Geely GKUI 4.x a second AudioTrack (or a navigation focus request + abandon on every
 * prompt) makes the head unit cut the music in and out while 高德/Apple Maps speak. While music
 * PCM is flowing, guidance PCM is resampled to the music format and summed into the music
 * track, so the music track is never paused, flushed, recreated or volume-jumped, and no focus
 * changes per prompt. The music gain ramps to [DUCK_LEVEL] (or the iPhone's duckAudio volume)
 * while guidance speaks and ramps back afterwards.
 *
 * Pure Kotlin (injected clock and logger) so it is unit-testable; one [shared] instance per process.
 * Threading: the music renderer thread calls [processHost]; navigation renderer threads call
 * [pushNav] / [noteNavSpeech]; AirPlay command threads call [onRemoteDuck]/[onRemoteUnduck].
 */
class NavMixBus(
    private val nowNs: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var hostId: Any? = null
    private var hostRate = 0
    private var hostChannels = 0
    @Volatile private var hostLastWriteNs = 0L

    // Pending guidance, interleaved 16-bit in the host format.
    private var ring = ShortArray(0)
    private var ringRead = 0
    private var ringCount = 0

    // Host-thread duck state.
    private var gain = 1f
    private var minGainThisPrompt = 1f

    @Volatile private var speechUntilNs = 0L
    @Volatile private var remoteDuckLevel: Float? = null
    @Volatile private var remoteDuckUntilNs = 0L

    // Prompt bookkeeping (under lock).
    private var promptActive = false
    private var promptStartNs = 0L
    private var promptMode = "own"
    private var mixedFrames = 0L
    private var droppedFrames = 0L
    private var navUnderruns = 0
    private var maxHostGapMs = 0L
    private var lastHostCallNs = 0L

    val isPromptActive: Boolean get() = promptActive

    /** The renderer whose track carries music; guidance is summed into it. */
    fun attachHost(id: Any, sampleRate: Int, channels: Int) = synchronized(lock) {
        hostId = id
        hostRate = sampleRate
        hostChannels = channels.coerceIn(1, 2)
        ring = ShortArray(hostRate * hostChannels * RING_SECONDS)
        ringRead = 0
        ringCount = 0
        hostLastWriteNs = 0L
        resetDuckLocked()
        log("nav-mix host attached rate=$sampleRate channels=$hostChannels")
    }

    fun detachHost(id: Any) = synchronized(lock) {
        if (hostId !== id) return@synchronized
        log("nav-mix host detached pendingFrames=${ringCount / hostChannels.coerceAtLeast(1)}")
        hostId = null
        ringCount = 0
        ringRead = 0
        hostLastWriteNs = 0L
        resetDuckLocked()
    }

    /**
     * A new or ended music session must not inherit a duck: an iPhone duckAudio whose unduckAudio
     * never arrived (session dropped) would otherwise hold music at the duck level for up to
     * [REMOTE_DUCK_MAX_NS] after reconnecting.
     */
    private fun resetDuckLocked() {
        remoteDuckLevel = null
        remoteDuckUntilNs = 0L
        speechUntilNs = 0L
        gain = 1f
    }

    /** Music PCM was produced recently, so mixed guidance will actually be played. */
    fun hostFlowing(): Boolean {
        val last = hostLastWriteNs
        return hostId != null && last != 0L && nowNs() - last < HOST_FLOWING_NS
    }

    fun hostFormat(): Pair<Int, Int>? = synchronized(lock) { if (hostId == null) null else hostRate to hostChannels }

    fun pendingNavFrames(): Int = synchronized(lock) { if (hostChannels == 0) 0 else ringCount / hostChannels }

    /** Drops queued guidance (navigation fell back to its own track). */
    fun clearNav(reason: String) = synchronized(lock) {
        if (ringCount > 0) log("nav-mix cleared pendingFrames=${ringCount / hostChannels.coerceAtLeast(1)} ($reason)")
        droppedFrames += (ringCount / hostChannels.coerceAtLeast(1)).toLong()
        ringCount = 0
        ringRead = 0
    }

    /**
     * Guidance PCM (16-bit LE, [srcRate]/[srcChannels]) from a navigation renderer. Returns false
     * when no music host exists (caller must play it on its own track).
     */
    fun pushNav(data: ByteArray, offset: Int, length: Int, srcRate: Int, srcChannels: Int, resampler: Resampler): Boolean {
        val converted: ShortArray
        synchronized(lock) {
            if (hostId == null || hostRate <= 0) return false
            converted = resampler.convert(data, offset, length, srcRate, srcChannels, hostRate, hostChannels)
            promptMode = "mix"
            noteSpeechLocked(converted)
            var dropped = 0
            for (sample in converted) {
                if (ringCount == ring.size) {
                    ringRead = (ringRead + 1) % ring.size
                    ringCount--
                    dropped++
                }
                ring[(ringRead + ringCount) % ring.size] = sample
                ringCount++
            }
            if (dropped > 0) droppedFrames += (dropped / hostChannels).toLong()
        }
        return true
    }

    /** Guidance played on its own track still ducks the music. */
    fun noteNavSpeech(data: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        var peak = 0
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val s = ((data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            i += 2
        }
        if (peak >= SPEECH_PEAK) markSpeechLocked()
    }

    private fun noteSpeechLocked(samples: ShortArray) {
        var peak = 0
        for (s in samples) {
            val a = if (s < 0) -s.toInt() else s.toInt()
            if (a > peak) peak = a
        }
        if (peak >= SPEECH_PEAK) markSpeechLocked()
    }

    private fun markSpeechLocked() {
        val now = nowNs()
        speechUntilNs = now + SPEECH_HANG_NS
        if (!promptActive) {
            promptActive = true
            promptStartNs = now
            mixedFrames = 0
            droppedFrames = 0
            navUnderruns = 0
            maxHostGapMs = 0
            minGainThisPrompt = gain
            NavPromptState.active = true
            log("nav prompt START mode=$promptMode duckTarget=${targetDuckLevel()} " +
                "hostFlowing=${hostFlowing()} remoteDuck=${remoteDuckLevel ?: "none"}")
        }
    }

    fun setOwnTrackMode() = synchronized(lock) { promptMode = "own" }

    /** iPhone "duckAudio" command: ramp music to [volume] (0..1) for up to [durationMs] or until unduck. */
    fun onRemoteDuck(volume: Double?, durationMs: Long?) {
        val level = (volume ?: DUCK_LEVEL.toDouble()).toFloat().let { if (it in 0.05f..1f) it else DUCK_LEVEL }
        remoteDuckLevel = level
        remoteDuckUntilNs = nowNs() + REMOTE_DUCK_MAX_NS
        log("nav remote duckAudio volume=${volume ?: "default"} level=$level durationMs=${durationMs ?: "none"}")
    }

    fun onRemoteUnduck(durationMs: Long?) {
        remoteDuckLevel = null
        log("nav remote unduckAudio durationMs=${durationMs ?: "none"}")
    }

    private fun targetDuckLevel(): Float = minOf(DUCK_LEVEL, remoteDuckLevel ?: 1f)

    /**
     * Music renderer, before writing [length] bytes: apply the duck ramp in place and add pending
     * guidance. Never changes the length, so the music track timing is untouched.
     */
    fun processHost(id: Any, data: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        if (hostId !== id) return@synchronized
        val now = nowNs()
        hostLastWriteNs = now
        if (lastHostCallNs != 0L && promptActive) {
            maxHostGapMs = maxOf(maxHostGapMs, (now - lastHostCallNs) / 1_000_000L)
        }
        lastHostCallNs = now
        val remote = remoteDuckLevel?.takeIf { now < remoteDuckUntilNs }
        if (remote == null && remoteDuckLevel != null) {
            remoteDuckLevel = null
            log("nav remote duck expired without unduckAudio")
        }
        val speaking = now < speechUntilNs || ringCount > 0
        val target = minOf(if (speaking) DUCK_LEVEL else 1f, remote ?: 1f)
        val channels = hostChannels.coerceAtLeast(1)
        val downStep = 1f / (hostRate * DUCK_DOWN_SECONDS)
        val upStep = 1f / (hostRate * DUCK_UP_SECONDS)
        val hadNav = ringCount > 0
        var i = offset
        val end = offset + length - 2 * channels + 1
        while (i <= end) {
            if (gain > target) gain = maxOf(target, gain - downStep)
            else if (gain < target) gain = minOf(target, gain + upStep)
            for (c in 0 until channels) {
                val music = ((data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)).toShort().toInt()
                var mixed = (music * gain).toInt()
                if (ringCount > 0) {
                    mixed += ring[ringRead].toInt()
                    ringRead = (ringRead + 1) % ring.size
                    ringCount--
                    if (c == channels - 1) mixedFrames++
                }
                if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE.toInt()
                if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE.toInt()
                data[i] = mixed.toByte()
                data[i + 1] = (mixed shr 8).toByte()
                i += 2
            }
        }
        if (gain < minGainThisPrompt) minGainThisPrompt = gain
        if (hadNav && ringCount == 0 && now < speechUntilNs) navUnderruns++
        if (promptActive && !speaking && ringCount == 0 && gain >= 1f) endPromptLocked(now, "music restored")
    }

    /** Ends prompt bookkeeping when no music host is running to do it. */
    fun tick() = synchronized(lock) {
        val now = nowNs()
        if (promptActive && now >= speechUntilNs && ringCount == 0 && !hostFlowing()) endPromptLocked(now, "no music host")
    }

    private fun endPromptLocked(now: Long, why: String) {
        promptActive = false
        NavPromptState.active = false
        log("nav prompt END mode=$promptMode durationMs=${(now - promptStartNs) / 1_000_000L} " +
            "mixedFrames=$mixedFrames droppedFrames=$droppedFrames navUnderruns=$navUnderruns " +
            "minMusicGain=${"%.2f".format(minGainThisPrompt)} maxMusicGapMs=$maxHostGapMs ($why)")
    }

    /** Linear resampler + channel conversion that keeps its phase across chunks of one stream. */
    class Resampler {
        private var phase = 0.0
        private var last = ShortArray(2)
        private var hasLast = false

        fun convert(
            data: ByteArray, offset: Int, length: Int,
            srcRate: Int, srcChannels: Int, dstRate: Int, dstChannels: Int,
        ): ShortArray {
            val sc = srcChannels.coerceIn(1, 2)
            val dc = dstChannels.coerceIn(1, 2)
            val frames = length / (2 * sc)
            if (frames <= 0) return ShortArray(0)
            fun src(frame: Int, ch: Int): Int {
                val c = minOf(ch, sc - 1)
                if (frame < 0) return last[c].toInt()
                val p = offset + (frame * sc + c) * 2
                return ((data[p].toInt() and 0xff) or (data[p + 1].toInt() shl 8)).toShort().toInt()
            }
            fun srcMono(frame: Int, ch: Int): Int =
                if (sc == 2 && dc == 1) (src(frame, 0) + src(frame, 1)) / 2 else src(frame, ch)
            val step = srcRate.toDouble() / dstRate
            var out = ShortArray(((frames + 1) / step + 2).toInt() * dc)
            var n = 0
            // Position relative to the first frame of this chunk; -1 is the previous chunk's last frame.
            var pos = if (hasLast) phase - 1.0 else 0.0
            while (pos <= frames - 1) {
                val i0 = Math.floor(pos).toInt()
                val frac = pos - i0
                if (n + dc > out.size) out = out.copyOf(out.size * 2 + dc)
                for (ch in 0 until dc) {
                    val a = srcMono(i0, ch)
                    val b = if (i0 + 1 <= frames - 1) srcMono(i0 + 1, ch) else a
                    out[n++] = (a + (b - a) * frac).toInt().toShort()
                }
                pos += step
            }
            phase = pos - (frames - 1)
            for (ch in 0 until sc) last[ch] = src(frames - 1, ch).toShort()
            hasLast = true
            return if (n == out.size) out else out.copyOf(n)
        }
    }

    companion object {
        const val DUCK_LEVEL = 0.3f
        const val DUCK_DOWN_SECONDS = 0.15f
        const val DUCK_UP_SECONDS = 0.5f
        const val SPEECH_PEAK = 400
        const val SPEECH_HANG_NS = 900_000_000L
        const val HOST_FLOWING_NS = 1_000_000_000L
        const val REMOTE_DUCK_MAX_NS = 20_000_000_000L
        const val RING_SECONDS = 8

        @JvmStatic val shared: NavMixBus by lazy {
            NavMixBus(log = { line ->
                runCatching { android.util.Log.i("DiPlay-AudioStats", line) }
                NavPromptState.report(line)
            })
        }
    }
}

/** Process-wide prompt flag for focus/key logs in other modules. */
object NavPromptState {
    @Volatile var active: Boolean = false
    @Volatile var reporter: ((String) -> Unit)? = null

    fun report(line: String) { runCatching { reporter?.invoke(line) } }
}
