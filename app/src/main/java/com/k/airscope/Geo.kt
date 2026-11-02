package com.k.airscope

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north (0..360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Smallest absolute difference between two headings (0..180). */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs((a - b) % 360)
        return if (d > 180) 360 - d else d
    }

    fun cardinal(deg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg % 360) + 360) % 360 / 45 + 0.5).toInt() % 8]
    }
}

/** A mapped surveillance device from OpenStreetMap (DeFlock tagging). */
data class Alpr(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val type: String, // "ALPR" or "gunshot_detector"
    val manufacturer: String?,
    val operator: String?,
    val direction: String?,
) {
    fun kindLabel() = if (type == "gunshot_detector") "gunshot detector" else "ALPR camera"

    fun title(): String = when {
        type == "gunshot_detector" -> (manufacturer?.let { "$it " } ?: "") + "gunshot detector (mapped)"
        manufacturer != null -> "$manufacturer ALPR (mapped)"
        else -> "ALPR camera (mapped)"
    }
}

class AlprHit(val alpr: Alpr, val dist: Double, val bearing: Double)

object AlprLogic {
    const val RADIUS_M = 8_000
    const val REFETCH_MOVE_M = 4_000.0

    fun overpassQuery(lat: Double, lon: Double, radius: Int = RADIUS_M): String {
        val c = String.format(java.util.Locale.US, "%d,%.6f,%.6f", radius, lat, lon)
        return "[out:json][timeout:25];(" +
            "node[\"man_made\"=\"surveillance\"][\"surveillance:type\"=\"ALPR\"](around:$c);" +
            "node[\"man_made\"=\"surveillance\"][\"surveillance:type\"=\"gunshot_detector\"](around:$c);" +
            ");out body;"
    }

    fun parse(json: String): List<Alpr> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        val els = root["elements"] as? List<*> ?: return emptyList()
        return els.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val lat = (m["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lon = (m["lon"] as? Number)?.toDouble() ?: return@mapNotNull null
            val tags = m["tags"] as? Map<*, *> ?: emptyMap<String, Any>()
            Alpr(
                id = (m["id"] as? Number)?.toLong() ?: 0L, lat = lat, lon = lon,
                type = tags["surveillance:type"] as? String ?: "ALPR",
                manufacturer = tags["manufacturer"] as? String ?: tags["brand"] as? String,
                operator = tags["operator"] as? String,
                direction = (tags["direction"] ?: tags["camera:direction"])?.toString(),
            )
        }
    }

    fun hits(list: List<Alpr>, lat: Double, lon: Double, maxM: Double = 2_000.0): List<AlprHit> =
        list.map { AlprHit(it, Geo.distanceM(lat, lon, it.lat, it.lon), Geo.bearingDeg(lat, lon, it.lat, it.lon)) }
            .filter { it.dist <= maxM }
            .sortedBy { it.dist }

    /** Driving: alert when it's ahead within 400 m. Walking/standing: within 150 m. */
    fun shouldAlert(h: AlprHit, speedMs: Double?, courseDeg: Double?): Boolean {
        val moving = speedMs != null && speedMs >= 3.0 && courseDeg != null
        return if (moving) h.dist <= 400 && Geo.angleDiff(h.bearing, courseDeg!!) <= 45 else h.dist <= 150
    }
}
