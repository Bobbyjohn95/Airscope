package com.k.airscope

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CSV exports and the JSON snapshot served to the web HUD. Pure Kotlin. */
object Exporter {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    const val DEVICE_HEADER =
        "kind,id,category,label,name,vendor,avg_rssi,est_distance_m,dist_low_m,dist_high_m,dist_method,lat,lon,pos_err_m,seen_sec,score,level,details,flags"

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun devicesCsv(views: List<DeviceView>): String = buildString {
        appendLine(DEVICE_HEADER)
        for (d in views.sortedByDescending { it.score }) {
            appendLine(
                listOf(
                    d.kind.name, d.id, d.category.name, csv(d.title), csv(d.name), csv(d.vendor), d.avg,
                    fmt1(d.dist), fmt1(d.lo), fmt1(d.hi), csv(d.distSrc),
                    d.lat?.let { String.format(Locale.US, "%.6f", it) } ?: "", d.lon?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                    if (d.located) d.errM.toInt().toString() else "", d.ageSec, d.score, d.level.name,
                    csv(d.facts.joinToString("; ") { "${it.first}: ${it.second}" }), csv(d.flags.joinToString("; ")),
                ).joinToString(","),
            )
        }
    }

    fun sessionCsv(rows: List<Engine.SessionRow>): String = buildString {
        appendLine("kind,id,category,name,vendor,times_seen,first_seen,last_seen,best_rssi,first_spots_apart_m,score")
        for (r in rows) {
            appendLine(listOf(r.kind.name, r.key.substringAfter(':'), r.category.name, csv(r.title), csv(r.vendor), r.sightings,
                dateFmt.format(Date(r.firstSeen)), dateFmt.format(Date(r.lastSeen)), r.bestRssi, r.spreadM.toInt(), r.score).joinToString(","))
        }
    }

    fun timelineCsv(log: List<DeviceStore.LogEntry>): String = buildString {
        appendLine("time,tag,text")
        for (e in log) appendLine("${dateFmt.format(Date(e.time))},${e.tag},${csv(e.text)}")
    }

    fun json(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(Locale.US, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun deviceJson(d: DeviceView): String = buildString {
        append("{\"key\":").append(json(d.key))
        append(",\"id\":").append(json(d.id))
        append(",\"kind\":").append(json(d.kind.name))
        append(",\"cat\":").append(json(d.category.name))
        append(",\"name\":").append(json(d.title))
        append(",\"vendor\":").append(json(d.vendor))
        append(",\"avg\":").append(d.avg)
        append(",\"dist\":").append(fmt1(d.dist))
        append(",\"lo\":").append(fmt1(d.lo))
        append(",\"hi\":").append(fmt1(d.hi))
        append(",\"src\":").append(json(d.distSrc))
        append(",\"score\":").append(d.score)
        append(",\"level\":").append(json(d.level.name))
        append(",\"detail\":").append(json(d.detail))
        append(",\"angle\":").append(fmt1(d.angle.toDouble()))
        append(",\"lat\":").append(d.lat?.let { String.format(Locale.US, "%.6f", it) } ?: "null")
        append(",\"lon\":").append(d.lon?.let { String.format(Locale.US, "%.6f", it) } ?: "null")
        append(",\"err\":").append(d.errM.toInt())
        append(",\"spots\":").append(d.spots)
        if (d.candA != null && d.candB != null) append(String.format(Locale.US, ",\"cand\":[[%.6f,%.6f],[%.6f,%.6f]]", d.candA.first, d.candA.second, d.candB.first, d.candB.second))
        append(",\"bearing\":").append(d.bearing?.let { fmt1(it.toDouble()) } ?: "null")
        append(",\"flags\":[").append(d.flags.joinToString(",") { json(it) }).append("]")
        append(",\"facts\":[").append(d.facts.joinToString(",") { "[${json(it.first)},${json(it.second)}]" }).append("]")
        append(",\"ageSec\":").append(d.ageSec)
        append("}")
    }

    private fun pts(a: FloatArray): String = a.joinToString(",", "[", "]") { (Math.round(it * 10) / 10.0).toString() }

    /** Street-map geometry for the web HUD (local meters around lat/lon). */
    /**
     * Several map tiles merged into one frame (around lat0/lon0) for the web HUD. Buildings only within
     * [bldM] of the center to keep it small; label-only street-name lines are sent with class 10+.
     */
    fun mapJsonMerged(chunks: List<MapData>, lat0: Double, lon0: Double, bldM: Double = 900.0): String {
        if (chunks.isEmpty()) return "{\"lat\":null}"
        val p = LocalProj(lat0, lon0)
        fun shifted(m: MapData, pts: FloatArray): String {
            val ox = p.x(m.lon0).toFloat(); val oy = p.y(m.lat0).toFloat()
            val sb = StringBuilder("[")
            var i = 0
            while (i < pts.size) {
                if (i > 0) sb.append(',')
                sb.append(Math.round(pts[i] + ox)).append(',').append(Math.round(pts[i + 1] + oy))
                i += 2
            }
            return sb.append(']').toString()
        }
        return buildString {
            append(String.format(Locale.US, "{\"lat\":%.6f,\"lon\":%.6f", lat0, lon0))
            append(",\"roads\":[")
            var first = true
            for (m in chunks) for (r in m.roads) {
                if (!first) append(','); first = false
                append('[').append(if (r.labelOnly) r.cls + 10 else r.cls).append(',').append(json(r.name ?: "")).append(',').append(shifted(m, r.pts)).append(']')
            }
            append("],\"bld\":[")
            first = true
            for (m in chunks) {
                val ox = p.x(m.lon0); val oy = p.y(m.lat0)
                for (b in m.buildings) {
                    if (kotlin.math.hypot(b.cx + ox, b.cy + oy) > bldM) continue
                    if (!first) append(','); first = false
                    append('[').append(fmt1(b.heightM.toDouble())).append(',').append(shifted(m, b.pts)).append(']')
                }
            }
            append("],\"areas\":[")
            first = true
            for (m in chunks) for (a in m.areas) {
                if (!first) append(','); first = false
                append('[').append(a.kind).append(',').append(shifted(m, a.pts)).append(']')
            }
            append("]}")
        }
    }

    fun mapJson(m: MapData?): String {
        if (m == null) return "{\"lat\":null}"
        return buildString {
            append(String.format(Locale.US, "{\"lat\":%.6f,\"lon\":%.6f", m.lat0, m.lon0))
            append(",\"roads\":[").append(m.roads.joinToString(",") { "[${it.cls},${json(it.name ?: "")},${pts(it.pts)}]" }).append("]")
            append(",\"bld\":[").append(m.buildings.joinToString(",") { "[${fmt1(it.heightM.toDouble())},${pts(it.pts)}]" }).append("]")
            append(",\"areas\":[").append(m.areas.joinToString(",") { "[${it.kind},${pts(it.pts)}]" }).append("]}")
        }
    }

    /** Monotonic clock matching Location.elapsedRealtimeNanos (overridable in tests). */
    /** Phone heading (deg true, +-deg) when known; set by the Engine. */
    @Volatile var heading: () -> Pair<Double, Double>? = { null }

    @Volatile var elapsedNanos: () -> Long = { System.nanoTime() }
    private fun android_elapsedNanos() = elapsedNanos()

    fun snapshotJson(s: Snapshot): String = buildString {
        append("{\"time\":").append(s.time)
        append(",\"scanning\":").append(s.scanning)
        append(",\"warning\":").append(s.warning?.let { json(it) } ?: "null")
        append(",\"env\":").append(json(s.env.label))
        append(",\"alpr\":").append(json(s.alprStatus))
        append(",\"mapStatus\":").append(json(s.mapStatus))
        append(",\"mapV\":").append(s.mapVersion)
        append(",\"me\":")
        if (s.myLat != null && s.myLon != null) {
            val age = if (s.fixNanos > 0) ((android_elapsedNanos() - s.fixNanos) / 1_000_000).coerceAtLeast(0) else 0
            val hd = heading()
            append(String.format(Locale.US, "{\"lat\":%.7f,\"lon\":%.7f,\"acc\":%.1f,\"course\":%s,\"vx\":%.2f,\"vy\":%.2f,\"ageMs\":%d,\"gnss\":%s,\"hdg\":%s,\"hdgAcc\":%d}",
                s.myLat, s.myLon, s.myAccM, s.myCourse?.let { fmt1(it) } ?: "null", s.myVx, s.myVy, age, json(s.gnss),
                hd?.first?.let { fmt1(it) } ?: "null", (hd?.second ?: 0.0).toInt()))
        } else append("null")
        append(",\"devices\":[").append(s.devices.joinToString(",") { deviceJson(it) }).append("]")
        append(",\"timeline\":[")
        append(s.timeline.take(60).joinToString(",") { "{\"time\":${it.time},\"tag\":${json(it.tag)},\"text\":${json(it.text)}}" })
        append("]}")
    }
}
