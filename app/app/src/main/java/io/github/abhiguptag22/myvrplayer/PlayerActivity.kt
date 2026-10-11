package io.github.abhiguptag22.myvrplayer

import android.app.Activity
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.File
import kotlin.math.abs

class PlayerActivity : Activity(), IVLCVout.OnNewVideoLayoutListener {

    private lateinit var prefs: Prefs
    private lateinit var mode: VrMode
    private var file: File? = null
    private var uri: Uri = Uri.EMPTY
    private var externalSubtitle: String? = null

    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private var videoSurface: SurfaceTexture? = null
    private var subSurface: SurfaceTexture? = null
    private var mediaOpened = false

    private val handler = Handler(Looper.getMainLooper())
    private var videoAspect = 16f / 9f
    private var pixelAspect = 16f / 9f
    private var lengthMs = 0L

    // ---- seek serialisation (fix for the seek freeze) ----
    private var seekInFlight = false
    private var seekTarget = -1L
    private var seekStartedAt = 0L
    private var pendingSeek = -1L
    private var userDraggingSeekBar = false
    private var lastSeekRequestTarget = 0L
    private var lastSeekRequestAt = 0L

    // ---- freeze watchdog ----
    private var lastWatchTime = -1L
    private var lastWatchFrames = -1L
    private var stalledTicks = 0
    private val recoveries = ArrayList<Long>()
    private var forceSoftwareDecoding = false
    private var lastGoodTimeMs = 0L
    private var resumeToastPending = 0L

    // ---- bluetooth / media keys ----
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    // ---- overlay views ----
    private lateinit var overlay: FrameLayout
    private lateinit var gearPanel: FrameLayout
    private lateinit var hSlider: ValueSlider
    private lateinit var vSlider: ValueSlider
    private lateinit var eyeSlider: ValueSlider
    private lateinit var ipdSlider: ValueSlider
    private lateinit var distSlider: ValueSlider
    private lateinit var sliderRows: List<SliderRow>
    private lateinit var whiteBtn: FrameLayout
    private var whiteScreen = false
    private lateinit var timeBar: TimeBar
    private lateinit var elapsedView: TextView
    private lateinit var remainingView: TextView
    private lateinit var titleView: TextView
    private lateinit var playPauseIcon: android.widget.ImageView
    private lateinit var speedChip: TextView
    private lateinit var audioName: TextView
    private lateinit var subName: TextView
    private lateinit var scaleChip: TextView
    private lateinit var invertChip: TextView
    private lateinit var loopChip: TextView
    private lateinit var theatreChip: TextView
    private lateinit var subDelayView: TextView
    private var subDelayMs = 0L

    private val hideOverlay = Runnable { if (gearPanel.visibility != View.VISIBLE) overlay.visibility = View.GONE }

    // ======================================================================================
    // lifecycle
    // ======================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        readIntent(intent)

        libVlc = LibVLC(this, vlcOptions())
        player = MediaPlayer(libVlc)
        player.setEventListener(::onVlcEvent)

        renderer = VrRenderer(
            onSurfacesReady = { v, s -> runOnUiThread { onSurfacesReady(v, s) } },
            requestRender = { glView.requestRender() },
            loadTheatre = {
                android.graphics.BitmapFactory.decodeResource(resources, R.drawable.theatre_cinema_hall,
                    android.graphics.BitmapFactory.Options().apply { inScaled = false })
            },
        )
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            keepScreenOn = true
            setOnClickListener { toggleOverlay() }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        overlay = buildOverlay()
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        gearPanel = buildGearPanel()
        gearPanel.visibility = View.GONE
        root.addView(gearPanel, FrameLayout.LayoutParams(-1, -1))
        // Draw edge to edge, including the camera cutout strip. By default the decor view pads the
        // content by the cutout, which shrank the video to 2295 px and moved the split line off the
        // physical screen centre, so the two eyes no longer lined up with the headset lenses.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // Only the touch controls keep clear of the cutout.
        overlay.setOnApplyWindowInsetsListener { v, insets ->
            val c = insets.displayCutout
            val l = c?.safeInsetLeft ?: 0; val t = c?.safeInsetTop ?: 0
            val r = c?.safeInsetRight ?: 0; val b = c?.safeInsetBottom ?: 0
            v.setPadding(l, t, r, b)
            gearPanel.setPadding(l, t, r, b)
            insets
        }
        setContentView(root)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) applyHd()
        }

        setupMediaSession()
        pushParams()
        showOverlay()
        goImmersive()
        handler.post(watchdog)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveResume()
        readIntent(intent)
        titleView.text = displayName()
        if (videoSurface != null) openMedia(startMsFor())
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        goImmersive()
        session.isActive = true
    }

    override fun onPause() {
        saveResume()
        if (player.isPlaying) player.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveResume()
        player.setEventListener(null)
        player.stop()
        player.vlcVout.detachViews()
        player.release()
        libVlc.release()
        renderer.release()
        session.isActive = false
        session.release()
        abandonAudioFocus()
        super.onDestroy()
    }

    private fun readIntent(i: Intent) {
        mode = runCatching { VrMode.valueOf(i.getStringExtra(EXTRA_MODE) ?: "") }.getOrDefault(VrMode.MONO_STATIC)
        externalSubtitle = i.getStringExtra(EXTRA_SUBTITLE)
        val path = i.getStringExtra(EXTRA_PATH)
        if (path != null) {
            file = File(path); uri = Uri.fromFile(file)
        } else {
            uri = i.data ?: Uri.EMPTY
            file = if (uri.scheme == "file") uri.path?.let { File(it) } else null
        }
    }

    private fun displayName(): String = file?.name ?: uri.lastPathSegment ?: "video"

    private fun vlcOptions() = arrayListOf(
        "--network-caching=${prefs.networkCachingSec * 1000}",
        "--file-caching=1500",
        "--sub-autodetect-file",
        "--no-drop-late-frames",
        "--no-skip-frames",
    ).apply { if (prefs.resampleAudio) add("--audio-time-stretch") else add("--no-audio-time-stretch") }

    // ======================================================================================
    // VLC wiring
    // ======================================================================================

    private fun onSurfacesReady(video: SurfaceTexture, subs: SurfaceTexture) {
        val vout = player.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        videoSurface = video; subSurface = subs
        vout.setVideoSurface(video)
        vout.setSubtitlesSurface(subs)
        vout.setWindowSize(1920, 1080)
        subs.setDefaultBufferSize(1920, 1080)
        try {
            vout.attachViews(this)
        } catch (e: IllegalArgumentException) {
            // libVLC 3.6 bug: with two raw SurfaceTextures the subtitle helper is already "ready" when the
            // video helper finishes, so its own attach throws "invalid state". By then both native surfaces
            // are registered and the helper loop is the last thing attachViews does, so this is harmless.
            Log.d(TAG, "attachViews: ignored libVLC double-ready check: ${e.message}")
        }
        if (!mediaOpened) {
            mediaOpened = true
            openMedia(startMsFor())
        }
    }

    override fun onNewVideoLayout(
        vlcVout: IVLCVout, width: Int, height: Int, visibleWidth: Int, visibleHeight: Int, sarNum: Int, sarDen: Int,
    ) {
        if (width <= 0 || height <= 0) return
        val vw = if (visibleWidth > 0) visibleWidth else width
        val vh = if (visibleHeight > 0) visibleHeight else height
        val sar = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f
        pixelAspect = vw.toFloat() / vh
        videoAspect = pixelAspect * sar
        vlcVout.setWindowSize(vw, vh)
        subSurface?.setDefaultBufferSize(vw, vh)
        pushParams()
    }

    private fun startMsFor(): Long {
        val f = file ?: return 0L
        val saved = prefs.getResume(f)
        if (saved > 10_000) resumeToastPending = saved
        return if (saved > 10_000) saved else 0L
    }

    private fun openMedia(startMs: Long) {
        seekInFlight = false; pendingSeek = -1; stalledTicks = 0
        lastWatchTime = -1; lastWatchFrames = -1
        player.stop()
        val media = Media(libVlc, uri)
        val hw = prefs.hwAcceleration && !forceSoftwareDecoding
        media.setHWDecoderEnabled(hw, false)
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        player.media = media
        media.release()
        lastGoodTimeMs = startMs
        subDelayMs = file?.let { prefs.getSubtitleDelay(it) } ?: 0L
        if (::subDelayView.isInitialized) subDelayView.text = delayText(subDelayMs)
        file?.let { prefs.lastPlayedPath = it.absolutePath; prefs.lastPlayedMode = mode.name }
        requestAudioFocus()
        player.play()
        player.volume = prefs.volume
        player.rate = Prefs.SPEEDS[prefs.speedIndex.coerceIn(0, Prefs.SPEEDS.size - 1)]
        externalSubtitle?.let { player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(File(it)), true) }
    }

    private fun onVlcEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                applySubDelay()
                updatePlayState(true)
                if (resumeToastPending > 0) {
                    Toast.makeText(this, "Resumed at " + formatTime(resumeToastPending), Toast.LENGTH_SHORT).show()
                    resumeToastPending = 0
                }
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> updatePlayState(false)
            MediaPlayer.Event.LengthChanged -> {
                lengthMs = e.lengthChanged
                file?.let { if (lengthMs > 0) prefs.setLength(it, lengthMs) }
            }
            MediaPlayer.Event.TimeChanged -> {
                val t = e.timeChanged
                if (seekInFlight && (abs(t - seekTarget) < 4000)) finishSeek()
                if (!seekInFlight) lastGoodTimeMs = t
                if (!userDraggingSeekBar && !seekInFlight) showTime(t)
            }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> { applySubDelay(); refreshTrackLabels() }
            MediaPlayer.Event.EndReached -> handler.post { onEnd() }
            MediaPlayer.Event.EncounteredError -> handler.post {
                Toast.makeText(this, "Playback error - retrying", Toast.LENGTH_SHORT).show()
                recover("error")
            }
        }
    }

    private fun onEnd() {
        file?.let { prefs.clearResume(it) }
        if (prefs.loop) openMedia(0) else updatePlayState(false)
    }

    // ======================================================================================
    // seeking: only one seek in flight; newer requests replace older pending ones
    // ======================================================================================

    private fun requestSeek(ms: Long) {
        val len = if (lengthMs > 0) lengthMs else player.length
        val target = if (len > 0) ms.coerceIn(0, len - 1000) else ms.coerceAtLeast(0)
        showTime(target)
        lastSeekRequestTarget = target
        lastSeekRequestAt = SystemClock.elapsedRealtime()
        if (seekInFlight) { pendingSeek = target; return }
        startSeek(target)
    }

    private fun startSeek(target: Long) {
        seekInFlight = true
        seekTarget = target
        seekStartedAt = SystemClock.elapsedRealtime()
        stalledTicks = 0
        player.setTime(target, true)
    }

    private fun finishSeek() {
        seekInFlight = false
        lastGoodTimeMs = seekTarget
        if (pendingSeek >= 0) {
            val p = pendingSeek
            pendingSeek = -1
            startSeek(p)
        }
    }

    private fun seekBy(deltaMs: Long) {
        // Right after a seek VLC still reports the old time, so quick repeated presses (e.g. 5 x -10 s on a
        // remote) build on the last requested target instead of losing steps.
        val recent = SystemClock.elapsedRealtime() - lastSeekRequestAt < 2000
        val base = if (recent) lastSeekRequestTarget else player.time
        requestSeek(base + deltaMs)
    }

    // ======================================================================================
    // watchdog: detects a frozen decoder and reopens the file at the same position
    // ======================================================================================

    private val watchdog = object : Runnable {
        override fun run() {
            handler.postDelayed(this, 1000)
            if (!::player.isInitialized) return
            val now = SystemClock.elapsedRealtime()
            if (seekInFlight && now - seekStartedAt > 2500) finishSeek() // paused / no TimeChanged
            if (!player.isPlaying || seekInFlight) { stalledTicks = 0; return }

            val t = player.time
            val frames = renderer.frameCount.get()
            val timeStuck = t == lastWatchTime
            val framesStuck = frames == lastWatchFrames
            lastWatchTime = t; lastWatchFrames = frames
            stalledTicks = if (timeStuck || framesStuck) stalledTicks + 1 else 0
            if (stalledTicks >= 5) recover("stall time=$timeStuck frames=$framesStuck")

            if (now % 5000 < 1000) saveResume()
        }
    }

    private fun recover(reason: String) {
        Log.w(TAG, "recovering playback: $reason at $lastGoodTimeMs")
        val now = SystemClock.elapsedRealtime()
        recoveries.removeAll { now - it > 60_000 }
        recoveries.add(now)
        if (recoveries.size >= 2 && !forceSoftwareDecoding && prefs.hwAcceleration) {
            forceSoftwareDecoding = true
            Toast.makeText(this, "Video decoder got stuck - switched to software decoding", Toast.LENGTH_LONG).show()
        }
        val pos = if (pendingSeek >= 0) pendingSeek else if (seekInFlight) seekTarget else lastGoodTimeMs
        openMedia(pos)
    }

    // ======================================================================================
    // play / pause, bluetooth media keys
    // ======================================================================================

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            requestAudioFocus()
            if (player.playerState == IMedia.State.Ended || player.playerState == IMedia.State.Stopped) openMedia(0)
            else player.play()
        }
    }

    private fun updatePlayState(playing: Boolean) {
        if (::playPauseIcon.isInitialized) playPauseIcon.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    player.time, if (playing) player.rate else 0f)
                .build()
        )
    }

    private fun setupMediaSession() {
        session = MediaSession(this, "MyVRPlayer")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (!player.isPlaying) togglePlay() }
            override fun onPause() { if (player.isPlaying) player.pause() }
            override fun onStop() { if (player.isPlaying) player.pause() }
            override fun onSkipToNext() = seekBy(10_000)
            override fun onSkipToPrevious() = seekBy(-10_000)
            override fun onFastForward() = seekBy(10_000)
            override fun onRewind() = seekBy(-10_000)
            override fun onSeekTo(pos: Long) = requestSeek(pos)
        })
        session.isActive = true
        updatePlayState(false)
    }

    private fun requestAudioFocus() {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    if (player.isPlaying) player.pause()
                }
            }
            .build().also { focusRequest = it }
        audioManager.requestAudioFocus(req)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    /** Media keys reach the focused window first, so handle them here as well as in the MediaSession. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!player.isPlaying) togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> { if (player.isPlaying) player.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000); return true }
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (prefs.bluetoothSpaceEnter) { togglePlay(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ======================================================================================
    // resume
    // ======================================================================================

    private fun saveResume() {
        val f = file ?: return
        if (!::player.isInitialized) return
        val len = if (lengthMs > 0) lengthMs else player.length
        val t = if (seekInFlight) seekTarget else player.time
        if (len <= 0 || t <= 0) return
        if (t > len - 60_000) prefs.clearResume(f) else prefs.setResume(f, t)
    }

    // ======================================================================================
    // rendering parameters
    // ======================================================================================

    private fun pushParams() {
        renderer.params = RenderParams(
            mode = mode,
            hShift = prefs.hShift, vShift = prefs.vShift,
            distortion = if (prefs.lensDistortionEnabled) prefs.distortion else 0f,
            ipd = prefs.ipd, distance = prefs.distance,
            videoAspect = videoAspect, pixelAspect = pixelAspect,
            scaleAspect = Prefs.SCALES[prefs.scaleIndex.coerceIn(0, Prefs.SCALES.size - 1)].second,
            invertScale = prefs.invertScale,
            imageOn = prefs.imageSettingsEnabled,
            brightness = prefs.brightness, contrast = prefs.contrast, saturation = prefs.saturation,
            subScale = prefs.subtitleSize / 100f, subPos = prefs.subtitlePos / 100f,
            whiteScreen = whiteScreen,
            theatre = prefs.theatre,
            roomLight = prefs.roomLight / 100f,
        )
        glView.requestRender()
    }

    /** HD slider: render at a fraction of the screen resolution. */
    private fun applyHd() {
        val w = glView.width; val h = glView.height
        if (w <= 0 || h <= 0) return
        val f = prefs.hd.coerceIn(30, 100) / 100f
        if (f >= 0.999f) glView.holder.setSizeFromLayout()
        else glView.holder.setFixedSize((w * f).toInt(), (h * f).toInt())
    }

    // ======================================================================================
    // overlay (touch controls)
    // ======================================================================================

    private fun s(v: Int) = dp(v * prefs.touchControlSize / 100f)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && overlay.visibility == View.VISIBLE) pokeOverlay()
        return super.dispatchTouchEvent(ev)
    }

    private fun toggleOverlay() {
        if (gearPanel.visibility == View.VISIBLE) return
        if (overlay.visibility == View.VISIBLE) hideOverlayNow() else showOverlay()
    }

    private fun showOverlay() {
        if (overlay.visibility != View.VISIBLE) {
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1f).setDuration(160).start()
        }
        pokeOverlay()
    }

    private fun hideOverlayNow() {
        handler.removeCallbacks(hideOverlay)
        overlay.animate().alpha(0f).setDuration(200).withEndAction { overlay.visibility = View.GONE }.start()
    }

    private fun pokeOverlay() {
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 4500)
    }

    private fun vrSlider(min: Float, max: Float, step: Float, value: Float, fmt: (Float) -> String,
                         vertical: Boolean = false, onSet: (Float) -> Unit) =
        ValueSlider(this, min, max, step, vertical, fmt).apply {
            this.value = value
            onTouchStart = { pokeOverlay() }
            onChange = { onSet(it); pushParams(); pokeOverlay() }
        }

    private fun glass(radius: Float = 18f) = rounded(Palette.GLASS, radius, Palette.STROKE, 1f)

    private fun scrim(top: Boolean) = android.graphics.drawable.GradientDrawable(
        if (top) android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
        else android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(0xCC000000.toInt(), 0x00000000),
    )

    private fun buildOverlay(): FrameLayout {
        val o = FrameLayout(this)
        o.addView(View(this).apply { background = scrim(true) }, FrameLayout.LayoutParams(-1, s(150), Gravity.TOP))
        o.addView(View(this).apply { background = scrim(false) }, FrameLayout.LayoutParams(-1, s(190), Gravity.BOTTOM))

        hSlider = vrSlider(-100f, 100f, 1f, prefs.hShift, { "${it.toInt()}%" }) { prefs.hShift = it }
        vSlider = vrSlider(-100f, 100f, 1f, prefs.vShift, { "${it.toInt()}%" }) { prefs.vShift = it }
        eyeSlider = vrSlider(-5f, 5f, 0.1f, prefs.distortion, { "%.1f".format(it) }) { prefs.distortion = it }
        ipdSlider = vrSlider(0.20f, 0.60f, 0.01f, prefs.ipd, { "%.2f".format(it) }) { prefs.ipd = it }
        distSlider = vrSlider(10f, 100f, 1f, prefs.distance, { "${it.toInt()}" }, vertical = true) { prefs.distance = it }
        distSlider.showBubble = true

        // ---- top: 2 x 2 slider grid in a glass panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glass()
            setPadding(s(6), s(8), s(6), s(4))
        }
        val leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sliderRows = listOf(
            SliderRow(this, R.drawable.ic_swap_horiz, "Picture shift", hSlider),
            SliderRow(this, R.drawable.ic_swap_vert, "Vertical position", vSlider),
            SliderRow(this, R.drawable.ic_visibility, "Lens distortion", eyeSlider),
            SliderRow(this, R.drawable.ic_compare_arrows, "Eye separation", ipdSlider),
        )
        leftCol.addView(sliderRows[0], LinearLayout.LayoutParams(-1, -2))
        leftCol.addView(sliderRows[1], LinearLayout.LayoutParams(-1, -2).apply { topMargin = s(4) })
        val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightCol.addView(sliderRows[2], LinearLayout.LayoutParams(-1, -2))
        rightCol.addView(sliderRows[3], LinearLayout.LayoutParams(-1, -2).apply { topMargin = s(4) })
        panel.addView(leftCol, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(View(this).apply { setBackgroundColor(Palette.STROKE) },
            LinearLayout.LayoutParams(1, -1).apply { leftMargin = s(6); rightMargin = s(6); topMargin = s(6); bottomMargin = s(6) })
        panel.addView(rightCol, LinearLayout.LayoutParams(0, -2, 1f))
        o.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = s(84); rightMargin = s(84); topMargin = s(12)
        })

        // ---- right: distance pill
        val distPill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = glass(30f)
            setPadding(0, s(12), 0, s(6))
        }
        distPill.addView(icon(R.drawable.ic_zoom_in, 20, Palette.ACCENT_LIGHT))
        distPill.addView(overline("Dist", Palette.TEXT_2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = s(2) })
        distPill.addView(distSlider, LinearLayout.LayoutParams(s(56), 0, 1f).apply { topMargin = s(4) })
        o.addView(distPill, FrameLayout.LayoutParams(s(60), -1, Gravity.END).apply {
            topMargin = s(12); bottomMargin = s(150); rightMargin = s(12)
        })

        // ---- left: options + reset
        val leftRail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = glass(30f)
            setPadding(0, s(6), 0, s(10))
        }
        leftRail.addView(iconButton(R.drawable.ic_tune, 52, BtnStyle.GLASS, contentDescription = "Options") { openGear() },
            LinearLayout.LayoutParams(s(52), s(52)))
        leftRail.addView(overline("Options", Palette.TEXT_2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = s(4) })
        leftRail.addView(iconButton(R.drawable.ic_restart_alt, 52, BtnStyle.GLASS, contentDescription = "Reset sliders") { resetSliders() },
            LinearLayout.LayoutParams(s(52), s(52)).apply { topMargin = s(14) })
        leftRail.addView(overline("Reset", Palette.TEXT_2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = s(4) })
        whiteBtn = iconButton(R.drawable.ic_light_mode, 52, BtnStyle.GLASS, contentDescription = "White screen") { toggleWhiteScreen() }
        leftRail.addView(whiteBtn, LinearLayout.LayoutParams(s(52), s(52)).apply { topMargin = s(14) })
        leftRail.addView(overline("White", Palette.TEXT_2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = s(4) })
        o.addView(leftRail, FrameLayout.LayoutParams(s(64), -2, Gravity.START or Gravity.TOP).apply {
            leftMargin = s(10); topMargin = s(12)
        })

        // ---- bottom: title, time bar, transport
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = text(displayName(), 15f, Palette.TEXT, Type.medium, lines = 1, gravity = Gravity.CENTER)
        bottom.addView(titleView, LinearLayout.LayoutParams(-1, -2))

        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        elapsedView = text("0:00", 13.5f, Palette.TEXT, Type.medium, gravity = Gravity.END or Gravity.CENTER_VERTICAL)
        remainingView = text("-0:00", 13.5f, Palette.TEXT_2, Type.medium)
        timeBar = TimeBar(this).apply {
            onTouchStart = { userDraggingSeekBar = true; pokeOverlay() }
            onScrub = { f -> showTime(fractionToMs(f), updateBar = false); pokeOverlay() }
            onCommit = { f -> userDraggingSeekBar = false; requestSeek(fractionToMs(f)) } // one seek per drag
        }
        timeRow.addView(elapsedView, LinearLayout.LayoutParams(s(64), -2))
        timeRow.addView(timeBar, LinearLayout.LayoutParams(0, s(36), 1f).apply { leftMargin = s(6); rightMargin = s(6) })
        timeRow.addView(remainingView, LinearLayout.LayoutParams(s(64), -2))
        bottom.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s(4) })

        val transport = FrameLayout(this)
        val center = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        center.addView(iconButton(R.drawable.ic_skip_previous, 46, BtnStyle.GHOST, contentDescription = "Previous video") { openSibling(-1) })
        center.addView(iconButton(R.drawable.ic_replay_10, 50, BtnStyle.GHOST, contentDescription = "Back 10 seconds") { seekBy(-10_000) },
            LinearLayout.LayoutParams(s(50), s(50)).apply { leftMargin = s(14) })
        val play = iconButton(R.drawable.ic_play_arrow, 62, BtnStyle.PRIMARY, 34, contentDescription = "Play / pause") { togglePlay() }
        playPauseIcon = play.getChildAt(0) as android.widget.ImageView
        center.addView(play, LinearLayout.LayoutParams(s(62), s(62)).apply { leftMargin = s(18); rightMargin = s(18) })
        center.addView(iconButton(R.drawable.ic_forward_10, 50, BtnStyle.GHOST, contentDescription = "Forward 10 seconds") { seekBy(10_000) },
            LinearLayout.LayoutParams(s(50), s(50)).apply { rightMargin = s(14) })
        center.addView(iconButton(R.drawable.ic_skip_next, 46, BtnStyle.GHOST, contentDescription = "Next video") { openSibling(1) })
        transport.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        speedChip = text(speedText(), 14f, Palette.TEXT, Type.medium, gravity = Gravity.CENTER)
        val speedPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rippled(glass(20f))
            setPadding(s(12), 0, s(14), 0)
            isClickable = true
            setOnClickListener { cycleSpeed() }
            addView(icon(R.drawable.ic_speed, 18, Palette.ACCENT_LIGHT), LinearLayout.LayoutParams(s(18), s(18)))
            addView(speedChip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s(6) })
        }
        transport.addView(speedPill, FrameLayout.LayoutParams(-2, s(40), Gravity.END or Gravity.CENTER_VERTICAL))
        bottom.addView(transport, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s(2) })

        o.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = s(24); rightMargin = s(24); bottomMargin = s(10)
        })
        return o
    }

    /** Both eyes plain white, to spot dust on the screen or lenses. Not saved between sessions. */
    private fun toggleWhiteScreen() {
        whiteScreen = !whiteScreen
        whiteBtn.background = rippled(oval(if (whiteScreen) Palette.ACCENT else 0x26FFFFFF))
        pushParams()
    }

    private fun fractionToMs(f: Float): Long {
        val len = if (lengthMs > 0) lengthMs else player.length
        return if (len > 0) (len * f).toLong() else 0L
    }

    private fun showTime(t: Long, updateBar: Boolean = true) {
        if (!::elapsedView.isInitialized) return
        val len = if (lengthMs > 0) lengthMs else player.length
        elapsedView.text = formatTime(t)
        remainingView.text = "-" + formatTime((len - t).coerceAtLeast(0))
        if (updateBar && len > 0 && !timeBar.scrubbing) timeBar.fraction = t.toFloat() / len
    }

    private fun resetSliders() {
        prefs.resetSliders()
        hSlider.value = prefs.hShift; vSlider.value = prefs.vShift; eyeSlider.value = prefs.distortion
        ipdSlider.value = prefs.ipd; distSlider.value = prefs.distance
        sliderRows.forEach { it.refresh() }
        pushParams()
        Toast.makeText(this, "Sliders reset", Toast.LENGTH_SHORT).show()
    }

    private fun speedText(): String {
        val sp = Prefs.SPEEDS[prefs.speedIndex.coerceIn(0, Prefs.SPEEDS.size - 1)]
        return (if (sp == sp.toInt().toFloat()) sp.toInt().toString() else sp.toString()) + "×"
    }

    private fun cycleSpeed() {
        prefs.speedIndex = (prefs.speedIndex + 1) % Prefs.SPEEDS.size
        player.rate = Prefs.SPEEDS[prefs.speedIndex]
        speedChip.text = speedText()
    }

    /** Previous / next video in the same folder. */
    private fun openSibling(dir: Int) {
        val f = file ?: return
        val siblings = (f.parentFile?.listFiles() ?: return)
            .filter { it.isFile && it.extension.lowercase() in Prefs.VIDEO_EXTENSIONS }
            .sortedBy { it.name.lowercase() }
        val idx = siblings.indexOfFirst { it.absolutePath == f.absolutePath }
        val next = siblings.getOrNull(idx + dir)
        if (next == null) {
            Toast.makeText(this, if (dir > 0) "This is the last video in the folder" else "This is the first video in the folder", Toast.LENGTH_SHORT).show()
            return
        }
        saveResume()
        file = next; uri = Uri.fromFile(next); externalSubtitle = null; lengthMs = 0
        titleView.text = displayName()
        openMedia(startMsFor())
    }

    // ======================================================================================
    // options sheet (gear panel)
    // ======================================================================================

    private fun openGear() {
        handler.removeCallbacks(hideOverlay)
        overlay.visibility = View.GONE
        refreshTrackLabels()
        gearPanel.alpha = 0f
        gearPanel.visibility = View.VISIBLE
        gearPanel.animate().alpha(1f).setDuration(160).start()
    }

    private fun closeGear() {
        gearPanel.visibility = View.GONE
        showOverlay()
    }

    private fun sheetSlider(iconRes: Int, title: String, min: Float, max: Float, value: Int, onSet: (Int) -> Unit): SliderRow {
        val sl = ValueSlider(this, min, max, 1f, false, { "${it.toInt()}%" }).apply { this.value = value.toFloat() }
        return SliderRow(this, iconRes, title, sl).also { row -> row.listen { onSet(it.toInt()) } }
    }

    /** Caption, then  [<]  current choice  [>]. */
    private fun selector(caption: String, iconRes: Int, onPrev: () -> Unit, onNext: () -> Unit): Pair<LinearLayout, TextView> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val headRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headRow.addView(icon(iconRes, 18, Palette.ACCENT_LIGHT), LinearLayout.LayoutParams(s(18), s(18)))
        headRow.addView(text(caption, 13f, Palette.TEXT_2, Type.medium), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s(8) })
        col.addView(headRow, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s(12) })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.SURFACE_2, 14f, Palette.STROKE, 1f)
        }
        box.addView(iconButton(R.drawable.ic_chevron_left, 44, BtnStyle.GHOST) { onPrev() })
        val name = text("", 15f, Palette.TEXT, Type.medium, lines = 1, gravity = Gravity.CENTER)
        box.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(iconButton(R.drawable.ic_chevron_right, 44, BtnStyle.GHOST) { onNext() })
        col.addView(box, LinearLayout.LayoutParams(-1, s(48)).apply { topMargin = s(6) })
        return col to name
    }

    private fun buildGearPanel(): FrameLayout {
        val p = FrameLayout(this).apply {
            setBackgroundColor(Palette.SCRIM)
            isClickable = true
            setOnClickListener { closeGear() } // tap outside the sheet closes it
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, 24f, Palette.STROKE, 1f)
            setPadding(s(20), s(14), s(20), s(16))
            isClickable = true // swallow taps inside the sheet
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Playback options", 19f, Palette.TEXT, Type.medium), LinearLayout.LayoutParams(0, -2, 1f))
        val (aspectView, aspectValue) = headerChip(R.drawable.ic_aspect_ratio, "Aspect",
            Prefs.SCALES[prefs.scaleIndex.coerceIn(0, Prefs.SCALES.size - 1)].first) { cycleScale() }
        scaleChip = aspectValue
        val (invertView, invertValue) = headerChip(R.drawable.ic_swap_horiz, "Invert", onOff(prefs.invertScale)) {
            prefs.invertScale = !prefs.invertScale; invertChip.text = onOff(prefs.invertScale); pushParams()
        }
        invertChip = invertValue
        val (loopView, loopValue) = headerChip(R.drawable.ic_repeat, "Loop", onOff(prefs.loop)) {
            prefs.loop = !prefs.loop; loopChip.text = onOff(prefs.loop)
        }
        loopChip = loopValue
        val (theatreView, theatreValue) = headerChip(R.drawable.ic_movie, "Theatre", onOff(prefs.theatre)) {
            prefs.theatre = !prefs.theatre; theatreChip.text = onOff(prefs.theatre); pushParams()
        }
        theatreChip = theatreValue
        header.addView(theatreView, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = s(8) })
        header.addView(aspectView)
        header.addView(invertView, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s(8) })
        header.addView(loopView, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s(8) })
        header.addView(pillButton("Done", R.drawable.ic_check, BtnStyle.PRIMARY, 40, 14f) { closeGear() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s(16) })
        sheet.addView(header, LinearLayout.LayoutParams(-1, -2))

        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // left column: picture & sound
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(overline("Picture & sound").apply { setPadding(s(12), 0, 0, s(4)) })
        left.addView(sheetSlider(R.drawable.ic_volume_up, "Volume", 0f, 100f, prefs.volume) { prefs.volume = it; player.volume = it })
        left.addView(sheetSlider(R.drawable.ic_hd, "Render quality", 30f, 100f, prefs.hd) { prefs.hd = it; applyHd() })
        left.addView(sheetSlider(R.drawable.ic_brightness_6, "Brightness", 0f, 100f, prefs.brightness) { prefs.brightness = it; pushParams() })
        left.addView(sheetSlider(R.drawable.ic_palette, "Saturation", 0f, 100f, prefs.saturation) { prefs.saturation = it; pushParams() })
        left.addView(sheetSlider(R.drawable.ic_contrast, "Contrast", 0f, 100f, prefs.contrast) { prefs.contrast = it; pushParams() })
        left.addView(sheetSlider(R.drawable.ic_light_mode, "Theatre house lights", 0f, 100f, prefs.roomLight) { prefs.roomLight = it; pushParams() })
        cols.addView(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(left) },
            LinearLayout.LayoutParams(0, -1, 1f))

        cols.addView(View(this).apply { setBackgroundColor(Palette.STROKE) },
            LinearLayout.LayoutParams(1, -1).apply { leftMargin = s(14); rightMargin = s(14) })

        // right column: tracks, subtitles, display
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(overline("Audio & subtitles").apply { setPadding(s(12), 0, 0, s(6)) })
        val (audioSel, aName) = selector("Audio track", R.drawable.ic_volume_up, { cycleAudio(-1) }, { cycleAudio(1) })
        audioName = aName
        right.addView(audioSel)
        val (subSel, sName) = selector("Subtitle track", R.drawable.ic_subtitles, { cycleSubtitle(-1) }, { cycleSubtitle(1) })
        subName = sName
        val subRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        subRow.addView(subSel, LinearLayout.LayoutParams(0, -2, 1f))
        subRow.addView(delayStepper(), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = s(10) })
        right.addView(subRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s(10) })

        val subSize = ValueSlider(this, 40f, 250f, 1f, false, { "${it.toInt()}%" }).apply { value = prefs.subtitleSize.toFloat() }
        right.addView(SliderRow(this, R.drawable.ic_zoom_in, "Subtitle size", subSize).also { row ->
            row.listen { prefs.subtitleSize = it.toInt(); pushParams() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s(10) })
        val subPos = ValueSlider(this, -20f, 80f, 1f, false, { "${it.toInt()}%" }).apply { value = prefs.subtitlePos.toFloat() }
        right.addView(SliderRow(this, R.drawable.ic_swap_vert, "Subtitle position", subPos).also { row ->
            row.listen { prefs.subtitlePos = it.toInt(); pushParams() }
        })

        cols.addView(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(right) },
            LinearLayout.LayoutParams(0, -1, 1f))

        sheet.addView(cols, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = s(12) })
        p.addView(sheet, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.84f).toInt(), -1, Gravity.CENTER).apply {
            topMargin = s(18); bottomMargin = s(18)
        })
        return p
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    /** Compact pill: icon, caption and current value, e.g. [aspect] Aspect 16:9. */
    private fun headerChip(iconRes: Int, caption: String, value: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val v = text(value, 14f, Palette.TEXT, Type.medium)
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rippled(rounded(Palette.SURFACE_2, 20f, Palette.STROKE, 1f))
            setPadding(s(12), 0, s(14), 0)
            minimumHeight = s(40)
            isClickable = true
            setOnClickListener { onClick() }
            addView(icon(iconRes, 18, Palette.ACCENT_LIGHT), LinearLayout.LayoutParams(s(18), s(18)))
            addView(text(caption, 13f, Palette.TEXT_2, Type.medium), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s(8) })
            addView(v, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s(6) })
        }
        return chip to v
    }

    private fun cycleScale() {
        prefs.scaleIndex = (prefs.scaleIndex + 1) % Prefs.SCALES.size
        scaleChip.text = Prefs.SCALES[prefs.scaleIndex].first
        pushParams()
    }

    private fun cycleAudio(dir: Int) {
        val tracks = player.audioTracks ?: return
        if (tracks.isEmpty()) return
        val idx = tracks.indexOfFirst { it.id == player.audioTrack }.coerceAtLeast(0)
        player.setAudioTrack(tracks[(idx + dir + tracks.size) % tracks.size].id)
        handler.postDelayed({ refreshTrackLabels() }, 150)
    }

    private fun cycleSubtitle(dir: Int) {
        val tracks = player.spuTracks ?: return
        if (tracks.isEmpty()) return
        val idx = tracks.indexOfFirst { it.id == player.spuTrack }.coerceAtLeast(0)
        player.setSpuTrack(tracks[(idx + dir + tracks.size) % tracks.size].id)
        handler.postDelayed({ refreshTrackLabels() }, 150)
    }

    // ---- subtitle delay (like VLC: + = subtitles later) ----

    private fun delayText(ms: Long): String = when {
        ms == 0L -> "0.0 s"
        ms > 0 -> "+%.1f s".format(ms / 1000.0)
        else -> "−%.1f s".format(-ms / 1000.0)
    }

    private fun applySubDelay() {
        player.setSpuDelay(subDelayMs * 1000) // libVLC takes microseconds
    }

    private fun changeSubDelay(deltaMs: Long) {
        subDelayMs = if (deltaMs == 0L) 0L else (subDelayMs + deltaMs).coerceIn(-60_000L, 60_000L)
        applySubDelay()
        file?.let { prefs.setSubtitleDelay(it, subDelayMs) }
        if (::subDelayView.isInitialized) subDelayView.text = delayText(subDelayMs)
    }

    /** A button that repeats while held, speeding up after a moment. */
    private fun repeatingButton(iconRes: Int, desc: String, action: () -> Unit): FrameLayout {
        val b = iconButton(iconRes, 44, BtnStyle.GHOST, contentDescription = desc) { }
        var repeats = 0
        val tick = object : Runnable {
            override fun run() {
                action(); repeats++
                handler.postDelayed(this, if (repeats < 8) 160L else 60L)
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; repeats = 0; action(); handler.postDelayed(tick, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(tick) }
            }
            true
        }
        return b
    }

    private fun delayStepper(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon(R.drawable.ic_history, 18, Palette.ACCENT_LIGHT), LinearLayout.LayoutParams(s(18), s(18)))
        head.addView(text("Subtitle delay", 13f, Palette.TEXT_2, Type.medium), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s(8) })
        col.addView(head, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s(12) })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.SURFACE_2, 14f, Palette.STROKE, 1f)
        }
        box.addView(repeatingButton(R.drawable.ic_remove, "Subtitles earlier") { changeSubDelay(-SUB_DELAY_STEP_MS) })
        subDelayView = text(delayText(subDelayMs), 15f, Palette.TEXT, Type.medium, lines = 1, gravity = Gravity.CENTER).apply {
            isClickable = true
            setOnClickListener { changeSubDelay(0) } // tap the value to reset
        }
        box.addView(subDelayView, LinearLayout.LayoutParams(0, -1, 1f))
        box.addView(repeatingButton(R.drawable.ic_add, "Subtitles later") { changeSubDelay(SUB_DELAY_STEP_MS) })
        col.addView(box, LinearLayout.LayoutParams(-1, s(48)).apply { topMargin = s(6) })
        return col
    }

    private fun refreshTrackLabels() {
        if (!::audioName.isInitialized) return
        val a = player.audioTracks
        val ai = a?.indexOfFirst { it.id == player.audioTrack } ?: -1
        audioName.text = if (a != null && ai >= 0) a[ai].name else "No audio"
        val st = player.spuTracks
        val si = st?.indexOfFirst { it.id == player.spuTrack } ?: -1
        subName.text = if (st != null && si >= 0) st[si].name else "Off"
    }

    companion object {
        private const val TAG = "PlayerActivity"
        const val EXTRA_PATH = "path"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SUBTITLE = "subtitle"
        private const val SUB_DELAY_STEP_MS = 500L
    }
}
