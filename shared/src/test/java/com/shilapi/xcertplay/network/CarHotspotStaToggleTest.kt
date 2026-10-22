package com.shilapi.xcertplay.network

import android.net.wifi.WifiConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AOSP 4.4 WifiController.StaEnabledState drops CMD_SET_AP: STA must be off before the AP request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarHotspotStaToggleTest {
    @Before fun reset() { CarHotspotTools.staDisabledForAp = false }

    private fun configuration(): CarHotspotConfiguration = requireNotNull(
        CarHotspotTools.configurationFrom(WifiConfiguration().apply {
            SSID = "GL"; preSharedKey = "testpass123"
            allowedKeyManagement.clear(); allowedKeyManagement.set(4)
        }, bandValue = 0, channelValue = 6),
    )

    private class Fake(
        val config: CarHotspotConfiguration,
        var apState: Int,
        var wifiState: Int,
        val staTurnsOff: Boolean = true,
        val apComesUp: Boolean = true,
    ) : CarHotspotWifiAccess {
        val calls = mutableListOf<String>()
        override fun readConfiguration() = config
        override fun readState(): Int = apState
        override fun readWifiState(): Int = wifiState
        override fun setStationEnabled(enabled: Boolean): Boolean {
            calls += "sta=$enabled"
            if (!enabled && staTurnsOff) wifiState = CarHotspotTools.WIFI_STATE_DISABLED
            if (enabled) wifiState = CarHotspotTools.WIFI_STATE_ENABLED
            return true
        }
        override fun requestEnable(configuration: CarHotspotConfiguration): Boolean {
            calls += "ap=on(wifi=$wifiState)"
            // Mirrors StaEnabledState: the request is silently dropped while STA is on.
            if (apComesUp && wifiState == CarHotspotTools.WIFI_STATE_DISABLED) apState = 13
            return true
        }
        override fun requestDisable(configuration: CarHotspotConfiguration?): Boolean {
            calls += "ap=off"; apState = 11; return true
        }
    }

    @Test
    fun turnsStationOffFirstAndRestoresItOnStop() {
        var now = 0L
        val fake = Fake(configuration(), apState = 11, wifiState = CarHotspotTools.WIFI_STATE_ENABLED)
        val result = CarHotspotTools.start(fake, 1000, 100, { now += it }, { false }, { now })
        assertTrue(result is CarHotspotStartResult.Enabled)
        assertEquals(listOf("sta=false", "ap=on(wifi=1)"), fake.calls)
        assertTrue(CarHotspotTools.staDisabledForAp)

        val stop = CarHotspotTools.stop(fake, 1000, 100, { now += it }, { false }, { now })
        assertTrue(stop is CarHotspotStopResult.Disabled)
        assertEquals(listOf("sta=false", "ap=on(wifi=1)", "ap=off", "sta=true"), fake.calls)
        assertFalse(CarHotspotTools.staDisabledForAp)
    }

    @Test
    fun failedStateFromAnEarlierAttemptIsRetriedNotReportedAgain() {
        var now = 0L
        val fake = Fake(configuration(), apState = 14, wifiState = CarHotspotTools.WIFI_STATE_DISABLED)
        assertTrue(CarHotspotTools.start(fake, 1000, 100, { now += it }, { false }, { now }) is CarHotspotStartResult.Enabled)
        assertEquals(listOf("ap=on(wifi=1)"), fake.calls)
    }

    @Test
    fun stationOffAlreadyMeansNoToggle() {
        var now = 0L
        val fake = Fake(configuration(), apState = 11, wifiState = CarHotspotTools.WIFI_STATE_DISABLED)
        assertTrue(CarHotspotTools.start(fake, 1000, 100, { now += it }, { false }, { now }) is CarHotspotStartResult.Enabled)
        assertEquals(listOf("ap=on(wifi=1)"), fake.calls)
        assertFalse(CarHotspotTools.staDisabledForAp)
    }

    @Test
    fun stationThatStaysOnGivesDistinctReasonAndIsRestored() {
        var now = 0L
        val fake = Fake(configuration(), apState = 11, wifiState = CarHotspotTools.WIFI_STATE_ENABLED, staTurnsOff = false)
        val result = CarHotspotTools.start(fake, 1000, 100, { now += it }, { false }, { now })
        assertEquals(CarHotspotFailure.START_TIMED_OUT_STA_ON, (result as CarHotspotStartResult.Failure).reason)
        assertEquals("sta=true", fake.calls.last())
        assertFalse(CarHotspotTools.staDisabledForAp)
    }
}
