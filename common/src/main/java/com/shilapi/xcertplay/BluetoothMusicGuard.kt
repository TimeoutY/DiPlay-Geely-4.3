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
 * Optional 「连接 CarPlay 时断开蓝牙音乐」: drop only the iPhone's A2DP link so the car's
 * Bluetooth-music app cannot AVRCP-pause CarPlay. Default is OFF — on Geely GKUI the steering
 * wheel's next/prev reach the iPhone over AVRCP, so dropping A2DP (or the whole BT link) kills
 * song switching. When the setting is off we only report which profiles are up, and rely on
 * audio focus + [StockBtMusicQuiet] + [PauseLoopGuard] to keep music playing.
 *
 * HFP (calls) and the iAP2 link are never touched. Nothing is restored on session end.
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
    /** Last status line for the log-page summary (never null after the first session event). */
    @Volatile var lastStatus: String = "尚未连接"
        private set

    fun onWirelessSessionActive(context: Context) {
        val app = context.applicationContext
        val installed = StockBtMusicQuiet.installedPackages(app)
        if (!AirPlayPersistence.loadDropBluetoothMusic(app)) {
            lastStatus = "保持连接（方向盘切歌靠 AVRCP）" +
                if (installed.isNotEmpty()) "；已装原车音乐=${installed.joinToString(",")}" else ""
            DiPlayLog.note(TAG, "A2DP drop skipped: setting off (keep BT/AVRCP for steering-wheel keys). " +
                "stockPackages=${installed.ifEmpty { listOf("none") }}")
            // Softly quiet the stock BT-music app so it does not AVRCP-pause after we take focus.
            StockBtMusicQuiet.quiet(app, "session-connect(keep-bt)")
            reportConnectedProfiles(app, pass = 0)
            return
        }
        lastStatus = "尝试断开 A2DP（通话 HFP 保留）"
        val current = ++generation
        check(app, current, pass = 1)
        handler.postDelayed({ if (generation == current) check(app, current, pass = 2) }, RECHECK_DELAY_MS)
    }

    fun onSessionEnded() {
        generation++
        lastStatus = "会话结束"
    }

    /** Best-effort profile inventory for the log summary; never disconnects. */
    private fun reportConnectedProfiles(context: Context, pass: Int) {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || runCatching { adapter.isEnabled }.getOrDefault(false) != true) {
            DiPlayLog.note(TAG, "BT status pass=$pass: Android Bluetooth adapter unavailable/off -> $VENDOR_STACK" +
                vendorHint(context))
            return
        }
        val profiles = if (android.os.Build.VERSION.SDK_INT >= 21) intArrayOf(BluetoothProfile.A2DP, A2DP_SINK)
        else intArrayOf(BluetoothProfile.A2DP)
        for (profileId in profiles) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            val devices = runCatching { proxy.connectedDevices.orEmpty() }.getOrDefault(emptyList())
                            val names = devices.joinToString(",") {
                                "${runCatching { it.name }.getOrNull() ?: "?"} ${mask(it.address)}"
                            }.ifEmpty { "none" }
                            DiPlayLog.note(TAG, "BT status pass=$pass profile=${name(profileId)} connected=[$names] (not disconnecting)")
                            if (devices.isNotEmpty()) lastStatus = "已连接 ${name(profileId)}×${devices.size}（保持，供方向盘 AVRCP）"
                        } finally {
                            runCatching { adapter.closeProfileProxy(profile, proxy) }
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) = Unit
                }, profileId)
            }
        }
    }

    private fun check(context: Context, current: Int, pass: Int) {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || runCatching { adapter.isEnabled }.getOrDefault(false) != true) {
            lastStatus = "无法断开：$VENDOR_STACK"
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
            lastStatus = "标准 A2DP 无设备 → $VENDOR_STACK"
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
            lastStatus = "已请求断开 ${name(profileId)} $label（HFP 保留）accepted=$accepted"
            DiPlayLog.note(TAG, "A2DP drop pass=$pass profile=${name(profileId)}: disconnect $label accepted=$accepted (HFP kept)")
        }.onFailure { error ->
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            lastStatus = "断开失败 ${cause.javaClass.simpleName}"
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
