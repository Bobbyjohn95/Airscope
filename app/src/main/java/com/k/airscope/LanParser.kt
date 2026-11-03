package com.k.airscope

import java.util.Locale

/**
 * Parsers for what devices announce on the local network, received only (never asked for):
 *  - mDNS / Bonjour (UDP 224.0.0.251:5353): hostnames, service instance names ("Living Room TV._airplay._tcp"),
 *    TXT details such as the model.
 *  - SSDP / UPnP (UDP 239.255.255.250:1900): NOTIFY announcements with the device type and server software.
 * Pure Kotlin so it can be unit tested; nothing here touches Android.
 */
object LanParser {
    class MdnsService(val instance: String, val type: String, var host: String? = null, var port: Int = 0, val txt: LinkedHashMap<String, String> = LinkedHashMap())

    class MdnsMessage {
        /** hostname (without the trailing dot) -> IPv4 / IPv6 text. */
        val hosts = LinkedHashMap<String, String>()
        val services = ArrayList<MdnsService>()
        var response = false
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun u8() = (b[pos++].toInt() and 0xFF)
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16().toLong() shl 16) or u16().toLong()
        fun remaining() = b.size - pos

        /** DNS name with compression pointers; returns labels joined by '.', no trailing dot. */
        fun name(): String {
            val sb = StringBuilder()
            var p = pos
            var jumped = false
            var hops = 0
            while (true) {
                if (p >= b.size) throw IndexOutOfBoundsException()
                val len = b[p].toInt() and 0xFF
                when {
                    len == 0 -> { p++; break }
                    len and 0xC0 == 0xC0 -> {
                        if (p + 1 >= b.size) throw IndexOutOfBoundsException()
                        val off = ((len and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                        if (!jumped) pos = p + 2
                        jumped = true
                        p = off
                        if (++hops > 20) throw IllegalStateException("pointer loop")
                    }
                    else -> {
                        if (p + 1 + len > b.size) throw IndexOutOfBoundsException()
                        if (sb.isNotEmpty()) sb.append('.')
                        sb.append(String(b, p + 1, len, Charsets.UTF_8))
                        p += 1 + len
                    }
                }
            }
            if (!jumped) pos = p
            return sb.toString()
        }
    }

    fun parseMdns(b: ByteArray?): MdnsMessage? {
        if (b == null || b.size < 12) return null
        val m = MdnsMessage()
        try {
            val r = Reader(b)
            r.u16() // id
            val flags = r.u16()
            m.response = flags and 0x8000 != 0
            val qd = r.u16(); val an = r.u16(); val ns = r.u16(); val ar = r.u16()
            repeat(qd) { r.name(); r.u16(); r.u16() }
            val byInstance = HashMap<String, MdnsService>()
            repeat(an + ns + ar) {
                val name = r.name()
                val type = r.u16()
                r.u16() // class (+ cache flush bit)
                r.u32() // ttl
                val len = r.u16()
                val end = r.pos + len
                if (end > b.size) throw IndexOutOfBoundsException()
                when (type) {
                    1 -> if (len == 4) m.hosts[strip(name)] = (0 until 4).joinToString(".") { (b[r.pos + it].toInt() and 0xFF).toString() }
                    28 -> if (len == 16) m.hosts.putIfAbsent(strip(name), ipv6(b, r.pos))
                    12 -> { // PTR: service type -> instance
                        val target = r.name()
                        val svcType = serviceType(name)
                        if (svcType != null && !name.startsWith("_services._dns-sd")) {
                            byInstance.getOrPut(target) { MdnsService(instanceName(target, name), svcType).also { m.services += it } }
                        }
                    }
                    33 -> { // SRV: instance -> host:port
                        r.u16(); r.u16(); val port = r.u16()
                        val host = r.name()
                        val svcType = serviceType(name.substringAfter('.'))
                        val s = byInstance.getOrPut(name) { MdnsService(instanceName(name, name.substringAfter('.')), svcType ?: "").also { m.services += it } }
                        s.host = strip(host); s.port = port
                    }
                    16 -> { // TXT: key=value strings
                        val svcType = serviceType(name.substringAfter('.'))
                        val s = byInstance.getOrPut(name) { MdnsService(instanceName(name, name.substringAfter('.')), svcType ?: "").also { m.services += it } }
                        var p = r.pos
                        while (p < end) {
                            val l = b[p].toInt() and 0xFF
                            if (l == 0 || p + 1 + l > end) break
                            val kv = String(b, p + 1, l, Charsets.UTF_8)
                            val k = kv.substringBefore('=').trim()
                            if (k.isNotEmpty() && s.txt.size < 40) s.txt[k] = if ('=' in kv) kv.substringAfter('=') else ""
                            p += 1 + l
                        }
                    }
                }
                r.pos = end
            }
        } catch (_: Exception) {
            // truncated or malformed packet: keep whatever was parsed before the error
        }
        return if (m.hosts.isEmpty() && m.services.isEmpty()) null else m
    }

    private fun strip(n: String) = n.removeSuffix(".").removeSuffix(".local")

    /** "_airplay._tcp.local" -> "airplay"; null when it isn't a DNS-SD service name. */
    fun serviceType(n: String): String? {
        val parts = n.removeSuffix(".").removeSuffix(".local").split('.')
        val i = parts.indexOfFirst { it == "_tcp" || it == "_udp" }
        if (i < 1) return null
        return parts[i - 1].removePrefix("_").ifEmpty { null }
    }

    /** "Living Room TV._airplay._tcp.local" with type suffix removed -> "Living Room TV". */
    private fun instanceName(full: String, typeName: String): String {
        val t = typeName.removeSuffix(".")
        val f = full.removeSuffix(".")
        return (if (f.endsWith(".$t")) f.removeSuffix(".$t") else f.substringBefore("._")).replace("\\.", ".")
    }

    private fun ipv6(b: ByteArray, off: Int): String = (0 until 8).joinToString(":") { String.format(Locale.US, "%x", ((b[off + 2 * it].toInt() and 0xFF) shl 8) or (b[off + 2 * it + 1].toInt() and 0xFF)) }

    /** What a DNS-SD service type says the device is. */
    fun serviceLabel(type: String): String = when (type.lowercase(Locale.US)) {
        "airplay", "raop" -> "AirPlay (Apple TV / HomePod / speaker)"
        "companion-link", "rdlink" -> "Apple device (iPhone / iPad / Mac)"
        "device-info" -> "Mac / Apple device"
        "homekit", "hap" -> "HomeKit accessory"
        "googlecast" -> "Chromecast / Google TV / Nest speaker"
        "androidtvremote2", "androidtvremote" -> "Android TV"
        "spotify-connect" -> "Spotify Connect speaker"
        "sonos" -> "Sonos speaker"
        "ipp", "ipps", "printer", "pdl-datastream", "scanner", "uscan" -> "Printer / scanner"
        "smb", "afpovertcp", "nfs" -> "File server / NAS / computer"
        "ssh", "sftp-ssh" -> "Computer (SSH)"
        "http", "https" -> "Web interface"
        "hue" -> "Philips Hue bridge"
        "matter", "matterc", "matterd" -> "Matter smart-home device"
        "esphomelib" -> "ESPHome device"
        "shelly" -> "Shelly smart switch"
        "octoprint" -> "3D printer"
        "xbox" -> "Xbox"
        "nvstream" -> "GeForce/Moonlight game stream"
        "amzn-wplay" -> "Amazon Fire TV / Echo"
        "roku" -> "Roku"
        "lg-smart-tv", "webos" -> "LG TV"
        "viziocast" -> "Vizio TV"
        "samsungmsf" -> "Samsung TV"
        "arduino" -> "Arduino"
        "mqtt" -> "MQTT broker"
        "workstation" -> "Computer"
        "sleep-proxy" -> "Apple sleep proxy (Apple TV / HomePod)"
        "meshcop", "thread" -> "Thread border router"
        "dosvc" -> "Windows PC"
        else -> type
    }

    /** Model from mDNS TXT records (Apple: model=MacBookPro18,3; HomeKit: md=; Google: md=; printers: ty=). */
    fun model(txt: Map<String, String>): String? =
        listOf("model", "md", "ty", "product", "am", "fn", "n").firstNotNullOfOrNull { k -> txt[k]?.takeIf { it.isNotBlank() && it.length <= 60 } }

    class SsdpNotify(val nt: String, val usn: String, val server: String, val location: String, val alive: Boolean, val extra: Map<String, String>)

    /** SSDP NOTIFY / M-SEARCH response headers. Returns null for anything else (we never send M-SEARCH ourselves). */
    fun parseSsdp(text: String?): SsdpNotify? {
        if (text == null) return null
        val lines = text.split("\r\n", "\n")
        val first = lines.firstOrNull()?.trim() ?: return null
        if (!first.startsWith("NOTIFY", ignoreCase = true) && !first.startsWith("HTTP/1.1 200", ignoreCase = true)) return null
        val h = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i <= 0) continue
            h[l.substring(0, i).trim().uppercase(Locale.US)] = l.substring(i + 1).trim()
        }
        val nt = h["NT"] ?: h["ST"] ?: ""
        val nts = h["NTS"] ?: "ssdp:alive"
        if (nt.isEmpty() && h["USN"].isNullOrEmpty()) return null
        val extra = h.filterKeys { it.startsWith("X-") || it in setOf("DEVICENAME.LGE.COM", "DEVICE-NAME", "FRIENDLYNAME.DLNA.ORG") }
        return SsdpNotify(nt, h["USN"] ?: "", h["SERVER"] ?: "", h["LOCATION"] ?: "", !nts.contains("byebye", ignoreCase = true), extra)
    }

    /** What a UPnP device type / server string says the device is. */
    fun ssdpLabel(n: SsdpNotify): String {
        val nt = n.nt.lowercase(Locale.US); val sv = n.server.lowercase(Locale.US)
        return when {
            "sonos" in sv -> "Sonos speaker"
            "roku" in sv || "roku" in nt -> "Roku"
            "mediarenderer" in nt -> "TV / media player (DLNA)"
            "mediaserver" in nt -> "Media server (DLNA / NAS)"
            "internetgatewaydevice" in nt || "wanconnectiondevice" in nt || "wandevice" in nt -> "Router (UPnP gateway)"
            "printer" in nt -> "Printer"
            "basic:1" in nt && "hue" in sv -> "Philips Hue bridge"
            "dial-multiscreen" in nt -> "Smart TV / streaming device (DIAL)"
            "wfadevice" in nt -> "Wi-Fi Alliance device (WPS)"
            "belkin" in sv || "wemo" in sv -> "Wemo smart plug"
            "xbox" in sv || "xbox" in nt -> "Xbox"
            "playstation" in sv || "sony" in sv -> "PlayStation / Sony device"
            "samsung" in sv -> "Samsung TV / device"
            "lge" in sv || "lg " in sv || "webos" in sv -> "LG TV"
            "windows" in sv -> "Windows PC"
            "synology" in sv || "nas" in sv -> "NAS"
            "echo" in sv || "amazon" in sv || "fire" in sv -> "Amazon Echo / Fire TV"
            "upnp" in sv -> "UPnP device"
            else -> "UPnP device"
        }
    }
}
