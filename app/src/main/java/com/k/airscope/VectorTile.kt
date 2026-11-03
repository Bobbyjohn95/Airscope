package com.k.airscope

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator XYZ tile math (OpenFreeMap / OpenMapTiles tiles). Tile coordinates are fractional:
 * x grows east, y grows south, both in tiles at zoom z.
 */
object Tiles {
    /** Meters per degree of latitude, same earth radius as [LocalProj] so sizes agree with local meters. */
    private const val M_PER_DEG = PI / 180.0 * 6_371_000.0
    private const val MAX_LAT = 85.0511287798066

    private fun n(z: Int) = Math.scalb(1.0, z)

    fun x(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * n(z)

    fun y(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n(z)
    }

    fun lon(x: Double, z: Int): Double = x / n(z) * 360.0 - 180.0

    fun lat(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / n(z)))))

    data class TileId(val z: Int, val x: Int, val y: Int) {
        /** "z/x/y", as in the tile URL and [MapData.key]. */
        val key: String = "$z/$x/$y"
        val centerLat: Double get() = lat(y + 0.5, z)
        val centerLon: Double get() = lon(x + 0.5, z)
        /** East-west ground width at the tile's center latitude (m). */
        val widthM: Double get() = 360.0 / n(z) * M_PER_DEG * cos(Math.toRadians(centerLat))
        /** North-south ground height (m). */
        val heightM: Double get() = (lat(y.toDouble(), z) - lat(y + 1.0, z)) * M_PER_DEG
        /** Half the tile diagonal (m). */
        val radiusM: Double get() = hypot(widthM, heightM) / 2
    }

    /** All tiles intersecting the square of half-size [radiusM] around the point, nearest-first. */
    fun around(lat: Double, lon: Double, radiusM: Double, z: Int = 14): List<TileId> {
        val n = 1 shl z
        val r = max(radiusM, 0.0)
        val dLat = r / M_PER_DEG
        val dLon = r / (M_PER_DEG * max(cos(Math.toRadians(lat)), 1e-6))
        val fx = x(lon, z); val fy = y(lat, z)
        val x0 = floor(x(lon - dLon, z)).toInt()
        val x1 = min(floor(x(lon + dLon, z)).toInt(), x0 + n - 1)
        val y0 = floor(y(lat + dLat, z)).toInt().coerceIn(0, n - 1)
        val y1 = floor(y(lat - dLat, z)).toInt().coerceIn(0, n - 1)
        val seen = HashSet<TileId>()
        val out = ArrayList<Pair<Double, TileId>>()
        for (ty in y0..y1) for (tx in x0..x1) {
            val id = TileId(z, Math.floorMod(tx, n), ty)
            if (!seen.add(id)) continue
            val dx = tx + 0.5 - fx; val dy = ty + 0.5 - fy
            out += (dx * dx + dy * dy) to id
        }
        out.sortBy { it.first }
        return out.map { it.second }
    }

    /**
     * Tiles a straight line (constant course, i.e. straight in Mercator) from the point to [aheadM] ahead
     * passes through, in the order the line enters them (nearest-first), no duplicates. Exact grid walk,
     * so a tile the line only clips at a corner is included too.
     */
    fun along(lat: Double, lon: Double, courseDeg: Double, aheadM: Double, z: Int = 14): List<TileId> {
        val n = 1 shl z
        val d = if (aheadM.isNaN()) 0.0 else aheadM.coerceIn(0.0, 2_000_000.0)
        val c = Math.toRadians(courseDeg)
        val lat1 = lat + d * cos(c) / M_PER_DEG
        val lon1 = lon + d * sin(c) / (M_PER_DEG * max(cos(Math.toRadians(lat)), 1e-6))
        val fx0 = x(lon, z); val fy0 = y(lat, z)
        val fx1 = x(lon1, z); val fy1 = y(lat1, z)
        val out = LinkedHashSet<TileId>()
        fun add(tx: Int, ty: Int) { if (ty in 0 until n) out += TileId(z, Math.floorMod(tx, n), ty) }
        var cx = floor(fx0).toInt(); var cy = floor(fy0).toInt()
        val ex = floor(fx1).toInt(); val ey = floor(fy1).toInt()
        val dx = fx1 - fx0; val dy = fy1 - fy0
        val sx = if (dx > 0) 1 else -1; val sy = if (dy > 0) 1 else -1
        val tdx = if (dx != 0.0) 1.0 / abs(dx) else Double.POSITIVE_INFINITY
        val tdy = if (dy != 0.0) 1.0 / abs(dy) else Double.POSITIVE_INFINITY
        var tmx = if (dx > 0) (cx + 1 - fx0) * tdx else if (dx < 0) (fx0 - cx) * tdx else Double.POSITIVE_INFINITY
        var tmy = if (dy > 0) (cy + 1 - fy0) * tdy else if (dy < 0) (fy0 - cy) * tdy else Double.POSITIVE_INFINITY
        add(cx, cy)
        var guard = abs(ex - cx) + abs(ey - cy) + 2
        while ((cx != ex || cy != ey) && guard-- > 0) {
            if (tmx < tmy) { cx += sx; tmx += tdx } else { cy += sy; tmy += tdy }
            add(cx, cy)
        }
        return out.toList()
    }
}

/**
 * Mapbox Vector Tile (spec 2.1) decoder for OpenMapTiles-schema tiles (OpenFreeMap), straight into [MapData]
 * in local meters around the tile center. Pure JVM: no Android or third-party dependencies.
 * Map data (c) OpenMapTiles, (c) OpenStreetMap contributors, ODbL.
 */
object VectorTile {
    private const val MAX_RAW = 16 shl 20

    /**
     * Decodes a tile (raw protobuf or gzip). Never throws: on truncated or corrupt input it returns
     * whatever decoded cleanly (possibly an empty MapData).
     */
    fun decode(bytes: ByteArray, id: Tiles.TileId, now: Long): MapData {
        val lat0 = id.centerLat
        val lon0 = id.centerLon
        val roads = ArrayList<MapData.Road>()
        val buildings = ArrayList<MapData.Building>()
        val areas = ArrayList<MapData.Area>()
        try {
            Decoder(unzip(bytes), id, LocalProj(lat0, lon0), roads, buildings, areas).run()
        } catch (e: Exception) {
            // keep what was decoded so far
        }
        roads.sortBy { -it.cls } // draw minor roads first, highways on top
        return MapData(lat0, lon0, roads, buildings, areas, now, id.radiusM, false, id.key)
    }

    private fun unzip(b: ByteArray): ByteArray {
        if (b.size < 2 || b[0] != 0x1f.toByte() || b[1] != 0x8b.toByte()) return b
        val out = ByteArrayOutputStream(max(b.size * 4, 1024))
        try {
            GZIPInputStream(ByteArrayInputStream(b), 16384).use { gz ->
                val buf = ByteArray(16384)
                while (out.size() < MAX_RAW) {
                    val r = gz.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                }
            }
        } catch (e: Exception) {
            // truncated / corrupt gzip: decode what inflated so far
        }
        return out.toByteArray()
    }

    /** Thrown on malformed protobuf; preallocated, no stack trace. */
    private class PbError : RuntimeException("malformed tile", null, false, false)
    private val ERR = PbError()

    // Attribute slots we care about
    private const val S_CLASS = 0
    private const val S_NAME = 1
    private const val S_NAME_EN = 2
    private const val S_NAME_EN2 = 3
    private const val S_REF = 4
    private const val S_HEIGHT = 5
    private const val S_HOUSENUMBER = 6
    private const val NSLOT = 7
    /** Street-name fallback order: name, name:en, name_en, ref. */
    private val NAME_SLOTS = intArrayOf(S_NAME, S_NAME_EN, S_NAME_EN2, S_REF)

    private fun slotOf(k: String): Int = when (k) {
        "class" -> S_CLASS
        "name" -> S_NAME
        "name:en" -> S_NAME_EN
        "name_en" -> S_NAME_EN2
        "ref" -> S_REF
        "render_height" -> S_HEIGHT
        "housenumber" -> S_HOUSENUMBER
        else -> -1
    }

    private val WANTED = setOf("transportation", "transportation_name", "building", "water", "park", "landcover", "landuse", "housenumber")

    private const val POINT = 1
    private const val LINESTRING = 2
    private const val POLYGON = 3

    /** OpenMapTiles transportation class -> MapData road class, -1 = skip. */
    private fun roadCls(c: String?): Int = when (c) {
        "motorway", "trunk", "primary" -> 0
        "secondary", "tertiary" -> 1
        "minor" -> 2
        "service" -> 3
        "track", "path" -> 4
        else -> -1
    }

    private class IntList(cap: Int = 16) {
        var a = IntArray(cap)
        var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    }

    /** One layer after the scan: attribute tables decoded, features as byte ranges (start,end pairs). */
    private class Layer(val name: String, val extent: Int, val slots: IntArray, val strs: Array<String?>, val nums: DoubleArray, val feats: IntList) {
        val nv get() = strs.size
    }

    private class Decoder(
        val b: ByteArray, val id: Tiles.TileId, val proj: LocalProj,
        val roads: ArrayList<MapData.Road>, val buildings: ArrayList<MapData.Building>, val areas: ArrayList<MapData.Area>,
    ) {
        var p = 0
        var lim = 0

        // ---- protobuf primitives (bounded by lim; throw ERR when out of data)
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (s < 64) {
                if (p >= lim) throw ERR
                val x = b[p++].toInt()
                r = r or ((x and 0x7f).toLong() shl s)
                if (x >= 0) return r
                s += 7
            }
            throw ERR
        }

        fun len(): Int {
            val l = varint()
            if (l < 0 || l > lim - p) throw ERR
            return l.toInt()
        }

        fun adv(n: Int) { if (n < 0 || n > lim - p) throw ERR; p += n }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> adv(8)
                2 -> adv(len())
                5 -> adv(4)
                else -> throw ERR
            }
        }

        fun fixed32(): Int {
            adv(4)
            return (b[p - 4].toInt() and 0xff) or ((b[p - 3].toInt() and 0xff) shl 8) or
                ((b[p - 2].toInt() and 0xff) shl 16) or (b[p - 1].toInt() shl 24)
        }

        fun fixed64(): Long {
            val lo = fixed32().toLong() and 0xffffffffL
            val hi = fixed32().toLong()
            return lo or (hi shl 32)
        }

        fun str(l: Int): String = String(b, p, l, Charsets.UTF_8).also { p += l }

        // ---- tile / layer scan
        fun run() {
            val ranges = IntList()
            p = 0; lim = b.size
            try {
                while (p < lim) {
                    val t = varint()
                    val w = (t and 7).toInt()
                    if (t ushr 3 == 3L && w == 2) {
                        val l = varint()
                        val e = if (l < 0 || l > lim - p) lim else p + l.toInt() // truncated: keep the part we have
                        ranges.add(p); ranges.add(e)
                        p = e
                    } else skip(w)
                }
            } catch (e: PbError) {
                // keep layers found so far
            }
            val layers = HashMap<String, Layer>()
            var i = 0
            while (i < ranges.n) {
                val l = try { scanLayer(ranges.a[i], ranges.a[i + 1]) } catch (e: RuntimeException) { null }
                if (l != null && l.name !in layers) layers[l.name] = l
                i += 2
            }
            // Green areas first, water on top (ponds in parks), then roads, buildings, finally house numbers
            // (they need the buildings and the street-name lines).
            layers["landcover"]?.let { safe { areaLayer(it, 1, setOf("grass", "wood", "wetland")) } }
            layers["landuse"]?.let { safe { areaLayer(it, 1, setOf("cemetery", "pitch", "playground", "park", "stadium", "garden")) } }
            layers["park"]?.let { safe { areaLayer(it, 1, null) } }
            layers["water"]?.let { safe { areaLayer(it, 0, null) } }
            layers["transportation"]?.let { safe { roadLayer(it, false) } }
            layers["transportation_name"]?.let { safe { roadLayer(it, true) } }
            layers["building"]?.let { safe { buildingLayer(it) } }
            layers["housenumber"]?.let { safe { houseNumbers(it) } }
        }

        inline fun safe(f: () -> Unit) {
            try { f() } catch (e: RuntimeException) { /* keep what this layer produced so far */ }
        }

        fun scanLayer(start: Int, end: Int): Layer? {
            p = start; lim = end
            var name: String? = null
            var extent = 4096
            val feats = IntList(256)
            val keyR = IntList()
            val valR = IntList()
            try {
                while (p < lim) {
                    val t = varint()
                    val f = t ushr 3
                    val w = (t and 7).toInt()
                    when {
                        f == 1L && w == 2 -> name = str(len())
                        f == 2L && w == 2 -> { val l = len(); feats.add(p); feats.add(p + l); p += l }
                        f == 3L && w == 2 -> { val l = len(); keyR.add(p); keyR.add(l); p += l }
                        f == 4L && w == 2 -> { val l = len(); valR.add(p); valR.add(l); p += l }
                        f == 5L && w == 0 -> extent = varint().coerceIn(-1L, Int.MAX_VALUE.toLong()).toInt()
                        else -> skip(w)
                    }
                }
            } catch (e: PbError) {
                // truncated layer: keep what we have
            }
            val nm = name ?: return null
            if (nm !in WANTED || extent < 1 || extent > 65536) return null
            val slots = IntArray(keyR.n / 2) { slotOf(String(b, keyR.a[2 * it], keyR.a[2 * it + 1], Charsets.UTF_8)) }
            val nv = valR.n / 2
            val strs = arrayOfNulls<String>(nv)
            val nums = DoubleArray(nv) { Double.NaN }
            for (i in 0 until nv) {
                p = valR.a[2 * i]; lim = p + valR.a[2 * i + 1]
                try {
                    while (p < lim) {
                        val t = varint()
                        val f = t ushr 3
                        val w = (t and 7).toInt()
                        when {
                            f == 1L && w == 2 -> strs[i] = str(len())
                            f == 2L && w == 5 -> nums[i] = java.lang.Float.intBitsToFloat(fixed32()).toDouble()
                            f == 3L && w == 1 -> nums[i] = java.lang.Double.longBitsToDouble(fixed64())
                            f == 4L && w == 0 -> nums[i] = varint().toDouble()
                            f == 5L && w == 0 -> nums[i] = varint().toULong().toDouble()
                            f == 6L && w == 0 -> { val v = varint(); nums[i] = ((v ushr 1) xor -(v and 1L)).toDouble() }
                            f == 7L && w == 0 -> nums[i] = if (varint() != 0L) 1.0 else 0.0
                            else -> skip(w)
                        }
                    }
                } catch (e: PbError) {
                    // bad value: leave it empty
                }
            }
            return Layer(nm, extent, slots, strs, nums, feats)
        }

        // ---- feature
        val tv = IntArray(NSLOT)
        var fType = 0
        var gStart = 0
        var gEnd = 0

        /** Parses feature [i] of the layer: tags into tv (value index per slot, -1 absent), type, geometry range. */
        fun feature(L: Layer, i: Int): Boolean {
            java.util.Arrays.fill(tv, -1)
            fType = 0; gStart = 0; gEnd = 0
            p = L.feats.a[2 * i]; lim = L.feats.a[2 * i + 1]
            val slots = L.slots
            val nv = L.nv
            while (p < lim) {
                val t = varint()
                val f = t ushr 3
                val w = (t and 7).toInt()
                when {
                    f == 2L && w == 2 -> {
                        val l = len()
                        val e = p + l
                        while (p < e) {
                            val k = varint()
                            if (p >= e) break
                            val v = varint()
                            if (k >= 0 && k < slots.size && v >= 0 && v < nv) {
                                val s = slots[k.toInt()]
                                if (s >= 0) tv[s] = v.toInt()
                            }
                        }
                        p = e
                    }
                    f == 3L && w == 0 -> fType = varint().toInt()
                    f == 4L && w == 2 -> { val l = len(); gStart = p; gEnd = p + l; p += l }
                    else -> skip(w)
                }
            }
            return gEnd > gStart
        }

        fun s(L: Layer, slot: Int): String? { val v = tv[slot]; return if (v < 0) null else L.strs[v] }

        fun num(L: Layer, slot: Int): Double {
            val v = tv[slot]
            if (v < 0) return Double.NaN
            val d = L.nums[v]
            return if (!d.isNaN()) d else L.strs[v]?.trim()?.toDoubleOrNull() ?: Double.NaN
        }

        // ---- pixel -> local meters (x is linear in px; y via a lazily filled table for integer py)
        var ext = 0
        var ax = 0.0
        var bx = 0.0
        var yTab = FloatArray(0)
        var tabOff = 0

        fun setExtent(e: Int) {
            if (e == ext) return
            ext = e
            val x0 = proj.x(Tiles.lon(id.x.toDouble(), id.z))
            val x1 = proj.x(Tiles.lon(id.x + 1.0, id.z))
            ax = (x1 - x0) / e
            bx = x0
            tabOff = e
            yTab = if (e <= 16384) FloatArray(3 * e).also { it.fill(Float.NaN) } else FloatArray(0) // py in [-e, 2e)
        }

        fun mx(px: Int): Float = (ax * px + bx).toFloat()

        fun my(py: Int): Float {
            val i = py + tabOff
            if (i >= 0 && i < yTab.size) {
                val v = yTab[i]
                if (v == v) return v
                val c = yCalc(py)
                yTab[i] = c
                return c
            }
            return yCalc(py)
        }

        fun yCalc(py: Int): Float = proj.y(Tiles.lat(id.y + py.toDouble() / ext, id.z)).toFloat()

        // ---- geometry: decoded into a scratch buffer of pixel coords, emitted per part
        var sx = IntArray(1024)
        var sn = 0
        var minX = 0f; var minY = 0f; var maxX = 0f; var maxY = 0f

        fun push(x: Int, y: Int) {
            if (sn + 2 > sx.size) sx = sx.copyOf(sx.size * 2)
            sx[sn++] = x; sx[sn++] = y
        }

        /** Converts the scratch part to local meters; sets minX..maxY. */
        fun meters(): FloatArray {
            val n = sn
            val out = FloatArray(n)
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            var i = 0
            while (i < n) {
                val x = mx(sx[i]); val y = my(sx[i + 1])
                out[i] = x; out[i + 1] = y
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                i += 2
            }
            minX = x0; minY = y0; maxX = x1; maxY = y1
            return out
        }

        /** Twice the signed ring area in tile pixels (y down): > 0 exterior, < 0 interior (MVT 2.1). */
        fun ringArea2(): Long {
            var a = 0L
            var j = sn - 2
            var i = 0
            while (i < sn) {
                a += sx[j].toLong() * sx[i + 1] - sx[i].toLong() * sx[j + 1]
                j = i; i += 2
            }
            return a
        }

        /** Pixel bbox center of the scratch ring inside [0, ext) on both axes. */
        fun centerInTile(): Boolean {
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
            var i = 0
            while (i < sn) {
                val x = sx[i]; val y = sx[i + 1]
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                i += 2
            }
            val cx2 = x0.toLong() + x1; val cy2 = y0.toLong() + y1
            return cx2 >= 0 && cx2 < 2L * ext && cy2 >= 0 && cy2 < 2L * ext
        }

        // What part() emits: 0 road, 1 building, 2 area
        var ctx = 0
        var cCls = 0
        var cName: String? = null
        var cLabel = false
        var cHeight = 6f
        var cKind = 0

        fun part(mode: Int) {
            if (mode == LINESTRING) {
                if (sn >= 4) {
                    val pts = meters()
                    roads += MapData.Road(cCls, cName, pts, minX, minY, maxX, maxY, cLabel)
                }
            } else if (mode == POLYGON) {
                if (sn >= 6 && ringArea2() > 0) {
                    if (ctx == 1) {
                        if (centerInTile()) {
                            val pts = meters()
                            buildings += MapData.Building(pts, cHeight, (minX + maxX) / 2, (minY + maxY) / 2, max(maxX - minX, maxY - minY) / 2, null)
                        }
                    } else {
                        val pts = meters()
                        areas += MapData.Area(cKind, pts, minX, minY, maxX, maxY)
                    }
                }
            }
            sn = 0
        }

        /**
         * Walks the current feature's command stream. Lines/polygons are emitted part by part via [part];
         * for points the vertices are left in the scratch buffer.
         */
        fun walk(mode: Int) {
            p = gStart; lim = gEnd
            sn = 0
            var cx = 0; var cy = 0
            while (p < lim) {
                val ci = varint().toInt()
                val cmd = ci and 7
                val cnt = ci ushr 3
                when (cmd) {
                    1 -> for (k in 0 until cnt) { // MoveTo
                        var u = varint().toInt(); cx += (u ushr 1) xor -(u and 1)
                        u = varint().toInt(); cy += (u ushr 1) xor -(u and 1)
                        if (mode != POINT) part(mode)
                        push(cx, cy)
                    }
                    2 -> for (k in 0 until cnt) { // LineTo
                        var u = varint().toInt(); cx += (u ushr 1) xor -(u and 1)
                        u = varint().toInt(); cy += (u ushr 1) xor -(u and 1)
                        push(cx, cy)
                    }
                    7 -> if (mode == POLYGON) part(mode) // ClosePath
                    else -> throw ERR
                }
            }
            if (mode != POINT) part(mode)
        }

        // ---- layers
        fun nameOf(L: Layer): String? {
            for (slot in NAME_SLOTS) {
                val v = s(L, slot)
                if (!v.isNullOrBlank()) return v
            }
            return null
        }

        fun roadLayer(L: Layer, labels: Boolean) {
            setExtent(L.extent)
            ctx = 0; cLabel = labels
            for (i in 0 until L.feats.n / 2) {
                try {
                    if (!feature(L, i) || fType != LINESTRING) continue
                    val cls = roadCls(s(L, S_CLASS))
                    if (cls < 0) continue
                    val name = if (labels) nameOf(L) ?: continue else null
                    cCls = cls; cName = name
                    walk(LINESTRING)
                } catch (e: RuntimeException) {
                    // skip corrupt feature
                }
            }
        }

        fun areaLayer(L: Layer, kind: Int, classes: Set<String>?) {
            setExtent(L.extent)
            ctx = 2; cKind = kind
            for (i in 0 until L.feats.n / 2) {
                try {
                    if (!feature(L, i) || fType != POLYGON) continue
                    if (classes != null && s(L, S_CLASS) !in classes) continue
                    walk(POLYGON)
                } catch (e: RuntimeException) {
                    // skip corrupt feature
                }
            }
        }

        fun buildingLayer(L: Layer) {
            setExtent(L.extent)
            ctx = 1
            for (i in 0 until L.feats.n / 2) {
                try {
                    if (!feature(L, i) || fType != POLYGON) continue
                    val h = num(L, S_HEIGHT)
                    cHeight = (if (h.isNaN()) 6.0 else h.coerceIn(2.5, 120.0)).toFloat()
                    walk(POLYGON)
                } catch (e: RuntimeException) {
                    // skip corrupt feature
                }
            }
        }

        /** Labels the building under each house-number point "<number> <nearest street within 80 m>". */
        fun houseNumbers(L: Layer) {
            if (buildings.isEmpty()) return
            setExtent(L.extent)
            val grid = BuildingGrid(buildings)
            val streets = StreetIndex(roads.filter { it.labelOnly && it.name != null }, 80.0)
            val labeled = BooleanArray(buildings.size)
            for (i in 0 until L.feats.n / 2) {
                try {
                    if (!feature(L, i) || fType != POINT) continue
                    val v = tv[S_HOUSENUMBER]
                    if (v < 0) continue
                    val hn = L.strs[v]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: L.nums[v].takeIf { !it.isNaN() }?.let { if (it == floor(it) && abs(it) < 1e15) it.toLong().toString() else it.toString() }
                        ?: continue
                    walk(POINT)
                    var k = 0
                    while (k < sn) {
                        val x = mx(sx[k]).toDouble(); val y = my(sx[k + 1]).toDouble()
                        k += 2
                        val bi = grid.find(x, y)
                        if (bi < 0 || labeled[bi]) continue
                        labeled[bi] = true
                        val street = streets.nearest(x, y)
                        val b = buildings[bi]
                        buildings[bi] = MapData.Building(b.pts, b.heightM, b.cx, b.cy, b.radius, if (street != null) "$hn $street" else "#$hn")
                    }
                } catch (e: RuntimeException) {
                    // skip corrupt feature
                }
            }
        }

    }

    /**
     * Segments of the named street lines in a uniform grid with cells >= maxM (coordinates stored inline
     * per cell), so the nearest street within maxM of a point needs only the point's cell and its 8 neighbors.
     */
    private class StreetIndex(val roads: List<MapData.Road>, val maxM: Double) {
        var x0 = 0.0; var y0 = 0.0; var cell = 1.0; var gx = 0; var gy = 0
        var start = IntArray(1)   // CSR offsets per cell
        var sc = FloatArray(0)    // x1,y1,x2,y2 per entry
        var sr = IntArray(0)      // road index per entry

        init {
            if (roads.isNotEmpty()) {
                var a = Double.MAX_VALUE; var c = Double.MAX_VALUE; var b = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
                for (r in roads) {
                    a = min(a, r.minX.toDouble()); b = max(b, r.maxX.toDouble()); c = min(c, r.minY.toDouble()); d = max(d, r.maxY.toDouble())
                }
                x0 = a; y0 = c
                cell = max(maxM, max(b - a, d - c) / 256)
                gx = ((b - a) / cell).toInt().coerceIn(0, 300) + 1; gy = ((d - c) / cell).toInt().coerceIn(0, 300) + 1
                val counts = IntArray(gx * gy + 1)
                eachSeg { _, q, i -> forCells(q, i) { counts[it + 1]++ } }
                for (i in 0 until gx * gy) counts[i + 1] += counts[i]
                start = counts
                val n = counts[gx * gy]
                sc = FloatArray(4 * n); sr = IntArray(n)
                val fill = counts.copyOf(gx * gy)
                eachSeg { ri, q, i ->
                    forCells(q, i) { cc ->
                        val e = fill[cc]++
                        sr[e] = ri
                        sc[4 * e] = q[i]; sc[4 * e + 1] = q[i + 1]; sc[4 * e + 2] = q[i + 2]; sc[4 * e + 3] = q[i + 3]
                    }
                }
            }
        }

        inline fun eachSeg(f: (Int, FloatArray, Int) -> Unit) {
            for ((ri, r) in roads.withIndex()) {
                var i = 0
                while (i + 3 < r.pts.size) { f(ri, r.pts, i); i += 2 }
            }
        }

        fun ix(x: Double) = ((x - x0) / cell).toInt().coerceIn(0, gx - 1)
        fun iy(y: Double) = ((y - y0) / cell).toInt().coerceIn(0, gy - 1)

        inline fun forCells(q: FloatArray, i: Int, f: (Int) -> Unit) {
            val ax = ix(min(q[i], q[i + 2]).toDouble()); val bx = ix(max(q[i], q[i + 2]).toDouble())
            val ay = iy(min(q[i + 1], q[i + 3]).toDouble()); val by = iy(max(q[i + 1], q[i + 3]).toDouble())
            for (j in ay..by) for (c in ax..bx) f(j * gx + c)
        }

        private var best = -1
        private var bd = 0.0

        /** Name of the street line nearest to the point, if within maxM. */
        fun nearest(x: Double, y: Double): String? {
            if (gx == 0) return null
            val fx = (x - x0) / cell; val fy = (y - y0) / cell
            if (fx < -1.0 || fy < -1.0 || fx > gx + 1.0 || fy > gy + 1.0) return null
            val cx = floor(fx).toInt(); val cy = floor(fy).toInt()
            best = -1; bd = maxM * maxM
            if (cx in 0 until gx && cy in 0 until gy) scan(cy * gx + cx, x, y)
            for (j in max(cy - 1, 0)..min(cy + 1, gy - 1)) for (i in max(cx - 1, 0)..min(cx + 1, gx - 1)) {
                if (i == cx && j == cy) continue
                // skip the cell if its rectangle is farther than the best so far
                val rx0 = x0 + i * cell; val ry0 = y0 + j * cell
                val ddx = max(max(rx0 - x, x - (rx0 + cell)), 0.0); val ddy = max(max(ry0 - y, y - (ry0 + cell)), 0.0)
                if (ddx * ddx + ddy * ddy > bd) continue
                scan(j * gx + i, x, y)
            }
            return if (best >= 0) roads[best].name else null
        }

        private fun scan(c: Int, x: Double, y: Double) {
            val q = sc
            for (e in start[c] until start[c + 1]) {
                val x1 = q[4 * e].toDouble(); val y1 = q[4 * e + 1].toDouble(); val x2 = q[4 * e + 2].toDouble(); val y2 = q[4 * e + 3].toDouble()
                val ddx = max(max(min(x1, x2) - x, x - max(x1, x2)), 0.0); val ddy = max(max(min(y1, y2) - y, y - max(y1, y2)), 0.0)
                if (ddx * ddx + ddy * ddy > bd) continue
                val d = segDist2(x, y, x1, y1, x2, y2)
                val r = sr[e]
                if (d < bd || (d == bd && (best < 0 || r < best))) { bd = d; best = r }
            }
        }
    }

    private fun segDist2(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1; val dy = y2 - y1
        val l2 = dx * dx + dy * dy
        val t = if (l2 > 0) (((px - x1) * dx + (py - y1) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
        val ex = x1 + t * dx - px; val ey = y1 + t * dy - py
        return ex * ex + ey * ey
    }

    /** Uniform grid over the buildings' bboxes (CSR layout) for point-in-building lookups. */
    private class BuildingGrid(val bs: List<MapData.Building>) {
        val g = 64
        val x0: Double; val y0: Double; val cw: Double; val ch: Double
        val start = IntArray(g * g + 1)
        val idx: IntArray

        init {
            var a = Double.MAX_VALUE; var c = Double.MAX_VALUE; var bb = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
            for (b in bs) {
                a = min(a, (b.cx - b.radius).toDouble()); bb = max(bb, (b.cx + b.radius).toDouble())
                c = min(c, (b.cy - b.radius).toDouble()); d = max(d, (b.cy + b.radius).toDouble())
            }
            x0 = a; y0 = c
            cw = max((bb - a) / g, 1e-3); ch = max((d - c) / g, 1e-3)
            for (b in bs) forCells(b) { start[it + 1]++ }
            for (i in 0 until g * g) start[i + 1] += start[i]
            idx = IntArray(start[g * g])
            val fill = start.copyOf(g * g)
            for ((i, b) in bs.withIndex()) forCells(b) { idx[fill[it]++] = i }
        }

        fun cx(x: Double) = ((x - x0) / cw).toInt().coerceIn(0, g - 1)
        fun cy(y: Double) = ((y - y0) / ch).toInt().coerceIn(0, g - 1)

        inline fun forCells(b: MapData.Building, f: (Int) -> Unit) {
            val ax = cx((b.cx - b.radius).toDouble()); val bx = cx((b.cx + b.radius).toDouble())
            val ay = cy((b.cy - b.radius).toDouble()); val by = cy((b.cy + b.radius).toDouble())
            for (j in ay..by) for (i in ax..bx) f(j * g + i)
        }

        /** Index of the building containing the point, or -1. */
        fun find(x: Double, y: Double): Int {
            if (x < x0 || y < y0 || x > x0 + cw * g || y > y0 + ch * g) return -1
            val c = cy(y) * g + cx(x)
            for (k in start[c] until start[c + 1]) {
                val i = idx[k]
                val b = bs[i]
                if (abs(b.cx - x) > b.radius || abs(b.cy - y) > b.radius) continue
                if (MapData.inside(b.pts, x, y)) return i
            }
            return -1
        }
    }
}
