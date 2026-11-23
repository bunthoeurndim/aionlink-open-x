package org.aionlink.open

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.WeakHashMap

/**
 * The dashboard web apps: one local launcher page plus the web apps it opens. Shared by the car
 * dashboard and the on-phone preview, so both show exactly the same pages with the same settings.
 * Stage 1 has no JavaScript bridge: the pages cannot touch the phone at all.
 *
 * Build 51 replaces the native dashboard with the full web dashboard (assets/carhome/dashboard.html).
 * That page DOES have a bridge — DashboardBridge, injected as window.AndroidBridge — but only
 * the local dashboard page ever sees it: createDashboard pins the WebView's main frame to the
 * local file, and the third-party pages the dashboard opens run in the dashboard's own iframe,
 * a different origin that cannot reach the bridge.
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
    const val DASHBOARD_URL = "file:///android_asset/carhome/dashboard.html"

    /** Desktop Chrome: youtube.com/tv serves the TV interface to this instead of redirecting. */
    private const val TV_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private var mobileAgent: String? = null

    /** Per-WebView dashboard-browser state: true while the main document is a third-party site. */
    private class BrowserState(var inBrowserMode: Boolean = false)
    private val browserStates = WeakHashMap<WebView, BrowserState>()

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

    /**
     * The full web dashboard. The bridge is injected here, so it exists only on this
     * WebView; the main frame is pinned to the local dashboard file, so no third-party
     * page can ever load where the bridge lives. Autoplay is allowed without a tap
     * because the owner already tapped a video tile to start it; pinch zoom stays off.
     * The dashboard browser ([enterBrowserMode]) removes the bridge before loading a
     * third-party site as the main document, and re-adds it on the way back.
     */
    fun createDashboard(context: Context, bridge: DashboardBridge): WebView {
        mobileAgent = WebSettings.getDefaultUserAgent(context)
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            addJavascriptInterface(bridge, "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    // Subframes are the dashboard page's own iframe (YouTube, Maps, …).
                    // A Maps intent:// cannot be retargeted into the iframe from here, and
                    // the dashboard's Maps links are embed URLs that do not redirect to one.
                    if (!request.isForMainFrame) return false
                    if (isBrowserMode(view)) {
                        // Dashboard browser: the main document is a third-party site, so any
                        // link may load there; an intent:// is rewritten to its https address.
                        rewriteIntentUrl(url)?.let { view.loadUrl(it); return true }
                        return false
                    }
                    // Dashboard: the bridge lives on the local page, so only it may load here.
                    return url != DASHBOARD_URL
                }
            }
        }
    }

    /** Load the web dashboard. */
    fun loadDashboard(view: WebView) { view.loadUrl(DASHBOARD_URL) }

    /** True while the WebView's main document is a third-party site (dashboard browser). */
    fun isBrowserMode(view: WebView): Boolean = browserStates[view]?.inBrowserMode == true

    /**
     * Dashboard browser (build 52): load a site as the WebView's MAIN document, after removing
     * the bridge — the bridge must never be exposed to a third-party page. The caller shows its
     * ⌂ Home overlay while the browser is open; tapping it calls [exitBrowserMode].
     */
    fun enterBrowserMode(view: WebView, bridge: DashboardBridge, url: String) {
        browserStates.getOrPut(view) { BrowserState() }.inBrowserMode = true
        view.removeJavascriptInterface("AndroidBridge")
        useMobileAgent(view)
        view.loadUrl(url)
    }

    /** Back to the dashboard: re-add the bridge first, then reload the local page. */
    fun exitBrowserMode(view: WebView, bridge: DashboardBridge) {
        browserStates.getOrPut(view) { BrowserState() }.inBrowserMode = false
        view.removeJavascriptInterface("AndroidBridge")
        view.addJavascriptInterface(bridge, "AndroidBridge")
        loadDashboard(view)
    }

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
