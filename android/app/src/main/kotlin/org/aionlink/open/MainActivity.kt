package org.aionlink.open

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aionlink.bluetooth.BluetoothDiscovery
import org.aionlink.bluetooth.GattInspector
import org.aionlink.bluetooth.BleNegotiator
import org.aionlink.iccoa.ClientInfo
import org.aionlink.iccoa.ExperimentalAuthWire
import org.aionlink.iccoa.ExperimentalQuickAuth
import org.aionlink.iccoa.ServerInfo
import org.aionlink.iccoa.AddressDisclosure
import org.aionlink.iccoa.NormalLinkReadiness
import org.aionlink.iccoa.normalLinkReadiness
import org.aionlink.wifi.LocalP2p
import org.aionlink.wifi.P2pConnector
import org.aionlink.wifi.NetworkStage
import org.aionlink.core.*
import org.aionlink.iccoa.ICCOAProtocolAdapter
import org.aionlink.iccoa.GattStage
import org.aionlink.vehicles.VehicleProfiles
import org.aionlink.usb.UsbAccessoryClient

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    val discovery = BluetoothDiscovery(application)
    val gatt = GattInspector(application)
    val bootstrap = BleNegotiator(application)
    val network = P2pConnector(application)
    val session = SessionCoordinator(ICCOAProtocolAdapter())
    private val mutableEvents = MutableStateFlow(listOf("Research client started. Compatibility unverified."))
    val events = mutableEvents.asStateFlow()
    val usb = UsbAccessoryClient(application, ::event)
    val savings = PhoneSavings(application, ::event)
    val projection = ProjectionSession(application, savings, ::event)
    private var displayProbe: OwnDisplayProbe? = null
    private val virtualProbeJournal = VirtualProbeJournal(application)
    private val probeEnd = Runnable { stopDisplayProbe() }
    private val probeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mutableProbeRunning = MutableStateFlow(false)
    val probeRunning = mutableProbeRunning.asStateFlow()
    /** Local test without the car: lets the owner lock the phone and read in the export whether frames continue. */
    fun startDisplayProbe() {
        if (displayProbe != null || VirtualProbeService.running.value) return
        if (projection.active || ProjectionService.captureReady) {
            event("Stop projection and capture before starting the local probe")
            return
        }
        // Long enough for several reports with the phone locked: a brief lock says little.
        try {
            displayProbe = OwnDisplayProbe(getApplication(), onReport = ::event)
            displayProbe?.start()
            mutableProbeRunning.value = true
            event("Local probe started for up to 11 minutes; turn off the screen and then export the diagnostics")
            probeHandler.postDelayed(probeEnd, 11 * 60_000L)
        } catch (error: RuntimeException) {
            displayProbe?.close(); displayProbe = null
            event("Own-display probe failed: ${error.javaClass.simpleName}")
        }
    }
    fun stopDisplayProbe() {
        stopOwnDisplayProbe()
        VirtualProbeService.stop(getApplication())
    }
    /** The activity-bound probe only; the foreground-service probe deliberately outlives the ViewModel. */
    private fun stopOwnDisplayProbe() {
        probeHandler.removeCallbacks(probeEnd)
        displayProbe?.let { event("Own-display probe finished; rendered=${it.framesRendered}; direct drawing=${it.framesDrawn}; frames to encoder=${it.framesSubmitted}; H264=${it.framesEncoded}; non-increasing H264 clock=${it.timestampRegressions}"); it.close() }
        displayProbe = null
        mutableProbeRunning.value = false
    }
    private var headunitDisplay: CarModeDisplay? = null
    private var headunitPreviewBridge: HeadunitSurfaceBridge? = null
    private var headunitPreviewAudio: BridgeAudio? = null
    private val mutableHeadunitPreview = MutableStateFlow(false)
    val headunitPreview = mutableHeadunitPreview.asStateFlow()

    // No probe of Android Auto's head-unit server (127.0.0.1:5277) here: the build with the probe
    // (ea2274d) was the first whose Lite handshake went silent, and the 11:32 build without it
    // still connects. A bare TCP connect looks like it wedges the server's single-client handler,
    // so the server is left alone; Lite itself warns when the server stops answering.

    /**
     * Without the car: Open Headunit on AionLink's 1920x960 virtual display, encoded into an in-memory TS/RTP sink.
     * Reports frames every 5 s so the owner (or ADB) can see whether Android Auto renders there, also with the screen off.
     */
    fun startHeadunitPreview(activity: android.content.Context) {
        if (headunitDisplay != null || projection.active) { event("Local Android Auto test not started: another session is active"); return }
        val units = java.util.concurrent.atomic.AtomicLong()
        val streamer = org.aionlink.projection.TsRtpStreamer({ }, audio = true)
        val peak = java.util.concurrent.atomic.AtomicInteger(-1)
        try {
            // Same bitrate as a car session in car mode, so the test measures what the car receives.
            val display = CarModeDisplay(getApplication(), 1920, 960, 30, org.aionlink.projection.WfdFormats.relayBitrate(1920, 960, 30),
                onAccessUnit = { unit, pts, key -> units.incrementAndGet(); synchronized(streamer) { streamer.onAccessUnit(unit, pts, key) } },
                onError = { error -> probeHandler.post { event("Android Auto test: error $error"); stopHeadunitPreview() } },
                externalContent = true, onNote = { note -> probeHandler.post { event("Android Auto test: $note") } })
            display.start()
            headunitDisplay = display
            mutableHeadunitPreview.value = true
            CarModeService.start(getApplication())
            val input = display.externalInputSurface() ?: error("Missing encoder surface")
            event("Local Android Auto test: surface bridge without virtual display")
            val sound = try {
                BridgeAudio({ frame, pts -> synchronized(streamer) { streamer.onAudioFrame(frame, pts) } },
                    { error -> probeHandler.post { event("Android Auto test: bridge audio stopped: $error") } },
                    onLevel = { peak.set(it) }).also { headunitPreviewAudio = it; it.start() }
            } catch (error: Exception) { event("Android Auto test: bridge audio not started: ${error.javaClass.simpleName}"); null }
            HeadunitSurfaceBridge(getApplication(), input, sound) { result -> event("Android Auto test: $result") }
                .also { headunitPreviewBridge = it; it.start() }
            var last = 0L
            val tick = object : Runnable {
                override fun run() {
                    if (headunitDisplay !== display) return
                    val now = units.get()
                    val power = getApplication<Application>().getSystemService(android.os.PowerManager::class.java)
                    event("Android Auto test: H264 in 5 s=${now - last}; total=$now; relay frames=${display.relayedFrames}; datagrams=${streamer.datagramsSent}; interactive screen=${power.isInteractive}; " +
                        "bridge audio=${(headunitPreviewAudio?.bytesReceived ?: 0) / 1024} KiB, AAC=${streamer.audioFrames}, peak=${peak.get()}%")
                    last = now
                    probeHandler.postDelayed(this, 5000)
                }
            }
            probeHandler.postDelayed(tick, 5000)
        } catch (error: Exception) {
            headunitPreviewAudio?.close(); headunitPreviewAudio = null
            headunitDisplay?.close(); headunitDisplay = null; mutableHeadunitPreview.value = false
            event("Local Android Auto test failed: ${error.javaClass.simpleName}")
        }
    }

    fun stopHeadunitPreview() {
        headunitPreviewBridge?.close(); headunitPreviewBridge = null
        headunitPreviewAudio?.close(); headunitPreviewAudio = null
        headunitDisplay?.close()
        headunitDisplay = null
        if (mutableHeadunitPreview.value) { CarModeService.stop(getApplication()); event("Local Android Auto test stopped") }
        mutableHeadunitPreview.value = false
    }

    /** The virtual-display probe lives in a foreground service so it keeps running with the screen off. */
    fun startVirtualDisplayProbe() {
        if (displayProbe != null || VirtualProbeService.running.value) return
        if (projection.active || ProjectionService.captureReady) {
            event("Stop projection and capture before starting the local probe")
            return
        }
        VirtualProbeService.onReport = { message -> probeHandler.post { event(message) } }
        VirtualProbeService.start(getApplication())
        event("Virtual display probe 1920x960 started in a foreground service; up to 12 minutes; not DeX and does not stream to the car")
    }
    fun captureGranted(resultCode: Int, data: android.content.Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) { event("Screen capture not authorized"); return }
        ProjectionService.start(getApplication(), resultCode, data)
        event("Screen capture authorized for this session; will be used after the receiver sends PLAY")
    }
    fun event(message: String) {
        // Events are already anonymized for export; mirroring them to the local log lets ADB follow a test.
        android.util.Log.i("AionLink", message)
        val next = mutableEvents.value + "${java.time.Instant.now()} $message"
        droppedEvents += (next.size - 500).coerceAtLeast(0)
        mutableEvents.value = next.takeLast(500)
    }
    private val exportCapture = DiagnosticExport()
    private val mutablePinRequest = MutableStateFlow<String?>(null)
    val pinRequest = mutablePinRequest.asStateFlow()
    private val mutableCodeReadiness = MutableStateFlow(CodeReadiness.IDLE)
    val codeReadiness = mutableCodeReadiness.asStateFlow()
    private val runId = java.util.UUID.randomUUID().toString().take(8)
    private var droppedEvents = 0
    private val mutableConnectionNotice = MutableStateFlow("Check the requirements before entering a code")
    val connectionNotice = mutableConnectionNotice.asStateFlow()
    private class Prepared(val peerId: String, val local: LocalP2p)
    private var prepared: Prepared? = null
    private var attemptNumber = 0
    /** Set when the owner taps the car-found notification: the next ICCOA candidate is prepared
     * automatically and the code dialog opens with automatic AUTH pre-checked. */
    var autoConnectPending = false
    var autoAuthDefault = false
    fun onAutoConnectIntent() {
        if (projection.active || mutablePinRequest.value != null) return
        autoConnectPending = true
        autoAuthDefault = true
        if (discovery.hasPermissions()) scan()
        else {
            autoConnectPending = false
            autoAuthDefault = false
            event("Bluetooth permissions missing; find the car manually")
        }
    }

    /** One choice for the projection mode. Before PLAY it applies to the upcoming video; while streaming
     * it stops the picture and the new mode applies when the car next sends PLAY. Never needs a new PIN. */
    fun switchProjectionMode(mode: ProjectionMode) {
        if (projection.quality.mode() == mode) return
        val wasActive = projection.active
        projection.quality.setMode(mode)
        if (wasActive) projection.restartStreamForModeSwitch()
        else event("Projection mode set to ${mode.name}; applies to the next connection")
    }
    private val pendingAutoPin = PendingAuthCode()
    /** QUICK reconnect state for the current attempt only; the peer record itself lives in
     * QuickConnectStore and survives restarts. Cleared whenever the attempt is abandoned. */
    private val quickStore by lazy { QuickConnectStore(getApplication()) }
    private var quickServerId: String? = null
    private var quickPeerId: String? = null
    private var quickPending = false
    private var quickAttemptActive = false
    private var quickServerInfo: ServerInfo? = null
    private fun clearQuickState() {
        quickPending = false; quickAttemptActive = false
        quickServerId = null; quickPeerId = null; quickServerInfo = null
    }
    private val autoAuthExpiry = Runnable { clearAutoAuth("Automatic AUTH expired before connecting") }
    private fun clearAutoAuth(reason: String? = null) {
        prepareHandler.removeCallbacks(autoAuthExpiry)
        if (!pendingAutoPin.clear()) return
        if (reason != null) event(reason)
    }
    private val prepareHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val backgroundExpiry = Runnable { stopAll("Maximum background link time exhausted (50 s)") }
    private val prepareExpiry = Runnable { cancelPrepared("Preparation for entering the code expired") }
    private fun operationBusy() = bootstrap.state.value.busy || network.state.value.busy || usb.state.value.busy ||
        network.authProbe.value.busy || network.authExchange.value.busy ||
        gatt.state.value.stage in setOf(GattStage.CONNECTING, GattStage.LINK_CONNECTED, GattStage.DISCOVERING_SERVICES)
    fun captureExport(): Boolean {
        if (operationBusy()) { event("Export postponed: wait for the result or tap Stop"); return false }
        event("Export requested: snapshot before the Android picker")
        exportCapture.capture { export() }
        return true
    }
    fun consumeExport() = exportCapture.consume()
    fun scan(iccoaOnly: Boolean = true) {
        AutoConnectService.stop(getApplication())
        clearAutoAuth("Automatic AUTH cancelled by new search"); cancelPrepared(); gatt.cancel(); bootstrap.cancel("New search"); network.stop("New search")
        discovery.stop()
        session.startDiscovery(); event("Modo: ${if (iccoaOnly) "ICCOA FCFB" else "general Classic/BLE"}"); event("Bluetooth discovery requested by the user")
        discovery.start(iccoaOnly) { error ->
            if (error != null) {
                session.transportError(); event(error)
                autoConnectPending = false; autoAuthDefault = false
            } else {
                session.discoveryFinished()
                event("UUID candidates: ${discovery.devices.value.count { it.recognizedServices.isNotEmpty() }}")
                event("Discovery finished: ${discovery.devices.value.size} observations")
                // A tap on the car-found notification sets autoConnectPending and starts this
                // scan; if the scan ends with no car in range, say so instead of going quiet.
                if (autoConnectPending && discovery.devices.value.none { it.recognizedServices.isNotEmpty() }) {
                    autoConnectPending = false; autoAuthDefault = false
                    event("Car not found in this scan; tap Find my car to try again")
                }
            }
        }
    }
    fun stop() {
        stopAll("Stopped by the user")
        if (projection.quality.autoWatch.value) AutoConnectService.start(getApplication())
    }
    private fun stopAll(reason: String) {
        prepareHandler.removeCallbacks(backgroundExpiry)
        clearAutoAuth("Automatic AUTH cancelled: $reason")
        cancelPrepared(reason); discovery.stop(); gatt.cancel(); bootstrap.cancel(reason); network.stop(reason)
        projection.stop(reason)
        usb.stop(); session.disconnect(); event(reason)
    }
    fun inspect(peerId: String) {
        val peer = discovery.observedBleDevice(peerId)
        if (peer == null) { event("Device unavailable; run a new search"); return }
        discovery.stop()
        event("GATT connection requested to temporary ID $peerId")
        gatt.inspect(peerId, peer) { result ->
            if (result.stage == GattStage.INSPECTED) {
                event("GATT checked and closed: services=${result.serviceCount}; UnionShare=${result.unionSharePresent}; no ICCOA session")
            } else event("GATT inspection: ${result.stage}; ${result.error ?: "no detail"}")
        }
    }
    fun prepareConnection(peerId: String, readOnly: Boolean = false, skipQuick: Boolean = false) {
        if (discovery.observedBleDevice(peerId) == null) { event("Receiver unavailable; search again"); return }
        cancelPrepared(); discovery.stop(); gatt.cancel(); bootstrap.reset()
        attemptNumber++; event("Local attempt #$attemptNumber; mode=${if (readOnly) "READ_ONLY_NO_PIN" else "NORMAL_CLIENT_INFO"}")
        event(if (readOnly) "Read query requested: does not use a PIN" else "Local check before requesting a PIN")
        mutableConnectionNotice.value = "Checking local Wi-Fi Direct..."
        if (!readOnly) {
            mutableCodeReadiness.value = CodeReadiness.PREPARING
            mutablePinRequest.value = peerId
        }
        network.prepare { local ->
            if (readOnly) {
                mutableConnectionNotice.value = "Diagnostic query without PIN or ClientInfo; not authentication"
                val peer = discovery.observedBleDevice(peerId)
                if (peer == null) { network.stop(); return@prepare }
                bootstrap.readAvailableServerInfo(peer) { info ->
                    event("ServerInfo obtained via READ; network=${info.mode}; port=${info.portStatus}; range=${info.portRange}; no ICCOA session")
                    network.connect(info)
                }
            } else if (local.platformAddress.disclosure == AddressDisclosure.UNAVAILABLE) {
                val message = "Android did not return usable local P2P data. None will be fabricated, and the key will not be sent without that data."
                mutableCodeReadiness.value = CodeReadiness.BLOCKED
                mutableConnectionNotice.value = message; event(message); network.stop("Local requirement unavailable")
            } else {
                quickServerId = try { discovery.observedBleDevice(peerId)?.address } catch (_: SecurityException) { null }
                val record = if (!skipQuick) quickServerId?.let { quickStore.peerRecord(it) } else null
                // A saved key is enough when the local P2P data is usable at all: Android 17 anonymizes
                // the local address (02:00:00:00:00:00), so AVAILABLE is never true on this phone and the
                // shortcut never fired. The QUICK bootstrap accepts the anonymized identity with consent,
                // exactly like the normal exchange; UNAVAILABLE already blocks the whole connection above.
                if (record != null && local.platformAddress.disclosure != AddressDisclosure.UNAVAILABLE) {
                    // Saved key for this car: connect without the code. Any failure falls back to the dialog.
                    quickPeerId = peerId
                    quickPending = true
                    mutableCodeReadiness.value = CodeReadiness.PREPARING
                    mutableConnectionNotice.value = "Saved key found for this car; connecting without the code"
                    event("Quick connect: saved key found for this car; trying without the code")
                    startQuickBootstrap(peerId, local, record)
                } else {
                    prepared = Prepared(peerId, local)
                    val anonymous = local.platformAddress.disclosure == AddressDisclosure.ANDROID_ANONYMIZED
                    mutableCodeReadiness.value = if (anonymous) CodeReadiness.ANONYMIZED_EXPERIMENT else CodeReadiness.READY
                    mutablePinRequest.value = peerId
                    mutableConnectionNotice.value = if (anonymous)
                        ANONYMIZED_EXPERIMENT_DETAIL
                        else "Local requirements available. Enter your receiver's code"
                    event("Local metadata: ${local.platformAddress.disclosure}; valor omitido")
                    prepareHandler.postDelayed(prepareExpiry, 60000)
                }
            }
        }
    }
    fun cancelPrepared(reason: String = "Code entry cancelled") {
        prepareHandler.removeCallbacks(prepareExpiry)
        autoAuthDefault = false
        clearQuickState()
        val existed = prepared != null
        val hadDialog = mutablePinRequest.value != null
        val wasPreparing = mutableCodeReadiness.value == CodeReadiness.PREPARING
        prepared = null; mutablePinRequest.value = null; mutableCodeReadiness.value = CodeReadiness.IDLE
        if (existed || hadDialog) network.stop(reason)
        if (existed || wasPreparing) { mutableConnectionNotice.value = reason; event(reason) }
    }
    /** QUICK reconnect: the BLE exchange carries the stored authentication token instead of a PIN,
     * exactly as Xiaomi's client does; the TCP AUTH that follows proves the stored key. */
    private fun startQuickBootstrap(peerId: String, local: LocalP2p, record: QuickConnectStore.PeerRecord) {
        val peer = discovery.observedBleDevice(peerId)
        if (peer == null) { fallbackToPin(peerId); return }
        try {
            val preferences = getApplication<Application>().getSharedPreferences("own-client", 0)
            val id = preferences.getString("id", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(6).also {
                preferences.edit().putString("id", it).apply()
            }
            // The saved key only exists because the owner already consented to the anonymized experiment
            // in the PIN connection that stored it, so QUICK reuses that consent; passing false made the
            // ClientInfo constructor reject the anonymized identity before any BLE exchange.
            val hello = ClientInfo(id, Build.MODEL, "000000", local.platformAddress, local.supports5Ghz, true, record.authentication)
            mutableConnectionNotice.value = "Trying saved key; waiting for the car to answer"
            bootstrap.start(peer, hello) { info ->
                quickServerInfo = info
                mutableConnectionNotice.value = "BLE response received; network ${info.mode}; port=${info.portStatus}; quick authentication pending"
                event("ServerInfo received for quick connect; requesting network ${info.mode}; port=${info.portStatus}; range=${info.portRange}")
                network.connect(info)
            }
        } catch (e: IllegalArgumentException) {
            event("Quick connect failed before contacting the car: ${e.message ?: "invalid local data"}")
            fallbackToPin(peerId)
        }
    }
    /** Quick connect did not work out; ask for the code exactly as before. The P2P group is torn down
     * first so the retry prepares from the same state as a fresh attempt. */
    private fun fallbackToPin(peerId: String) {
        clearQuickState()
        event("Quick connect unavailable; asking for the code")
        network.stop("Quick connect failed; asking for the code")
        prepareConnection(peerId, skipQuick = true)
    }
    /** Starts the QUICK AUTH once the P2P group is up. Any problem here falls back to the code dialog. */
    private fun startQuickAuth() {
        val peerId = quickPeerId
        val serverId = quickServerId
        if (peerId == null || serverId == null) {
            clearQuickState()
            event("Quick connect unavailable; asking for the code")
            if (peerId != null) fallbackToPin(peerId)
            return
        }
        val record = quickStore.peerRecord(serverId)
        val keyPair = quickStore.authKeyPair(serverId)
        val preferences = getApplication<Application>().getSharedPreferences("own-client", 0)
        val id = preferences.getString("id", null)
        if (record == null || keyPair == null || id == null) {
            fallbackToPin(peerId)
            return
        }
        if (network.authExchange.value.busy || network.authProbe.value.busy) {
            // Another check owns the AUTH channel; leave it alone. The saved key stays for next time.
            clearQuickState()
            mutableConnectionNotice.value = "Quick connect skipped: another check is running"
            event("Quick connect skipped: another authentication or port check is running")
            return
        }
        try {
            val quick = ExperimentalQuickAuth(keyPair, record.serverAuthPoint, id.toByteArray(Charsets.US_ASCII), Build.MODEL, ExperimentalAuthWire.CLIENT_WORK_MODES)
            quickAttemptActive = true
            network.startQuickAuthExchange(quick, id.toByteArray(Charsets.US_ASCII), Build.MODEL)
        } catch (_: Exception) {
            fallbackToPin(peerId)
        }
    }
    fun submitPin(pin: String, consentToAnonymized: Boolean, automaticAuth: Boolean) {
        val request = prepared ?: return
        AutoConnectService.stop(getApplication())
        autoAuthDefault = false
        // Without capture the car receives no video and closes the session after about 10 s, showing an error.
        if (automaticAuth && !ProjectionService.captureReady && !projection.quality.carMode.value) {
            cancelPrepared(if (displayProbe != null || VirtualProbeService.running.value)
                "Connection not started: a local test is running. Stop it, tap 'Prepare screen and audio capture', and reconnect"
                else "Connection not started: tap 'Prepare screen and audio capture' before each connection. Nothing was sent to the car")
            return
        }
        val disclosure = request.local.platformAddress.disclosure
        val permitted = disclosure == AddressDisclosure.AVAILABLE && mutableCodeReadiness.value == CodeReadiness.READY ||
            disclosure == AddressDisclosure.ANDROID_ANONYMIZED && mutableCodeReadiness.value == CodeReadiness.ANONYMIZED_EXPERIMENT && consentToAnonymized
        if (!permitted || network.state.value.stage != NetworkStage.READY) {
            cancelPrepared("Network preparation is no longer available; code discarded")
            return
        }
        prepareHandler.removeCallbacks(prepareExpiry)
        prepared = null; mutablePinRequest.value = null; mutableCodeReadiness.value = CodeReadiness.IDLE
        val peer = discovery.observedBleDevice(request.peerId)
        if (peer == null || disclosure == AddressDisclosure.UNAVAILABLE) { network.stop(); return }
        quickServerId = try { peer.address } catch (_: SecurityException) { null }
        try {
            val preferences = getApplication<Application>().getSharedPreferences("own-client", 0)
            val id = preferences.getString("id", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(6).also {
                preferences.edit().putString("id", it).apply()
            }
            val hello = ClientInfo(id, Build.MODEL, pin, request.local.platformAddress, request.local.supports5Ghz, consentToAnonymized)
            mutableConnectionNotice.value = "Trying normal ClientInfo; waiting for write confirmation"
            event("Local identity=$disclosure; experimental consent=$consentToAnonymized; no address value")
            event("PIN handed to the normal exchange; value omitted, sending not yet confirmed")
            if (automaticAuth) {
                clearAutoAuth()
                pendingAutoPin.set(pin.toByteArray(Charsets.US_ASCII))
                prepareHandler.postDelayed(autoAuthExpiry, 60000)
                event("Automatic AUTH authorized for this attempt; waiting for P2P network; code omitted")
            }
            bootstrap.start(peer, hello) { info ->
                quickServerInfo = info
                mutableConnectionNotice.value = "BLE response received; network ${info.mode}; port=${info.portStatus}; authentication pending"
                event("ServerInfo received after ClientInfo; requesting network ${info.mode}; port=${info.portStatus}; range=${info.portRange}")
                network.connect(info)
            }
        } catch (_: IllegalArgumentException) {
            clearAutoAuth("Automatic AUTH cancelled due to invalid data")
            network.stop(); mutableConnectionNotice.value = "Invalid ClientInfo; no data sent"
            event(mutableConnectionNotice.value)
        }
    }
    fun resumed() {
        prepareHandler.removeCallbacks(backgroundExpiry)
        projection.quality.refreshAutoWatch()
    }
    fun background() {
        if (projection.active) {
            discovery.stop(); gatt.cancel(); usb.stop()
            event("Projection session active; kept in the background until Stop")
        } else if (preserveBackgroundHandoff(bootstrap.state.value.busy, network.systemWifiHandoff)) {
            discovery.stop(); gatt.cancel(); usb.stop()
            event("BLE link or network in progress; kept up to 50 s in the background, no ICCOA session")
            prepareHandler.removeCallbacks(backgroundExpiry)
            prepareHandler.postDelayed(backgroundExpiry, 50000)
        } else stopAll("Activity in background (may include file picker or app switch)")
    }
    fun denied() { session.permissionDenied(); event("Discovery permissions denied") }
    fun sendExperimentalAuth(pin: String) = sendExperimentalAuthBytes(pin.toByteArray(Charsets.US_ASCII))
    private fun sendExperimentalAuthBytes(pin: ByteArray) {
        val preferences = getApplication<Application>().getSharedPreferences("own-client", 0)
        val id = preferences.getString("id", null)
        if (id == null) { pin.fill(0); event("No local identity for the attempt; AUTH request not started"); return }
        // A normal AUTH with the stored key lets the next connection skip the code. Without a stored
        // key, or when the Keystore fails, this behaves exactly as before.
        val serverId = quickServerId
        val persistentPair = serverId?.let { quickStore.authKeyPair(it) }
        val serverInfo = quickServerInfo
        network.startAuthExchange(pin, id.toByteArray(Charsets.US_ASCII), Build.MODEL,
            persistentAuthPair = persistentPair,
            onVerifiedPeer = if (serverId != null && persistentPair != null) { peerPoint ->
                quickStore.savePeerRecord(serverId, peerPoint, serverInfo?.authentication, serverInfo?.mac)
                prepareHandler.post { event("Saved key stored for this car; the next connection can skip the code") }
            } else null)
    }
    fun export(): String = buildString {
        appendLine("AionLink Probe " + run {
            try { getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0).versionName.orEmpty() }
            catch (_: Exception) { "unknown" }
        })
        appendLine("Snapshot UTC: ${java.time.Instant.now()}; captured before the file picker")
        appendLine("Android API ${Build.VERSION.SDK_INT}")
        appendLine("State: ${session.state.value}")
        appendLine("Permissions: ${discovery.hasPermissions()}")
        appendLine("Search active: ${discovery.isScanning.value}")
        appendLine("ICCOA: no verified session")
        appendLine("Current observations: ${discovery.devices.value.size} (not equivalent to unique devices)")
        appendLine("Local process: $runId; events=${mutableEvents.value.size}; earlier events omitted=$droppedEvents")
        appendLine("Form: ${mutableCodeReadiness.value}; request visible=${mutablePinRequest.value != null}; key value omitted")
        appendLine("Local attempt: $attemptNumber")
        appendLine("UUID candidates: ${discovery.devices.value.count { it.recognizedServices.isNotEmpty() }}")
        discovery.devices.value.forEach { peer ->
            appendLine("Temporary ID=${peer.id}; transport=${peer.transport}; RSSI=${peer.rssi}; connectable=${peer.connectable}; advertised services=${peer.observedServiceCount}")
            appendLine("Static UUID hints=${peer.recognizedServices.sorted().joinToString().ifEmpty { "none" }}; compatibility=UNKNOWN")
        }
        appendLine("No names, MAC, PIN, or raw advertisement data are included.")
        val inspection = gatt.state.value
        appendLine("Last GATT inspection: ${inspection.stage}; Temporary ID=${inspection.peerId}; services=${inspection.serviceCount}; UnionShare=${inspection.unionSharePresent}; code=${inspection.statusCode}")
        inspection.knownCharacteristics.forEach {
            appendLine("Known characteristic=${it.uuid}; properties=${it.properties}; no value read")
        }
        appendLine("GATT result is not equivalent to an ICCOA session; the inspection link closes when done.")
        val negotiation = bootstrap.state.value
        appendLine("BLE exchange: ${negotiation.stage}; MTU=${negotiation.mtu}; sent=${negotiation.bytesSent}; received=${negotiation.bytesReceived}")
        appendLine("Exchange mode=${negotiation.mode}; write confirmed=${negotiation.writeConfirmed}")
        appendLine("Exchange detail=${negotiation.message}")
        appendLine("Preparation: ${mutableConnectionNotice.value}")
        appendLine("Network: ${network.state.value.stage}; mode=${network.state.value.mode}; ${network.state.value.message}")
        appendLine("TCP AUTH test: ${network.authProbe.value.stage}; result=${network.authProbe.value.detail}; duration ms=${network.authProbe.value.elapsedMs}; no payload")
        appendLine("TCP AUTH tests in this process: ${network.authProbeCount}; last result kept=${network.lastAuthProbe?.detail ?: "none"}; duration ms=${network.lastAuthProbe?.elapsedMs}")
        appendLine("Automatic AUTH pending: ${pendingAutoPin.pending}; this attempt only; code omitted")
        appendLine("Experimental AUTH request: ${network.authExchange.value.stage}; ${network.authExchange.value.detail}; session not confirmed")
        appendLine("PIN, MAC, SSID, PSK, and ServerInfo content omitted. A connected network does not imply ICCOA authentication.")
        val control = projection.control.value
        appendLine("CONTROL channel: ${control.stage}; received=${control.received}; heartbeats received=${control.heartbeatsReceived}; sent=${control.heartbeatsSent}; ACK=${control.acksSent}; decryption failures=${control.decryptFailures}; category*1000+method=${control.lastMethods}")
        appendLine("Car configuration: ${control.carConfig}")
        val rtsp = projection.rtsp.value
        appendLine("RTSP WFD: ${rtsp.stage}; ${rtsp.detail}")
        appendLine("Receiver wfd_video_formats=${rtsp.sinkVideoFormats}")
        appendLine("Receiver wfd_client_rtp_ports=${rtsp.sinkRtpPorts}")
        appendLine("Receiver other M3 parameters=${rtsp.sinkOther}")
        appendLine("Receiver wfd_audio_codecs=${rtsp.sinkAudioCodecs}; wfd_car_display_mode=${rtsp.carDisplayMode}")
        appendLine("Receiver wfd_uibc_capability=${rtsp.sinkUibc}")
        appendLine(projection.auxiliary.value)
        val video = projection.stream.value
        appendLine("Projection mode: ${if (projection.quality.carMode.value) "CAR MODE (own panel, screen off)" else "MIRROR"}; " +
            "music/navigation access=${CarInfoListener.granted(getApplication())}; Android Auto receiver=${OpenHeadunit.label(getApplication())}")
        appendLine("Default mode: ${projection.quality.mode()}; auto-connect watch: ${if (projection.quality.autoWatch.value) "ON" else "OFF"}; keep 30 fps when hot: ${if (projection.quality.keepFullFpsWhenHot.value) "ON" else "OFF"}")
        appendLine("Quick connect: saved keys=${quickStore.peerCount()}")
        appendLine("Chosen image quality: ${if (projection.quality.high.value) "HIGH (car screen resolution)" else "COMPATIBLE (1280x720)"}")
        appendLine("Video: ${video.stage}; ${video.detail}; units=${video.accessUnits}; RTP datagrams=${video.datagrams}; capture ready=${ProjectionService.projection != null}; frames/s=${video.fps}; slow writes=${video.slowWrites}")
        val captured = ProjectionService.observation.value
        appendLine("Android capture: stage=${captured.stage}; size=${captured.width ?: "unknown"}x${captured.height ?: "unknown"}; visible=${captured.visible ?: "unknown"}; does not identify the app")
        appendLine("Launcher: enabled=${savings.launcher.value}; landscape=${savings.landscape.value}; chosen apps=${CarLauncherActivity.chosenCount(getApplication())}")
        appendLine("Savings: minimum brightness=${savings.enabled.value}; settings permission=${savings.canWrite}; battery: ${savings.summary()}")
        appendLine("Audio: ${video.audio}; AAC frames=${video.audioFrames}")
        val touch = projection.touch.value
        appendLine("UIBC touch: ${touch.stage}; events=${touch.events}; keys=${touch.keys}; car keys=${touch.customKeys}; applied=${touch.injected}; outside image=${touch.outside}; declared touch frame=${if (touch.frameWidth > 0 && touch.frameHeight > 0) "${touch.frameWidth}x${touch.frameHeight}" else "not declared"}; accessibility active=${TouchInjectionService.instance != null}; no coordinates")
        val cable = usb.state.value
        appendLine("USB: stage=${cable.stage}; accessories=${cable.accessories.size}; host-mode devices=${cable.hostDevices}; reported USB power=${cable.usbPower}")
        cable.accessories.forEach { appendLine("Temporary accessory=${it.id}; observed ICCOA identifier=${it.observedIccoa}; permission=${it.permission}") }
        appendLine("USB received=${cable.bytesRead}; frames=${cable.frames}; experimental requests sent=${cable.requestsSent}; known ports=${cable.knownPorts.sorted()}")
        appendLine("USB detail=${cable.message ?: "no incidents recorded"}")
        appendLine("No USB serial or payload is included. An open USB does not imply an ICCOA session.")
        appendLine(virtualProbeJournal.snapshot())
        append(events.value.joinToString("\n"))
    }
    init {
        savings.restorePending()
        viewModelScope.launch { bootstrap.state.collect {
            event("BLE exchange: ${it.stage}; ${it.message}")
            if (it.stage in setOf(org.aionlink.bluetooth.BootstrapStage.ERROR, org.aionlink.bluetooth.BootstrapStage.CANCELLED) && bootstrap.state.value == it) {
                clearAutoAuth("Automatic AUTH cancelled because the BLE exchange ended")
                if (quickPending) {
                    val peerId = quickPeerId
                    clearQuickState()
                    if (peerId != null) fallbackToPin(peerId)
                }
                if (network.state.value.stage == NetworkStage.READY) network.stop()
            }
        } }
        viewModelScope.launch { network.state.collect {
            event("Network: ${it.stage}; ${it.message}")
            if (network.state.value == it && quickPending) {
                if (it.stage == NetworkStage.CONNECTED && it.mode == org.aionlink.iccoa.NetworkMode.P2P) {
                    quickPending = false
                    event("P2P network ready; starting quick reconnect for attempt #$attemptNumber")
                    startQuickAuth()
                } else if (it.stage in setOf(NetworkStage.ERROR, NetworkStage.CANCELLED) ||
                    (it.stage == NetworkStage.CONNECTED && it.mode != org.aionlink.iccoa.NetworkMode.P2P)) {
                    val peerId = quickPeerId
                    clearQuickState()
                    if (peerId != null) fallbackToPin(peerId)
                    else event("Quick connect cancelled: P2P network unavailable")
                }
            }
            if (network.state.value == it && pendingAutoPin.pending) {
                if (it.stage == NetworkStage.CONNECTED && it.mode == org.aionlink.iccoa.NetworkMode.P2P) {
                    val pin = pendingAutoPin.take() ?: return@collect
                    prepareHandler.removeCallbacks(autoAuthExpiry)
                    event("P2P network ready; starting authorized automatic AUTH for attempt #$attemptNumber")
                    sendExperimentalAuthBytes(pin)
                } else if (it.stage in setOf(NetworkStage.ERROR, NetworkStage.CANCELLED) ||
                    (it.stage == NetworkStage.CONNECTED && it.mode != org.aionlink.iccoa.NetworkMode.P2P)) {
                    clearAutoAuth("Automatic AUTH cancelled: P2P network unavailable")
                }
            }
            if (it.stage == NetworkStage.ERROR && mutablePinRequest.value != null) {
                prepareHandler.removeCallbacks(prepareExpiry); prepared = null
                mutableCodeReadiness.value = CodeReadiness.BLOCKED
                mutableConnectionNotice.value = it.message
            }
        } }
        network.onSessionAuthenticated = { local, key -> projection.onAuthenticated(local, key) }
        viewModelScope.launch { network.authProbe.collect {
            event("TCP AUTH test: ${it.stage}; ${it.detail}; duration ms=${it.elapsedMs}; no payload or ICCOA session")
        } }
        viewModelScope.launch { network.authExchange.collect {
            event("Experimental AUTH request: ${it.stage}; ${it.detail}; session not confirmed")
            if (quickAttemptActive && it.stage == org.aionlink.wifi.AuthProbeStage.FINISHED) {
                quickAttemptActive = false
                if (!it.detail.contains("QUICK_RESPONSE_VERIFIED")) {
                    val peerId = quickPeerId
                    val serverId = quickServerId
                    val rejected = it.detail.contains("QUICK_RESPONSE_REJECTED_TRY_PIN") ||
                        it.detail.contains("QUICK_RESPONSE_SERVER_ERROR") ||
                        it.detail.contains("QUICK_RESPONSE_BAD_SIGNATURE")
                    if (rejected && serverId != null) {
                        quickStore.deletePeerRecord(serverId); quickStore.deleteAuthKey(serverId)
                        event("Quick connect rejected by the car; saved key removed")
                    } else {
                        event("Quick connect did not succeed; asking for the code")
                    }
                    clearQuickState()
                    if (peerId != null) fallbackToPin(peerId)
                } else {
                    val serverId = quickServerId
                    val info = quickServerInfo
                    if (serverId != null && info?.authentication != null) {
                        quickStore.peerRecord(serverId)?.let { record ->
                            quickStore.savePeerRecord(serverId, record.serverAuthPoint, info.authentication, info.mac)
                        }
                    }
                    clearQuickState()
                    event("Quick connect succeeded; no code needed")
                }
            }
        } }
        viewModelScope.launch { discovery.devices.collect { devices ->
        if (devices.isNotEmpty() && session.state.value in setOf(ConnectionState.DISCOVERING, ConnectionState.DEVICE_FOUND)) session.deviceFound()
        if (autoConnectPending && session.state.value in setOf(ConnectionState.DISCOVERING, ConnectionState.DEVICE_FOUND) &&
            mutablePinRequest.value == null && prepared == null) {
            val candidate = devices.firstOrNull { it.recognizedServices.isNotEmpty() }
            if (candidate != null) {
                autoConnectPending = false
                event("Car found by the background watch; preparing the connection")
                prepareConnection(candidate.id)
            }
        }
    } } }
    override fun onCleared() { stopHeadunitPreview(); stopOwnDisplayProbe(); VirtualProbeService.onReport = null; stopAll("ViewModel finished"); usb.close(); ProjectionService.stop(getApplication()) }
}

class MainActivity : ComponentActivity() {
    private lateinit var diagnostics: DiagnosticsViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = ViewModelProvider(this)[DiagnosticsViewModel::class.java]
        setContent { AionLinkTheme { AionLinkScreen(diagnostics) } }
        if (intent?.action == AutoConnectService.ACTION_AUTO_CONNECT) diagnostics.onAutoConnectIntent()
    }
    override fun onResume() { super.onResume(); if (::diagnostics.isInitialized) diagnostics.let { it.resumed(); it.usb.refresh() } }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); diagnostics.usb.refresh()
        if (intent.action == AutoConnectService.ACTION_AUTO_CONNECT) diagnostics.onAutoConnectIntent()
    }
    override fun onStop() { if (::diagnostics.isInitialized) diagnostics.background(); super.onStop() }
}

@Composable fun AionLinkScreen(model: DiagnosticsViewModel = viewModel()) {
    val state by model.session.state.collectAsStateWithLifecycle()
    val devices by model.discovery.devices.collectAsStateWithLifecycle()
    val events by model.events.collectAsStateWithLifecycle()
    val scanning by model.discovery.isScanning.collectAsStateWithLifecycle()
    val inspection by model.gatt.state.collectAsStateWithLifecycle()
    val cable by model.usb.state.collectAsStateWithLifecycle()
    val negotiation by model.bootstrap.state.collectAsStateWithLifecycle()
    val network by model.network.state.collectAsStateWithLifecycle()
    val authProbe by model.network.authProbe.collectAsStateWithLifecycle()
    val authExchange by model.network.authExchange.collectAsStateWithLifecycle()
    val controlChannel by model.projection.control.collectAsStateWithLifecycle()
    val rtspSession by model.projection.rtsp.collectAsStateWithLifecycle()
    val videoStream by model.projection.stream.collectAsStateWithLifecycle()
    val touchState by model.projection.touch.collectAsStateWithLifecycle()
    val auxiliary by model.projection.auxiliary.collectAsStateWithLifecycle()
    val minBrightness by model.savings.enabled.collectAsStateWithLifecycle()
    val ownProbeRunning by model.probeRunning.collectAsStateWithLifecycle()
    val virtualProbeRunning by VirtualProbeService.running.collectAsStateWithLifecycle()
    val probeRunning = ownProbeRunning || virtualProbeRunning
    val carLauncher by model.savings.launcher.collectAsStateWithLifecycle()
    val highQuality by model.projection.quality.high.collectAsStateWithLifecycle()
    val carMode by model.projection.quality.carMode.collectAsStateWithLifecycle()
    val externalHeadunit by model.projection.quality.externalHeadunit.collectAsStateWithLifecycle()
    val autoWatch by model.projection.quality.autoWatch.collectAsStateWithLifecycle()
    val keepFullFps by model.projection.quality.keepFullFpsWhenHot.collectAsStateWithLifecycle()
    val headunitPreview by model.headunitPreview.collectAsStateWithLifecycle()
    val forceLandscape by model.savings.landscape.collectAsStateWithLifecycle()
    val captureObservation by ProjectionService.observation.collectAsStateWithLifecycle()
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.captureGranted(result.resultCode, result.data)
    }
    val captureContext = androidx.compose.ui.platform.LocalContext.current
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.event(if (granted) "Audio permission granted for playback capture" else "Audio permission denied; will project without sound")
        val manager = captureContext.getSystemService(android.media.projection.MediaProjectionManager::class.java)
        capture.launch(manager.createScreenCaptureIntent())
    }
    val operationBusy = negotiation.busy || network.busy || authProbe.busy || authExchange.busy || cable.busy || inspection.stage in setOf(GattStage.CONNECTING, GattStage.LINK_CONNECTED, GattStage.DISCOVERING_SERVICES)
    val gattBusy = negotiation.busy || network.busy || network.stage == NetworkStage.CONNECTED || inspection.stage in setOf(GattStage.CONNECTING, GattStage.LINK_CONNECTED, GattStage.DISCOVERING_SERVICES)
    var showOthers by remember { mutableStateOf(false) }
    val candidates = devices.filter { it.recognizedServices.isNotEmpty() }
    var pendingPeer by remember { mutableStateOf<String?>(null) }
    val pendingConnect by model.pinRequest.collectAsStateWithLifecycle()
    val codeReadiness by model.codeReadiness.collectAsStateWithLifecycle()
    val connectionNotice by model.connectionNotice.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    LaunchedEffect(connectionNotice) { scroll.animateScrollTo(0) }
    var pendingRead by remember { mutableStateOf<String?>(null) }
    var pendingAuthProbe by remember { mutableStateOf(false) }
    var pendingAuthExchange by remember { mutableStateOf(false) }
    var showCaptureGuide by remember { mutableStateOf(false) }
    var authorizedConnect by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val networkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = authorizedConnect; authorizedConnect = null
        if (request != null && model.network.hasPermissions()) model.prepareConnection(request.first, request.second)
        else model.event("Wi-Fi permissions not granted; exchange not started")
    }
    var pendingUsb by remember { mutableStateOf<Pair<String,Boolean>?>(null) }
    var requestedIccoaOnly by remember { mutableStateOf(true) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (model.discovery.hasPermissions()) model.scan(requestedIccoaOnly) else model.denied()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { model.projection.quality.setAutoWatch(true); AutoConnectService.start(context) }
        else model.event("Notifications denied; the car watch cannot alert you")
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val frozen = model.consumeExport()
        if (uri != null && frozen == null) {
            model.event("The capture was lost when the process was recreated; export again. File not opened")
        } else if (uri != null && frozen != null) try {
            val stream = context.contentResolver.openOutputStream(uri) ?: error("No output stream")
            stream.bufferedWriter().use { writer -> writer.write(frozen) }
            model.event("Pre-picker diagnostics exported without names or Bluetooth addresses")
        } catch (_: Exception) { model.event("Could not export the diagnostics") }

    }
    pendingPeer?.let { peerId ->
        AlertDialog(
            onDismissRequest = { pendingPeer = null },
            title = { Text("Check the link to your vehicle") },
            text = { Text("Select only your own parked vehicle. A BLE connection will open to discover services and close when done. No PIN or authentication is sent, and there is no projection yet.") },
            confirmButton = { TextButton(onClick = { pendingPeer = null; model.inspect(peerId) }) { Text("Connect to my vehicle") } },
            dismissButton = { TextButton(onClick = { pendingPeer = null }) { Text("Cancel") } }
        )
    }
    if (pendingAuthProbe) AuthProbeDialog(
        onConfirm = { pendingAuthProbe = false; model.network.probeAuthChannel() },
        onDismiss = { pendingAuthProbe = false }
    )
    if (pendingAuthExchange) AuthExchangeDialog(onSubmit = { pin -> pendingAuthExchange = false; model.sendExperimentalAuth(pin) },
        onDismiss = { pendingAuthExchange = false })
    if (showCaptureGuide) CaptureAppGuide(onContinue = {
        showCaptureGuide = false
        audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }, onDismiss = { showCaptureGuide = false })
    pendingConnect?.let {
        ConnectionCodeDialog(codeReadiness, connectionNotice, onSubmit = model::submitPin,
            onClose = { model.cancelPrepared() }, autoAuthDefault = model.autoAuthDefault)
    }
    pendingRead?.let { peerId ->
        AlertDialog(onDismissRequest = { pendingRead = null },
            title = { Text("Diagnostic query to my vehicle") },
            text = { Text("Confirm this is your parked vehicle. ServerInfo will be read for up to 15 seconds, without using a PIN or sending ClientInfo. The previous test got no response. If it returns valid parameters, forming its network will be attempted; this does not authenticate ICCOA.") },
            confirmButton = { TextButton(onClick = {
                pendingRead = null
                if (model.network.hasPermissions()) model.prepareConnection(peerId, true)
                else { authorizedConnect = peerId to true; networkPermission.launch(model.network.requiredPermissions()) }
            }) { Text("Query my receiver") } },
            dismissButton = { TextButton(onClick = { pendingRead = null }) { Text("Cancel") } })
    }
    pendingUsb?.let { (id,bootstrap) ->
        AlertDialog(onDismissRequest = { pendingUsb = null },
            title = { Text(if (bootstrap) "Experimental USB start" else "Open USB accessory") },
            text = { Text(if (bootstrap)
                "Confirm the accessory is your parked vehicle. Two channel requests observed on Samsung, for RTSP and UIBC, will be sent, and responses read for 15 seconds. There is no authentication or projection yet. It has not been validated with the AION."
                else "Confirm this is your parked vehicle. Android will ask for authorization if needed. The AOA channel will open and data observed for 15 seconds, without sending messages. Content will not be saved.") },
            confirmButton = { TextButton(onClick = { pendingUsb = null; model.usb.request(id,bootstrap) }) { Text("Continue with my vehicle") } },
            dismissButton = { TextButton(onClick = { pendingUsb = null }) { Text("Cancel") } })
    }
    // Re-read permissions and services when the owner comes back from Android's settings.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val linkPermissions = remember { (model.discovery.requiredPermissions() + model.network.requiredPermissions()).distinct().toTypedArray() }
    val linkPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeTick++ }
    val setup = remember(resumeTick, carMode, externalHeadunit) {
        buildList {
            add(SetupItem("Bluetooth and nearby devices", "To find the car and create its Wi-Fi Direct network",
                model.discovery.hasPermissions() && model.network.hasPermissions(), "Allow") { linkPermissionRequest.launch(linkPermissions) })
            add(SetupItem("Car touch", "Accessibility → AionLink: touch the car screen",
                TouchInjectionService.instance != null, "Enable") {
                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            if (carMode && externalHeadunit) add(SetupItem("OpenHeadunit Lite", "Receives Android Auto on the phone to bring it to the car",
                OpenHeadunit.installed(context), null, null))
        }
    }
    val streaming = videoStream.stage == "STREAMING"
    val connecting = !streaming && (negotiation.busy || network.busy || pendingConnect != null || videoStream.stage == "STARTING" ||
        state in setOf(ConnectionState.CONNECTING, ConnectionState.PAIRING, ConnectionState.NEGOTIATING,
            ConnectionState.AUTHENTICATING, ConnectionState.SESSION_ESTABLISHED, ConnectionState.PROJECTION_ACTIVE))
    val status = when {
        streaming -> HomeStatus(if (carMode && externalHeadunit) "Android Auto in the car" else "Projecting to the car",
            "You can turn off the phone screen. ${videoStream.accessUnits} frames sent", HomeTone.LIVE)
        connecting -> HomeStatus("Connecting to the car", connectionNotice, HomeTone.BUSY)
        scanning -> HomeStatus("Looking for your car", "Open CarLink on the car screen, with the car parked", HomeTone.BUSY)
        probeRunning || headunitPreview -> HomeStatus("Local test in progress", "Nothing is sent to the car", HomeTone.BUSY)
        state in setOf(ConnectionState.TIMEOUT, ConnectionState.AUTHENTICATION_FAILED, ConnectionState.TRANSPORT_ERROR,
            ConnectionState.PERMISSION_DENIED, ConnectionState.PROTOCOL_UNSUPPORTED) ->
            HomeStatus("Could not connect", connectionNotice, HomeTone.PROBLEM)
        setup.any { !it.done } -> HomeStatus("A step is missing", "Complete the list below and come back to this screen", HomeTone.IDLE)
        else -> HomeStatus("Ready to connect", "Open CarLink in the car and tap Find my car", HomeTone.IDLE)
    }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val appName = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().substringBefore("-") }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HomeHeader(appName, version)
            StatusCard(status, busy = connecting || scanning)
            if (events.isNotEmpty()) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                events.takeLast(3).reversed().forEach { line ->
                    val shown = if (line.length > 28 && line[10] == 'T') "${line.substring(11, 19)}  ${line.substring(28)}" else line
                    Text(shown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (setup.any { !it.done }) SetupCard(setup)
            Text("Projection mode", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val currentMode = when {
                    !carMode -> ProjectionMode.MIRROR
                    externalHeadunit -> ProjectionMode.LITE
                    else -> ProjectionMode.DASHBOARD
                }
                listOf(ProjectionMode.MIRROR to "Mirror", ProjectionMode.DASHBOARD to "Dashboard", ProjectionMode.LITE to "Lite").forEach { (mode, label) ->
                    if (mode == currentMode) Button(onClick = { }, modifier = Modifier.weight(1f)) { Text(label) }
                    else OutlinedButton(onClick = { model.switchProjectionMode(mode) }, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
            Text("Mirror shows the phone screen; Dashboard is AionLink's panel; Lite is Android Auto. " +
                "This is the default for the next connection; while connected it switches the picture.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (streaming || connecting) {
                Button(onClick = model::stop, modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Disconnect") }
            } else {
                Button(onClick = {
                    requestedIccoaOnly = true
                    if (model.discovery.hasPermissions()) model.scan() else permission.launch(model.discovery.requiredPermissions())
                }, enabled = !scanning && !gattBusy && !probeRunning && !headunitPreview, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (scanning) "Searching…" else "Find my car", style = MaterialTheme.typography.titleMedium)
                }
                candidates.forEach { device ->
                    ReceiverRow(device.name, enabled = !gattBusy && device.connectable != false) {
                        if (model.network.hasPermissions()) model.prepareConnection(device.id)
                        else { authorizedConnect = device.id to false; networkPermission.launch(model.network.requiredPermissions()) }
                    }
                }
                if (carMode && externalHeadunit) Text("If Android Auto does not appear: in its settings, menu ⋮ → “Start head unit server” " +
                    "(again after updating Android Auto or restarting the phone).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAdvanced) "Hide advanced options ▴" else "Advanced options and diagnostics ▾")
            }
            if (showAdvanced) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Internal state: $state", style = MaterialTheme.typography.titleSmall)
            Text(connectionNotice, style = MaterialTheme.typography.bodyMedium)
            if (network.stage == NetworkStage.CONNECTED && network.mode == org.aionlink.iccoa.NetworkMode.P2P) {
                Button(onClick = { pendingAuthExchange = true }, enabled = !authProbe.busy && !authExchange.busy) { Text("Try experimental AUTH") }
                OutlinedButton(onClick = { pendingAuthProbe = true }, enabled = !authProbe.busy && !authExchange.busy) { Text("Only check port (no authentication)") }
            }
            Text("Automatic connection", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = {
                if (!autoWatch) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else { model.projection.quality.setAutoWatch(true); AutoConnectService.start(context) }
                } else { model.projection.quality.setAutoWatch(false); AutoConnectService.stop(context) }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (autoWatch) "Watch for my car: ON — tap to turn off" else "Watch for my car in the background: OFF")
            }
            Text("While on, the phone listens for the car's signal and notifies you when it is found. Tap the notification and the code box is ready; you still type the 6-digit code once, and authentication then runs by itself. Needs notifications on and uses some battery while listening.")
            Text("Projection", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { model.projection.quality.setCarMode(!carMode) }, enabled = !probeRunning,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (carMode) "Mode: CAR (AionLink panel, phone screen off)" else "Mode: MIRROR (what the phone shows)")
            }
            if (carMode) {
                Text("Car mode: the car shows a clock, music with controls, and Maps or Waze directions. You can turn off the phone screen after connecting. " +
                    "Sound comes through the car's Bluetooth; if you also tap “Prepare capture”, sending it via AionLink is attempted.")
                OutlinedButton(onClick = {
                    context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (CarInfoListener.granted(context)) "Music and navigation: access granted" else "Allow music and navigation (notification access)")
                }
            } else Text("Before connecting, authorize screen capture. After AUTH, the phone listens on CONTROL and RTSP; video starts when the car sends PLAY.")
            Button(onClick = { audioPermission.launch(android.Manifest.permission.RECORD_AUDIO) }, enabled = !probeRunning,
                modifier = Modifier.fillMaxWidth()) { Text(if (carMode) "Prepare audio via AionLink (optional)" else "Prepare screen and audio capture") }
            if (carMode) {
                OutlinedButton(onClick = { model.projection.quality.setExternalHeadunit(!externalHeadunit) },
                    enabled = videoStream.stage != "STREAMING" && videoStream.stage != "STARTING", modifier = Modifier.fillMaxWidth()) {
                    Text(if (externalHeadunit) "Source for next connection: Android Auto (OpenHeadunit Lite on virtual display)" else "Source for next connection: AionLink panel")
                }
                if (externalHeadunit) {
                    Text("Experimental: compatible versions signed with the same key can share video directly, with no virtual-display activity. " +
                        "Video, screen-off, and audio are not yet verified. Touch is not implemented in this mode.")
                    OutlinedButton(onClick = { model.event(model.projection.showHeadunitOnCarDisplay(context)) },
                        modifier = Modifier.fillMaxWidth()) { Text("Retry Android Auto on the virtual display") }
                    OutlinedButton(onClick = { if (headunitPreview) model.stopHeadunitPreview() else model.startHeadunitPreview(context) },
                        enabled = videoStream.stage != "STREAMING" && videoStream.stage != "STARTING", modifier = Modifier.fillMaxWidth()) {
                        Text(if (headunitPreview) "Stop local Android Auto test" else "Local test without the car: Android Auto on virtual display")
                    }
                }
            }
            Text("Android Auto", style = MaterialTheme.typography.titleMedium)
            if (OpenHeadunit.installed(context)) {
                Text("OpenHeadunit Lite shows Android Auto on this phone; in MIRROR mode, AionLink brings it to the car screen with touch and sound. " +
                    "It needs the screen on (uses minimum brightness).")
                OutlinedButton(onClick = { model.event(OpenHeadunit.startSelfMode(context)) }, enabled = !carMode,
                    modifier = Modifier.fillMaxWidth()) { Text("Open Android Auto (OpenHeadunit Lite, self mode)") }
            } else Text("To see Android Auto in the car, install “OpenHeadunit Lite” (built from the fork, same signature as AionLink) and enable the head unit server for developers in Android Auto. " +
                "It is a third-party app; AionLink only opens it and shares its screen.")
            OutlinedButton(onClick = { model.projection.quality.setHigh(!highQuality) }, enabled = !probeRunning,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (highQuality) "Image quality: HIGH (car resolution, sharper)" else "Image quality: COMPATIBLE (1280x720)")
            }
            OutlinedButton(onClick = { model.projection.quality.setKeepFullFpsWhenHot(!keepFullFps) },
                modifier = Modifier.fillMaxWidth()) {
                Text(if (keepFullFps) "Hot phone: keep 30 fps (bitrate still halves)" else "Hot phone: halve frames to 15 fps")
            }
            Text("When the phone gets hot it halves the video bitrate; at severe heat it also drops to 15 fps. " +
                "Keeping 30 fps is smoother but the phone runs hotter, and Android itself may still slow the encoder.")
            if (Build.VERSION.SDK_INT >= 34) {
                OutlinedButton(onClick = { showCaptureGuide = true }, enabled = !probeRunning, modifier = Modifier.fillMaxWidth()) { Text("Share an app (experimental)") }
            }
            Text("Capture: ${captureObservation.stage} • visibility: ${captureObservation.visible?.toString() ?: "unknown"}")
            OutlinedButton(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.fillMaxWidth()) { Text(if (TouchInjectionService.instance == null) "Car touch INACTIVE: tap and enable it in accessibility" else "Car touch active (accessibility)") }
            Button(onClick = { context.startActivity(Intent(context, CarLauncherActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()) { Text("Open launcher and choose my apps") }
            OutlinedButton(onClick = { model.savings.setLauncher(!carLauncher) },
                modifier = Modifier.fillMaxWidth()) { Text(if (carLauncher) "Launcher when opening AionLink in the car: enabled" else "Launcher off: screen sharing only") }
            OutlinedButton(onClick = {
                if (forceLandscape || model.savings.canWrite) model.savings.setLandscape(!forceLandscape)
                else context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.fillMaxWidth()) { Text(if (forceLandscape) "Landscape while projecting: enabled" else "Landscape while projecting: disabled") }
            OutlinedButton(onClick = {
                if (minBrightness || model.savings.canWrite) model.savings.setEnabled(!minBrightness)
                else context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.fillMaxWidth()) { Text(if (minBrightness) "Minimum brightness while projecting: enabled" else "Minimum brightness while projecting: disabled") }
            OutlinedButton(onClick = { if (probeRunning) model.stopDisplayProbe() else model.startDisplayProbe() },
                modifier = Modifier.fillMaxWidth()) { Text(if (probeRunning) "Stop local test" else "Local test (no car): own drawing, up to 11 min") }
            OutlinedButton(onClick = { model.startVirtualDisplayProbe() }, enabled = !probeRunning,
                modifier = Modifier.fillMaxWidth()) { Text("Local test (no car): virtual display with screen off, 12 min") }
            Text("Lab tests: they send NOTHING to the car. They only measure on the phone whether video keeps being generated with the screen off. To see the phone in the car, use “Prepare screen and audio capture” and connect.")
            if (probeRunning) Text("Local test in progress: the car connection is blocked until you stop it. Turn off the screen with the power button, wait, then save the diagnostics.")
            Text("Touch: ${if (TouchInjectionService.instance != null) "service active" else "service not enabled"} • UIBC ${touchState.stage} • events ${touchState.events} • keys ${touchState.keys} • applied ${touchState.injected}")
            Text("CONTROL: ${controlChannel.stage} • received ${controlChannel.received} • heartbeats ${controlChannel.heartbeatsReceived}/${controlChannel.heartbeatsSent}")
            Text(auxiliary)
            Text("RTSP: ${rtspSession.stage} ${rtspSession.detail}")
            Text("Video: ${videoStream.stage} ${videoStream.detail} • ${videoStream.accessUnits} frames • ${videoStream.datagrams} RTP")
            Text("Audio: ${videoStream.audio} • ${videoStream.audioFrames} AAC frames")
            Text("AUTH channel: ${authProbe.stage} • ${authProbe.detail}")
            Text("AUTH request: ${authExchange.stage} • ${authExchange.detail}")
            Text("Find ICCOA receiver", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { requestedIccoaOnly = true; if (model.discovery.hasPermissions()) model.scan() else permission.launch(model.discovery.requiredPermissions()) }, enabled = !scanning && !gattBusy, modifier = Modifier.fillMaxWidth()) { Text("Search ICCOA CarLink") }
            if (scanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Searching for 15 seconds...") }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ICCOA candidates: ${candidates.size}", style = MaterialTheme.typography.titleLarge)
                    if (candidates.isEmpty()) Text(if (scanning) "Waiting for receiver advertisements..." else "No ICCOA indications. Open ICCOA in the parked car and search again. If it does not appear, try the general search.")
                    candidates.forEach { device ->
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text("${device.transport} | RSSI ${device.rssi ?: "?"} | ID ${device.id}")
                        Text("Compatible UUID observed; vehicle identity unconfirmed.")
                        Button(onClick = {
                            if (model.network.hasPermissions()) model.prepareConnection(device.id)
                            else { authorizedConnect = device.id to false; networkPermission.launch(model.network.requiredPermissions()) }
                        }, enabled = !gattBusy && device.connectable != false, modifier = Modifier.fillMaxWidth()) { Text("Prepare normal connection") }
                        OutlinedButton(onClick = { pendingPeer = device.id }, enabled = !gattBusy && device.connectable != false, modifier = Modifier.fillMaxWidth()) { Text("Only check BLE services") }
                        if (device.connectable == false) Text("The current advertisement does not allow a BLE connection.")
                        TextButton(onClick = { pendingRead = device.id }, enabled = !gattBusy && device.connectable != false) { Text("Experimental read without PIN") }
                        HorizontalDivider()
                    }
                }
            }
            Text("BLE check: ${inspection.stage}")
            Text("Exchange: ${negotiation.stage} | Network: ${network.stage}", style = MaterialTheme.typography.titleMedium)
            Text(negotiation.message)
            Text(network.message)
            if (negotiation.busy || network.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (operationBusy) Text("Wait for the result to save the diagnostics, or tap Stop to cancel the attempt.")
            OutlinedButton(onClick = model::stop) { Text("Stop search / link") }
            OutlinedButton(onClick = { requestedIccoaOnly = false; if (model.discovery.hasPermissions()) model.scan(false) else permission.launch(model.discovery.requiredPermissions()) }, enabled = !scanning && !gattBusy) { Text("General Classic + BLE search") }
            TextButton(onClick = { showOthers = !showOthers }) { Text("${if (showOthers) "Hide" else "Show"} other observations (${devices.size - candidates.size})") }
            if (showOthers) devices.filter { it.recognizedServices.isEmpty() }.forEach { device ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text("${device.transport} | ID ${device.id} | RSSI ${device.rssi ?: "?"}")
                    Text("No recognized ICCOA UUID. The name does not identify the protocol.")
                    if (device.transport == "BLE") OutlinedButton(onClick = { pendingPeer = device.id }, enabled = !gattBusy && device.connectable != false) { Text("Check BLE services") }
                } }
            }
            OutlinedButton(enabled = !operationBusy, onClick = { if (model.captureExport()) export.launch("aionlink-diagnostics.txt") }) { Text("Save diagnostics") }
            Text("Wired connection", style = MaterialTheme.typography.titleLarge)
            Text("USB: ${cable.stage} • AOA accessories: ${cable.accessories.size}")
            Text("Reported USB power: ${cable.usbPower} • Host-mode devices: ${cable.hostDevices}")
            OutlinedButton(onClick = model.usb::refresh) { Text("Refresh USB") }
            if (cable.accessories.isEmpty()) Text("Waiting for an AOA accessory. Charging the phone or having wired ADB does not prove an ICCOA link; the receiver must start accessory mode.")
            cable.accessories.forEach { accessory ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(accessory.label)
                    Text("Temporary ID ${accessory.id} • Permission: ${accessory.permission}")
                    OutlinedButton(onClick = { pendingUsb = accessory.id to false }, enabled = !cable.busy) { Text("Open USB and observe") }
                    if (accessory.observedIccoa) OutlinedButton(onClick = { pendingUsb = accessory.id to true }, enabled = !cable.busy) { Text("Start USB channels (experimental)") }
                } }
            }
            if (cable.busy) OutlinedButton(onClick = { model.usb.stop("Cancelled by the user") }) { Text("Close USB") }
            Text("USB: ${cable.bytesRead} bytes received; ${cable.frames} frames; ${cable.requestsSent} requests sent")
            cable.message?.let { Text(it) }
            Text("ICCOA connection", style = MaterialTheme.typography.titleLarge)
            Text("GATT link: ${inspection.stage}")
            if (inspection.stage == GattStage.INSPECTED) {
                Text("BLE link checked and closed. Services: ${inspection.serviceCount}. UnionShare present: ${inspection.unionSharePresent}.")
                inspection.knownCharacteristics.forEach { Text("${it.uuid} • propiedades ${it.properties}", style = MaterialTheme.typography.bodySmall) }
                Text("ICCOA negotiation pending. This inspection does not enable projection.")
            }
            inspection.error?.let { Text(it) }
            Text("Goal: own interface over ICCOA. Android Auto bridge and Huawei Car not implemented; CarbitLink not identified in this vehicle.")
            Text("Research profile: ${VehicleProfiles.aionI60.brand} ${VehicleProfiles.aionI60.model} — NOT YET VERIFIED")
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            Text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nPermissions granted: ${model.discovery.hasPermissions()}\nTransport: Classic/BLE discovery; no session channel")
            OutlinedButton(enabled = !operationBusy, onClick = { if (model.captureExport()) export.launch("aionlink-diagnostics.txt") }) { Text("Export anonymized diagnostics") }
            events.takeLast(20).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
