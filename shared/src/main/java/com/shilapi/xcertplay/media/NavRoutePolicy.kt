package com.shilapi.xcertplay.media

/**
 * Chooses, once per guidance prompt, whether a navigation stream is mixed into the music track
 * ([Route.MIX]) or played on its own AudioTrack ([Route.OWN]).
 *
 * The iPhone keeps a navigation stream open for a whole drive and fills the gaps between prompts
 * with silence, so deciding once per stream (v39) pinned the route to whatever was true when the
 * stream opened (review rv1: STICKY-A, STICKY-B). Here a prompt starts at the first speech chunk
 * after [promptGapNs] without speech, and that is when the route is chosen again. Silence between
 * prompts is not guidance: in MIX it is dropped instead of queued.
 *
 * Pure logic with caller-supplied timestamps so it is unit-testable.
 */
internal class NavRoutePolicy(private val promptGapNs: Long = PROMPT_GAP_NS) {
    enum class Route { NONE, MIX, OWN }

    /** What the renderer does with one PCM chunk. */
    enum class Sink { DROP, MIX, OWN }

    data class Step(val sink: Sink, val switchedTo: Route?)

    var route: Route = Route.NONE
        private set
    private var lastSpeechNs = Long.MIN_VALUE / 2

    fun inPrompt(now: Long): Boolean = route != Route.NONE && now - lastSpeechNs < promptGapNs

    /**
     * @param speech the chunk carries guidance speech (not keep-alive silence)
     * @param hostRecent the music track was written very recently, so mixing will be heard now
     */
    fun onChunk(now: Long, speech: Boolean, hostRecent: Boolean): Step {
        val inPrompt = inPrompt(now)
        var switched: Route? = null
        if (speech) {
            if (!inPrompt) {
                val want = if (hostRecent) Route.MIX else Route.OWN
                if (want != route) {
                    route = want
                    switched = want
                }
            }
            lastSpeechNs = now
        }
        val sink = when (route) {
            Route.NONE -> Sink.DROP
            Route.MIX -> if (speech || inPrompt) Sink.MIX else Sink.DROP
            Route.OWN -> Sink.OWN
        }
        return Step(sink, switched)
    }

    /** MIX could not be heard (music stalled or gone); the rest of this prompt plays on the own track. */
    fun fellBackToOwn() {
        route = Route.OWN
    }

    companion object {
        /** No speech for this long ends a prompt; the next speech chunk chooses the route again. */
        const val PROMPT_GAP_NS = 1_200_000_000L
        /**
         * MIX only when the music renderer wrote within this window at prompt start. Short, so a
         * prompt that starts right after music stops goes to its own track, yet above the normal
         * AAC/Wi-Fi write cadence so a prompt during steady music still mixes.
         */
        const val DECIDE_HOST_RECENT_NS = 250_000_000L
    }
}
