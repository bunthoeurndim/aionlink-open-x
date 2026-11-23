package org.aionlink.open

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The dashboard's bridge to the phone. The web dashboard (assets/carhome/dashboard.html)
 * is a local page that cannot read the phone by itself; this object is the one native
 * object it talks to, through the `window.AndroidBridge` the page wraps as PhoneBridge.
 *
 * Only the local dashboard page ever sees it: the dashboard WebView pins its main frame
 * to the local file (AppsWebView), so no third-party page can load there, and the pages
 * the dashboard opens (YouTube, Maps, …) run in the dashboard's own iframe, which is a
 * different origin and cannot reach the bridge. The bridge exposes reads and media
 * controls only — it never runs code, opens the car session, or touches the car.
 *
 * Methods are synchronous getters returning JSON, because the page polls them every few
 * seconds; the native side also pushes "media" through PhoneBridge._push when playback
 * starts, so the Media page opens promptly instead of waiting for the next poll.
 */
class DashboardBridge(
    context: Context,
    /** What the Media page reports as the sound route ("Car speakers via Bluetooth" by default). */
    private val audioRoute: () -> String = { "Car speakers via Bluetooth" },
) {
    private val appContext = context.applicationContext

    @Volatile private var lastLocation: Location? = null
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val locationListener = LocationListener { location -> lastLocation = location }

    /** Phone GPS speed for the gauge. Start with the dashboard, stop with it. */
    fun start() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            lastLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { }
    }

    fun stop() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) { }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Top-right pills: phone name, battery, signal, network, Wi-Fi, Bluetooth. */
    @JavascriptInterface
    fun getStatus(): String {
        val battery = try {
            val manager = appContext.getSystemService(BatteryManager::class.java)
            val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val plugged = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            JSONObject().put("level", level).put("charging", plugged != 0)
        } catch (_: Exception) { JSONObject().put("level", -1).put("charging", false) }
        val wifi = wifiStatus()
        val mobile = isMobileConnected()
        val network = when {
            wifi.connected -> "Wi-Fi"
            mobile -> "Mobile"
            else -> "Offline"
        }
        return JSONObject()
            .put("name", deviceName())
            .put("battery", battery.optInt("level", -1))
            .put("charging", battery.optBoolean("charging", false))
            .put("signalBars", if (wifi.connected) wifi.level else -1)
            .put("network", network)
            .put("wifi", wifi.label)
            .put("bluetooth", bluetoothLabel())
            .toString()
    }

    /** The track playing on the phone, from the media session AionLink already reads. */
    @JavascriptInterface
    fun getMedia(): String {
        val info = CarInfoListener.info.value
        val positionSec = if (info.mediaDurationMs > 0L) {
            val pos = if (info.mediaPlaying) info.mediaPositionMs + (android.os.SystemClock.elapsedRealtime() - info.mediaPositionAtMs)
                else info.mediaPositionMs
            (pos / 1000L).coerceIn(0L, info.mediaDurationMs / 1000L)
        } else (info.mediaPositionMs / 1000L).coerceAtLeast(0L)
        return JSONObject()
            .put("title", info.mediaTitle ?: "")
            .put("artist", info.mediaArtist ?: "")
            .put("album", "")
            .put("app", info.mediaApp ?: "")
            .put("isPlaying", info.mediaPlaying)
            .put("positionSec", positionSec)
            .put("durationSec", (info.mediaDurationMs / 1000L).coerceAtLeast(0L))
            .put("route", audioRoute())
            .put("art", artDataUri(info.mediaArt))
            .toString()
    }

    /** GPS speed in km/h; -1 when the location permission is not granted. */
    @JavascriptInterface
    fun getLocation(): String {
        if (!hasLocationPermission()) {
            return JSONObject().put("speedKmh", -1).put("lat", 0.0).put("lng", 0.0).put("place", "").toString()
        }
        val loc = lastLocation
        val speedKmh = if (loc?.hasSpeed() == true) (loc.speed * 3.6f).toInt().coerceAtLeast(0) else 0
        val place = when {
            loc == null -> ""
            kotlin.math.abs(loc.latitude - 11.55) < 0.5 && kotlin.math.abs(loc.longitude - 104.92) < 0.5 -> "Phnom Penh"
            else -> "On the road"
        }
        return JSONObject()
            .put("speedKmh", speedKmh)
            .put("lat", loc?.latitude ?: 0.0)
            .put("lng", loc?.longitude ?: 0.0)
            .put("place", place)
            .toString()
    }

    /** The navigation notification Maps or Waze is showing, if any. */
    @JavascriptInterface
    fun getNav(): String {
        val info = CarInfoListener.info.value
        val active = info.navTitle != null || info.navText != null
        return JSONObject()
            .put("active", active)
            .put("title", info.navTitle ?: "")
            .put("text", info.navText ?: "")
            .toString()
    }

    @JavascriptInterface fun mediaPlayPause() = CarInfoListener.playPause()
    @JavascriptInterface fun mediaNext() = CarInfoListener.next()
    @JavascriptInterface fun mediaPrev() = CarInfoListener.previous()
    @JavascriptInterface fun mediaSeek(seconds: Int) = CarInfoListener.seekTo(seconds.coerceAtLeast(0) * 1000L)

    /** Open a real app on the phone (the dashboard's fallback for sites that refuse framing). */
    @JavascriptInterface
    fun openApp(pkg: String) {
        if (pkg.isBlank()) return
        try {
            appContext.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }
        } catch (_: Exception) { }
    }

    /** Open the phone's dialer with the number typed on the dashboard keypad. */
    @JavascriptInterface
    fun dial(number: String) {
        val digits = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (digits.isBlank()) return
        try {
            appContext.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    private fun deviceName(): String {
        val named = try {
            Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) { null }
        return named?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Phone"
    }

    private data class Wifi(val connected: Boolean, val level: Int, val label: String)

    private fun wifiStatus(): Wifi {
        return try {
            val manager = appContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return Wifi(false, -1, "—")
            @Suppress("DEPRECATION") val info = manager.connectionInfo
            val connected = info != null && info.networkId >= 0
            if (!connected) return Wifi(false, -1, "—")
            val level = android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 5).coerceIn(0, 4)
            val ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "Wi-Fi"
            Wifi(true, level, ssid)
        } catch (_: Exception) { Wifi(false, -1, "—") }
    }

    private fun isMobileConnected(): Boolean {
        return try {
            val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (_: Exception) { false }
    }

    private fun bluetoothLabel(): String {
        return try {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return "—"
            if (adapter.getProfileConnectionState(BluetoothProfile.A2DP) != BluetoothProfile.STATE_CONNECTED) return "—"
            adapter.getConnectedDevices(BluetoothProfile.A2DP).firstOrNull()?.name ?: "Connected"
        } catch (_: SecurityException) { "—" }
        catch (_: Exception) { "—" }
    }

    /** Album art as a small data URI, or "" when the track has none. */
    private fun artDataUri(art: Bitmap?): String {
        if (art == null) return ""
        return try {
            val out = ByteArrayOutputStream()
            art.compress(Bitmap.CompressFormat.JPEG, 80, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }
}
