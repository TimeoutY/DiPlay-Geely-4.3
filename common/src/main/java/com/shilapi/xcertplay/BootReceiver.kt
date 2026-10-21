package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        com.shilapi.xcertplay.logs.DiPlayLog.start(context)
        com.shilapi.xcertplay.logs.DiPlayLog.note(TAG, "boot broadcast ${intent.action}")
        // Independent of "open after the car starts": the hotspot can come up without the UI.
        BootHotspotService.request(context, "boot")
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        if (!launchEnabled) return

        val launch = Intent(context, DiPlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
        /** Some ROMs (and fast-boot head units) send QUICKBOOT_POWERON instead of BOOT_COMPLETED. */
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
