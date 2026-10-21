package com.shilapi.xcertplay.logs

/** Pulls the few facts that decide "why is there no sound" out of the captured log lines. */
data class AudioLogSummary(
    val focusRequest: String? = null,
    val focusGranted: String? = null,
    val lastFocusChange: String? = null,
    val playState: String? = null,
    val volume: String? = null,
    val muted: String? = null,
    val audioMode: String? = null,
    val writtenFrames: Long? = null,
    val playbackHeadFrames: Long? = null,
    val playbackAdvanceFrames: Long? = null,
    val previousWrittenFrames: Long? = null,
    val iphonePlaying: String? = null,
    val a2dp: String? = null,
) {
    /** True when the last two stats windows both show PCM written and the head advancing. */
    val pcmFlowing: Boolean?
        get() {
            if (writtenFrames == null) return null
            // The first stats window has no previous head position (advance = -1); use the head itself.
            val advance = playbackAdvanceFrames?.takeIf { it >= 0 } ?: playbackHeadFrames ?: return null
            return advance > 0 && writtenFrames > 0
        }

    /** Head movement for display: per-window advance, or the absolute head on the first window. */
    val headProgress: Long?
        get() = playbackAdvanceFrames?.takeIf { it >= 0 } ?: playbackHeadFrames

    companion object {
        private val GRANTED = Regex("""granted=(\w+)""")
        private val CHANGE = Regex("""change=(\w+)""")
        private fun field(line: String, key: String): String? =
            Regex("""(?:^|\s)$key=([^\s]+)""").find(line)?.groupValues?.get(1)

        fun from(lines: List<String>): AudioLogSummary {
            var summary = AudioLogSummary()
            var statsSeen = 0
            for (line in lines.asReversed()) {
                if (summary.focusRequest == null &&
                    (line.contains("focus request") || line.contains("focus requested") || line.contains("Geely focus denied"))
                ) {
                    summary = summary.copy(
                        focusRequest = line.substringAfter("): ", line).take(160),
                        focusGranted = GRANTED.find(line)?.groupValues?.get(1)
                            ?: if (line.contains("denied")) "false" else null,
                    )
                }
                if (summary.lastFocusChange == null && line.contains("focus change")) {
                    summary = summary.copy(lastFocusChange = CHANGE.find(line)?.groupValues?.get(1))
                }
                if (summary.playState == null && line.contains("audio play()")) {
                    summary = summary.copy(
                        playState = field(line, "playState"),
                        volume = field(line, "volume"),
                        muted = field(line, "muted"),
                        audioMode = field(line, "mode"),
                    )
                }
                if (statsSeen < 2 && line.contains("audio stats") && line.contains("channel=MEDIA")) {
                    statsSeen++
                    val written = field(line, "writtenFrames")?.toLongOrNull()
                    summary = if (statsSeen == 1) {
                        summary.copy(
                            writtenFrames = written,
                            playbackHeadFrames = field(line, "playbackHeadFrames")?.toLongOrNull(),
                            playbackAdvanceFrames = field(line, "playbackAdvanceFrames")?.toLongOrNull(),
                        )
                    } else {
                        summary.copy(previousWrittenFrames = written)
                    }
                }
                if (summary.iphonePlaying == null && line.contains("iPhone playing=")) {
                    summary = summary.copy(iphonePlaying = field(line, "playing"))
                }
                if (summary.a2dp == null && line.contains("DiPlay-BtAudio")) {
                    summary = summary.copy(a2dp = line.substringAfter("): ", line).take(120))
                }
            }
            return summary
        }
    }
}
