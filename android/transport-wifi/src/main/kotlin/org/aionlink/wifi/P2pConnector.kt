package org.aionlink.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.MacAddress
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aionlink.iccoa.ServerInfo
import org.aionlink.iccoa.validUnicastMac
import org.aionlink.iccoa.PlatformP2pAddress
import org.aionlink.iccoa.AddressDisclosure
import org.aionlink.iccoa.NetworkMode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

enum class NetworkStage { IDLE, PREPARING, READY, CONNECTING, CONNECTED, ERROR, CANCELLED }
data class P2pStatus(val stage: NetworkStage = NetworkStage.IDLE, val message: String = "Network not prepared", val mode: NetworkMode? = null) {
    val busy get() = stage in setOf(NetworkStage.PREPARING, NetworkStage.CONNECTING)
    val handoffActive get() = stage in setOf(NetworkStage.CONNECTING, NetworkStage.CONNECTED)
}
enum class AuthProbeStage { IDLE, RUNNING, FINISHED, UNAVAILABLE, CANCELLED }
data class AuthProbeStatus(val stage: AuthProbeStage = AuthProbeStage.IDLE, val detail: String = "Not checked", val elapsedMs: Long? = null) {
    val busy get() = stage == AuthProbeStage.RUNNING
}
data class AuthExchangeStatus(val stage: AuthProbeStage = AuthProbeStage.IDLE, val detail: String = "Not started") {
    val busy get() = stage == AuthProbeStage.RUNNING
}
class LocalP2p(val platformAddress: PlatformP2pAddress, val supports5Ghz: Boolean) {
    val address get() = platformAddress.realValueOrNull()
    override fun toString() = "LocalP2p(redacted)"
}

/** Public Wi-Fi Direct APIs only. Never changes global Wi-Fi mode or removes preexisting groups. */
@SuppressLint("MissingPermission")
class P2pConnector(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var apCallback: ConnectivityManager.NetworkCallback? = null
    private var apNetwork: Network? = null
    val systemWifiHandoff get() = mutable.value.handoffActive
    private val handler = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private var generation = 0
    private var timeout: Runnable? = null
    private var registered = false
    private class Target(val ssid: String, val mac: String)
    private var target: Target? = null
    private var submitted = false
    private var ownedGroup = false
    private class VerifiedEndpoint(val address: InetAddress, val interfaceName: String)
    private var verifiedEndpoint: VerifiedEndpoint? = null
    private var probeSocket: Socket? = null
    private var probeGeneration = 0
    private var exchangeTimeout: Runnable? = null
    /** Receives the group address and a session-key copy before AuthConfirm; the receiver owns and wipes the key. */
    var onSessionAuthenticated: ((java.net.Inet4Address, ByteArray) -> Unit)? = null
    private val mutableExchange = MutableStateFlow(AuthExchangeStatus())
    val authExchange = mutableExchange.asStateFlow()
    private val mutableProbe = MutableStateFlow(AuthProbeStatus())
    val authProbe = mutableProbe.asStateFlow()
    var lastAuthProbe: AuthProbeStatus? = null
        private set
    var authProbeCount: Int = 0
        private set
    private fun finishProbe(status: AuthProbeStatus) { lastAuthProbe = status; mutableProbe.value = status }
    private val mutable = MutableStateFlow(P2pStatus())
    val state = mutable.asStateFlow()
    fun requiredPermissions() = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasPermissions() = requiredPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun publish(stage: NetworkStage, message: String) { mutable.value = P2pStatus(stage, message, mutable.value.mode) }
    private fun action(ticket: Int, success: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { if (ticket == generation) success() }
        override fun onFailure(reason: Int) {
            if (ticket != generation) return
            val detail = when (reason) {
                WifiP2pManager.BUSY -> "BUSY; Android is busy, wait before retrying"
                WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED; feature unavailable"
                WifiP2pManager.ERROR -> "ERROR; internal Android failure"
                else -> "code $reason"
            }
            fail("Wi-Fi Direct did not start the operation: $detail")
        }
    }
    fun prepare(onReady: (LocalP2p) -> Unit) {
        stop()
        mutableProbe.value = AuthProbeStatus()
        if (!hasPermissions()) { fail("Missing nearby Wi-Fi devices permission"); return }
        if (wifi?.isWifiEnabled != true || manager == null) { fail("Turn on Wi-Fi to prepare the link"); return }
        val ticket = generation
        mutable.value = P2pStatus(NetworkStage.PREPARING, "Requesting Wi-Fi Direct identity from Android")
        arm(10000, "Android did not deliver the P2P data in time")
        try {
            val connection = manager.initialize(context, Looper.getMainLooper()) {
                if (ticket == generation) fail("Wi-Fi Direct channel closed by Android")
            }
            channel = connection
            if (connection == null) { fail("Wi-Fi Direct unavailable"); return }
            manager.requestGroupInfo(connection) { existing ->
                if (ticket != generation) return@requestGroupInfo
                if (existing != null) { fail("A Wi-Fi Direct group already exists. End it before connecting AionLink"); return@requestGroupInfo }
                try {
                    manager.requestDeviceInfo(connection) { device ->
                        if (ticket != generation) return@requestDeviceInfo
                        val address = device?.deviceAddress
                        val identity = PlatformP2pAddress.fromPlatform(address)
                        disarm()
                        publish(NetworkStage.READY, when (identity.disclosure) {
                            AddressDisclosure.AVAILABLE -> "Local P2P identity prepared; no remote link"
                            AddressDisclosure.ANDROID_ANONYMIZED -> "Android returned anonymized data; experiment only possible with consent"
                            AddressDisclosure.UNAVAILABLE -> "Android did not return usable P2P metadata"
                        })
                        onReady(LocalP2p(identity, wifi.is5GHzBandSupported))
                    }
                } catch (_: SecurityException) { fail("Wi-Fi permissions revoked") }
            }
        } catch (_: SecurityException) { fail("Wi-Fi permissions pending") }
        catch (_: IllegalStateException) { fail("Wi-Fi Direct unavailable") }
    }
    fun connect(info: ServerInfo) {
        val connection = channel
        val radio = manager
        if (connection == null || radio == null || mutable.value.stage != NetworkStage.READY) {
            fail("P2P preparation is no longer available"); return
        }
        val ticket = generation
        mutable.value = mutable.value.copy(mode = info.mode)
        if (info.mode == NetworkMode.ACCESS_POINT) { connectAccessPoint(info, ticket); return }
        target = Target(info.ssid, info.mac)
        publish(NetworkStage.CONNECTING, "Requesting the receiver's Wi-Fi Direct network; accept the Android dialog if it appears")
        arm(45000, "The Wi-Fi Direct network did not form within 45 seconds")
        try {
            val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true
            val config = WifiP2pConfig.Builder().setDeviceAddress(MacAddress.fromString(info.mac))
                .setNetworkName(info.ssid).setPassphrase(info.passphrase)
                .setGroupOperatingFrequency(info.frequency).enablePersistentMode(false).build()
            submitted = true
            radio.connect(connection, config, action(ticket) { checkConnection(ticket) })
        } catch (_: SecurityException) { fail("Wi-Fi permissions revoked") }
        catch (_: IllegalArgumentException) { fail("Android does not support the received P2P parameters; content omitted") }
        catch (_: IllegalStateException) { fail("Wi-Fi Direct unavailable") }
    }
    private fun connectAccessPoint(info: ServerInfo, ticket: Int) {
        val service = connectivity ?: return fail("Network service unavailable")
        publish(NetworkStage.CONNECTING, "The receiver indicated AP mode; accept the Wi-Fi connection requested by Android")
        arm(45000, "The receiver's AP network was not obtained within 45 seconds")
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (ticket != generation || apCallback !== this) return
                apNetwork = network; disarm()
                publish(NetworkStage.CONNECTED, "Android granted the requested AP network for this receiver. ICCOA authentication pending")
            }
            override fun onUnavailable() {
                if (ticket == generation && apCallback === this) fail("Android did not authorize or could not obtain the requested AP network")
            }
            override fun onLost(network: Network) {
                if (ticket == generation && apCallback === this && apNetwork == network) fail("The granted AP network disconnected")
            }
        }
        apCallback = callback
        try { service.requestNetwork(accessPointRequest(info), callback, handler, 45000) }
        catch (_: SecurityException) { fail("Android did not authorize the AP network request") }
        catch (_: IllegalArgumentException) { fail("AP network parameters not supported; content omitted") }
        catch (_: IllegalStateException) { fail("AP network request unavailable") }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Ignore intent extras; ask the platform for the current group instead.
            if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) checkConnection(generation)
        }
    }
    private fun checkConnection(ticket: Int) {
        val radio = manager ?: return
        val connection = channel ?: return
        if (mutable.value.stage !in setOf(NetworkStage.CONNECTING, NetworkStage.CONNECTED)) return
        try {
            radio.requestConnectionInfo(connection) { link ->
                if (ticket != generation) return@requestConnectionInfo
                if (!link.groupFormed) {
                    if (mutable.value.stage == NetworkStage.CONNECTED) fail("The receiver's network disconnected")
                    return@requestConnectionInfo
                }
                try {
                    radio.requestGroupInfo(connection) { group ->
                        if (ticket != generation) return@requestGroupInfo
                        val expected = target ?: return@requestGroupInfo
                        if (group == null || link.isGroupOwner || group.networkName != expected.ssid ||
                            group.owner?.deviceAddress?.equals(expected.mac, true) != true) {
                            fail("The formed group does not match the selected receiver; not reported as connected")
                            return@requestGroupInfo
                        }
                        ownedGroup = true
                        val ownerAddress = link.groupOwnerAddress
                        val interfaceName = group.`interface`
                        verifiedEndpoint = if (ownerAddress != null && !ownerAddress.isAnyLocalAddress &&
                            !ownerAddress.isLoopbackAddress && !ownerAddress.isMulticastAddress && !interfaceName.isNullOrBlank())
                            VerifiedEndpoint(ownerAddress, interfaceName) else null
                        disarm()
                        publish(NetworkStage.CONNECTED, "Receiver network connected. ICCOA authentication and projection pending")
                    }
                } catch (_: SecurityException) { fail("Wi-Fi permissions revoked") }
            }
        } catch (_: SecurityException) { fail("Wi-Fi permissions revoked") }
    }
    private fun arm(ms: Long, message: String) {
        disarm()
        val ticket = generation
        timeout = Runnable { if (ticket == generation) fail(message) }.also { handler.postDelayed(it, ms) }
    }
    private fun disarm() { timeout?.let(handler::removeCallbacks); timeout = null }
    /** Explicit user action, restricted to the selected, verified P2P group. */
    fun probeAuthChannel() {
        if (mutableProbe.value.busy || mutableExchange.value.busy) return
        authProbeCount++
        if (mutable.value.stage != NetworkStage.CONNECTED || mutable.value.mode != NetworkMode.P2P) {
            finishProbe(AuthProbeStatus(AuthProbeStage.UNAVAILABLE, "Receiver P2P not connected"))
            return
        }
        val endpoint = verifiedEndpoint
        if (endpoint == null) {
            finishProbe(AuthProbeStatus(AuthProbeStage.UNAVAILABLE, "Android did not provide the group destination and interface"))
            return
        }
        val service = connectivity
        if (service == null) {
            finishProbe(AuthProbeStatus(AuthProbeStage.UNAVAILABLE, "Network service unavailable"))
            return
        }
        val matchedNetwork = try {
            @Suppress("DEPRECATION")
            service.allNetworks.firstOrNull { service.getLinkProperties(it)?.interfaceName == endpoint.interfaceName }
        } catch (_: SecurityException) { null }
        val sourceAddress = if (matchedNetwork == null) try {
            val interfaceInfo = NetworkInterface.getByName(endpoint.interfaceName)
            val candidates = interfaceInfo?.interfaceAddresses?.mapNotNull {
                it.address?.let { address -> P2pSourceAddress.Candidate(address, it.networkPrefixLength.toInt()) }
            } ?: emptyList()
            P2pSourceAddress.select(endpoint.address, candidates)
        } catch (_: java.net.SocketException) { null } catch (_: SecurityException) { null }
        else null
        if (matchedNetwork == null && sourceAddress == null) {
            finishProbe(AuthProbeStatus(AuthProbeStage.UNAVAILABLE, "No P2P Network or unambiguous local address for the group interface"))
            return
        }
        val routeMode = if (matchedNetwork != null) "NETWORK" else "P2P_SOURCE"
        val ticket = ++probeGeneration
        val socket = Socket()
        probeSocket = socket
        mutableProbe.value = AuthProbeStatus(AuthProbeStage.RUNNING, "Checking TCP channel; route=$routeMode; no data sent")
        val started = SystemClock.elapsedRealtime()
        Thread {
            val result = OneShotTcpProbe.check(socket, endpoint.address, 57209, 2500) {
                if (matchedNetwork != null) matchedNetwork.bindSocket(it)
                else it.bind(InetSocketAddress(sourceAddress!!, 0))
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            handler.post {
                if (ticket != probeGeneration) return@post
                probeSocket = null
                finishProbe(AuthProbeStatus(AuthProbeStage.FINISHED, "${result.name}; route=$routeMode", elapsed))
            }
        }.apply { name = "AionLink-auth-check"; isDaemon = true; start() }
    }
    /** Owner explicitly initiates one experimental AUTH request on the verified P2P group. */
    fun startAuthExchange(pin: ByteArray, clientId: ByteArray, model: String) {
        fun unavailable(detail: String) {
            pin.fill(0); clientId.fill(0)
            mutableExchange.value = AuthExchangeStatus(AuthProbeStage.UNAVAILABLE, detail)
        }
        if (mutableProbe.value.busy || mutableExchange.value.busy) { pin.fill(0); clientId.fill(0); return }
        val endpoint = verifiedEndpoint
        if (mutable.value.stage != NetworkStage.CONNECTED || mutable.value.mode != NetworkMode.P2P || endpoint == null) {
            unavailable("Receiver P2P group unavailable"); return
        }
        val service = connectivity ?: run { unavailable("Network service unavailable"); return }
        val matched = try {
            @Suppress("DEPRECATION")
            service.allNetworks.firstOrNull { service.getLinkProperties(it)?.interfaceName == endpoint.interfaceName }
        } catch (_: SecurityException) { null }
        val source = if (matched == null) try {
            val addresses = NetworkInterface.getByName(endpoint.interfaceName)?.interfaceAddresses?.mapNotNull {
                it.address?.let { address -> P2pSourceAddress.Candidate(address, it.networkPrefixLength.toInt()) }
            } ?: emptyList()
            P2pSourceAddress.select(endpoint.address, addresses)
        } catch (_: java.net.SocketException) { null } catch (_: SecurityException) { null } else null
        if (matched == null && source == null) { unavailable("P2P route unavailable"); return }
        val ticket = ++probeGeneration
        val socket = Socket(); probeSocket = socket
        val route = if (matched != null) "NETWORK" else "P2P_SOURCE"
        mutableExchange.value = AuthExchangeStatus(AuthProbeStage.RUNNING, "Experimental AUTH request; route=$route")
        val deadline = Runnable {
            if (ticket == probeGeneration && mutableExchange.value.busy) {
                probeGeneration++
                try { socket.close() } catch (_: java.io.IOException) { }
                probeSocket = null; exchangeTimeout = null
                mutableExchange.value = AuthExchangeStatus(AuthProbeStage.FINISHED, "TIMEOUT_TOTAL; route=$route")
            }
        }
        exchangeTimeout = deadline; handler.postDelayed(deadline, 10000)
        Thread {
            val result = ExperimentalAuthExchange.run(socket, endpoint.address, 57209, pin, clientId, model,
                onAuthenticated = { local, key -> onSessionAuthenticated?.invoke(local, key) ?: key.fill(0) }) {
                if (matched != null) matched.bindSocket(it) else it.bind(InetSocketAddress(source!!, 0))
            }
            handler.post {
                if (ticket != probeGeneration) return@post
                handler.removeCallbacks(deadline); exchangeTimeout = null; probeSocket = null
                mutableExchange.value = AuthExchangeStatus(AuthProbeStage.FINISHED, "$result; route=$route; session not confirmed")
            }
        }.apply { name = "AionLink-auth-request"; isDaemon = true; start() }
    }
    private fun fail(message: String) { release(); publish(NetworkStage.ERROR, message) }
    fun stop(reason: String = "AionLink preparation/network stopped") {
        val wasActive = mutable.value.stage !in setOf(NetworkStage.IDLE, NetworkStage.ERROR, NetworkStage.CANCELLED)
        release()
        if (wasActive) publish(NetworkStage.CANCELLED, reason)
    }
    private fun release() {
        generation++
        probeGeneration++
        exchangeTimeout?.let(handler::removeCallbacks); exchangeTimeout = null
        try { probeSocket?.close() } catch (_: java.io.IOException) { }
        probeSocket = null
        if (mutableProbe.value.busy) finishProbe(AuthProbeStatus(AuthProbeStage.CANCELLED, "The network or attempt stopped"))
        if (mutableExchange.value.busy) mutableExchange.value = AuthExchangeStatus(AuthProbeStage.CANCELLED, "The network or attempt stopped")
        verifiedEndpoint = null
        disarm()
        val callback = apCallback; apCallback = null; apNetwork = null
        if (callback != null) try { connectivity?.unregisterNetworkCallback(callback) }
        catch (_: IllegalArgumentException) { }
        catch (_: SecurityException) { }
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        val connection = channel
        try {
            if (connection != null && submitted) manager?.cancelConnect(connection, null)
            if (connection != null && ownedGroup) manager?.removeGroup(connection, null)
        } catch (_: SecurityException) { }
        // Closing our channel does not remove unrelated groups or change Wi-Fi settings.
        connection?.close(); channel = null
        target = null; submitted = false; ownedGroup = false
    }
}
