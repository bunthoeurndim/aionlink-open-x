package org.aionlink.open

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale

/**
 * Build 53: a tiny HTTP server that serves the dashboard's own files (assets/carhome/)
 * over http://127.0.0.1, so the dashboard page has a real origin instead of file://.
 * A file:// page sends no HTTP Referer, which is why YouTube refused the embed with
 * error 153, and an ES module (the Ferrostar bundle) never loaded from file://.
 * Standard library only — no new dependency.
 *
 * Loopback only: the socket binds 127.0.0.1, so nothing outside this phone can reach
 * it. GET and HEAD only, correct MIME types, 404 for anything outside assets/carhome/,
 * no directory listing, no default page. The server is reference-counted: [start] is
 * idempotent (a second dashboard just adds a reference) and [stop] only closes the
 * socket when the last dashboard is gone, so it never leaks a thread after the
 * dashboard closes and never breaks if createDashboard runs twice.
 */
object DashboardHttpServer {
    /** First port tried; the next 10 are tried if it is busy. */
    const val FIRST_PORT: Int = 18765
    const val PORT_TRIES: Int = 11

    private const val ASSET_ROOT: String = "carhome"
    private const val MAX_HEAD_BYTES: Int = 8192

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var boundPort: Int = -1
    private var refCount: Int = 0
    private var appContext: Context? = null

    /** "http://127.0.0.1:<port>" while the server is up, null otherwise. */
    @Synchronized
    fun baseUrl(): String? {
        val port: Int = boundPort
        return if (port > 0) "http://127.0.0.1:$port" else null
    }

    /**
     * Start the server if it is not already running; a running server just gains
     * another reference. Returns true when the server is up afterwards, false when
     * every port in the range was busy.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (serverSocket != null) {
            refCount += 1
            return true
        }
        var port: Int = FIRST_PORT
        while (port < FIRST_PORT + PORT_TRIES) {
            try {
                val server: ServerSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = server
                boundPort = port
                appContext = context.applicationContext
                refCount = 1
                val thread: Thread = Thread({ acceptLoop(server) }, "AionLink-dash-http-accept").apply { isDaemon = true }
                acceptThread = thread
                thread.start()
                return true
            } catch (_: IOException) {
                port += 1
            }
        }
        return false
    }

    /** Drop one reference; the socket closes only when the last dashboard is gone. */
    @Synchronized
    fun stop() {
        if (refCount > 0) refCount -= 1
        if (refCount > 0) return
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        acceptThread = null
        boundPort = -1
        appContext = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (true) {
            val socket: Socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            Thread({ handleConnection(socket) }, "AionLink-dash-http").apply { isDaemon = true; start() }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { sock ->
                val input: BufferedInputStream = BufferedInputStream(sock.getInputStream())
                val head: String = readHead(input) ?: return
                val parts: List<String> = head.substringBefore("\r\n").split(" ")
                if (parts.size < 2) {
                    respond(sock, 400, "Bad Request", "text/plain; charset=utf-8", "bad request".toByteArray(Charsets.UTF_8), false)
                    return
                }
                val method: String = parts[0]
                if (method != "GET" && method != "HEAD") {
                    respond(sock, 405, "Method Not Allowed", "text/plain; charset=utf-8", "method not allowed".toByteArray(Charsets.UTF_8), false)
                    return
                }
                val assetPath: String? = resolveAssetPath(parts[1])
                val context: Context = appContext ?: run {
                    respond(sock, 500, "Internal Server Error", "text/plain; charset=utf-8", "server not started".toByteArray(Charsets.UTF_8), false)
                    return
                }
                val bytes: ByteArray? = if (assetPath == null) null else runCatching {
                    context.assets.open(assetPath).use { stream -> stream.readBytes() }
                }.getOrNull()
                if (assetPath == null || bytes == null) {
                    respond(sock, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray(Charsets.UTF_8), false)
                    return
                }
                respond(sock, 200, "OK", mimeType(assetPath), bytes, method == "HEAD")
            }
        } catch (_: IOException) {
            // The client went away mid-request; nothing to report.
        }
    }

    /** Read request bytes up to the blank line that ends the headers. */
    private fun readHead(input: BufferedInputStream): String? {
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream()
        val end: ByteArray = byteArrayOf(13, 10, 13, 10) // \r\n\r\n
        var matched: Int = 0
        while (bytes.size() < MAX_HEAD_BYTES) {
            val b: Int = input.read()
            if (b < 0) break
            bytes.write(b)
            if (b == end[matched].toInt()) {
                matched += 1
                if (matched == end.size) break
            } else {
                matched = if (b == 13) 1 else 0
            }
        }
        if (bytes.size() == 0) return null
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }

    /**
     * Turn a request target into an assets/carhome/ path, or null when it escapes
     * the dashboard directory. Query strings are dropped; no listing, no default page.
     */
    private fun resolveAssetPath(target: String): String? {
        val rawPath: String = target.substringBefore("?").substringBefore("#")
        val decoded: String = runCatching { URLDecoder.decode(rawPath, Charsets.UTF_8) }.getOrDefault(rawPath)
        val rel: String = decoded.removePrefix("/")
        if (rel.isEmpty() || rel.contains("..") || rel.contains("\\") || rel.startsWith("/")) return null
        return "$ASSET_ROOT/$rel"
    }

    private fun mimeType(assetPath: String): String {
        val lower: String = assetPath.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html; charset=utf-8"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "text/javascript; charset=utf-8"
            lower.endsWith(".css") -> "text/css; charset=utf-8"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".json") -> "application/json; charset=utf-8"
            lower.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    private fun respond(socket: Socket, status: Int, reason: String, contentType: String, body: ByteArray?, headOnly: Boolean) {
        val out: BufferedOutputStream = BufferedOutputStream(socket.getOutputStream())
        val header: String = "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (!headOnly && body != null) out.write(body)
        out.flush()
    }
}
