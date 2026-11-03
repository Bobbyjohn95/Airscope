package com.k.airscope

import java.util.Locale

/** One security event (not tied to a single device): what, when, where. Shown in the security log and the report. */
class SecurityEvent(val time: Long, val kind: String, val text: String, val lat: Double?, val lon: Double?)

/** Security events of this and recent sessions (engine thread; the UI reads [snapshot]). */
class SecurityLog {
    val events = ArrayList<SecurityEvent>() // newest first
    @Volatile var snapshot: List<SecurityEvent> = emptyList()
        private set

    fun add(kind: String, text: String, now: Long, lat: Double?, lon: Double?): SecurityEvent {
        val e = SecurityEvent(now, kind, text, lat, lon)
        events.add(0, e)
        while (events.size > 300) events.removeAt(events.size - 1)
        snapshot = ArrayList(events)
        return e
    }

    fun clear() { events.clear(); snapshot = emptyList() }
}

/**
 * Places you mark (home, work) and what is normally there. A place is "learned" once you have been there on 3
 * different days; from then on a device with a stable identity that was never seen there is "new here".
 * Devices with random addresses and no name can't be remembered, so visitors' phones are not part of this: it is
 * about new fixed gear (a camera, a hub, a tracker left behind), not people. Pure Kotlin; persisted by the engine.
 */
class Places {
    class Place(val name: String, val lat: Double, val lon: Double) {
        /** Stable device identities seen here. */
        val known = LinkedHashSet<String>()
        var days = 0
        var lastDay = -1
        var lastAt = 0L
        val learned get() = days >= 3
    }

    val places = ArrayList<Place>()
    var dirty = false
    private var lastLearn = 0L

    companion object {
        const val RADIUS_M = 150.0
        const val LEARN_DAYS = 3

        /** What identifies a device across sessions at a place: a fixed address, or name + maker for random ones. */
        fun identity(d: Device): String? {
            val random = d.randomAddr ?: VendorDb.isRandomizedMac(d.id)
            if (!random) return d.kind.name + ":" + d.id
            val n = d.name.trim()
            return if (n.length >= 3) d.kind.name + ":name:" + n.lowercase(Locale.US) + "|" + d.vendor.lowercase(Locale.US) else null
        }
    }

    fun add(name: String, lat: Double, lon: Double): Place {
        places.removeAll { it.name.equals(name, ignoreCase = true) }
        val p = Place(name.trim().take(24), lat, lon)
        places += p
        dirty = true
        return p
    }

    fun remove(name: String) { if (places.removeAll { it.name.equals(name, ignoreCase = true) }) dirty = true }

    fun at(lat: Double?, lon: Double?): Place? {
        if (lat == null || lon == null) return null
        return places.minByOrNull { Geo.distanceM(lat, lon, it.lat, it.lon) }?.takeIf { Geo.distanceM(lat, lon, it.lat, it.lon) <= RADIUS_M }
    }

    private fun day(t: Long) = (t / 86_400_000L).toInt()

    /**
     * Once a minute while at a place: remember what is here, and return the devices that are new at a learned
     * place (present for 3+ min so a passer-by doesn't count). Also marks each device's place context.
     */
    fun learn(here: Place?, active: List<Device>, now: Long): List<Device> {
        for (d in active) d.placeNote = ""
        if (here == null) return emptyList()
        if (now - lastLearn < 60_000) return emptyList()
        lastLearn = now
        val d0 = day(now)
        if (d0 != here.lastDay) { here.days++; here.lastDay = d0; dirty = true }
        here.lastAt = now
        val fresh = ArrayList<Device>()
        for (d in active) {
            val id = identity(d) ?: continue
            if (now - d.presentSince < 3 * 60_000L) continue
            val elsewhere = places.firstOrNull { it !== here && id in it.known }
            if (id !in here.known) {
                if (here.learned) {
                    d.placeNote = "Never seen at ${here.name} before" + (elsewhere?.let { " (known from ${it.name})" } ?: "")
                    if (!d.placeAlerted) { d.placeAlerted = true; fresh += d }
                }
                if (here.known.size < 2000) { here.known += id; dirty = true }
            } else if (elsewhere != null) {
                d.placeNote = "Usual here; also known from ${elsewhere.name}"
            }
        }
        return fresh
    }

    fun dump(): String = places.joinToString("\n") { p ->
        listOf(java.net.URLEncoder.encode(p.name, "UTF-8"), String.format(Locale.US, "%.5f", p.lat), String.format(Locale.US, "%.5f", p.lon), p.days, p.lastDay, p.lastAt,
            p.known.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") }).joinToString("\t")
    }

    fun load(text: String) {
        places.clear()
        for (line in text.split('\n')) {
            val f = line.split('\t')
            if (f.size < 7 || f[0].isBlank()) continue
            val p = Place(java.net.URLDecoder.decode(f[0], "UTF-8"), f[1].toDoubleOrNull() ?: continue, f[2].toDoubleOrNull() ?: continue)
            p.days = f[3].toIntOrNull() ?: 0; p.lastDay = f[4].toIntOrNull() ?: -1; p.lastAt = f[5].toLongOrNull() ?: 0L
            for (k in f[6].split(',')) if (k.isNotBlank()) p.known += java.net.URLDecoder.decode(k, "UTF-8")
            places += p
        }
        dirty = false
    }
}

/**
 * Wi-Fi disruption watch. A phone can't see deauth frames, so this is an indicator, never proof: all the networks
 * that were steadily visible vanishing at once while you stand still, or your own Wi-Fi connection dropping again
 * and again. Pure Kotlin; fed by the engine every scan.
 */
class DisruptionWatch {
    private var steadyScans = 0
    private var lastCount = 0
    private var emptySince = 0L
    private var lastVanishAt = 0L
    private val drops = ArrayList<Long>()

    /** Call after every Wi-Fi scan with the number of access points heard; returns an event text or null. */
    fun onScan(apCount: Int, speedMps: Double, now: Long): String? {
        var event: String? = null
        if (apCount >= 4) { steadyScans++; emptySince = 0L }
        else if (apCount == 0) {
            if (emptySince == 0L) emptySince = now
            // Networks were steady for 5+ scans, then nothing for 20 s, and you are not driving away from them
            if (steadyScans >= 5 && lastCount >= 4 && speedMps < 2.0 && now - emptySince >= 20_000 && now - lastVanishAt > 10 * 60_000L) {
                lastVanishAt = now
                event = "All $lastCount nearby Wi-Fi networks vanished at once while you were still: jamming or a deauth flood is one explanation (so is a power cut or the phone's Wi-Fi radio resetting)"
                steadyScans = 0
            }
        } else { steadyScans = 0; emptySince = 0L }
        if (apCount > 0) lastCount = apCount
        return event
    }

    /** Call when the phone's own Wi-Fi connection is lost; returns an event text when it keeps happening. */
    fun onConnectionLost(speedMps: Double, now: Long): String? {
        if (speedMps >= 2.0) return null
        drops += now
        drops.removeAll { now - it > 10 * 60_000L }
        if (drops.size >= 3 && now - lastVanishAt > 10 * 60_000L) {
            lastVanishAt = now
            return "Your Wi-Fi connection dropped ${drops.size} times in 10 minutes while you were still: a deauth attack is one explanation (so is a flaky router or weak signal)"
        }
        return null
    }
}

/**
 * BLE spam detection: Flipper-style floods of fake Apple proximity-pairing / Fast Pair / Swift Pair advertisements
 * that make phones show pairing pop-ups. Each packet uses a fresh random address, so the flood shows up as many new
 * "AirPods" appearing per second. The source can't be identified from the packets; the strongest signal is the
 * closest guess. Pure Kotlin.
 */
class BleSpamWatch {
    private val recent = ArrayList<Long>()
    private var bestRssi = -127
    private var lastEventAt = 0L
    @Volatile var flooding = false
        private set

    /** Is this advertisement one of the pop-up-triggering kinds? */
    fun isPopupKind(adv: Adv): Boolean =
        adv.mfr[0x004C]?.let { it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x07 } == true || // Apple proximity pairing
            0xFE2C in adv.svcData16 || // Google Fast Pair
            adv.mfr[0x0006]?.let { it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x03 } == true // Microsoft Swift Pair

    /** Call for every NEW device whose first advertisement is a pop-up kind; returns an event text when a flood starts. */
    fun onNewPopupDevice(rssi: Int, now: Long): String? {
        recent += now
        recent.removeAll { now - it > 10_000 }
        if (rssi > bestRssi) bestRssi = rssi
        val n = recent.size
        flooding = n >= 15
        if (flooding && now - lastEventAt > 5 * 60_000L) {
            lastEventAt = now
            val r = bestRssi; bestRssi = -127
            return "BLE pairing spam: $n new \"AirPods / earbuds\" addresses in 10 s (Flipper-style pop-up flood). Strongest at $r dBm: the source is probably within a few meters of that"
        }
        if (n < 5) bestRssi = -127
        return null
    }
}
