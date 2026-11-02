package com.k.airscope

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Kind { WIFI, BLE }

enum class Level { LOW, MEDIUM, HIGH }

fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

/** One observed transmitter (a Wi-Fi access point or a BLE advertiser). Pure Kotlin, no Android types. */
class Device(val id: String, val kind: Kind, now: Long) {
    val key: String = kind.name + ":" + id

    var name: String = ""
    var rssi: Int = -100
    val firstSeen: Long = now
    var lastSeen: Long = now
    var readings: Int = 0
    var lastWifiTs: Long = 0L

    private val history = ArrayDeque<Int>()

    // Wi-Fi details
    var frequency: Int = 0
    var security: String = ""

    // BLE details
    var bleType: String = ""
    var manufacturer: String = ""
    var txPower: Int? = null

    // Scoring
    var flags: List<String> = emptyList()
    var score: Int = 0
    var maxLevelLogged: Int = 0
    var trusted: Boolean = false

    val smoothRssi: Int
        get() = if (history.isEmpty()) rssi else history.average().roundToInt()

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
        history.addLast(value)
        while (history.size > 6) history.removeFirst()
    }

    /**
     * Rough distance from signal strength (log-distance path loss, indoor exponent 2.7).
     * Walls, bodies and antennas make this +/- 2-3x; treat it as "near / mid / far".
     */
    fun distanceMeters(): Double {
        val refAt1m = when (kind) {
            Kind.BLE -> txPower?.let { it - 41 } ?: -59
            Kind.WIFI -> if (frequency >= 5000) -45 else -40
        }
        val n = 2.7
        return 10.0.pow((refAt1m - smoothRssi) / (10 * n)).coerceIn(0.3, 99.0)
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
        name.isNotBlank() -> name
        kind == Kind.WIFI -> "<hidden SSID>"
        bleType.isNotBlank() -> bleType
        manufacturer.isNotBlank() -> "$manufacturer device"
        else -> "Unknown BLE"
    }

    fun detail(): String = when (kind) {
        Kind.WIFI -> "$band ch$channel ${Heuristics.securityLabel(security)}"
        Kind.BLE -> listOf(manufacturer, bleType).filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { "BLE" }
    }
}
