package com.k.airscope

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CSV exports and the JSON snapshot served to the web HUD. Pure Kotlin. */
object Exporter {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    const val DEVICE_HEADER =
        "kind,id,name,last_rssi,avg_rssi,est_distance_m,first_seen,last_seen,readings,score,level,details,flags"

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun devicesCsv(store: DeviceStore): String = buildString {
        appendLine(DEVICE_HEADER)
        for (d in store.devices.values.sortedByDescending { it.score }) {
            appendLine(
                listOf(
                    d.kind.name, d.id, csv(d.name), d.rssi, d.smoothRssi, fmt1(d.distanceMeters()),
                    dateFmt.format(Date(d.firstSeen)), dateFmt.format(Date(d.lastSeen)), d.readings,
                    d.score, d.level.name, csv(d.detail()), csv(d.flags.joinToString("; ")),
                ).joinToString(","),
            )
        }
    }

    fun timelineCsv(store: DeviceStore): String = buildString {
        appendLine("time,tag,text")
        for (e in store.timeline) appendLine("${dateFmt.format(Date(e.time))},${e.tag},${csv(e.text)}")
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

    fun deviceJson(d: Device, now: Long): String = buildString {
        append("{")
        append("\"key\":").append(json(d.key))
        append(",\"id\":").append(json(d.id))
        append(",\"kind\":").append(json(d.kind.name))
        append(",\"name\":").append(json(d.displayName()))
        append(",\"rssi\":").append(d.rssi)
        append(",\"avg\":").append(d.smoothRssi)
        append(",\"dist\":").append(fmt1(d.distanceMeters()))
        append(",\"score\":").append(d.score)
        append(",\"level\":").append(json(d.level.name))
        append(",\"detail\":").append(json(d.detail()))
        append(",\"flags\":[").append(d.flags.joinToString(",") { json(it) }).append("]")
        append(",\"ageSec\":").append((now - d.firstSeen) / 1000)
        append(",\"lastSec\":").append((now - d.lastSeen) / 1000)
        append("}")
    }

    fun snapshotJson(store: DeviceStore, now: Long, scanning: Boolean, warning: String?): String = buildString {
        val act = store.active(now).sortedWith(compareByDescending<Device> { it.score }.thenByDescending { it.smoothRssi })
        append("{\"time\":").append(now)
        append(",\"scanning\":").append(scanning)
        append(",\"warning\":").append(if (warning == null) "null" else json(warning))
        append(",\"devices\":[").append(act.joinToString(",") { deviceJson(it, now) }).append("]")
        append(",\"timeline\":[")
        append(store.timeline.take(60).joinToString(",") {
            "{\"time\":${it.time},\"tag\":${json(it.tag)},\"text\":${json(it.text)}}"
        })
        append("]}")
    }
}
