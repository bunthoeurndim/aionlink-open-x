package org.aionlink.open

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Car mode: AionLink's own dashboard drawn on a private virtual display and encoded to H.264, with no screen capture.
 * The 6 Nov 2026 test showed such a display keeps drawing and encoding for 10 minutes with the phone screen off and
 * locked. Experimental external-content mode uses the same private display without the dashboard; access and
 * visibility of another app require Android approval and remain unverified. Touches are implemented for the dashboard only.
 */
class CarModeDisplay(
    private val context: Context, private val width: Int, private val height: Int, private val fps: Int, private val bitrate: Int,
    private val onAccessUnit: (ByteArray, Long, Boolean) -> Unit, private val onError: (String) -> Unit,
    private val externalContent: Boolean = false,
    /** Owner override: keep every frame at SEVERE thermal state instead of drawing one in two. */
    private val keepFullFpsWhenHot: Boolean = false,
    /** Diagnostic notes, such as thermal adjustments. */
    private val onNote: (String) -> Unit = {},
) : AutoCloseable {
    companion object {
        /** Larger than the car's 160 dpi so text and buttons are comfortable at arm's length (1920x960 → 1280x640 dp). */
        const val DENSITY = 240
    }

    private val main = Handler(Looper.getMainLooper())
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var relay: SurfaceRelay? = null
    private var dashboard: CarDashboardView? = null
    private var downTime = 0L
    @Volatile private var running = false
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var thermalStep = 0

    /** Main thread only. */
    fun start() {
        try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            encoder.configure(ScreenEncoder.format(width, height, fps, bitrate, repeatFrames = !externalContent, variableBitrate = true),
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = encoder.createInputSurface(); surface = input
            encoder.start()
            running = true
            Thread({ drain(encoder) }, "AionLink-car-h264").apply { isDaemon = true; start() }
            watchThermal()
            // In direct mode the other app is the sole producer for the encoder's Surface.
            // External content: the producer draws into a GL relay that rescales into the encoder surface.
            if (externalContent) { relay = SurfaceRelay(input, width, height); return }
            val virtual = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "AionLink-car", width, height, DENSITY, input,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            ) ?: error("No virtual display")
            display = virtual
            presentation = if (!externalContent) object : Presentation(context, virtual.display) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(CarDashboardView(getContext(), onNote).also { dashboard = it })
                }
                override fun onStop() { dashboard?.release(); super.onStop() }
            }.also { it.show() }
            // External content: a black placeholder window makes AionLink present on the display while the other
            // app's activity is opened; it is removed afterwards because presentation windows sit above activities.
            else Presentation(context, virtual.display).apply {
                setContentView(View(context).apply { setBackgroundColor(android.graphics.Color.BLACK) })
                show()
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    /** Only the display owner can request launching a consenting activity on this private display. */
    fun externalDisplayId(): Int? = if (externalContent) display?.display?.displayId else null

    fun externalInputSurface(): Surface? = if (externalContent) relay?.input else null

    /** Frames the GL relay passed to the encoder (external content only). */
    val relayedFrames: Long get() = relay?.framesDrawn ?: 0
    fun isExternalContent(): Boolean = externalContent

    /** External content only: drop the placeholder once the other app's activity is on the display. Main thread. */
    fun removePlaceholder() {
        if (externalContent) { presentation?.dismiss(); presentation = null }
    }

    /**
     * Android's thermal status (API 29): MODERATE lowers the bitrate to 70 %; SEVERE or worse to 50 % and, with
     * external content, draws one of every two frames (15 fps at 30). Restores both when the phone cools down.
     * When [keepFullFpsWhenHot] is on, SEVERE still halves the bitrate but keeps every frame.
     */
    private fun watchThermal() {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status -> applyThermal(status) }
        thermalListener = listener
        power.addThermalStatusListener(context.mainExecutor, listener)
    }

    private fun applyThermal(status: Int) {
        val step = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 2
            status >= PowerManager.THERMAL_STATUS_MODERATE -> 1
            else -> 0
        }
        if (step == thermalStep || !running) return
        thermalStep = step
        val target = when (step) { 2 -> bitrate / 2; 1 -> bitrate * 7 / 10; else -> bitrate }
        try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target) }) }
        catch (_: IllegalStateException) { return }
        val halfFrames = step == 2 && !keepFullFpsWhenHot
        relay?.frameDivisor = if (halfFrames) 2 else 1
        onNote("Thermal: state $status; video ${target / 1000} kbps" + if (halfFrames && relay != null) ", half frames" else "")
    }

    fun requestKeyframe() {
        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    /** UIBC touch in the encoded frame, which is the virtual display itself. Main thread only. */
    fun touch(action: Int, x: Float, y: Float) {
        val root = presentation?.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        if (action and 0xFF == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action and 0xFF, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(event)
        event.recycle()
    }

    /** The car's back key closes nothing yet; it is accepted so it never reaches the phone. */
    fun key(keyCode: Int) = Unit

    private fun drain(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var config: ByteArray? = null
        try {
            while (running) {
                val index = encoder.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue
                try {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = bytes
                        else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val csd = config
                            onAccessUnit(if (key && csd != null && !startsWithSps(bytes)) csd + bytes else bytes, info.presentationTimeUs, key)
                        }
                    }
                } finally { encoder.releaseOutputBuffer(index, false) }
            }
        } catch (error: Exception) { if (running) onError("CAR_ENCODER_${error.javaClass.simpleName}") }
    }

    private fun startsWithSps(bytes: ByteArray): Boolean {
        val offset = when {
            bytes.size > 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() -> 3
            bytes.size > 5 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte() -> 4
            else -> return false
        }
        return bytes[offset].toInt() and 0x1f == 7
    }

    /** Main thread only. */
    override fun close() {
        running = false
        thermalListener?.let { listener ->
            try { context.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(listener) } catch (_: Exception) { }
        }
        thermalListener = null
        relay?.close(); relay = null
        presentation?.dismiss(); presentation = null
        dashboard = null
        display?.release(); display = null
        surface?.release(); surface = null
        codec?.let { try { it.stop() } catch (_: IllegalStateException) { }; it.release() }; codec = null
    }
}

/** The car dashboard: clock, battery, media with controls and the current navigation instruction. Plain Views.
 * The "Apps" button swaps this view for a full-screen WebView with a local launcher page (YouTube, Maps,
 * Weather); the WebView is created lazily, paused when hidden, and destroyed with the dashboard. */
class CarDashboardView(context: Context, private val onAppsNote: (String) -> Unit = {}) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val clock = text(110f, bold = true)
    private val date = text(30f)
    private val battery = text(26f, color = 0xFF9FB3C8.toInt())
    private val mediaApp = text(22f, color = 0xFF9FB3C8.toInt())
    private val mediaTitle = text(40f, bold = true)
    private val mediaArtist = text(28f, color = 0xFFCBD5E1.toInt())
    private val mediaArt = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(120), dp(120)); scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val mediaProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(16) }
    }
    private var lastInfo: CarInfo? = null
    private val playButton = button("▶") { CarInfoListener.playPause() }
    private val navIcon = ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(dp(120), dp(120)) }
    private val navTitle = text(40f, bold = true)
    private val navText = text(28f, color = 0xFFCBD5E1.toInt())
    private val tick = object : Runnable {
        override fun run() { updateClock(); updateProgress(); postDelayed(this, 1000) }
    }
    private val appsButton = textButton("▦  Apps") { showApps() }
    private val dashboardRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(0xFF0B0F14.toInt())
        setPadding(dp(32), dp(32), dp(32), dp(32))
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    private val webContainer = FrameLayout(context).apply {
        visibility = View.GONE
        setBackgroundColor(0xFF0B0F14.toInt())
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    private val webColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    private val confirmCard = LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(32), dp(32), dp(32), dp(32))
        background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xFF111827.toInt()) }
        layoutParams = FrameLayout.LayoutParams(dp(700), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        visibility = View.GONE
    }
    private var appsWebView: WebView? = null
    private var youtubeConfirmed = false
    private var pendingYouTubeUrl: String? = null

    init {
        dashboardRow.addView(column(1f, clock, date, battery, text(20f, color = 0xFF64748B.toInt()).apply { text = "AionLink · Car mode" }, appsButton))
        dashboardRow.addView(card(1.3f).apply {
            addView(mediaArt); addView(mediaApp); addView(mediaTitle); addView(mediaArtist); addView(mediaProgress)
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0)
                addView(button("⏮") { CarInfoListener.previous() }); addView(playButton); addView(button("⏭") { CarInfoListener.next() })
            })
        })
        dashboardRow.addView(card(1.3f).apply { addView(navIcon); addView(navTitle); addView(navText) })
        webColumn.addView(topBar())
        webContainer.addView(webColumn)
        webContainer.addView(confirmCard)
        addView(dashboardRow)
        addView(webContainer)
        post(tick)
        scope.launch { CarInfoListener.info.collect(::render) }
    }

    fun release() {
        removeCallbacks(tick); scope.cancel()
        appsWebView?.let { web ->
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        appsWebView = null
    }

    /** The Apps button opens the launcher; the WebView is built once and kept for this session. */
    private fun showApps() {
        if (appsWebView == null) {
            appsWebView = AppsWebView.create(context, onYouTubeTap = { url -> showYouTubeConfirm(url) }).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }.also { webColumn.addView(it) }
            AppsWebView.loadLauncher(appsWebView!!)
        }
        dashboardRow.visibility = View.GONE
        webContainer.visibility = View.VISIBLE
        appsWebView?.onResume()
        onAppsNote("Dashboard web apps opened")
    }

    private fun showDashboard() {
        appsWebView?.onPause()
        webContainer.visibility = View.GONE
        confirmCard.visibility = View.GONE
        dashboardRow.visibility = View.VISIBLE
        onAppsNote("Dashboard web apps closed")
    }

    /** YouTube is parked-only: ask once per dashboard session, then load the tapped link. */
    private fun showYouTubeConfirm(url: String) {
        if (youtubeConfirmed) { appsWebView?.loadUrl(url); return }
        pendingYouTubeUrl = url
        confirmCard.removeAllViews()
        confirmCard.addView(text(30f, bold = true).apply {
            text = "Video is for parked use only."
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        confirmCard.addView(text(24f, color = 0xFFCBD5E1.toInt()).apply {
            text = "Only watch YouTube when the car is parked."
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        })
        confirmCard.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) }
            addView(textButton("Parked — open YouTube") {
                youtubeConfirmed = true; confirmCard.visibility = View.GONE
                pendingYouTubeUrl?.let { appsWebView?.loadUrl(it) }; pendingYouTubeUrl = null
            })
            addView(textButton("Cancel") { confirmCard.visibility = View.GONE; pendingYouTubeUrl = null })
        })
        confirmCard.visibility = View.VISIBLE
    }

    private fun topBar() = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(16))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(textButton("⌂ Home") { appsWebView?.let(AppsWebView::loadLauncher) })
        addView(textButton("‹ Back") { appsWebView?.let { if (it.canGoBack()) it.goBack() else AppsWebView.loadLauncher(it) } })
        addView(textButton("✕ Close") { showDashboard() })
        addView(text(24f, color = 0xFF9FB3C8.toInt()).apply {
            text = "Apps — same pages as the phone preview"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(16), 0, 0, 0) }
        })
    }

    private fun render(info: CarInfo) {
        lastInfo = info
        val access = CarInfoListener.granted(context)
        mediaApp.text = info.mediaApp ?: "Music"
        mediaTitle.text = info.mediaTitle ?: if (access) "Nothing playing" else "Enable notification access in AionLink"
        mediaArtist.text = info.mediaArtist ?: ""
        playButton.text = if (info.mediaPlaying) "⏸" else "▶"
        mediaArt.setImageBitmap(info.mediaArt)
        mediaArt.visibility = if (info.mediaArt != null) View.VISIBLE else View.GONE
        updateProgress()
        navTitle.text = info.navTitle ?: "No active navigation"
        navText.text = info.navText ?: if (access) "Start a route in Maps or Waze on the phone" else ""
        navIcon.setImageBitmap(info.navIcon)
        navIcon.visibility = if (info.navIcon != null) View.VISIBLE else View.GONE
    }

    /** Track position as a 0..1 fraction, or null when there is nothing to show (no track, live
     * stream without a duration). While playing, the position advances from the last read. */
    private fun trackFraction(info: CarInfo): Float? {
        if (info.mediaDurationMs <= 0L) return null
        val pos = if (info.mediaPlaying) info.mediaPositionMs + (SystemClock.elapsedRealtime() - info.mediaPositionAtMs)
            else info.mediaPositionMs
        return (pos.toFloat() / info.mediaDurationMs).coerceIn(0f, 1f)
    }

    private fun updateProgress() {
        val fraction = lastInfo?.let(::trackFraction)
        if (fraction == null) mediaProgress.visibility = View.GONE
        else { mediaProgress.visibility = View.VISIBLE; mediaProgress.progress = (fraction * 1000).toInt() }
    }

    private fun updateClock() {
        val now = Date()
        clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(now)
        date.text = DateFormat.getDateInstance(DateFormat.FULL).format(now)
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.let {
            val raw = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (raw >= 0 && scale > 0) raw * 100 / scale else null
        }
        val charging = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true
        battery.text = level?.let { "Phone: $it %" + if (charging) " · charging" else "" } ?: ""
    }

    private fun textButton(label: String, action: () -> Unit) = TextView(context).apply {
        text = label; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(40).toFloat(); setColor(0xFF1E293B.toInt()) }
        setPadding(dp(24), dp(14), dp(24), dp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), dp(12), 0) }
        isClickable = true
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(context).apply {
        textSize = size; setTextColor(color); maxLines = 2
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, action: () -> Unit) = TextView(context).apply {
        text = label; textSize = 52f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(48).toFloat(); setColor(0xFF1E293B.toInt()) }
        layoutParams = LinearLayout.LayoutParams(dp(130), dp(110)).apply { setMargins(dp(12), 0, dp(12), 0) }
        isClickable = true
        setOnClickListener { action() }
    }

    private fun column(weight: Float, vararg views: View) = LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        views.forEach(::addView)
    }

    private fun card(weight: Float) = LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(28), dp(28), dp(28), dp(28))
        background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xFF111827.toInt()) }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply { setMargins(dp(16), 0, 0, 0) }
    }
}
