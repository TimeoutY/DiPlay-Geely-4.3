package com.shilapi.xcertplay.logs

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.host.R

/** Car-screen log viewer: large monospace text, newest at the bottom, audio-only filter. */
class DiPlayLogActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var summaryView: TextView
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var filterButton: Button
    private var audioOnly = false
    private var shownVersion = -1L
    private var shownFilter: Boolean? = null
    private var savedPath: String? = null
    private val refresher = object : Runnable {
        override fun run() {
            refresh(force = false)
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiPlayLog.start(this)
        audioOnly = savedInstanceState?.getBoolean("audioOnly") ?: false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply {
            text = getString(R.string.log_title)
            setTextColor(TEXT); textSize = 24f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        filterButton = barButton("") { audioOnly = !audioOnly; updateFilterButton(); refresh(force = true) }
        updateFilterButton()
        bar.addView(filterButton, barParams(170))
        bar.addView(barButton(getString(R.string.log_refresh)) { refresh(force = true, scrollToEnd = true) }, barParams(110))
        bar.addView(barButton(getString(R.string.log_clear)) {
            DiPlayLog.clear(); savedPath = null; refresh(force = true)
        }, barParams(110))
        bar.addView(barButton(getString(R.string.log_save)) { save() }, barParams(150))
        bar.addView(barButton(getString(R.string.back)) { finish() }, barParams(110))
        root.addView(bar)
        summaryView = TextView(this).apply {
            setTextColor(ACCENT); textSize = 16f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(12).toFloat(); setStroke(dp(1), BORDER) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(summaryView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        statusView = TextView(this).apply { setTextColor(MUTED); textSize = 14f; setPadding(dp(4), dp(6), dp(4), dp(6)) }
        root.addView(statusView)
        scroll = ScrollView(this).apply {
            background = GradientDrawable().apply { setColor(Color.rgb(6, 9, 15)); cornerRadius = dp(12).toFloat() }
            isVerticalScrollBarEnabled = true
        }
        logView = TextView(this).apply {
            setTextColor(TEXT); textSize = 15f; typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(0f, 1.1f)
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("audioOnly", audioOnly)
    }

    override fun onResume() {
        super.onResume()
        refresh(force = true, scrollToEnd = true)
        handler.removeCallbacks(refresher)
        handler.postDelayed(refresher, REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun updateFilterButton() {
        filterButton.text = getString(if (audioOnly) R.string.log_filter_audio_on else R.string.log_filter_audio_off)
        filterButton.background = rounded(if (audioOnly) ACCENT else SURFACE)
        filterButton.setTextColor(if (audioOnly) BG else TEXT)
    }

    private fun refresh(force: Boolean, scrollToEnd: Boolean = false) {
        val version = DiPlayLog.version
        if (!force && version == shownVersion && shownFilter == audioOnly) return
        val all = DiPlayLog.snapshot()
        val filtered = if (audioOnly) all.filter(::isAudioLine) else all
        val visible = filtered.takeLast(MAX_VISIBLE_LINES)
        // Follow the tail only when the driver has not scrolled up to read.
        val child = scroll.getChildAt(0)
        val atBottom = child == null || scroll.scrollY + scroll.height >= child.height - dp(48)
        logView.text = if (visible.isEmpty()) getString(R.string.log_empty) else visible.joinToString("\n") { compact(it) }
        summaryView.text = summaryText(AudioLogSummary.from(all))
        statusView.text = getString(
            R.string.log_status,
            visible.size, filtered.size, all.size,
            if (DiPlayLog.readerWorking) getString(R.string.log_reader_ok)
            else getString(R.string.log_reader_failed, DiPlayLog.readerError ?: "?"),
        ) + (savedPath?.let { "  ·  " + getString(R.string.log_saved_to, it) } ?: "")
        shownVersion = version
        shownFilter = audioOnly
        if (atBottom || scrollToEnd) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** "10-21 16:05:01.123 I/Tag( 1234): msg" -> "16:05:01.123 I/Tag: msg" for a narrower car screen. */
    private fun compact(line: String): String {
        val match = LINE.matchEntire(line) ?: return line
        val (time, level, tag, message) = match.destructured
        return "$time $level/${tag.trim()}: $message"
    }

    private fun summaryText(s: AudioLogSummary): String {
        val unknown = getString(R.string.log_unknown)
        val trend = when (s.pcmFlowing) {
            true -> getString(R.string.log_pcm_flowing, s.writtenFrames ?: 0, s.headProgress ?: 0)
            false -> getString(R.string.log_pcm_stalled, s.writtenFrames ?: 0, s.headProgress ?: 0)
            null -> getString(R.string.log_pcm_none)
        }
        return listOf(
            getString(R.string.log_sum_focus, s.focusGranted ?: unknown, s.lastFocusChange ?: unknown),
            getString(R.string.log_sum_play, s.playState ?: unknown, s.volume ?: unknown, s.muted ?: unknown, s.audioMode ?: unknown),
            trend,
            getString(R.string.log_sum_iphone, s.iphonePlaying ?: unknown, s.a2dp ?: unknown),
        ).joinToString("\n")
    }

    private fun save() {
        Thread({
            val result = runCatching { DiPlayLog.save(applicationContext) }
            runOnUiThread {
                result.onSuccess { file ->
                    savedPath = file.absolutePath
                    Toast.makeText(this, getString(R.string.log_saved_to, file.absolutePath), Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(this, getString(R.string.log_save_failed, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
                refresh(force = true)
            }
        }, "diplay-log-save").start()
    }

    private fun barButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 17f; setTextColor(TEXT)
        background = rounded(SURFACE)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { click() }
    }

    private fun barParams(widthDp: Int) = LinearLayout.LayoutParams(dp(widthDp), dp(56)).apply { leftMargin = dp(8) }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat(); setStroke(dp(1), BORDER) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REFRESH_MS = 2_000L
        private const val MAX_VISIBLE_LINES = 400
        private val LINE = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d\.\d+) ([VDIWEF])/([^(]+)\(\s*\d+\): ?(.*)$""")
        private val AUDIO_TAGS = listOf(
            "DiPlay-AudioFocus", "DiPlay-AudioStats", "DiPlay-MediaKeys", "DiPlay-BtAudio",
            "xcertplay-usb", "AudioService", "AudioFlinger", "AudioTrack", "AudioPolicy", "DiPlay-H52-Audio",
        )

        fun isAudioLine(line: String): Boolean = AUDIO_TAGS.any { tag -> line.contains("/$tag(") || line.contains("/$tag ") }

        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
    }
}
