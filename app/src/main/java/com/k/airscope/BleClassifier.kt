package com.k.airscope

import java.util.Locale

/**
 * Classifies a BLE advertisement from its raw fields. Pure Kotlin so it can be unit tested.
 *
 * Signatures come from public research on tracker advertising formats
 * (Apple Find My offline finding, Tile, Samsung SmartTag, Chipolo, Google Find Hub,
 * and the cross-platform "unwanted tracker" spec). They are heuristics, not proof.
 */
object BleClassifier {
    const val FINDMY_SEPARATED = "Apple Find My tag (away from owner)"
    const val FINDMY_NEAR = "Apple Find My device (near owner)"
    const val FINDHUB_SEPARATED = "Google Find Hub tag (away from owner)"
    const val FINDHUB = "Google Find Hub tag"
    const val TILE = "Tile tracker"
    const val SMARTTAG = "Samsung SmartTag"
    const val CHIPOLO = "Chipolo tracker"
    const val DULT = "Location tracker (cross-platform alert)"
    const val IBEACON = "iBeacon"
    const val EDDYSTONE = "Eddystone beacon"
    const val FAST_PAIR = "Google Fast Pair device"
    const val AIRPODS = "AirPods / Beats"
    const val APPLE_NEARBY = "iPhone / iPad / Mac"

    val TRACKERS = setOf(FINDMY_SEPARATED, FINDHUB_SEPARATED, FINDHUB, TILE, SMARTTAG, CHIPOLO, DULT)

    private val COMPANY_IDS = mapOf(
        0x004C to "Apple",
        0x0006 to "Microsoft",
        0x0075 to "Samsung",
        0x00E0 to "Google",
        0x0059 to "Nordic Semi",
        0x0087 to "Garmin",
        0x02E5 to "Espressif",
        0x012D to "Sony",
        0x009E to "Bose",
    )

    /** 16-bit service UUIDs used for the screen-off (filtered) scan. */
    val FILTER_SERVICE_UUIDS = listOf(0xFEED, 0xFEEC, 0xFD5A, 0xFCB2, 0xFE33)
    /** 16-bit service-data UUIDs used for the screen-off scan. */
    val FILTER_SERVICE_DATA = listOf(0xFEAA, 0xFD5A)
    /** Company IDs used for the screen-off scan (Apple, Samsung). */
    val FILTER_COMPANY_IDS = listOf(0x004C, 0x0075)

    data class Result(val type: String, val maker: String)

    fun classify(
        manufacturerData: Map<Int, ByteArray>,
        serviceUuids: Set<Int>,
        serviceData: Map<Int, ByteArray>,
    ): Result {
        var type = ""
        var maker = ""

        for ((id, data) in manufacturerData) {
            maker = COMPANY_IDS[id] ?: maker.ifEmpty { String.format(Locale.US, "0x%04X", id) }
            if (id == 0x004C && data.isNotEmpty()) {
                val t = data[0].toInt() and 0xFF
                val len = if (data.size > 1) data[1].toInt() and 0xFF else 0
                type = when (t) {
                    // Offline-finding frame: a full 0x19-byte payload carries the public key,
                    // which an AirTag only broadcasts once it is separated from its owner.
                    0x12 -> if (len == 0x19) FINDMY_SEPARATED else FINDMY_NEAR
                    0x07 -> AIRPODS
                    0x02 -> if (len == 0x15) IBEACON else type
                    0x10, 0x0C, 0x0F -> if (type.isEmpty()) APPLE_NEARBY else type
                    else -> type
                }
            }
        }

        val all = serviceUuids + serviceData.keys
        when {
            0xFEED in all || 0xFEEC in all -> {
                type = TILE; maker = "Tile"
            }
            0xFD5A in all -> {
                type = SMARTTAG; maker = "Samsung"
            }
            0xFE33 in all -> {
                type = CHIPOLO; maker = "Chipolo"
            }
            0xFCB2 in all -> {
                type = DULT
            }
            0xFEAA in all -> {
                val frame = serviceData[0xFEAA]?.firstOrNull()?.toInt()?.and(0xFF)
                type = when (frame) {
                    0x41 -> FINDHUB_SEPARATED // "unwanted tracking protection" mode
                    0x40 -> FINDHUB
                    else -> if (type.isEmpty()) EDDYSTONE else type
                }
                if (frame == 0x40 || frame == 0x41) maker = "Google"
            }
            0xFE2C in all -> {
                if (type.isEmpty()) type = FAST_PAIR
            }
        }
        return Result(type, maker)
    }
}
