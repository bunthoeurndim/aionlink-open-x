package org.aionlink.open

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aionlink.iccoa.ExperimentalAuthCrypto
import org.aionlink.iccoa.UCarFrameWriter
import org.aionlink.projection.TsRtpStreamer
import org.aionlink.projection.UibcEvent
import org.aionlink.projection.UibcMapper
import org.aionlink.projection.UibcMediaKeys
import org.aionlink.projection.UibcServer
import org.aionlink.projection.WfdFormats
import org.aionlink.projection.WfdPlay
import org.aionlink.projection.WfdSource
import org.aionlink.projection.WfdStatus
import org.aionlink.wifi.CarFrame
import org.aionlink.wifi.ControlChannelStatus
import org.aionlink.wifi.SessionControlServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress

data class StreamStatus(val stage: String = "IDLE", val detail: String = "", val accessUnits: Long = 0,
                        val datagrams: Long = 0, val audioFrames: Long = 0, val audio: String = "IDLE",
                        val fps: Long = 0, val slowWrites: Int = 0)
data class TouchStatus(val stage: String = "IDLE", val events: Int = 0, val injected: Int = 0, val outside: Int = 0,
                        val frameWidth: Int = 0, val frameHeight: Int = 0, val keys: Int = 0, val customKeys: Int = 0)

/**
 * Post-AUTH session observed in Samsung: phone-side CONTROL listener (57219), passive MEDIA (57229) and
 * SENSOR (57239) listeners, UIBC touch input (4321) and a Wi-Fi Display source (RTSP 7236, RTP server port
 * 15550). Video/audio come from the owner-approved MediaProjection. Status values are exported without
 * addresses, keys, coordinates or payloads.
 */
class ProjectionSession(private val context: Context, private val savings: PhoneSavings, private val event: (String) -> Unit,
    private val onSessionClosedByCar: () -> Unit = {}) {
    private companion object {
        const val MAX_LOGGED_FRAMES = 40
        /** The car asked to end the session (drive over, CarLink closed). */
        const val CAR_REQUESTED_DISCONNECT = "COCHE_PIDIO_DESCONEXION"
        /** One frame at 30 fps is 33 ms; a TCP write this slow means the link or the car is holding the stream back. */
        const val SLOW_WRITE_MS = 50L
    }
    private val mutableControl = MutableStateFlow(ControlChannelStatus())
    val control = mutableControl.asStateFlow()
    private val mutableAux = MutableStateFlow("MEDIA IDLE • SENSOR IDLE")
    val auxiliary = mutableAux.asStateFlow()
    private val mutableRtsp = MutableStateFlow(WfdStatus())
    val rtsp = mutableRtsp.asStateFlow()
    private val mutableStream = MutableStateFlow(StreamStatus())
    val stream = mutableStream.asStateFlow()
    private val mutableTouch = MutableStateFlow(TouchStatus())
    val touch = mutableTouch.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    val quality = VideoQuality(context)
    @Volatile private var controlServer: SessionControlServer? = null
    @Volatile private var mediaServer: SessionControlServer? = null
    @Volatile private var sensorServer: SessionControlServer? = null
    @Volatile private var uibcServer: UibcServer? = null
    @Volatile private var source: WfdSource? = null
    @Volatile private var encoder: ScreenEncoder? = null
    @Volatile private var carDisplay: CarModeDisplay? = null
    @Volatile private var headunitBridge: HeadunitSurfaceBridge? = null
    @Volatile private var audioCapture: AudioCapture? = null
    @Volatile private var bridgeAudio: BridgeAudio? = null
    @Volatile private var datagrams: DatagramSocket? = null
    @Volatile private var localAddress: Inet4Address? = null
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    @Volatile private var uibcKey: ByteArray? = null
    @Volatile private var mediaStage = "IDLE"
    @Volatile private var sensorStage = "IDLE"
    @Volatile private var slowWrites = 0
    private var touchWarned = false
    private var launcherOpened = false
    val active get() = controlServer != null || source != null

    /** Called on the AUTH thread before AuthConfirm, so all listeners exist when the receiver connects. */
    fun onAuthenticated(local: Inet4Address, sessionKey: ByteArray) {
        stop(null)
        localAddress = local
        uibcKey = sessionKey.copyOf()
        touchWarned = false
        launcherOpened = false
        val logged = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        fun logFrame(label: String, frame: CarFrame) {
            if (logged.getOrPut(label) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() > MAX_LOGGED_FRAMES) return
            event("Channel $label received: category=${frame.category}; method=${frame.method} " +
                "(${SessionControlServer.methodName(frame.method)}); type=${frame.messageType}")
        }
        fun passive(port: Int, label: String, assign: (SessionControlServer) -> Unit) = try {
            SessionControlServer(local, sessionKey.copyOf(), port, active = false, onFrame = { logFrame(label, it) }) { status ->
                if (label == "MEDIA") mediaStage = status.stage else sensorStage = status.stage
                val text = "MEDIA $mediaStage • SENSOR $sensorStage"
                if (mutableAux.value != text) { mutableAux.value = text; event("Channel $label: ${status.stage}") }
            }.also { it.start(); assign(it) }
        } catch (_: java.io.IOException) { event("Channel $label: BIND_FAILED") }
        passive(SessionControlServer.MEDIA_PORT, "MEDIA") { mediaServer = it }
        passive(SessionControlServer.SENSOR_PORT, "SENSOR") { sensorServer = it }
        try {
            SessionControlServer(local, sessionKey, onFrame = { frame ->
                if (frame.category == UCarFrameWriter.CATEGORY_ACK ||
                    frame.category == UCarFrameWriter.CATEGORY_CONTROL && frame.method != SessionControlServer.METHOD_HEARTBEAT) logFrame("CONTROL", frame)
                if (frame.category == UCarFrameWriter.CATEGORY_CONTROL) when (frame.method) {
                    // Samsung lb/g.G: disconnect → notifyDisplayError(23); foreground → resumeDisplay.
                    SessionControlServer.METHOD_DISCONNECT -> main.post { stop(CAR_REQUESTED_DISCONNECT) }
                    SessionControlServer.METHOD_CAR_TO_FOREGROUND -> { encoder?.requestKeyframe(); carDisplay?.requestKeyframe(); main.post { openLauncherOnce() } }
                    // The car sends its own keys (steering wheel next/previous is the suspect) as CONTROL
                    // custom_key_event, not as UIBC keys; the payload is logged in SessionControlServer.
                    SessionControlServer.METHOD_CUSTOM_KEY_EVENT -> mutableTouch.value = mutableTouch.value.copy(customKeys = mutableTouch.value.customKeys + 1)
                }
            }, onSent = { event("CONTROL channel sent: $it") }, onEvent = { event(it) }, onStatus = { status ->
                if (mutableControl.value.stage != status.stage) event("CONTROL channel: ${status.stage}")
                if (mutableControl.value.carConfig != status.carConfig) event("Car configuration: ${status.carConfig}")
                mutableControl.value = status
            }).also { it.start(); controlServer = it }
        } catch (_: java.io.IOException) { sessionKey.fill(0); mutableControl.value = ControlChannelStatus("BIND_FAILED") }
        try {
            UibcServer(local, onEvent = ::onUibc, onStatus = { stage ->
                if (mutableTouch.value.stage != stage) event("UIBC touch: $stage")
                mutableTouch.value = mutableTouch.value.copy(stage = stage)
            }, decrypt = { data -> uibcKey?.let { key -> try { ExperimentalAuthCrypto.sessionOpen(key, data) } catch (_: Exception) { null } } })
                .also { it.start(); uibcServer = it }
        } catch (_: java.io.IOException) { mutableTouch.value = TouchStatus("BIND_FAILED") }
        try {
            WfdSource(local, onStatus = { status ->
                if (mutableRtsp.value.stage != status.stage) event("RTSP WFD: ${status.stage} ${status.detail}".trim())
                mutableRtsp.value = status
            }, onPlay = ::play, onIdrRequest = { encoder?.requestKeyframe(); carDisplay?.requestKeyframe() }, onStop = { reason -> stopStream("RTSP_$reason") }, onLog = event)
                .also { it.start(); source = it }
        } catch (_: java.io.IOException) { mutableRtsp.value = WfdStatus("BIND_FAILED") }
    }

    /** The car opened AionLink: show the owner's apps once, then leave the phone wherever the owner takes it. */
    private fun openLauncherOnce() {
        if (carDisplay != null || launcherOpened || !savings.launcher.value) return
        launcherOpened = true
        val service = TouchInjectionService.instance
        if (service == null) { event("Launcher not opened: the accessibility service is not active"); return }
        service.showCarButtons()
        service.openCarLauncher()
        event("Launcher opened on the phone")
    }

    private fun onUibc(input: UibcEvent) {
        val counted = mutableTouch.value.copy(events = mutableTouch.value.events + 1)
        mutableTouch.value = counted
        main.post {
            if (input is UibcEvent.Key) {
                // The car's steering wheel sends transport controls as UIBC keys. Until now every
                // key died here: the dashboard ignores them and the Lite bridge had no key path,
                // so next/previous did nothing in Android Auto. Log every key: the next car test
                // must show which key codes the wheel actually sends.
                mutableTouch.value = mutableTouch.value.copy(keys = mutableTouch.value.keys + 1)
                event("UIBC key: action=${input.action} keyCode=${input.keyCode}")
                if (UibcMediaKeys.isMedia(input.keyCode)) {
                    if (deliverMediaKey(input)) mutableTouch.value = mutableTouch.value.copy(injected = mutableTouch.value.injected + 1)
                    return@post
                }
            }
            // Car mode: the encoded frame is AionLink's own display, so touches go straight to its views.
            carDisplay?.let { car ->
                when (input) {
                    is UibcEvent.Key -> car.key(input.keyCode)
                    is UibcEvent.Touch -> input.pointers.firstOrNull()?.let { pointer ->
                        // The car declares the frame its touch coordinates are in; record it for the
                        // diagnostics export and hand it to the Lite bridge, which rescales from that
                        // frame to the Android Auto display size. Fall back to the encoded frame when
                        // the car declares none. Passing the encoded frame unconditionally used to
                        // mis-scale taps whenever the car negotiated a smaller video mode (e.g. 1280x720).
                        val frameWidth = input.width.takeIf { it > 0 } ?: videoWidth
                        val frameHeight = input.height.takeIf { it > 0 } ?: videoHeight
                        mutableTouch.value = mutableTouch.value.copy(frameWidth = input.width, frameHeight = input.height)
                        if (!car.isExternalContent()) {
                            car.touch(input.action, pointer.x.toFloat(), pointer.y.toFloat())
                            mutableTouch.value = mutableTouch.value.copy(injected = mutableTouch.value.injected + 1)
                        } else {
                            val sent = headunitBridge?.touch(input.action, input.pointers.map { it.id }.toIntArray(),
                                input.pointers.map { it.x }.toIntArray(), input.pointers.map { it.y }.toIntArray(), frameWidth, frameHeight)
                            mutableTouch.value = if (sent == true) mutableTouch.value.copy(injected = mutableTouch.value.injected + 1)
                                else mutableTouch.value.copy(stage = "TACTIL_PUENTE_SIN_CONEXION")
                        }
                    }
                }
                return@post
            }
            val service = TouchInjectionService.instance
            if (service == null) {
                mutableTouch.value = mutableTouch.value.copy(stage = "SIN_ACCESIBILIDAD")
                if (!touchWarned) { touchWarned = true; event("Touch: the car sends touches, but the accessibility service is not active") }
                return@post
            }
            when (input) {
                is UibcEvent.Key -> service.key(input)
                is UibcEvent.Touch -> {
                    val pointer = input.pointers.firstOrNull() ?: return@post
                    // The car reports coordinates in the negotiated video frame; fall back to its stated size.
                    val width = videoWidth.takeIf { it > 0 } ?: input.width
                    val height = videoHeight.takeIf { it > 0 } ?: input.height
                    val metrics = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
                    val mapped = UibcMapper.map(pointer.x, pointer.y, width, height, metrics.widthPixels, metrics.heightPixels)
                    if (mapped == null) { mutableTouch.value = mutableTouch.value.copy(outside = mutableTouch.value.outside + 1); return@post }
                    service.touch(input.action, mapped.first, mapped.second)
                    mutableTouch.value = mutableTouch.value.copy(injected = mutableTouch.value.injected + 1)
                }
            }
        }
    }

    /**
     * Steering-wheel transport control. Lite mode: over the bridge to Android Auto, which is
     * what a real head unit does (Lite's sendKey applies its own routing and debounce). Any
     * other mode: straight to the phone's active media session, which is the music app in
     * mirror mode and the dashboard's media card in car mode.
     */
    private fun deliverMediaKey(key: UibcEvent.Key): Boolean {
        if (key.action != KeyEvent.ACTION_DOWN && key.action != KeyEvent.ACTION_UP) return false
        val display = carDisplay
        if (display != null && display.isExternalContent()) {
            val sent = headunitBridge?.key(key.action, key.keyCode) == true
            if (!sent) event("UIBC key ${key.keyCode}: bridge not connected")
            return sent
        }
        return dispatchMediaKey(key)
    }

    private var lastMediaDownTime = 0L
    private var lastMediaKeyCode = -1

    private fun dispatchMediaKey(key: UibcEvent.Key): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val now = SystemClock.uptimeMillis()
        val downTime = when {
            key.action == KeyEvent.ACTION_DOWN -> { lastMediaDownTime = now; lastMediaKeyCode = key.keyCode; now }
            key.keyCode == lastMediaKeyCode && lastMediaDownTime != 0L -> lastMediaDownTime
            else -> now
        }
        audio.dispatchMediaKeyEvent(KeyEvent(downTime, now, key.action, key.keyCode, 0))
        return true
    }

    /** Manual fallback when the automatic start was blocked (for example, AionLink was not on screen). */
    fun showHeadunitOnCarDisplay(activity: Context): String {
        return if (headunitBridge != null) "The surface bridge was already requested; check the events"
        else "Select OpenHeadunit Lite before connecting and wait for video to start"
    }

    private fun play(play: WfdPlay) {
        val carMode = quality.carMode.value
        val externalHeadunit = carMode && quality.externalHeadunit.value
        // Car mode only needs a projection for optional audio capture; mirroring needs it for the image.
        val granted = ProjectionService.takeProjection()
        if (granted == null && !carMode) {
            mutableStream.value = StreamStatus("SIN_CAPTURA", "Tap 'Prepare screen capture' before connecting")
            event("Video not started: capture not prepared for this connection")
            return
        }
        try {
            val local = localAddress ?: return
            val audioPermitted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            // Open Headunit hands its decoded sound to AionLink through the bridge: no capture or permission needed,
            // and the phone speaker stays silent. Other sources use playback capture when it was prepared.
            val audioFromBridge = externalHeadunit && play.audioCodec == WfdFormats.AAC_48K_STEREO
            val withAudio = play.audioCodec == WfdFormats.AAC_48K_STEREO && (audioFromBridge || (audioPermitted && granted != null))
            val send: (ByteArray) -> Unit = play.rtpTcp?.let { tcpSender(it) } ?: run {
                val socket = DatagramSocket(null).apply { reuseAddress = true }
                try { socket.bind(InetSocketAddress(local, WfdSource.RTP_SERVER_PORT)) }
                catch (_: java.net.SocketException) { socket.bind(InetSocketAddress(local, 0)) }
                datagrams = socket
                val target = InetSocketAddress(play.sink, play.rtpPort)
                val udp: (ByteArray) -> Unit = { bytes ->
                    try { socket.send(DatagramPacket(bytes, bytes.size, target)) } catch (_: java.io.IOException) { }
                }
                udp
            }
            val streamer = TsRtpStreamer(send, audio = withAudio)
            val audioState = when {
                audioFromBridge -> "AAC_PUENTE_OPEN_HEADUNIT"
                withAudio -> "AAC"
                play.audioCodec == null -> "RECEPTOR_SIN_AUDIO"
                !audioPermitted -> "SIN_PERMISO_AUDIO"
                granted == null -> "POR_BLUETOOTH_DEL_COCHE"
                else -> "LPCM_NO_IMPLEMENTADO"
            }
            var last = 0L
            val startedAt = System.currentTimeMillis()
            slowWrites = 0
            val requested = WfdFormats.encodeTarget(play.mode, play.carDisplay, quality.high.value)
            val target = if (requested.source == "CEA" || ScreenEncoder.supports(requested.width, requested.height, requested.fps)) requested
                else WfdFormats.encodeTarget(play.mode, null, false).copy(source = "CEA_BY_ENCODER")
            // Car mode content is mostly static interface; it does not need the mirror's bitrate (see relayBitrate).
            // Exception: Android Auto through OpenHeadunit Lite carries a live UI with maps and text, so it
            // gets the full video bitrate instead of the relay bitrate (1920x960p30: 11 Mbps, not 3.9 Mbps).
            val carBitrate = if (externalHeadunit) target.bitrate else WfdFormats.relayBitrate(target.width, target.height, target.fps)
            val label = "${target.width}x${target.height}p${target.fps}; ${(if (carMode) carBitrate else target.bitrate) / 1000} kbps; source=${target.source}; " +
                if (externalHeadunit) "source=OPEN_HEADUNIT_EXPERIMENTAL" else if (carMode) "source=CAR_MODE" else "source=MIRROR"
            // Touch coordinates arrive in the frame the car displays, which is the encoded frame.
            videoWidth = target.width; videoHeight = target.height
            fun report() {
                val now = System.currentTimeMillis()
                if (now - last > 1000) {
                    last = now
                    mutableStream.value = StreamStatus("STREAMING", label,
                        streamer.accessUnits, streamer.datagramsSent, streamer.audioFrames, mutableStream.value.audio,
                        streamer.accessUnits * 1000 / (now - startedAt).coerceAtLeast(1), slowWrites)
                }
            }
            if (carMode) {
                CarModeService.start(context)
                main.post {
                    try {
                        CarModeDisplay(context, target.width, target.height, target.fps, carBitrate, onAccessUnit = { unit, ptsUs, key ->
                            streamer.onAccessUnit(unit, ptsUs, key); report()
                        }, onError = { error -> main.post { stopStream(error) } }, externalContent = externalHeadunit,
                            keepFullFpsWhenHot = quality.keepFullFpsWhenHot.value,
                            onNote = { note -> event(note) }).also { car ->
                            carDisplay = car; car.start()
                            car.externalInputSurface()?.let { surface ->
                                val sound = if (audioFromBridge) startBridgeAudio(streamer) else null
                                HeadunitSurfaceBridge(context, surface, sound) { result -> event("OpenHeadunit Lite: $result") }
                                    .also { bridge -> headunitBridge = bridge; bridge.start() }
                            }
                        }
                    } catch (error: Exception) { stopStream("CAR_START_${error.javaClass.simpleName}") }
                }
            } else ScreenEncoder(granted!!, target.width, target.height, target.fps, target.bitrate, onAccessUnit = { unit, ptsUs, key ->
                streamer.onAccessUnit(unit, ptsUs, key); report()
            }, onError = { error -> stopStream(error) }).also { encoder = it; it.start(context.resources.displayMetrics.densityDpi) }
            mutableStream.value = StreamStatus("STARTING", label, audio = audioState)
            if (withAudio && !audioFromBridge) try {
                AudioCapture(granted!!, { frame, ptsUs -> streamer.onAudioFrame(frame, ptsUs) }, { error ->
                    mutableStream.value = mutableStream.value.copy(audio = error); event("Audio stopped: $error")
                }, onLevel = { level -> logAudioLevel(level) }).also { audioCapture = it; it.start() }
            } catch (error: Exception) { mutableStream.value = mutableStream.value.copy(audio = "START_${error.javaClass.simpleName}") }
            event("Video started to the receiver: $label; M4=${play.mode.width}x${play.mode.height}p${play.mode.fps}; " +
                "car screen=${play.carDisplay?.let { "${it.width}x${it.height}@${it.fps}" } ?: "not reported"}; audio=$audioState; touch=${play.uibc}")
            if (!carMode) savings.onStreamStarted { streamer.accessUnits }
        } catch (error: Exception) { stopStream("START_${error.javaClass.simpleName}") }
    }

    /** The socket belongs to WfdSource; after the first write failure the stream stops and later packets are dropped. */
    private fun tcpSender(socket: java.net.Socket): (ByteArray) -> Unit {
        val output = socket.getOutputStream()
        var failed = false
        return { bytes ->
            if (!failed) try {
                val started = System.nanoTime()
                output.write(TsRtpStreamer.tcpFrame(bytes))
                if ((System.nanoTime() - started) / 1_000_000 >= SLOW_WRITE_MS) slowWrites++
            } catch (_: java.io.IOException) {
                failed = true
                main.post { stopStream("RTP_TCP_IO") }
            }
        }
    }

    private fun startBridgeAudio(streamer: TsRtpStreamer): BridgeAudio? = try {
        BridgeAudio({ frame, ptsUs -> streamer.onAudioFrame(frame, ptsUs) }, { error ->
            mutableStream.value = mutableStream.value.copy(audio = error); event("Bridge audio stopped: $error")
        }, onLevel = { level -> logAudioLevel(level) }).also { bridgeAudio = it; it.start() }
    } catch (error: Exception) {
        mutableStream.value = mutableStream.value.copy(audio = "PUENTE_${error.javaClass.simpleName}"); null
    }

    private var audioLevelsLogged = 0
    private var silentReports = 0

    /** Logs the first reports and any change between silence and sound, so a long session stays readable. */
    private fun logAudioLevel(level: Int) {
        val silent = level == 0
        val changed = silent != (silentReports > 0)
        silentReports = if (silent) silentReports + 1 else 0
        if (audioLevelsLogged < 6 || changed) {
            audioLevelsLogged++
            event(if (silent) "Captured audio: silence (nothing capturable playing)" else "Captured audio: peak=$level%")
        }
    }

    private fun stopStream(reason: String) {
        main.post { TouchInjectionService.instance?.hideCarButtons() }
        savings.onStreamStopped()
        audioCapture?.close(); audioCapture = null
        bridgeAudio?.close(); bridgeAudio = null
        audioLevelsLogged = 0; silentReports = 0
        encoder?.close(); encoder = null
        carDisplay?.let { car ->
            carDisplay = null
            // A session left open makes Android Auto's developer server fail the next one with "Already connected".
            val bridge = headunitBridge; headunitBridge = null
            main.post { bridge?.close(); car.close() }
        }
        CarModeService.stop(context)
        datagrams?.close(); datagrams = null
        if (mutableStream.value.stage != "IDLE") {
            mutableStream.value = mutableStream.value.copy(stage = "STOPPED", detail = reason)
            event("Video stopped: $reason")
        }
    }

    /** Stop only the video stream (encoder / car display / bridge), keeping the CONTROL, RTSP and UIBC
     * listeners and the session alive. Used when the owner switches projection mode mid-session: the new
     * mode is read fresh when the car next sends PLAY. Does not need a new PIN. */
    fun restartStreamForModeSwitch() {
        stopStream("MODE_SWITCH")
        event("Projection mode changed; the picture restarts when the car asks for video again")
    }

    fun stop(reason: String?) {
        stopStream(reason ?: "NUEVA_SESION")
        controlServer?.close(); controlServer = null
        mediaServer?.close(); mediaServer = null
        sensorServer?.close(); sensorServer = null
        uibcServer?.close(); uibcServer = null
        source?.close(); source = null
        uibcKey?.fill(0); uibcKey = null
        if (reason != null && mutableRtsp.value.stage != "IDLE") event("Projection session closed: $reason")
        // The car ended the session (drive over, CarLink closed): the app is not involved, so
        // this is the only place that can bring the watch back for the next drive.
        if (reason == CAR_REQUESTED_DISCONNECT) onSessionClosedByCar()
    }
}
