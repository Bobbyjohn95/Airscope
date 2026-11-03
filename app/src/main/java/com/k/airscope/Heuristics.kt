package com.k.airscope

/** Scoring: classifier evidence + generic rules. 25+ = MEDIUM, 50+ = HIGH. */
object Heuristics {
    /** 0 = police/trackers/drones/watched only, 1 = normal, 2 = everything MEDIUM and up. */
    @Volatile var alertLevel = 1
    /** Networks you know (for evil-twin checks); set by the engine, null in tests that don't need it. */
    @Volatile var ssidMemory: SsidMemory? = null
    /** Your current position (engine thread), for the "near where you learned it" check. */
    @Volatile var myLat: Double? = null
    @Volatile var myLon: Double? = null

    const val PERSIST_MS = 10 * 60_000L

    fun securityLabel(caps: String): String = when {
        caps.contains("WEP") -> "WEP"
        caps.contains("SAE") -> "WPA3"
        caps.contains("OWE") -> "OWE"
        caps.contains("RSN") || caps.contains("WPA2") -> "WPA2"
        caps.contains("WPA") -> "WPA"
        else -> "OPEN"
    }

    fun score(d: Device, active: List<Device>, now: Long) {
        if (d.trusted || d.priority < 0) {
            d.score = 0
            d.flags = listOf(if (d.trusted) "Marked as yours" else "Ignored (muted)")
            d.secTags = emptyList()
            return
        }
        val f = ArrayList<String>()
        val sec = ArrayList<String>()
        var s = 0
        if (d.attackTool) sec += "attack-tool"
        if (d.placeNote.startsWith("Never seen")) { s += 25; f += d.placeNote + " (fixed gear with a fixed address; phones and wearables are never counted)"; sec += "new-here" }
        else if (d.placeNote.isNotBlank()) f += d.placeNote
        if (d.priority > 0) { s += 40; f += "On your watch list" }
        for (x in d.findings) {
            s += x.points; f += x.text
        }
        // "Stayed with you" means continuously: a tag seen this morning and again this afternoon doesn't count.
        val present = now - d.presentSince
        val mins = present / 60_000

        if (d.kind == Kind.WIFI) {
            when (securityLabel(d.security)) {
                "WEP" -> { s += 20; f += "WEP encryption (broken)" }
                "OPEN" -> { s += 15; f += "Open network (no encryption)" }
                "WPA" -> { s += 10; f += "WPA1 only (outdated, crackable)" }
            }
            if (d.wps) { s += 5; f += "WPS on (PIN attack risk on older routers)" }
            if (WifiSecurity.isWeak(d.security, d.wps)) sec += "weak-wifi"
            if (d.name.isBlank()) {
                s += 10; f += "Hidden SSID"
            } else {
                val twins = active.filter { it.kind == Kind.WIFI && it.key != d.key && it.name == d.name }
                val open = securityLabel(d.security) == "OPEN"
                if (twins.any { (securityLabel(it.security) == "OPEN") != open }) {
                    // Venues legitimately run an open portal and a secured network under one name: noted, no alert by itself
                    s += if (open) 25 else 5
                    f += if (open) "Same name as a secured network nearby but open (a venue's guest portal, or an evil twin)" else "An open copy of this network's name is nearby (see that one)"
                }
                val mem = ssidMemory?.findings(d.name, d.security, d.id, myLat, myLon).orEmpty()
                for (x in mem) { s += x.points; f += x.text; if (x.points >= 40) sec += "evil-twin" }
            }
            if (VendorDb.isRandomizedMac(d.id)) {
                s += 10; f += "Randomized MAC (phone hotspot, guest or mesh network)"
                if (present > PERSIST_MS && d.category == Category.PHONE) {
                    s += 15; f += "Mobile hotspot nearby for $mins min"
                }
            }
            if (d.smoothRssi > -35) { s += 10; f += "Very close (about 1–2 m)" }
        } else {
            if (present > PERSIST_MS) {
                when {
                    d.bleType in Classifier.TRACKERS -> { s += 30; f += "Tracker has stayed with you for $mins min" }
                    d.category == Category.POLICE -> { s += 10; f += "Present for $mins min" }
                    d.category != Category.NETWORK -> { s += 10; f += "Nearby for $mins min" }
                }
            }
            if (d.smoothRssi > -45) { s += 10; f += "Very close (about 1 m)" }
            // Came back this session somewhere else: a classic sign of something traveling with you
            if (d.sightings >= 2 && d.sightSpreadM >= 1000 && d.category != Category.NETWORK && d.category != Category.CAMERA_IOT) {
                val pts = if (d.bleType in Classifier.TRACKERS) 20 else 10
                s += pts; f += "Showed up again ${fmtDist(d.sightSpreadM)} m from where it was first seen (${d.sightings}× this session)"
            }
            if (d.followM > 400) {
                val km = String.format(java.util.Locale.US, "%.1f", d.followM / 1000)
                when {
                    d.bleType in Classifier.TRACKERS -> { s += 35; f += "Tracker has moved with you for $km km" }
                    d.category == Category.POLICE -> { s += 15; f += "Has moved with you for $km km" }
                    d.followM > 2000 && !fixedGear(d) -> { s += 15; f += "Has moved with you for $km km (a passenger's device, your own gear, or something hidden: mark it if it's yours)" }
                    else -> { s += 10; f += "Has moved with you for $km km (yours or a passenger's?)" }
                }
            }
            // Following you across places: 3+ separate appearances that began far apart in one session. Moving with you
            // alone is not enough (every passenger's phone does that), and a bus/train full of devices is not a tail.
            val crowd = active.count { it.kind == Kind.BLE && it.followM > 2000 && !fixedGear(it) && it.bleType !in Classifier.TRACKERS }
            if (d.sightings >= 3 && d.sightSpreadM >= 1500 && !fixedGear(d)) {
                s += 15; f += "Appeared ${d.sightings} separate times, ${fmtDist(d.sightSpreadM)} m apart: showing up wherever you go"
                if (crowd <= 4) sec += "following" else f += "(many devices are travelling with you right now: transit or a group, so no alert)"
            }
        }
        if (d.kind == Kind.WIFI && d.followM > 600) { s += 10; f += "Hotspot moving with you (${(d.followM / 1000).let { String.format(java.util.Locale.US, "%.1f", it) }} km)" }

        d.score = s.coerceIn(0, 100)
        d.flags = f
        d.secTags = sec.distinct()
    }

    /** Fixed infrastructure that cannot "follow" anyone: access points, beacons, IoT gear. */
    fun fixedGear(d: Device): Boolean = d.kind == Kind.WIFI || d.category == Category.CAMERA_IOT ||
        d.label.contains("beacon", ignoreCase = true) || d.label.contains("access point", ignoreCase = true) || d.label.contains("router", ignoreCase = true)

    /** Should this device trigger a pop-up alert (once)? Security tags alert at MEDIUM unless alerts are "critical only". */
    fun wantsAlert(d: Device): Boolean = !d.trusted && d.priority >= 0 && (d.priority > 0 ||
        (alertLevel >= 1 && d.level >= Level.MEDIUM && ("attack-tool" in d.secTags || "evil-twin" in d.secTags || "following" in d.secTags || "new-here" in d.secTags)) ||
        (alertLevel >= 2 && d.level >= Level.MEDIUM) ||
        (alertLevel >= 1 && d.level == Level.HIGH) ||
            (d.bleType in Classifier.TRACKERS && d.level >= Level.MEDIUM) ||
            (d.category == Category.POLICE && d.score >= 45) || // Flock, Axon, Raven, ShotSpotter (not fleet routers alone)
            (d.category == Category.DRONE && d.score >= 25)
        )
}
