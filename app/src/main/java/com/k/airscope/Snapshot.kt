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
    /** Ground angle on the radar in degrees: the category's wedge, or real bearing for mapped/self-located items. */
    val angle: Float,
    /** True compass bearing (degrees) when known (mapped ALPRs, drones reporting GPS); null otherwise. */
    val bearing: Float?,
)

class Snapshot(
    val time: Long,
    val scanning: Boolean,
    val warning: String?,
    val devices: List<DeviceView>,
    val timeline: List<DeviceStore.LogEntry>,
    val env: Env,
    val hasFix: Boolean,
    val alprStatus: String,
    val version: Long,
) {
    companion object {
        val EMPTY = Snapshot(0, false, null, emptyList(), emptyList(), Env.INDOOR, false, "", 0)

        /** Stable angle inside the category's 45° wedge (5°..40° within it). */
        fun wedgeAngle(key: String, c: Category): Float {
            val h = (key.hashCode().toLong() and 0xffffffffL) % 35
            return (c.sector * 45 + 5 + h).toFloat()
        }

        fun view(d: Device, env: Env, now: Long, fixLat: Double?, fixLon: Double?): DeviceView {
            var est = Distance.estimate(d, env, now)
            var bearing: Float? = null
            if (d.selfLat != null && d.selfLon != null && fixLat != null && fixLon != null) {
                val m = Geo.distanceM(fixLat, fixLon, d.selfLat!!, d.selfLon!!)
                est = DistEst(m, m * 0.9, m * 1.1, "GPS position broadcast by the device")
                bearing = Geo.bearingDeg(fixLat, fixLon, d.selfLat!!, d.selfLon!!).toFloat()
            }
            return DeviceView(
                key = d.key, id = d.id, kind = d.kind, category = d.category, title = d.displayName(),
                name = d.name, vendor = d.vendor, detail = d.detail(), rssi = d.rssi, avg = d.smoothRssi,
                dist = est.meters, lo = est.lo, hi = est.hi, distSrc = est.source, score = d.score, level = d.level,
                flags = d.flags, facts = d.facts.entries.map { it.key to it.value },
                ageSec = (now - d.firstSeen) / 1000, lastSec = (now - d.lastSeen) / 1000, trusted = d.trusted,
                angle = wedgeAngle(d.key, d.category), bearing = bearing,
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
                angle = h.bearing.toFloat(), bearing = h.bearing.toFloat(),
            )
        }
    }
}
