package com.k.airscope

/** All observations plus the intel timeline. Owned by the engine thread only. */
class DeviceStore {
    data class LogEntry(val time: Long, val tag: String, val text: String)

    val devices = LinkedHashMap<String, Device>()
    val timeline = ArrayList<LogEntry>() // newest first
    val trustedKeys = HashSet<String>()
    /** Saved watch (+1) / ignore (-1) choices by device key. */
    val priorities = HashMap<String, Int>()
    /** Watch rules: any device matching one of these search terms is treated as watched. */
    @Volatile var watchRules: List<String> = emptyList()

    private var lastSummary = ""

    /** When the current scan session started (START pressed). */
    var sessionStart = 0L

    fun upsert(id: String, kind: Kind, now: Long): Device {
        val key = kind.name + ":" + id
        var fresh = false
        val d = devices.getOrPut(key) {
            fresh = true
            Device(id, kind, now).also { it.trusted = key in trustedKeys; it.priority = priorities[key] ?: 0 }
        }
        // Separate sightings this session: it went quiet for 3+ minutes and came back
        when {
            fresh || d.lastSeen < sessionStart || d.sightings == 0 -> { d.sightings = 1; d.sightingStart = now; d.sightPlaces.clear(); d.sightSpreadM = 0.0 }
            now - d.lastSeen > SIGHTING_GAP_MS -> {
                d.sightings++
                d.sightingStart = now
                d.lastGapMs = now - d.lastSeen
                log("AGAIN", "${d.displayName()} is back (seen ${d.sightings}× this session, gone ${(d.lastGapMs / 60_000)} min)", now)
            }
        }
        return d
    }

    companion object {
        const val SIGHTING_GAP_MS = 3 * 60_000L
        /** A rotated address has to show up within this long after the old one went quiet. */
        const val LINK_WINDOW_MS = 90_000L

        /**
         * What stays the same when a device changes its random address: its broadcast name plus maker and type.
         * Null when there is nothing specific enough to link on (no name, or a fixed public address that never changes).
         */
        fun fingerprint(d: Device): String? {
            val n = d.name.trim()
            if (n.length < 3) return null
            val random = d.randomAddr ?: VendorDb.isRandomizedMac(d.id)
            if (!random) return null
            return if (d.kind == Kind.WIFI) "WIFI|$n|${Heuristics.securityLabel(d.security)}|${d.vendor}"
            else "BLE|$n|${d.vendor}|${d.label}|${d.bleType}"
        }
    }

    private var lastLinkAt = 0L

    /**
     * Phones, earbuds and hotspots change their random address every so often; without this each change would look
     * like a brand-new device and "stayed with you for 40 min" would reset. A device that went quiet and was replaced
     * within [LINK_WINDOW_MS] by exactly one new address carrying the same name + maker + type is treated as the same
     * device: presence, sightings, watch/ignore and "mine" carry over, and the old address is kept as an alias.
     * Nothing is linked when the name is shared by other devices nearby (two "Galaxy Buds2" stay separate).
     */
    fun linkRotated(now: Long): List<Pair<Device, Device>> {
        if (now - lastLinkAt < 5_000) return emptyList()
        lastLinkAt = now
        val byPrint = HashMap<String, ArrayList<Device>>()
        for (d in devices.values) fingerprint(d)?.let { byPrint.getOrPut(it) { ArrayList() } += d }
        val linked = ArrayList<Pair<Device, Device>>()
        for ((_, group) in byPrint) {
            if (group.size < 2) continue
            val gone = group.filter { !isActive(it, now) && now - it.lastSeen < 20 * 60_000L }
            for (old in gone) {
                val successors = group.filter { it !== old && it.firstSeen >= old.lastSeen - 5_000 && it.firstSeen <= old.lastSeen + LINK_WINDOW_MS && it.readings > 0 }
                val others = group.filter { it !== old && it !in successors && isActive(it, now) }
                if (successors.size != 1 || others.isNotEmpty()) continue
                val new = successors[0]
                if (devices[new.key] !== new || devices[old.key] !== old) continue
                merge(old, new)
                devices.remove(old.key)
                linked += old to new
                log("LINK", "${new.displayName()} changed address ${old.id} -> ${new.id} (same name and maker)", now)
            }
        }
        return linked
    }

    private fun merge(old: Device, new: Device) {
        new.aliases += old.aliases
        new.aliases += old.id
        while (new.aliases.size > 12) new.aliases.removeAt(0)
        new.firstSeen = minOf(old.firstSeen, new.firstSeen)
        if (new.firstSeen - old.lastSeen < 120_000) new.presentSince = minOf(old.presentSince, new.presentSince)
        if (old.sightings > new.sightings) {
            new.sightings = old.sightings; new.sightingStart = old.sightingStart; new.lastGapMs = old.lastGapMs
            new.sightPlaces.clear(); new.sightPlaces += old.sightPlaces; new.sightSpreadM = old.sightSpreadM
        }
        new.followM += old.followM
        if (new.firstEver == 0L) { new.firstEver = old.firstEver; new.daysSeen = old.daysSeen }
        if (new.priority == 0 && old.priority != 0) { new.priority = old.priority; new.ruleWatched = old.ruleWatched }
        if (old.trusted) new.trusted = true
        new.alerted = new.alerted || old.alerted
        new.maxLevelLogged = maxOf(new.maxLevelLogged, old.maxLevelLogged)
        if (new.owner.isBlank()) new.owner = old.owner
    }

    fun isActive(d: Device, now: Long): Boolean =
        now - d.lastSeen < (if (d.kind == Kind.WIFI) 75_000L else 30_000L)

    fun active(now: Long): List<Device> = devices.values.filter { isActive(it, now) }

    fun log(tag: String, text: String, now: Long = System.currentTimeMillis()) {
        timeline.add(0, LogEntry(now, tag, text))
        while (timeline.size > 500) timeline.removeAt(timeline.size - 1)
    }

    /** Re-scores active devices; returns devices that should raise an alert now (each only once). */
    fun rescore(now: Long): List<Device> {
        val act = active(now)
        val alerts = ArrayList<Device>()
        for (d in act) {
            // Watch rules: match by text (re-checked every rescore because names/facts arrive over time)
            if (d.priority == 0 && priorities[d.key] == null && watchRules.any { DeviceFilter.matches(d, it) }) {
                d.priority = 1; d.ruleWatched = true
            } else if (d.ruleWatched && watchRules.none { DeviceFilter.matches(d, it) }) { d.priority = 0; d.ruleWatched = false }
            Heuristics.score(d, act, now)
            val lvl = d.level.ordinal
            if (lvl > d.maxLevelLogged) {
                d.maxLevelLogged = lvl
                log(d.level.name, "${d.category.short} ${d.displayName()} [${d.id}] ${d.flags.joinToString("; ")}", now)
            }
            if (!d.alerted && Heuristics.wantsAlert(d)) {
                d.alerted = true
                alerts += d
            }
        }
        val wifi = act.count { it.kind == Kind.WIFI }
        val police = act.count { it.category == Category.POLICE }
        val summary = "wifi=$wifi ble=${act.size - wifi} med=${act.count { it.level == Level.MEDIUM }} " +
            "high=${act.count { it.level == Level.HIGH }}" + if (police > 0) " police=$police" else ""
        if (summary != lastSummary) {
            lastSummary = summary
            log("SCAN", summary, now)
        }
        if (devices.size > 3000) devices.values.removeAll { now - it.lastSeen > 15 * 60_000L }
        return alerts
    }

    fun setTrusted(key: String, on: Boolean) {
        val d = devices[key] ?: return
        d.trusted = on
        if (on) trustedKeys += key else trustedKeys -= key
        d.maxLevelLogged = 0
        d.alerted = on
    }

    fun clear() {
        devices.clear()
        timeline.clear()
        lastSummary = ""
    }
}
