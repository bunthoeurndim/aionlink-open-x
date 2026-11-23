package org.aionlink.open

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * On-phone preview of the dashboard web apps: the same launcher page and the same WebView
 * settings as the car dashboard, shown on this phone so the owner can try it without the car.
 * Touch, keyboard and pinch work here as on any phone screen; the car shows the same pages
 * through the single-pointer touch path, with no keyboard and no pinch.
 */
class AppsPreviewActivity : ComponentActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            val web = webView
            if (web != null && web.canGoBack()) web.goBack() else finish()
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
        bar.addView(topButton("‹ Back") {
            webView?.let { if (it.canGoBack()) it.goBack() else AppsWebView.loadLauncher(it) }
        })
        bar.addView(topButton("⌂ Home") { webView?.let(AppsWebView::loadLauncher) })
        bar.addView(TextView(this).apply {
            text = "Preview — same pages as the car"
            setTextColor(0xFF9FB3C8.toInt()); textSize = 16f; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(16), 0, 0, 0) }
        })
        bar.addView(topButton("✕ Close") { finish() })
        root.addView(bar)
        val web = AppsWebView.create(this, onYouTubeTap = null).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        webView = web
        root.addView(web)
        AppsWebView.loadLauncher(web)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); webView?.onResume() }

    override fun onPause() { webView?.onPause(); super.onPause() }

    override fun onDestroy() {
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
