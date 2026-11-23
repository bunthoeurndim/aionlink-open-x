package org.aionlink.open

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * On-phone preview of the web dashboard: the same dashboard page, the same WebView
 * settings and the same phone bridge as the car dashboard, shown on this phone so the
 * owner can try it without the car. Touch, keyboard and pinch work here as on any phone
 * screen; the car shows the same pages through the single-pointer touch path, with no
 * keyboard and no pinch. GPS speed works here too, from the phone's own GPS.
 */
class AppsPreviewActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var bridge: DashboardBridge? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                this@AppsPreviewActivity.finish()
            }
        })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F14.toInt())
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        bar.addView(topButton("‹ Back") { finish() })
        bar.addView(TextView(this).apply {
            text = "Preview"
            setTextColor(0xFF9FB3C8.toInt()); textSize = 16f; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(16), 0, 0, 0) }
        })
        bar.addView(topButton("✕ Close") { finish() })
        root.addView(bar)
        val dashBridge = DashboardBridge(this)
        bridge = dashBridge
        val web = AppsWebView.createDashboard(this, dashBridge).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        webView = web
        root.addView(web)
        AppsWebView.loadDashboard(web)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); bridge?.start(); webView?.onResume() }

    override fun onPause() { bridge?.stop(); webView?.onPause(); super.onPause() }

    override fun onDestroy() {
        bridge?.stop()
        webView?.let { web ->
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun topButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xFF1E293B.toInt()) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(12), 0) }
        isClickable = true
        setOnClickListener { action() }
    }
}
