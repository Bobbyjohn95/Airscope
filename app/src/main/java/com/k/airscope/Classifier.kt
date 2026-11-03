package com.k.airscope

import java.util.Locale

/** Result of classifying one device. Higher `prio` wins the category/label. */
class Classification {
    var category = Category.NETWORK
    var label = ""
    var vendor = ""
    var bleType = ""
    var ref: Int? = null
    var refSource = ""
    val facts = LinkedHashMap<String, String>()
    val findings = ArrayList<Finding>()
    var remoteId: RemoteId.Info? = null
    /** Attack/audit hardware evidence found (see Threats). */
    var attackTool = false
    var prio = 0
        private set

    fun set(cat: Category, label: String, prio: Int) {
        if (prio > this.prio) {
            category = cat; this.label = label; this.prio = prio
        }
    }
}

object Classifier {
    const val FINDMY_SEPARATED = "Apple Find My tag (away from owner)"
    const val FINDMY_NEAR = "Find My device (owner nearby)"
    const val FINDHUB_SEPARATED = "Google Find Hub tag (away from owner)"
    const val FINDHUB = "Google Find Hub tag"
    const val TILE = "Tile tracker"
    const val SMARTTAG = "Samsung SmartTag"
    const val CHIPOLO = "Chipolo tracker"
    const val DULT = "Location tracker (cross-platform alert)"

    val TRACKERS = setOf(FINDMY_SEPARATED, FINDHUB_SEPARATED, FINDHUB, TILE, SMARTTAG, CHIPOLO, DULT)

    /** Apple proximity-pairing model IDs (public reverse-engineering of Apple Continuity). */
    private val APPLE_AUDIO = mapOf(
        0x0220 to "AirPods", 0x0F20 to "AirPods (2nd gen)", 0x1320 to "AirPods (3rd gen)",
        0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro 2", 0x0A20 to "AirPods Max",
        0x0320 to "Powerbeats3", 0x0520 to "BeatsX", 0x0620 to "Beats Solo3", 0x0920 to "Beats Studio3",
        0x0B20 to "Powerbeats Pro", 0x0C20 to "Beats Solo Pro", 0x1020 to "Beats Flex",
        0x1120 to "Beats Studio Buds", 0x1220 to "Beats Fit Pro", 0x1620 to "Beats Studio Buds+",
        0x1720 to "Beats Studio Pro",
    )

    private val APPLE_TYPES = mapOf(
        0x02 to "iBeacon", 0x05 to "AirDrop", 0x06 to "HomeKit", 0x07 to "Proximity pairing", 0x08 to "Hey Siri",
        0x09 to "AirPlay target", 0x0A to "AirPlay source", 0x0B to "Watch (Magic Switch)", 0x0C to "Handoff",
        0x0D to "Tethering target", 0x0E to "Instant Hotspot", 0x0F to "Nearby Action", 0x10 to "Nearby Info",
        0x12 to "Find My",
    )

    /** BLE appearance category (value >> 6) -> category + label. */
    private val APPEARANCE = mapOf(
        1 to (Category.PHONE to "Phone"), 2 to (Category.PHONE to "Computer"), 3 to (Category.WEARABLE to "Watch"),
        4 to (Category.CAMERA_IOT to "Clock"), 5 to (Category.CAMERA_IOT to "Display"),
        6 to (Category.CAMERA_IOT to "Remote control"), 7 to (Category.WEARABLE to "Smart glasses"),
        8 to (Category.TRACKER to "Tag"), 9 to (Category.TRACKER to "Keyring tag"),
        10 to (Category.WEARABLE to "Media player"), 12 to (Category.CAMERA_IOT to "Thermometer"),
        13 to (Category.WEARABLE to "Heart-rate sensor"), 14 to (Category.WEARABLE to "Blood-pressure monitor"),
        15 to (Category.NETWORK to "Keyboard / mouse / controller"), 16 to (Category.WEARABLE to "Glucose meter"),
        17 to (Category.WEARABLE to "Running sensor"), 18 to (Category.WEARABLE to "Cycling sensor"),
        19 to (Category.CAMERA_IOT to "Control device"), 20 to (Category.NETWORK to "Network device"),
        21 to (Category.CAMERA_IOT to "Sensor"), 22 to (Category.CAMERA_IOT to "Light"), 23 to (Category.CAMERA_IOT to "Fan"),
        24 to (Category.CAMERA_IOT to "HVAC"), 28 to (Category.CAMERA_IOT to "Lock / access control"),
        30 to (Category.CAMERA_IOT to "Power device"), 31 to (Category.CAMERA_IOT to "Light"),
        33 to (Category.WEARABLE to "Speaker / headphones"), 34 to (Category.WEARABLE to "Audio source"),
        35 to (Category.VEHICLE to "Vehicle"), 36 to (Category.CAMERA_IOT to "Appliance"),
        37 to (Category.WEARABLE to "Earbuds / headphones"), 38 to (Category.DRONE to "Aircraft"),
        39 to (Category.CAMERA_IOT to "AV equipment"), 40 to (Category.CAMERA_IOT to "Display"),
        41 to (Category.WEARABLE to "Hearing aid"), 42 to (Category.NETWORK to "Gaming device"),
        49 to (Category.WEARABLE to "Pulse oximeter"), 50 to (Category.CAMERA_IOT to "Weight scale"),
        81 to (Category.WEARABLE to "Outdoor sports device"),
    )

    private fun h4(v: Int) = String.format(Locale.US, "0x%04X", v)

    /** Apple Nearby Info activity codes (Celosia & Cunche, "Discontinued Privacy", PETS 2020). */
    private val APPLE_ACTIVITY = mapOf(
        0x03 to "idle", 0x05 to "playing audio, screen locked", 0x07 to "screen on / in use", 0x09 to "screen on, playing video",
        0x0A to "watch on wrist, unlocked", 0x0B to "recently used", 0x0D to "driving", 0x0E to "on a phone or FaceTime call",
    )

    private val IBEACON_UUIDS = mapOf(
        "B9407F30-F5F8-466E-AFF9-25556B57FE6D" to "Estimote",
        "F7826DA6-4FA2-4E98-8024-BC5B71E0893E" to "Kontakt.io",
        "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0" to "Apple AirLocate sample",
    )

    private fun hexUpper(d: ByteArray, from: Int, len: Int) =
        (from until from + len).joinToString("") { String.format(Locale.US, "%02X", d[it].toInt() and 0xFF) }

    private fun printable(d: ByteArray, from: Int): String? {
        if (from >= d.size) return null
        val s = String(d, from, d.size - from, Charsets.UTF_8).replace("\u0000", "").trim()
        return if (s.length >= 3 && s.all { it.code in 32..126 || it.code > 160 }) s else null
    }

    private fun flagsText(f: Int): String = listOfNotNull(
        if (f and 0x01 != 0) "limited discoverable" else null,
        if (f and 0x02 != 0) "discoverable" else null,
        if (f and 0x04 != 0) "BLE-only" else "BLE + Classic",
    ).joinToString(", ")

    private val EDDY_SCHEMES = arrayOf("http://www.", "https://www.", "http://", "https://")
    private val EDDY_EXP = arrayOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")

    private fun eddystoneUrl(d: ByteArray): String? {
        if (d.size < 4) return null
        val sb = StringBuilder(EDDY_SCHEMES.getOrNull(d[2].toInt() and 0xFF) ?: return null)
        for (i in 3 until d.size) {
            val c = d[i].toInt() and 0xFF
            if (c < EDDY_EXP.size) sb.append(EDDY_EXP[c]) else if (c in 0x21..0x7E) sb.append(c.toChar()) else return null
        }
        return sb.toString()
    }

    private fun eddystoneTlm(d: ByteArray): String? {
        if (d.size < 14 || d[1].toInt() != 0) return null
        val mv = ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        val temp = d[4].toInt() + (d[5].toInt() and 0xFF) / 256.0
        val up = (((d[10].toLong() and 0xFF) shl 24) or ((d[11].toLong() and 0xFF) shl 16) or ((d[12].toLong() and 0xFF) shl 8) or (d[13].toLong() and 0xFF)) / 10
        val parts = ArrayList<String>()
        if (mv > 0) parts += String.format(Locale.US, "battery %.2f V", mv / 1000.0)
        if (d[4].toInt() != -128) parts += String.format(Locale.US, "%.1f °C", temp)
        if (up > 0) parts += "up ${up / 86400} d ${(up % 86400) / 3600} h"
        return parts.joinToString(" · ").ifEmpty { null }
    }

    private fun withVendor(vendor: String, what: String) =
        if (vendor.isBlank() || what.contains(vendor, ignoreCase = true)) what else "$vendor $what"

    // ------------------------------------------------------------------ BLE

    fun classifyBle(addr: String, randomAddr: Boolean?, adv: Adv, db: VendorDb): Classification {
        val c = Classification()
        val companyNames = adv.mfr.keys.mapNotNull { db.company(it)?.let(VendorDb::shortVendor) }
        val ouiVendor = if (randomAddr != true) db.oui(addr)?.let(VendorDb::shortVendor) else null
        c.vendor = companyNames.firstOrNull() ?: ouiVendor ?: ""

        c.facts["Address"] = addr + when (randomAddr) {
            true -> " (random / private)"
            false -> " (public)"
            null -> if (VendorDb.isRandomizedMac(addr)) " (looks random)" else ""
        }
        if (ouiVendor != null) c.facts["MAC vendor"] = ouiVendor
        if (adv.mfr.isNotEmpty()) c.facts["Manufacturer data"] = adv.mfr.entries.joinToString("; ") { (id, d) ->
            "${db.company(id)?.let(VendorDb::shortVendor) ?: "?"} ${h4(id)} ${AdvParser.hex(d, 12)}"
        }
        val svc16 = adv.uuid16 + adv.svcData16.keys
        if (svc16.isNotEmpty()) c.facts["Services"] = svc16.joinToString(", ") { db.uuid16(it)?.let { n -> "$n (${h4(it)})" } ?: h4(it) }
        if (adv.uuid128.isNotEmpty()) c.facts["Custom services"] = adv.uuid128.joinToString(", ")
        adv.appearance?.let { c.facts["Appearance"] = h4(it) + (APPEARANCE[it shr 6]?.let { a -> " (${a.second})" } ?: "") }
        adv.flags?.let { c.facts["BLE flags"] = flagsText(it) }
        adv.txPower?.let {
            c.facts["TX power"] = "$it dBm"
            if (it in -40..20) { c.ref = it - 41; c.refSource = "advertised TX power" }
        }

        // --- Police / surveillance equipment
        val police = ArrayList<Finding>()
        police += Signatures.macFindings(addr)
        police += Signatures.bleNameFindings(adv.name)
        police += Signatures.ravenFindings(svc16, adv.uuid128 + adv.svcData128.keys)
        if (Signatures.FLOCK_BLE_COMPANY in adv.mfr) police += Finding(55, "Flock Safety BLE (company ID 0x09C8)")
        val psVendor = (companyNames + listOfNotNull(ouiVendor)).firstOrNull { Signatures.publicSafetyVendor.containsMatchIn(it) }
        if (psVendor != null && police.none { it.points >= 50 }) police += Finding(30, "Public-safety equipment maker: $psVendor")
        c.findings += police
        if (police.sumOf { it.points } >= 25) c.set(Category.POLICE, policeLabel(police), 100)

        // --- Attack / audit hardware (Flipper, Marauder, O.MG, Hak5): a hint from the name, never proof
        val attack = Threats.bleNameFindings(adv.name) + (if (randomAddr != true) Threats.macFindings(addr) else emptyList())
        if (attack.isNotEmpty()) {
            c.findings += attack
            c.attackTool = true
            Threats.label(attack)?.let { c.set(Category.NETWORK, it, 95) }
        }

        // --- Drone Remote ID
        adv.svcData16[0xFFFA]?.let { RemoteId.parseBle(it) }?.let { rid ->
            c.remoteId = rid
            c.findings += Finding(35, "Drone broadcasting FAA Remote ID")
            rid.serial?.let { c.facts["Drone serial"] = it; if (it.startsWith("1581F")) c.vendor = "DJI" }
            if (rid.lat != null) c.facts["Drone position"] = "${fmt5(rid.lat!!)}, ${fmt5(rid.lon!!)}" + (rid.altM?.let { " · ${it.toInt()} m alt" } ?: "")
            c.set(Category.DRONE, withVendor(c.vendor, "drone (Remote ID)"), 90)
        }

        // --- Trackers + Apple Continuity
        adv.mfr[0x004C]?.let { classifyApple(it, c) }
        val trackerSvc = svc16
        when {
            0xFEED in trackerSvc || 0xFEEC in trackerSvc -> tracker(c, TILE, "Tile", 35)
            0xFD5A in trackerSvc -> tracker(c, SMARTTAG, "Samsung", 35)
            0xFE33 in trackerSvc -> tracker(c, CHIPOLO, "Chipolo", 35)
            0xFCB2 in trackerSvc -> tracker(c, DULT, "", 35)
        }
        adv.svcData16[0xFEAA]?.let { d ->
            val frame = d.firstOrNull()?.toInt()?.and(0xFF)
            when (frame) {
                0x41 -> tracker(c, FINDHUB_SEPARATED, "Google", 40)
                0x40 -> tracker(c, FINDHUB, "Google", 35)
                0x00, 0x10 -> {
                    if (d.size > 1) { c.ref = d[1].toInt() - 41; c.refSource = "Eddystone TX@0m" }
                    if (frame == 0x10) eddystoneUrl(d)?.let { c.facts["Beacon URL"] = it }
                    if (frame == 0x00 && d.size >= 18) c.facts["Eddystone UID"] = hexUpper(d, 2, 10) + " / " + hexUpper(d, 12, 6)
                    c.findings += Finding(10, "Beacon (Eddystone)")
                    c.set(Category.NETWORK, "Eddystone beacon", 60)
                }
                0x20 -> {
                    eddystoneTlm(d)?.let { c.facts["Beacon telemetry"] = it }
                    c.set(Category.NETWORK, "Eddystone beacon", 58)
                }
                else -> c.set(Category.NETWORK, "Eddystone beacon", 55)
            }
        }

        // --- Other well-known protocols
        adv.mfr[0x0006]?.let { d ->
            when (d.firstOrNull()?.toInt()) {
                0x01 -> c.set(Category.PHONE, "Windows PC (Connected Devices)", 68)
                0x03 -> {
                    printable(d, 6)?.let { c.facts["Swift Pair name"] = it } ?: printable(d, 3)?.let { c.facts["Swift Pair name"] = it }
                    c.set(Category.NETWORK, "Windows Swift Pair accessory", 68)
                }
                else -> c.set(Category.PHONE, "Microsoft device", 45)
            }
        }
        adv.svcData16[0xFE2C]?.let { if (it.size == 3) c.facts["Fast Pair model ID"] = "0x" + hexUpper(it, 0, 3) + " (look up in Google's Fast Pair device list)" }
        if (0xFE2C in trackerSvc) c.set(Category.WEARABLE, withVendor(c.vendor, "Fast Pair accessory (earbuds/headphones)"), 66)
        if (0x180D in trackerSvc) c.set(Category.WEARABLE, withVendor(c.vendor, "heart-rate sensor"), 62)
        if (0x1816 in trackerSvc || 0x1818 in trackerSvc) c.set(Category.WEARABLE, withVendor(c.vendor, "bike sensor"), 62)
        if (0x1814 in trackerSvc) c.set(Category.WEARABLE, withVendor(c.vendor, "running sensor"), 62)
        if (0x1812 in trackerSvc) c.set(Category.NETWORK, withVendor(c.vendor, "keyboard / mouse / controller"), 58)
        if (0x181A in trackerSvc) c.set(Category.CAMERA_IOT, withVendor(c.vendor, "environmental sensor"), 58)

        adv.appearance?.let { ap -> APPEARANCE[ap shr 6]?.let { (cat, what) -> c.set(cat, withVendor(c.vendor, what), 60) } }

        // --- Names
        val name = adv.name.orEmpty()
        if (name.isNotEmpty()) {
            when {
                Patterns.teslaBle.matches(name) -> c.set(Category.VEHICLE, "Tesla vehicle (phone key)", 66)
                Patterns.droneName.containsMatchIn(name) -> c.set(Category.DRONE, withVendor(c.vendor, "drone / controller"), 55)
                Patterns.vehicleName.containsMatchIn(name) -> c.set(Category.VEHICLE, "Vehicle / car accessory", 50)
                Patterns.wearableName.containsMatchIn(name) -> c.set(Category.WEARABLE, withVendor(c.vendor, wearableKind(name)), 50)
                Patterns.phoneName.containsMatchIn(name) -> c.set(Category.PHONE, withVendor(c.vendor, "phone / tablet"), 50)
                Patterns.computerName.containsMatchIn(name) -> c.set(Category.PHONE, withVendor(c.vendor, "computer"), 50)
                Patterns.cameraIotName.containsMatchIn(name) -> c.set(Category.CAMERA_IOT, withVendor(c.vendor, "smart-home / IoT device"), 48)
            }
        }

        // --- Vendors
        val vendors = companyNames + listOfNotNull(ouiVendor)
        for (v in vendors) {
            when {
                Patterns.droneVendor.containsMatchIn(v) -> c.set(Category.DRONE, "$v drone / controller", 42)
                Patterns.vehicleVendor.containsMatchIn(v) -> c.set(Category.VEHICLE, "$v vehicle system", 41)
                Patterns.wearableVendor.containsMatchIn(v) -> c.set(Category.WEARABLE, "$v wearable / audio", 40)
                Patterns.cameraIotVendor.containsMatchIn(v) -> c.set(Category.CAMERA_IOT, "$v IoT device", 40)
                Patterns.phoneVendor.containsMatchIn(v) -> c.set(Category.PHONE, "$v device (likely phone)", 30)
                Patterns.computerVendor.containsMatchIn(v) -> c.set(Category.PHONE, "$v computer", 30)
            }
        }
        if (c.prio == 0) c.set(Category.NETWORK, if (c.vendor.isNotBlank()) "${c.vendor} BLE device" else "Unknown BLE device", 1)
        return c
    }

    private fun wearableKind(name: String): String {
        val l = name.lowercase(Locale.US)
        return when {
            "watch" in l || "forerunner" in l || "fenix" in l || "venu" in l || "versa" in l -> "watch"
            "buds" in l || "airpods" in l || "earbuds" in l -> "earbuds"
            "band" in l -> "fitness band"
            "ring" in l || "oura" in l -> "smart ring"
            else -> "wearable / audio"
        }
    }

    private fun tracker(c: Classification, type: String, vendor: String, points: Int) {
        c.bleType = type
        if (vendor.isNotEmpty()) c.vendor = vendor
        c.findings += Finding(points, type)
        c.set(Category.TRACKER, type, 80)
    }

    private fun classifyApple(d: ByteArray, c: Classification) {
        c.vendor = "Apple"
        val seen = ArrayList<String>()
        var i = 0
        while (i + 1 < d.size) {
            val type = d[i].toInt() and 0xFF
            val len = d[i + 1].toInt() and 0xFF
            val p = i + 2
            APPLE_TYPES[type]?.let { seen += it }
            when (type) {
                0x12 -> if (len == 0x19) tracker(c, FINDMY_SEPARATED, "Apple", 40) else {
                    if (c.bleType.isEmpty()) c.bleType = FINDMY_NEAR
                    c.findings += Finding(10, FINDMY_NEAR)
                    c.set(Category.TRACKER, FINDMY_NEAR, 65)
                }
                0x07 -> if (p + 2 < d.size) {
                    val model = ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p + 2].toInt() and 0xFF)
                    val n = APPLE_AUDIO[model]
                    c.set(Category.WEARABLE, if (n != null) "Apple $n" else "AirPods / Beats (model ${h4(model)})", 72)
                    if (n != null) c.facts["Model"] = n
                    if (p + 5 < d.size) {
                        fun pct(v: Int) = if (v in 0..10) "${v * 10}%" else "?"
                        val buds = d[p + 4].toInt() and 0xFF
                        val cs = d[p + 5].toInt() and 0xFF
                        val charging = listOfNotNull(if (cs and 0x10 != 0) "bud" else null, if (cs and 0x20 != 0) "bud" else null, if (cs and 0x40 != 0) "case" else null)
                        c.facts["Battery"] = "buds ${pct(buds shr 4)} · ${pct(buds and 0x0F)} · case ${pct(cs and 0x0F)}" +
                            if (charging.isNotEmpty()) " · charging: ${charging.distinct().joinToString()}" else ""
                    }
                }
                0x10 -> {
                    c.set(Category.PHONE, "Apple iPhone / iPad / Mac", 70)
                    if (p < d.size) APPLE_ACTIVITY[d[p].toInt() and 0x0F]?.let { c.facts["Apple activity"] = "$it (Nearby Info code)" }
                }
                0x0C -> c.set(Category.PHONE, "Apple device (Handoff)", 70)
                0x0E -> c.set(Category.PHONE, "iPhone (Instant Hotspot)", 71)
                0x0D -> c.set(Category.PHONE, "Apple device (hotspot client)", 70)
                0x0B -> c.set(Category.WEARABLE, "Apple Watch", 71)
                0x09 -> c.set(Category.CAMERA_IOT, "Apple TV / HomePod (AirPlay)", 70)
                0x0A -> c.set(Category.PHONE, "Apple device (AirPlay source)", 69)
                0x06 -> c.set(Category.CAMERA_IOT, "HomeKit accessory", 70)
                0x05 -> c.set(Category.PHONE, "Apple device (AirDrop)", 69)
                0x0F -> c.set(Category.PHONE, "Apple device (Nearby Action)", 69)
                0x02 -> if (len == 0x15 && p + 20 < d.size) {
                    c.ref = d[p + 20].toInt() // measured power at 1 m (signed)
                    c.refSource = "iBeacon measured power"
                    val u = hexUpper(d, p, 16).let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
                    c.facts["iBeacon UUID"] = u + (IBEACON_UUIDS[u]?.let { " ($it)" } ?: "")
                    c.facts["iBeacon"] = "major ${((d[p + 16].toInt() and 0xFF) shl 8) or (d[p + 17].toInt() and 0xFF)}, minor ${((d[p + 18].toInt() and 0xFF) shl 8) or (d[p + 19].toInt() and 0xFF)}"
                    c.findings += Finding(10, "Beacon (iBeacon)")
                    c.set(Category.NETWORK, "iBeacon", 60)
                }
            }
            if (len == 0) break
            i = p + len
        }
        if (seen.isNotEmpty()) c.facts["Apple messages"] = seen.joinToString(", ")
        c.set(Category.PHONE, "Apple device", 35)
    }

    private fun policeLabel(f: List<Finding>): String {
        val top = f.maxByOrNull { it.points }?.text.orEmpty().lowercase(Locale.US)
        return when {
            "axon" in top -> "Axon police equipment"
            "raven" in top -> "Raven gunshot detector"
            "soundthinking" in top || "shotspotter" in top -> "SoundThinking / ShotSpotter sensor"
            "flock" in top || "penguin" in top -> "Flock Safety device"
            "law-enforcement" in top && "vehicle router" in top -> "Police vehicle"
            "law-enforcement" in top -> "Law-enforcement network"
            "fire department" in top -> if ("vehicle router" in top) "Fire truck" else "Fire department network"
            "ambulance" in top -> if ("vehicle router" in top) "Ambulance / EMS vehicle" else "EMS network"
            "traffic" in top -> "Traffic / enforcement camera"
            else -> "Public-safety equipment"
        }
    }

    // ------------------------------------------------------------------ Wi-Fi

    fun classifyWifi(
        bssid: String, ssid: String, freq: Int, caps: String, ie: WifiIeInfo?,
        standard: Int, widthMhz: Int, rtt: Boolean, db: VendorDb,
    ): Classification {
        val c = Classification()
        val randomized = VendorDb.isRandomizedMac(bssid)
        val ouiVendor = if (!randomized) db.oui(bssid)?.let(VendorDb::shortVendor) else null
        c.vendor = ouiVendor ?: ie?.wpsManufacturer?.let(VendorDb::shortVendor) ?: ""

        c.facts["BSSID"] = bssid + if (randomized) " (randomized)" else ""
        if (ouiVendor != null) c.facts["MAC vendor"] = ouiVendor
        val band = when { freq >= 5925 -> "6 GHz"; freq >= 5000 -> "5 GHz"; else -> "2.4 GHz" }
        c.facts["Band"] = "$band · $freq MHz" + if (widthMhz > 0) " · $widthMhz MHz wide" else ""
        standardName(standard)?.let { c.facts["Standard"] = it }
        c.facts["Security"] = Heuristics.securityLabel(caps) + "  $caps"
        if (rtt) c.facts["Wi-Fi RTT"] = "supported (measured distance)"
        ie?.let { info ->
            info.wpsManufacturer?.let { c.facts["WPS maker"] = it }
            (info.wpsModel ?: info.wpsModelNumber)?.let { c.facts["WPS model"] = it }
            info.wpsDeviceName?.let { c.facts["WPS device name"] = it }
            info.wpsCategory?.let { c.facts["WPS type"] = WifiIe.WPS_CATEGORIES[it] ?: "type $it" }
            info.apName?.let { c.facts["AP name"] = it }
            info.stations?.let { c.facts["Connected clients"] = it.toString() + (info.channelUtil?.let { u -> " · channel busy $u%" } ?: "") }
            info.country?.let { c.facts["Country code"] = it }
            val roam = listOfNotNull(if (info.rrm) "802.11k" else null, if (info.bssTransition) "802.11v" else null, if (info.fastRoaming) "802.11r" else null)
            if (roam.isNotEmpty()) c.facts["Roaming support"] = roam.joinToString(" / ") + " (managed / mesh network)"
            if (info.passpoint) c.facts["Passpoint"] = "Hotspot 2.0 (carrier / enterprise Wi-Fi)"
            val chips = info.vendorOuis.filter { it != "00:50:f2" && it != "50:6f:9a" }.mapNotNull { db.oui(it)?.let(VendorDb::shortVendor) }.distinct()
            if (chips.isNotEmpty()) c.facts["Vendor elements"] = chips.joinToString(", ")
        }

        // --- Police / fire / EMS / cameras
        // Vehicle routers (Cradlepoint, Sierra Wireless...) ride in police cars but also in buses, delivery vans and
        // utility trucks, so a router alone is NOT police: the network name has to say who it belongs to.
        val police = ArrayList<Finding>()
        police += Signatures.macFindings(bssid)
        police += Signatures.ssidFindings(ssid)
        val fleetRouter = Signatures.fleetRouterSsid.matches(ssid) || (ouiVendor != null && Signatures.fleetRouterVendor.containsMatchIn(ouiVendor))
        if (ouiVendor != null && police.none { it.points >= 50 } && Signatures.publicSafetyVendor.containsMatchIn(ouiVendor)) {
            police += Finding(30, "Public-safety equipment maker: $ouiVendor")
        }
        val agency = Signatures.agency(ssid)
        if (agency != null && (agency != Signatures.Agency.COMMERCIAL || fleetRouter)) c.facts["Network name says"] = agency.label
        when (agency) {
            Signatures.Agency.POLICE -> police += Finding(if (fleetRouter) 55 else 40,
                "Law-enforcement network name" + if (fleetRouter) " on a vehicle router (patrol car / unmarked unit)" else "")
            Signatures.Agency.FIRE -> police += Finding(30, "Fire department network name" + if (fleetRouter) " on a vehicle router (fire truck)" else "")
            Signatures.Agency.EMS -> police += Finding(30, "Ambulance / EMS network name" + if (fleetRouter) " on a vehicle router" else "")
            Signatures.Agency.TRAFFIC -> police += Finding(35, "Traffic / enforcement camera network name")
            else -> Unit
        }
        if (fleetRouter) {
            c.facts["Router maker"] = (ouiVendor ?: "vehicle router") + " (used by police, EMS, transit and delivery fleets)"
            when {
                agency == Signatures.Agency.COMMERCIAL -> c.set(Category.VEHICLE, "Commercial / transit fleet router", 60)
                agency == null && police.none { it.points >= 50 } -> c.set(Category.VEHICLE, "Fleet vehicle router (unknown fleet)", 60)
            }
        }
        c.findings += police
        if (police.sumOf { it.points } >= 25) c.set(Category.POLICE, policeLabel(police), 100)

        // --- Attack / audit hardware (Pineapple, deauther, Marauder, O.MG): a hint from the name, never proof
        val attack = Threats.ssidFindings(ssid) + Threats.macFindings(bssid)
        if (attack.isNotEmpty()) {
            c.findings += attack
            c.attackTool = attack.any { it.points >= 40 }
            if (c.attackTool) Threats.label(attack)?.let { c.set(Category.NETWORK, it, 95) }
        }
        // --- Security grade (A..F) in plain words
        val wps = ie?.wps == true
        WifiSecurity.grade(caps, wps).let { (g, why) -> c.facts["Security grade"] = "$g · $why" }

        // --- Drones
        if (ie?.remoteId == true) {
            c.findings += Finding(40, "Drone broadcasting Remote ID (Wi-Fi)")
            c.set(Category.DRONE, "Drone (Remote ID)", 90)
        }
        if (ie?.djiDroneId == true) {
            c.findings += Finding(35, "DJI DroneID beacon")
            c.set(Category.DRONE, "DJI drone", 88)
        }

        // --- WPS device type (printers, TVs, cameras, consoles...)
        val wpsLabel = ie?.wpsLabel()
        ie?.wpsCategory?.let { cat ->
            val what = WifiIe.WPS_CATEGORIES[cat] ?: "device"
            val label = wpsLabel?.let { "$it ($what)" } ?: withVendor(c.vendor, what)
            when (cat) {
                1, 10 -> c.set(Category.PHONE, label, 62)
                3, 4, 7, 8, 11 -> c.set(Category.CAMERA_IOT, label, 62)
                9 -> c.set(Category.NETWORK, label, 62)
            }
        }

        // --- SSID patterns
        if (ssid.isNotBlank()) {
            when {
                Patterns.droneName.containsMatchIn(ssid) -> c.set(Category.DRONE, "Drone / controller network", 55)
                ssid.startsWith("DIRECT-", ignoreCase = true) && Regex("android", RegexOption.IGNORE_CASE).containsMatchIn(ssid) ->
                    c.set(Category.PHONE, "Android phone (Wi-Fi Direct)", 56)
                Patterns.hotspotName.containsMatchIn(ssid) -> c.set(Category.PHONE, hotspotLabel(ssid), 55)
                Patterns.vehicleName.containsMatchIn(ssid) -> c.set(Category.VEHICLE, vehicleLabel(ssid), 56)
                ssid.startsWith("DIRECT-", ignoreCase = true) -> c.set(Category.CAMERA_IOT, wpsLabel?.let { "$it (Wi-Fi Direct)" } ?: "Wi-Fi Direct device (printer / TV / camera)", 52)
                Patterns.cameraIotName.containsMatchIn(ssid) -> c.set(Category.CAMERA_IOT, withVendor(c.vendor, "camera / IoT setup network"), 50)
            }
            if (Patterns.cameraName.containsMatchIn(ssid)) c.findings += Finding(30, "Camera/IoT-style network name")
        }

        // --- Vendor
        ouiVendor?.let { v ->
            when {
                Patterns.droneVendor.containsMatchIn(v) -> c.set(Category.DRONE, "$v drone network", 45)
                Patterns.vehicleVendor.containsMatchIn(v) -> c.set(Category.VEHICLE, "$v vehicle Wi-Fi", 45)
                Patterns.cameraIotVendor.containsMatchIn(v) -> c.set(Category.CAMERA_IOT, "$v device", 40)
            }
        }
        if (c.prio == 0) {
            val l = wpsLabel?.let { "$it router / AP" } ?: if (c.vendor.isNotBlank()) "${c.vendor} access point" else "Wi-Fi network"
            c.set(Category.NETWORK, l, 1)
        }
        return c
    }

    private fun hotspotLabel(ssid: String): String {
        val l = ssid.lowercase(Locale.US)
        return when {
            "iphone" in l -> "iPhone hotspot"
            "galaxy" in l -> "Samsung Galaxy hotspot"
            "pixel" in l -> "Google Pixel hotspot"
            else -> "Phone hotspot"
        }
    }

    private fun vehicleLabel(ssid: String): String {
        val l = ssid.lowercase(Locale.US)
        return when {
            Regex("dash ?cam|70mai|viofo|blackvue|nextbase").containsMatchIn(l) -> "Dash cam"
            Regex("obd|vgate|elm327").containsMatchIn(l) -> "OBD-II car adapter"
            else -> "Car Wi-Fi / vehicle hotspot"
        }
    }

    private fun standardName(s: Int): String? = when (s) {
        1 -> "802.11a/b/g (legacy)"; 4 -> "Wi-Fi 4 (802.11n)"; 5 -> "Wi-Fi 5 (802.11ac)"
        6 -> "Wi-Fi 6/6E (802.11ax)"; 7 -> "802.11ad (60 GHz)"; 8 -> "Wi-Fi 7 (802.11be)"
        else -> null
    }

    private fun fmt5(v: Double) = String.format(Locale.US, "%.5f", v)
}
