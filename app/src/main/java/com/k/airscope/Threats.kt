package com.k.airscope

import java.util.Locale

/**
 * Passive signatures of Wi-Fi/BLE attack and audit hardware, from the names and addresses these tools use out of
 * the box (vendor documentation and public write-ups). Everything here is a hint from a broadcast, never proof:
 * anyone can name a network "pwned", and a pentester's Pineapple at a conference is not an attack on you. The texts
 * say so. Pure Kotlin.
 */
object Threats {
    /** Hak5 LLC (Wi-Fi Pineapple, Wi-Fi Coconut) IEEE OUI. */
    val HAK5_OUI = setOf("00:13:37")

    private val pineappleDefault = Regex("^pineapple[_ -]?[0-9a-f]{4}$", RegexOption.IGNORE_CASE)
    private val omgDefault = Regex("^o\\.mg([ _-].*)?$", RegexOption.IGNORE_CASE)
    private val flipperName = Regex("^flipper\\s+\\S{3,}$", RegexOption.IGNORE_CASE)

    /** Evidence from a Wi-Fi network name. */
    fun ssidFindings(ssid: String): List<Finding> {
        if (ssid.isBlank()) return emptyList()
        val s = ssid.trim()
        val l = s.lowercase(Locale.US)
        val f = ArrayList<Finding>()
        when {
            pineappleDefault.matches(s) -> f += Finding(60, "Wi-Fi Pineapple default network name (Hak5 audit/attack tool)")
            l.contains("pineapple") && (l.contains("hak5") || l.contains("wifi") || l.contains("mk") || l.contains("nano") || l.contains("tetra")) ->
                f += Finding(45, "Network name mentions a Wi-Fi Pineapple (Hak5 audit/attack tool)")
            l == "pwned" -> f += Finding(55, "\"pwned\": default network of the ESP8266 Deauther (Wi-Fi jamming tool), or someone's joke name")
            l.contains("deauther") -> f += Finding(55, "Network name says Deauther (ESP8266 Wi-Fi jamming tool)")
            l.contains("marauder") -> f += Finding(50, "Network name says Marauder (ESP32 Wi-Fi/BLE attack tool), or a game/car name")
            omgDefault.matches(s) -> f += Finding(55, "O.MG default network name (malicious-cable implant with Wi-Fi)")
            l.contains("hak5") -> f += Finding(45, "Network name says Hak5 (audit/attack hardware maker)")
            l.contains("wifi pineapple") -> f += Finding(45, "Network name says Wi-Fi Pineapple")
            l == "pwnagotchi" || l.startsWith("pwnagotchi") -> f += Finding(40, "Pwnagotchi (handshake-collecting Wi-Fi toy) name")
            l.contains("evil twin") || l.contains("eviltwin") || l.contains("wifiphisher") || l.contains("fluxion") -> f += Finding(40, "Network name taken from a Wi-Fi phishing tool")
            l == "free wifi" || l == "free_wifi" || l == "free public wifi" -> f += Finding(10, "Generic \"Free WiFi\" name: the classic bait name (often harmless)")
        }
        return f
    }

    /** Evidence from a hardware address (both Wi-Fi BSSIDs and public BLE addresses). */
    fun macFindings(mac: String): List<Finding> {
        val o = Signatures.oui(mac)
        return if (o in HAK5_OUI) listOf(Finding(60, "Hak5 hardware address ($o): Wi-Fi Pineapple / Coconut")) else emptyList()
    }

    /** Evidence from a BLE advertised name. */
    fun bleNameFindings(name: String?): List<Finding> {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return emptyList()
        val l = n.lowercase(Locale.US)
        return when {
            flipperName.matches(n) -> listOf(Finding(45, "Flipper Zero (multi-tool: can clone, replay and spam signals; often just a hobbyist)"))
            l.startsWith("marauder") -> listOf(Finding(50, "ESP32 Marauder name (Wi-Fi/BLE attack tool)"))
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

/** Networks you know: name -> best security rank seen and the OUIs that served it. Learned after 10 min near a network. */
class SsidMemory {
    class Known(var bestRank: Int, val ouis: LinkedHashSet<String>, var minutes: Int)

    val known = LinkedHashMap<String, Known>()
    var dirty = false

    /** Call every minute per active, named, non-randomized network. */
    fun touch(ssid: String, caps: String, bssid: String, minutesNear: Int) {
        if (ssid.isBlank() || minutesNear < 10) return
        val r = WifiSecurity.rank(caps)
        val k = known.getOrPut(ssid) { dirty = true; Known(r, LinkedHashSet(), 0) }
        if (r > k.bestRank) { k.bestRank = r; dirty = true }
        val o = Signatures.oui(bssid)
        if (!VendorDb.isRandomizedMac(bssid) && k.ouis.add(o)) dirty = true
        if (k.ouis.size > 16) { k.ouis.remove(k.ouis.first()); dirty = true }
        if (minutesNear > k.minutes) k.minutes = minutesNear
        if (known.size > 400) { known.remove(known.keys.first()); dirty = true }
    }

    /**
     * Evil-twin evidence for an access point claiming a known network name: weaker security than that network ever
     * used (classic captive-portal twin), or a maker never seen for it (new router, mesh node, or a copy: weak alone).
     */
    fun findings(ssid: String, caps: String, bssid: String): List<Finding> {
        val k = known[ssid] ?: return emptyList()
        val f = ArrayList<Finding>()
        val r = WifiSecurity.rank(caps)
        if (r < k.bestRank && k.bestRank >= 3 && r <= 2) f += Finding(45, "Claims to be \"$ssid\" (a network you know as ${rankName(k.bestRank)}) but is ${rankName(r)}: possible evil twin")
        val o = Signatures.oui(bssid)
        if (!VendorDb.isRandomizedMac(bssid) && k.ouis.isNotEmpty() && o !in k.ouis) f += Finding(15, "New access-point maker for \"$ssid\" ($o): a new router or mesh node, or a copy of the name")
        return f
    }

    private fun rankName(r: Int) = when (r) { 5 -> "WPA3"; 4 -> "OWE"; 3 -> "WPA2"; 2 -> "WPA"; 1 -> "WEP"; else -> "open" }

    fun dump(): String = known.entries.joinToString("\n") { (s, k) -> listOf(s.replace("\t", " "), k.bestRank, k.minutes, k.ouis.joinToString(",")).joinToString("\t") }

    fun load(text: String) {
        for (line in text.split('\n')) {
            val p = line.split('\t')
            if (p.size < 4 || p[0].isBlank()) continue
            known[p[0]] = Known(p[1].toIntOrNull() ?: 0, LinkedHashSet(p[3].split(',').filter { it.isNotBlank() }), p[2].toIntOrNull() ?: 0)
        }
        dirty = false
    }
}
