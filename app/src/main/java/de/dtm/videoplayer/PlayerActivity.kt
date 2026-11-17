@file:OptIn(UnstableApi::class)

package de.dtm.videoplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.media.audiofx.Equalizer
import android.os.PowerManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.CaptionStyleCompat
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.LanczosResample
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var audioManager: AudioManager
    private val balance = BalanceAudioProcessor()
    private var mediaSession: MediaSession? = null
    private var loudness: LoudnessEnhancer? = null
    private var boostMb = 0
    private var eq: Equalizer? = null
    private var eqLevels: ShortArray? = null
    private var batterySaver = false
    private var preSaverBrightness = -1f
    private val resetSeek = Runnable { player.setSeekParameters(SeekParameters.DEFAULT) }

    private val subPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSubtitle(uri)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!batterySaver) {
                setBatterySaver(true)
                showHud("Akku niedrig: Schonmodus an")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("resume", MODE_PRIVATE) }

    // UI
    private lateinit var root: FrameLayout
    private lateinit var controls: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var hud: TextView
    private lateinit var btBadge: TextView
    private lateinit var speedBtn: TextView
    private var volSeek: SeekBar? = null

    // Zustand
    private var controlsVisible = true
    private var scrubbing = false
    private var resizeIdx = 0
    private var landscapeLock = false
    private var enhance = 0
    private var speedIdx = 2
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val resizeNames = arrayOf("Anpassen", "Zoom", "Strecken")

    // Gesten
    private var gestureActive = false
    private var gestureVolStart = 0
    private var gestureBrightStart = 0.5f
    private var lastSaveMs = 0L

    private val hideControls = Runnable { setControls(false) }
    private val hideHud = Runnable { hud.visibility = View.GONE }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            val now = System.currentTimeMillis()
            if (now - lastSaveMs > 5000) {
                savePosition()
                lastSaveMs = now
            }
            handler.postDelayed(this, 500)
        }
    }

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            volSeek?.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshBt()
            addedDevices.firstOrNull { AudioOutputs.isBt(it) }?.let { showHud("Verbunden: ${it.productName}") }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.id == AudioOutputs.preferredId }) {
                AudioOutputs.preferredId = -1
                player.setPreferredAudioDevice(null)
            }
            refreshBt()
        }
    }

    // ---------------------------------------------------------------- Lebenszyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bildschirm bleibt an – auch beim Drehen des Handys
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        buildPlayer()
        buildUi()
        loadMedia()

        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        refreshBt()
        hideSystemBars()

        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_LOW), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isPowerSaveMode || getSharedPreferences("settings", MODE_PRIVATE).getBoolean("saver", false)) {
            setBatterySaver(true)
        }
        handler.post(ticker)
        scheduleHide()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Activity wird beim Drehen NICHT neu erstellt -> Wiedergabe läuft weiter
        hideSystemBars()
        if (enhance > 0) applyEnhance()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(volumeObserver)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Throwable) {
        }
        try {
            loudness?.release()
            eq?.release()
        } catch (_: Throwable) {
        }
        mediaSession?.release()
        player.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Player

    private fun buildPlayer() {
        player = ExoPlayer.Builder(this, CinemaRenderersFactory(this, balance))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                if (isPlaying) scheduleHide() else handler.removeCallbacks(hideControls)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                titleView.text = mediaItem?.mediaMetadata?.title ?: ""
                player.playbackParameters = player.playbackParameters.withSpeed(speeds[speedIdx])
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    setControls(true)
                    handler.removeCallbacks(hideControls)
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupLoudness(audioSessionId)
                setupEq(audioSessionId)
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Wiedergabe-Fehler: ${error.errorCodeName}",
                    Toast.LENGTH_LONG
                ).show()
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    setControls(true)
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
    }

    private fun loadMedia() {
        val uris = ArrayList<Uri>()
        intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)?.let { uris.addAll(it) }
        intent.data?.let { if (uris.isEmpty()) uris.add(it) }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nichts zum Abspielen", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val items = uris.map {
            MediaItem.Builder()
                .setUri(it)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(displayName(it)).build())
                .build()
        }
        val resume = prefs.getLong(uris[0].toString(), 0L)
        player.setMediaItems(items, 0, if (resume > 5_000) resume else C.TIME_UNSET)
        titleView.text = items[0].mediaMetadata.title ?: ""
        player.prepare()
        player.playWhenReady = true
    }

    private fun displayName(u: Uri): String {
        if (u.scheme == "content") {
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) return it.getString(0)
                }
            } catch (_: Exception) {
            }
        }
        return u.lastPathSegment ?: "Video"
    }

    private fun savePosition() {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur != C.TIME_UNSET && pos > 5_000 && pos < dur - 10_000) {
            prefs.edit().putLong(uri.toString(), pos).apply()
        } else {
            prefs.edit().remove(uri.toString()).apply()
        }
    }

    // ---------------------------------------------------------------- Audio-Extras

    private fun setupLoudness(sessionId: Int) {
        try {
            loudness?.release()
            loudness = null
            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                loudness = LoudnessEnhancer(sessionId).also {
                    it.setTargetGain(boostMb)
                    it.setEnabled(boostMb > 0)
                }
            }
        } catch (_: Throwable) {
            loudness = null
        }
    }

    private fun setBoostDb(db: Int) {
        boostMb = db * 100
        if (loudness == null) setupLoudness(player.audioSessionId)
        try {
            loudness?.setTargetGain(boostMb)
            loudness?.setEnabled(boostMb > 0)
        } catch (_: Throwable) {
        }
    }

    private fun setupEq(sessionId: Int) {
        try {
            eq?.release()
            eq = null
            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                eq = Equalizer(0, sessionId).also { e ->
                    eqLevels?.forEachIndexed { b, l ->
                        if (b < e.numberOfBands.toInt()) e.setBandLevel(b.toShort(), l)
                    }
                    e.setEnabled(eqLevels != null)
                }
            }
        } catch (_: Throwable) {
            eq = null
        }
    }

    private fun setEq(levels: ShortArray?) {
        eqLevels = levels
        val e = eq ?: return
        try {
            levels?.forEachIndexed { b, l ->
                if (b < e.numberOfBands.toInt()) e.setBandLevel(b.toShort(), l)
            }
            e.setEnabled(levels != null)
        } catch (_: Throwable) {
        }
    }

    /** 0 = aus, 1 = Bass, 2 = Stimme, 3 = Höhen */
    private fun eqPreset(kind: Int) {
        val e = eq ?: return
        if (kind == 0) {
            setEq(null)
            return
        }
        val n = e.numberOfBands.toInt()
        val range = e.bandLevelRange
        val lo = range[0].toInt()
        val hi = range[1].toInt()
        val levels = ShortArray(n) { i ->
            val f = if (n > 1) i / (n - 1f) else 0f
            val v = when (kind) {
                1 -> (1f - f) * hi * 0.7f
                2 -> (1f - abs(f - 0.55f) * 2f).coerceAtLeast(0f) * hi * 0.6f
                else -> f * hi * 0.7f
            }
            v.toInt().coerceIn(lo, hi).toShort()
        }
        setEq(levels)
    }

    private fun stepFrame(dir: Int) {
        player.pause()
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 25f
        val step = (1000f / fps).toLong().coerceAtLeast(1L)
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo((player.currentPosition + dir * step).coerceAtLeast(0L))
        showHud(if (dir > 0) "+1 Bild" else "-1 Bild")
        handler.removeCallbacks(resetSeek)
        handler.postDelayed(resetSeek, 2500)
    }

    private fun loadSubtitle(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val name = displayName(uri).lowercase()
        val mime: String? = when {
            name.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml") || name.endsWith(".xml") || name.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> null
        }
        if (mime == null) {
            Toast.makeText(this, "Unbekanntes Format (srt, vtt, ass, ssa, ttml)", Toast.LENGTH_LONG).show()
            return
        }
        val cur = player.currentMediaItem ?: return
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition
        val playing = player.playWhenReady
        val cfg = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("de")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        player.replaceMediaItem(idx, cur.buildUpon().setSubtitleConfigurations(listOf(cfg)).build())
        player.seekTo(idx, pos)
        player.playWhenReady = playing
        showHud("Untertitel geladen")
    }

    private fun setSubStyle(kind: Int) {
        val sv = playerView.subtitleView ?: return
        val style = when (kind) {
            0 -> CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null)
            1 -> CaptionStyleCompat(Color.YELLOW, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null)
            else -> CaptionStyleCompat(Color.WHITE, 0xAA000000.toInt(), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, null)
        }
        sv.setApplyEmbeddedStyles(false)
        sv.setStyle(style)
    }

    private fun saverLabel(): String =
        if (batterySaver) "Akku-Schonmodus: AN (tippen zum Ausschalten)" else "Akku-Schonmodus: AUS (tippen zum Einschalten)"

    private fun setBatterySaver(on: Boolean) {
        batterySaver = on
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("saver", on).apply()
        if (on) {
            preSaverBrightness = window.attributes.screenBrightness
            val cur = if (preSaverBrightness < 0f) 0.5f else preSaverBrightness
            window.attributes = window.attributes.apply { screenBrightness = min(cur, 0.30f) }
            if (enhance != 0) {
                enhance = 0
                applyEnhance()
            }
        } else {
            window.attributes = window.attributes.apply { screenBrightness = preSaverBrightness }
        }
    }

    private fun refreshBt() {
        val n = AudioOutputs.btDevices(this).size
        btBadge.text = n.toString()
        btBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- Bild-Extras

    /** Upscaling: Lanczos auf Bildschirmgröße (+ optional Kontrast/Farbe). */
    private fun applyEnhance() {
        val effects = ArrayList<Effect>()
        if (enhance >= 1) {
            val dm = resources.displayMetrics
            effects.add(LanczosResample.scaleToFit(max(dm.widthPixels, dm.heightPixels), min(dm.widthPixels, dm.heightPixels)))
        }
        if (enhance >= 2) {
            effects.add(Contrast(0.10f))
            effects.add(HslAdjustment.Builder().adjustSaturation(12f).build())
        }
        try {
            player.setVideoEffects(effects)
        } catch (t: Throwable) {
            Toast.makeText(this, "Upscaling auf diesem Gerät nicht möglich", Toast.LENGTH_SHORT).show()
            enhance = 0
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = resizeModes[resizeIdx]
            setShutterBackgroundColor(Color.BLACK)
            keepScreenOn = true
            player = this@PlayerActivity.player
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        // Gesten-Fläche
        val touch = View(this)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                gestureActive = false
                gestureVolStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val b = window.attributes.screenBrightness
                gestureBrightStart = if (b < 0f) 0.5f else b
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControls(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val x = e.x / root.width
                when {
                    x < 0.33f -> seekBy(-10_000)
                    x > 0.67f -> seekBy(10_000)
                    else -> togglePlay()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (e1 == null) return false
                val totalY = e1.y - e2.y
                val totalX = e2.x - e1.x
                if (!gestureActive && abs(totalY) < abs(totalX)) return false
                if (abs(totalY) < 12f && !gestureActive) return false
                gestureActive = true
                val frac = totalY / root.height * 1.3f
                if (e1.x < root.width / 2f) {
                    val v = (gestureBrightStart + frac).coerceIn(0.02f, 1f)
                    window.attributes = window.attributes.apply { screenBrightness = v }
                    showHud("Helligkeit ${(v * 100).toInt()} %")
                } else {
                    val maxV = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val v = (gestureVolStart + frac * maxV).toInt().coerceIn(0, maxV)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    showHud("Lautstärke ${v * 100 / maxV} %")
                }
                return true
            }
        })
        touch.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            true
        }
        root.addView(touch, FrameLayout.LayoutParams(-1, -1))

        // Steuerung
        controls = FrameLayout(this)
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))

        controls.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
        }, FrameLayout.LayoutParams(-1, dp(110), Gravity.TOP))
        controls.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x00000000)
            )
        }, FrameLayout.LayoutParams(-1, dp(170), Gravity.BOTTOM))

        // oben
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        top.addView(iconBtn(R.drawable.ic_back) { finish() })
        titleView = label("", 16f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(8), 0, dp(8), 0)
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val btWrap = FrameLayout(this)
        btWrap.addView(iconBtn(R.drawable.ic_headphones) {
            AudioOutputs.show(this, player) { refreshBt() }
        })
        btBadge = label("0", 10f, Color.BLACK, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.ACCENT2) }
            visibility = View.GONE
        }
        btWrap.addView(btBadge, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(4), dp(2), 0)
        })
        top.addView(btWrap, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(iconBtn(R.drawable.ic_tune) { showSettings() })
        controls.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Mitte
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        center.addView(iconBtn(R.drawable.ic_prev, 56, 14) { player.seekToPreviousMediaItem() })
        playBtn = iconBtn(R.drawable.ic_pause, 76, 20) { togglePlay() }.apply {
            background = withRipple(
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.ACCENT, Pal.ACCENT2)
                ).apply { shape = GradientDrawable.OVAL }
            )
        }
        center.addView(playBtn, LinearLayout.LayoutParams(dp(76), dp(76)).apply { setMargins(dp(24), 0, dp(24), 0) })
        center.addView(iconBtn(R.drawable.ic_next, 56, 14) { player.seekToNextMediaItem() })
        controls.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // unten
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        posView = label("0:00", 12f, Color.WHITE)
        durView = label("0:00", 12f, Color.WHITE)
        seek = slider(1000, 0) { p, fromUser ->
            if (fromUser) posView.text = fmt(player.duration.coerceAtLeast(0) * p / 1000)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) posView.text = fmt(player.duration.coerceAtLeast(0) * p / 1000)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                scrubbing = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                val d = player.duration
                if (d != C.TIME_UNSET) player.seekTo(d * s.progress / 1000)
                scrubbing = false
                scheduleHide()
            }
        })
        seekRow.addView(posView)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        seekRow.addView(durView)
        bottom.addView(seekRow, matchWrap())

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        speedBtn = label("1.0x", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                speedIdx = (speedIdx + 1) % speeds.size
                setSpeed(speedIdx)
            }
        }
        btns.addView(speedBtn, LinearLayout.LayoutParams(dp(56), dp(44)))
        btns.addView(iconBtn(R.drawable.ic_aspect) {
            resizeIdx = (resizeIdx + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeIdx]
            showHud("Bildformat: ${resizeNames[resizeIdx]}")
        })
        btns.addView(space(), LinearLayout.LayoutParams(0, 1, 1f))
        btns.addView(iconBtn(R.drawable.ic_rew) { seekBy(-10_000) })
        btns.addView(iconBtn(R.drawable.ic_ff) { seekBy(10_000) })
        btns.addView(space(), LinearLayout.LayoutParams(0, 1, 1f))
        btns.addView(iconBtn(R.drawable.ic_cc) { showTracks(C.TRACK_TYPE_TEXT, "Untertitel") })
        btns.addView(iconBtn(R.drawable.ic_fullscreen) {
            landscapeLock = !landscapeLock
            requestedOrientation = if (landscapeLock) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR
            showHud(if (landscapeLock) "Vollbild: Querformat" else "Drehen: automatisch")
        })
        bottom.addView(btns, matchWrap())
        controls.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // HUD
        hud = label("", 16f, Color.WHITE, true).apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = roundRect(0xCC151824.toInt(), dp(20).toFloat())
            visibility = View.GONE
        }
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(48)
        })

        // Aussparungen / Ränder berücksichtigen
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            controls.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        setContentView(root)
    }

    private fun space(): View = View(this)

    private fun setControls(show: Boolean) {
        if (controlsVisible == show) return
        controlsVisible = show
        controls.fadeTo(show)
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (player.isPlaying) handler.postDelayed(hideControls, 3500)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
        } else if (player.isPlaying) player.pause() else player.play()
    }

    private fun seekBy(ms: Long) {
        val d = player.duration
        val target = (player.currentPosition + ms).coerceAtLeast(0)
        player.seekTo(if (d != C.TIME_UNSET) min(target, d) else target)
        showHud(if (ms > 0) "+${ms / 1000} s" else "${ms / 1000} s")
    }

    private fun setSpeed(idx: Int) {
        speedIdx = idx
        player.playbackParameters = player.playbackParameters.withSpeed(speeds[idx])
        speedBtn.text = "${speeds[idx]}x"
    }

    private fun showHud(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 1000)
    }

    private fun updateProgress() {
        if (!controlsVisible || scrubbing) return
        val d = player.duration
        val p = player.currentPosition
        durView.text = if (d == C.TIME_UNSET) "--:--" else fmt(d)
        posView.text = fmt(p)
        if (d != C.TIME_UNSET && d > 0) seek.progress = (p * 1000 / d).toInt()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun showTracks(type: Int, title: String) {
        val has = player.currentTracks.groups.any { it.type == type }
        if (!has) {
            Toast.makeText(this, "Keine Auswahl vorhanden", Toast.LENGTH_SHORT).show()
            return
        }
        TrackSelectionDialogBuilder(this, title, player, type).build().show()
    }

    // ---------------------------------------------------------------- Einstellungen

    private fun showSettings() {
        val dialog = BottomSheetDialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
            background = roundRect(Pal.SURFACE, dp(24).toFloat())
        }

        col.addView(label("Einstellungen", 20f, Pal.TEXT, true))

        // Lautstärke (System = auch Bluetooth-Kopfhörer)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volLabel = label("", 14f, Pal.MUTED)
        fun volText(v: Int) {
            volLabel.text = "Lautstärke (auch am Kopfhörer): ${v * 100 / maxVol} %"
        }
        col.addView(volLabel, matchWrap(18, this))
        volSeek = slider(maxVol, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) { p, fromUser ->
            volText(p)
            if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
        }
        volText(volSeek!!.progress)
        col.addView(volSeek, matchWrap())

        // Balance
        val balLabel = label("", 14f, Pal.MUTED)
        fun balText(p: Int) {
            val v = p - 100
            balLabel.text = when {
                v == 0 -> "Balance: Mitte"
                v < 0 -> "Balance: links ${-v} %"
                else -> "Balance: rechts $v %"
            }
        }
        col.addView(balLabel, matchWrap(14, this))
        val balSeek = slider(200, (balance.balance * 100).toInt() + 100) { p, _ ->
            balance.balance = (p - 100) / 100f
            balText(p)
        }
        balText(balSeek.progress)
        balLabel.setOnClickListener { balSeek.progress = 100 }
        col.addView(balSeek, matchWrap())
        col.addView(label("Tipp: Auf die Balance-Zeile tippen = Mitte", 11f, Pal.MUTED))

        // Lautstärke-Boost
        val boostLabel = label("", 14f, Pal.MUTED)
        fun boostText(db: Int) {
            boostLabel.text = "Lautstärke-Boost: +$db dB" + if (db > 6) "  (kann übersteuern)" else ""
        }
        col.addView(boostLabel, matchWrap(14, this))
        val boostSeek = slider(12, boostMb / 100) { p, _ ->
            setBoostDb(p)
            boostText(p)
        }
        boostText(boostSeek.progress)
        col.addView(boostSeek, matchWrap())

        // Upscaling
        col.addView(label("Bildverbesserung (Upscaling)", 14f, Pal.MUTED), matchWrap(18, this))
        val enhNames = listOf("Aus", "Lanczos", "Lanczos + Farbe")
        val enhRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val enhButtons = ArrayList<TextView>()
        fun markEnh() {
            enhButtons.forEachIndexed { i, b ->
                b.background = withRipple(
                    if (i == enhance) roundRect(Pal.ACCENT, dp(22).toFloat())
                    else roundRect(Pal.SURFACE2, dp(22).toFloat())
                )
            }
        }
        enhNames.forEachIndexed { i, n ->
            val b = pill(n, false) {
                if (batterySaver && i > 0) {
                    showHud("Akku-Schonmodus an – Upscaling aus")
                } else {
                    enhance = i
                    applyEnhance()
                    markEnh()
                }
            }.apply { textSize = 13f; setPadding(dp(6), dp(10), dp(6), dp(10)) }
            enhButtons.add(b)
            enhRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(6), dp(3), 0)
            })
        }
        markEnh()
        col.addView(enhRow, matchWrap())
        col.addView(label("Rechnet das Bild per GPU auf Display-Größe hoch und schärft es. Kostet Akku.", 11f, Pal.MUTED))

        // Geschwindigkeit
        col.addView(label("Geschwindigkeit", 14f, Pal.MUTED), matchWrap(18, this))
        val spRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speeds.forEachIndexed { i, s ->
            val b = pill("${s}x", false) {
                setSpeed(i)
                dialog.dismiss()
            }.apply { textSize = 12f; setPadding(dp(2), dp(9), dp(2), dp(9)) }
            spRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), dp(6), dp(2), 0)
            })
        }
        col.addView(spRow, matchWrap())

        // Einzelbild
        col.addView(label("Einzelbild", 14f, Pal.MUTED), matchWrap(18, this))
        val frameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        frameRow.addView(pill("◀ Bild", false) { stepFrame(-1) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, dp(6), dp(4), 0) })
        frameRow.addView(pill("Bild ▶", false) { stepFrame(1) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(6), 0, 0) })
        col.addView(frameRow, matchWrap())

        // Akku
        col.addView(label("Akku", 14f, Pal.MUTED), matchWrap(18, this))
        var saverBtn: TextView? = null
        saverBtn = pill(saverLabel(), false) {
            setBatterySaver(!batterySaver)
            saverBtn?.text = saverLabel()
            markEnh()
        }
        col.addView(saverBtn, matchWrap(6, this))
        col.addView(label("Dimmt das Bild auf 30 % und schaltet Upscaling aus. Springt bei niedrigem Akku von selbst an.", 11f, Pal.MUTED))

        // Equalizer
        col.addView(label("Equalizer", 14f, Pal.MUTED), matchWrap(18, this))
        val eqCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun buildEqBands() {
            eqCol.removeAllViews()
            val e = eq
            if (e == null) {
                eqCol.addView(label("Der Equalizer ist verfügbar, sobald die Wiedergabe läuft.", 11f, Pal.MUTED))
                return
            }
            val range = e.bandLevelRange
            val lo = range[0].toInt()
            val hi = range[1].toInt()
            val bands = e.numberOfBands.toInt()
            for (b in 0 until bands) {
                val hz = e.getCenterFreq(b.toShort()) / 1000
                val name = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
                val cur = eqLevels?.getOrNull(b)?.toInt() ?: 0
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(label(name, 12f, Pal.MUTED), LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(slider(hi - lo, cur - lo) { p, fromUser ->
                    if (fromUser) {
                        val lv = eqLevels ?: ShortArray(bands)
                        lv[b] = (p + lo).toShort()
                        setEq(lv)
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                eqCol.addView(row, matchWrap())
            }
        }
        val eqRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Aus", "Bass", "Stimme", "Höhen").forEachIndexed { i, n ->
            eqRow.addView(
                pill(n, false) {
                    eqPreset(i)
                    buildEqBands()
                }.apply { textSize = 12f; setPadding(dp(2), dp(9), dp(2), dp(9)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(6), dp(2), 0) }
            )
        }
        col.addView(eqRow, matchWrap())
        col.addView(eqCol, matchWrap(6, this))
        buildEqBands()

        // Untertitel
        col.addView(label("Untertitel", 14f, Pal.MUTED), matchWrap(18, this))
        col.addView(pill("Untertitel-Datei laden (srt, vtt, ass …)", false) {
            dialog.dismiss()
            subPicker.launch(arrayOf("*/*"))
        }, matchWrap(6, this))
        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Klein" to 0.04f, "Normal" to 0.0533f, "Groß" to 0.075f, "Riesig" to 0.10f).forEach { (n, f) ->
            sizeRow.addView(
                pill(n, false) {
                    playerView.subtitleView?.setFractionalTextSize(f)
                    showHud("Untertitel: $n")
                }.apply { textSize = 12f; setPadding(dp(2), dp(9), dp(2), dp(9)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(6), dp(2), 0) }
            )
        }
        col.addView(sizeRow, matchWrap())
        val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Weiß", "Gelb", "Kasten").forEachIndexed { i, n ->
            styleRow.addView(
                pill(n, false) {
                    setSubStyle(i)
                    showHud("Untertitel-Stil: $n")
                }.apply { textSize = 12f; setPadding(dp(2), dp(9), dp(2), dp(9)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(6), dp(2), 0) }
            )
        }
        col.addView(styleRow, matchWrap())

        // Spuren / Ausgang
        val rowBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowBtns.addView(pill("Tonspur", false) {
            dialog.dismiss()
            showTracks(C.TRACK_TYPE_AUDIO, "Tonspur")
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, dp(18), dp(4), 0) })
        rowBtns.addView(pill("Kopfhörer", false) {
            dialog.dismiss()
            AudioOutputs.show(this, player) { refreshBt() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(18), 0, 0) })
        col.addView(rowBtns, matchWrap())

        dialog.setContentView(ScrollView(this).apply { addView(col) })
        dialog.setOnDismissListener {
            volSeek = null
            hideSystemBars()
            scheduleHide()
        }
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        handler.removeCallbacks(hideControls)
    }
}
