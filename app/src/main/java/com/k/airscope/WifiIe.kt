package com.k.airscope

/** A Wi-Fi information element from a beacon/probe response (id 221 = vendor specific). */
class Ie(val id: Int, val data: ByteArray)

/** What we could learn from a network's information elements. */
class WifiIeInfo {
    var wpsManufacturer: String? = null
    var wpsModel: String? = null
    var wpsModelNumber: String? = null
    var wpsDeviceName: String? = null
    var wpsCategory: Int? = null
    /** A WPS information element is present (WPS enabled on the access point). */
    var wps: Boolean = false
    var remoteId: Boolean = false
    var djiDroneId: Boolean = false
    val vendorOuis = LinkedHashSet<String>()
    var stations: Int? = null
    var channelUtil: Int? = null
    var country: String? = null
    var rrm = false
    var bssTransition = false
    var fastRoaming = false
    var passpoint = false
    var apName: String? = null

    fun wpsLabel(): String? {
        val parts = listOfNotNull(wpsManufacturer, wpsModel ?: wpsModelNumber)
            .map { it.trim() }.filter { it.isNotEmpty() && it != "-" }
        if (parts.isEmpty()) return wpsDeviceName?.trim()?.ifEmpty { null }
        val mfr = parts[0]
        return if (parts.size > 1 && parts[1].startsWith(mfr, ignoreCase = true)) parts[1] else parts.joinToString(" ")
    }
}

object WifiIe {
    /** WPS primary device type categories (Wi-Fi Simple Configuration spec). */
    val WPS_CATEGORIES = mapOf(
        1 to "Computer", 2 to "Input device", 3 to "Printer/scanner", 4 to "Camera", 5 to "Storage",
        6 to "Network infrastructure", 7 to "Display/TV", 8 to "Multimedia device", 9 to "Gaming device",
        10 to "Phone", 11 to "Audio device",
    )

    fun parse(ies: List<Ie>): WifiIeInfo {
        val info = WifiIeInfo()
        for (ie in ies) {
            val v = ie.data
            when (ie.id) {
                11 -> if (v.size >= 3) { // BSS Load: station count (LE16), channel utilization (0-255)
                    info.stations = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
                    info.channelUtil = ((v[2].toInt() and 0xFF) * 100 + 127) / 255
                }
                7 -> if (v.size >= 2) String(v, 0, 2, Charsets.US_ASCII).takeIf { s -> s.all { it in 'A'..'Z' } }?.let { info.country = it }
                70 -> info.rrm = true
                54 -> info.fastRoaming = true
                127 -> if (v.size >= 3 && (v[2].toInt() and 0x08) != 0) info.bssTransition = true
                133 -> if (v.size >= 26) { // Cisco Aironet element: AP name at offset 10
                    val n = String(v, 10, 16, Charsets.US_ASCII).replace("\u0000", "").trim()
                    if (n.length >= 2 && n.all { it in ' '..'~' }) info.apName = n
                }
            }
            if (ie.id != 221 || ie.data.size < 4) continue
            val d = ie.data
            val oui = String.format("%02x:%02x:%02x", d[0].toInt() and 0xFF, d[1].toInt() and 0xFF, d[2].toInt() and 0xFF)
            val type = d[3].toInt() and 0xFF
            info.vendorOuis += oui
            when {
                oui == "00:50:f2" && type == 0x04 -> parseWps(d, info)
                oui == "fa:0b:bc" && type == 0x0D -> info.remoteId = true // ASTM F3411 Remote ID beacon
                oui == "26:37:12" -> info.djiDroneId = true // DJI proprietary DroneID
                oui == "50:6f:9a" && type == 0x10 -> info.passpoint = true
            }
        }
        return info
    }

    private fun parseWps(d: ByteArray, info: WifiIeInfo) {
        info.wps = true
        var i = 4
        while (i + 4 <= d.size) {
            val t = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
            val len = ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
            val start = i + 4
            if (start + len > d.size) break
            val v = d.copyOfRange(start, start + len)
            fun str() = String(v, Charsets.UTF_8).replace("\u0000", "").trim().ifEmpty { null }
            when (t) {
                0x1011 -> info.wpsDeviceName = str()
                0x1021 -> info.wpsManufacturer = str()
                0x1023 -> info.wpsModel = str()
                0x1024 -> info.wpsModelNumber = str()
                0x1054 -> if (len >= 2) info.wpsCategory = ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
            }
            i = start + len
        }
    }
}

/** ASTM F3411 drone Remote ID (Bluetooth service data 0xFFFA or Wi-Fi beacon). */
object RemoteId {
    class Info {
        var serial: String? = null
        var lat: Double? = null
        var lon: Double? = null
        var altM: Double? = null
        var speedMs: Double? = null
    }

    /** `data` = service-data payload for UUID 0xFFFA: [appCode 0x0D][counter][message 25 bytes...]. */
    fun parseBle(data: ByteArray): Info? {
        if (data.size < 3 || (data[0].toInt() and 0xFF) != 0x0D) return null
        val info = Info()
        parseMessage(data, 2, info)
        return info
    }

    /** [inPack]: a message pack may not contain another pack (spec), which also stops crafted infinite nesting. */
    private fun parseMessage(d: ByteArray, o: Int, info: Info, inPack: Boolean = false) {
        if (o >= d.size) return
        val type = (d[o].toInt() and 0xF0) shr 4
        when (type) {
            0x0 -> if (o + 22 <= d.size) { // Basic ID: UAS ID is 20 ASCII bytes at offset 2
                val s = String(d, o + 2, 20, Charsets.US_ASCII).replace("\u0000", "").trim()
                if (s.isNotEmpty() && s.all { it in ' '..'~' }) info.serial = s
            }
            0x1 -> if (o + 17 <= d.size) { // Location/vector
                val lat = le32(d, o + 5) / 1e7
                val lon = le32(d, o + 9) / 1e7
                if (lat != 0.0 && lon != 0.0 && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    info.lat = lat; info.lon = lon
                }
                val geoAlt = ((d[o + 15].toInt() and 0xFF) or ((d[o + 16].toInt() and 0xFF) shl 8))
                if (geoAlt != 0) info.altM = geoAlt * 0.5 - 1000
                val mult = if ((d[o + 1].toInt() and 0x01) != 0) 0.75 else 0.25
                val sp = d[o + 3].toInt() and 0xFF
                info.speedMs = if (mult == 0.75) sp * 0.75 + 255 * 0.25 else sp * 0.25
            }
            0xF -> if (!inPack && o + 3 <= d.size) { // Message pack
                val size = d[o + 1].toInt() and 0xFF
                val count = d[o + 2].toInt() and 0xFF
                if (size == 25) for (k in 0 until count.coerceAtMost(9)) parseMessage(d, o + 3 + k * size, info, inPack = true)
            }
        }
    }

    private fun le32(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or
            ((d[o + 2].toInt() and 0xFF) shl 16) or ((d[o + 3].toInt() and 0xFF) shl 24)
}
