package com.shilapi.xcertplay.logs

import android.content.Context
import android.os.Environment
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-process log ring buffer for head units where adb is unavailable.
 *
 * A daemon thread follows `logcat -v time`. Since Android 4.1 an app without READ_LOGS only
 * sees its own uid's entries, which is exactly what we want; system lines (AudioService,
 * AudioFlinger) appear only on ROMs that still expose them. The buffer lives for the whole
 * process, so it survives switching to the CarPlay screen and back.
 */
object DiPlayLog {
    const val MAX_LINES = 5_000
    private const val TAG = "DiPlay-Log"

    private val lines = ArrayDeque<String>(MAX_LINES)
    @Volatile var version = 0L
        private set
    @Volatile private var started = false
    @Volatile var readerWorking = false
        private set
    @Volatile var readerError: String? = null
        private set
    private var readerProcess: java.lang.Process? = null

    /** Starts the background logcat follower once per process. Safe to call from any component. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val pid = Process.myPid()
        Thread({ follow(pid) }, "diplay-logcat").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        note(TAG, "log capture started pid=$pid package=${context.packageName}")
    }

    private fun follow(pid: Int) {
        var attempt = 0
        while (attempt < 5) {
            attempt++
            try {
                val process = ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start()
                synchronized(this) { readerProcess = process }
                BufferedReader(InputStreamReader(process.inputStream), 8 * 1024).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank() || line.startsWith("--------- beginning of")) continue
                        readerWorking = true
                        append(line)
                    }
                }
                readerError = "logcat exited (${runCatching { process.waitFor() }.getOrNull()})"
            } catch (error: Throwable) {
                readerError = "${error.javaClass.simpleName}: ${error.message}"
            }
            readerWorking = false
            appendDirect(TAG, "logcat reader stopped: $readerError; retry $attempt/5 (pid=$pid)")
            try { Thread.sleep(3_000L * attempt) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * Logs through android.util.Log; the reader picks it up. When logcat is unreadable on this
     * ROM, the line is stored directly so app events (boot hotspot, focus) still show.
     */
    fun note(tag: String, message: String, warn: Boolean = false) {
        if (warn) Log.w(tag, message) else Log.i(tag, message)
        if (!readerWorking) appendDirect(tag, message, if (warn) 'W' else 'I')
    }

    private fun appendDirect(tag: String, message: String, level: Char = 'I') {
        val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        append("$time $level/$tag(${Process.myPid()}): $message")
    }

    private fun append(line: String) {
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
            version++
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { ArrayList(lines) }

    fun clear() {
        synchronized(lines) { lines.clear(); version++ }
    }

    /**
     * Writes the whole buffer. Tries /sdcard/DiPlay first (copyable by USB stick / file
     * manager), then the app's external files dir, then private storage.
     */
    fun save(context: Context): File {
        val name = "diplay-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        val text = buildString {
            appendLine("DiPlay log ${Date()} lines=${snapshot().size} readerWorking=$readerWorking readerError=${readerError ?: "none"}")
            snapshot().forEach { appendLine(it) }
        }
        val candidates = listOfNotNull(
            runCatching { File(Environment.getExternalStorageDirectory(), "DiPlay") }.getOrNull(),
            runCatching { context.getExternalFilesDir(null)?.let { File(it, "logs") } }.getOrNull(),
            File(context.filesDir, "logs"),
        )
        var lastError: Throwable? = null
        for (dir in candidates) {
            try {
                if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("cannot create $dir")
                val file = File(dir, name)
                file.writeText(text)
                note(TAG, "log saved to ${file.absolutePath}")
                return file
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw java.io.IOException("could not save log", lastError)
    }
}
