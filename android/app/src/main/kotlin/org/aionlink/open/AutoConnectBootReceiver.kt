package org.aionlink.open

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot, resume the car watch if the owner left it on. */
class AutoConnectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AutoConnectService.enabled(context)) return
        try {
            AutoConnectService.start(context)
        } catch (_: RuntimeException) {
            // The system refused the background start; the owner can restart the watch from the app.
        }
    }
}
