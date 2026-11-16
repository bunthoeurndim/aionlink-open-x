package org.aionlink.open

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.aionlink.bluetooth.BluetoothDiscovery

/**
 * Almost-auto connection: while the owner enables the car watch, this foreground service listens for the
 * car's ICCOA advertisement in 15 s scan windows (45 s rest between them, so the radio is not held open)
 * and notifies once per sighting. Tapping the notification opens the app with the code box ready; the
 * owner still types the 6-digit code, and the existing automatic AUTH then runs by itself. The service
 * never sends anything to the car and never stores a code. Type connectedDevice, same as CarModeService.
 */
class AutoConnectService : Service() {
    companion object {
        const val ACTION_AUTO_CONNECT = "org.aionlink.open.AUTO_CONNECT"
        const val ACTION_STOP_WATCH = "org.aionlink.open.STOP_WATCH"
        private const val CHANNEL = "aionlink-auto-connect"
        private const val CHANNEL_WATCH = "aionlink-car-watch"
        private const val NOTIFY_ID = 4
        private const val WATCH_ID = 5
        private const val NOTIFY_COOLDOWN_MS = 10 * 60_000L
        private const val REST_MS = 45_000L
        private const val PREFS = "aionlink_video"
        private const val KEY_AUTO_WATCH = "auto_watch"
        private const val KEY_LAST_NOTIFY = "auto_last_notify"

        fun enabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_WATCH, false)

        fun start(context: Context) {
            // Android 12+ refuses a foreground-service start from the background (for example
            // when a session ends while the app is in the background): fail soft, the watch
            // simply does not restart this time and the next app open retries it.
            try {
                context.startForegroundService(Intent(context, AutoConnectService::class.java))
            } catch (_: RuntimeException) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoConnectService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var discovery: BluetoothDiscovery
    private var watching = false
    private var destroyed = false
    private val nextWindow = Runnable { if (!destroyed) runWindow() }

    // Persisted, so a service restart (for example after Disconnect) does not buzz again for the same car.
    private fun lastNotifiedAt(): Long =
        getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_LAST_NOTIFY, 0L)

    private fun setLastNotifiedAt(value: Long) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_LAST_NOTIFY, value).apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_WATCH) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO_WATCH, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_WATCH, "Car watch", NotificationManager.IMPORTANCE_LOW))
            // The watch notification itself opens the app: without a content intent a tap
            // on it does nothing at all, which looks like the tap is broken.
            val openApp = PendingIntent.getActivity(this, 2,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val watchNotification = Notification.Builder(this, CHANNEL_WATCH).setContentTitle("AionLink: watching for your car")
                .setContentText("Listening for the car's signal; tap the alert when it is found")
                .setSmallIcon(android.R.drawable.ic_menu_search).setOngoing(true)
                .setContentIntent(openApp).build()
            try {
                startForeground(WATCH_ID, watchNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } catch (_: RuntimeException) {
                stopSelf()
                return START_NOT_STICKY
            }
            watching = true
            discovery = BluetoothDiscovery(applicationContext)
            serviceScope.launch {
                discovery.devices.collect { devices ->
                    // At most one alert per 10 minutes: the car keeps advertising after a disconnect,
                    // and the owner should not be buzzed again for the same parked car.
                    val now = System.currentTimeMillis()
                    if (devices.any { it.recognizedServices.isNotEmpty() } &&
                        now - lastNotifiedAt() > NOTIFY_COOLDOWN_MS) {
                        setLastNotifiedAt(now)
                        notifyCarFound()
                    }
                }
            }
            runWindow()
        }
        return START_STICKY
    }

    private fun runWindow() {
        if (destroyed || !watching) return
        discovery.start(true) {
            if (destroyed || !watching) return@start
            handler.postDelayed(nextWindow, REST_MS)
        }
    }

    private fun notifyCarFound() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Automatic connection", NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(this, MainActivity::class.java).setAction(ACTION_AUTO_CONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = Intent(this, AutoConnectService::class.java).setAction(ACTION_STOP_WATCH)
        val stopPending = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // The alert opens the app by itself (full-screen intent, like an incoming call) so the
        // car connects without a tap. On Android 14+ that needs the full-screen permission; without
        // it the alert is an ordinary notification and the owner taps to connect, as before.
        val fullScreenAllowed = android.os.Build.VERSION.SDK_INT < 34 ||
            manager.canUseFullScreenIntent()
        val builder = Notification.Builder(this, CHANNEL).setContentTitle("Your car was found")
            .setContentText(if (fullScreenAllowed) "Connecting automatically"
                else "Tap to connect — allow full-screen alerts for AionLink in Settings for automatic opening")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(openPending).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Stop watching", stopPending).build())
        if (fullScreenAllowed) builder.setFullScreenIntent(openPending, true)
        try {
            manager.notify(NOTIFY_ID, builder.build())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(nextWindow)
        if (::discovery.isInitialized) discovery.stop()
        serviceScope.cancel()
        watching = false
        super.onDestroy()
    }
}
