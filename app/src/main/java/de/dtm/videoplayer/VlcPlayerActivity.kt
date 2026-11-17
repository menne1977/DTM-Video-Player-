package de.dtm.videoplayer

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import kotlin.math.abs
import kotlin.math.min

/**
 * Fallback-Player mit der VLC-Engine. Startet automatisch, wenn der Media3-Player
 * eine Datei nicht lesen oder decodieren kann (z. B. VOB, AVI, ungewöhnliche Container).
 */
class VlcPlayerActivity : AppCompatActivity() {

    private lateinit var libVlc: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var surface: SurfaceView
    private lateinit var audioManager: AudioManager

    private val uris = ArrayList<Uri>()
    private var index = 0
    private var fd: ParcelFileDescriptor? = null
    private var started = false

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var controls: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var hud: TextView
    private lateinit var speedBtn: TextView

    private var controlsVisible = true
    private var scrubbing = false
    private var speedIdx = 2
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var aspectIdx = 0
    private val aspectNames = arrayOf("Original", "16:9", "4:3", "21:9")
    private val aspectValues = arrayOf<String?>(null, "16:9", "4:3", "21:9")

    private var gestureActive = false
    private var gestureVolStart = 0
    private var gestureBrightStart = 0.5f

    private val hideControls = Runnable { setControls(false) }
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        intent.getParcelableArrayListExtra<Uri>(PlayerActivity.EXTRA_URIS)?.let { uris.addAll(it) }
        intent.data?.let { if (uris.isEmpty()) uris.add(it) }
        if (uris.isEmpty()) {
            finish()
            return
        }

        libVlc = LibVLC(this, ArrayList<String>())
        mp = MediaPlayer(libVlc)
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.EndReached -> runOnUiThread {
                    if (index + 1 < uris.size) playIndex(index + 1) else setControls(true)
                }
                MediaPlayer.Event.EncounteredError -> runOnUiThread {
                    Toast.makeText(this, "Diese Datei kann auch die VLC-Engine nicht abspielen", Toast.LENGTH_LONG).show()
                    setControls(true)
                }
                MediaPlayer.Event.Playing, MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped ->
                    runOnUiThread { updatePlayIcon() }
            }
        }

        buildUi()
        hideSystemBars()
        handler.post(ticker)
        scheduleHide()
    }

    override fun onStart() {
        super.onStart()
        val vout = mp.vlcVout
        if (!vout.areViewsAttached()) {
            vout.setVideoView(surface)
            vout.attachViews()
            vout.setWindowSize(surface.width, surface.height)
        }
        if (!started) {
            started = true
            playIndex(index)
        }
    }

    override fun onStop() {
        super.onStop()
        mp.pause()
        val vout = mp.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::mp.isInitialized) {
            mp.setEventListener(null)
            mp.stop()
            mp.release()
        }
        if (::libVlc.isInitialized) libVlc.release()
        try {
            fd?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------------------------------------------------------- Wiedergabe

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

    private fun playIndex(i: Int) {
        index = i
        val uri = uris[i]
        titleView.text = displayName(uri)
        try {
            fd?.close()
        } catch (_: Exception) {
        }
        fd = null
        try {
            val media = if (uri.scheme == "content") {
                val p = contentResolver.openFileDescriptor(uri, "r") ?: return
                fd = p
                Media(libVlc, p.fileDescriptor)
            } else {
                Media(libVlc, uri)
            }
            media.setHWDecoderEnabled(true, false)
            mp.media = media
            media.release()
            mp.play()
            mp.rate = speeds[speedIdx]
        } catch (e: Exception) {
            Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlay() {
        if (mp.isPlaying) mp.pause() else mp.play()
    }

    private fun seekBy(ms: Long) {
        val len = mp.length
        val target = (mp.time + ms).coerceAtLeast(0L)
        mp.setTime(if (len > 0) min(target, len) else target)
        showHud(if (ms > 0) "+${ms / 1000} s" else "${ms / 1000} s")
    }

    private fun updatePlayIcon() {
        playBtn.setImageResource(if (mp.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateProgress() {
        updatePlayIcon()
        if (!controlsVisible || scrubbing) return
        val len = mp.length
        val t = mp.time
        durView.text = if (len > 0) fmt(len) else "--:--"
        posView.text = fmt(t.coerceAtLeast(0L))
        if (len > 0) seek.progress = (t * 1000 / len).toInt().coerceIn(0, 1000)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun pickTrack(
        title: String,
        tracks: Array<MediaPlayer.TrackDescription>?,
        current: Int,
        apply: (Int) -> Unit
    ) {
        if (tracks == null || tracks.isEmpty()) {
            Toast.makeText(this, "Keine Auswahl vorhanden", Toast.LENGTH_SHORT).show()
            return
        }
        val names = tracks.map { it.name ?: "Spur ${it.id}" }.toTypedArray()
        val checked = tracks.indexOfFirst { it.id == current }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(names, checked) { d, which ->
                apply(tracks[which].id)
                d.dismiss()
            }
            .show()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        surface.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            if (::mp.isInitialized && mp.vlcVout.areViewsAttached()) mp.vlcVout.setWindowSize(r - l, b - t)
        }

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

        controls = FrameLayout(this)
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        controls.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(110), Gravity.TOP))
        controls.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(170), Gravity.BOTTOM))

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
        top.addView(label("VLC-Engine", 11f, Pal.ACCENT2, true).apply { setPadding(dp(8), 0, dp(8), 0) })
        controls.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        center.addView(iconBtn(R.drawable.ic_prev, 56, 14) { if (index > 0) playIndex(index - 1) })
        playBtn = iconBtn(R.drawable.ic_pause, 76, 20) { togglePlay() }.apply {
            background = withRipple(
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.ACCENT, Pal.ACCENT2))
                    .apply { shape = GradientDrawable.OVAL }
            )
        }
        center.addView(playBtn, LinearLayout.LayoutParams(dp(76), dp(76)).apply { setMargins(dp(24), 0, dp(24), 0) })
        center.addView(iconBtn(R.drawable.ic_next, 56, 14) { if (index + 1 < uris.size) playIndex(index + 1) })
        controls.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

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
        seek = slider(1000, 0) { _, _ -> }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) posView.text = fmt(mp.length.coerceAtLeast(0L) * p / 1000)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                scrubbing = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                val len = mp.length
                if (len > 0) mp.setTime(len * s.progress / 1000)
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
                mp.rate = speeds[speedIdx]
                text = "${speeds[speedIdx]}x"
            }
        }
        btns.addView(speedBtn, LinearLayout.LayoutParams(dp(56), dp(44)))
        btns.addView(iconBtn(R.drawable.ic_aspect) {
            aspectIdx = (aspectIdx + 1) % aspectValues.size
            mp.setAspectRatio(aspectValues[aspectIdx])
            mp.setScale(0f)
            showHud("Bildformat: ${aspectNames[aspectIdx]}")
        })
        btns.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        btns.addView(iconBtn(R.drawable.ic_rew) { seekBy(-10_000) })
        btns.addView(iconBtn(R.drawable.ic_ff) { seekBy(10_000) })
        btns.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        btns.addView(label("Ton", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                pickTrack("Tonspur", mp.audioTracks, mp.getAudioTrack()) { id -> mp.setAudioTrack(id) }
            }
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        btns.addView(iconBtn(R.drawable.ic_cc) {
            pickTrack("Untertitel", mp.spuTracks, mp.getSpuTrack()) { id -> mp.setSpuTrack(id) }
        })
        bottom.addView(btns, matchWrap())
        controls.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        hud = label("", 16f, Color.WHITE, true).apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = roundRect(0xCC151824.toInt(), dp(20).toFloat())
            visibility = View.GONE
        }
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(48)
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            controls.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        setContentView(root)
    }

    private fun setControls(show: Boolean) {
        if (controlsVisible == show) return
        controlsVisible = show
        controls.fadeTo(show)
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 3500)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showHud(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 1000)
    }
}
