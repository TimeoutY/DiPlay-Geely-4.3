package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.xcertplay.logs.DiPlayLog

/**
 * Softly quiets the car's stock Bluetooth-music app so it stops AVRCP-pausing the iPhone after we
 * take STREAM_MUSIC focus. Uses [ActivityManager.killBackgroundProcesses] only (needs the normal
 * KILL_BACKGROUND_PROCESSES permission) — never force-stop, which on some GKUI builds can reboot
 * or break the OEM media stack (see docs/geely-android43/H52-REPAIR-STATUS).
 *
 * Package list is best-effort: Geely/ECarX/Neusoft builds vary. Missing packages are skipped.
 */
internal object StockBtMusicQuiet {
    private const val TAG = "DiPlay-BtAudio"

    /** Known GKUI / ECarX / Neusoft Bluetooth-music packages seen on Geely 4.x units. */
    val CANDIDATES = listOf(
        "com.ecarx.multimedia",
        "com.neusoft.na.multimedia",
        "com.neusoft.multimedia",
        "com.geely.multimedia",
        "com.geely.bluetooth.music",
        "com.neusoft.automotive.media",
    )

    @Volatile private var lastQuietAt = 0L
    private const val MIN_GAP_MS = 30_000L

    fun quiet(context: Context, reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastQuietAt < MIN_GAP_MS) {
            DiPlayLog.note(TAG, "stock BT music quiet skipped (throttled ${now - lastQuietAt}ms): $reason")
            return
        }
        lastQuietAt = now
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            DiPlayLog.note(TAG, "stock BT music quiet failed: ActivityManager missing ($reason)", warn = true)
            return
        }
        var found = 0
        for (pkg in CANDIDATES) {
            if (!installed(pm, pkg)) continue
            found++
            val ok = runCatching {
                @Suppress("DEPRECATION")
                am.killBackgroundProcesses(pkg)
                true
            }.getOrElse { error ->
                DiPlayLog.note(TAG, "stock BT music quiet $pkg failed ${error.javaClass.simpleName}: ${error.message}", warn = true)
                false
            }
            if (ok) DiPlayLog.note(TAG, "stock BT music quieted pkg=$pkg via killBackgroundProcesses ($reason)", warn = true)
        }
        if (found == 0) {
            DiPlayLog.note(TAG, "stock BT music quiet: none of ${CANDIDATES.size} candidate packages installed ($reason)")
        }
    }

    fun installedPackages(context: Context): List<String> =
        CANDIDATES.filter { installed(context.packageManager, it) }

    private fun installed(pm: PackageManager, pkg: String): Boolean =
        runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
