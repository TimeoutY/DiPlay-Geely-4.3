package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockBtMusicQuietTest {
    @Test
    fun discoveryMatchesKnownVendorPackages() {
        assertTrue(StockBtMusicQuiet.matchesDiscovery("com.ecarx.multimedia"))
        assertTrue(StockBtMusicQuiet.matchesDiscovery("com.neusoft.na.multimedia"))
        assertTrue(StockBtMusicQuiet.matchesDiscovery("com.nforetek.bt.music"))
        assertTrue(StockBtMusicQuiet.matchesDiscovery("com.vendor.btmusic"))
        assertTrue(StockBtMusicQuiet.matchesDiscovery("com.foo.bluetooth.music.player"))
        assertTrue(StockBtMusicQuiet.matchesDiscovery("Com.ECarX.MultiMedia"))
    }

    @Test
    fun discoverySkipsUnrelatedPackages() {
        assertFalse(StockBtMusicQuiet.matchesDiscovery("com.shilapi.xcertplay"))
        assertFalse(StockBtMusicQuiet.matchesDiscovery("com.android.settings"))
        assertFalse(StockBtMusicQuiet.matchesDiscovery("com.geely.launcher"))
        // bluetooth without music
        assertFalse(StockBtMusicQuiet.matchesDiscovery("com.android.bluetooth"))
    }

    @Test
    fun candidatesIncludeEcarxAndNeusoft() {
        assertTrue(StockBtMusicQuiet.CANDIDATES.any { it.contains("ecarx") })
        assertTrue(StockBtMusicQuiet.CANDIDATES.any { it.contains("neusoft") })
        assertTrue(StockBtMusicQuiet.CANDIDATES.any { it.contains("nfore") })
    }
}
