package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StockBtMusicQuietPlanTest {
    @Test
    fun settingOffDoesNothingEvenInFightMode() {
        val plan = StockBtMusicQuiet.plan(settingEnabled = false, fightMode = true)
        assertFalse(plan.politePause); assertFalse(plan.kill); assertFalse(plan.mediaKeyBroadcast)
    }

    @Test
    fun settingOnPausesPolitelyAndKillsOnlyInFightMode() {
        assertEquals(StockBtMusicQuiet.Plan(politePause = true, kill = false), StockBtMusicQuiet.plan(true, false))
        assertEquals(StockBtMusicQuiet.Plan(politePause = true, kill = true), StockBtMusicQuiet.plan(true, true))
        assertFalse(StockBtMusicQuiet.plan(true, true).mediaKeyBroadcast)
    }

    @Test
    fun killTargetsAreExactCandidatesOnly() {
        val installed = listOf("com.ecarx.multimedia", "com.ecarx.keyservice", "com.ecarx.carsetting", "com.neusoft.btmusic")
        assertEquals(listOf("com.ecarx.multimedia", "com.neusoft.btmusic"), StockBtMusicQuiet.killTargets(installed))
    }
}
