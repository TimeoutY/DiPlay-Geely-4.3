package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact

internal object TouchCoalescing {
    /** True when [next] only moves the same pressed contacts as [previous] (a mergeable MOVE). */
    fun isMoveOf(previous: List<AirPlayContact>, next: List<AirPlayContact>): Boolean {
        if (previous.size != next.size || previous.isEmpty()) return false
        for (i in previous.indices) {
            val a = previous[i]
            val b = next[i]
            if (a.id != b.id || !a.down || !b.down) return false
        }
        return true
    }
}
