package org.aionlink.open

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Talks to OpenHeadunit Lite (`org.aionlink.headunit.lite`), the owner-built edition of Open Headunit (AGPL-3.0) for
 * AionLink, and to nothing else: the full Open Headunit is a separate app that AionLink leaves alone, so the two never
 * compete for Android Auto or the bridge. Self mode starts through the documented automation broadcast
 * (headunit.andrerinas.com/guides/automation); video, touch and sound go through Lite's surface bridge, guarded by
 * Lite's own signature permission `org.aionlink.headunit.lite.permission.AIONLINK_SURFACE`. AionLink bundles none of
 * its code and handles none of Android Auto's authentication.
 */
object OpenHeadunit {
    /** OpenHeadunit Lite, built from the owner's fork (flavor `lite`). The only receiver AionLink uses. */
    const val PACKAGE = "org.aionlink.headunit.lite"

    /** The receiver to talk to. Kept as a function so call sites stay independent of the constant. */
    fun packageName(@Suppress("UNUSED_PARAMETER") context: Context): String = PACKAGE

    /** For diagnostics. */
    fun label(context: Context): String = if (installed(context)) "OpenHeadunit Lite" else "OpenHeadunit Lite not installed"

    private const val RECEIVER = "com.andrerinas.openheadunit.automation.AutomationReceiver"
    private const val PROJECTION_ACTIVITY = "com.andrerinas.openheadunit.aap.AapProjectionActivity"
    private const val ACTION_SELF_MODE = "com.andrerinas.openheadunit.ACTION_START_SELF_MODE"
    private const val ACTION_DISCONNECT = "com.andrerinas.openheadunit.ACTION_DISCONNECT"

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun selfModeBroadcast(context: Context, noUi: Boolean) =
        context.sendBroadcast(Intent(ACTION_SELF_MODE).setComponent(ComponentName(packageName(context), RECEIVER)).putExtra("no_ui", noUi))

    fun requestSelfModeWithoutUi(context: Context) = selfModeBroadcast(context, noUi = true)

    /** Mirror mode: returns a diagnostic line without any Android Auto content. */
    fun startSelfMode(context: Context): String {
        if (!installed(context)) return "OpenHeadunit Lite not installed"
        val launch = context.packageManager.getLaunchIntentForPackage(packageName(context))
            ?: return "OpenHeadunit Lite installed, no main activity"
        // Bring the receiver UI to the front first so Android Auto has a visible, landscape surface to mirror.
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        selfModeBroadcast(context, noUi = false)
        return "OpenHeadunit Lite opened and self mode requested (documented broadcast); Android Auto is managed by OpenHeadunit Lite"
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var generation = 0
    private var initiatedSession = false

    /** Refuse a denied launch before sending any command to the separately installed receiver. */
    fun startOnDisplay(context: Context, displayId: Int, report: (String) -> Unit, onLaunched: () -> Unit = {}) {
        if (!installed(context)) { report("OpenHeadunit Lite not installed"); return }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
            report("Android does not support activities on secondary displays"); return
        }
        val intent = projectionIntent(context)
        val allowed = try {
            context.getSystemService(android.app.ActivityManager::class.java)
                .isActivityStartAllowedOnDisplay(context, displayId, intent)
        } catch (error: RuntimeException) {
            report("Could not check display access: ${error.javaClass.simpleName}"); return
        }
        if (!allowed) {
            report("Android does not authorize this activity on the virtual display; OpenHeadunit Lite was neither started nor stopped")
            return
        }
        val attempt = ++generation
        initiatedSession = true
        context.sendBroadcast(Intent(ACTION_STOP_SERVICE).setComponent(ComponentName(packageName(context), RECEIVER)))
        handler.postDelayed({
            if (attempt == generation) {
                selfModeBroadcast(context, noUi = true)
                handler.postDelayed({
                    if (attempt == generation) {
                        val shown = showOnDisplay(context, displayId)
                        report("$shown; self mode requested without its own UI")
                        if (shown.startsWith("SOLICITADO")) handler.postDelayed({
                            if (attempt == generation) onLaunched()
                        }, PLACEHOLDER_HOLD_MS)
                    }
                }, LAUNCH_AFTER_SELF_MODE_MS)
            }
        }, STOP_SETTLE_MS)
    }

    private fun projectionIntent(context: Context) = Intent().setComponent(ComponentName(packageName(context), PROJECTION_ACTIVITY))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val ACTION_STOP_SERVICE = "com.andrerinas.openheadunit.ACTION_STOP_SERVICE"
    /** Time for a previous Open Headunit session and its activity to finish before starting a new one. */
    private const val STOP_SETTLE_MS = 2000L
    /** Measured window: at 0 ms the activity closes (no session yet); at ~1 s Open Headunit has opened its own task. */
    private const val LAUNCH_AFTER_SELF_MODE_MS = 300L
    /** Keep AionLink's placeholder until Open Headunit's window exists, then remove it so it does not cover it. */
    private const val PLACEHOLDER_HOLD_MS = 1500L

    /** Requires a visible AionLink activity (Android limits background starts) and an embeddable activity. */
    fun showOnDisplay(context: Context, displayId: Int): String {
        if (!installed(context)) return "OpenHeadunit Lite not installed"
        val intent = projectionIntent(context)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
            return "Android does not support activities on secondary displays"
        return try {
            context.startActivity(intent, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
            "REQUESTED: Android received the request; the image is not yet verified"
        } catch (error: RuntimeException) {
            "Could not open OpenHeadunit Lite on the virtual display: ${error.javaClass.simpleName}" +
                if (!CarDisplayHostActivity.present) " (the AionLink host is not on the display)" else ""
        }
    }

    /**
     * Ends display mode: stops Open Headunit (its activities finish) and removes AionLink's host, so nothing is moved
     * onto the phone screen when the virtual display is released.
     */
    fun stopOnDisplay(context: Context) {
        ++generation
        if (initiatedSession && installed(context)) context.sendBroadcast(Intent(ACTION_STOP_SERVICE).setComponent(ComponentName(packageName(context), RECEIVER)))
        initiatedSession = false
        CarDisplayHostActivity.finishCurrent()
    }

    fun disconnect(context: Context) {
        if (installed(context)) context.sendBroadcast(Intent(ACTION_DISCONNECT).setComponent(ComponentName(packageName(context), RECEIVER)))
    }
}
