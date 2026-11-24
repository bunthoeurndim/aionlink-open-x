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
 * local dashboard URL, and the third-party pages the dashboard opens run in the dashboard's
 * own iframe, a different origin that cannot reach the bridge.
 *
 * Build 53: the dashboard is no longer a file:// page. DashboardHttpServer serves
 * assets/carhome/ over http://127.0.0.1, so the page has a real origin: a file:// page
 * sends no HTTP Referer, which is why YouTube refused the embed with error 153, and an
 * ES module (the Ferrostar bundle) never loaded from file://. If the server cannot start
 * (every port busy) the file:// page is the fallback — YouTube and the nav module fail
 * again there, but the rest of the dashboard still works.
 *
 * Build 50 fixes, from the first preview test:
 * - YouTube: youtube.com/tv redirects to youtube.com when the client looks like a mobile
 *   browser, so YouTube loads got a desktop (TV) user agent; everything else keeps the
 *   phone's mobile agent, so pages stay phone-sized. (Build 55: the /tv embed path is gone —
 *   the tile opens the real youtube.com page in the dashboard browser with the mobile agent,
 *   so no YouTube load uses the TV agent any more; loadYouTube is removed.)
 * - Map links: a maps site can answer with an intent:// link to the Maps app, which a
 *   WebView cannot load (ERR_UNKNOWN_URL_SCHEME). The intent is rewritten to the plain
 *   https address it carries, so the map stays in the WebView — opening the Maps app on
 *   the phone would be useless on the car screen. (Build 53 removes the Google Maps
 *   tile; the rewrite stays for any map link a third-party page still produces.) (Build 53 removes the Google Maps tile;
 *   the rewrite stays for any Maps link a third-party page still produces.)
 * - The launcher is a file:// page and its weather widget fetches Open-Meteo over https;
 *   that needs allowUniversalAccessFromFileURLs, which only affects file:// pages (ours).
 */
object AppsWebView {
    const val LAUNCHER_URL = "file:///android_asset/carhome/index.html"
    const val MAP_URL = "file:///android_asset/carhome/map.html"

    /** Fallback when the local server cannot start: the file:// page still loads, but YouTube embeds get no Referer (error 153) and the Ferrostar module may not load. */
    private const val FILE_DASHBOARD_URL = "file:///android_asset/carhome/dashboard.html"

    /** The dashboard URL: the local server's http origin when it is up, else the file:// fallback. */
    fun dashboardUrl(): String = DashboardHttpServer.baseUrl()?.let { "$it/dashboard.html" } ?: FILE_DASHBOARD_URL

    /** Build 55: YouTube loads with the phone's mobile agent (the TV agent was only for
     * the /tv embed path, which is gone). The dashboard browser path already does this
     * via [enterBrowserMode]; this branch keeps the old launcher helper consistent. */
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
                        useMobileAgent(view)
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
     * WebView; the main frame is pinned to the local dashboard URL (served by
     * DashboardHttpServer on 127.0.0.1, so the page has a real http origin), so no
     * third-party page can ever load where the bridge lives. Autoplay is allowed
     * without a tap because the owner already tapped a video tile to start it; pinch
     * zoom stays off. The dashboard browser ([enterBrowserMode]) removes the bridge
     * before loading a third-party site as the main document, and re-adds it on the
     * way back. The server is started here (idempotent) and stopped when the
     * dashboard is released (CarDashboardView.release / AppsPreviewActivity.onDestroy).
     */
    fun createDashboard(context: Context, bridge: DashboardBridge): WebView {
        DashboardHttpServer.start(context)
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
                    // Subframes are the dashboard page's own iframe (YouTube, …).
                    // A Maps intent:// cannot be retargeted into the iframe from here.
                    if (!request.isForMainFrame) return false
                    if (isBrowserMode(view)) {
                        // Dashboard browser: the main document is a third-party site, so any
                        // link may load there; an intent:// is rewritten to its https address.
                        rewriteIntentUrl(url)?.let { view.loadUrl(it); return true }
                        return false
                    }
                    // Dashboard: the bridge lives on the local page, so only it may load here.
                    return url != dashboardUrl()
                }
            }
        }
    }

    /** Load the web dashboard (from the local server when it is up). */
    fun loadDashboard(view: WebView) { view.loadUrl(dashboardUrl()) }

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

    private fun useMobileAgent(view: WebView) {
        view.settings.userAgentString = mobileAgent ?: WebSettings.getDefaultUserAgent(view.context)
    }

    /**
     * An intent:// URL carries a plain web address plus an Intent description, e.g.
     * intent://www.example.com/maps?...#Intent;scheme=https;package=com.example...;end
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
