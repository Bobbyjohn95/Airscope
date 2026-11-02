package com.k.airscope

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CSV exports and the JSON snapshot served to the web HUD. Pure Kotlin. */
object Exporter {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    const val DEVICE_HEADER =
        "kind,id,category,label,name,vendor,avg_rssi,est_distance_m,dist_low_m,dist_high_m,dist_method,seen_sec,score,level,details,flags"

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun devicesCsv(views: List<DeviceView>): String = buildString {
        appendLine(DEVICE_HEADER)
        for (d in views.sortedByDescending { it.score }) {
            appendLine(
                listOf(
                    d.kind.name, d.id, d.category.name, csv(d.title), csv(d.name), csv(d.vendor), d.avg,
                    fmt1(d.dist), fmt1(d.lo), fmt1(d.hi), csv(d.distSrc), d.ageSec, d.score, d.level.name,
                    csv(d.facts.joinToString("; ") { "${it.first}: ${it.second}" }), csv(d.flags.joinToString("; ")),
                ).joinToString(","),
            )
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
        append(",\"bearing\":").append(d.bearing?.let { fmt1(it.toDouble()) } ?: "null")
        append(",\"flags\":[").append(d.flags.joinToString(",") { json(it) }).append("]")
        append(",\"facts\":[").append(d.facts.joinToString(",") { "[${json(it.first)},${json(it.second)}]" }).append("]")
        append(",\"ageSec\":").append(d.ageSec)
        append("}")
    }

    fun snapshotJson(s: Snapshot): String = buildString {
        append("{\"time\":").append(s.time)
        append(",\"scanning\":").append(s.scanning)
        append(",\"warning\":").append(s.warning?.let { json(it) } ?: "null")
        append(",\"env\":").append(json(s.env.label))
        append(",\"alpr\":").append(json(s.alprStatus))
        append(",\"devices\":[").append(s.devices.joinToString(",") { deviceJson(it) }).append("]")
        append(",\"timeline\":[")
        append(s.timeline.take(60).joinToString(",") { "{\"time\":${it.time},\"tag\":${json(it.tag)},\"text\":${json(it.text)}}" })
        append("]}")
    }
}
