package org.aionlink.open

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What the app knows about a newer build. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val version: String, val url: String) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/** Result of one check against GitHub Releases. */
sealed interface UpdateCheckResult {
    data class UpToDate(val version: String) : UpdateCheckResult
    data class Available(val version: String, val url: String) : UpdateCheckResult
    data class Failed(val reason: String) : UpdateCheckResult
}

/**
 * One HTTPS GET to the public releases API (no auth for a public repo). Run off the main
 * thread. Never downloads or installs anything: the app only reports that a newer build
 * exists and links to its release page, where the owner taps through the installer herself.
 */
object UpdateCheck {
    const val RELEASES_PAGE = "https://github.com/bunthoeurndim/aionlink-open-x/releases"
    private const val LATEST_URL = "https://api.github.com/repos/bunthoeurndim/aionlink-open-x/releases/latest"

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) { "?" }

    fun check(context: Context): UpdateCheckResult {
        val installed = installedVersion(context)
        return try {
            val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK)
                    return UpdateCheckResult.Failed("GitHub answered ${connection.responseCode}")
                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                val tag = json.optString("tag_name", "")
                if (tag.isEmpty()) return UpdateCheckResult.Failed("no release tag in the answer")
                val url = json.optString("html_url", RELEASES_PAGE).ifEmpty { RELEASES_PAGE }
                val latest = tag.removePrefix("v")
                if (compareVersions(installed, latest) >= 0) UpdateCheckResult.UpToDate(installed)
                else UpdateCheckResult.Available(latest, url)
            } finally { connection.disconnect() }
        } catch (error: Exception) { UpdateCheckResult.Failed(error.javaClass.simpleName) }
    }

    /** Numeric comparison of dotted versions ("0.42.0.48-research" vs "0.42.0.47-research"):
     * splits on "." and "-", compares numbers as numbers, words as words. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".", "-"); val pb = b.split(".", "-")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: "0"; val y = pb.getOrNull(i) ?: "0"
            val xn = x.toIntOrNull(); val yn = y.toIntOrNull()
            val cmp = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
            if (cmp != 0) return cmp
        }
        return 0
    }
}
