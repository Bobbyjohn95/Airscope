package com.k.airscope

/** Immutable view of one device for the UI / web HUD (built on the engine thread). */
class DeviceView(
    val key: String,
    val id: String,
    val kind: Kind,
    val category: Category,
    val title: String,
    val name: String,
    val vendor: String,
    val detail: String,
    val rssi: Int,
    val avg: Int,
    val dist: Double,
    val lo: Double,
    val hi: Double,
    val distSrc: String,
    val score: Int,
    val level: Level,
    val flags: List<String>,
    val facts: List<Pair<String, String>>,
    val ageSec: Long,
    val lastSec: Long,
    val trusted: Boolean,
    val inRadius: Boolean = true,
    /** Everything searchable about it, lowercased (name, maker, id, facts), for the search box. */
    val searchText: String = "",
    /** 1 = watch list, 0 normal, -1 ignored. */
    val priority: Int = 0,
    /** Stable placeholder angle (deg) used only while a device's position is unknown. */
    val angle: Float,
    /** Compass bearing from you (deg) when its position is known. */
    val bearing: Float?,
    /** Map position: exact (mapped ALPR, drone GPS) or estimated from your movement; null if unknown. */
    val lat: Double?,
    val lon: Double?,
    /** Position uncertainty radius in meters. */
    val errM: Double,
    /** Number of distinct GPS spots it was measured from. */
    val spots: Int,
    /** Meters you've moved while measuring it (bigger = better fix). */
    val spreadM: Double,
    /** Two equally likely spots (one on each side of your path) when the position is ambiguous. */
    val candA: Pair<Double, Double>? = null,
    val candB: Pair<Double, Double>? = null,
    /** Meters it has stayed with you while you moved. */
    val followM: Double = 0.0,
    /** Its readings say it was moving: no fixed spot. */
    val moving: Boolean = false,
    /** Separate appearances this scan session (2+ = it came back) and how far apart they began (m). */
    val sightings: Int = 1,
    val sightSpreadM: Double = 0.0,
    /** Person the device's own name says it belongs to, else "". */
    val owner: String = "",
) {
    /** "↻3" when it came back this session, else "". */
    val againMark: String get() = if (sightings >= 2) "↻$sightings" else ""
    val located: Boolean get() = lat != null
}

class Snapshot(
    val time: Long,
    val scanning: Boolean,
    val warning: String?,
    val devices: List<DeviceView>,
    val timeline: List<DeviceStore.LogEntry>,
    val env: Env,
    val alprStatus: String,
    val mapStatus: String,
    val version: Long,
    val myLat: Double?,
    val myLon: Double?,
    val myAccM: Double,
    val myCourse: Double?,
    val mapVersion: Long,
    /** Your velocity (m/s east, north) and the elapsedRealtime time of the position, for smooth prediction. */
    val myVx: Double = 0.0,
    val myVy: Double = 0.0,
    val fixNanos: Long = 0L,
    val gnss: String = "",
) {
    val hasFix: Boolean get() = myLat != null

    companion object {
        val EMPTY = Snapshot(0, false, null, emptyList(), emptyList(), Env.INDOOR, "", "", 0, null, null, 0.0, null, 0)

        fun hashAngle(key: String): Float = ((key.hashCode().toLong() and 0xffffffffL) % 360).toFloat()

        private val timeFmt = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US)

        fun view(d: Device, env: Env, now: Long, fixLat: Double?, fixLon: Double?, meshCount: Int = 0): DeviceView {
            var est = Distance.estimate(d, env, now)
            var lat: Double? = null
            var lon: Double? = null
            var err = 0.0
            val tr = d.track
            if (d.selfLat != null && d.selfLon != null) {
                lat = d.selfLat; lon = d.selfLon; err = 10.0
            } else if (tr.located) {
                lat = tr.estLat; lon = tr.estLon; err = tr.errM
            }
            val ambiguous = lat == null && tr.ambiguous && tr.altLat != null
            if (ambiguous) err = tr.rawErrM
            var bearing: Float? = null
            if (lat != null && lon != null && fixLat != null && fixLon != null) {
                val m = Geo.distanceM(fixLat, fixLon, lat, lon)
                bearing = Geo.bearingDeg(fixLat, fixLon, lat, lon).toFloat()
                val half = (est.hi - est.lo) / 2
                if (d.selfLat != null) {
                    est = DistEst(m, m * 0.9, m * 1.1 + 5, "GPS position broadcast by the device")
                } else if (err < half) {
                    est = DistEst(m, (m - err).coerceAtLeast(0.1), m + err, "map fix from ${tr.obs.size} spots (±${err.toInt()} m)")
                }
            }
            // Live details on top of what the classifier found
            val facts = ArrayList<Pair<String, String>>()
            facts += d.facts.entries.map { it.key to it.value }
            if (d.readings > 1) {
                val mean = d.rssiSum / d.readings
                val sd = kotlin.math.sqrt(kotlin.math.max(0.0, d.rssiSq / d.readings - mean * mean))
                facts += "Signal stats" to String.format(java.util.Locale.US, "min %d / avg %.0f / max %d dBm · ±%.1f · %d readings", d.rssiMin, mean, d.rssiMax, sd, d.readings)
            }
            if (d.kind == Kind.BLE && d.intervalMs > 0) facts += "Advertising" to "about every ${d.intervalMs.toInt()} ms" +
                (d.connectable?.let { if (it) " · connectable" else " · broadcast-only" } ?: "") + (d.phy?.let { " · $it" } ?: "")
            if (meshCount > 1) facts += "Same network name" to "$meshCount access points (mesh / multi-AP network)"
            if (d.firstEver > 0) facts += "History" to "first seen ${timeFmt.format(java.util.Date(d.firstEver))} · seen on ${d.daysSeen} day${if (d.daysSeen == 1) "" else "s"}"
            else facts += "First seen" to timeFmt.format(java.util.Date(d.firstSeen))
            if (d.sightings >= 2) {
                facts += "↻ Seen again" to "${d.sightings} separate times this session (gone ${d.lastGapMs / 60_000} min before coming back)" +
                    if (d.sightSpreadM >= 300) " · first spots ${fmtDist(d.sightSpreadM)} m apart" else ""
            }
            if (d.followM > 50) facts += "Moved with you" to "${fmtDist(d.followM)} m"
            if (d.owner.isNotBlank()) facts += "Owner" to "${d.owner} (that's what the device's own name says: nothing was looked up)"
            if (d.lanNote.isNotBlank()) facts += "On your Wi-Fi" to d.lanNote
            if (d.aliases.isNotEmpty()) facts += "Earlier addresses" to d.aliases.takeLast(4).joinToString(", ") + " (same name and maker: linked across its address changes)"
            when {
                d.selfLat != null -> facts += "Map position" to "reported by the device (GPS)"
                lat != null -> facts += "Map position" to String.format(java.util.Locale.US, "%.5f, %.5f ±%d m · %d spots over %d m",
                    lat, lon, err.toInt(), tr.usedObs, tr.spreadM.toInt()) + if (d.sideFromMap) " · side picked from street map" else ""
                ambiguous -> facts += "Map position" to "one of two spots ±${err.toInt()} m (either side of your path): walk a different way to settle it"
                tr.moving -> facts += "Map position" to "moving (or carried): no fixed spot to show, distance only"
                else -> facts += "Map position" to "not located yet (${tr.obs.size} spots over ${tr.spreadM.toInt()} m)"
            }
            tr.p0?.let { facts += "Learned signal level" to "${it.toInt()} dBm at 1 m (improves distance)" }
            d.building?.let { facts += "Likely inside" to "$it (OpenStreetMap)" }

            return DeviceView(
                key = d.key, id = d.id, kind = d.kind, category = d.category, title = d.displayName(),
                name = d.name, vendor = d.vendor, detail = d.detail(), rssi = d.rssi, avg = d.smoothRssi,
                dist = est.meters, lo = est.lo, hi = est.hi, distSrc = est.source, score = d.score, level = d.level,
                flags = d.flags, facts = facts,
                ageSec = (now - d.firstSeen) / 1000, lastSec = (now - d.lastSeen) / 1000, trusted = d.trusted,
                inRadius = d.inRadius, priority = d.priority,
                searchText = (listOf(d.id, d.name, d.vendor, d.label, d.category.label, d.category.short, d.bleType, d.owner) + d.aliases + d.facts.values + d.flags)
                    .joinToString("\n").lowercase(java.util.Locale.US),
                angle = hashAngle(d.key), bearing = bearing, lat = lat, lon = lon, errM = err,
                spots = tr.obs.size, spreadM = tr.spreadM,
                candA = if (ambiguous) tr.estLat to tr.estLon else null,
                candB = if (ambiguous) tr.altLat!! to tr.altLon!! else null,
                followM = d.followM,
                moving = tr.moving,
                sightings = d.sightings.coerceAtLeast(1), sightSpreadM = d.sightSpreadM,
                owner = d.owner,
            )
        }

        fun alprView(h: AlprHit, now: Long): DeviceView {
            val a = h.alpr
            val facts = ArrayList<Pair<String, String>>()
            facts += "Source" to "OpenStreetMap / DeFlock (node ${a.id})"
            a.manufacturer?.let { facts += "Manufacturer" to it }
            a.operator?.let { facts += "Operator" to it }
            a.direction?.let { facts += "Camera faces" to it }
            facts += "Position" to String.format(java.util.Locale.US, "%.5f, %.5f", a.lat, a.lon)
            facts += "Bearing from you" to "${h.bearing.toInt()}° (${Geo.cardinal(h.bearing)})"
            return DeviceView(
                key = "MAP:${a.id}", id = "osm:${a.id}", kind = Kind.MAP, category = Category.POLICE,
                title = a.title(), name = "", vendor = a.manufacturer ?: "", detail = "mapped · ${Geo.cardinal(h.bearing)}",
                rssi = 0, avg = 0, dist = h.dist, lo = h.dist * 0.95, hi = h.dist * 1.05, distSrc = "GPS + mapped location",
                score = if (h.dist < 300) 60 else 30, level = if (h.dist < 300) Level.HIGH else Level.MEDIUM,
                flags = listOf("Mapped ${a.kindLabel()} ${fmtDist(h.dist)} m ${Geo.cardinal(h.bearing)}"),
                facts = facts, ageSec = 0, lastSec = 0, trusted = false,
                angle = h.bearing.toFloat(), bearing = h.bearing.toFloat(), lat = a.lat, lon = a.lon, errM = 5.0,
                spots = 0, spreadM = 0.0,
            )
        }
    }
}
