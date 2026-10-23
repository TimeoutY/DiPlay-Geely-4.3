package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.RemoteControlClient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.systemService
import com.shilapi.xcertplay.logs.DiPlayLog
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Hardware media keys routed to the iPhone or the on-screen CarPlay video player. */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )

    private var artworkOwner: Any? = null
    private var controller: CarPlayController? = null
    /** API 21+ implementation is isolated in ModernSession so this object loads on API 18. */
    private var session: ModernSession? = null
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()

    /**
     * API 18/19: hardware keys use registerMediaButtonEventReceiver + RemoteControlClient, and
     * STREAM_MUSIC focus (AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE by default, or AUDIOFOCUS_GAIN when
     * 「独占声音焦点」is off) is what makes Geely GKUI (帝豪GL, 4.4.2) switch its audio source to
     * this app. Without it the AudioTrack plays into a muted/unrouted source: no sound.
     * Exclusive/gain makes GKUI's Bluetooth-music app (AVRCP-connected to the same iPhone) lose
     * focus, and it may pause the iPhone over AVRCP (issue #1); see [maybeUndoFocusPause].
     */
    private val legacyFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            loopGuard.onFocusChange()
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                loopGuard.onFocusLoss()
                legacyFocusHeld = false
                // Someone else (radio, BT music, another app) took the source on purpose; never undo a pause.
                autoResumeArmed = false
            }
            DiPlayLog.note(FOCUS_TAG, "legacy focus change=${focusChangeName(change)} held=$legacyFocusHeld " +
                "sessionActive=$sessionActive mediaActive=$mediaAudioActive iPhonePlaying=$iphonePlaying" +
                if (change == AudioManager.AUDIOFOCUS_LOSS && loopGuard.contention()) {
                    " CONTENTION: another app took MUSIC focus ${PauseLoopGuard.CONTENTION_LOSSES}+ times in " +
                        "${PauseLoopGuard.CONTENTION_WINDOW_MS / 1000}s (likely the car's Bluetooth-music app still " +
                        "connected to the iPhone); re-requests slowed, auto-resume disabled"
                } else "",
                warn = change == AudioManager.AUDIOFOCUS_LOSS)
        }
    }
    private val loopGuard = PauseLoopGuard { SystemClock.elapsedRealtime() }
    /** Last play state reported by the iPhone (null = unknown). */
    private var iphonePlaying: Boolean? = null
    /** Focus was granted at least once in this session; later requests are re-requests after a loss. */
    private var focusGrantedThisSession = false
    private var legacyFocusHeld = false
    /** True between AirPlay session active and session end (see CarPlayController.airPlaySessionListener). */
    private var sessionActive = false
    private var legacyFocusGrantedAt = 0L
    private var autoResumeArmed = false
    /** Last play/pause key we actually forwarded to the iPhone (a driver action). */
    private var lastHardwareKeyAt = 0L
    private var mediaAudioStartedAt = 0L
    private var legacyReceiver: BroadcastReceiver? = null
    private var legacyPendingIntent: PendingIntent? = null
    private var legacyRemoteControlClient: RemoteControlClient? = null

    /**
     * Live values for the log-page summary. Prefer this over scraping log lines: on a busy unit
     * the ring buffer may have rotated the grant/play lines away, leaving `granted=?`.
     */
    data class StatusSnapshot(
        val focusHeld: Boolean,
        val sessionActive: Boolean,
        val iphonePlaying: Boolean?,
        val mediaAudioActive: Boolean,
        val fightMode: Boolean,
        val btMusic: String,
    )

    @Synchronized
    fun statusSnapshot(): StatusSnapshot = StatusSnapshot(
        focusHeld = legacyFocusHeld,
        sessionActive = sessionActive,
        iphonePlaying = iphonePlaying,
        mediaAudioActive = mediaAudioActive,
        fightMode = PauseLoopGuard.fightMode,
        btMusic = BluetoothMusicGuard.lastStatus,
    )

    /**
     * Hardwired / window-focused media keys (steering wheel when the car delivers KeyEvents to the
     * focused Activity instead of — or in addition to — Bluetooth AVRCP). Same PauseLoopGuard as
     * the legacy MEDIA_BUTTON receiver. Returns true when the key was a media key we handle.
     */
    fun onHardwiredKey(event: KeyEvent): Boolean {
        val toggleIndex = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        handleMediaKeyEvent(event.keyCode, toggleIndex, "hardwiredKey")
        return true
    }

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        next.playbackListener = { playing -> onIphonePlaying(next, playing) }
        next.airPlaySessionListener = { active, wireless -> onAirPlaySession(next, active, wireless) }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.airPlaySessionListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        releaseLocked()
    }

    /**
     * Session-level focus (setting 「连接时预先占用音频焦点」, default on): take STREAM_MUSIC focus
     * once when the AirPlay session connects, before the iPhone sends any audio, and hold it for
     * the whole session. The car's Bluetooth-music app then loses focus (and may AVRCP-pause the
     * iPhone) at connect time, when nothing is playing through CarPlay yet, instead of 1-2 s
     * into every song.
     */
    private fun onAirPlaySession(expected: CarPlayController, active: Boolean, wireless: Boolean) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                val context = appContext ?: return@synchronized
                sessionActive = active
                focusGrantedThisSession = false
                DiPlayLog.note(FOCUS_TAG, "AirPlay session ${if (active) "active" else "ended"} wireless=$wireless " +
                    "sessionFocus=${AirPlayPersistence.loadSessionAudioFocus(context)} " +
                    "antiAutoPause=${AirPlayPersistence.loadAntiAutoPause(context)} " +
                    "exclusiveFocus=${AirPlayPersistence.loadExclusiveAudioFocus(context)}")
                if (active) {
                    PauseLoopGuard.clearFightForNewSession()
                    // Publish preferred MEDIA gain before focus/quiet so AndroidMediaSink and logs agree.
                    com.shilapi.xcertplay.media.AudioSessionFocus.preferredMediaGain = sessionMediaGain(context)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP &&
                        AirPlayPersistence.loadSessionAudioFocus(context)) {
                        requestLegacyFocusLocked(context, "session-connect")
                    }
                    if (wireless) BluetoothMusicGuard.onWirelessSessionActive(context)
                    else StockBtMusicQuiet.quiet(context, "session-connect(wired)")
                } else {
                    BluetoothMusicGuard.onSessionEnded()
                    abandonLegacyFocusLocked("session-end")
                }
            }
        }
    }

    /** Called when CarPlay audio starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        DiPlayLog.note(FOCUS_TAG, "iPhone playing=$playing")
        synchronized(this) { if (controller === expected) iphonePlaying = playing }
        if (!playing) {
            mainHandler.post { synchronized(this) { if (controller === expected) maybeUndoFocusPauseLocked(expected) } }
            return
        }
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                regainFocusLocked()
                publishPlaybackStateLocked()
            }
        }
    }

    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = update.artworkTransferId?.let { id ->
                        if (artworkCache.containsKey(id)) artworkCache[id] else null
                    }
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                nowPlaying = update
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) session?.setMetadata(update, artwork)
                publishPlaybackStateLocked()
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) session?.setMetadata(nowPlaying, artwork)
        }
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        if (active && !mediaAudioActive) {
            mediaAudioStartedAt = SystemClock.elapsedRealtime()
            loopGuard.onMediaStart()
            DiPlayLog.note(FOCUS_TAG, "CarPlay media audio started focusHeld=$legacyFocusHeld")
        }
        mediaAudioActive = active
        if (active) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (session == null) session = ModernSession(context, mainHandler, ::send)
                session?.setMetadata(nowPlaying, artwork)
            } else {
                registerLegacyMediaButtons(context)
            }
            regainFocusLocked()
        }
        publishPlaybackStateLocked()
    }

    private fun handleMediaKeyEvent(keyCode: Int, toggleIndex: Int, source: String) {
        val kind = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> PauseLoopGuard.Key.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> PauseLoopGuard.Key.PAUSE
            else -> if (toggleIndex == CarPlayMediaButton.PLAY_PAUSE) PauseLoopGuard.Key.TOGGLE else null
        }
        val keyName = KeyEvent.keyCodeToString(keyCode)
        if (kind == null) {
            // NEXT / PREVIOUS: always forward (steering-wheel song switch). Not part of the pause loop.
            synchronized(this) { lastHardwareKeyAt = SystemClock.elapsedRealtime() }
            send(toggleIndex, source)
            DiPlayLog.note(TAG, "key $keyName -> SEND index=$toggleIndex ($source)")
            return
        }
        val (decision, playing) = synchronized(this) {
            val ctx = appContext
            val guard = ctx != null && AirPlayPersistence.loadAntiAutoPause(ctx)
            loopGuard.keyDecision(kind, iphonePlaying, guard).also {
                if (it.send) lastHardwareKeyAt = SystemClock.elapsedRealtime()
            } to iphonePlaying
        }
        // Explicit PLAY/PAUSE stay explicit: a car-generated PAUSE while the iPhone is already
        // paused must not become a toggle that starts it again (qa1 pause/play loop).
        val index = when (kind) {
            PauseLoopGuard.Key.PLAY -> CarPlayMediaButton.PLAY
            PauseLoopGuard.Key.PAUSE -> CarPlayMediaButton.PAUSE
            PauseLoopGuard.Key.TOGGLE -> CarPlayMediaButton.PLAY_PAUSE
        }
        DiPlayLog.note(TAG, "key $keyName iPhonePlaying=$playing -> " +
            (if (decision.send) "SEND index=$index" else "IGNORED") + " (${decision.reason}) [$source]",
            warn = !decision.send)
        if (decision.send) send(index, source)
    }

    private fun registerLegacyMediaButtons(context: Context) {
        if (legacyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
                @Suppress("DEPRECATION")
                val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
                val toggleIndex = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    handleMediaKeyEvent(event.keyCode, toggleIndex, "legacyKeyEvent")
                }
            }
        }
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_MEDIA_BUTTON))
        legacyReceiver = receiver

        @Suppress("DEPRECATION")
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
        legacyPendingIntent = pending
        val audio = context.systemService(AudioManager::class.java, "audio")
        if (audio != null) {
            @Suppress("DEPRECATION")
            audio.registerMediaButtonEventReceiver(pending)
            val client = RemoteControlClient(pending)
            @Suppress("DEPRECATION")
            audio.registerRemoteControlClient(client)
            legacyRemoteControlClient = client
        }
        Log.i(TAG, "legacy media button receiver registered with RCC")
    }

    private fun unregisterLegacyMediaButtons() {
        val audio = appContext?.systemService(AudioManager::class.java, "audio")
        legacyRemoteControlClient?.let { client ->
            @Suppress("DEPRECATION")
            runCatching { audio?.unregisterRemoteControlClient(client) }
        }
        legacyRemoteControlClient = null
        legacyPendingIntent?.let { pending ->
            @Suppress("DEPRECATION")
            runCatching { audio?.unregisterMediaButtonEventReceiver(pending) }
        }
        legacyPendingIntent = null
        legacyReceiver?.let { receiver -> runCatching { appContext?.unregisterReceiver(receiver) } }
        legacyReceiver = null
    }

    private fun regainFocusLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            session?.regainFocus()
            return
        }
        val context = appContext ?: return
        registerLegacyMediaButtons(context)
        if (!legacyFocusHeld && focusGrantedThisSession) {
            // Re-request after another app took focus: never a tight request/loss ping-pong with the
            // car's Bluetooth-music app (each of our grants makes it AVRCP-pause the iPhone).
            val decision = loopGuard.mayReRequestFocus()
            DiPlayLog.note(FOCUS_TAG, "focus re-request after loss: ${if (decision.send) "YES" else "NO"} (${decision.reason})",
                warn = !decision.send)
            if (!decision.send) return
        }
        // Session mode: focus was taken at connect; only a real permanent loss (or a failed
        // request) leaves it unheld, and then the next CarPlay media start must take it back,
        // otherwise GKUI keeps the previous source and CarPlay is silent.
        requestLegacyFocusLocked(context, if (AirPlayPersistence.loadSessionAudioFocus(context)) "media-start(after-loss)" else "media-start")
    }

    private fun requestLegacyFocusLocked(context: Context, reason: String) {
        // H52 track coordinator owns vendor focus; a second MUSIC focus mutes that route (upstream P21).
        // Only when the routing is really active: a stale toggle on a unit without the H52 streams
        // must not leave CarPlay without any focus (GKUI then routes nothing = silence).
        if (AirPlayPersistence.loadGeelyAudioRouting(context)) {
            if (com.shilapi.xcertplay.media.GeelyAudioCapabilities.detect() != null) {
                DiPlayLog.note(FOCUS_TAG, "legacy focus skipped ($reason): H52 Geely audio routing owns focus")
                return
            }
            DiPlayLog.note(FOCUS_TAG, "H52 Geely audio routing is ON but this unit has no H52 audio streams; " +
                "requesting normal focus ($reason)", warn = true)
        }
        com.shilapi.xcertplay.media.AudioSessionFocus.sessionOwnsMusicFocus = true
        if (legacyFocusHeld) return
        val audio = context.systemService(AudioManager::class.java, "audio") ?: run {
            DiPlayLog.note(FOCUS_TAG, "legacy focus ($reason): AudioManager unavailable", warn = true)
            return
        }
        val gain = sessionMediaGain(context)
        com.shilapi.xcertplay.media.AudioSessionFocus.preferredMediaGain = gain
        @Suppress("DEPRECATION")
        val result = runCatching {
            audio.requestAudioFocus(legacyFocusListener, AudioManager.STREAM_MUSIC, gain)
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        legacyFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (legacyFocusHeld) {
            val now = SystemClock.elapsedRealtime()
            legacyFocusGrantedAt = now
            loopGuard.onFocusChange()
            val reGrant = focusGrantedThisSession
            focusGrantedThisSession = true
            autoResumeArmed = AirPlayPersistence.loadAntiAutoPause(context) &&
                PauseLoopGuard.autoResumeAllowed(now) && !loopGuard.contention()
            DiPlayLog.note(FOCUS_TAG, "auto-resume ${if (autoResumeArmed) "armed" else "NOT armed"} for this grant " +
                "(reGrant=$reGrant antiAutoPause=${AirPlayPersistence.loadAntiAutoPause(context)} " +
                "lastResume=${if (PauseLoopGuard.autoResumeAllowed(now)) ">=5min ago" else "<5min ago"} " +
                "contention=${loopGuard.contention()} fight=${PauseLoopGuard.fightMode})")
            // Stock BT-music loses Android focus when we gain it and may AVRCP-pause ~1–2 s later.
            // Softly quiet it so the pause never fires; keep BT/AVRCP up for the steering wheel.
            StockBtMusicQuiet.quiet(context, "focus-granted:$reason")
            // Re-assert shortly after: on 4.4 the same listener returns GRANTED without a LOSS
            // to ourselves, and climbs back on top if the stock app briefly contested.
            mainHandler.postDelayed({ synchronized(this@CarPlayMediaKeys) { reassertFocusLocked(context) } }, 600L)
        }
        DiPlayLog.note(FOCUS_TAG, "legacy focus request reason=$reason stream=MUSIC gain=${gainName(gain)} " +
            "granted=$legacyFocusHeld " +
            "musicVolume=${runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()}/" +
            "${runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()} " +
            "mode=${runCatching { audio.mode }.getOrNull()} musicActive=${runCatching { audio.isMusicActive }.getOrNull()}")
    }

    private fun reassertFocusLocked(context: Context) {
        if (!legacyFocusHeld || !sessionActive) return
        val audio = context.systemService(AudioManager::class.java, "audio") ?: return
        val gain = sessionMediaGain(context)
        com.shilapi.xcertplay.media.AudioSessionFocus.preferredMediaGain = gain
        @Suppress("DEPRECATION")
        val result = runCatching {
            audio.requestAudioFocus(legacyFocusListener, AudioManager.STREAM_MUSIC, gain)
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        StockBtMusicQuiet.quiet(context, "re-assert", force = true)
        DiPlayLog.note(FOCUS_TAG, "legacy focus re-assert after grant: gain=${gainName(gain)} " +
            "granted=${result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED}")
    }

    /**
     * MEDIA session gain for API 19 / 4.4.2: AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE when the setting
     * is on (default), else plain AUDIOFOCUS_GAIN. Siri/phone keep using transient in AndroidMediaSink.
     */
    private fun sessionMediaGain(context: Context): Int {
        if (!AirPlayPersistence.loadExclusiveAudioFocus(context)) return AudioManager.AUDIOFOCUS_GAIN
        // AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE = 4, added in API 19 (KitKat).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        } else {
            AudioManager.AUDIOFOCUS_GAIN
        }
    }

    private fun gainName(gain: Int): String = when (gain) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "GAIN_TRANSIENT"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "GAIN_TRANSIENT_MAY_DUCK"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "GAIN_TRANSIENT_EXCLUSIVE"
        else -> gain.toString()
    }

    private fun abandonLegacyFocusLocked(reason: String) {
        if (legacyFocusHeld) {
            @Suppress("DEPRECATION")
            runCatching { appContext?.systemService(AudioManager::class.java, "audio")?.abandonAudioFocus(legacyFocusListener) }
            DiPlayLog.note(FOCUS_TAG, "legacy focus abandoned ($reason)")
        }
        legacyFocusHeld = false
        autoResumeArmed = false
        com.shilapi.xcertplay.media.AudioSessionFocus.sessionOwnsMusicFocus = false
    }

    /**
     * Issue #1: right after we take focus, the head unit's Bluetooth-music app may pause the
     * iPhone over AVRCP (or echo a pause key). That pause never passes through DiPlay, so undo it
     * once: if the iPhone stops within [AUTO_RESUME_WINDOW_MS] of our focus grant, without a
     * hardware key from the driver and without anyone taking focus from us, send PLAY once.
     */
    private fun maybeUndoFocusPauseLocked(expected: CarPlayController) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP || !autoResumeArmed || !legacyFocusHeld) return
        val now = SystemClock.elapsedRealtime()
        val sinceGrant = now - legacyFocusGrantedAt
        if (sinceGrant > AUTO_RESUME_WINDOW_MS) {
            autoResumeArmed = false
            return
        }
        if (now - lastHardwareKeyAt < AUTO_RESUME_WINDOW_MS) {
            DiPlayLog.note(FOCUS_TAG, "iPhone paused ${sinceGrant}ms after focus grant by a hardware key; leaving it paused")
            autoResumeArmed = false
            return
        }
        autoResumeArmed = false
        if (PauseLoopGuard.notePostResumePause(now)) {
            DiPlayLog.note(FOCUS_TAG, "iPhone paused ${sinceGrant}ms after focus grant AGAIN within " +
                "${PauseLoopGuard.FIGHT_WINDOW_MS / 1000}s of auto-resume; entering fight mode " +
                "(stop auto-resume, quiet stock BT music)", warn = true)
            appContext?.let { StockBtMusicQuiet.quiet(it, "fight-mode", force = true) }
            return
        }
        if (!PauseLoopGuard.autoResumeAllowed(now) || loopGuard.contention()) {
            DiPlayLog.note(FOCUS_TAG, "iPhone paused ${sinceGrant}ms after focus grant; NOT resuming " +
                "(auto-resume at most once per 5 min, never under focus contention/fight)", warn = true)
            appContext?.let { StockBtMusicQuiet.quiet(it, "no-resume") }
            return
        }
        PauseLoopGuard.noteAutoResumeSent(now)
        DiPlayLog.note(FOCUS_TAG, "iPhone paused ${sinceGrant}ms after focus grant with no key press " +
            "(head-unit AVRCP pause on focus loss); resuming once", warn = true)
        appContext?.let { StockBtMusicQuiet.quiet(it, "pre-auto-resume") }
        mainHandler.postDelayed({
            val (stillCurrent, playingNow) = synchronized(this) {
                (controller === expected && legacyFocusHeld && lastHardwareKeyAt <= now) to iphonePlaying
            }
            if (stillCurrent && playingNow != true) {
                val sent = expected.sendMediaButton(CarPlayMediaButton.PLAY)
                DiPlayLog.note(FOCUS_TAG, "auto-resume PLAY sent=$sent")
            } else {
                DiPlayLog.note(FOCUS_TAG, "auto-resume cancelled (focus lost, key pressed, or already playing)")
            }
        }, AUTO_RESUME_DELAY_MS)
    }

    private fun focusChangeName(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> change.toString()
    }

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            session?.setPlaybackState(playing, nowPlaying.elapsedMillis, elapsedUpdatedAt)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            legacyRemoteControlClient?.setPlaybackState(
                if (playing) RemoteControlClient.PLAYSTATE_PLAYING else RemoteControlClient.PLAYSTATE_PAUSED,
            )
        }
    }

    private fun releaseLocked() {
        artworkOwner = null
        artworkQueue.clear()
        unregisterLegacyMediaButtons()
        abandonLegacyFocusLocked("controller-released")
        sessionActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) session?.close()
        session = null
        mediaAudioActive = false
        mediaAudioStartedAt = 0L
        iphonePlaying = null
        focusGrantedThisSession = false
        nowPlaying = CarPlayNowPlaying()
        elapsedUpdatedAt = 0L
        artwork = null
        artworkCache.clear()
    }

    private fun send(index: Int, source: String) {
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= MAX_ARTWORK_DIMENSION) return decoded
        val scale = MAX_ARTWORK_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { scaled -> if (scaled !== decoded) decoded.recycle() }
    }

    private const val FOCUS_TAG = "DiPlay-AudioFocus"
    private const val AUTO_RESUME_WINDOW_MS = 6_000L
    private const val AUTO_RESUME_DELAY_MS = 400L
    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}
