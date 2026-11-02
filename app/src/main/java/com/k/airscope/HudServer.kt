package com.k.airscope

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Tiny read-only HTTP server for the web HUD (java.net only, no Android types).
 * Every request must carry ?k=<token>; anything else gets 403.
 */
class HudServer(
    private val port: Int,
    private val token: String,
    private val page: ByteArray,
    private val snapshot: () -> String,
    private val map: () -> String = { "{\"lat\":null}" },
) {
    @Volatile
    private var socket: ServerSocket? = null

    val isRunning: Boolean get() = socket?.isClosed == false

    @Throws(IOException::class)
    fun start() {
        if (isRunning) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        socket = ss
        Thread({ acceptLoop(ss) }, "hud-server").apply { isDaemon = true }.start()
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                break
            }
            Thread { handle(client) }.apply { isDaemon = true }.start()
        }
    }

    private fun handle(s: Socket) {
        try {
            s.use { sock ->
                sock.soTimeout = 5000
                val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = input.readLine() ?: return
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val out = sock.getOutputStream()
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))

                if (parts[0] != "GET") {
                    send(out, 405, "text/plain", "GET only".toByteArray())
                } else if (path == "/favicon.ico") {
                    send(out, 404, "text/plain", ByteArray(0))
                } else if (query["k"] != token) {
                    send(out, 403, "text/plain; charset=utf-8", "AirScope HUD: missing or wrong access key".toByteArray())
                } else when (path) {
                    "/", "/index.html" -> send(out, 200, "text/html; charset=utf-8", page)
                    "/api/state" -> send(out, 200, "application/json; charset=utf-8", snapshot().toByteArray(Charsets.UTF_8))
                    "/api/map" -> send(out, 200, "application/json; charset=utf-8", map().toByteArray(Charsets.UTF_8))
                    else -> send(out, 404, "text/plain", "not found".toByteArray())
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Method Not Allowed"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    companion object {
        fun parseQuery(q: String): Map<String, String> =
            q.split('&').filter { it.contains('=') }.associate {
                val k = it.substringBefore('=')
                val v = it.substringAfter('=')
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }

        /** IPv4 addresses on Wi-Fi / hotspot interfaces, for showing the HUD link. */
        fun localIps(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
