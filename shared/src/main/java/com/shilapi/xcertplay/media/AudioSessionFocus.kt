package com.shilapi.xcertplay.media

import android.media.AudioManager
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide coordination between the session-level STREAM_MUSIC focus held by
 * CarPlayMediaKeys and the per-track AudioFocusCoordinator, so the app never runs two focus
 * clients on the music stream that keep taking focus from each other.
 */
object AudioSessionFocus {
    /** True while CarPlayMediaKeys owns MUSIC focus for the whole AirPlay session. */
    @Volatile
    var sessionOwnsMusicFocus: Boolean = false

    /**
     * Preferred MEDIA gain when this process requests STREAM_MUSIC focus.
     * Set by CarPlayMediaKeys from the 「独占声音焦点」setting (GAIN_TRANSIENT_EXCLUSIVE or GAIN).
     * Siri/phone keep requesting transient separately and must not use this value.
     */
    @Volatile
    var preferredMediaGain: Int = AudioManager.AUDIOFOCUS_GAIN

    private val sessionEndHooks = CopyOnWriteArraySet<() -> Unit>()

    fun addSessionEndHook(hook: () -> Unit) { sessionEndHooks.add(hook) }

    fun removeSessionEndHook(hook: () -> Unit) { sessionEndHooks.remove(hook) }

    /** Called by CarPlayController when the AirPlay session ends. */
    fun onSessionEnded() {
        sessionEndHooks.forEach { runCatching { it() } }
    }
}
