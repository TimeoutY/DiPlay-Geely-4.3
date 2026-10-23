package com.shilapi.xcertplay

/**
 * Breaks pause/play loops between CarPlay, the car's own Bluetooth-music app and the media-key
 * path on Android 4.x head units. Pure logic with an injected clock so it is unit-testable.
 *
 * Kinds of key: [Key.PLAY] / [Key.PAUSE] are explicit hardware keys (KEYCODE_MEDIA_PLAY/PAUSE),
 * [Key.TOGGLE] is PLAY_PAUSE/HEADSETHOOK. On qa1 all three were forwarded as a CarPlay *toggle*,
 * so a car-generated PAUSE while the iPhone was already paused played it again (and vice versa).
 */
internal class PauseLoopGuard(private val now: () -> Long) {
    enum class Key { PLAY, PAUSE, TOGGLE }
    data class Decision(val send: Boolean, val reason: String)

    private var lastFocusChangeAt = Long.MIN_VALUE / 2
    private var lastMediaStartAt = Long.MIN_VALUE / 2
    private val forwarded = ArrayDeque<Long>()
    private var stormUntil = Long.MIN_VALUE / 2
    private val losses = ArrayDeque<Long>()
    private var lastReRequestAt = Long.MIN_VALUE / 2

    fun onFocusChange() { lastFocusChangeAt = now() }
    fun onMediaStart() { lastMediaStartAt = now() }

    fun onFocusLoss() {
        val t = now()
        losses.addLast(t)
        while (losses.isNotEmpty() && t - losses.first() > CONTENTION_WINDOW_MS) losses.removeFirst()
        lastFocusChangeAt = t
    }

    /** True when another app took MUSIC focus from us repeatedly (likely the car's BT-music app). */
    fun contention(): Boolean {
        val t = now()
        while (losses.isNotEmpty() && t - losses.first() > CONTENTION_WINDOW_MS) losses.removeFirst()
        return losses.size >= CONTENTION_LOSSES
    }

    /** Whether focus may be re-requested after a loss (never a tight request/loss ping-pong). */
    fun mayReRequestFocus(): Decision {
        val t = now()
        val gap = if (contention()) CONTENTION_REREQUEST_GAP_MS else REREQUEST_GAP_MS
        if (t - lastReRequestAt < gap) {
            val note = if (contention()) ", focus contention" else ""
            return Decision(false, "re-request throttled (${t - lastReRequestAt}ms < ${gap}ms$note)")
        }
        lastReRequestAt = t
        return Decision(true, if (contention()) "re-request allowed (contention, 1 per ${gap / 1000}s)" else "re-request allowed")
    }

    fun keyDecision(key: Key, iphonePlaying: Boolean?, guardEnabled: Boolean): Decision {
        if (!guardEnabled) return record(Decision(true, "guard off"))
        val t = now()
        val sinceFocus = t - lastFocusChangeAt
        if (sinceFocus in 0 until KEY_GUARD_AFTER_FOCUS_MS) {
            return Decision(false, "within ${sinceFocus}ms of a focus change (car echo)")
        }
        val sinceMedia = t - lastMediaStartAt
        if (sinceMedia in 0 until KEY_GUARD_AFTER_FOCUS_MS) {
            return Decision(false, "within ${sinceMedia}ms of CarPlay media start (car echo)")
        }
        if (key == Key.PLAY && iphonePlaying == true) return Decision(false, "PLAY while iPhone already playing (redundant)")
        if (key == Key.PAUSE && iphonePlaying == false) return Decision(false, "PAUSE while iPhone already paused (redundant)")
        if (t < stormUntil) return Decision(false, "key storm: play/pause muted for ${stormUntil - t}ms")
        while (forwarded.isNotEmpty() && t - forwarded.first() > STORM_WINDOW_MS) forwarded.removeFirst()
        if (forwarded.isNotEmpty() && t - forwarded.last() < DEBOUNCE_MS) {
            return Decision(false, "debounced (${t - forwarded.last()}ms after previous)")
        }
        if (forwarded.size >= STORM_KEYS - 1) {
            stormUntil = t + STORM_MUTE_MS
            forwarded.clear()
            return Decision(false, "key storm: $STORM_KEYS play/pause keys within ${STORM_WINDOW_MS / 1000}s; muting ${STORM_MUTE_MS / 1000}s")
        }
        return record(Decision(true, "forwarded"))
    }

    private fun record(decision: Decision): Decision {
        if (decision.send) forwarded.addLast(now())
        return decision
    }

    companion object {
        const val KEY_GUARD_AFTER_FOCUS_MS = 3_000L
        const val DEBOUNCE_MS = 1_000L
        const val STORM_WINDOW_MS = 20_000L
        const val STORM_KEYS = 4
        const val STORM_MUTE_MS = 30_000L
        const val REREQUEST_GAP_MS = 10_000L
        const val CONTENTION_WINDOW_MS = 120_000L
        const val CONTENTION_LOSSES = 3
        const val CONTENTION_REREQUEST_GAP_MS = 60_000L
        const val AUTO_RESUME_MIN_GAP_MS = 5 * 60_000L

        /** Process-wide: auto-resume at most once per 5 minutes, across session reconnects. */
        @Volatile var lastAutoResumeAt: Long = Long.MIN_VALUE / 2
        /** After an auto-resume, another no-key pause within this window means we are fighting the BT-music app. */
        const val FIGHT_WINDOW_MS = 10_000L
        @Volatile var lastAutoResumeCompletedAt: Long = Long.MIN_VALUE / 2
        @Volatile var fightMode: Boolean = false

        fun autoResumeAllowed(now: Long): Boolean =
            !fightMode && now - lastAutoResumeAt >= AUTO_RESUME_MIN_GAP_MS

        fun noteAutoResumeSent(now: Long) {
            lastAutoResumeAt = now
            lastAutoResumeCompletedAt = now
        }

        /** Call when the iPhone pauses again with no key after a recent auto-resume. */
        fun notePostResumePause(now: Long): Boolean {
            if (now - lastAutoResumeCompletedAt in 0 until FIGHT_WINDOW_MS) {
                fightMode = true
                return true
            }
            return false
        }

        fun clearFightForNewSession() {
            // Fight mode survives a session reconnect for the 5-minute auto-resume gap; only clear
            // the short window marker so a brand-new connect can still arm once.
            lastAutoResumeCompletedAt = Long.MIN_VALUE / 2
        }
    }
}
