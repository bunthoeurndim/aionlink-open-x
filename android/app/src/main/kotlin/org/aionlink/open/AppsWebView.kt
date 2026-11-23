package org.aionlink.open

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The dashboard web apps: one local launcher page plus the web apps it opens. Shared by the car
 * dashboard and the on-phone preview, so both show exactly the same pages with the same settings.
 * Stage 1 has no JavaScript bridge: the pages cannot touch the phone at all.
 */
object AppsWebView {
    const val LAUNCHER_URL = "file:///android_asset/carhome/index.html"

    fun isYouTubeUrl(url: String): Boolean =
        runCatching { android.net.Uri.parse(url).host?.contains("youtube.com") == true }.getOrDefault(false)

    /**
     * A WebView configured for the car apps. When [onYouTubeTap] is given, a tap on a YouTube link
     * calls it instead of loading, so the caller can ask first (parked-only check on the car);
     * when it is null the link loads straight away (the on-phone preview).
     */
    fun create(context: Context, onYouTubeTap: ((String) -> Unit)? = null): WebView =
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (isYouTubeUrl(url) && onYouTubeTap != null) { onYouTubeTap(url); return true }
                    return false
                }
            }
        }

    fun loadLauncher(view: WebView) { view.loadUrl(LAUNCHER_URL) }
}
