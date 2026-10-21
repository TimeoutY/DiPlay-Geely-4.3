package com.shilapi.xcertplay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.shilapi.xcertplay.logs.DiPlayLog
import com.shilapi.xcertplay.network.CarHotspotFailure
import com.shilapi.xcertplay.network.CarHotspotStartResult
import com.shilapi.xcertplay.network.CarHotspotTools
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.atomic.AtomicBoolean

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
                run(appContext, reason)
            } finally {
                running.set(false)
                stopSelf()
            }
        }, "diplay-boot-hotspot").start()
        return START_NOT_STICKY
    }

    companion object {
        const val TAG = "DiPlay-BootHotspot"
        private const val EXTRA_REASON = "reason"
        private val running = AtomicBoolean(false)
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

        private fun run(context: Context, reason: String) {
            val bootAge = SystemClock.elapsedRealtime() / 1000
            val modeBefore = AirPlayPersistence.loadWirelessHotspotMode(context)
            DiPlayLog.note(TAG, "auto-start ($reason) begin uptime=${bootAge}s mode=$modeBefore")
            // App launch long after boot: no need to wait for the Wi-Fi stack.
            val delays = if (reason == "app-launch") longArrayOf(500, 5_000, 10_000) else ATTEMPT_DELAYS_MS
            for ((index, delay) in delays.withIndex()) {
                SystemClock.sleep(delay)
                if (!AirPlayPersistence.loadBootHotspotEnabled(context)) {
                    DiPlayLog.note(TAG, "auto-start ($reason) stopped: setting turned off")
                    return
                }
                val attempt = index + 1
                when (val result = CarHotspotTools.start(context)) {
                    is CarHotspotStartResult.AlreadyEnabled -> {
                        persist(context, result.configuration, reason)
                        DiPlayLog.note(TAG, "attempt $attempt/${delays.size}: hotspot already on ssid=${result.configuration.ssid}")
                        return
                    }
                    is CarHotspotStartResult.Enabled -> {
                        persist(context, result.configuration, reason)
                        DiPlayLog.note(TAG, "attempt $attempt/${delays.size}: hotspot ENABLED ssid=${result.configuration.ssid}")
                        return
                    }
                    is CarHotspotStartResult.Failure -> {
                        val fatal = result.reason in FATAL
                        DiPlayLog.note(TAG, "attempt $attempt/${delays.size}: failed reason=${result.reason}" +
                            if (fatal) " (not retrying)" else "", warn = true)
                        if (fatal) return
                    }
                }
            }
            DiPlayLog.note(TAG, "auto-start ($reason) gave up after ${delays.size} attempts", warn = true)
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
