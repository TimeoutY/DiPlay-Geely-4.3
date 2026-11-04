package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.shilapi.xcertplay.logs.DiPlayLog

/**
 * Optionally quiets the car's stock Bluetooth-music app so it stops AVRCP-pausing the iPhone after
 * we take STREAM_MUSIC focus.
 *
 * nav build: OFF by default (setting 「静默原车蓝牙音乐」). On GKUI the same ECarX/Neusoft media
 * stack that plays BT music also turns the steering-wheel NEXT/PREV into AVRCP commands for the
 * iPhone. btkeys/excl sent it MEDIA_BUTTON PAUSE broadcasts, AudioManager.dispatchMediaKeyEvent
 * PAUSE and killBackgroundProcesses on every focus grant / re-assert, which left the key bridge
 * dead (steering NEXT stopped switching songs). Exclusive focus + PauseLoopGuard alone keep the
 * pause/play loop fixed, so by default this does nothing but log.
 *
 * When the user turns it on: package-targeted musicservicecommand pause only (never a
 * MEDIA_BUTTON broadcast or dispatchMediaKeyEvent, which reach the media-button owner, i.e. the
 * key bridge or ourselves). killBackgroundProcesses only in fight mode and only for the exact
 * known BT-music [CANDIDATES], never for runtime-discovered packages. Never force-stop.
 */
internal object StockBtMusicQuiet {
    private const val TAG = "DiPlay-BtAudio"

    /** Known GKUI / ECarX / Neusoft / nFore Bluetooth-music packages seen on Geely 4.x units. */
    val CANDIDATES = listOf(
        "com.ecarx.multimedia",
        "com.ecarx.btmusic",
        "com.ecarx.bluetooth.music",
        "com.neusoft.na.multimedia",
        "com.neusoft.multimedia",
        "com.neusoft.automotive.media",
        "com.neusoft.btmusic",
        "com.geely.multimedia",
        "com.geely.bluetooth.music",
        "com.nforetek.bt.music",
        "com.nfore.btmusic",
        "com.goodocom.btmusic",
    )

    private val DISCOVERY_NEEDLES = listOf(
        "ecarx", "neusoft", "nfore", "btmusic", "multimedia", "bluetooth.music", "bluetoothmusic",
    )

    /** What a quiet call may do. Pure so the default-off / no-kill rules are unit-tested. */
    data class Plan(val politePause: Boolean, val kill: Boolean, val mediaKeyBroadcast: Boolean = false)

    internal fun plan(settingEnabled: Boolean, fightMode: Boolean): Plan = when {
        !settingEnabled -> Plan(politePause = false, kill = false)
        else -> Plan(politePause = true, kill = fightMode)
    }

    /** Kill targets are restricted to exact known BT-music packages (never discovery matches). */
    internal fun killTargets(installed: Collection<String>): List<String> = installed.filter { it in CANDIDATES }

    @Volatile private var lastQuietAt = 0L
    @Volatile private var lastSkipLogAt = 0L
    private const val MIN_GAP_MS = 8_000L

    /**
     * @param fightMode PauseLoopGuard fight mode: the only case allowed to killBackgroundProcesses.
     */
    fun quiet(context: Context, reason: String, fightMode: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        val plan = plan(AirPlayPersistence.loadQuietStockBtMusic(context), fightMode)
        if (!plan.politePause && !plan.kill) {
            if (now - lastSkipLogAt >= MIN_GAP_MS) {
                lastSkipLogAt = now
                DiPlayLog.note(TAG, "stock BT music quiet SKIPPED ($reason): setting 「静默原车蓝牙音乐」 off; " +
                    "no pause broadcast / no kill, steering-wheel key bridge left alone")
            }
            return
        }
        if (!fightMode && now - lastQuietAt < MIN_GAP_MS) {
            DiPlayLog.note(TAG, "stock BT music quiet skipped (throttled ${now - lastQuietAt}ms): $reason")
            return
        }
        lastQuietAt = now
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val targets = discoverPackages(context, pm, am)
        DiPlayLog.note(TAG, "stock BT music quiet reason=$reason plan=$plan packages=${targets.ifEmpty { listOf("none") }}")
        if (targets.isEmpty()) return
        sendPolitePause(context, targets)
        if (!plan.kill || am == null) return
        val quieted = mutableListOf<String>()
        for (pkg in killTargets(targets)) {
            val ok = runCatching {
                @Suppress("DEPRECATION")
                am.killBackgroundProcesses(pkg)
                true
            }.getOrElse { error ->
                DiPlayLog.note(TAG, "stock BT music quiet $pkg failed ${error.javaClass.simpleName}: ${error.message}", warn = true)
                false
            }
            if (ok) quieted += pkg
        }
        if (quieted.isNotEmpty()) {
            DiPlayLog.note(TAG, "stock BT music killBackgroundProcesses pkgs=${quieted.joinToString(",")} (fight mode, $reason)", warn = true)
        }
    }

    fun installedPackages(context: Context): List<String> =
        discoverPackages(context, context.packageManager, null)

    /** Exposed for unit tests: package name matches runtime discovery needles. */
    internal fun matchesDiscovery(packageName: String): Boolean {
        val n = packageName.lowercase()
        if (DISCOVERY_NEEDLES.any { n.contains(it) }) return true
        // bluetooth.*music (any chars between)
        val bt = n.indexOf("bluetooth")
        return bt >= 0 && n.indexOf("music", bt + 9) >= 0
    }

    private fun discoverPackages(context: Context, pm: PackageManager, am: ActivityManager?): List<String> {
        val found = linkedSetOf<String>()
        for (pkg in CANDIDATES) {
            if (installed(pm, pkg)) found += pkg
        }
        // Installed packages matching needles (best-effort; getInstalledPackages may be filtered).
        runCatching {
            @Suppress("DEPRECATION")
            val pkgs = pm.getInstalledPackages(0) ?: return@runCatching
            for (info in pkgs) {
                val name = info.packageName ?: continue
                if (matchesDiscovery(name)) found += name
            }
        }.onFailure { error ->
            DiPlayLog.note(TAG, "stock BT music discover installed failed ${error.javaClass.simpleName}: ${error.message}")
        }
        // Running processes
        if (am != null) {
            runCatching {
                @Suppress("DEPRECATION")
                val procs = am.runningAppProcesses ?: return@runCatching
                for (proc in procs) {
                    val list = proc.pkgList ?: continue
                    for (name in list) {
                        if (name != null && matchesDiscovery(name)) found += name
                    }
                }
            }.onFailure { error ->
                DiPlayLog.note(TAG, "stock BT music discover running failed ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        // Never quiet ourselves
        found.remove(context.packageName)
        return found.toList()
    }

    private fun sendPolitePause(context: Context, packages: List<String>) {
        // Package-targeted only. No MEDIA_BUTTON broadcast and no dispatchMediaKeyEvent: those
        // reach the current media-button owner (GKUI's steering-wheel/AVRCP bridge or DiPlay).
        for (pkg in packages) {
            runCatching {
                context.sendBroadcast(
                    Intent("com.android.music.musicservicecommand").setPackage(pkg).putExtra("command", "pause"),
                )
            }
        }
        DiPlayLog.note(TAG, "stock BT music package-targeted pause sent to ${packages.size} package(s)")
    }

    private fun installed(pm: PackageManager, pkg: String): Boolean =
        runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
