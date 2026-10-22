package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.logs.DiPlayLog

/**
 * 「连接 CarPlay 时断开蓝牙音乐」: once wireless CarPlay is up, drop only the iPhone's A2DP link
 * (the car's Bluetooth-music source, whose AVRCP pause on focus loss stopped CarPlay music,
 * issue #1). HFP (calls) and the iAP2 link are untouched. Nothing is restored on session end:
 * the iPhone and the car reconnect Bluetooth audio by themselves after CarPlay ends.
 */
internal object BluetoothMusicGuard {
    private const val TAG = "DiPlay-BtAudio"
    /** Hidden BluetoothProfile.A2DP_SINK (API 21+); getProfileProxy simply returns false before that. */
    private const val A2DP_SINK = 11
    private const val RECHECK_DELAY_MS = 8_000L
    /**
     * Geely units often run Bluetooth on a vendor module (nForeTek/Goodocom, ecarx.bluetooth.service)
     * invisible to Android's BluetoothAdapter. We deliberately never call those vendor services.
     */
    private const val VENDOR_STACK = "vendor Bluetooth stack, cannot drop A2DP (vendor services are not touched)"
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    fun onWirelessSessionActive(context: Context) {
        val app = context.applicationContext
        if (!AirPlayPersistence.loadDropBluetoothMusic(app)) {
            DiPlayLog.note(TAG, "A2DP drop skipped: setting off")
            return
        }
        val current = ++generation
        check(app, current, pass = 1)
        handler.postDelayed({ if (generation == current) check(app, current, pass = 2) }, RECHECK_DELAY_MS)
    }

    fun onSessionEnded() {
        generation++
    }

    private fun check(context: Context, current: Int, pass: Int) {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || runCatching { adapter.isEnabled }.getOrDefault(false) != true) {
            DiPlayLog.note(TAG, "A2DP drop pass=$pass: Android Bluetooth adapter unavailable/off -> $VENDOR_STACK" +
                vendorHint(context), warn = true)
            return
        }
        // A2DP_SINK only exists from API 21; on 4.x asking for it just logs noise.
        val profiles = if (android.os.Build.VERSION.SDK_INT >= 21) intArrayOf(BluetoothProfile.A2DP, A2DP_SINK)
        else intArrayOf(BluetoothProfile.A2DP)
        for (profileId in profiles) {
            val requested = runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            if (generation == current) disconnectPhone(context, profile, proxy, pass)
                        } finally {
                            runCatching { adapter.closeProfileProxy(profile, proxy) }
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) = Unit
                }, profileId)
            }.getOrElse { error ->
                DiPlayLog.note(TAG, "A2DP drop pass=$pass profile=${name(profileId)} proxy error ${error.javaClass.simpleName}: ${error.message}", warn = true)
                false
            }
            if (!requested) DiPlayLog.note(TAG, "A2DP drop pass=$pass profile=${name(profileId)} proxy unavailable on this ROM -> $VENDOR_STACK")
        }
    }

    private fun disconnectPhone(context: Context, profileId: Int, proxy: BluetoothProfile, pass: Int) {
        val devices = runCatching { proxy.connectedDevices.orEmpty() }.getOrElse { error ->
            DiPlayLog.note(TAG, "A2DP drop pass=$pass profile=${name(profileId)} list failed: ${error.javaClass.simpleName}", warn = true)
            return
        }
        if (devices.isEmpty()) {
            DiPlayLog.note(TAG, "A2DP drop pass=$pass profile=${name(profileId)}: no connected devices -> $VENDOR_STACK" +
                if (pass == 1) vendorHint(context) else "")
            return
        }
        val selected = DiPlayPreferences.phoneAddress(context)?.takeIf { it.isNotBlank() }
        // With a selected iPhone, only that exact device may be dropped (never a passenger's phone).
        // Without one, fall back to the single connected phone-class device.
        val target = if (selected != null) {
            devices.firstOrNull { it.address.equals(selected, ignoreCase = true) }
        } else {
            devices.filter { isPhone(it) }.singleOrNull()
        }
        if (target == null) {
            DiPlayLog.note(TAG, "A2DP drop pass=$pass profile=${name(profileId)}: ${devices.size} device(s), " +
                "none matches the selected iPhone; leaving them connected")
            return
        }
        val result = runCatching {
            proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(proxy, target) as? Boolean
        }
        val label = "${runCatching { target.name }.getOrNull() ?: "?"} ${mask(target.address)}"
        result.onSuccess { accepted ->
            DiPlayLog.note(TAG, "A2DP drop pass=$pass profile=${name(profileId)}: disconnect $label accepted=$accepted (HFP kept)")
        }.onFailure { error ->
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            DiPlayLog.note(TAG, "A2DP drop pass=$pass profile=${name(profileId)}: disconnect $label failed " +
                "${cause.javaClass.simpleName}: ${cause.message}", warn = true)
        }
    }

    private fun isPhone(device: BluetoothDevice): Boolean =
        runCatching { device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }.getOrDefault(false)

    private fun vendorHint(context: Context): String =
        if (DiPlayPreferences.phoneUsesGeelyBluetooth(context)) {
            " (iPhone uses the Geely vendor Bluetooth stack; Android's A2DP API may not see it)"
        } else ""

    private fun name(profile: Int) = if (profile == BluetoothProfile.A2DP) "A2DP" else "A2DP_SINK"
    private fun mask(address: String?): String = address?.let { "**:**:**:" + it.takeLast(8) } ?: "?"
}
