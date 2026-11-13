package airscope.tests.logic

import com.k.airscope.*

var failures = 0
fun check(name: String, cond: Boolean, info: Any? = null) {
    println((if (cond) "PASS " else "FAIL ") + name + (if (!cond && info != null) "  -> $info" else ""))
    if (!cond) failures++
}

fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** Build raw advertising data from (type, payload) structures. */
fun ad(vararg parts: Pair<Int, ByteArray>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for ((t, p) in parts) { out.write(p.size + 1); out.write(t); out.write(p) }
    out.write(ByteArray(4)) // zero padding like real scan records
    return out.toByteArray()
}

fun mfr(company: Int, vararg data: Int) = 0xFF to (bytes(company and 0xFF, company shr 8) + bytes(*data))
fun svcData(uuid: Int, vararg data: Int) = 0x16 to (bytes(uuid and 0xFF, uuid shr 8) + bytes(*data))
fun uuids(vararg u: Int) = 0x03 to u.flatMap { listOf(it and 0xFF, it shr 8) }.let { l -> ByteArray(l.size) { l[it].toByte() } }
fun name(s: String) = 0x09 to s.toByteArray()

fun main() {
    val t0 = 1_700_000_000_000L
    val db = VendorDb()
    db.loadOui(sequenceOf("00a0d5\tSierra Wireless", "003044\tCradlepoint, Inc", "70b3d5\tIEEE Registration Authority", "28cdc1\tRaspberry Pi Trading Ltd", "ac233f\tShenzhen Minew Technologies Co., Ltd."))

    // ---- AdvParser
    val raw = ad(0x01 to bytes(0x06), name("Galaxy Buds2"), 0x0A to bytes(0xF4), uuids(0x180F, 0xFE2C), mfr(0x0075, 1, 2, 3))
    val a = AdvParser.parse(raw)
    check("adv name", a.name == "Galaxy Buds2", a.name)
    check("adv tx power -12", a.txPower == -12, a.txPower)
    check("adv uuids", a.uuid16 == setOf(0x180F, 0xFE2C), a.uuid16)
    check("adv mfr samsung", a.mfr.keys == setOf(0x0075))
    check("adv truncated safe", AdvParser.parse(bytes(0x05, 0x09, 0x41)).name == null)
    val u128 = AdvParser.uuid128(bytes(0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80, 0x00, 0x10, 0x00, 0x00, 0x00, 0x31, 0x00, 0x00), 0)
    check("uuid128 base -> 0x3100", AdvParser.short16(u128) == 0x3100, u128)

    // ---- Classifier: BLE
    val buds = Classifier.classifyBle("12:34:56:78:9a:bc", true, a, db)
    check("Galaxy Buds -> wearable", buds.category == Category.WEARABLE, "${buds.category} ${buds.label}")
    check("Galaxy Buds ref from tx power", buds.ref == -53, buds.ref)

    val airpods = Classifier.classifyBle("4a:11:22:33:44:55", true, AdvParser.parse(ad(mfr(0x004C, 0x07, 0x19, 0x01, 0x14, 0x20, 0x55))), db)
    check("AirPods Pro 2 label", airpods.label == "Apple AirPods Pro 2" && airpods.category == Category.WEARABLE, airpods.label)

    val tagData = IntArray(27).also { it[0] = 0x12; it[1] = 0x19 }
    val airtag = Classifier.classifyBle("f1:22:33:44:55:66", true, AdvParser.parse(ad(mfr(0x004C, *tagData))), db)
    check("AirTag separated -> tracker", airtag.category == Category.TRACKER && airtag.bleType == Classifier.FINDMY_SEPARATED, airtag.label)

    val iphone = Classifier.classifyBle("5b:22:33:44:55:66", true, AdvParser.parse(ad(mfr(0x004C, 0x10, 0x05, 0x01, 0x18, 0x00, 0x00, 0x00))), db)
    check("iPhone Nearby Info -> phone", iphone.category == Category.PHONE, iphone.label)

    val axon = Classifier.classifyBle("00:25:df:12:34:56", false, AdvParser.parse(ad(name("AXON_CAMERA"))), db)
    check("Axon OUI -> POLICE", axon.category == Category.POLICE && axon.label == "Axon police equipment", "${axon.category} ${axon.label}")

    val penguin = Classifier.classifyBle("c8:11:22:33:44:55", false, AdvParser.parse(ad(name("Penguin-1234567890"))), db)
    check("Penguin battery -> POLICE/Flock", penguin.category == Category.POLICE && penguin.label == "Flock Safety device", penguin.label)
    val pingu = Classifier.classifyBle("c8:11:22:33:44:56", false, AdvParser.parse(ad(name("Penguins Fan"))), db)
    check("BLE 'Penguins Fan' is not Flock", pingu.category != Category.POLICE, pingu.label)
    val flockhart = Classifier.classifyBle("c8:11:22:33:44:57", false, AdvParser.parse(ad(name("Flockhart"))), db)
    check("BLE 'Flockhart' is not Flock", flockhart.category != Category.POLICE, flockhart.label)

    val flockCo = Classifier.classifyBle("d2:11:22:33:44:55", true, AdvParser.parse(ad(mfr(0x09C8, 1, 2))), db)
    check("Flock company 0x09C8 -> POLICE", flockCo.category == Category.POLICE)

    val raven = Classifier.classifyBle("d4:11:d6:00:00:01", false, AdvParser.parse(ad(uuids(0x3100, 0x3200))), db)
    check("Raven UUIDs + SoundThinking OUI -> POLICE", raven.category == Category.POLICE && raven.findings.sumOf { it.points } >= 100, raven.findings)

    val weakMfr = Classifier.classifyBle("f4:6a:dd:00:00:01", false, AdvParser.parse(ad(name("Speaker"))), db)
    check("Liteon OUI alone is NOT police", weakMfr.category != Category.POLICE, weakMfr.label)

    val ridMsg = IntArray(25).also { it[0] = 0x00; it[1] = 0x12; "1581F5BKD225400ABCDE".forEachIndexed { i, ch -> it[2 + i] = ch.code } }
    val drone = Classifier.classifyBle("e2:11:22:33:44:55", true, AdvParser.parse(ad(svcData(0xFFFA, 0x0D, 0x01, *ridMsg))), db)
    check("Remote ID -> DRONE with DJI serial", drone.category == Category.DRONE && drone.facts["Drone serial"] == "1581F5BKD225400ABCDE" && drone.vendor == "DJI", "${drone.category} ${drone.facts}")

    val loc = IntArray(25)
    loc[0] = 0x10
    fun putLe32(arr: IntArray, o: Int, v: Int) { for (k in 0..3) arr[o + k] = (v shr (8 * k)) and 0xFF }
    putLe32(loc, 5, 339_234_567); putLe32(loc, 9, -847_012_345)
    val rid = RemoteId.parseBle(bytes(0x0D, 0x02, *loc))
    check("Remote ID location decode", rid?.lat?.let { kotlin.math.abs(it - 33.9234567) < 1e-6 } == true && rid.lon?.let { kotlin.math.abs(it + 84.7012345) < 1e-6 } == true, "${rid?.lat} ${rid?.lon}")

    val tesla = Classifier.classifyBle("c1:22:33:44:55:66", true, AdvParser.parse(ad(name("S1a87a5a75f3df858C"))), db)
    check("Tesla BLE key name -> VEHICLE", tesla.category == Category.VEHICLE, tesla.label)

    val hr = Classifier.classifyBle("c2:22:33:44:55:66", true, AdvParser.parse(ad(uuids(0x180D))), db)
    check("Heart-rate service -> wearable", hr.category == Category.WEARABLE, hr.label)

    val tile = Classifier.classifyBle("c3:22:33:44:55:66", true, AdvParser.parse(ad(svcData(0xFEED, 1, 2))), db)
    check("Tile -> tracker", tile.category == Category.TRACKER && tile.bleType == Classifier.TILE)

    val ib = IntArray(23).also { it[0] = 0x02; it[1] = 0x15; it[22] = 0xC5 } // measured power -59
    val ibeacon = Classifier.classifyBle("c4:22:33:44:55:66", true, AdvParser.parse(ad(mfr(0x004C, *ib))), db)
    check("iBeacon measured power -> ref -59", ibeacon.ref == -59 && ibeacon.label == "iBeacon", "${ibeacon.ref} ${ibeacon.label}")

    val appearance = Classifier.classifyBle("c5:22:33:44:55:66", true, AdvParser.parse(ad(0x19 to bytes(0xC1, 0x00))), db)
    check("Appearance 0x00C1 -> watch", appearance.category == Category.WEARABLE && appearance.label.contains("Watch"), appearance.label)

    // ---- Classifier: Wi-Fi
    fun wifi(bssid: String, ssid: String, caps: String = "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]", ie: WifiIeInfo? = null) =
        Classifier.classifyWifi(bssid, ssid, 2437, caps, ie, 4, 20, false, db)

    check("Flock-XXXXXX SSID -> POLICE", wifi("02:aa:bb:cc:dd:ee", "Flock-1A2B3C").category == Category.POLICE)
    check("Flock Camera net. -> POLICE", wifi("06:aa:bb:cc:dd:ee", "Flock Camera net.").category == Category.POLICE)
    check("Flock OUI BSSID -> POLICE", wifi("b4:1e:52:01:02:03", "x").category == Category.POLICE)
    check("Flock exact default MAC 70 pts", wifi("00:03:7f:50:00:01", "").findings.any { it.points == 70 })
    // Name words must be whole words: neighbours' networks are not cameras or cars
    for (n in listOf("Penguin_5G", "Club Penguin", "Flockhart Family")) {
        val h = wifi("02:aa:bb:cc:dd:01", n)
        check("\"$n\" is not a Flock camera", h.category != Category.POLICE && h.findings.none { it.points >= 50 }, "${h.category} ${h.findings}")
    }
    check("FlockSafety word still -> POLICE", wifi("02:aa:bb:cc:dd:02", "FlockSafety-Cam").category == Category.POLICE)
    check("Flock word still -> POLICE", wifi("02:aa:bb:cc:dd:02", "Flock Cam 2").category == Category.POLICE)
    for (n in listOf("Stanford_Guest", "Oxford Apartments", "Clifford", "Rhonda's WiFi", "Campbell Family", "Camden 2.4", "Cambridge Inn", "Charlotte", "Carlos", "Ernest Home", "Wizards Den", "Camry")) {
        val h = wifi("02:aa:bb:cc:dd:03", n)
        check("\"$n\" is not a car or camera", h.category != Category.VEHICLE && h.category != Category.CAMERA_IOT && h.findings.none { "Camera/IoT-style" in it.text }, "${h.category} ${h.label} ${h.findings}")
    }
    for (n in listOf("Ford SYNC", "MyFord Touch", "Honda HFT", "OBDII", "Veepeak-OBD2")) check("\"$n\" still -> VEHICLE", wifi("02:aa:bb:cc:dd:04", n).category == Category.VEHICLE, n)
    for (n in listOf("Wyze Cam v3", "IPCAM-1234", "Dashcam", "Camera_Front", "Arlo-5G", "SpyCam", "Nest-Hub", "WiZ_Bulb", "Hue Bridge")) {
        val h = wifi("02:aa:bb:cc:dd:05", n)
        check("\"$n\" still camera/IoT or car", h.category == Category.CAMERA_IOT || h.category == Category.VEHICLE, "${h.category} ${h.label}")
    }
    val cp = wifi("00:30:44:aa:bb:cc", "IBR900-1b2")
    check("Cradlepoint IBR900 default name -> VEHICLE, not police", cp.category == Category.VEHICLE && cp.label.startsWith("Fleet vehicle router"), "${cp.category} ${cp.label} ${cp.findings}")
    val sierra = wifi("00:a0:d5:aa:bb:cc", "AirLink")
    check("Sierra Wireless router alone -> VEHICLE", sierra.category == Category.VEHICLE, "${sierra.category} ${sierra.label}")
    val sheriff = wifi("00:30:44:aa:bb:01", "PCSO-24")
    check("Fleet router named PCSO-24 -> Police vehicle", sheriff.category == Category.POLICE && sheriff.label == "Police vehicle", "${sheriff.category} ${sheriff.label}")
    val cobb = wifi("00:30:44:aa:bb:02", "CCPD 1234")
    check("Fleet router named CCPD 1234 -> Police vehicle", cobb.category == Category.POLICE && cobb.label == "Police vehicle", "${cobb.category} ${cobb.label}")
    val medic = wifi("00:a0:d5:aa:bb:03", "Medic 12")
    check("Medic 12 -> EMS vehicle (police/EMS mode)", medic.category == Category.POLICE && medic.label.startsWith("Ambulance"), "${medic.category} ${medic.label}")
    val engine = wifi("00:a0:d5:aa:bb:04", "Engine 5")
    check("Engine 5 -> Fire truck", engine.category == Category.POLICE && engine.label == "Fire truck", "${engine.category} ${engine.label}")
    val bus = wifi("00:a0:d5:aa:bb:05", "MARTA Bus 1234")
    check("MARTA bus router -> commercial fleet, not police", bus.category == Category.VEHICLE && bus.label.startsWith("Commercial"), "${bus.category} ${bus.label}")
    val ups = wifi("00:30:44:aa:bb:06", "UPS-Truck-55")
    check("UPS truck -> not police", ups.category != Category.POLICE, "${ups.category} ${ups.label}")
    for (home in listOf("Leo's iPhone", "Marshall-Acton", "Fire TV-1234", "Miso_net", "PAW Patrol Tablet", "Echo Dot-7F", "Lasso 5G", "Engineering Lab")) {
        val h = wifi("a4:2b:b0:11:22:33", home)
        check("Home network \"$home\" -> not police", h.category != Category.POLICE, "${h.category} ${h.label} ${h.findings}")
    }
    check("iPhone hotspot -> PHONE", wifi("6a:11:22:33:44:55", "Kevin's iPhone").label == "iPhone hotspot")
    check("myChevrolet -> VEHICLE", wifi("6a:11:22:33:44:56", "myChevrolet 5821").category == Category.VEHICLE)
    check("VIOFO -> dash cam", wifi("6a:11:22:33:44:57", "VIOFO_A129_PRO").label == "Dash cam")
    check("TELLO -> DRONE", wifi("60:60:1f:00:00:01", "TELLO-5F2A1B").category == Category.DRONE)
    val esp = wifi("dc:4f:22:12:34:56", "ESP_C1A2B3", "[ESS]")
    check("ESP SSID -> camera/IoT + camera finding", esp.category == Category.CAMERA_IOT && esp.findings.any { it.points == 30 }, "${esp.category} ${esp.findings}")

    // WPS information element: HP printer over Wi-Fi Direct
    fun tlv(t: Int, v: ByteArray) = bytes(t shr 8, t and 0xFF, v.size shr 8, v.size and 0xFF) + v
    val wps = bytes(0x00, 0x50, 0xF2, 0x04) + tlv(0x1021, "HP".toByteArray()) + tlv(0x1023, "OfficeJet Pro 9015".toByteArray()) +
        tlv(0x1054, bytes(0x00, 0x03, 0x00, 0x50, 0xF2, 0x04, 0x00, 0x01))
    val ieInfo = WifiIe.parse(listOf(Ie(221, wps), Ie(221, bytes(0xFA, 0x0B, 0xBC, 0x0D, 0x00))))
    check("WPS parse maker/model/category", ieInfo.wpsManufacturer == "HP" && ieInfo.wpsModel == "OfficeJet Pro 9015" && ieInfo.wpsCategory == 3, "${ieInfo.wpsManufacturer} ${ieInfo.wpsModel} ${ieInfo.wpsCategory}")
    check("Remote ID vendor IE detected", ieInfo.remoteId)
    val printer = wifi("8a:11:22:33:44:55", "DIRECT-4B-HP OfficeJet Pro 9015", ie = WifiIe.parse(listOf(Ie(221, wps))))
    check("WPS printer label", printer.category == Category.CAMERA_IOT && printer.label.contains("OfficeJet"), printer.label)

    // ---- Distance + Kalman
    val dev = Device("aa:bb:cc:dd:ee:ff", Kind.BLE, t0).apply { category = Category.PHONE }
    repeat(10) { dev.addReading(-60 + (if (it % 2 == 0) 4 else -4), t0 + it * 500L) }
    check("Kalman smooths +/-4 dB noise", kotlin.math.abs(dev.kalman.x + 60) < 2.5, dev.kalman.x)
    val est = Distance.estimate(dev, Env.INDOOR, t0 + 5000)
    check("Phone at -60 indoor ~1 m", est.meters in 0.7..1.4, est.meters)
    check("Range brackets estimate", est.lo < est.meters && est.hi > est.meters, "${est.lo} ${est.hi}")
    dev.rttMeters = 7.2; dev.rttStd = 0.6; dev.rttAt = t0 + 4000
    val rtt = Distance.estimate(dev, Env.INDOOR, t0 + 5000)
    check("RTT overrides RSSI", rtt.meters == 7.2 && rtt.source.contains("RTT"), rtt)
    val car = Distance.meters(-60.0, -70.0, Env.CAR); val open = Distance.meters(-60.0, -70.0, Env.OPEN)
    check("Car profile compensates body loss", car < open, "$car $open")
    val ap = Device("10:20:30:40:50:60", Kind.WIFI, t0).apply { frequency = 2437 }
    repeat(5) { ap.addReading(-60, t0 + it * 1000L) }
    val apEst = Distance.estimate(ap, Env.INDOOR, t0 + 5000)
    check("2.4 GHz AP at -60 ~ 10-25 m indoor", apEst.meters in 10.0..25.0, apEst.meters)

    // ---- Store, scoring, alerts
    val store = DeviceStore()
    val tag = store.upsert("f1:22:33:44:55:66", Kind.BLE, t0).apply {
        category = Category.TRACKER; bleType = Classifier.FINDMY_SEPARATED
        findings = listOf(Finding(40, Classifier.FINDMY_SEPARATED)); addReading(-62, t0)
    }
    val cop = store.upsert("00:25:df:12:34:56", Kind.BLE, t0).apply {
        category = Category.POLICE; findings = axon.findings; addReading(-75, t0)
    }
    var alerts = store.rescore(t0)
    check("Axon alerts immediately", alerts.contains(cop) && cop.level == Level.HIGH, "${cop.score} ${cop.flags}")
    check("Separated AirTag MEDIUM + alert", tag.level == Level.MEDIUM && alerts.contains(tag), tag.score)
    val t12 = t0 + 12 * 60_000L
    for (m in 1..11) { tag.addReading(-62, t0 + m * 60_000L); cop.addReading(-75, t0 + m * 60_000L) } // heard every minute
    tag.addReading(-61, t12); cop.addReading(-76, t12)
    alerts = store.rescore(t12)
    check("No duplicate alerts", alerts.isEmpty(), alerts.map { it.key })
    check("Tracker persistence -> HIGH", tag.level == Level.HIGH && tag.flags.any { "stayed with you for 12 min" in it }, tag.flags)
    store.setTrusted(tag.key, true); store.rescore(t12 + 1000)
    check("Marked as mine -> 0", tag.score == 0)

    // ---- Snapshot / export / JSON
    val views = store.active(t12 + 1000).map { Snapshot.view(it, Env.INDOOR, t12 + 1000, null, null) }
    check("Placeholder angle 0..360 and stable", views.all { it.angle in 0f..360f } && Snapshot.hashAngle("a") == Snapshot.hashAngle("a"))
    val snap = Snapshot(t12, true, "warn \"x\"", views, store.timeline.toList(), Env.INDOOR, "", "", 1, 33.92, -84.84, 5.0, null, 0)
    val js = Exporter.snapshotJson(snap)
    val back = MiniJson.parse(js) as Map<*, *>
    check("Snapshot JSON round-trips", (back["devices"] as List<*>).size == views.size && back["warning"] == "warn \"x\"")
    java.io.File("/tmp/snapshot.json").writeText(js)
    check("CSV header + rows", Exporter.devicesCsv(views).trim().lines().size == views.size + 1)

    // ---- ALPR / geo
    val overpass = """{"version":0.6,"elements":[
      {"type":"node","id":101,"lat":33.9230,"lon":-84.8410,"tags":{"man_made":"surveillance","surveillance:type":"ALPR","manufacturer":"Flock Safety","operator":"Paulding County Sheriff","direction":"270"}},
      {"type":"node","id":102,"lat":33.9400,"lon":-84.8410,"tags":{"man_made":"surveillance","surveillance:type":"gunshot_detector"}}]}"""
    val alprs = AlprLogic.parse(overpass)
    check("Overpass parse", alprs.size == 2 && alprs[0].manufacturer == "Flock Safety" && alprs[0].direction == "270", alprs)
    val me = 33.9212 to -84.8410 // ~200 m south of camera 101
    val hits = AlprLogic.hits(alprs, me.first, me.second)
    check("ALPR distance ~200 m due north", kotlin.math.abs(hits[0].dist - 200) < 5 && Geo.cardinal(hits[0].bearing) == "N", "${hits[0].dist} ${hits[0].bearing}")
    check("Alert when driving toward it", AlprLogic.shouldAlert(hits[0], 15.0, 5.0))
    check("No alert when driving away", !AlprLogic.shouldAlert(hits[0], 15.0, 180.0))
    check("No alert walking at 200 m", !AlprLogic.shouldAlert(hits[0], 1.0, 0.0))
    check("Overpass query has both types", AlprLogic.overpassQuery(33.9, -84.8).let { "ALPR" in it && "gunshot_detector" in it })
    check("angleDiff wraps", Geo.angleDiff(350.0, 10.0) == 20.0)
    check("ALPR view title", Snapshot.alprView(hits[0], t0).title == "Flock Safety ALPR (mapped)")

    // ---- Locator v2: joint position + transmit power
    val rnd = java.util.Random(7)
    val proj = LocalProj(33.9200, -84.8400)
    fun simulate(points: List<Pair<Double, Double>>, devX: Double, devY: Double, trueP0: Double, priorP0: Double, sigmaDb: Double = 3.0): LocTrack {
        val tr = LocTrack()
        var t = 0L
        for ((px, py) in points) {
            val d = kotlin.math.hypot(devX - px, devY - py).coerceAtLeast(1.0)
            repeat(3) {
                val rssi = trueP0 - 10 * 2.6 * kotlin.math.log10(d) + rnd.nextGaussian() * sigmaDb
                Locator.add(tr, proj.lat(py), proj.lon(px), rssi, t, 3.0); t += 700
            }
        }
        Locator.estimate(tr, priorP0, 8.0, Env.INDOOR, t)
        return tr
    }
    fun errOf(tr: LocTrack, x: Double, y: Double) = kotlin.math.hypot(proj.x(tr.estLon) - x, proj.y(tr.estLat) - y)
    val loop = (0 until 40).map { val a = it * Math.PI * 2 / 40; 25 * kotlin.math.cos(a) to 25 * kotlin.math.sin(a) }
    val tr1 = simulate(loop, 35.0, 22.0, -60.0, -60.0)
    fun altErrOf(tr: LocTrack, x: Double, y: Double) = tr.altLat?.let { kotlin.math.hypot(proj.x(tr.altLon!!) - x, proj.y(it) - y) } ?: 999.0
    check("Locator: walk loop -> located, or truth is one of two candidate spots (<8 m)",
        (tr1.located && errOf(tr1, 35.0, 22.0) < 8.0) || (tr1.ambiguous && minOf(errOf(tr1, 35.0, 22.0), altErrOf(tr1, 35.0, 22.0)) < 8.0),
        "located=${tr1.located} amb=${tr1.ambiguous} best=${"%.1f".format(errOf(tr1, 35.0, 22.0))} alt=${"%.1f".format(altErrOf(tr1, 35.0, 22.0))}")
    // Device inside the loop (walking around the block it's in) with known power class
    val trIn = simulate(loop, 6.0, -4.0, -60.0, -60.0)
    check("Locator: device inside your loop", (trIn.located && errOf(trIn, 6.0, -4.0) < 6.0) || (trIn.ambiguous && minOf(errOf(trIn, 6.0, -4.0), altErrOf(trIn, 6.0, -4.0)) < 6.0),
        "loc=${trIn.located} amb=${trIn.ambiguous} err=${"%.1f".format(errOf(trIn, 6.0, -4.0))}")
    // Device transmits 12 dB stronger than assumed. On a pure loop that's mathematically ambiguous
    // (a point and its mirror inside the loop give identical patterns): must NOT claim a location.
    val tr2 = simulate(loop, 35.0, 22.0, -48.0, -60.0)
    val altErr = tr2.altLat?.let { kotlin.math.hypot(proj.x(tr2.altLon!!) - 35, proj.y(it) - 22) } ?: 999.0
    check("Locator: loop + unknown power -> never confidently wrong", (tr2.ambiguous && minOf(errOf(tr2, 35.0, 22.0), altErr) < 10) ||
        (tr2.located && errOf(tr2, 35.0, 22.0) <= tr2.errM + 3) || (!tr2.located && !tr2.ambiguous), "loc=${tr2.located} amb=${tr2.ambiguous} best=${errOf(tr2, 35.0, 22.0)} alt=$altErr")
    // Realistic walk: down a street, turn the corner, walk back on the next street -> learns power and position
    val zig = (0..25).map { it * 4.0 - 50 to 0.0 } + (0..10).map { 50.0 to it * 4.0 } + (0..25).map { 50 - it * 4.0 to 40.0 }
    val tr3 = simulate(zig, 18.0, 62.0, -48.0, -60.0)
    check("Locator: realistic walk learns unknown transmit power (<10 m)", tr3.located && errOf(tr3, 18.0, 62.0) < 10.0, "loc=${tr3.located} amb=${tr3.ambiguous} err=${"%.1f".format(errOf(tr3, 18.0, 62.0))} ±${tr3.errM.toInt()} p0=${tr3.p0}")
    check("Locator: error radius covers truth", !tr3.located || errOf(tr3, 18.0, 62.0) <= tr3.errM + 3, "true=${errOf(tr3, 18.0, 62.0)} reported=${tr3.errM}")
    val still = simulate(List(20) { 0.0 to 0.0 }, 35.0, 22.0, -60.0, -60.0)
    check("Locator: standing still = not located", !still.located)
    val line = simulate((0..40).map { it * 4.0 - 80 to 0.0 }, 10.0, 30.0, -60.0, -60.0)
    check("Locator: straight road flags side ambiguity", line.ambiguous || !line.located || line.errM >= 25, "amb=${line.ambiguous} loc=${line.located} err=${line.errM}")
    check("Locator: ambiguous result keeps the other side", !line.ambiguous || line.altLat != null)
    check("Locator: memory capped", simulate((0..300).map { (it % 20) * 4.0 to (it / 20) * 4.0 }, 30.0, 30.0, -60.0, -60.0).obs.size <= Locator.MAX_OBS)
    // RTT ranges pin an access point precisely even with few RSSI samples
    val trR = LocTrack(); var tt = 0L
    for ((px, py) in loop.take(12)) {
        val d = kotlin.math.hypot(20.0 - px, -15.0 - py)
        Locator.addRtt(trR, proj.lat(py), proj.lon(px), d + rnd.nextGaussian() * 0.8, 0.8, tt, 2.0); tt += 1000
        Locator.add(trR, proj.lat(py), proj.lon(px), -40 - 26 * kotlin.math.log10(d), tt, 2.0)
    }
    Locator.estimate(trR, -30.0, 8.0, Env.INDOOR, tt)
    check("Locator: Wi-Fi RTT pins AP (<3 m)", trR.located && errOf(trR, 20.0, -15.0) < 3.0, "err=${"%.2f".format(errOf(trR, 20.0, -15.0))} ±${trR.errM}")

    // Hold hysteresis: a located device is not taken off the map by one "moving" verdict (a wall, a truck),
    // only by one that persists; a plain poor estimate keeps the spot for the 30 s hold.
    run {
        val th = LocTrack()
        Locator.settlePublic(th, true, 33.92, -84.84, 12.0, 100_000L)
        check("Locator hold: good fix shows", th.located)
        th.moving = true; Locator.settlePublic(th, false, 0.0, 0.0, 0.0, 103_000L)
        check("Locator hold: one moving verdict keeps the marker", th.located)
        th.moving = false; Locator.settlePublic(th, false, 0.0, 0.0, 0.0, 106_000L)
        check("Locator hold: poor estimate keeps the marker", th.located)
        th.moving = false; Locator.settlePublic(th, true, 33.92, -84.84, 12.0, 108_000L)
        check("Locator hold: a good fix clears the moving timer", th.movingSince == 0L)
        th.moving = true; Locator.settlePublic(th, false, 0.0, 0.0, 0.0, 110_000L)
        th.moving = true; Locator.settlePublic(th, false, 0.0, 0.0, 0.0, 116_000L)
        check("Locator hold: still shown 6 s into a moving verdict", th.located)
        th.moving = true; Locator.settlePublic(th, false, 0.0, 0.0, 0.0, 123_000L)
        check("Locator hold: persistent moving verdict (13 s) takes it down", !th.located)
        // Moving verdicts with "no idea" estimates in between still count as one persisting verdict
        val tl = LocTrack()
        Locator.settlePublic(tl, true, 33.92, -84.84, 12.0, 0L)
        tl.moving = true; Locator.settlePublic(tl, false, 0.0, 0.0, 0.0, 10_000L)
        tl.moving = false; Locator.settlePublic(tl, false, 0.0, 0.0, 0.0, 14_000L)
        tl.moving = true; Locator.settlePublic(tl, false, 0.0, 0.0, 0.0, 18_000L)
        tl.moving = false; Locator.settlePublic(tl, false, 0.0, 0.0, 0.0, 21_000L)
        tl.moving = true; Locator.settlePublic(tl, false, 0.0, 0.0, 0.0, 23_000L)
        check("Locator hold: moving verdicts interleaved with blank estimates still take it down", !tl.located)
        // A one-off verdict long ago is not linked to a new one
        val to = LocTrack().apply { longHold = true }
        Locator.settlePublic(to, true, 33.92, -84.84, 12.0, 0L)
        to.moving = true; Locator.settlePublic(to, false, 0.0, 0.0, 0.0, 5_000L)
        to.moving = false; Locator.settlePublic(to, false, 0.0, 0.0, 0.0, 10_000L)
        Locator.add(to, 33.9201, -84.8401, -70.0, 60_000L, 3.0)
        to.moving = true; Locator.settlePublic(to, false, 0.0, 0.0, 0.0, 61_000L)
        check("Locator hold: an old one-off verdict does not count toward a new one", to.located && to.movingSince == 61_000L)

        val tq = LocTrack()
        Locator.settlePublic(tq, true, 33.92, -84.84, 12.0, 0L)
        Locator.settlePublic(tq, false, 0.0, 0.0, 0.0, 29_000L)
        check("Locator hold: poor estimates keep the spot for 30 s", tq.located)
        Locator.settlePublic(tq, false, 0.0, 0.0, 0.0, 31_000L)
        check("Locator hold: gone after the hold", !tq.located)
        // Devices that can move (fitted on recent readings only) never get more than the 30 s hold
        val te = LocTrack()
        Locator.add(te, 33.92, -84.84, -60.0, 0L, 3.0)
        Locator.settlePublic(te, true, 33.92, -84.84, 12.0, 1_000L)
        Locator.estimate(te, -60.0, 8.0, Env.OPEN, 5_000L, 120_000L)
        check("Locator hold: phone, too few readings keeps a good spot briefly", te.located)
        Locator.add(te, 33.92, -84.84, -60.0, 39_000L, 3.0)
        Locator.estimate(te, -60.0, 8.0, Env.OPEN, 40_000L, 120_000L)
        check("Locator hold: phone, gone after 30 s even while heard and standing still", !te.located)
        // Fixed gear: still being heard and nothing says it moved: the spot stays well past 30 s, but not forever
        val tk = LocTrack().apply { longHold = true }
        Locator.settlePublic(tk, true, 33.92, -84.84, 12.0, 0L)
        Locator.add(tk, 33.9201, -84.8401, -70.0, 50_000L, 3.0)
        Locator.settlePublic(tk, false, 0.0, 0.0, 0.0, 60_000L)
        check("Locator hold: still heard keeps the spot past 30 s", tk.located)
        Locator.add(tk, 33.9202, -84.8402, -70.0, 80_000L, 3.0)
        Locator.settlePublic(tk, false, 0.0, 0.0, 0.0, 95_000L)
        check("Locator hold: heard-hold ends after 90 s", !tk.located)
        // A poor fit that lands somewhere else entirely (or finds a second candidate) is not "nothing new"
        val td = LocTrack().apply { longHold = true }
        Locator.settlePublic(td, true, 33.92, -84.84, 12.0, 0L)
        Locator.add(td, 33.9201, -84.8401, -70.0, 40_000L, 3.0)
        Locator.settlePublic(td, false, 33.9205, -84.8405, 30.0, 41_000L) // ~70 m away: disagrees
        check("Locator hold: a fit elsewhere ends the hold at 30 s", !td.located)
        val ta = LocTrack().apply { longHold = true }
        Locator.settlePublic(ta, true, 33.92, -84.84, 12.0, 0L)
        Locator.add(ta, 33.9201, -84.8401, -70.0, 40_000L, 3.0)
        ta.ambiguous = true; ta.altLat = 33.9205; ta.altLon = -84.8405
        Locator.settlePublic(ta, false, 33.92001, -84.84001, 30.0, 41_000L)
        check("Locator hold: an ambiguous fit ends the hold at 30 s", !ta.located)
        // Standing still: nothing new is learned, so fixed gear keeps a good spot as long as it is heard...
        val ts = LocTrack().apply { longHold = true }
        Locator.add(ts, 33.92, -84.84, -60.0, 0L, 3.0)
        Locator.settlePublic(ts, true, 33.9202, -84.8402, 12.0, 1_000L)
        for (t in 15_000L..300_000L step 15_000L) Locator.add(ts, 33.92, -84.84, -60.0 + (t / 15_000 % 3), t, 3.0)
        Locator.settlePublic(ts, false, 33.9202, -84.8402, 30.0, 150_000L)
        Locator.estimate(ts, -60.0, 8.0, Env.OPEN, 305_000L, longHold = true)
        check("Locator hold: standing still keeps a heard fixed device's spot (5 min)", ts.located && ts.obs.size == 1 && ts.spotSeq == ts.spotSeqAtGood)
        // The same for a device not known to stay put (an unknown BLE gadget, police gear): 30 s only
        val tu = LocTrack()
        Locator.add(tu, 33.92, -84.84, -60.0, 0L, 3.0)
        Locator.settlePublic(tu, true, 33.9202, -84.8402, 12.0, 1_000L)
        for (t in 15_000L..60_000L step 15_000L) Locator.add(tu, 33.92, -84.84, -60.0, t, 3.0)
        Locator.estimate(tu, -60.0, 8.0, Env.OPEN, 61_000L)
        check("Locator hold: not known to stay put -> 30 s hold even while heard and standing still", !tu.located)
        // ...but not forever, and a persistent "moving" verdict still takes it down
        ts.moving = true; Locator.settlePublic(ts, false, 33.9202, -84.8402, 30.0, 306_000L)
        check("Locator hold: standing still, one moving verdict is ignored", ts.located)
        ts.moving = true; Locator.settlePublic(ts, false, 33.9202, -84.8402, 30.0, 320_000L)
        check("Locator hold: standing still, persistent moving verdict takes it down", !ts.located)
        val tz = LocTrack().apply { longHold = true }
        Locator.add(tz, 33.92, -84.84, -60.0, 0L, 3.0)
        Locator.settlePublic(tz, true, 33.9202, -84.8402, 12.0, 1_000L)
        Locator.add(tz, 33.92, -84.84, -60.0, 11 * 60_000L, 3.0)
        Locator.settlePublic(tz, false, 0.0, 0.0, 0.0, 11 * 60_000L + 1)
        check("Locator hold: standing still ends after 10 min without a good fix", !tz.located)
        // The real thing for a phone: walk past it (located), stop, then it walks away while still heard.
        // Its old spot must not stay on the map (fitted on recent readings only, 30 s hold at most).
        var locatedAtStart = 0; var staleShownFor = 0L
        for (seed in 1..6) {
            val r2 = java.util.Random(seed.toLong())
            val tp = LocTrack(); var t = 0L; var lastEst = 0L
            fun hear(px: Double, py: Double, dx: Double, dy: Double) {
                val d = kotlin.math.hypot(dx - px, dy - py).coerceAtLeast(1.0)
                val rssi = -60.0 - 10 * 2.6 * kotlin.math.log10(d) + r2.nextGaussian() * 4
                if (rssi > -100) Locator.add(tp, proj.lat(py), proj.lon(px), rssi, t, 3.0, Env.INDOOR)
                if (t - lastEst >= 6_000) { lastEst = t; Locator.estimate(tp, -60.0, 8.0, Env.INDOOR, t, 120_000L) }
            }
            for ((px, py) in loop) repeat(3) { hear(px, py, 6.0, -4.0); t += 700 }
            val (sx, sy) = loop.last()
            val tWalk = t
            while (t < tWalk + 20_000) { hear(sx, sy, 6.0, -4.0); t += 2_000 } // stop for 20 s
            val t0 = t
            if (tp.located) locatedAtStart++ else continue
            while (t < t0 + 150_000) { // the phone walks off at 1.3 m/s while its spot is on the map
                val s = (t - t0) / 1000.0
                hear(sx, sy, 6.0 + 1.3 * s * 0.7, -4.0 + 1.3 * s * 0.7)
                if (tp.located && s > 60) staleShownFor = maxOf(staleShownFor, (s - 60).toLong())
                t += 2_000
            }
        }
        check("Locator hold: walk-away scenario exercised (phone on the map when it starts walking)", locatedAtStart >= 2, "located at start in $locatedAtStart of 6 seeds")
        check("Locator hold: phone that walks away loses its old spot within 60 s", staleShownFor == 0L, "stale for ${staleShownFor}s more")
    }

    // ---- GPS Kalman filter
    val pf = PositionFilter()
    val g0 = LocalProj(33.9, -84.8)
    var tn = 0L
    var maxJit = 0.0
    for (k in 0 until 30) { // standing still with 5 m GPS noise
        pf.update(PositionFilter.Fix(g0.lat(rnd.nextGaussian() * 4), g0.lon(rnd.nextGaussian() * 4), 5.0, 0.0, 0.3, null, null, tn)); tn += 1_000_000_000L
        if (k > 5) pf.current()?.let { maxJit = maxOf(maxJit, kotlin.math.hypot(g0.x(it.lon), g0.y(it.lat))) }
    }
    check("GPS filter: steadies a stationary fix (<3.5 m wander)", maxJit < 3.5, "%.2f".format(maxJit))
    // Highway: 31 m/s heading 60°, 1 Hz fixes with 4 m noise
    val pf2 = PositionFilter(); tn = 0L
    val sp = 31.0; val hd = Math.toRadians(60.0)
    var worst = 0.0
    for (k in 0 until 40) {
        val tx = sp * kotlin.math.sin(hd) * k; val ty = sp * kotlin.math.cos(hd) * k
        pf2.update(PositionFilter.Fix(g0.lat(ty + rnd.nextGaussian() * 3), g0.lon(tx + rnd.nextGaussian() * 3), 4.0, sp + rnd.nextGaussian() * 0.3, 0.5, 60.0 + rnd.nextGaussian(), 2.0, tn))
        // Position halfway to the next fix (what the map shows between fixes)
        if (k > 5) pf2.at(tn + 500_000_000L)?.let {
            val ex = sp * kotlin.math.sin(hd) * (k + 0.5); val ey = sp * kotlin.math.cos(hd) * (k + 0.5)
            worst = maxOf(worst, kotlin.math.hypot(g0.x(it.lon) - ex, g0.y(it.lat) - ey))
        }
        tn += 1_000_000_000L
    }
    check("GPS filter: predicts between fixes at 70 mph (<5 m)", worst < 5.0, "%.2f".format(worst))
    check("GPS filter: course + speed", pf2.current()?.let { kotlin.math.abs(it.speed - 31) < 1.5 && kotlin.math.abs(it.course!! - 60) < 3 } == true, pf2.current()?.let { "${it.speed} ${it.course}" })
    // Reception-time interpolation: where were we 2.5 s ago?
    val backPos = pf2.at(tn - 3_500_000_000L)
    val bx = sp * kotlin.math.sin(hd) * 36.5; val by = sp * kotlin.math.cos(hd) * 36.5
    check("GPS filter: position at exact reception time (<4 m)", backPos != null && kotlin.math.hypot(g0.x(backPos.lon) - bx, g0.y(backPos.lat) - by) < 4.0)
    // A single wild jump is rejected
    val beforeJump = pf2.current()!!
    pf2.update(PositionFilter.Fix(g0.lat(5000.0), g0.lon(5000.0), 4.0, 31.0, 0.5, 60.0, 2.0, tn))
    check("GPS filter: rejects a single 5 km jump", kotlin.math.hypot(g0.x(pf2.current()!!.lon) - g0.x(beforeJump.lon), g0.y(pf2.current()!!.lat) - g0.y(beforeJump.lat)) < 60)

    // ---- Highway drive past a roadside AP: Wi-Fi results arrive 0-3 s after reception.
    //      Tagging them with the reception-time position (v2) vs. the arrival-time position (old).
    val roadPf = PositionFilter(); tn = 0L
    val apX = 900.0; val apY = 45.0
    val trNew = LocTrack(); val trOld = LocTrack()
    for (k in 0 until 70) {
        val ux = 31.0 * k; val uy = if (k > 35) (k - 35) * 6.0 else 0.0 // road bends after the AP
        roadPf.update(PositionFilter.Fix(g0.lat(uy + rnd.nextGaussian() * 2), g0.lon(ux + rnd.nextGaussian() * 2), 3.0, 31.0, 0.5, if (k > 35) 79.0 else 90.0, 2.0, tn))
        if (k % 2 == 0 && k > 2) {
            val delay = (rnd.nextDouble() * 3.0 * 1e9).toLong()
            val rxT = tn - delay
            val rxPos = roadPf.at(rxT)!!
            val rx = g0.x(rxPos.lon); val ry = g0.y(rxPos.lat)
            val d = kotlin.math.hypot(apX - rx, apY - ry).coerceAtLeast(1.0)
            if (d < 300) {
                val rssi = -30 - 26 * kotlin.math.log10(d) + rnd.nextGaussian() * 3
                Locator.add(trNew, rxPos.lat, rxPos.lon, rssi, rxT / 1_000_000, 3.0)
                val nowPos = roadPf.current()!!
                Locator.add(trOld, nowPos.lat, nowPos.lon, rssi, tn / 1_000_000, 3.0)
            }
        }
        tn += 1_000_000_000L
    }
    Locator.estimate(trNew, -30.0, 8.0, Env.OPEN, tn / 1_000_000); Locator.estimate(trOld, -30.0, 8.0, Env.OPEN, tn / 1_000_000)
    val eNew = kotlin.math.hypot(g0.x(trNew.estLon) - apX, g0.y(trNew.estLat) - apY)
    val eOld = kotlin.math.hypot(g0.x(trOld.estLon) - apX, g0.y(trOld.estLat) - apY)
    val eNewAlt = trNew.altLat?.let { kotlin.math.hypot(g0.x(trNew.altLon!!) - apX, g0.y(it) - apY) }
    println("   highway AP: reception-time tagging ${"%.0f".format(eNew)} m (located=${trNew.located} ±${trNew.errM.toInt()} amb=${trNew.ambiguous} alt=${eNewAlt?.toInt()}) vs arrival-time ${"%.0f".format(eOld)} m")
    check("Highway: reception-time tagging beats arrival-time tagging", eNew < eOld || minOf(eNew, eNewAlt ?: 999.0) < 20, "new=$eNew old=$eOld")
    check("Highway: never claims a location it can't back up", !trNew.located || eNew <= trNew.errM + 5, "err=$eNew claimed=${trNew.errM}")

    // ---- Street map parse
    val mapJson = """{"elements":[
      {"type":"way","id":1,"tags":{"highway":"primary","name":"Main St"},"geometry":[{"lat":33.9200,"lon":-84.8400},{"lat":33.9210,"lon":-84.8400}]},
      {"type":"way","id":2,"tags":{"building":"yes","building:levels":"3"},"geometry":[{"lat":33.9201,"lon":-84.8401},{"lat":33.9201,"lon":-84.8402},{"lat":33.9202,"lon":-84.8402},{"lat":33.9201,"lon":-84.8401}]},
      {"type":"way","id":3,"tags":{"natural":"water"},"geometry":[{"lat":33.93,"lon":-84.84},{"lat":33.931,"lon":-84.84},{"lat":33.931,"lon":-84.841}]},
      {"type":"way","id":4,"tags":{"highway":"footway"},"geometry":[{"lat":33.9200,"lon":-84.8400},{"lat":33.9200,"lon":-84.8410}]}]}"""
    val md = MapData.parse(mapJson, 33.9200, -84.8400, 0)
    check("Map parse roads/buildings/areas", md.roads.size == 2 && md.buildings.size == 1 && md.areas.size == 1, "${md.roads.size} ${md.buildings.size} ${md.areas.size}")
    check("Road geometry in meters (111 m north)", kotlin.math.abs(md.roads.first { it.cls == 0 }.pts[3] - 111.2f) < 0.5f, md.roads.first { it.cls == 0 }.pts.toList())
    check("Building height from levels", kotlin.math.abs(md.buildings[0].heightM - 9.6f) < 0.01f, md.buildings[0].heightM)
    check("Minor roads drawn first", md.roads.first().cls == 4)
    check("Map query covers roads+buildings", MapData.query(33.9, -84.8).let { "highway" in it && "building" in it && "out geom" in it })
    check("Map JSON for HUD parses", (MiniJson.parse(Exporter.mapJson(md)) as Map<*, *>)["roads"].let { (it as List<*>).size == 2 })

    // ---- Camera direction
    check("Direction 270", Geo.parseDirection("270") == 270.0)
    check("Direction NE", Geo.parseDirection("ne") == 45.0)
    check("Direction range 350-10 -> 0", Geo.parseDirection("350-10") == 0.0, Geo.parseDirection("350-10"))
    check("Direction list takes first", Geo.parseDirection("90;180") == 90.0)
    check("Direction junk -> null", Geo.parseDirection("toward road") == null)

    // ---- Richer details
    val pods = Classifier.classifyBle("4a:11:22:33:44:56", true, AdvParser.parse(ad(0x01 to bytes(0x1A), mfr(0x004C, 0x07, 0x19, 0x01, 0x14, 0x20, 0x55, 0x89, 0x45, 0x00))), db)
    check("AirPods battery decoded", pods.facts["Battery"]?.startsWith("buds 80% · 90% · case 50%") == true && pods.facts["Battery"]!!.contains("charging"), pods.facts["Battery"])
    check("BLE flags decoded", pods.facts["BLE flags"] == "discoverable, BLE + Classic", pods.facts["BLE flags"])
    val nearby = Classifier.classifyBle("5b:22:33:44:55:67", true, AdvParser.parse(ad(mfr(0x004C, 0x10, 0x05, 0x1B, 0x18, 0, 0, 0))), db)
    check("Apple activity decoded", nearby.facts["Apple activity"]?.startsWith("recently used") == true, nearby.facts["Apple activity"])
    val url = Classifier.classifyBle("c6:22:33:44:55:66", true, AdvParser.parse(ad(svcData(0xFEAA, 0x10, 0xEB, 0x03, 'g'.code, 'o'.code, 'o'.code, '.'.code, 'g'.code, 'l'.code, 0x00, 'x'.code))), db)
    check("Eddystone URL decoded", url.facts["Beacon URL"] == "https://goo.gl/x" || url.facts["Beacon URL"] == "https://goo.gl.com/x", url.facts["Beacon URL"])
    val tlm = Classifier.classifyBle("c7:22:33:44:55:66", true, AdvParser.parse(ad(svcData(0xFEAA, 0x20, 0x00, 0x0B, 0xB8, 0x16, 0x80, 0, 0, 0, 10, 0x00, 0x0D, 0x2F, 0x00))), db)
    check("Eddystone TLM decoded", tlm.facts["Beacon telemetry"]?.contains("3.00 V") == true && tlm.facts["Beacon telemetry"]!!.contains("22.5 °C"), tlm.facts["Beacon telemetry"])
    val estUuid = IntArray(23).also { val u = "B9407F30F5F8466EAFF925556B57FE6D"; it[0] = 0x02; it[1] = 0x15; for (k in 0 until 16) it[2 + k] = u.substring(k * 2, k * 2 + 2).toInt(16); it[22] = 0xC5 }
    check("iBeacon vendor from UUID", Classifier.classifyBle("c8:22:33:44:55:66", true, AdvParser.parse(ad(mfr(0x004C, *estUuid))), db).facts["iBeacon UUID"]?.endsWith("(Estimote)") == true)
    check("Fast Pair model ID", Classifier.classifyBle("c9:22:33:44:55:66", true, AdvParser.parse(ad(svcData(0xFE2C, 0x2D, 0x7A, 0x23))), db).facts["Fast Pair model ID"]?.startsWith("0x2D7A23") == true)
    val ies = WifiIe.parse(listOf(Ie(11, bytes(12, 0, 87, 0, 0)), Ie(7, "US ".toByteArray()), Ie(70, bytes(0x73)), Ie(54, bytes(1, 2, 3)),
        Ie(127, bytes(0, 0, 0x08)), Ie(221, bytes(0x50, 0x6F, 0x9A, 0x10, 0)), Ie(133, ByteArray(10) + "LOBBY-AP-02".toByteArray() + ByteArray(5))))
    check("Wi-Fi BSS load", ies.stations == 12 && ies.channelUtil == 34, "${ies.stations} ${ies.channelUtil}")
    check("Wi-Fi country / 11k / 11v / 11r / Passpoint / AP name", ies.country == "US" && ies.rrm && ies.bssTransition && ies.fastRoaming && ies.passpoint && ies.apName == "LOBBY-AP-02", "${ies.country} ${ies.apName}")
    val apc = Classifier.classifyWifi("00:1a:2b:3c:4d:5e", "Corp", 5180, "[RSN-PSK-CCMP]", ies, 6, 80, false, db)
    check("Wi-Fi detail facts", apc.facts["Connected clients"] == "12 · channel busy 34%" && apc.facts["AP name"] == "LOBBY-AP-02" && apc.facts["Roaming support"]!!.contains("802.11r"), apc.facts)
    val hdb = HistoryDb(); val day = 86_400_000L
    hdb.touch("WIFI:aa", 1_700_000_000_000L); hdb.touch("WIFI:aa", 1_700_000_000_000L + 3600_000); hdb.touch("WIFI:aa", 1_700_000_000_000L + 2 * day)
    check("History counts distinct days", hdb.map["WIFI:aa"]!!.days == 2)
    val hdb2 = HistoryDb().apply { load(hdb.dump().lineSequence()) }
    check("History round-trips", hdb2.map["WIFI:aa"]?.days == 2)
    val mdB = MapData.parse("""{"elements":[{"type":"way","tags":{"building":"house","addr:housenumber":"123","addr:street":"Main St"},"geometry":[{"lat":33.92,"lon":-84.84},{"lat":33.92,"lon":-84.8398},{"lat":33.9202,"lon":-84.8398},{"lat":33.9202,"lon":-84.84},{"lat":33.92,"lon":-84.84}]}]}""", 33.92, -84.84, 0)
    val inB = mdB.buildingAt(mdB.proj.x(-84.8399), mdB.proj.y(33.9201))
    check("Building lookup + address label", inB?.label == "123 Main St · house" && mdB.buildingAt(-100.0, -100.0) == null, inB?.label)
    check("ALPR alert distance grows with speed", AlprLogic.shouldAlert(AlprHit(alprs[0], 800.0, 10.0), 33.0, 5.0) && !AlprLogic.shouldAlert(AlprHit(alprs[0], 800.0, 10.0), 10.0, 5.0))

    // ---- Update check
    val rel = UpdateCheck.parse("""{"tag_name":"build-7","html_url":"https://github.com/x","assets":[{"name":"AirScope.apk","url":"https://api.github.com/repos/a/b/releases/assets/9","browser_download_url":"https://github.com/a/b/releases/download/build-7/AirScope.apk"}]}""")
    check("Release parse", rel?.build == 7 && rel.apiAssetUrl?.endsWith("/assets/9") == true, rel?.build)

    // ---- Vendor DB
    check("OUI lookup", db.oui("00:30:44:12:34:56") == "Cradlepoint, Inc" && db.oui("ff:ff:ff:00:00:00") == null)
    check("shortVendor", VendorDb.shortVendor("Shenzhen Minew Technologies Co., Ltd.") == "Shenzhen Minew", VendorDb.shortVendor("Shenzhen Minew Technologies Co., Ltd."))
    check("Query parse", HudServer.parseQuery("k=abc%20d&x=1") == mapOf("k" to "abc d", "x" to "1"))

    // ---- Review fixes
    // Tracker seen in the morning and again 5 h later: not "stayed with you"
    val st2 = DeviceStore()
    val tileTag = st2.upsert("d1:22:33:44:55:77", Kind.BLE, t0).apply {
        category = Category.TRACKER; bleType = Classifier.FINDMY_SEPARATED; findings = listOf(Finding(40, Classifier.FINDMY_SEPARATED))
    }
    for (m in 0..5) tileTag.addReading(-70, t0 + m * 60_000L)
    val later = t0 + 5 * 3_600_000L
    tileTag.addReading(-70, later); st2.rescore(later)
    check("Tracker gap resets presence", tileTag.flags.none { "stayed with you" in it }, tileTag.flags)

    // Malformed HUD request must not throw (it used to crash the whole app)
    val q = runCatching { HudServer.parseQuery("k=%&x=1&y=%zz") }
    check("HUD bad escape survives", q.isSuccess && q.getOrNull()?.get("x") == "1", q.exceptionOrNull())

    // Crafted drone Remote ID with nested message packs finishes instantly
    val nested = ByteArray(2 + 3 * 80) { i -> when { i == 0 -> 0x0D; i == 1 -> 0; (i - 2) % 3 == 0 -> 0xF0.toByte().toInt(); (i - 2) % 3 == 1 -> 25; else -> 9 }.toByte() }
    val tRid = System.nanoTime(); RemoteId.parseBle(nested); val ridMs = (System.nanoTime() - tRid) / 1e6
    check("Nested Remote ID packs can't hang", ridMs < 50, ridMs)

    // GPS gap at highway speed (tunnel): the first fix after it is trusted, no speed spike, no lag
    val gp = PositionFilter(); var gt = 0L
    val gg = LocalProj(33.9, -84.8)
    var gxy = 0.0
    for (k in 0..30) { gp.update(PositionFilter.Fix(gg.lat(0.0), gg.lon(gxy), 4.0, 30.0, 0.5, 90.0, 2.0, gt)); gxy += 30.0; gt += 1_000_000_000L }
    gxy += 30.0 * 19; gt += 19_000_000_000L // 20 s without GPS
    gp.update(PositionFilter.Fix(gg.lat(0.0), gg.lon(gxy), 4.0, 30.0, 0.5, 90.0, 2.0, gt))
    val after = gp.current()!!
    val lagM = Geo.distanceM(after.lat, after.lon, gg.lat(0.0), gg.lon(gxy))
    check("After a 20 s GPS gap: on the new fix, sane speed", lagM < 6 && after.speed < 34, "lag=${"%.1f".format(lagM)} m speed=${"%.1f".format(after.speed)}")
    val mid = gp.at(gt - 10_000_000_000L)
    check("Inside a GPS gap: uncertain, not trusted", mid == null || mid.accM > 20, mid?.accM)
    val ahead = gp.at(gt + 5_000_000_000L)!!
    check("5 s past the last fix at 30 m/s: uncertainty grows with speed", ahead.accM > 40, ahead.accM)

    // GPS that reports speed 0 (or no speed) while you drive: the filter must follow the real movement
    for ((label, spd) in listOf("speed=0" to 0.0, "no speed" to null)) {
        val zp = PositionFilter(); var zt = 0L; var zx = 0.0
        val zr = java.util.Random(5)
        for (k in 0..19) { // stopped for 20 s, then 31 m/s east
            zp.update(PositionFilter.Fix(gg.lat(zr.nextGaussian() * 2), gg.lon(zx + zr.nextGaussian() * 2), 4.0, if (spd == null) null else 0.0, null, null, null, zt)); zt += 1_000_000_000L
        }
        var worst = 0.0
        for (k in 0..29) {
            zx += 31.0
            zp.update(PositionFilter.Fix(gg.lat(zr.nextGaussian() * 2), gg.lon(zx + zr.nextGaussian() * 2), 4.0, spd, null, null, null, zt)); zt += 1_000_000_000L
            if (k >= 4) { val c = zp.current()!!; worst = maxOf(worst, Geo.distanceM(c.lat, c.lon, gg.lat(0.0), gg.lon(zx))) }
        }
        val zc = zp.current()!!
        check("Driving with GPS $label: follows (speed ${"%.0f".format(zc.speed)} m/s, worst ${"%.0f".format(worst)} m)", zc.speed in 25.0..37.0 && worst < 15, "speed=${zc.speed} worst=$worst")
    }

    // ---- Heading
    // Row-major device->world rotation matrices. Flat, top edge pointing east: device Y = world X.
    val flatEast = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
    check("Heading flat: top edge east = 90", kotlin.math.abs(HeadingMath.lookAzimuth(flatEast)!! - 90.0) < 0.5, HeadingMath.lookAzimuth(flatEast))
    // Upright portrait facing north: device Y = up, device Z (screen) = south, so the back camera looks north
    val uprightNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
    val un = HeadingMath.lookAzimuth(uprightNorth)!!
    check("Heading upright: back camera north = 0", Geo.angleDiff(un, 0.0) < 0.5, un)
    // Tilted 45 deg toward you, looking west: top and camera agree
    val c45 = (Math.sqrt(0.5)).toFloat()
    val tiltWest = floatArrayOf(0f, -c45, c45, 1f, 0f, 0f, 0f, c45, c45)
    check("Heading 45° tilt looking west = 270", Geo.angleDiff(HeadingMath.lookAzimuth(tiltWest)!!, 270.0) < 0.5, HeadingMath.lookAzimuth(tiltWest))
    check("Turn guidance right", HeadingMath.turn(90.0, 30.0, 10.0) == "↻ turn right 60°", HeadingMath.turn(90.0, 30.0, 10.0))
    check("Turn guidance left across north", HeadingMath.turn(350.0, 20.0, 10.0) == "↺ turn left 30°", HeadingMath.turn(350.0, 20.0, 10.0))
    check("Turn guidance ahead within accuracy", HeadingMath.turn(100.0, 92.0, 12.0).contains("ahead"))
    check("Turn guidance behind", HeadingMath.turn(185.0, 2.0, 10.0).contains("behind"))
    check("Clock position", HeadingMath.clock(90.0, 0.0) == 3 && HeadingMath.clock(355.0, 0.0) == 12 && HeadingMath.clock(270.0, 0.0) == 9)
    check("Magnetic interference", HeadingMath.interference(80.0, 48.0) && !HeadingMath.interference(50.0, 48.0))
    check("Circular blend wraps", Geo.angleDiff(HeadingMath.blend(350.0, 10.0, 0.5), 0.0) < 0.01)
    check("Smoothing: fast turns follow quicker", HeadingMath.smoothAlpha(120.0, 0.02) > HeadingMath.smoothAlpha(2.0, 0.02) * 5)
    check("Course accuracy improves with speed", HeadingMath.courseAccuracy(30.0, 3.0) < 3 && HeadingMath.courseAccuracy(1.0, 3.0) > 30)
    check("16-point cardinal", Geo.cardinal16(247.0) == "WSW" && Geo.cardinal16(359.0) == "N")

    // ---- Route prediction: follows the road through a bend instead of going straight
    run {
        val rp = LocalProj(33.92, -84.84)
        // Road east for 800 m, then bends north for 1500 m; a side street branches south at x=400
        val main = floatArrayOf(-200f, 0f, 400f, 0f, 800f, 0f) // segment 1
        val north = floatArrayOf(800f, 0f, 820f, 300f, 820f, 1500f) // segment 2 starts where 1 ends
        val side = floatArrayOf(400f, 0f, 400f, -600f)
        fun road(pts: FloatArray, cls: Int) = MapData.Road(cls, null, pts, pts.filterIndexed { i, _ -> i % 2 == 0 }.minOrNull()!!, pts.filterIndexed { i, _ -> i % 2 == 1 }.minOrNull()!!,
            pts.filterIndexed { i, _ -> i % 2 == 0 }.maxOrNull()!!, pts.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull()!!)
        val md = MapData(33.92, -84.84, listOf(road(main, 1), road(north, 1), road(side, 2)), emptyList(), emptyList(), 0L, 3000.0)
        val pred = RoutePredictor.predict(listOf(md), rp.lat(0.0), rp.lon(0.0), 90.0, 1800.0)
        val end = pred.main.last()
        val ex = rp.x(end[1]); val ey = rp.y(end[0])
        check("Route prediction follows the bend north", ex in 780.0..860.0 && ey > 800, "end=(%.0f, %.0f)".format(ex, ey))
        check("Route prediction notes the side street", pred.branches.any { rp.y(it[0]) < -100 }, pred.branches.size)
        val straight = RoutePredictor.predict(emptyList(), 33.92, -84.84, 0.0, 1000.0)
        check("No map loaded: extends straight ahead", straight.main.isNotEmpty() && Geo.distanceM(33.92, -84.84, straight.main.last()[0], straight.main.last()[1]) in 950.0..1050.0)
        check("Route tiles cover the path", RoutePredictor.tiles(pred).isNotEmpty())
    }

    // ---- Tile manager: disk cache, back-off, offline area (fake network)
    run {
        val dir = java.nio.file.Files.createTempDirectory("tiles").toFile()
        var calls = 0
        var fail = true
        val tm = TileManager(dir, { url -> calls++; if (fail) throw java.io.IOException("HTTP 503") else ByteArray(0) }, threads = 1)
        val ids = Tiles.around(33.92, -84.84, 500.0, 14)
        fun waitFor(cond: () -> Boolean) { val t0 = System.currentTimeMillis(); while (!cond() && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20) }
        tm.request(ids); waitFor { tm.stats.failed >= ids.size }
        check("Tiles: failure recorded for diagnostics", tm.stats.failed >= 1 && tm.stats.lastError == "HTTP 503", tm.stats.lastError)
        val before = calls
        tm.request(ids); Thread.sleep(200)
        check("Tiles: failed tiles back off (no hammering)", calls == before, "$before -> $calls")
        fail = false
        val tm2 = TileManager(dir, { calls++; ByteArray(16) { 1 } }, threads = 2)
        tm2.request(ids); waitFor { ids.all { tm2.cached(it) != null } }
        check("Tiles: downloaded tiles saved to disk", ids.all { tm2.onDisk(it) } && tm2.cached(ids[0]) != null)
        val c0 = calls
        val tm3 = TileManager(dir, { calls++; ByteArray(16) { 1 } }, threads = 2)
        tm3.request(ids); waitFor { tm3.stats.fromDisk >= ids.size }
        check("Tiles: second run loads from the phone, no network", calls == c0 && tm3.stats.fromDisk >= ids.size, "${calls - c0} downloads")
        var last = 0; var outOfOrder = 0
        // Pair up the offline downloads so two tiles finish at the same moment (the ordering race needs that).
        val pair = java.util.concurrent.CyclicBarrier(2)
        val tm4 = TileManager(dir, { calls++; try { pair.await(300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) { pair.reset() }; ByteArray(16) { 1 } }, threads = 2)
        val failed = tm4.downloadArea(33.92, -84.84, 3000.0, { d, _, _ -> if (d <= last) outOfOrder++; last = d }, { false })
        check("Offline area: every tile saved", failed == 0 && last == Tiles.around(33.92, -84.84, 3000.0, 14).size, "$failed failed, $last done")
        check("Offline area: progress reported in order", outOfOrder == 0, "$outOfOrder steps went backwards")
        dir.deleteRecursively()
    }

    // ---- Placement stays steady: a located device must not blink off or jump between estimates
    run {
        val sp = LocalProj(33.92, -84.84)
        val trk = LocTrack()
        val rr = java.util.Random(21)
        var t = 0L
        var blinks = 0; var wasLoc = false; var maxJump = 0.0; var prevLat = 0.0; var prevLon = 0.0
        for (k in 0..240) { // 80 s loop walk around a device at (20, 10) with noisy readings
            val a = k / 40.0 * 2 * Math.PI / 3
            val x = 30 * Math.sin(a); val y = 30 * Math.sin(2 * a) // figure-8 (crosses the middle: no mirror twin)
            val d = Math.hypot(20 - x, 10 - y).coerceAtLeast(1.0)
            val rssi = -60 - 10 * 2.2 * Math.log10(d) + rr.nextGaussian() * 5
            Locator.add(trk, sp.lat(y), sp.lon(x), rssi, t, 3.0, Env.OPEN)
            if (k % 2 == 0) {
                Locator.estimate(trk, -60.0, 8.0, Env.OPEN, t)
                if (trk.located) {
                    if (wasLoc) maxJump = maxOf(maxJump, Geo.distanceM(prevLat, prevLon, trk.estLat, trk.estLon))
                    prevLat = trk.estLat; prevLon = trk.estLon
                } else if (wasLoc) blinks++
                wasLoc = trk.located
            }
            t += 333
        }
        check("Placement: once located it stays on (no blinking)", blinks == 0, "blinks=$blinks")
        check("Placement: shown spot moves smoothly (max step < 6 m)", maxJump < 6.0, "maxJump=%.1f".format(maxJump))
        check("Placement: ends located near truth", trk.located && Geo.distanceM(trk.estLat, trk.estLon, sp.lat(10.0), sp.lon(20.0)) < 15, trk.located)
    }

    // ---- Scan radius gate: noisy distance around the edge must not flicker
    run {
        val rr = java.util.Random(9)
        var inR = true; var flips = 0; var prev = true
        for (k in 0 until 400) { inR = RadiusGate.next(inR, 100 + rr.nextGaussian() * 8, 100.0); if (inR != prev) flips++; prev = inR }
        check("Radius gate: ±8 m noise at the edge flips at most once", flips <= 1, "flips=$flips")
        check("Radius gate: clearly outside leaves", !RadiusGate.next(true, 130.0, 100.0) && RadiusGate.next(false, 85.0, 100.0))
    }

    // ---- Search / watch rules
    run {
        val st = DeviceStore()
        val fl = st.upsert("aa:bb:cc:11:22:33", Kind.WIFI, t0).apply { name = "Flock-ABC123"; vendor = "Flock Safety"; category = Category.POLICE; addReading(-70, t0) }
        val ph = st.upsert("c1:22:33:44:55:99", Kind.BLE, t0).apply { name = "Pixel 8"; category = Category.PHONE; addReading(-70, t0) }
        check("Search: name", DeviceFilter.matches(fl, "flock") && !DeviceFilter.matches(ph, "flock"))
        check("Search: MAC fragment", DeviceFilter.matches(ph, "55:99"))
        check("Search: category prefix", DeviceFilter.matches(fl, "cat:pol") && !DeviceFilter.matches(ph, "cat:pol"))
        check("Search: multi-word = all", DeviceFilter.matches(fl, "flock safety") && !DeviceFilter.matches(fl, "flock pixel"))
        st.watchRules = listOf("pixel")
        st.rescore(t0)
        check("Watch rule marks matching device", ph.priority == 1 && ph.flags.any { "watch list" in it } && fl.priority == 0, "${ph.priority} ${ph.flags}")
        check("Watched device alerts even at LOW", Heuristics.wantsAlert(ph))
        st.watchRules = emptyList(); st.rescore(t0 + 1000)
        check("Rule removed -> priority cleared", ph.priority == 0)
    }

    // ---- Heading fusion (gyro + gated compass + GPS course)
    run {
        val hf = HeadingFusion()
        var tn = 1_000_000_000L
        fun step(gyro: Double, compass: Double, ok: Boolean, secs: Double = 1.0) {
            val n = (secs * 50).toInt()
            repeat(n) { hf.onGyro(gyro, tn); if (it % 3 == 0) hf.onCompass(compass, tn, ok, 8.0); tn += 20_000_000L }
        }
        // gyro's arbitrary zero is 100 deg off true north; compass is right and the field is clean
        step(10.0, 110.0, true, 3.0)
        check("Fusion: settles on the compass", Geo.angleDiff(hf.facing!!, 110.0) < 2, hf.facing)
        // Walk next to a car: compass swings 80 deg, but the field fails the Earth check -> ignored
        step(10.0, 190.0, false, 5.0)
        check("Fusion: magnetic disturbance ignored (no swing)", Geo.angleDiff(hf.facing!!, 110.0) < 2 && hf.source == HeadingFusion.Source.GYRO, "${hf.facing} ${hf.source}")
        // Turn 45 deg right while the compass is still blocked: the gyro follows
        step(55.0, 250.0, false, 1.0)
        check("Fusion: turning tracked by gyro while compass blocked", Geo.angleDiff(hf.facing!!, 155.0) < 2, hf.facing)
        check("Fusion: accuracy widens while blocked", hf.accuracy > 4, hf.accuracy)
        // Field clean again: back to compass, no jump if it agrees
        step(55.0, 155.0, true, 3.0)
        check("Fusion: compass trusted again when clean", Geo.angleDiff(hf.facing!!, 155.0) < 2 && hf.source == HeadingFusion.Source.COMPASS, "${hf.facing} ${hf.source}")
        // Wrap-around: gyro 350 + offset -> near north
        step(350.0, 95.0, true, 6.0)
        check("Fusion: wraps across north", Geo.angleDiff(hf.facing!!, 95.0) < 3, hf.facing)
        // Driving hysteresis: 10 m/s for 4 s -> driving; brief slowdown to 4 m/s doesn't drop it
        repeat(40) { hf.onCourse(70.0, 10.0, 2.0, tn); tn += 100_000_000L }
        check("Fusion: driving detected after 3 s", hf.driving && Geo.angleDiff(hf.mapHeading!!, 70.0) < 0.1, "${hf.driving} ${hf.mapHeading}")
        repeat(30) { hf.onCourse(70.0, 4.0, 5.0, tn); tn += 100_000_000L }
        check("Fusion: stop-and-go doesn't flip out of driving", hf.driving)
        repeat(100) { hf.onCourse(null, 0.5, 90.0, tn); tn += 100_000_000L }
        check("Fusion: stopped 10 s -> walking mode", !hf.driving)
        check("Earth field check: car body / magnet", !HeadingFusion.earthField(80.0, 47.0, 62.0, 62.0, 1.0) &&
            !HeadingFusion.earthField(47.0, 47.0, 30.0, 62.0, 1.0) && HeadingFusion.earthField(48.0, 47.0, 60.0, 62.0, 1.0))
    }


    // ---- Names: owner from a device's own name
    run {
        fun o(n: String) = Names.owner(n)
        check("owner: Sarah's AirPods", o("Sarah's AirPods") == "Sarah", o("Sarah's AirPods"))
        check("owner: curly apostrophe", o("Mike\u2019s iPhone") == "Mike")
        check("owner: James' iPhone", o("James' iPhone") == "James", o("James' iPhone"))
        check("owner: no apostrophe keeps the word as typed", o("Sarahs iPhone") == "Sarahs" && o("James iPhone") == "James" && o("Chris iPad") == "Chris", o("James iPhone"))
        check("owner: hyphen form", o("Anna-Pixel 8") == "Anna", o("Anna-Pixel 8"))
        check("owner: Spanish", o("iPhone de Juan") == "Juan", o("iPhone de Juan"))
        check("owner: German", o("MacBook Pro von Anna Schmidt") == "Anna Schmidt", o("MacBook Pro von Anna Schmidt"))
        check("owner: ALL CAPS normalized", o("JOHN'S GALAXY S23") == "John", o("JOHN'S GALAXY S23"))
        check("owner: two names", o("Mary Ann's iPad") == "Mary Ann", o("Mary Ann's iPad"))
        check("owner: home network", o("Taylor's WiFi") == "Taylor")
        check("no owner: McDonald's", o("McDonald's Free WiFi") == null, o("McDonald's Free WiFi"))
        check("no owner: Mike's Bar", o("Mike's Bar WiFi") == null, o("Mike's Bar WiFi"))
        check("no owner: Sonos Speaker", o("Sonos Speaker") == null, o("Sonos Speaker"))
        check("no owner: Galaxy Buds", o("Galaxy Buds2") == null && o("Beats Studio Buds") == null, o("Beats Studio Buds"))
        check("no owner: DIRECT-roku", o("DIRECT-roku-123") == null && o("TP-Link_1234") == null)
        check("no owner: Guest's WiFi", o("Guest's WiFi") == null)
        check("no owner: product name", o("Bose QC35 II") == null && o("AirPods Pro") == null)
        check("no owner: bare SSID", o("NETGEAR42") == null && o("xfinitywifi") == null)
        check("no owner: chains", o("Starbucks WiFi") == null && o("Hilton's Guest WiFi") == null, o("Starbucks WiFi"))
        for (bad in listOf("Living-Room-TV", "Kitchen-Echo", "Bedroom-TV", "Work-Laptop", "Old-iPhone", "Test-Phone", "Kids' iPad",
            "The Smith's House", "TV de la sala", "iPad de casa", "House of Pizza", "My iPhone", "Family Room Apple TV", "Garage-Speaker"))
            check("no owner: $bad", o(bad) == null && Names.ownerFromHostname(bad) == null, o(bad) ?: Names.ownerFromHostname(bad))
        check("hostname: Johns-MacBook-Pro", Names.ownerFromHostname("Johns-MacBook-Pro.local") == "Johns", Names.ownerFromHostname("Johns-MacBook-Pro.local"))
        check("hostname: sarahs-iphone", Names.ownerFromHostname("sarahs-iphone") == "Sarahs" && Names.ownerFromHostname("Chris-iPhone") == "Chris" && Names.ownerFromHostname("Lucas-iPad") == "Lucas")
        check("hostname: iPhone-de-Juan", Names.ownerFromHostname("iPhone-de-Juan") == "Juan", Names.ownerFromHostname("iPhone-de-Juan"))
        check("hostname: DESKTOP-ABC123 -> none", Names.ownerFromHostname("DESKTOP-ABC123") == null && Names.ownerFromHostname("Galaxy-S23") == null)
        val d = Device("aa:bb:cc:dd:ee:ff", Kind.BLE, t0).also { it.name = "Sarah's AirPods"; it.owner = "Sarah" }
        check("search owner:sar", DeviceFilter.matches(d, "owner:sar") && DeviceFilter.matches(d, "owner:") && !DeviceFilter.matches(d, "owner:mike"))
        check("search plain owner word", DeviceFilter.matches(d, "sarah"))
    }

    // ---- DeviceStore: linking a device across random-address changes
    run {
        val st = DeviceStore()
        st.sessionStart = t0
        fun seen(mac: String, at: Long): Device = st.upsert(mac, Kind.BLE, at).also {
            it.name = "Sarah's AirPods"; it.vendor = "Apple"; it.label = "Apple AirPods Pro 2"; it.randomAddr = true
            it.addReading(-60, at)
        }
        val a = seen("4a:00:00:00:00:01", t0)
        a.followM = 500.0; a.priority = 1; st.priorities[a.key] = 1; a.trusted = true; st.trustedKeys += a.key
        for (i in 1..20) seen("4a:00:00:00:00:01", t0 + i * 10_000)        // present 200 s
        val b = seen("4a:00:00:00:00:02", t0 + 215_000)                     // address rotates 15 s after the last reading
        var linked = st.linkRotated(t0 + 216_000)
        check("link: not yet (old still counts as active)", linked.isEmpty() && st.devices.size == 2)
        linked = st.linkRotated(t0 + 250_000)                                // old gone > 30 s
        check("link: merged into the new address", linked.size == 1 && linked[0].first === a && linked[0].second === b, linked.size)
        check("link: old address removed", st.devices.size == 1 && st.devices.containsKey(b.key))
        check("link: presence carried over", b.presentSince == t0 && b.firstSeen == t0, "${b.presentSince - t0}")
        check("link: follow + priority carried over", b.followM >= 500.0 && b.priority == 1, "${b.followM} ${b.priority}")
        check("link: alias kept", b.aliases == listOf("4a:00:00:00:00:01"), b.aliases)
        check("link: timeline entry", st.timeline.any { it.tag == "LINK" })
        // Two devices with the same name both active: nothing gets linked
        val st2 = DeviceStore(); st2.sessionStart = t0
        fun seen2(mac: String, at: Long) = st2.upsert(mac, Kind.BLE, at).also { it.name = "Galaxy Buds2"; it.vendor = "Samsung"; it.randomAddr = true; it.addReading(-60, at) }
        seen2("5a:00:00:00:00:01", t0); seen2("5a:00:00:00:00:02", t0); seen2("5a:00:00:00:00:03", t0 + 20_000)
        seen2("5a:00:00:00:00:02", t0 + 60_000)
        check("link: ambiguous names stay separate", st2.linkRotated(t0 + 70_000).isEmpty() && st2.devices.size == 3)
        // Two same-named devices both go quiet, one new address appears: nobody gets merged (which one would it be?)
        val st5 = DeviceStore(); st5.sessionStart = t0
        fun seen5(mac: String, at: Long) = st5.upsert(mac, Kind.BLE, at).also { it.name = "Galaxy Buds2"; it.vendor = "Samsung"; it.randomAddr = true; it.addReading(-60, at) }
        val mine = seen5("6a:00:00:00:00:01", t0).also { it.trusted = true; st5.trustedKeys += it.key }
        seen5("6a:00:00:00:00:02", t0)
        seen5("6a:00:00:00:00:01", t0 + 30_000); seen5("6a:00:00:00:00:02", t0 + 32_000)
        val n5 = seen5("6a:00:00:00:00:03", t0 + 50_000)
        check("link: two quiet twins + one newcomer -> no merge", st5.linkRotated(t0 + 100_000).isEmpty() && st5.devices.size == 3 && !n5.trusted && mine.trusted)
        // A default product name never passes "mine" on to a possible stranger
        val st6 = DeviceStore(); st6.sessionStart = t0
        fun seen6(mac: String, at: Long) = st6.upsert(mac, Kind.BLE, at).also { it.name = "Galaxy Buds2"; it.vendor = "Samsung"; it.randomAddr = true; it.addReading(-60, at) }
        seen6("7a:00:00:00:00:01", t0).also { it.trusted = true; it.alerted = true }
        seen6("7a:00:00:00:00:01", t0 + 30_000)
        val n6 = seen6("7a:00:00:00:00:02", t0 + 50_000)
        check("link: default name links presence but not 'mine'", st6.linkRotated(t0 + 100_000).size == 1 && n6.presentSince == t0 && !n6.trusted && !n6.alerted, "${n6.trusted} ${n6.alerted}")
        check("link: personal name carries 'mine' + watch to the new key", b.trusted && b.key in st.trustedKeys && st.priorities[b.key] == 1 && st.choicesChanged, "${st.trustedKeys} ${st.priorities}")
        // A public (fixed) address is never linked
        val st3 = DeviceStore(); st3.sessionStart = t0
        val p1 = st3.upsert("00:25:df:00:00:01", Kind.BLE, t0).also { it.name = "AXON_CAMERA"; it.randomAddr = false; it.addReading(-60, t0) }
        st3.upsert("00:25:df:00:00:02", Kind.BLE, t0 + 10_000).also { it.name = "AXON_CAMERA"; it.randomAddr = false; it.addReading(-60, t0 + 10_000) }
        check("link: public addresses never linked", DeviceStore.fingerprint(p1) == null && st3.linkRotated(t0 + 60_000).isEmpty())
        // Wi-Fi hotspot with a randomized BSSID
        val st4 = DeviceStore(); st4.sessionStart = t0
        fun hs(mac: String, at: Long, cat: Category = Category.PHONE, nm: String = "Mike's iPhone") = st4.upsert(mac, Kind.WIFI, at).also { it.name = nm; it.category = cat; it.security = "[WPA2-PSK-CCMP][ESS]"; it.randomAddr = true; it.addReading(-50, at) }
        hs("f2:11:22:33:44:55", t0); hs("f2:11:22:33:44:55", t0 + 60_000)
        hs("f2:11:22:33:44:66", t0 + 100_000)
        // mesh / guest network with local BSSIDs passed one after another: separate access points, never chained
        hs("f2:aa:00:00:00:01", t0, Category.NETWORK, "xfinitywifi"); hs("f2:aa:00:00:00:02", t0 + 100_000, Category.NETWORK, "xfinitywifi")
        check("link: hotspot BSSID rotation, mesh APs untouched", st4.linkRotated(t0 + 200_000).size == 1 && st4.devices.size == 3, st4.devices.keys)
    }

    // ---- LanParser: mDNS + SSDP announcements
    run {
        fun lbl(vararg labels: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for (l in labels) { out.write(l.length); out.write(l.toByteArray()) }
            out.write(0)
            return out.toByteArray()
        }
        fun u16(v: Int) = bytes(v shr 8, v and 0xFF)
        fun rr(name: ByteArray, type: Int, data: ByteArray) = name + u16(type) + u16(0x8001) + bytes(0, 0, 0x11, 0x94) + u16(data.size) + data
        val host = lbl("Johns-MacBook-Pro", "local")
        val svcType = lbl("_airplay", "_tcp", "local")
        val inst = lbl("Living Room TV", "_airplay", "_tcp", "local")
        val txtData = run { val s1 = "model=AppleTV6,2".toByteArray(); val s2 = "deviceid=AA:BB".toByteArray(); bytes(s1.size) + s1 + bytes(s2.size) + s2 }
        val pkt = u16(0) + u16(0x8400) + u16(0) + u16(4) + u16(0) + u16(0) +
            rr(host, 1, bytes(192, 168, 1, 23)) +
            rr(svcType, 12, inst) +
            rr(inst, 33, u16(0) + u16(0) + u16(7000) + host) +
            rr(inst, 16, txtData)
        val m = LanParser.parseMdns(pkt)
        check("mdns: parsed", m != null && m.response)
        check("mdns: host A record", m!!.hosts["Johns-MacBook-Pro"] == "192.168.1.23", m.hosts)
        check("mdns: service instance", m.services.size == 1 && m.services[0].instance == "Living Room TV" && m.services[0].type == "airplay", m.services.map { it.instance + "/" + it.type })
        check("mdns: SRV + TXT", m.services[0].port == 7000 && m.services[0].host == "Johns-MacBook-Pro" && m.services[0].txt["model"] == "AppleTV6,2", m.services[0].txt)
        check("mdns: garbage safe", LanParser.parseMdns(bytes(1, 2, 3)) == null && LanParser.parseMdns(ByteArray(40) { 0xC0.toByte() }) == null)
        // compression pointer: second record's name points at offset of the host name
        val pkt2 = u16(0) + u16(0x8400) + u16(0) + u16(2) + u16(0) + u16(0) + rr(host, 1, bytes(10, 0, 0, 5)) + rr(bytes(0xC0, 12), 28, ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 })
        val m2 = LanParser.parseMdns(pkt2)
        check("mdns: compression pointer", m2 != null && m2.hosts["Johns-MacBook-Pro"] == "10.0.0.5", m2?.hosts)
        val dir = LanDirectory()
        dir.onMdns("192.168.1.23", m, t0)
        val e = dir.entries["192.168.1.23"]
        check("lan: entry from mDNS", e != null && e.host == "Johns-MacBook-Pro" && "Living Room TV" in e.names && e.model == "AppleTV6,2", e?.what())
        check("lan: owner from hostname", e!!.owner == "Johns", e.owner)
        check("lan: name match", dir.matching("living room tv", t0) === e && dir.matching("Johns MacBook Pro", t0) === e && dir.matching("Kitchen", t0) == null)
        check("lan: generic / stale / ambiguous names don't match", dir.matching("iPhone", t0) == null && dir.matching("living room tv", t0 + 11 * 60_000) == null)
        val dir2 = LanDirectory()
        dir2.onMdns("192.168.1.23", m, t0)
        dir2.onMdns("192.168.1.99", LanParser.parseMdns(u16(0) + u16(0x8400) + u16(0) + u16(1) + u16(0) + u16(0) + rr(svcType, 12, inst))!!, t0) // same friendly name, another device
        check("lan: two entries with one name -> no match", dir2.entries.size == 2 && dir2.matching("living room tv", t0) == null, dir2.entries.keys)
        check("lan: spoofed off-subnet A record makes no entry", run { val d2 = LanDirectory(); d2.onMdns("192.168.1.5", LanParser.parseMdns(pkt2)!!, t0); d2.entries.size == 1 })
        val sonyish = LanParser.parseSsdp("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nNTS: ssdp:alive\r\nSERVER: Panasonic-UPnP/1.0\r\nUSN: uuid:1\r\nX-RINCON-BOOTSEQ: 123\r\n\r\n")!!
        check("ssdp: Panasonic is not a NAS, bootseq is not a name", LanParser.ssdpLabel(sonyish) != "NAS" && sonyish.extra.isEmpty(), LanParser.ssdpLabel(sonyish))
        val ssdp = LanParser.parseSsdp("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\nNTS: ssdp:alive\r\nSERVER: Linux UPnP/1.0 Sonos/80.1-55014\r\nUSN: uuid:RINCON_1\r\nLOCATION: http://192.168.1.40:1400/xml/device_description.xml\r\n\r\n")
        check("ssdp: parsed", ssdp != null && ssdp.alive && ssdp.nt.contains("MediaRenderer") && LanParser.ssdpLabel(ssdp) == "Sonos speaker", ssdp?.let { LanParser.ssdpLabel(it) })
        check("ssdp: M-SEARCH ignored", LanParser.parseSsdp("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n") == null)
        dir.onSsdp("192.168.1.40", ssdp!!, t0)
        check("lan: entry from SSDP", dir.entries["192.168.1.40"]?.upnpType == "Sonos speaker")
        check("lan: active list", dir.active(t0 + 1000).size == 2 && dir.active(t0 + 11 * 60_000).isEmpty())
    }


    // ---- Security part 1: attack tools, grades, evil twins, following
    run {
        fun w(bssid: String, ssid: String, caps: String = "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]") = Classifier.classifyWifi(bssid, ssid, 2437, caps, null, 5, 20, false, db)
        val pine = w("00:13:37:aa:bb:cc", "Pineapple_BBCC")
        check("threat: Pineapple default (digits match address) + OUI", pine.attackTool && pine.label == "Wi-Fi Pineapple / Hak5 tool" && pine.findings.sumOf { it.points } >= 100, "${pine.label} ${pine.findings}")
        check("threat: Pineapple default digits match without OUI", w("a0:11:22:33:3f:2a", "Pineapple_3F2A").attackTool)
        for (fp in listOf("Pineapple Cafe", "Pineapple WiFi", "PineappleHouse-WiFi", "Pineapple 2024", "Pineapple_Face", "Pineapple House", "Marauders", "Marauders-Guest", "Marauders WiFi", "OMG Cable"))
            check("threat: '$fp' is not a tool", !w("a0:bb:cc:dd:ee:04", fp).attackTool && w("a0:bb:cc:dd:ee:04", fp).findings.none { it.points >= 40 }, w("a0:bb:cc:dd:ee:04", fp).findings)
        check("threat: 'WiFi Pineapple MK7' named", w("a0:bb:cc:dd:ee:05", "WiFi Pineapple MK7").attackTool)
        val pwned = w("aa:bb:cc:dd:ee:01", "pwned", "[ESS]")
        check("threat: deauther 'pwned'", pwned.attackTool && pwned.label == "Wi-Fi deauther (jamming tool)", pwned.label)
        check("threat: Marauder default SSID", w("aa:bb:cc:dd:ee:02", "MarauderAP").attackTool && w("aa:bb:cc:dd:ee:02", "marauder").attackTool)
        check("threat: O.MG", w("aa:bb:cc:dd:ee:03", "O.MG").attackTool)
        val free = w("aa:bb:cc:dd:ee:05", "Free WiFi", "[ESS]")
        check("threat: 'Free WiFi' is only a weak hint", !free.attackTool && free.findings.any { it.points == 10 })
        val flip = Classifier.classifyBle("80:e1:26:00:00:01", false, AdvParser.parse(ad(name("Flipper Kx9ab3"))), db)
        check("threat: Flipper Zero BLE name", flip.attackTool && flip.label == "Flipper Zero", flip.label)
        check("threat: plain 'Flipper' word alone is not", !Classifier.classifyBle("c1:00:00:00:00:01", true, AdvParser.parse(ad(name("Flipper"))), db).attackTool)
        check("threat: Marauder BLE", Classifier.classifyBle("c1:00:00:00:00:02", true, AdvParser.parse(ad(name("Marauder"))), db).attackTool)
        // grades
        check("grade: WPA3 A, +WPS B", WifiSecurity.grade("[WPA2-SAE-CCMP][RSN-SAE-CCMP][ESS]", false).first == 'A' && WifiSecurity.grade("[RSN-SAE-CCMP][ESS]", true).first == 'B')
        check("grade: WPA2 B, +WPS C", WifiSecurity.grade("[WPA2-PSK-CCMP][ESS]", false).first == 'B' && WifiSecurity.grade("[WPA2-PSK-CCMP][ESS][WPS]", true).first == 'C')
        check("grade: WPA2 TKIP-only C", WifiSecurity.grade("[WPA2-PSK-TKIP][ESS]", false).first == 'C')
        check("grade: WPA1 C weak, WEP D, open F", WifiSecurity.grade("[WPA-PSK-TKIP][ESS]", false).first == 'C' && WifiSecurity.isWeak("[WPA-PSK-TKIP][ESS]", false) &&
            WifiSecurity.grade("[WEP][ESS]", false).first == 'D' && WifiSecurity.grade("[ESS]", false).first == 'F' && !WifiSecurity.isWeak("[WPA2-PSK-CCMP][ESS]", false))
        check("grade fact present", w("aa:bb:cc:dd:ee:06", "Home").facts["Security grade"]?.startsWith("B") == true, w("aa:bb:cc:dd:ee:06", "Home").facts["Security grade"])
        // evil twin via memory: learned near home on two days
        val home = 33.75 to -84.39
        val mem = SsidMemory()
        val day = 86_400_000L
        mem.touch("HomeNet", "[WPA2-PSK-CCMP][ESS]", "00:11:22:33:44:55", 5, home.first, home.second, t0)
        check("ssid memory: not learned before 10 min", mem.known.isEmpty())
        mem.touch("HomeNet", "[WPA2-PSK-CCMP][ESS]", "00:11:22:33:44:55", 12, home.first, home.second, t0)
        mem.touch("Guest", "[WPA2-PSK-CCMP][ESS]", "00:11:22:33:44:56", 30, home.first, home.second, t0)
        check("ssid memory: learned, generic skipped", mem.known["HomeNet"]?.bestRank == 3 && mem.known["HomeNet"]?.ouis == setOf("00:11:22") && "Guest" !in mem.known)
        check("evil twin: one day only -> nothing yet", mem.findings("HomeNet", "[ESS]", "a4:77:88:99:aa:bb", home.first, home.second).isEmpty())
        mem.touch("HomeNet", "[WPA2-PSK-CCMP][ESS]", "00:11:22:33:44:55", 12, home.first, home.second, t0 + day)
        val twin = mem.findings("HomeNet", "[ESS]", "a4:77:88:99:aa:bb", home.first, home.second)
        check("evil twin: known WPA2 network now open from another block", twin.any { it.points == 45 } && twin.any { it.points == 15 }, twin)
        check("evil twin: same network far away (10 km) -> nothing", mem.findings("HomeNet", "[ESS]", "a4:77:88:99:aa:bb", home.first + 0.09, home.second).isEmpty())
        check("evil twin: no position -> nothing", mem.findings("HomeNet", "[ESS]", "a4:77:88:99:aa:bb", null, null).isEmpty())
        check("evil twin: same network, same block, same security -> nothing", mem.findings("HomeNet", "[WPA2-PSK-CCMP][ESS]", "00:11:22:ff:ff:ff", home.first, home.second).isEmpty())
        check("evil twin: unknown name -> nothing", mem.findings("Other", "[ESS]", "00:11:22:ff:ff:ff", home.first, home.second).isEmpty())
        mem.touch("Caf\u00e9 \ud83d\ude00\ttab\nline", "[ESS]", "00:aa:bb:cc:dd:ee", 20, home.first, home.second, t0)
        val mem2 = SsidMemory(); mem2.load(mem.dump())
        check("ssid memory: round trip incl. tab/newline/emoji", mem2.known["HomeNet"]?.let { it.bestRank == 3 && it.ouis == setOf("00:11:22") && it.days == 2 } == true && mem2.known.containsKey("Caf\u00e9 \ud83d\ude00\ttab\nline") && mem2.known.size == 2, mem2.known.keys)
        mem2.expire(t0 + 62 * day)
        check("ssid memory: expiry", mem2.known.isEmpty() && mem2.dirty)
        Heuristics.ssidMemory = mem; Heuristics.myLat = home.first; Heuristics.myLon = home.second
        val st = DeviceStore(); st.sessionStart = t0
        val ap = st.upsert("a4:77:88:99:aa:bb", Kind.WIFI, t0).also { it.name = "HomeNet"; it.security = "[ESS]"; it.addReading(-60, t0) }
        Heuristics.score(ap, listOf(ap), t0)
        check("evil twin: scored + tagged + alerts", "evil-twin" in ap.secTags && "weak-wifi" in ap.secTags && ap.level >= Level.MEDIUM && Heuristics.wantsAlert(ap), "${ap.secTags} ${ap.score} ${ap.flags}")
        Heuristics.alertLevel = 0
        check("evil twin: no alert at 'critical only'", !Heuristics.wantsAlert(ap))
        Heuristics.alertLevel = 1
        Heuristics.ssidMemory = null; Heuristics.myLat = null; Heuristics.myLon = null
        // open + secured under one name at the same time: noted, no alert by itself
        val sec = st.upsert("00:11:22:33:44:77", Kind.WIFI, t0).also { it.name = "Venue"; it.security = "[WPA2-PSK-CCMP][ESS]"; it.addReading(-60, t0) }
        val opn = st.upsert("00:11:22:33:44:78", Kind.WIFI, t0).also { it.name = "Venue"; it.security = "[ESS]"; it.addReading(-60, t0) }
        Heuristics.score(opn, listOf(sec, opn), t0)
        check("venue portal: noted, tagged weak only, no alert", opn.flags.any { "guest portal" in it } && "evil-twin" !in opn.secTags && !Heuristics.wantsAlert(opn), opn.flags)
        val wps = st.upsert("00:11:22:33:44:66", Kind.WIFI, t0).also { it.name = "Shop"; it.security = "[WPA2-PSK-CCMP][ESS][WPS]"; it.wps = true; it.addReading(-60, t0) }
        Heuristics.score(wps, listOf(wps), t0)
        check("WPS: noted, not weak", wps.flags.any { "WPS" in it } && "weak-wifi" !in wps.secTags && wps.level == Level.LOW, wps.flags)
        // attack tool device alerts at MEDIUM
        val tool = st.upsert("00:13:37:aa:bb:cc", Kind.WIFI, t0).also { it.name = "Pineapple_BBCC"; it.security = "[ESS]"; it.addReading(-60, t0)
            val c = w(it.id, it.name, it.security); it.findings = c.findings; it.attackTool = c.attackTool; it.category = c.category; it.label = c.label }
        Heuristics.score(tool, listOf(tool), t0)
        check("attack tool: HIGH + tagged + alert", "attack-tool" in tool.secTags && tool.level == Level.HIGH && Heuristics.wantsAlert(tool), "${tool.score} ${tool.secTags}")
        // following: only repeated appearances far apart; riding along is not enough; crowds never
        fun ble(mac: String, cat: Category, label: String = "", f: Double = 0.0, n: Int = 1, spread: Double = 0.0) =
            st.upsert(mac, Kind.BLE, t0).also { it.category = cat; it.label = label; it.addReading(-70, t0); it.followM = f; it.sightings = n; it.sightSpreadM = spread }
        val fol = ble("d1:00:00:00:00:01", Category.PHONE, n = 3, spread = 2500.0)
        Heuristics.score(fol, listOf(fol), t0)
        check("following: 3 sightings 2.5 km apart -> tagged, MEDIUM, alert", "following" in fol.secTags && fol.level >= Level.MEDIUM && Heuristics.wantsAlert(fol), "${fol.score} ${fol.flags}")
        check("following text says km, not \"k m\"", fol.flags.any { "2.5 km apart" in it } && fol.flags.none { "k m" in it }, fol.flags)
        check("fmtDistUnit", fmtDistUnit(350.0) == "350 m" && fmtDistUnit(1500.0) == "1.5 km" && fmtDistUnit(4.2) == "4.2 m")
        val unknown = ble("d1:00:00:00:00:05", Category.NETWORK, "Unknown BLE device", n = 4, spread = 5000.0)
        Heuristics.score(unknown, listOf(unknown), t0)
        check("following: unknown BLE device counts", "following" in unknown.secTags, unknown.flags)
        val pass = ble("d1:00:00:00:00:02", Category.WEARABLE, f = 2100.0)
        Heuristics.score(pass, listOf(pass), t0)
        check("following: passenger at 2.1 km -> noted, no tag, no alert", "following" !in pass.secTags && !Heuristics.wantsAlert(pass) && pass.flags.any { "moved with you" in it }, "${pass.score} ${pass.flags}")
        val commuters = (1..6).map { ble("d2:00:00:00:00:0$it", Category.PHONE, f = 3000.0, n = 3, spread = 2000.0) }
        for (c in commuters) Heuristics.score(c, commuters, t0)
        check("following: 6 commuters on a train -> no tags", commuters.none { "following" in it.secTags } && commuters.none { Heuristics.wantsAlert(it) }, commuters[0].flags)
        val beacon = ble("d1:00:00:00:00:03", Category.NETWORK, "Eddystone beacon", f = 2500.0, n = 3, spread = 2500.0)
        Heuristics.score(beacon, listOf(beacon), t0)
        check("following: beacons/APs never count", "following" !in beacon.secTags)
        val once = ble("d1:00:00:00:00:04", Category.PHONE, n = 2, spread = 2500.0)
        Heuristics.score(once, listOf(once), t0)
        check("following: two sightings is not enough", "following" !in once.secTags && !Heuristics.wantsAlert(once), once.flags)
        // search + mode
        val v = Snapshot.view(tool, Env.OPEN, t0, null, null)
        check("sec: search + SECURITY chip", DeviceFilter.matches(tool, "sec:attack") && DeviceFilter.matches(tool, "sec:") && !DeviceFilter.matches(tool, "sec:follow") &&
            DeviceFilter.matchesView(v, "sec:attack") && !DeviceFilter.matchesView(v, "sec:follow") && DeviceFilter.matchesView(v, "cat:net pineapple") &&
            Mode.SECURITY.matches(v) && !Mode.SECURITY.matches(Snapshot.view(wps, Env.OPEN, t0, null, null)))
    }


    // ---- Security part 2: places baseline, disruption watch, BLE spam, report
    run {
        val day = 86_400_000L
        val pl = Places()
        val home0 = pl.add("  Home  ", 33.75, -84.39)
        check("places: at/not at, name normalized", pl.at(33.7505, -84.39) === home0 && pl.at(33.76, -84.39) == null && pl.at(null, null) == null && home0.name == "Home")
        val home = pl.add("Home", 33.75, -84.39); check("places: same name replaces", pl.places.size == 1 && pl.places[0] === home)
        fun dev(mac: String, cat: Category = Category.CAMERA_IOT, name: String = "", random: Boolean = false, since: Long = t0 - 10 * 60_000, rssi: Int = -60) =
            Device(mac, Kind.BLE, since).also { it.name = name; it.category = cat; it.randomAddr = random; it.vendor = "Acme"; it.presentSince = since; it.addReading(rssi, t0) }
        val cam = dev("00:aa:bb:cc:dd:01")
        val visitor = dev("4a:aa:bb:cc:dd:02", Category.PHONE, random = true)
        val named = dev("4a:aa:bb:cc:dd:03", Category.WEARABLE, "Sarah's AirPods", random = true)
        val phone = dev("00:aa:bb:cc:dd:04", Category.PHONE)
        check("places: only fixed gear with a fixed address is eligible", Places.identity(cam) == "BLE:00:aa:bb:cc:dd:01" && Places.identity(visitor) == null && Places.identity(named) == null && Places.identity(phone) == null)
        // Three local days, 30+ minutes each: learning, nothing is "new"
        var t = t0
        var alerts = 0
        for (d in 0 until 3) for (m in 0 until 31) { t = t0 + d * day + m * 60_000; alerts += pl.learn(pl.at(33.75, -84.39), listOf(cam, named, phone), t).size }
        check("places: learning days give no alerts", alerts == 0 && home.learned && home.known.size == 1 && home.days == 3, "${home.days} ${home.known.keys}")
        // Short visit (10 min) on day 4 does not count as a day but still learns
        // Day 4: a new fixed device appears, present 5 min, strong -> new here; weak neighbour gear and phones never
        t = t0 + 3 * day + 61_000
        val hub = dev("00:aa:bb:cc:dd:09", since = t - 5 * 60_000)
        val weak = dev("00:aa:bb:cc:dd:0a", since = t - 5 * 60_000, rssi = -88)
        val fresh = pl.learn(pl.at(33.75, -84.39), listOf(cam, named, visitor, hub, weak, phone), t)
        check("places: new fixed device flagged", fresh == listOf(hub) && hub.placeNote.startsWith("Never seen at Home") && hub.newAtPlace && cam.placeNote.isEmpty() && visitor.placeNote.isEmpty() && weak.placeNote.isEmpty(), "${fresh.size} ${hub.placeNote} ${weak.placeNote}")
        val again = pl.learn(pl.at(33.75, -84.39), listOf(hub), t + 61_000)
        check("places: not flagged twice, note kept for the session", again.isEmpty() && hub.placeNote.startsWith("Never seen"))
        pl.learn(null, listOf(hub, cam), t + 122_000)
        check("places: leaving keeps the 'new here' note", hub.placeNote.startsWith("Never seen"))
        val passer = dev("00:aa:bb:cc:dd:10", since = t + 180_000 - 30_000)
        check("places: passer-by (present < 3 min) ignored", pl.learn(pl.at(33.75, -84.39), listOf(passer), t + 182_000).isEmpty() && passer.placeNote.isEmpty())
        Heuristics.score(hub, listOf(hub), t)
        check("places: new-here scores MEDIUM + alerts", "new-here" in hub.secTags && hub.level >= Level.MEDIUM && Heuristics.wantsAlert(hub), "${hub.score}")
        val pl2 = Places(); pl2.load(pl.dump())
        check("places: round trip", pl2.places.size == 1 && pl2.places[0].name == "Home" && pl2.places[0].known.keys == home.known.keys && pl2.places[0].days == home.days && pl2.places[0].minutesToday == home.minutesToday)
        pl.remove("home")
        check("places: remove (case-insensitive)", pl.places.isEmpty() && pl.dirty)

        val dw = DisruptionWatch()
        var n: String? = null
        for (i in 0 until 6) n = dw.onScan(8, 3, 0.3, t0 + i * 8_000)
        check("disruption: steady networks -> nothing", n == null)
        n = dw.onScan(0, 3, 0.3, t0 + 48_000); val n2 = dw.onScan(0, 3, 0.3, t0 + 70_000)
        check("disruption: two fresh empty scans while still -> event once", n == null && n2 != null && n2.contains("8 nearby"), n2)
        check("disruption: no repeat within 10 min", dw.onScan(0, 3, 0.3, t0 + 100_000) == null)
        val dw2 = DisruptionWatch()
        for (i in 0 until 6) dw2.onScan(8, 3, 1.4, t0 + i * 8_000)
        check("disruption: walking (lift, basement) -> nothing", dw2.onScan(0, 3, 1.4, t0 + 48_000) == null && dw2.onScan(0, 3, 1.4, t0 + 70_000) == null)
        val dw3 = DisruptionWatch()
        for (i in 0 until 6) dw3.onScan(8, 3, 0.0, t0 + i * 8_000)
        check("disruption: Bluetooth also silent (shielding) -> nothing", dw3.onScan(0, 0, 0.0, t0 + 48_000) == null && dw3.onScan(0, 0, 0.0, t0 + 70_000) == null)
        val dw4 = DisruptionWatch()
        for (i in 0 until 6) dw4.onScan(8, 3, 0.0, t0 + i * 8_000)
        dw4.onScan(0, 3, 0.0, t0 + 48_000); dw4.skipped()
        check("disruption: a stale read in between resets the evidence", dw4.onScan(0, 3, 0.0, t0 + 70_000) == null)
        val dw5 = DisruptionWatch()
        check("disruption: 3 connection drops in 10 min -> event", dw5.onConnectionLost(0.0, t0) == null && dw5.onConnectionLost(0.0, t0 + 60_000) == null && dw5.onConnectionLost(0.0, t0 + 120_000) != null)
        check("disruption: drops while moving ignored", DisruptionWatch().let { it.onConnectionLost(1.0, t0) == null && it.onConnectionLost(1.0, t0) == null && it.onConnectionLost(1.0, t0) == null })

        val bs = BleSpamWatch()
        val popup = AdvParser.parse(ad(mfr(0x004C, 0x07, 0x19, 0x01, 0x0E, 0x20, 0x55)))
        check("spam: pop-up kinds", bs.isPopupKind(popup) && bs.isPopupKind(AdvParser.parse(ad(mfr(0x004C, 0x0F, 0x05, 0x01)))) && bs.isPopupKind(AdvParser.parse(ad(svcData(0xFE2C, 0x2C, 0xFE, 0x13)))) &&
            !bs.isPopupKind(AdvParser.parse(ad(mfr(0x004C, 0x10, 0x05, 0x01)))) && !bs.isPopupKind(AdvParser.parse(ad(svcData(0xFE2C, 0, 1, 2, 3, 4, 5, 6)))))
        val st = DeviceStore(); st.sessionStart = t0
        // 20 one-shot packets within 4 s: each device heard exactly once
        for (i in 0 until 20) { val d = st.upsert("5e:00:00:00:00:${"%02x".format(i)}", Kind.BLE, t0 + i * 200); d.addReading(-70 + i % 5, t0 + i * 200); bs.onNewPopupDevice(d.key, -70 + i % 5, t0 + i * 200) }
        check("spam: nothing before the one-shot check (5 s)", bs.check(t0 + 4_000) { st.devices[it] } == null && !bs.flooding)
        val ev = bs.check(t0 + 9_000) { st.devices[it] }
        check("spam: one-shot flood -> event with strongest signal", ev != null && ev.contains("20 one-shot") && ev.contains("-66 dBm") && bs.flooding, ev)
        check("spam: no second event for 5 min", bs.check(t0 + 12_000) { st.devices[it] } == null)
        // 20 real earbuds that keep advertising: not a flood
        val bs2 = BleSpamWatch(); val st2 = DeviceStore(); st2.sessionStart = t0
        for (i in 0 until 20) { val d = st2.upsert("5f:00:00:00:00:${"%02x".format(i)}", Kind.BLE, t0 + i * 200); d.addReading(-60, t0 + i * 200); bs2.onNewPopupDevice(d.key, -60, t0 + i * 200) }
        for (i in 0 until 20) for (k in 1..5) st2.devices["BLE:5f:00:00:00:00:${"%02x".format(i)}"]!!.addReading(-60, t0 + i * 200 + k * 1500)
        check("spam: 20 real earbuds (still advertising) are not a flood", bs2.check(t0 + 9_000) { st2.devices[it] } == null && !bs2.flooding)
        val bs3 = BleSpamWatch(); bs3.quietUntil = t0 + 30_000
        for (i in 0 until 20) bs3.onNewPopupDevice("x$i", -60, t0 + i * 200)
        check("spam: first 30 s after a scan start ignored", bs3.check(t0 + 9_000) { null } == null)

        val log = SecurityLog()
        log.add("BLE-SPAM", "x", t0, 1.0, 2.0); log.add("WIFI-DISRUPT", "y", t0 + 1, null, null)
        val view = Snapshot.view(hub, Env.INDOOR, t, null, null)
        val rep = Exporter.securityReport(t, log.snapshot, listOf(view), t0, 33.75 to -84.39)
        check("report: events + flagged device", rep.contains("== Events (2) ==") && rep.contains("WIFI-DISRUPT  y") && rep.contains("@ 1.00000, 2.00000") && rep.contains("new-here") && rep.contains("Never seen at Home"), rep.take(400))
    }

    println(if (failures == 0) "ALL LOGIC TESTS PASSED" else "$failures FAILURE(S)")
    if (failures > 0) throw AssertionError("$failures test failure(s)")
}
