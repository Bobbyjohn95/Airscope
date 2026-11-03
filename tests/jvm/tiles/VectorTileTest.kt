package airscope.tests.tiles

import com.k.airscope.*
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.*

var failures = 0
fun check(name: String, cond: Boolean, info: Any? = null) {
    println((if (cond) "PASS " else "FAIL ") + name + (if (!cond && info != null) "  -> $info" else ""))
    if (!cond) failures++
}

// ---------------------------------------------------------------- tiny MVT encoder
class PbW {
    val o = ByteArrayOutputStream()
    fun varint(v0: Long) { var v = v0; while (v and 0x7fL.inv() != 0L) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }; o.write(v.toInt()) }
    fun tag(f: Int, w: Int) = varint(((f shl 3) or w).toLong())
    fun uint(f: Int, v: Long) { tag(f, 0); varint(v) }
    fun bytes(f: Int, b: ByteArray) { tag(f, 2); varint(b.size.toLong()); o.write(b) }
    fun str(f: Int, s: String) = bytes(f, s.toByteArray())
    fun fixed32(f: Int, bits: Int) { tag(f, 5); for (i in 0 until 4) o.write((bits ushr (8 * i)) and 0xff) }
    fun fixed64(f: Int, bits: Long) { tag(f, 1); for (i in 0 until 8) o.write(((bits ushr (8 * i)) and 0xff).toInt()) }
    fun packed(f: Int, v: IntArray) { val w = PbW(); v.forEach { w.varint(it.toLong() and 0xffffffffL) }; bytes(f, w.done()) }
    fun done(): ByteArray = o.toByteArray()
}

sealed class V {
    data class S(val v: String) : V()
    data class F(val v: Float) : V()
    data class D(val v: Double) : V()
    data class I(val v: Long) : V()
    data class U(val v: Long) : V()
    data class Z(val v: Long) : V()
    data class B(val v: Boolean) : V()

    fun encode(): ByteArray = PbW().also { w ->
        when (this) {
            is S -> w.str(1, v)
            is F -> w.fixed32(2, java.lang.Float.floatToIntBits(v))
            is D -> w.fixed64(3, java.lang.Double.doubleToLongBits(v))
            is I -> w.uint(4, v)
            is U -> w.uint(5, v)
            is Z -> w.uint(6, (v shl 1) xor (v shr 63))
            is B -> w.uint(7, if (v) 1 else 0)
        }
    }.done()
}

fun s(v: String) = V.S(v)

class LayerB(val name: String, val extent: Int = 4096) {
    val keys = LinkedHashMap<String, Int>()
    val vals = LinkedHashMap<V, Int>()
    val feats = ArrayList<ByteArray>()
    fun feature(type: Int, geom: IntArray, vararg tags: Pair<String, V>) {
        val w = PbW()
        w.uint(1, feats.size + 1L)
        val t = IntArray(tags.size * 2)
        tags.forEachIndexed { i, (k, v) -> t[2 * i] = keys.getOrPut(k) { keys.size }; t[2 * i + 1] = vals.getOrPut(v) { vals.size } }
        if (t.isNotEmpty()) w.packed(2, t)
        w.uint(3, type.toLong())
        w.packed(4, geom)
        feats += w.done()
    }
    /** Field-number order like real encoders: name, features, keys, values, extent, version (features BEFORE keys/values). */
    fun encode(): ByteArray {
        val w = PbW()
        w.str(1, name)
        feats.forEach { w.bytes(2, it) }
        keys.keys.forEach { w.str(3, it) }
        vals.keys.forEach { w.bytes(4, it.encode()) }
        w.uint(5, extent.toLong())
        w.uint(15, 2)
        return w.done()
    }
}

fun tile(vararg layers: LayerB): ByteArray { val w = PbW(); layers.forEach { w.bytes(3, it.encode()) }; return w.done() }

data class P(val x: Int, val y: Int)
fun cmd(id: Int, count: Int) = (id and 7) or (count shl 3)
fun zz(n: Int) = (n shl 1) xor (n shr 31)

class GeomB {
    val out = ArrayList<Int>()
    var cx = 0; var cy = 0
    fun pt(p: P) { out += zz(p.x - cx); out += zz(p.y - cy); cx = p.x; cy = p.y }
    fun line(ps: List<P>) { out += cmd(1, 1); pt(ps[0]); out += cmd(2, ps.size - 1); ps.drop(1).forEach { pt(it) } }
    fun ring(ps: List<P>) { line(ps); out += cmd(7, 1) }
    fun points(ps: List<P>) { out += cmd(1, ps.size); ps.forEach { pt(it) } }
    fun done() = out.toIntArray()
}
fun lines(vararg ls: List<P>) = GeomB().also { g -> ls.forEach { g.line(it) } }.done()
fun polys(vararg rs: List<P>) = GeomB().also { g -> rs.forEach { g.ring(it) } }.done()
fun points(vararg ps: P) = GeomB().also { it.points(ps.toList()) }.done()
/** Clockwise on screen (y down) = exterior in MVT 2.1. */
fun rect(x0: Int, y0: Int, x1: Int, y1: Int) = listOf(P(x0, y0), P(x1, y0), P(x1, y1), P(x0, y1))
fun hole(x0: Int, y0: Int, x1: Int, y1: Int) = rect(x0, y0, x1, y1).reversed()

// ---------------------------------------------------------------- independent Web Mercator math
const val Z = 14
val N = 16384.0
fun fx(lon: Double) = (lon + 180.0) / 360.0 * N
fun fy(lat: Double) = (1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * N
fun lonOf(x: Double) = x / N * 360.0 - 180.0
fun latOf(y: Double) = Math.toDegrees(2 * atan(exp(PI * (1 - 2 * y / N))) - PI / 2) // gudermannian form
val K = PI / 180 * 6_371_000.0

fun main() {
    val LAT = 33.92; val LON = -84.84
    val TX = floor(fx(LON)).toInt(); val TY = floor(fy(LAT)).toInt()
    val id = Tiles.TileId(Z, TX, TY)
    val lat0 = latOf(TY + 0.5); val lon0 = lonOf(TX + 0.5)
    fun ex(lon: Double) = (lon - lon0) * K * cos(Math.toRadians(lat0))
    fun ey(lat: Double) = (lat - lat0) * K
    fun pix(lat: Double, lon: Double, ext: Int = 4096) = P(((fx(lon) - TX) * ext).roundToInt(), ((fy(lat) - TY) * ext).roundToInt())

    // ---- tile math
    var rt = 0.0
    for (lo in listOf(-179.9, -84.84, 0.0, 13.4, 151.2)) rt = max(rt, abs(Tiles.lon(Tiles.x(lo, Z), Z) - lo))
    check("lon -> x -> lon round trip", rt < 1e-9, rt)
    rt = 0.0
    for (la in listOf(-80.0, -33.9, 0.0, 33.92, 51.5, 84.9)) rt = max(rt, abs(Tiles.lat(Tiles.y(la, Z), Z) - la))
    check("lat -> y -> lat round trip", rt < 1e-9, rt)
    check("x/y match independent formula", abs(Tiles.x(LON, Z) - fx(LON)) < 1e-9 && abs(Tiles.y(LAT, Z) - fy(LAT)) < 1e-9)
    check("TileId key", id.key == "14/$TX/$TY", id.key)
    check("TileId center", abs(id.centerLat - lat0) < 1e-9 && abs(id.centerLon - lon0) < 1e-9, "${id.centerLat},${id.centerLon}")
    val wInd = 360.0 / N * K * cos(Math.toRadians(lat0))
    check("TileId width ~2030 m", abs(id.widthM - wInd) < 0.5 && id.widthM in 1900.0..2100.0, id.widthM)
    val ar = Tiles.around(LAT, LON, 1500.0)
    check("around: containing tile first", ar.firstOrNull() == id, ar.firstOrNull())
    check("around: 4..9 tiles", ar.size in 4..9, ar.size)
    check("around: no duplicates", ar.toSet().size == ar.size)
    check("around: all within 1 tile", ar.all { abs(it.x - TX) <= 1 && abs(it.y - TY) <= 1 }, ar)
    val ar0 = Tiles.around(LAT, LON, 0.0)
    check("around r=0 -> just the tile", ar0 == listOf(id), ar0)
    for ((course, dist) in listOf(90.0 to 5000.0, 45.0 to 6000.0, 200.0 to 3000.0, 0.0 to 0.0)) {
        val al = Tiles.along(LAT, LON, course, dist)
        val elat = LAT + dist * cos(Math.toRadians(course)) / K
        val elon = LON + dist * sin(Math.toRadians(course)) / (K * cos(Math.toRadians(LAT)))
        val endTile = Tiles.TileId(Z, floor(fx(elon)).toInt(), floor(fy(elat)).toInt())
        val adjacent = al.zipWithNext().all { (a, b) -> abs(a.x - b.x) + abs(a.y - b.y) == 1 }
        check("along $course deg ${dist.toInt()} m: starts at tile, ends at end tile, contiguous, unique",
            al.first() == id && al.last() == endTile && adjacent && al.toSet().size == al.size, al)
    }
    val al90 = Tiles.along(LAT, LON, 90.0, 5000.0)
    check("along east 5 km: 3..4 tiles, same row", al90.size in 3..4 && al90.all { it.y == TY }, al90)

    // ---- synthetic tile
    // Building A: render_height 18 as double, housenumber 12, "Main Street" ~40 m south
    val aLat = lat0 + 0.0010; val aLon = lon0 - 0.0015
    val dLa = 0.00012; val dLo = 0.00015
    val aCorners = listOf(aLat + dLa to aLon - dLo, aLat + dLa to aLon + dLo, aLat - dLa to aLon + dLo, aLat - dLa to aLon - dLo) // NW NE SE SW (clockwise on screen)
    val aRing = aCorners.map { (la, lo) -> pix(la, lo) }
    // Building B: render_height 18 as int64, housenumber 34, no named street within 80 m
    val bLat = lat0 - 0.0040; val bLon = lon0 + 0.0040
    val bRing = listOf(bLat + dLa to bLon - dLo, bLat + dLa to bLon + dLo, bLat - dLa to bLon + dLo, bLat - dLa to bLon - dLo).map { (la, lo) -> pix(la, lo) }
    val mainSt = listOf(pix(aLat - 0.00036, aLon - 0.002), pix(aLat - 0.00036, aLon + 0.002))
    val bld = LayerB("building")
    bld.feature(3, polys(aRing), "render_height" to V.D(18.0), "colour" to s("#ccc"))
    bld.feature(3, polys(bRing), "render_height" to V.I(18))
    bld.feature(3, polys(rect(3000, 3000, 3040, 3030)), "render_height" to V.F(30f))           // float
    bld.feature(3, polys(rect(3100, 3000, 3140, 3030)), "render_height" to V.U(500))           // uint64, clamp 120
    bld.feature(3, polys(rect(3200, 3000, 3240, 3030)), "render_height" to V.Z(-5))            // sint64, clamp 2.5
    bld.feature(3, polys(rect(3300, 3000, 3340, 3030)))                                       // none -> 6
    bld.feature(3, polys(rect(-50, 100, -10, 140)), "render_height" to V.I(9))                 // center in buffer (x=-30): drop
    bld.feature(3, polys(rect(4080, 200, 4100, 240)), "render_height" to V.I(9))               // center x=4090: keep
    bld.feature(3, polys(rect(4090, 300, 4110, 340)), "render_height" to V.I(9))               // center x=4100: drop
    bld.feature(3, polys(rect(1000, 4070, 1040, 4130)), "render_height" to V.I(9))             // center y=4100: drop
    bld.feature(3, polys(rect(3400, 3000, 3500, 3100), hole(3420, 3020, 3480, 3080), rect(3600, 3000, 3640, 3040)), "render_height" to V.I(7)) // multipolygon w/ hole -> 2
    bld.feature(3, polys(listOf(P(3700, 3000), P(3740, 3000))))                                 // < 3 points: drop
    bld.feature(2, lines(listOf(P(3800, 3000), P(3840, 3000), P(3840, 3040))))                 // wrong type: drop

    val tr = LayerB("transportation")
    tr.feature(2, lines(listOf(P(100, 2000), P(2000, 2050), P(4000, 2100))), "class" to s("primary"), "brunnel" to s("tunnel"))
    tr.feature(2, lines(listOf(P(100, 1000), P(900, 1000))), "class" to s("minor"))
    tr.feature(2, lines(listOf(P(100, 1100), P(900, 1100))), "class" to s("service"))
    tr.feature(2, lines(listOf(P(100, 1200), P(900, 1200))), "class" to s("path"))
    tr.feature(2, lines(listOf(P(100, 1300), P(900, 1300))), "class" to s("track"))
    tr.feature(2, lines(listOf(P(100, 1400), P(900, 1400))), "class" to s("secondary"))
    tr.feature(2, lines(listOf(P(100, 1500), P(900, 1500)), listOf(P(100, 1600), P(900, 1600), P(950, 1700))), "class" to s("minor")) // multilinestring -> 2
    for (c in listOf("rail", "transit", "ferry", "aerialway", "busway", "bus_guideway", "raceway", "pier", "minor_construction", "weird"))
        tr.feature(2, lines(listOf(P(100, 1800), P(900, 1800))), "class" to s(c))
    tr.feature(2, lines(listOf(P(100, 1900), P(900, 1900))))                                    // no class
    tr.feature(3, polys(rect(200, 200, 300, 300)), "class" to s("path"))                        // polygon in transportation: skip
    tr.feature(2, lines(listOf(P(100, 1950))), "class" to s("minor"))                           // 1-point line: drop

    val tn = LayerB("transportation_name")
    tn.feature(2, lines(mainSt), "class" to s("minor"), "name" to s("Main Street"), "name:en" to s("Main St EN"))
    tn.feature(2, lines(listOf(P(100, 3500), P(900, 3500))), "class" to s("primary"), "ref" to s("GA 6"))
    tn.feature(2, lines(listOf(P(100, 3600), P(900, 3600))), "class" to s("tertiary"), "name:en" to s("English Rd"))
    tn.feature(2, lines(listOf(pix(aLat + 0.00063, aLon - 0.002), pix(aLat + 0.00063, aLon + 0.002))), "class" to s("minor"), "name" to s("Far Ave")) // ~70 m from A: Main St nearer
    tn.feature(2, lines(listOf(pix(bLat - 0.00081, bLon - 0.002), pix(bLat - 0.00081, bLon + 0.002))), "class" to s("minor"), "name" to s("Ninety Rd")) // ~90 m from B: too far
    tn.feature(2, lines(listOf(P(100, 3700), P(900, 3700))), "class" to s("minor"))                       // no name: skip
    tn.feature(2, lines(listOf(P(100, 3800), P(900, 3800))), "class" to s("rail"), "name" to s("Railroad")) // rail: skip

    val water = LayerB("water")
    water.feature(3, polys(rect(2000, 2500, 2600, 3100), hole(2200, 2700, 2400, 2900)), "class" to s("lake"))
    val park = LayerB("park", 512) // different extent
    val parkRing = rect(10, 400, 60, 450)
    park.feature(3, polys(parkRing), "class" to s("park"))
    park.feature(1, points(P(30, 420)), "class" to s("park"), "name" to s("Label point")) // point: skip
    val lc = LayerB("landcover")
    lc.feature(3, polys(rect(500, 500, 600, 600)), "class" to s("wood"))
    lc.feature(3, polys(rect(600, 500, 700, 600)), "class" to s("farmland"))
    val lu = LayerB("landuse")
    lu.feature(3, polys(rect(700, 500, 800, 600)), "class" to s("cemetery"))
    lu.feature(3, polys(rect(800, 500, 900, 600)), "class" to s("residential"))
    val poi = LayerB("poi")
    poi.feature(1, points(P(10, 10), P(20, 20)), "class" to s("cafe"), "name" to s("Joe's"))
    val hn = LayerB("housenumber")
    val aMid = pix(aLat, aLon); val bMid = pix(bLat, bLon)
    hn.feature(1, points(aMid), "housenumber" to s("12"))
    hn.feature(1, points(bMid), "housenumber" to s("34"))
    hn.feature(1, points(P(10, 3990)), "housenumber" to s("99"))      // not in any building
    hn.feature(1, points(aMid), "housenumber" to s("14"))             // second number on A: first wins

    val raw = tile(tr, tn, water, park, lc, lu, poi, bld, hn)
    val now = 1_700_000_000_000L
    val m = VectorTile.decode(raw, id, now)
    println("  synthetic tile ${raw.size} bytes: roads=${m.roads.size} buildings=${m.buildings.size} areas=${m.areas.size}")

    check("MapData origin = tile center", abs(m.lat0 - lat0) < 1e-9 && abs(m.lon0 - lon0) < 1e-9)
    val hInd = (latOf(TY.toDouble()) - latOf(TY + 1.0)) * K
    check("MapData radiusM = half diagonal", abs(m.radiusM - hypot(wInd, hInd) / 2) < 1.0, m.radiusM)
    check("MapData key/fast/fetchedAt", m.key == id.key && !m.fast && m.fetchedAt == now, "${m.key} ${m.fast} ${m.fetchedAt}")

    val drawn = m.roads.filter { !it.labelOnly }
    val labels = m.roads.filter { it.labelOnly }
    check("transportation: 8 drawn roads (tunnel kept, rail/ferry/etc skipped)", drawn.size == 8, drawn.map { it.cls })
    check("road classes", drawn.map { it.cls }.sorted() == listOf(0, 1, 2, 2, 2, 3, 4, 4), drawn.map { it.cls }.sorted())
    check("drawn roads have no name", drawn.all { it.name == null })
    check("roads sorted minor first", m.roads.zipWithNext().all { (a, b) -> a.cls >= b.cls }, m.roads.map { it.cls })
    check("label roads: 5", labels.size == 5, labels.map { it.name })
    check("label names (name, ref, name:en fallbacks)", labels.map { it.name }.toSet() == setOf("Main Street", "GA 6", "English Rd", "Far Ave", "Ninety Rd"), labels.map { it.name })
    check("label road classes", labels.first { it.name == "GA 6" }.cls == 0 && labels.first { it.name == "English Rd" }.cls == 1 && labels.first { it.name == "Main Street" }.cls == 2)
    val prim = drawn.first { it.cls == 0 }
    check("primary road 3 vertices", prim.pts.size == 6, prim.pts.size)
    check("road bbox", prim.minX <= prim.pts[0] && prim.maxX >= prim.pts[4] && prim.minY <= prim.pts[5] && prim.maxY >= prim.pts[1])

    // Vertex accuracy (independent lat/lon -> local meters)
    val ms = labels.first { it.name == "Main Street" }
    val msExp = listOf(aLat - 0.00036 to aLon - 0.002, aLat - 0.00036 to aLon + 0.002)
    var err = 0.0
    msExp.forEachIndexed { i, (la, lo) -> err = max(err, hypot(ms.pts[2 * i] - ex(lo), ms.pts[2 * i + 1] - ey(la))) }
    val bA = m.buildings.firstOrNull { MapData.inside(it.pts, ex(aLon), ey(aLat)) }
    check("building A found", bA != null)
    if (bA != null) {
        check("building A 4 vertices (unclosed ring)", bA.pts.size == 8, bA.pts.size)
        aCorners.forEachIndexed { i, (la, lo) -> err = max(err, hypot(bA.pts[2 * i] - ex(lo), bA.pts[2 * i + 1] - ey(la))) }
        check("building A height 18 (double)", bA.heightM == 18f, bA.heightM)
        check("building A center/radius", abs(bA.cx - ex(aLon)) < 1 && abs(bA.cy - ey(aLat)) < 1 &&
            abs(bA.radius - max(ex(aLon + dLo) - ex(aLon - dLo), ey(aLat + dLa) - ey(aLat - dLa)) / 2) < 1, "${bA.cx},${bA.cy} r=${bA.radius}")
        check("building A label '12 Main Street'", bA.label == "12 Main Street", bA.label)
    }
    check("vertices within 1 m of independent positions", err < 1.0, err)
    println("  max vertex error: ${"%.3f".format(err)} m")
    val bB = m.buildings.firstOrNull { MapData.inside(it.pts, ex(bLon), ey(bLat)) }
    check("building B height 18 (int64)", bB?.heightM == 18f, bB?.heightM)
    check("building B label '#34' (no street in 80 m)", bB?.label == "#34", bB?.label)
    val heights = m.buildings.map { it.heightM }.sorted()
    check("building heights incl. float 30, clamp 120/2.5, default 6", heights == listOf(2.5f, 6f, 7f, 7f, 9f, 18f, 18f, 30f, 120f), heights)
    check("buffer buildings dropped (9 kept)", m.buildings.size == 9, m.buildings.size)
    check("only A and B labelled", m.buildings.count { it.label != null } == 2)
    val mp = m.buildings.filter { it.heightM == 7f }.sortedBy { it.pts.size }
    check("multipolygon hole dropped (4-pt rings)", mp.size == 2 && mp.all { it.pts.size == 8 })

    val waterA = m.areas.filter { it.kind == 0 }
    check("water: 1 area, exterior only", waterA.size == 1 && waterA[0].pts.size == 8, waterA.map { it.pts.size })
    val green = m.areas.filter { it.kind == 1 }
    check("green: park + wood + cemetery = 3", green.size == 3, green.size)
    check("green areas before water (water drawn on top)", m.areas.last().kind == 0)
    // park layer uses extent 512: check vertices against independent pixel -> lat/lon
    val pk = green.minByOrNull { it.minX }!!
    var perr = 0.0
    parkRing.forEachIndexed { i, pp ->
        val lo = lonOf(TX + pp.x / 512.0); val la = latOf(TY + pp.y / 512.0)
        perr = max(perr, hypot(pk.pts[2 * i] - ex(lo), pk.pts[2 * i + 1] - ey(la)))
    }
    check("extent 512 layer vertices exact (< 0.05 m)", perr < 0.05, perr)

    // ---- gzip
    val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
    val mg = VectorTile.decode(gz, id, now)
    fun same(a: MapData, b: MapData): Boolean =
        a.roads.size == b.roads.size && a.buildings.size == b.buildings.size && a.areas.size == b.areas.size &&
            a.roads.indices.all { a.roads[it].pts.contentEquals(b.roads[it].pts) && a.roads[it].name == b.roads[it].name && a.roads[it].cls == b.roads[it].cls && a.roads[it].labelOnly == b.roads[it].labelOnly } &&
            a.buildings.indices.all { a.buildings[it].pts.contentEquals(b.buildings[it].pts) && a.buildings[it].label == b.buildings[it].label && a.buildings[it].heightM == b.buildings[it].heightM } &&
            a.areas.indices.all { a.areas[it].pts.contentEquals(b.areas[it].pts) && a.areas[it].kind == b.areas[it].kind }
    check("gzip decodes identically", same(m, mg))

    // ---- robustness
    var threw: Throwable? = null
    fun safe(b: ByteArray): MapData? = try { VectorTile.decode(b, id, now) } catch (t: Throwable) { threw = t; null }
    for (n in 0 until raw.size) safe(raw.copyOf(n))
    check("every truncation of raw tile: no throw", threw == null, threw)
    for (n in 0 until gz.size) safe(gz.copyOf(n))
    check("every truncation of gzip tile: no throw", threw == null, threw)
    val hnStart = raw.size - tile(hn).size
    val cut = safe(raw.copyOf(hnStart + (raw.size - hnStart) / 2))
    check("truncated in last layer keeps earlier layers", cut != null && cut.roads.size == m.roads.size && cut.buildings.size == m.buildings.size && cut.areas.size == m.areas.size,
        "${cut?.roads?.size} ${cut?.buildings?.size} ${cut?.areas?.size}")
    val rnd = java.util.Random(42)
    for (i in 0 until 3000) safe(ByteArray(rnd.nextInt(3000)).also { rnd.nextBytes(it) })
    check("random garbage: no throw", threw == null, threw)
    for (i in 0 until 3000) safe(ByteArray(2 + rnd.nextInt(500)).also { rnd.nextBytes(it); it[0] = 0x1f; it[1] = 0x8b.toByte() })
    check("garbage with gzip magic: no throw", threw == null, threw)
    var partial = 0
    for (i in 0 until 5000) {
        val b = raw.copyOf()
        repeat(1 + rnd.nextInt(4)) { val k = rnd.nextInt(b.size); b[k] = (b[k].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
        val r = safe(b)
        if (r != null && r.roads.size + r.buildings.size + r.areas.size > 0) partial++
    }
    check("bit-flipped tiles: no throw", threw == null, threw)
    check("bit-flipped tiles still mostly decode", partial > 4000, partial)
    check("empty input -> empty MapData", safe(ByteArray(0))?.let { it.roads.isEmpty() && it.buildings.isEmpty() && it.areas.isEmpty() && it.key == id.key } == true)

    // ---- speed: ~4000 buildings, ~800 roads (+ names, housenumbers, areas)
    val r2 = java.util.Random(7)
    val sb = LayerB("building"); val st = LayerB("transportation"); val sn = LayerB("transportation_name")
    val sh = LayerB("housenumber"); val sw = LayerB("landcover")
    val cls = listOf("motorway", "primary", "secondary", "tertiary", "minor", "minor", "minor", "service", "path", "track")
    for (i in 0 until 4000) { // non-overlapping: one building per 64 px grid cell (63 x 64 cells)
        val cx = 32 + 64 * (i % 63); val cy = 32 + 64 * (i / 63); val w = 6 + r2.nextInt(22); val h = 6 + r2.nextInt(22)
        val ring = listOf(P(cx - w, cy - h), P(cx, cy - h - 4), P(cx + w, cy - h), P(cx + w, cy), P(cx + w, cy + h), P(cx, cy + h + 3), P(cx - w, cy + h), P(cx - w - 2, cy))
        sb.feature(3, polys(ring), "render_height" to V.I((3 + r2.nextInt(40)).toLong()), "colour" to s("#d0c0b0"))
        if (i % 4 != 3) sh.feature(1, points(P(cx, cy)), "housenumber" to s((100 + i).toString()))
    }
    for (i in 0 until 800) {
        var x = r2.nextInt(4096); var y = r2.nextInt(4096)
        val pts = ArrayList<P>()
        repeat(25) { pts += P(x, y); x += r2.nextInt(81) - 40; y += r2.nextInt(81) - 40 }
        val c = cls[r2.nextInt(cls.size)]
        st.feature(2, lines(pts), "class" to s(c), "subclass" to s("x$i"), "oneway" to V.I(1), "surface" to s("paved"))
        if (i % 2 == 0) sn.feature(2, lines(pts), "class" to s(c), "name" to s("Street $i"), "name:en" to s("Street $i"))
    }
    for (i in 0 until 200) {
        val cx = r2.nextInt(4096); val cy = r2.nextInt(4096); val r = 20 + r2.nextInt(150)
        val ring = (0 until 48).map { k -> val a = -2 * PI * k / 48; P(cx + (r * cos(a)).toInt(), cy + (r * sin(a)).toInt()) }.reversed()
        sw.feature(3, polys(ring), "class" to s(if (i % 2 == 0) "wood" else "grass"))
    }
    val big = tile(st, sn, sw, sb, sh)
    val bigGz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(big) } }.toByteArray()
    var t0 = System.nanoTime()
    var md = VectorTile.decode(big, id, now)
    val cold = (System.nanoTime() - t0) / 1e6
    val times = ArrayList<Double>()
    repeat(40) { md = VectorTile.decode(big, id, now) } // let the JIT settle
    repeat(25) { t0 = System.nanoTime(); md = VectorTile.decode(big, id, now); times += (System.nanoTime() - t0) / 1e6 }
    t0 = System.nanoTime(); val mdg = VectorTile.decode(bigGz, id, now); val gzMs = (System.nanoTime() - t0) / 1e6
    times.sort()
    println("  speed tile: ${big.size / 1024} KB raw (${bigGz.size / 1024} KB gzip): roads=${md.roads.size} buildings=${md.buildings.size} areas=${md.areas.size} labelled=${md.buildings.count { it.label != null }}")
    println("  decode: cold ${"%.1f".format(cold)} ms, warm median ${"%.1f".format(times[times.size / 2])} ms, best ${"%.1f".format(times[0])} ms, gzip ${"%.1f".format(gzMs)} ms")
    check("speed tile decoded fully", md.roads.size == 1200 && md.buildings.size > 3900 && md.areas.size == 200, "${md.roads.size} ${md.buildings.size} ${md.areas.size}")
    check("speed tile: every housenumber labels its building", md.buildings.count { it.label != null } == 3000, md.buildings.count { it.label != null })
    check("speed tile gzip identical", same(md, mdg))
    // Street part of each label vs brute-force nearest named line within 80 m of the house-number point
    val streetsBF = md.roads.filter { it.labelOnly }
    fun segD(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1; val dy = y2 - y1; val l2 = dx * dx + dy * dy
        val t = if (l2 > 0) (((px - x1) * dx + (py - y1) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
        return hypot(x1 + t * dx - px, y1 + t * dy - py)
    }
    var mism = 0; var withStreet = 0
    for (b in md.buildings) {
        val lab = b.label ?: continue
        val hnum = lab.removePrefix("#").substringBefore(' ').toInt()
        val i = hnum - 100
        val px = 32 + 64 * (i % 63); val py = 32 + 64 * (i / 63)
        val x = ex(lonOf(TX + px / 4096.0)); val y = ey(latOf(TY + py / 4096.0))
        var bd = 80.0; var bn: String? = null
        for (r in streetsBF) { var k = 0; while (k + 3 < r.pts.size) { val d = segD(x, y, r.pts[k].toDouble(), r.pts[k + 1].toDouble(), r.pts[k + 2].toDouble(), r.pts[k + 3].toDouble()); if (d < bd) { bd = d; bn = r.name }; k += 2 } }
        val expect = if (bn != null) "$hnum $bn" else "#$hnum"
        if (bn != null) withStreet++
        if (expect != lab) { mism++; if (mism <= 3) println("  label mismatch: got '$lab' expected '$expect'") }
    }
    check("street index matches brute force ($withStreet with street)", mism == 0 && withStreet > 500, "$mism mismatches")
    check("warm decode < 150 ms on JVM", times[times.size / 2] < 150, times[times.size / 2])

    println(if (failures == 0) "ALL PASS" else "$failures FAILED")
    if (failures > 0) throw AssertionError("$failures test failure(s)")
}
