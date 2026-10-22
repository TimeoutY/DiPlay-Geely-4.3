package com.shilapi.xcertplay

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.SystemClock
import com.shilapi.xcertplay.logs.DiPlayLog
import com.shilapi.xcertplay.network.CarHotspotFailure
import com.shilapi.xcertplay.network.CarHotspotStartResult
import com.shilapi.xcertplay.network.CarHotspotState
import com.shilapi.xcertplay.network.CarHotspotTools
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns the car's own Wi-Fi hotspot on after boot (or once per app start), retrying while the
 * Wi-Fi stack comes up. Uses the same CarHotspotTools path as the home 「启动热点」 button.
 * A started Service keeps the process alive for the ~60 s retry window, which a
 * BroadcastReceiver alone cannot.
 */
class BootHotspotService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "unknown"
        if (!running.compareAndSet(false, true)) {
            DiPlayLog.note(TAG, "auto-start ($reason) ignored: already running")
            return START_NOT_STICKY
        }
        val appContext = applicationContext
        Thread({
            try {
                val started = try {
                    run(appContext, reason)
                } finally {
                    // The retry phase is over: the home button may act again even while we watch.
                    running.set(false)
                }
                if (started && watching.compareAndSet(false, true)) {
                    try { watchAfterStart(appContext, reason) } finally { watching.set(false) }
                }
            } finally {
                stopSelf(startId)
            }
        }, "diplay-boot-hotspot").start()
        return START_NOT_STICKY
    }

    companion object {
        const val TAG = "DiPlay-BootHotspot"
        private const val EXTRA_REASON = "reason"
        private val running = AtomicBoolean(false)
        private val watching = AtomicBoolean(false)
        /** True while an auto-start run is retrying; the home button waits instead of racing it. */
        val isRunning: Boolean get() = running.get()
        @Volatile private var attemptedThisProcess = false
        /** Delays before each attempt; total window about 60 s after boot. */
        private val ATTEMPT_DELAYS_MS = longArrayOf(5_000, 8_000, 10_000, 12_000, 12_000, 13_000)

        /** Called from BootReceiver and DiPlayActivity; respects the 「开机自动开启热点」 setting. */
        fun request(context: Context, reason: String) {
            DiPlayLog.start(context)
            if (!AirPlayPersistence.loadBootHotspotEnabled(context)) {
                DiPlayLog.note(TAG, "auto-start ($reason) skipped: setting off")
                return
            }
            if (reason == "app-launch" && attemptedThisProcess) return
            attemptedThisProcess = true
            val result = runCatching {
                context.startService(Intent(context, BootHotspotService::class.java).putExtra(EXTRA_REASON, reason))
            }
            result.exceptionOrNull()?.let {
                DiPlayLog.note(TAG, "auto-start ($reason) could not start service: ${it.javaClass.simpleName}: ${it.message}", warn = true)
            }
        }

        /** Returns true when the hotspot is on at the end (so the AP watchdog should run). */
        private fun run(context: Context, reason: String): Boolean {
            val bootAge = SystemClock.elapsedRealtime() / 1000
            val modeBefore = AirPlayPersistence.loadWirelessHotspotMode(context)
            DiPlayLog.note(TAG, "auto-start ($reason) begin uptime=${bootAge}s mode=$modeBefore")
            // App launch long after boot: no need to wait for the Wi-Fi stack.
            val delays = if (reason == "app-launch") longArrayOf(500, 5_000, 10_000) else ATTEMPT_DELAYS_MS
            for ((index, delay) in delays.withIndex()) {
                SystemClock.sleep(delay)
                if (!AirPlayPersistence.loadBootHotspotEnabled(context)) {
                    DiPlayLog.note(TAG, "auto-start ($reason) stopped: setting turned off")
                    return false
                }
                val attempt = index + 1
                // Never touch the AP under a running CarPlay session (it would drop a Wi-Fi Direct link).
                if (CarPlayBackgroundSession.hasSession()) {
                    DiPlayLog.note(TAG, "auto-start ($reason) stopped: CarPlay session is running")
                    return false
                }
                if (DiPlayActivity.hotspotOperationInProgress()) {
                    DiPlayLog.note(TAG, "attempt $attempt/${delays.size}: home hotspot button busy; waiting")
                    continue
                }
                when (val result = CarHotspotTools.start(context)) {
                    is CarHotspotStartResult.AlreadyEnabled -> {
                        persist(context, result.configuration, reason)
                        DiPlayLog.note(TAG, "attempt $attempt/${delays.size}: hotspot already on ssid=${result.configuration.ssid}")
                        return true
                    }
                    is CarHotspotStartResult.Enabled -> {
                        persist(context, result.configuration, reason)
                        DiPlayLog.note(TAG, "attempt $attempt/${delays.size}: hotspot ENABLED ssid=${result.configuration.ssid}")
                        return true
                    }
                    is CarHotspotStartResult.Failure -> {
                        val fatal = result.reason in FATAL
                        DiPlayLog.note(TAG, "attempt $attempt/${delays.size}: failed reason=${result.reason}" +
                            if (fatal) " (not retrying)" else "", warn = true)
                        if (fatal) return false
                    }
                }
            }
            DiPlayLog.note(TAG, "auto-start ($reason) gave up after ${delays.size} attempts", warn = true)
            return false
        }

        private const val WATCH_WINDOW_MS = 90_000L
        private const val ACTION_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_AP_STATE = "wifi_state"

        /**
         * Some stock firmware turns the AP back off shortly after boot (its own Wi-Fi restore).
         * Watch WIFI_AP_STATE_CHANGED for a short window; if the AP goes off without the app asking,
         * log it and re-enable it at most once. Runs on the service's worker thread.
         */
        private fun watchAfterStart(context: Context, reason: String) {
            val latch = AtomicReference(CountDownLatch(1))
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val raw = intent?.getIntExtra(EXTRA_AP_STATE, -1) ?: return
                    val state = CarHotspotTools.stateFromRaw(raw)
                    if (state == CarHotspotState.DISABLED || state == CarHotspotState.FAILED) latch.get().countDown()
                }
            }
            val watchStart = SystemClock.elapsedRealtime()
            try {
                context.registerReceiver(receiver, IntentFilter(ACTION_AP_STATE_CHANGED))
            } catch (error: Throwable) {
                DiPlayLog.note(TAG, "AP watchdog unavailable: ${error.javaClass.simpleName}", warn = true)
                return
            }
            DiPlayLog.note(TAG, "AP watchdog ($reason) watching for ${WATCH_WINDOW_MS / 1000}s")
            var reEnabled = false
            try {
                while (true) {
                    val remaining = WATCH_WINDOW_MS - (SystemClock.elapsedRealtime() - watchStart)
                    if (remaining <= 0) break
                    if (!latch.get().await(remaining, TimeUnit.MILLISECONDS)) break
                    latch.set(CountDownLatch(1))
                    val after = SystemClock.elapsedRealtime() - watchStart
                    if (CarHotspotTools.lastStopRequestAtMillis >= watchStart) {
                        DiPlayLog.note(TAG, "AP watchdog: hotspot stopped by the app/user after ${after}ms; not re-enabling")
                        return
                    }
                    DiPlayLog.note(TAG, "AP watchdog: firmware turned the hotspot OFF ${after}ms after start", warn = true)
                    if (reEnabled) {
                        DiPlayLog.note(TAG, "AP watchdog: already re-enabled once; giving up", warn = true)
                        return
                    }
                    if (CarPlayBackgroundSession.hasSession() || DiPlayActivity.hotspotOperationInProgress()) {
                        DiPlayLog.note(TAG, "AP watchdog: session or home button busy; not re-enabling")
                        return
                    }
                    reEnabled = true
                    SystemClock.sleep(1_000)
                    val result = CarHotspotTools.start(context)
                    DiPlayLog.note(TAG, "AP watchdog: re-enable once -> " + when (result) {
                        is CarHotspotStartResult.Enabled -> "ENABLED"
                        is CarHotspotStartResult.AlreadyEnabled -> "already on"
                        is CarHotspotStartResult.Failure -> "failed reason=${result.reason}"
                    }, warn = result is CarHotspotStartResult.Failure)
                }
                DiPlayLog.note(TAG, "AP watchdog ($reason) window ended")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        private val FATAL = setOf(
            CarHotspotFailure.CONFIGURATION_UNAVAILABLE,
            CarHotspotFailure.UNSUPPORTED_CONFIGURATION,
            CarHotspotFailure.PERMISSION_DENIED,
        )

        /** Same as the home button: keep the SSID/passphrase and switch to car-hotspot mode on success. */
        private fun persist(context: Context, configuration: com.shilapi.xcertplay.network.CarHotspotConfiguration, reason: String) {
            AirPlayPersistence.saveManualHotspotProfile(
                context,
                configuration.ssid,
                configuration.passphrase,
                configuration.security,
                configuration.band ?: ManualHotspotBand.AUTO,
                configuration.channel ?: 0,
            )
            val mode = AirPlayPersistence.loadWirelessHotspotMode(context)
            if (mode != WirelessHotspotMode.MANUAL) {
                AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
                DiPlayLog.note(TAG, "auto-start ($reason): wireless mode $mode -> MANUAL (car hotspot)")
            }
        }
    }
}
