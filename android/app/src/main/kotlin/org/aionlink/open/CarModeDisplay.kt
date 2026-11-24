package org.aionlink.open

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaFormat
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
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    /** What the dashboard's Media page reports as the sound route. */
    private val audioRoute: () -> String = { "Car speakers via Bluetooth" },
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
                    setContentView(CarDashboardView(getContext(), onNote, audioRoute).also { dashboard = it })
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
        // The dashboard's release() already stops the server via onStop; this is
        // belt-and-braces for any path where release() never ran (a no-op otherwise).
        DashboardHttpServer.stop()
        display?.release(); display = null
        surface?.release(); surface = null
        codec?.let { try { it.stop() } catch (_: IllegalStateException) { }; it.release() }; codec = null
    }
}

/** The car dashboard is now the web dashboard (assets/carhome/dashboard.html): clock, GPS
 * speed, media with controls, navigation, weather and the app grid, drawn on the virtual
 * display by one WebView. The page polls DashboardBridge for live phone data, and the bridge
 * pushes a "media" event when playback starts, so the Dynamic Island pill pops up. The native
 * dashboard cards and the separate Apps launcher are gone — the dashboard's app grid is the
 * launcher now. The WebView is created once per session and destroyed with it. */
class CarDashboardView(
    context: Context,
    private val onAppsNote: (String) -> Unit = {},
    private val audioRoute: () -> String = { "Car speakers via Bluetooth" },
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bridge: DashboardBridge = DashboardBridge(
        context, audioRoute,
        onOpenBrowser = { url ->
            AppsWebView.enterBrowserMode(webView, bridge, url)
            homeOverlay.visibility = View.VISIBLE
        },
    )
    private val webView: WebView = AppsWebView.createDashboard(context, bridge).apply {
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    /** ⌂ Home, shown only while the dashboard browser is open (third-party main document). */
    private val homeOverlay: TextView = TextView(context).apply {
        text = "⌂ Home"
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xCC17181C.toInt()) }
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply { topMargin = dp(16); rightMargin = dp(16) }
        visibility = View.GONE
        isClickable = true
        setOnClickListener {
            AppsWebView.exitBrowserMode(webView, bridge)
            visibility = View.GONE
        }
    }
    private var lastPlaying = false

    init {
        addView(webView)
        addView(homeOverlay)
        AppsWebView.loadDashboard(webView)
        bridge.start()
        onAppsNote("Web dashboard opened")
        scope.launch {
            CarInfoListener.info.collect { info ->
                if (info.mediaPlaying && !lastPlaying) {
                    webView.evaluateJavascript("PhoneBridge._push('media')", null)
                }
                lastPlaying = info.mediaPlaying
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun release() {
        scope.cancel()
        bridge.stop()
        DashboardHttpServer.stop()
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }
}
