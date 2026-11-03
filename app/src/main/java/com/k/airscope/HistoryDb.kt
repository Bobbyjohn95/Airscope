package com.k.airscope

import java.util.TimeZone

/**
 * Remembers stable devices across sessions (on this phone only): first time ever seen and on how
 * many different days. Randomized addresses are skipped because they change every few minutes.
 */
class HistoryDb {
    class Rec(var first: Long, var last: Long, var days: Int, var lastDay: Int)

    val map = HashMap<String, Rec>()
    var dirty = false

    fun dayOf(t: Long): Int = ((t + TimeZone.getDefault().getOffset(t)) / 86_400_000L).toInt()

    fun touch(key: String, now: Long): Rec {
        val day = dayOf(now)
        val r = map.getOrPut(key) { dirty = true; Rec(now, now, 1, day) }
        r.last = now
        if (day != r.lastDay) { r.days++; r.lastDay = day; dirty = true }
        if (map.size > MAX) trim()
        return r
    }

    private fun trim() {
        val drop = map.entries.sortedBy { it.value.last }.take(map.size - MAX + MAX / 10).map { it.key }
        drop.forEach { map.remove(it) }
        dirty = true
    }

    fun load(lines: Sequence<String>) {
        for (l in lines) {
            val p = l.split('\t')
            if (p.size != 5) continue
            map[p[0]] = Rec(p[1].toLongOrNull() ?: continue, p[2].toLongOrNull() ?: continue, p[3].toIntOrNull() ?: 1, p[4].toIntOrNull() ?: 0)
        }
    }

    fun dump(): String = buildString {
        for ((k, r) in map) append(k).append('\t').append(r.first).append('\t').append(r.last).append('\t').append(r.days).append('\t').append(r.lastDay).append('\n')
    }

    companion object {
        const val MAX = 20_000

        fun shouldTrack(d: Device): Boolean = when (d.kind) {
            Kind.WIFI -> !VendorDb.isRandomizedMac(d.id)
            Kind.BLE -> d.randomAddr == false || d.category == Category.POLICE
            Kind.MAP -> false
        }
    }
}
