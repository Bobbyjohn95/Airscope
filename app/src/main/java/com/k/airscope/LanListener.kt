package com.k.airscope

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Receive-only listener for the names devices announce on the Wi-Fi network the phone is joined to:
 * mDNS/Bonjour (224.0.0.251:5353) and SSDP/UPnP (239.255.255.250:1900). It joins the two multicast groups
 * (the standard way to receive these announcements) and never sends a query, never connects to anything
 * (joining tells your router to forward them; that membership report is the only thing that ever leaves the phone).
 * Needs the phone to be on Wi-Fi; does nothing on mobile data. Parsing happens on the engine thread.
 */
class LanListener(ctx: Context, private val handler: Handler, private val dir: LanDirectory) {
    private val app = ctx.applicationContext
    @Volatile private var running = false
    private var thread: Thread? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile var ifaceName: String = ""
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var packets = 0
        private set
    @Volatile private var sockets: List<MulticastSocket> = emptyList()
    @Volatile private var generation = 0

    fun start() {
        if (running) return
        running = true
        generation++
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("airscope-lan").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            error = "multicast lock: ${e.message}" // still try: some devices deliver multicast without it
        }
        thread = Thread({ loop() }, "airscope-lan").apply { isDaemon = true; start() }
        dir.status = "starting"
    }

    fun stop() {
        running = false
        generation++
        for (s in sockets) runCatching { s.close() } // unblocks receive() right away
        thread?.interrupt()
        thread = null
        try { lock?.release() } catch (_: Exception) { }
        lock = null
        dir.status = "off"
    }

    private fun wifiInterface(): NetworkInterface? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.firstOrNull { ni ->
            ni.isUp && !ni.isLoopback && ni.name.startsWith("wlan") && ni.inetAddresses.toList().any { it is Inet4Address }
        }
    } catch (_: Exception) { null }

    private fun ipv4s(ni: NetworkInterface): List<String> = ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" }

    private fun loop() {
        val gen = generation
        fun status(s: String) { if (generation == gen) dir.status = s } // only the live listener may report
        val groups = listOf(InetAddress.getByName("224.0.0.251") to 5353, InetAddress.getByName("239.255.255.250") to 1900)
        while (running && generation == gen) {
            val ni = wifiInterface()
            if (ni == null) {
                status("not on Wi-Fi")
                sleepQuiet(5_000)
                continue
            }
            ifaceName = ni.name
            val myV4 = ipv4s(ni)
            val socks = ArrayList<MulticastSocket>()
            try {
                for ((g, port) in groups) {
                    val s = MulticastSocket(null)
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(port))
                    s.networkInterface = ni
                    s.joinGroup(InetSocketAddress(g, port), ni)
                    s.soTimeout = 2_000
                    socks += s
                }
                sockets = socks
                error = null
                status("listening on ${ni.name}")
            } catch (e: Exception) {
                error = "can't listen: ${e.message}"
                status("can't listen (${e.message})")
                for (s in socks) runCatching { s.close() }
                sleepQuiet(15_000)
                continue
            }
            val sockets = socks
            // Two sockets, one thread: poll each in turn with short timeouts (announcements are rare, not a stream).
            val buf = ByteArray(9000)
            var lastCheck = System.currentTimeMillis()
            try {
                while (running && generation == gen) {
                    for ((i, s) in sockets.withIndex()) {
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                        val from = p.address?.hostAddress ?: continue
                        val data = p.data.copyOfRange(p.offset, p.offset + p.length)
                        packets++
                        val now = System.currentTimeMillis()
                        if (i == 0) LanParser.parseMdns(data)?.let { m -> handler.post { dir.onMdns(from, m, now) } }
                        else LanParser.parseSsdp(String(data, Charsets.ISO_8859_1))?.let { n -> handler.post { dir.onSsdp(from, n, now) } }
                    }
                    val t = System.currentTimeMillis()
                    if (t - lastCheck > 30_000) { // Wi-Fi changed (new network / off): rejoin on the current interface
                        lastCheck = t
                        val cur = wifiInterface()
                        if (cur == null || cur.name != ni.name || ipv4s(cur) != myV4) break // IPv6 privacy addresses rotate: ignore them
                    }
                }
            } catch (_: Exception) {
            } finally {
                for (s in sockets) runCatching { s.close() }
            }
        }
        status("off")
    }

    private fun sleepQuiet(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { } }
}
