package com.k.airscope

import java.util.Locale

enum class Kind { WIFI, BLE, MAP }

enum class Level { LOW, MEDIUM, HIGH }

/** What a device is. `sector` is its wedge on the radar (position around the ring = category). */
enum class Category(val label: String, val short: String, val sector: Int) {
    PHONE("Phones & computers", "PHONE", 0),
    WEARABLE("Wearables & audio", "WEAR", 1),
    TRACKER("Trackers", "TRACK", 2),
    CAMERA_IOT("Cameras & IoT", "CAM/IOT", 3),
    VEHICLE("Vehicles", "VEHICLE", 4),
    DRONE("Drones", "DRONE", 5),
    POLICE("Police, fire, EMS & cameras", "POLICE", 6),
    NETWORK("Networks & other", "NET", 7),
}

/** Search modes shown as chips in the app and web HUD. */
enum class Mode(val label: String, val category: Category?) {
    ALL("ALL", null),
    PHONES("PHONES", Category.PHONE),
    WEAR("WEAR/AUDIO", Category.WEARABLE),
    TRACKERS("TRACKERS", Category.TRACKER),
    CAMS("CAMS/IOT", Category.CAMERA_IOT),
    VEHICLES("VEHICLES", Category.VEHICLE),
    DRONES("DRONES", Category.DRONE),
    POLICE("POLICE/EMS/ALPR", Category.POLICE),
    NETWORKS("NETWORKS", Category.NETWORK),
    /** Not a category: anything with a security finding (attack tool, evil twin, weak network, following you). */
    SECURITY("SECURITY", null);

    fun matches(c: Category) = category == null || category == c
    fun matches(d: DeviceView) = if (this == SECURITY) d.secTags.isNotEmpty() else matches(d.category)
}

/**
 * Text search / watch rules (pure logic). A term matches a device if it appears in its name, broadcast name, maker,
 * label, type, ID (MAC), owner or any detail. Several words = all must match. "cat:tracker" matches a category,
 * "owner:sarah" a device whose name says who owns it ("owner:" alone = any named owner), "sec:" any security finding
 * ("sec:attack", "sec:evil", "sec:weak", "sec:follow").
 */
object DeviceFilter {
    fun matches(d: Device, query: String): Boolean {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return true
        val hay = lazy {
            (listOf(d.id, d.name, d.vendor, d.label, d.category.label, d.category.short, d.bleType, d.displayName(), d.owner) +
                d.aliases + d.facts.values + d.flags).joinToString("\n").lowercase(Locale.US)
        }
        return q.split(' ').filter { it.isNotBlank() }.all { term ->
            when {
                term.startsWith("cat:") -> d.category.name.lowercase(Locale.US).startsWith(term.removePrefix("cat:")) ||
                    d.category.short.lowercase(Locale.US).startsWith(term.removePrefix("cat:"))
                term.startsWith("sec:") -> d.secTags.any { term.length == 4 || it.startsWith(term.removePrefix("sec:")) }
                term.startsWith("owner:") -> d.owner.lowercase(Locale.US).let { it.isNotEmpty() && (term.length == 6 || it.contains(term.removePrefix("owner:"))) }
                else -> hay.value.contains(term)
            }
        }
    }

    /** Same rules for the UI's immutable views (list, map, HUD). */
    fun matchesView(d: DeviceView, query: String): Boolean {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return true
        return q.split(' ').filter { it.isNotBlank() }.all { term ->
            when {
                term.startsWith("cat:") -> d.category.name.lowercase(Locale.US).startsWith(term.removePrefix("cat:")) ||
                    d.category.short.lowercase(Locale.US).startsWith(term.removePrefix("cat:"))
                term.startsWith("sec:") -> d.secTags.any { term.length == 4 || it.startsWith(term.removePrefix("sec:")) }
                term.startsWith("owner:") -> d.owner.lowercase(Locale.US).let { it.isNotEmpty() && (term.length == 6 || it.contains(term.removePrefix("owner:"))) }
                else -> d.searchText.contains(term) || d.title.lowercase(Locale.US).contains(term)
            }
        }
    }
}

/** Scan-radius edge with hysteresis: leave only when clearly outside, return only when clearly inside. */
object RadiusGate {
    fun next(wasIn: Boolean, distM: Double, radiusM: Double): Boolean = if (wasIn) distM <= radiusM * 1.25 else distM <= radiusM * 0.9
}

/** One piece of evidence from the classifier or the scoring rules. */
data class Finding(val points: Int, val text: String)

fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

fun fmtDist(m: Double): String = when {
    m < 10 -> fmt1(m)
    m < 1000 -> m.toInt().toString()
    else -> fmt1(m / 1000) + "k"
}

/** One observed transmitter. Pure Kotlin; owned by the engine thread. */
class Device(val id: String, val kind: Kind, now: Long) {
    val key: String = kind.name + ":" + id

    var name: String = ""
    /** Person named by the device's own broadcast name ("Sarah's AirPods" -> Sarah), else "". */
    var owner: String = ""
    /** The owner came from a hostname on your Wi-Fi (mDNS) rather than from the device's own broadcast name. */
    var ownerFromLan: Boolean = false
    /** Earlier addresses this device used (MAC randomization), newest last. */
    val aliases = ArrayList<String>()
    /** "hostname (ip)" when a device on your Wi-Fi announced the same name (mDNS), else "". */
    var lanNote: String = ""
    var rssi: Int = -100
    var firstSeen: Long = now
    var lastSeen: Long = now
    /** Start of the current continuous sighting (resets after a 2+ minute gap). */
    var presentSince: Long = now
    /** Separate appearances this scan session (gone 3+ min, then back), when the latest began, the last gap. */
    var sightings: Int = 0
    var sightingStart: Long = now
    var lastGapMs: Long = 0L
    /** Where each sighting began (lat, lon), oldest first, at most 10. */
    val sightPlaces = ArrayList<DoubleArray>()
    /** Farthest apart two sightings began (m): showing up again somewhere else matters. */
    var sightSpreadM: Double = 0.0
    var readings: Int = 0
    var lastWifiTs: Long = 0L
    val kalman = RssiKalman()

    // Classification (refreshed whenever new data arrives)
    var category: Category = Category.NETWORK
    var label: String = ""
    var vendor: String = ""
    var bleType: String = ""
    var facts: Map<String, String> = emptyMap()
    var findings: List<Finding> = emptyList()
    var refAt1m: Int? = null
    var refSource: String = ""
    var randomAddr: Boolean? = null
    var classifiedAt: Long = 0L
    var dataHash: Int = 0

    // Wi-Fi
    var frequency: Int = 0
    var security: String = ""
    /** WPS advertised in the beacon. */
    var wps: Boolean = false
    var rttCapable: Boolean = false
    var rttMeters: Double? = null
    var rttStd: Double? = null
    var rttAt: Long = 0L

    // BLE
    var txPower: Int? = null

    // Position reported by the device itself (drone Remote ID)
    var selfLat: Double? = null
    var selfLon: Double? = null

    // Signal samples at GPS positions -> estimated map position
    val track = LocTrack()

    // Signal statistics + radio details
    var rssiMin = 0
    var rssiMax = -200
    var rssiSum = 0.0
    var rssiSq = 0.0
    var lastRxNanos = 0L
    var intervalMs = 0.0
    var connectable: Boolean? = null
    var phy: String? = null

    // Movement relative to you
    var followM = 0.0
    var lastObsNanos = 0L
    var lastObsLat = 0.0
    var lastObsLon = 0.0

    // History (persistent across sessions) + map context
    var firstEver: Long = 0L
    var daysSeen: Int = 0
    var building: String? = null
    var sideFromMap = false

    // Scoring
    /** Security tags set by the scorer: attack-tool, evil-twin, weak-wifi, following. Empty = nothing to report. */
    var secTags: List<String> = emptyList()
    /** The classifier found attack/audit-hardware evidence (Threats). */
    var attackTool: Boolean = false
    /** Place context from Places.learn ("Never seen at Home before"), else "". */
    var placeNote: String = ""
    var placeAlerted: Boolean = false
    var flags: List<String> = emptyList()
    var score: Int = 0
    var maxLevelLogged: Int = 0
    var alerted: Boolean = false
    var trusted: Boolean = false
    /** Inside the scan radius (with hysteresis so it doesn't flicker at the edge). */
    var inRadius: Boolean = true
    /** User-set priority: 0 normal, 1 watch (always listed first, alerts even at LOW), -1 ignore (muted). */
    var priority: Int = 0
    var ruleWatched: Boolean = false

    val smoothRssi: Int
        get() = if (kalman.x.isNaN()) rssi else Math.round(kalman.x).toInt()

    val level: Level
        get() = when {
            score >= 50 -> Level.HIGH
            score >= 25 -> Level.MEDIUM
            else -> Level.LOW
        }

    fun addReading(value: Int, now: Long, rxNanos: Long = 0L) {
        if (now - lastSeen > 120_000) presentSince = now // it went away and came back: a new sighting
        rssi = value
        lastSeen = now
        readings++
        if (readings == 1) rssiMin = value
        rssiMin = minOf(rssiMin, value); rssiMax = maxOf(rssiMax, value)
        rssiSum += value; rssiSq += value.toDouble() * value
        if (rxNanos > 0 && lastRxNanos > 0 && rxNanos > lastRxNanos) {
            val gap = (rxNanos - lastRxNanos) / 1e6
            if (gap < 10_000) intervalMs = if (intervalMs == 0.0) gap else intervalMs * 0.9 + gap * 0.1
        }
        if (rxNanos > 0) lastRxNanos = rxNanos
        kalman.update(value.toDouble(), now, if (kind == Kind.WIFI) 9.0 else 16.0)
    }

    val channel: Int
        get() = when (frequency) {
            2484 -> 14
            in 2412..2483 -> (frequency - 2407) / 5
            in 5000..5899 -> (frequency - 5000) / 5
            in 5925..7125 -> (frequency - 5950) / 5
            else -> 0
        }

    val band: String
        get() = when {
            frequency >= 5925 -> "6 GHz"
            frequency >= 5000 -> "5 GHz"
            frequency > 0 -> "2.4 GHz"
            else -> ""
        }

    fun displayName(): String = when {
        label.isNotBlank() && name.isNotBlank() && !label.contains(name, ignoreCase = true) -> "$name · $label"
        label.isNotBlank() -> label
        name.isNotBlank() -> name
        kind == Kind.WIFI -> "<hidden SSID>"
        else -> "Unknown BLE"
    }

    fun detail(): String = when (kind) {
        Kind.WIFI -> listOf(vendor, "$band ch$channel", Heuristics.securityLabel(security) + " " + WifiSecurity.grade(security, wps).first + (if (wps) " WPS" else ""))
            .filter { it.isNotBlank() }.joinToString(" · ")
        else -> listOf(vendor, category.short).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
