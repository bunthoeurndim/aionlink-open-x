package org.aionlink.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aionlink.iccoa.*
import java.util.UUID

/** User-selected connection and service discovery only. Never reads/writes characteristic values,
 * subscribes to notifications, bonds, opens IP sockets, or declares an ICCOA session.
 * All state mutation is serialized on the main thread; stale callbacks cannot affect a new attempt.
 */
class GattInspector(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(GattInspection())
    val state = mutableState.asStateFlow()
    private var generation = 0
    private var active: BluetoothGatt? = null
    private var deadline: Runnable? = null
    private var completion: ((GattInspection) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun inspect(peerId: String, device: BluetoothDevice, onFinished: (GattInspection) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        cancel()
        val ticket = generation
        mutableState.value = GattInspection(GattStage.CONNECTING, peerId)
        completion = onFinished
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                handler.post {
                    if (!current(ticket, gatt)) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(GattInspection(GattStage.ERROR, peerId, statusCode = status, error = "GATT link failure ($status)"))
                    } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                        mutableState.value = GattInspection(GattStage.LINK_CONNECTED, peerId)
                        try {
                            mutableState.value = GattInspection(GattStage.DISCOVERING_SERVICES, peerId)
                            if (!gatt.discoverServices()) finish(GattInspection(GattStage.ERROR, peerId, error = "Could not start GATT discovery"))
                        } catch (_: SecurityException) {
                            finish(GattInspection(GattStage.ERROR, peerId, error = "Bluetooth permission revoked"))
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        finish(GattInspection(GattStage.DISCONNECTED, peerId, error = "The device closed the link before services were obtained"))
                    }
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                handler.post {
                    if (!current(ticket, gatt)) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(GattInspection(GattStage.ERROR, peerId, statusCode = status, error = "Error discovering services ($status)"))
                        return@post
                    }
                    try {
                        val services = gatt.services
                        val union = services.firstOrNull { it.uuid == UUID.fromString(AdvertisementEvidence.GATT_SERVICE) }
                        val known = union?.characteristics?.map { it.uuid.toString() to it.properties } ?: emptyList()
                        finish(GattEvidence.summarize(peerId, services.map { it.uuid.toString() }, known))
                    } catch (_: SecurityException) {
                        finish(GattInspection(GattStage.ERROR, peerId, error = "Bluetooth permission revoked"))
                    }
                }
            }
        }
        deadline = Runnable {
            if (ticket == generation) finish(GattInspection(GattStage.TIMEOUT, peerId, error = "No GATT response in 15 seconds"))
        }.also { handler.postDelayed(it, 15000) }
        try {
            active = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (active == null) finish(GattInspection(GattStage.ERROR, peerId, error = "Android did not create the GATT client"))
        } catch (_: SecurityException) {
            finish(GattInspection(GattStage.ERROR, peerId, error = "Bluetooth connection permission denied"))
        } catch (_: IllegalArgumentException) {
            finish(GattInspection(GattStage.ERROR, peerId, error = "Invalid BLE device"))
        } catch (_: IllegalStateException) {
            finish(GattInspection(GattStage.ERROR, peerId, error = "Bluetooth unavailable"))
        }
    }

    private fun current(ticket: Int, gatt: BluetoothGatt): Boolean {
        if (ticket == generation && active === gatt) return true
        return false
    }

    private fun finish(result: GattInspection) {
        val done = completion
        completion = null
        generation++
        cleanup()
        mutableState.value = result
        done?.invoke(result)
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val running = completion != null
        val peer = mutableState.value.peerId
        completion = null
        generation++
        cleanup()
        if (running) mutableState.value = GattInspection(GattStage.DISCONNECTED, peer, error = "Inspection cancelled")
    }

    private fun cleanup() {
        deadline?.let(handler::removeCallbacks); deadline = null
        val link = active; active = null
        link?.let(::release)
    }

    @SuppressLint("MissingPermission")
    private fun release(gatt: BluetoothGatt) {
        try { gatt.disconnect() } catch (_: SecurityException) { }
        try { gatt.close() } catch (_: SecurityException) { }
    }
}
