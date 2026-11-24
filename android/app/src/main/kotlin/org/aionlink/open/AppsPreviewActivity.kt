package org.aionlink.open

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
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
 *
 * Build 52: no native top bar — the WebView fills the whole screen and the dashboard
 * page's own rail ✕ button (visible only in the preview, via the bridge's isPreview)
 * exits. Android back still finishes the preview too.
 */
class AppsPreviewActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var bridge: DashboardBridge? = null
    private var homeOverlay: TextView? = null

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
        // Build 52: no native top bar — the WebView fills the whole screen and the
        // dashboard page's own rail ✕ button (visible only in the preview) exits.
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0B0F14.toInt())
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val dashBridge = DashboardBridge(
            this,
            isPreviewMode = true,
            onExitPreview = { finish() },
            onOpenBrowser = { url ->
                webView?.let { web -> bridge?.let { b -> AppsWebView.enterBrowserMode(web, b, url) } }
                homeOverlay?.visibility = View.VISIBLE
            },
        )
        bridge = dashBridge
        val web = AppsWebView.createDashboard(this, dashBridge).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        webView = web
        root.addView(web)
        homeOverlay = TextView(this).apply {
            text = "⌂ Home"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xCC17181C.toInt()) }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = dp(16); rightMargin = dp(16) }
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                webView?.let { w -> AppsWebView.exitBrowserMode(w, dashBridge) }
                visibility = View.GONE
            }
        }.also { root.addView(it) }
        AppsWebView.loadDashboard(web)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); bridge?.start(); webView?.onResume() }

    override fun onPause() { bridge?.stop(); webView?.onPause(); super.onPause() }

    override fun onDestroy() {
        bridge?.stop()
        DashboardHttpServer.stop()
        webView?.let { web ->
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
