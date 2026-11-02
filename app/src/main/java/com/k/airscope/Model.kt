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
    POLICE("Police & ALPR", "POLICE", 6),
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
    POLICE("POLICE/ALPR", Category.POLICE),
    NETWORKS("NETWORKS", Category.NETWORK);

    fun matches(c: Category) = category == null || category == c
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
    var rssi: Int = -100
    val firstSeen: Long = now
    var lastSeen: Long = now
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
    var rttCapable: Boolean = false
    var rttMeters: Double? = null
    var rttStd: Double? = null
    var rttAt: Long = 0L

    // BLE
    var txPower: Int? = null

    // Position reported by the device itself (drone Remote ID)
    var selfLat: Double? = null
    var selfLon: Double? = null

    // Scoring
    var flags: List<String> = emptyList()
    var score: Int = 0
    var maxLevelLogged: Int = 0
    var alerted: Boolean = false
    var trusted: Boolean = false

    val smoothRssi: Int
        get() = if (kalman.x.isNaN()) rssi else Math.round(kalman.x).toInt()

    val level: Level
        get() = when {
            score >= 50 -> Level.HIGH
            score >= 25 -> Level.MEDIUM
            else -> Level.LOW
        }

    fun addReading(value: Int, now: Long) {
        rssi = value
        lastSeen = now
        readings++
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
        Kind.WIFI -> listOf(vendor, "$band ch$channel", Heuristics.securityLabel(security))
            .filter { it.isNotBlank() }.joinToString(" · ")
        else -> listOf(vendor, category.short).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
