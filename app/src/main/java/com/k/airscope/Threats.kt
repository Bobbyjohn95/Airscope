package com.k.airscope

import java.util.Locale

/**
 * Passive signatures of Wi-Fi/BLE attack and audit hardware, from the names and addresses these tools use out of
 * the box (vendor documentation and public write-ups). Everything here is a hint from a broadcast, never proof:
 * anyone can name a network "pwned", and a pentester's Pineapple at a conference is not an attack on you. The texts
 * say so. Pure Kotlin.
 */
object Threats {
    /** Address block Wi-Fi Pineapples ship with (registered to Orient Power Home Network in the IEEE list, used by Hak5 gear). */
    val PINEAPPLE_OUI = setOf("00:13:37")

    private val pineappleDefault = Regex("^pineapple[_ -]?([0-9a-f]{4})$", RegexOption.IGNORE_CASE)
    private val pineappleWord = Regex("(wi-?fi ?pineapple|pineapple ?(mk ?\\d+|nano|tetra|hak5)\\b|hak5 ?pineapple)", RegexOption.IGNORE_CASE)
    private val marauderDefault = Regex("^marauder(ap|_[0-9a-f]{2,6})?$", RegexOption.IGNORE_CASE)
    private val omgDefault = Regex("^o\\.mg([ _-].*)?$", RegexOption.IGNORE_CASE)
    private val flipperName = Regex("^flipper\\s+\\S{3,}$", RegexOption.IGNORE_CASE)

    /** Evidence from a Wi-Fi network name (the BSSID decides whether a "Pineapple_XXXX" name is the real default). */
    fun ssidFindings(ssid: String, bssid: String = ""): List<Finding> {
        if (ssid.isBlank()) return emptyList()
        val s = ssid.trim()
        val l = s.lowercase(Locale.US)
        val f = ArrayList<Finding>()
        val macTail = bssid.lowercase(Locale.US).replace(":", "").takeLast(4)
        val pine = pineappleDefault.find(s)
        when {
            pine != null && (pine.groupValues[1].lowercase(Locale.US) == macTail || Signatures.oui(bssid) in PINEAPPLE_OUI) ->
                f += Finding(60, "Wi-Fi Pineapple default network name matching its address (Hak5 audit/attack tool)")
            pineappleWord.containsMatchIn(s) -> f += Finding(45, "Network name says Wi-Fi Pineapple (Hak5 audit/attack tool), or someone's joke")
            pine != null -> f += Finding(15, "Looks like a Wi-Fi Pineapple default name, but the digits don't match its address (probably just a name)")
            l == "pwned" -> f += Finding(55, "\"pwned\": default network of the ESP8266 Deauther (Wi-Fi jamming tool), or someone's joke name")
            l.contains("deauther") -> f += Finding(55, "Network name says Deauther (ESP8266 Wi-Fi jamming tool)")
            marauderDefault.matches(s) -> f += Finding(50, "ESP32 Marauder default network name (Wi-Fi/BLE attack tool)")
            l.contains("marauder") -> f += Finding(10, "Name contains \"marauder\" (a school mascot or a car more often than the attack tool)")
            omgDefault.matches(s) -> f += Finding(55, "O.MG default network name (malicious-cable implant with Wi-Fi)")
            l.contains("hak5") -> f += Finding(45, "Network name says Hak5 (audit/attack hardware maker)")
            l == "pwnagotchi" || l.startsWith("pwnagotchi") -> f += Finding(40, "Pwnagotchi (handshake-collecting Wi-Fi toy) name")
            l.contains("evil twin") || l.contains("eviltwin") || l.contains("wifiphisher") || l.contains("fluxion") -> f += Finding(40, "Network name taken from a Wi-Fi phishing tool")
            l == "free wifi" || l == "free_wifi" || l == "free public wifi" -> f += Finding(10, "Generic \"Free WiFi\" name: the classic bait name (often harmless)")
        }
        return f
    }

    /** Evidence from a hardware address (both Wi-Fi BSSIDs and public BLE addresses). */
    fun macFindings(mac: String): List<Finding> {
        val o = Signatures.oui(mac)
        return if (o in PINEAPPLE_OUI) listOf(Finding(40, "Address block used by Wi-Fi Pineapples ($o; the IEEE list names Orient Power for it)")) else emptyList()
    }

    /** Evidence from a BLE advertised name. */
    fun bleNameFindings(name: String?): List<Finding> {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return emptyList()
        val l = n.lowercase(Locale.US)
        return when {
            flipperName.matches(n) -> listOf(Finding(45, "Flipper Zero (multi-tool: can clone, replay and spam signals; often just a hobbyist)"))
            marauderDefault.matches(n) -> listOf(Finding(50, "ESP32 Marauder name (Wi-Fi/BLE attack tool)"))
            l.startsWith("o.mg") || l.startsWith("omg-") -> listOf(Finding(50, "O.MG implant name (malicious cable)"))
            l.contains("hak5") -> listOf(Finding(40, "Hak5 (audit/attack hardware maker) in the name"))
            l == "pwnagotchi" -> listOf(Finding(35, "Pwnagotchi (handshake-collecting Wi-Fi toy)"))
            else -> emptyList()
        }
    }

    /** Short label for the list when attack-tool evidence adds up. */
    fun label(findings: List<Finding>): String? {
        val t = findings.maxByOrNull { it.points }?.text?.lowercase(Locale.US) ?: return null
        return when {
            "pineapple" in t || "hak5" in t -> "Wi-Fi Pineapple / Hak5 tool"
            "deauther" in t || "pwned" in t -> "Wi-Fi deauther (jamming tool)"
            "marauder" in t -> "ESP32 Marauder (attack tool)"
            "o.mg" in t -> "O.MG cable implant"
            "flipper" in t -> "Flipper Zero"
            "pwnagotchi" in t -> "Pwnagotchi"
            "phishing" in t -> "Wi-Fi phishing tool name"
            else -> null
        }
    }
}

/**
 * Wi-Fi security grading and the per-network memory used to spot evil twins: for each network name you have spent
 * time near, the strongest security it ever used and the makers (OUIs) of its access points. Pure Kotlin; the engine
 * persists [dump] and restores it with [load].
 */
object WifiSecurity {
    /** A..F grade and a plain-words reason. */
    fun grade(caps: String, wps: Boolean): Pair<Char, String> {
        val label = Heuristics.securityLabel(caps)
        val tkipOnly = caps.contains("TKIP") && !caps.contains("CCMP") && !caps.contains("GCMP")
        var g = when (label) {
            "WPA3" -> 'A'
            "OWE" -> 'B'
            "WPA2" -> if (tkipOnly) 'C' else 'B'
            "WPA" -> 'C'
            "WEP" -> 'D'
            else -> 'F'
        }
        if (wps && g in 'A'..'C') g += 1
        val why = when (label) {
            "WPA3" -> "WPA3: current standard"
            "OWE" -> "OWE: encrypted open network (no password, traffic still protected)"
            "WPA2" -> if (tkipOnly) "WPA2 with TKIP only (old cipher)" else "WPA2"
            "WPA" -> "WPA1 (2003, outdated; crackable)"
            "WEP" -> "WEP (broken since 2001: anyone can read the traffic)"
            else -> "Open: no encryption, anyone nearby can read the traffic"
        } + if (wps) " · WPS on (PIN brute-force risk on older routers)" else ""
        return g to why
    }

    fun isWeak(caps: String, wps: Boolean): Boolean = grade(caps, wps).first >= 'D' || (Heuristics.securityLabel(caps) == "WPA")

    /** Security rank for "weaker than before" comparisons: higher is stronger. */
    fun rank(caps: String): Int = when (Heuristics.securityLabel(caps)) { "WPA3" -> 5; "OWE" -> 4; "WPA2" -> 3; "WPA" -> 2; "WEP" -> 1; else -> 0 }
}

/**
 * Networks you know: name -> strongest security seen, the address blocks that served it, where it was learned and on
 * how many days. Learned after 10 minutes near a network; compared only near that place (2 km) and only once it has
 * been seen on two different days, so a chain's open captive portal across town never "twins" its WPA2 sibling.
 * Entries unseen for 60 days expire. Generic/default names are never learned.
 */
class SsidMemory {
    class Known(var bestRank: Int, val ouis: LinkedHashSet<String>, var lat: Double, var lon: Double, var days: Int, var lastDay: Int, var lastAt: Long)

    val known = LinkedHashMap<String, Known>()
    var dirty = false

    private fun day(t: Long) = (t / 86_400_000L).toInt()

    /** Call about once a minute per active, named network; [minutesNear] = continuous presence so far. */
    fun touch(ssid: String, caps: String, bssid: String, minutesNear: Int, lat: Double?, lon: Double?, now: Long) {
        if (ssid.isBlank() || minutesNear < 10 || lat == null || lon == null) return
        if (ssid.trim().lowercase(Locale.US) in GENERIC) return
        val r = WifiSecurity.rank(caps)
        val d = day(now)
        val k = known.getOrPut(ssid) { dirty = true; Known(r, LinkedHashSet(), lat, lon, 1, d, now) }
        if (r > k.bestRank) { k.bestRank = r; dirty = true }
        val o = Signatures.oui(bssid)
        if (!VendorDb.isRandomizedMac(bssid) && k.ouis.size < 24 && k.ouis.add(o)) dirty = true
        if (d != k.lastDay) { k.days++; k.lastDay = d; dirty = true }
        k.lastAt = now
        if (known.size > 400) { known.remove(known.minByOrNull { it.value.lastAt }!!.key); dirty = true }
    }

    /** Drop networks unseen for 60 days. */
    fun expire(now: Long) {
        if (known.values.removeAll { now - it.lastAt > 60L * 86_400_000L }) dirty = true
    }

    /**
     * Evil-twin evidence for an access point claiming a known network name near where you learned it: weaker security
     * than that network ever used (the classic captive-portal twin), or an address block never seen for it (a new
     * router or mesh node more often than a copy: weak alone).
     */
    fun findings(ssid: String, caps: String, bssid: String, lat: Double?, lon: Double?): List<Finding> {
        val k = known[ssid] ?: return emptyList()
        if (k.days < 2 || lat == null || lon == null || Geo.distanceM(lat, lon, k.lat, k.lon) > 2000) return emptyList()
        val f = ArrayList<Finding>()
        val r = WifiSecurity.rank(caps)
        if (r < k.bestRank && k.bestRank >= 3 && r <= 2) f += Finding(45, "Claims to be \"$ssid\" (a network you know here as ${rankName(k.bestRank)}) but is ${rankName(r)}: possible evil twin")
        val o = Signatures.oui(bssid)
        if (!VendorDb.isRandomizedMac(bssid) && k.ouis.isNotEmpty() && o !in k.ouis) f += Finding(15, "New address block for \"$ssid\" ($o): a new router or mesh node, or a copy of the name")
        return f
    }

    private fun rankName(r: Int) = when (r) { 5 -> "WPA3"; 4 -> "OWE"; 3 -> "WPA2"; 2 -> "WPA"; 1 -> "WEP"; else -> "open" }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    fun dump(): String = known.entries.joinToString("\n") { (s, k) ->
        listOf(enc(s), k.bestRank, k.days, k.ouis.joinToString(","), String.format(Locale.US, "%.4f", k.lat), String.format(Locale.US, "%.4f", k.lon), k.lastDay, k.lastAt).joinToString("\t")
    }

    fun load(text: String) {
        for (line in text.split('\n')) {
            val p = line.split('\t')
            if (p.size < 8 || p[0].isBlank()) continue
            known[dec(p[0])] = Known(p[1].toIntOrNull() ?: 0, LinkedHashSet(p[3].split(',').filter { it.isNotBlank() }),
                p[4].toDoubleOrNull() ?: continue, p[5].toDoubleOrNull() ?: continue, p[2].toIntOrNull() ?: 1, p[6].toIntOrNull() ?: 0, p[7].toLongOrNull() ?: 0L)
        }
        dirty = false
    }

    companion object {
        /** Names so common that "the same name, weaker" means nothing. */
        val GENERIC = setOf("guest", "wifi", "wi-fi", "home", "network", "default", "linksys", "netgear", "dlink", "d-link", "tp-link", "tplink",
            "asus", "wireless", "internet", "free wifi", "free_wifi", "public", "hotspot", "iphone", "android", "androidap", "xfinitywifi",
            "xfinity", "attwifi", "spectrum", "cablewifi", "boingo", "starbucks", "mcdonalds", "google starbucks", "airport", "hotel", "office",
            "visitor", "visitors", "customer", "shop", "store", "cafe", "coffee", "lobby", "admin", "setup", "camera", "printer", "tv", "router")
    }
}
