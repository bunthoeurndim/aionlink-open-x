package org.aionlink.open

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The dashboard web apps: one local launcher page plus the web apps it opens. Shared by the car
 * dashboard and the on-phone preview, so both show exactly the same pages with the same settings.
 * Stage 1 has no JavaScript bridge: the pages cannot touch the phone at all.
 *
 * Build 50 fixes, from the first preview test:
 * - YouTube: youtube.com/tv redirects to youtube.com when the client looks like a mobile
 *   browser, so YouTube loads get a desktop (TV) user agent; everything else keeps the
 *   phone's mobile agent, so pages stay phone-sized.
 * - Google Maps: google.com/maps answers with an intent:// link to the Maps app, which a
 *   WebView cannot load (ERR_UNKNOWN_URL_SCHEME). The intent is rewritten to the plain
 *   https address it carries, so Maps stays in the WebView — opening the Maps app on the
 *   phone would be useless on the car screen.
 * - The launcher is a file:// page and its weather widget fetches Open-Meteo over https;
 *   that needs allowUniversalAccessFromFileURLs, which only affects file:// pages (ours).
 */
object AppsWebView {
    const val LAUNCHER_URL = "file:///android_asset/carhome/index.html"
    const val MAP_URL = "file:///android_asset/carhome/map.html"

    /** Desktop Chrome: youtube.com/tv serves the TV interface to this instead of redirecting. */
    private const val TV_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private var mobileAgent: String? = null

    fun isYouTubeUrl(url: String): Boolean =
        runCatching { android.net.Uri.parse(url).host?.contains("youtube.com") == true }.getOrDefault(false)

    /**
     * A WebView configured for the car apps. When [onYouTubeTap] is given, a tap on a YouTube link
     * calls it instead of loading, so the caller can ask first (parked-only check on the car);
     * when it is null the link loads straight away (the on-phone preview).
     */
    fun create(context: Context, onYouTubeTap: ((String) -> Unit)? = null): WebView {
        mobileAgent = WebSettings.getDefaultUserAgent(context)
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowUniversalAccessFromFileURLs = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    rewriteIntentUrl(url)?.let { view.loadUrl(it); return true }
                    if (isYouTubeUrl(url)) {
                        if (onYouTubeTap != null) { onYouTubeTap(url); return true }
                        view.settings.userAgentString = TV_USER_AGENT
                        return false
                    }
                    view.settings.userAgentString = mobileAgent
                    return false
                }
            }
        }
    }

    /** Load the launcher with the mobile agent. */
    fun loadLauncher(view: WebView) { useMobileAgent(view); view.loadUrl(LAUNCHER_URL) }

    /** Load the Leaflet map page with the mobile agent. */
    fun loadMap(view: WebView) { useMobileAgent(view); view.loadUrl(MAP_URL) }

    /** Load a YouTube page with the TV agent, so /tv serves the TV interface. */
    fun loadYouTube(view: WebView, url: String) { view.settings.userAgentString = TV_USER_AGENT; view.loadUrl(url) }

    private fun useMobileAgent(view: WebView) {
        view.settings.userAgentString = mobileAgent ?: WebSettings.getDefaultUserAgent(view.context)
    }

    /**
     * An intent:// URL carries a plain web address plus an Intent description, e.g.
     * intent://www.google.com/maps?...#Intent;scheme=https;package=com.google...;end
     * A WebView cannot load the intent: scheme; return the https address it carries, or null.
     */
    fun rewriteIntentUrl(url: String): String? {
        if (!url.startsWith("intent://")) return null
        return runCatching {
            val rest = url.removePrefix("intent://")
            val hash = rest.indexOf("#Intent;")
            if (hash < 0) return null
            val address = rest.substring(0, hash)
            val scheme = Regex("scheme=([^;]+)").find(rest.substring(hash))?.groupValues?.get(1) ?: "https"
            "$scheme://$address"
        }.getOrNull()
    }
}
