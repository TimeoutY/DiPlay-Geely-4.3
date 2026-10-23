package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import com.shilapi.xcertplay.logs.DiPlayLog

/**
 * Softly quiets the car's stock Bluetooth-music app so it stops AVRCP-pausing the iPhone after we
 * take STREAM_MUSIC focus. Prefers polite pause/stop intents; falls back to
 * [ActivityManager.killBackgroundProcesses] (needs KILL_BACKGROUND_PROCESSES) — never force-stop,
 * which on some GKUI builds can reboot or break the OEM media stack.
 *
 * Package list is best-effort: known candidates plus runtime discovery of installed/running
 * packages matching ecarx|neusoft|nfore|bluetooth.*music|multimedia|btmusic.
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

    @Volatile private var lastQuietAt = 0L
    private const val MIN_GAP_MS = 8_000L

    /**
     * @param force skip the short throttle (fight-mode / re-assert). Still never force-stops.
     */
    fun quiet(context: Context, reason: String, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastQuietAt < MIN_GAP_MS) {
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
        val targets = discoverPackages(context, pm, am)
        DiPlayLog.note(TAG, "stock BT music quiet discover reason=$reason packages=${targets.ifEmpty { listOf("none") }}")
        if (targets.isEmpty()) return

        // Polite pause first (common media intents + package-targeted), then soft kill.
        sendPolitePause(context, targets)

        val quieted = mutableListOf<String>()
        for (pkg in targets) {
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
            DiPlayLog.note(TAG, "stock BT music quieted pkgs=${quieted.joinToString(",")} via pause+killBackgroundProcesses ($reason)", warn = true)
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
        // Global-ish media pause broadcasts used by many OEM players.
        val broadcasts = listOf(
            Intent("com.android.music.musicservicecommand").putExtra("command", "pause"),
            Intent("com.android.music.musicservicecommand").putExtra("command", "stop"),
            Intent("com.android.music.musicservicecommand.pause"),
            Intent("com.neusoft.music.musicservicecommand").putExtra("command", "pause"),
            Intent("com.ecarx.music.musicservicecommand").putExtra("command", "pause"),
        )
        for (intent in broadcasts) {
            runCatching { context.sendBroadcast(intent) }
        }
        // MEDIA_BUTTON PAUSE down+up (and package-targeted copies).
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
        fun mediaButton(target: String?): Intent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON)
            i.putExtra(Intent.EXTRA_KEY_EVENT, down)
            if (target != null) i.setPackage(target)
            return i
        }
        runCatching { context.sendOrderedBroadcast(mediaButton(null), null) }
        runCatching {
            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, up)
            context.sendOrderedBroadcast(upIntent, null)
        }
        for (pkg in packages) {
            runCatching { context.sendOrderedBroadcast(mediaButton(pkg), null) }
            runCatching {
                context.sendBroadcast(
                    Intent("com.android.music.musicservicecommand").setPackage(pkg).putExtra("command", "pause"),
                )
            }
        }
        // API 19+: dispatch through AudioManager so AVRCP-aware stacks see a pause.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            runCatching {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                audio?.javaClass?.getMethod("dispatchMediaKeyEvent", KeyEvent::class.java)
                    ?.invoke(audio, down)
                audio?.javaClass?.getMethod("dispatchMediaKeyEvent", KeyEvent::class.java)
                    ?.invoke(audio, up)
            }
        }
        DiPlayLog.note(TAG, "stock BT music polite pause/stop intents sent to ${packages.size} package(s)")
    }

    private fun installed(pm: PackageManager, pkg: String): Boolean =
        runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
