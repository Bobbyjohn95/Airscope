package com.k.airscope

/** All observations plus the intel timeline. Owned by the engine thread only. */
class DeviceStore {
    data class LogEntry(val time: Long, val tag: String, val text: String)

    val devices = LinkedHashMap<String, Device>()
    val timeline = ArrayList<LogEntry>() // newest first
    val trustedKeys = HashSet<String>()

    private var lastSummary = ""

    fun upsert(id: String, kind: Kind, now: Long): Device {
        val key = kind.name + ":" + id
        return devices.getOrPut(key) {
            Device(id, kind, now).also { it.trusted = key in trustedKeys }
        }
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
