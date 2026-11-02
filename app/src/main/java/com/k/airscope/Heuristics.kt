package com.k.airscope

/** Risk scoring. Each rule adds points and a human-readable reason. 25+ = MEDIUM, 50+ = HIGH. */
object Heuristics {
    const val PERSIST_MS = 10 * 60_000L

    private val cameraName = Regex(
        "(cam|ipc|dvr|nvr|wyze|v380|tuya|smartlife|esp[_-]|blink|arlo|^ring|yi[-_]|xmeye|jxl|spy|hidvr|hikvision|dahua|reolink|eufy)",
        RegexOption.IGNORE_CASE,
    )
    private val hotspotName = Regex(
        "(iphone|android|galaxy|pixel|hotspot|direct-|moto|oneplus|redmi)",
        RegexOption.IGNORE_CASE,
    )

    fun securityLabel(caps: String): String = when {
        caps.contains("WEP") -> "WEP"
        caps.contains("SAE") -> "WPA3"
        caps.contains("OWE") -> "OWE"
        caps.contains("RSN") || caps.contains("WPA2") -> "WPA2"
        caps.contains("WPA") -> "WPA"
        else -> "OPEN"
    }

    /** Locally-administered bit set = randomized MAC (phones, hotspots). */
    fun isRandomizedMac(mac: String): Boolean {
        val first = mac.take(2).toIntOrNull(16) ?: return false
        return (first and 0x02) != 0
    }

    fun score(d: Device, active: List<Device>, now: Long) {
        if (d.trusted) {
            d.score = 0
            d.flags = listOf("Marked as yours")
            return
        }
        val f = ArrayList<String>()
        var s = 0
        val present = now - d.firstSeen
        val mins = present / 60_000

        if (d.kind == Kind.WIFI) {
            val sec = securityLabel(d.security)
            if (sec == "WEP") {
                s += 20; f += "WEP encryption (broken)"
            } else if (sec == "OPEN") {
                s += 15; f += "Open network (no encryption)"
            }
            if (d.name.isBlank()) {
                s += 10; f += "Hidden SSID"
            } else {
                if (cameraName.containsMatchIn(d.name)) {
                    s += 30; f += "Camera/IoT-style network name"
                }
                if (hotspotName.containsMatchIn(d.name)) {
                    s += 5; f += "Mobile hotspot name"
                }
                val twins = active.filter { it.kind == Kind.WIFI && it.key != d.key && it.name == d.name }
                if (twins.any { (securityLabel(it.security) == "OPEN") != (sec == "OPEN") }) {
                    s += 25; f += "Same SSID seen open and secured (possible evil twin)"
                }
            }
            val randomized = isRandomizedMac(d.id)
            if (randomized) {
                s += 10; f += "Randomized MAC (usually a phone hotspot)"
                if (present > PERSIST_MS) {
                    s += 15; f += "Mobile hotspot nearby for $mins min"
                }
            }
        } else {
            when (d.bleType) {
                BleClassifier.FINDMY_SEPARATED, BleClassifier.FINDHUB_SEPARATED -> {
                    s += 40; f += d.bleType
                }
                in BleClassifier.TRACKERS -> {
                    s += 35; f += d.bleType
                }
                BleClassifier.FINDMY_NEAR -> {
                    s += 10; f += d.bleType
                }
                BleClassifier.IBEACON, BleClassifier.EDDYSTONE -> {
                    s += 10; f += "Beacon (${d.bleType})"
                }
            }
            if (present > PERSIST_MS) {
                if (d.bleType in BleClassifier.TRACKERS) {
                    s += 30; f += "Tracker has stayed with you for $mins min"
                } else {
                    s += 10; f += "Nearby for $mins min"
                }
            }
        }

        if (d.smoothRssi > -45) {
            s += 10; f += "Very close (about 1 m)"
        }

        d.score = s.coerceAtMost(100)
        d.flags = f
    }
}
