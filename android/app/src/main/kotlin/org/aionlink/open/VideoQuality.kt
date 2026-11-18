package org.aionlink.open

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owner's image-quality choice. HIGH encodes at the car's own screen size (sharper, more bitrate); COMPATIBLE keeps
 * the 1280x720 mode that 0.25–0.28 used in the vehicle, as a fallback if a car rejects the larger stream.
 */
enum class ProjectionMode { MIRROR, DASHBOARD, LITE }

class VideoQuality(context: Context) {
    private val preferences = context.getSharedPreferences("aionlink_video", Context.MODE_PRIVATE)
    private val mutableHigh = MutableStateFlow(preferences.getBoolean("high", true))
    val high = mutableHigh.asStateFlow()

    fun setHigh(value: Boolean) {
        preferences.edit().putBoolean("high", value).apply()
        mutableHigh.value = value
    }

    /**
     * Car mode sends AionLink's own dashboard or Android Auto (works with the screen off); otherwise the phone screen
     * is mirrored. On by default, together with [externalHeadunit], so a new phone starts with Android Auto.
     */
    private val mutableCarMode = MutableStateFlow(preferences.getBoolean("car_mode", true))
    val carMode = mutableCarMode.asStateFlow()

    /** Opt-in source for the next car session; never switches an active virtual display. */
    private val mutableExternalHeadunit = MutableStateFlow(preferences.getBoolean("external_headunit", true))
    val externalHeadunit = mutableExternalHeadunit.asStateFlow()

    fun setExternalHeadunit(value: Boolean) {
        preferences.edit().putBoolean("external_headunit", value).apply()
        mutableExternalHeadunit.value = value
    }

    fun setCarMode(value: Boolean) {
        preferences.edit().putBoolean("car_mode", value).apply()
        mutableCarMode.value = value
    }

    /** The three projection modes as one choice: MIRROR is the phone screen, DASHBOARD is AionLink's own panel,
     * LITE is Android Auto through OpenHeadunit Lite. The choice is the default for the next connection; while a
     * session is up it also applies to the next PLAY the car sends. */
    fun mode(): ProjectionMode = when {
        !mutableCarMode.value -> ProjectionMode.MIRROR
        mutableExternalHeadunit.value -> ProjectionMode.LITE
        else -> ProjectionMode.DASHBOARD
    }

    fun setMode(mode: ProjectionMode) {
        when (mode) {
            ProjectionMode.MIRROR -> setCarMode(false)
            ProjectionMode.DASHBOARD -> { setCarMode(true); setExternalHeadunit(false) }
            ProjectionMode.LITE -> { setCarMode(true); setExternalHeadunit(true) }
        }
    }

    /**
     * Background car watch: while on, a foreground service listens for the car's ICCOA advertisement and notifies
     * when the car is found, so the owner only has to type the code. Off by default; the watch needs the
     * notification permission to be useful and costs some battery while scanning.
     */
    private val mutableAutoWatch = MutableStateFlow(preferences.getBoolean("auto_watch", false))
    val autoWatch = mutableAutoWatch.asStateFlow()

    fun setAutoWatch(value: Boolean) {
        preferences.edit().putBoolean("auto_watch", value).apply()
        mutableAutoWatch.value = value
    }

    /**
     * Keep 30 fps when the phone is hot. Off by default: at SEVERE thermal state the bitrate halves
     * and one frame in two is drawn (15 fps). On: the bitrate still halves, but every frame is drawn.
     * The phone runs hotter and Android itself may still throttle the encoder, so this is best-effort.
     */
    private val mutableKeepFullFpsWhenHot = MutableStateFlow(preferences.getBoolean("keep_full_fps_when_hot", false))
    val keepFullFpsWhenHot = mutableKeepFullFpsWhenHot.asStateFlow()

    fun setKeepFullFpsWhenHot(value: Boolean) {
        preferences.edit().putBoolean("keep_full_fps_when_hot", value).apply()
        mutableKeepFullFpsWhenHot.value = value
    }

    /**
     * Reduce bass clipping: −3 dB before AAC encoding (mirror + Android Auto paths). Off by default:
     * the captured signal runs hot (peaks 88–100%) and the car's CarLink DSP clips heavy bass.
     * The toggle itself is the test — flip it on during a bass-heavy song. The car volume
     * compensates the 3 dB.
     */
    private val mutableReduceClipping = MutableStateFlow(preferences.getBoolean("reduce_bass_clipping", false))
    val reduceClipping = mutableReduceClipping.asStateFlow()

    fun setReduceClipping(value: Boolean) {
        preferences.edit().putBoolean("reduce_bass_clipping", value).apply()
        mutableReduceClipping.value = value
    }

    /** Re-read the watch flag (the background service can turn it off from a notification action). */
    fun refreshAutoWatch() {
        mutableAutoWatch.value = preferences.getBoolean("auto_watch", false)
    }
}
