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
 * Places you mark (home, work) and what is normally there. A place is "learned" once you have spent 30+ minutes there
 * on 3 different (local) days; from then on fixed gear with a fixed address that was never seen there is "new here".
 * Phones, wearables, vehicles and anything with a random address are never part of this: it is about a new camera,
 * hub, router or tag with a fixed address, not about people. Learning never stops. Pure Kotlin; persisted by the engine.
 */
class Places {
    class Place(val name: String, val lat: Double, val lon: Double) {
        /** Fixed device identities seen here -> last seen (ms). */
        val known = LinkedHashMap<String, Long>()
        var days = 0
        var lastDay = -1
        var lastAt = 0L
        /** Minutes of presence on the current day (a day counts once it reaches [DAY_MINUTES]). */
        var minutesToday = 0
        val learned get() = days >= LEARN_DAYS
    }

    val places = ArrayList<Place>()
    var dirty = false
    private var lastLearn = 0L

    companion object {
        const val RADIUS_M = 150.0
        const val LEARN_DAYS = 3
        const val DAY_MINUTES = 30
        const val MAX_KNOWN = 2000
        /** Weaker than this is a neighbour's edge-of-range gear, not something "here". */
        const val MIN_RSSI = -75

        /** Fixed gear with a fixed address: the only thing a baseline can remember honestly. */
        fun eligible(d: Device): Boolean {
            val random = d.randomAddr ?: VendorDb.isRandomizedMac(d.id)
            if (random) return false
            return when (d.category) {
                Category.PHONE, Category.WEARABLE, Category.VEHICLE, Category.DRONE -> false
                else -> true
            }
        }

        fun identity(d: Device): String? = if (eligible(d)) d.kind.name + ":" + d.id else null

        fun localDay(t: Long): Int = ((t + java.util.TimeZone.getDefault().getOffset(t)) / 86_400_000L).toInt()
    }

    private fun norm(name: String) = name.trim().take(24)

    fun add(name: String, lat: Double, lon: Double): Place {
        val n = norm(name)
        places.removeAll { it.name.equals(n, ignoreCase = true) }
        val p = Place(n, lat, lon)
        places += p
        dirty = true
        return p
    }

    fun remove(name: String) { val n = norm(name); if (places.removeAll { it.name.equals(n, ignoreCase = true) }) dirty = true }

    fun at(lat: Double?, lon: Double?): Place? {
        if (lat == null || lon == null) return null
        return places.minByOrNull { Geo.distanceM(lat, lon, it.lat, it.lon) }?.takeIf { Geo.distanceM(lat, lon, it.lat, it.lon) <= RADIUS_M }
    }

    /**
     * Once a minute while at a place: remember what is here, and return the devices that are new at a learned place
     * (present 3+ min and reasonably strong, so a passer-by or a neighbour's edge-of-range gear doesn't count).
     * A device found new keeps its "Never seen at X before" note for the rest of the session.
     */
    fun learn(here: Place?, active: List<Device>, now: Long): List<Device> {
        if (here == null) { for (d in active) if (!d.newAtPlace) d.placeNote = ""; return emptyList() }
        if (now - lastLearn < 60_000) return emptyList()
        lastLearn = now
        val d0 = localDay(now)
        if (d0 != here.lastDay) { here.lastDay = d0; here.minutesToday = 0 }
        here.minutesToday++
        if (here.minutesToday == DAY_MINUTES) { here.days++; dirty = true }
        here.lastAt = now
        val fresh = ArrayList<Device>()
        for (d in active) {
            val id = identity(d)
            if (id == null) { if (!d.newAtPlace) d.placeNote = ""; continue }
            val elsewhere = places.firstOrNull { it !== here && id in it.known }
            if (id !in here.known) {
                if (now - d.presentSince < 3 * 60_000L || d.smoothRssi < MIN_RSSI) continue // not really "here" yet
                if (here.learned) {
                    d.placeNote = "Never seen at ${here.name} before" + (elsewhere?.let { " (known from ${it.name})" } ?: "")
                    d.newAtPlace = true
                    if (!d.placeAlerted) { d.placeAlerted = true; fresh += d }
                }
                if (here.known.size >= MAX_KNOWN) here.known.remove(here.known.minByOrNull { it.value }!!.key)
                here.known[id] = now; dirty = true
            } else {
                here.known[id] = now
                if (!d.newAtPlace) d.placeNote = if (elsewhere != null) "Usual here; also known from ${elsewhere.name}" else ""
            }
        }
        return fresh
    }

    fun dump(): String = places.joinToString("\n") { p ->
        listOf(java.net.URLEncoder.encode(p.name, "UTF-8"), String.format(Locale.US, "%.5f", p.lat), String.format(Locale.US, "%.5f", p.lon), p.days, p.lastDay, p.lastAt,
            p.known.entries.joinToString(",") { java.net.URLEncoder.encode(it.key, "UTF-8") + "@" + it.value }, p.minutesToday).joinToString("\t")
    }

    fun load(text: String) {
        places.clear()
        for (line in text.split('\n')) {
            val f = line.split('\t')
            if (f.size < 7 || f[0].isBlank()) continue
            val p = Place(java.net.URLDecoder.decode(f[0], "UTF-8"), f[1].toDoubleOrNull() ?: continue, f[2].toDoubleOrNull() ?: continue)
            p.days = f[3].toIntOrNull() ?: 0; p.lastDay = f[4].toIntOrNull() ?: -1; p.lastAt = f[5].toLongOrNull() ?: 0L
            for (k in f[6].split(',')) if (k.isNotBlank()) p.known[java.net.URLDecoder.decode(k.substringBefore('@'), "UTF-8")] = k.substringAfter('@', "0").toLongOrNull() ?: 0L
            p.minutesToday = f.getOrNull(7)?.toIntOrNull() ?: 0
            places += p
        }
        dirty = false
    }
}

/**
 * Wi-Fi disruption watch. A phone can't see deauth frames, so this is an indicator, never proof: all the networks
 * that were steadily visible vanishing at once while you stand still (and Bluetooth still hears things, so it isn't a
 * lift or a basement), or your own Wi-Fi connection dropping again and again. Fed only by fresh scan results.
 */
class DisruptionWatch {
    private var steadyScans = 0
    private var lastCount = 0
    private var emptyScans = 0
    private var emptySince = 0L
    private var lastVanishAt = 0L
    private val drops = ArrayList<Long>()

    /** A read that doesn't count (stale list, throttled): forget any pending "vanished" evidence. */
    fun skipped() { emptyScans = 0; emptySince = 0L }

    /**
     * Call for every FRESH Wi-Fi scan with the number of access points heard and how many BLE devices are active.
     * Returns an event text or null.
     */
    fun onScan(apCount: Int, bleActive: Int, speedMps: Double, now: Long): String? {
        var event: String? = null
        if (apCount >= 4) { steadyScans++; emptyScans = 0; emptySince = 0L }
        else if (apCount == 0) {
            emptyScans++
            if (emptySince == 0L) emptySince = now
            // Steady for 5+ scans, then two fresh empty scans over 20+ s, standing still, Bluetooth still hearing things
            if (steadyScans >= 5 && lastCount >= 4 && emptyScans >= 2 && speedMps < 0.7 && bleActive > 0 &&
                now - emptySince >= 20_000 && now - lastVanishAt > 10 * 60_000L) {
                lastVanishAt = now
                event = "All $lastCount nearby Wi-Fi networks vanished at once while you were still (Bluetooth still hears devices): " +
                    "jamming is one explanation; so are a power cut or the phone's Wi-Fi radio resetting"
                steadyScans = 0; emptyScans = 0
            }
        } else { steadyScans = 0; emptyScans = 0; emptySince = 0L }
        if (apCount > 0) lastCount = apCount
        return event
    }

    /** Call when the phone's own Wi-Fi connection is lost (and not replaced within a few seconds); returns an event when it keeps happening. */
    fun onConnectionLost(speedMps: Double, now: Long): String? {
        if (speedMps >= 0.7) return null
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
 * BLE spam detection: Flipper-style floods of fake Apple proximity-pairing / Nearby Action / Fast Pair / Swift Pair
 * advertisements that make phones show pop-ups. Spam packets each use a one-shot random address, while real earbuds
 * keep one address for minutes, so candidates are counted only once they have proven one-shot (never heard again
 * after 5 s). The source can't be identified from the packets; the strongest signal is the closest guess.
 */
class BleSpamWatch {
    private class Cand(val key: String, val at: Long, val rssi: Int)

    private val cands = ArrayList<Cand>()
    private var lastEventAt = 0L
    @Volatile var flooding = false
        private set
    /** Count nothing in the first 30 s after a scan (re)start or screen-on: everything looks new then. */
    var quietUntil = 0L

    /** Is this advertisement one of the pop-up-triggering kinds? */
    fun isPopupKind(adv: Adv): Boolean {
        val apple = adv.mfr[0x004C]
        val appleType = apple?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF }
        return appleType == 0x07 || appleType == 0x0F || // proximity pairing, Nearby Action
            adv.svcData16[0xFE2C]?.size == 3 || // Google Fast Pair in discoverable (model-ID) form, the form spam uses
            adv.mfr[0x0006]?.let { it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x03 } == true // Microsoft Swift Pair
    }

    /** Call for every NEW device whose first advertisement is a pop-up kind. */
    fun onNewPopupDevice(key: String, rssi: Int, now: Long) {
        if (now < quietUntil) return
        cands += Cand(key, now, rssi)
        cands.removeAll { now - it.at > 20_000 }
    }

    /**
     * Call every few seconds with a way to look up a device by key: candidates older than 5 s that were never heard
     * again are one-shot spam packets; 15+ of those within 10 s is a flood. Returns an event text once per 5 min.
     */
    fun check(now: Long, lookup: (String) -> Device?): String? {
        cands.removeAll { now - it.at > 20_000 }
        val oneShot = cands.filter { c -> now - c.at in 5_000..15_000 && lookup(c.key)?.let { it.readings <= 2 && it.lastSeen - it.firstSeen < 2_000 } != false }
        val window = oneShot.filter { c -> oneShot.count { kotlin.math.abs(it.at - c.at) <= 10_000 } >= 15 }
        flooding = window.isNotEmpty()
        if (flooding && now - lastEventAt > 5 * 60_000L) {
            lastEventAt = now
            val best = window.maxOf { it.rssi }
            return "BLE pairing spam: ${window.size} one-shot \"earbuds / pairing\" addresses within 10 s (Flipper-style pop-up flood). Strongest at $best dBm: the source is probably within a few meters of that"
        }
        return null
    }
}
