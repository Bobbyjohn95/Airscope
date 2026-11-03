package com.k.airscope

/** Scoring: classifier evidence + generic rules. 25+ = MEDIUM, 50+ = HIGH. */
object Heuristics {
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
        if (d.trusted) {
            d.score = 0
            d.flags = listOf("Marked as yours")
            return
        }
        val f = ArrayList<String>()
        var s = 0
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
            }
            if (d.name.isBlank()) {
                s += 10; f += "Hidden SSID"
            } else {
                val twins = active.filter { it.kind == Kind.WIFI && it.key != d.key && it.name == d.name }
                val open = securityLabel(d.security) == "OPEN"
                if (twins.any { (securityLabel(it.security) == "OPEN") != open }) {
                    s += 25; f += "Same SSID seen open and secured (possible evil twin)"
                }
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
                    else -> { s += 10; f += "Has moved with you for $km km (yours or a passenger's?)" }
                }
            }
        }
        if (d.kind == Kind.WIFI && d.followM > 600) { s += 10; f += "Hotspot moving with you (${(d.followM / 1000).let { String.format(java.util.Locale.US, "%.1f", it) }} km)" }

        d.score = s.coerceIn(0, 100)
        d.flags = f
    }

    /** Should this device trigger a pop-up alert (once)? */
    fun wantsAlert(d: Device): Boolean = !d.trusted && (
        d.level == Level.HIGH ||
            (d.bleType in Classifier.TRACKERS && d.level >= Level.MEDIUM) ||
            (d.category == Category.POLICE && d.score >= 45) || // Flock, Axon, Raven, ShotSpotter (not fleet routers alone)
            (d.category == Category.DRONE && d.score >= 25)
        )
}
