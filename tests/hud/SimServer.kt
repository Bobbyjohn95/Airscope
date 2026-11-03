import com.k.airscope.*
import java.io.File

/** Real HudServer + classifier + Locator + map pipeline with a simulated walk, for browser testing. */
@Volatile var json = "{\"devices\":[],\"timeline\":[]}"
@Volatile var mapJson = "{\"lat\":null}"

fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
fun ad(vararg parts: Pair<Int, ByteArray>): ByteArray {
    val o = java.io.ByteArrayOutputStream()
    for ((t, p) in parts) { o.write(p.size + 1); o.write(t); o.write(p) }
    return o.toByteArray()
}

fun main(args: Array<String>) {
    val page = File(args[0]).readBytes()
    val server = HudServer(8787, "testkey123", page, { json }, { mapJson })
    server.start()
    println("HUD running on http://127.0.0.1:8787/?k=testkey123")
    val lat0 = 33.9212; val lon0 = -84.8410
    val P = LocalProj(lat0, lon0)
    // Synthetic Overpass response: street grid + buildings + a pond
    val els = ArrayList<String>()
    fun way(tags: String, pts: List<Pair<Double, Double>>) =
        els.add("{\"type\":\"way\",\"tags\":{$tags},\"geometry\":[" + pts.joinToString(",") { "{\"lat\":${P.lat(it.second)},\"lon\":${P.lon(it.first)}}" } + "]}")
    for (i in -3..3) {
        way(if (i == 0) "\"highway\":\"primary\",\"name\":\"Main St\"" else "\"highway\":\"residential\",\"name\":\"Oak Rd $i\"", listOf(-450.0 to i * 120.0, 450.0 to i * 120.0))
        way(if (i == 0) "\"highway\":\"secondary\",\"name\":\"Bay Ave\"" else "\"highway\":\"residential\"", listOf(i * 150.0 to -450.0, i * 150.0 to 450.0))
    }
    for (gx in -3..2) for (gy in -3..2) for (k in 0..3) {
        val x = gx * 150 + 22.0 + (k % 2) * 60; val y = gy * 120 + 18.0 + (k / 2) * 50
        way("\"building\":\"yes\",\"building:levels\":\"${1 + (gx + gy + k + 9) % 4}\"", listOf(x to y, x + 34 to y, x + 34 to y + 26, x to y + 26, x to y))
    }
    way("\"natural\":\"water\"", listOf(200.0 to -300.0, 330.0 to -300.0, 330.0 to -200.0, 200.0 to -200.0))
    val md = MapData.parse("{\"elements\":[" + els.joinToString(",") + "]}", lat0, lon0, System.currentTimeMillis())
    mapJson = Exporter.mapJson(md)

    val db = VendorDb().apply { loadOui(sequenceOf("003044\tCradlepoint, Inc", "a42bb0\tNETGEAR")) }
    val store = DeviceStore()
    val rnd = java.util.Random(3)
    val tag = IntArray(27).also { it[0] = 0x12; it[1] = 0x19 }
    data class Sim(val id: String, val kind: Kind, val ssid: String, val raw: ByteArray?, val x: Double, val y: Double, val ref: Double)
    val sims = listOf(
        Sim("a4:2b:b0:11:22:33", Kind.WIFI, "HomeNet-5G", null, 32.0, 40.0, -28.0),
        Sim("dc:4f:22:12:34:56", Kind.WIFI, "ESP_C1A2B3", null, -70.0, 25.0, -28.0),
        Sim("00:30:44:aa:bb:cc", Kind.WIFI, "IBR900-1b2", null, 140.0, -60.0, -28.0),
        Sim("F1:22:33:44:55:66", Kind.BLE, "", ad(0xFF to (b(0x4C, 0) + ByteArray(27) { tag[it].toByte() })), 5.0, -8.0, -62.0),
        Sim("00:25:DF:68:5D:1F", Kind.BLE, "", ad(0x09 to "AXON_CAMERA".toByteArray()), 60.0, 70.0, -60.0),
        Sim("C1:22:33:44:55:66", Kind.BLE, "", ad(0x09 to "S1a87a5a75f3df858C".toByteArray()), -35.0, -55.0, -55.0),
    )
    val start = System.currentTimeMillis()
    var i = 0
    while (true) {
        val now = System.currentTimeMillis()
        // Walk a 30 m loop around the origin, one lap every ~60 s
        val t = (now - start) / 1000.0
        val mx = 30 * kotlin.math.sin(t / 9.5); val my = 30 * kotlin.math.cos(t / 9.5)
        val myLat = P.lat(my); val myLon = P.lon(mx)
        for ((n, s) in sims.withIndex()) {
            val d = store.upsert(s.id, s.kind, now)
            if (d.readings == 0) {
                if (s.kind == Kind.WIFI) {
                    d.name = s.ssid; d.security = "[RSN-PSK-CCMP][ESS]"; d.frequency = 2437
                    val c = Classifier.classifyWifi(s.id, s.ssid, 2437, d.security, null, 6, 80, false, db)
                    d.category = c.category; d.label = c.label; d.vendor = c.vendor; d.facts = c.facts; d.findings = c.findings
                } else {
                    val adv = AdvParser.parse(s.raw)
                    adv.name?.let { d.name = it }
                    val c = Classifier.classifyBle(s.id, n != 4, adv, db)
                    d.category = c.category; d.label = c.label; d.vendor = c.vendor; d.facts = c.facts; d.findings = c.findings; d.bleType = c.bleType
                }
            }
            val dist = kotlin.math.hypot(s.x - mx, s.y - my).coerceAtLeast(0.5)
            val rssi = (s.ref - 26 * kotlin.math.log10(dist) + rnd.nextGaussian() * 3).toInt()
            d.addReading(rssi, now)
            Locator.add(d.track, myLat, myLon, rssi.toDouble(), now)
            if (d.track.dirty) { val (p0, sg) = Distance.locatorPrior(d); Locator.estimate(d.track, p0, sg, Env.INDOOR, now) }
        }
        store.rescore(now)
        val views = store.active(now).map { Snapshot.view(it, Env.INDOOR, now, myLat, myLon) }.toMutableList()
        val alpr = Alpr(101, P.lat(121.0), P.lon(150.0), "ALPR", "Flock Safety", "Paulding County Sheriff", "270")
        views += AlprLogic.hits(listOf(alpr), myLat, myLon).map { Snapshot.alprView(it, now) }
        views.sortWith(compareByDescending<DeviceView> { it.score }.thenBy { it.dist })
        val vx = 30 / 9.5 * kotlin.math.cos(t / 9.5); val vy = -30 / 9.5 * kotlin.math.sin(t / 9.5)
        val crs = (Math.toDegrees(kotlin.math.atan2(vx, vy)) + 360) % 360
        Exporter.heading = { (crs + 15) % 360 to 12.0 }
        json = Exporter.snapshotJson(Snapshot(now, true, null, views, store.timeline.toList(), Env.INDOOR, "1 mapped", "test map", i.toLong(),
            myLat, myLon, 2.4, crs, 1, vx, vy, System.nanoTime(), "27/41 sats · L1+L5"))
        if (i % 10 == 0) {
            val errs = sims.mapNotNull { s -> store.devices[s.kind.name + ":" + s.id]?.track?.takeIf { it.located }?.let { tr -> "%s:%.0fm".format(s.id.takeLast(5), kotlin.math.hypot(P.x(tr.estLon) - s.x, P.y(tr.estLat) - s.y)) } }
            println("t=${t.toInt()}s located=${errs.size}/${sims.size} trueErr=$errs")
        }
        i++
        Thread.sleep(500)
    }
}
