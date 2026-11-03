package com.k.airscope

import java.util.Locale

/**
 * Devices heard announcing themselves on the Wi-Fi network the phone is joined to (mDNS / SSDP), keyed by IP.
 * Only what they said about themselves is kept: hostname, service names, model, software. Nothing is asked of them.
 * Pure Kotlin (engine thread only).
 */
class LanDirectory {
    class Entry(val ip: String, val firstSeen: Long) {
        var lastSeen: Long = firstSeen
        var host: String = ""
        /** Friendly instance names ("Living Room TV"), most recent last. */
        val names = LinkedHashSet<String>()
        /** Service types seen (airplay, googlecast, ...). */
        val services = LinkedHashSet<String>()
        var model: String = ""
        var software: String = ""
        var upnpType: String = ""
        var owner: String = ""
        var packets: Int = 0

        /** Best one-line description. */
        fun title(): String = names.firstOrNull { it.isNotBlank() } ?: host.ifBlank { ip }

        fun what(): String {
            val svc = services.map { LanParser.serviceLabel(it) }.distinct()
            val parts = ArrayList<String>()
            if (model.isNotBlank()) parts += model
            parts += svc.take(3)
            if (upnpType.isNotBlank() && parts.none { it.contains(upnpType, true) }) parts += upnpType
            if (software.isNotBlank() && parts.size < 2) parts += software.take(40)
            return parts.distinct().joinToString(" · ")
        }
    }

    val entries = LinkedHashMap<String, Entry>()
    @Volatile var status: String = "off"

    private fun entry(ip: String, now: Long): Entry = entries.getOrPut(ip) { Entry(ip, now) }.also { it.lastSeen = now; it.packets++ }

    fun onMdns(fromIp: String, m: LanParser.MdnsMessage, now: Long) {
        val e = entry(fromIp, now)
        // The sender usually announces its own hostname; a record for another IP goes to that IP's entry.
        for ((host, ip) in m.hosts) {
            val target = if (ip == fromIp || ip.contains(':')) e else entry(ip, now)
            if (target.host.isBlank() && !host.startsWith("_")) target.host = host
            if (target.owner.isBlank()) target.owner = Names.ownerFromHostname(host) ?: ""
        }
        for (s in m.services) {
            val target = s.host?.let { h -> m.hosts[h]?.let { ip -> if (ip == fromIp || ip.contains(':')) e else entry(ip, now) } } ?: e
            if (s.type.isNotBlank()) target.services += s.type
            val inst = s.instance.trim()
            if (inst.isNotEmpty() && inst.length <= 60 && !inst.startsWith("_") && !looksLikeId(inst)) {
                if (target.names.size >= 6) target.names.remove(target.names.first())
                target.names += inst
                if (target.owner.isBlank()) target.owner = Names.owner(inst) ?: ""
            }
            if (s.host != null && target.host.isBlank()) target.host = s.host!!
            LanParser.model(s.txt)?.let { if (target.model.isBlank() || it.length > target.model.length) target.model = it }
        }
        if (entries.size > 500) prune(now)
    }

    fun onSsdp(fromIp: String, n: LanParser.SsdpNotify, now: Long) {
        if (!n.alive) return
        val e = entry(fromIp, now)
        val label = LanParser.ssdpLabel(n)
        if (e.upnpType.isBlank() || label != "UPnP device") e.upnpType = label
        if (n.server.isNotBlank()) e.software = n.server
        for ((_, v) in n.extra) if (v.isNotBlank() && v.length <= 60 && e.names.size < 6) e.names += v
        if (entries.size > 500) prune(now)
    }

    /** Hex/UUID-looking instance names (AirPlay IDs, Chromecast IDs) say nothing to a person. */
    private fun looksLikeId(s: String): Boolean {
        val t = s.replace("-", "").replace(":", "")
        return t.length >= 12 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    private fun prune(now: Long) {
        entries.values.removeAll { now - it.lastSeen > 30 * 60_000L }
    }

    /** Heard in the last 10 minutes, most recently heard first. */
    fun active(now: Long): List<Entry> = entries.values.filter { now - it.lastSeen < 10 * 60_000L }.sortedByDescending { it.lastSeen }

    /** An entry whose hostname or friendly name matches a device's broadcast name (BLE "Sarah's MacBook Pro"). */
    fun matching(name: String): Entry? {
        val n = norm(name)
        if (n.length < 4) return null
        return entries.values.firstOrNull { e -> norm(e.host) == n || e.names.any { norm(it) == n } }
    }

    private fun norm(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    fun clear() = entries.clear()
}
