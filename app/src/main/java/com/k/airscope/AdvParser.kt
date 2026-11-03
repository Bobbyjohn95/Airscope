package com.k.airscope

import java.util.Locale

/** Parsed Bluetooth LE advertisement (legacy or extended). */
class Adv {
    var name: String? = null
    var txPower: Int? = null
    var appearance: Int? = null
    var flags: Int? = null
    val mfr = LinkedHashMap<Int, ByteArray>()
    val uuid16 = LinkedHashSet<Int>()
    val uuid128 = LinkedHashSet<String>()
    val svcData16 = LinkedHashMap<Int, ByteArray>()
    val svcData128 = LinkedHashMap<String, ByteArray>()
}

/** Parses raw advertising data (AD structures). Pure Kotlin so it can be unit tested. */
object AdvParser {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    fun parse(b: ByteArray?): Adv {
        val a = Adv()
        if (b == null) return a
        var i = 0
        while (i < b.size) {
            val len = b[i].toInt() and 0xFF
            if (len == 0 || i + len > b.size - 1) break // zero padding or truncated structure
            val type = b[i + 1].toInt() and 0xFF
            val d = b.copyOfRange(i + 2, i + 1 + len)
            when (type) {
                0x01 -> if (d.isNotEmpty()) a.flags = d[0].toInt() and 0xFF
                0x02, 0x03 -> for (j in 0 until d.size / 2) a.uuid16 += le16(d, j * 2)
                0x06, 0x07 -> for (j in 0 until d.size / 16) addUuid128(a, uuid128(d, j * 16))
                0x08, 0x09 -> if (d.isNotEmpty()) a.name = String(d, Charsets.UTF_8).trim { it <= ' ' }
                0x0A -> if (d.isNotEmpty()) a.txPower = d[0].toInt()
                0x16 -> if (d.size >= 2) a.svcData16[le16(d, 0)] = d.copyOfRange(2, d.size)
                0x19 -> if (d.size >= 2) a.appearance = le16(d, 0)
                0x21 -> if (d.size >= 16) {
                    val u = uuid128(d, 0)
                    val rest = d.copyOfRange(16, d.size)
                    val s = short16(u)
                    if (s != null) a.svcData16[s] = rest else a.svcData128[u] = rest
                }
                0xFF -> if (d.size >= 2) {
                    val id = le16(d, 0)
                    if (!a.mfr.containsKey(id)) a.mfr[id] = d.copyOfRange(2, d.size)
                }
            }
            i += len + 1
        }
        return a
    }

    private fun addUuid128(a: Adv, u: String) {
        val s = short16(u)
        if (s != null) a.uuid16 += s else a.uuid128 += u
    }

    fun le16(d: ByteArray, o: Int): Int = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    /** 16 little-endian bytes -> canonical lowercase UUID string. */
    fun uuid128(d: ByteArray, o: Int): String {
        val sb = StringBuilder()
        for (k in 15 downTo 0) {
            sb.append(String.format(Locale.US, "%02x", d[o + k].toInt() and 0xFF))
            if (k == 12 || k == 10 || k == 8 || k == 6) sb.append('-')
        }
        return sb.toString()
    }

    /** "0000xxxx-0000-1000-8000-00805f9b34fb" -> 0xXXXX, else null. */
    fun short16(u: String): Int? {
        val s = u.lowercase(Locale.US)
        if (s.length != 36 || !s.endsWith(BASE_SUFFIX) || !s.startsWith("0000")) return null
        return s.substring(4, 8).toIntOrNull(16)
    }

    fun hex(d: ByteArray, max: Int = 32): String =
        d.take(max).joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) } +
            if (d.size > max) "…" else ""
}
