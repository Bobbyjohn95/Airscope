package com.k.airscope

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Predicts where you're driving so the map ahead loads before you get there (pure logic, unit-tested).
 * It only predicts the road: devices are never predicted.
 *
 * Finds the road you're on (near you, pointing your way), follows it through bends, and at each junction keeps
 * the straightest continuation (preferring same-or-bigger roads) while also noting the side branches so their
 * first stretch can be preloaded too. Where the loaded map ends, it extends straight ahead.
 */
object RoutePredictor {
    class Prediction(val main: List<DoubleArray>, val branches: List<DoubleArray>)

    private class Seg(val pts: DoubleArray, val cls: Int) // x,y pairs in the common frame

    /** Points (lat, lon) along the likely route ahead, and side-branch points. */
    fun predict(chunks: List<MapData>, lat: Double, lon: Double, courseDeg: Double, horizonM: Double): Prediction {
        val p = LocalProj(lat, lon)
        val segs = ArrayList<Seg>()
        for (m in chunks) {
            val ox = p.x(m.lon0); val oy = p.y(m.lat0)
            if (hypot(ox, oy) > horizonM + m.radiusM + 500) continue
            for (r in m.roads) {
                if (r.labelOnly || r.cls > 3 || r.pts.size < 4) continue
                segs += Seg(DoubleArray(r.pts.size) { i -> r.pts[i] + if (i % 2 == 0) ox else oy }, r.cls)
            }
        }
        val main = ArrayList<DoubleArray>()
        val branches = ArrayList<DoubleArray>()
        var hx = sin(Math.toRadians(courseDeg)); var hy = cos(Math.toRadians(courseDeg))
        var x = 0.0; var y = 0.0
        var traveled = 0.0
        var nextMark = 0.0

        fun mark(px: Double, py: Double) {
            if (traveled >= nextMark) { main += doubleArrayOf(p.lat(py), p.lon(px)); nextMark = traveled + 150 }
        }

        // 1) Match onto the road you're on: close by and pointing your way
        var cur: Seg? = null; var curIdx = 0; var forward = true
        var bestD = 30.0
        for (s in segs) {
            var i = 0
            while (i + 3 < s.pts.size) {
                val ax = s.pts[i]; val ay = s.pts[i + 1]; val bx = s.pts[i + 2]; val by = s.pts[i + 3]
                val dx = bx - ax; val dy = by - ay; val len = hypot(dx, dy)
                if (len > 0.5) {
                    val t = ((x - ax) * dx + (y - ay) * dy) / (len * len)
                    val d = hypot(ax + dx * t.coerceIn(0.0, 1.0) - x, ay + dy * t.coerceIn(0.0, 1.0) - y)
                    val cosA = (dx * hx + dy * hy) / len
                    if (d < bestD && abs(cosA) > 0.7) { bestD = d; cur = s; forward = cosA > 0; curIdx = if (forward) i / 2 else i / 2 + 1 }
                }
                i += 2
            }
        }

        // Spatial index of every vertex (25 m cells) so junctions anywhere along a road are found quickly
        val grid = HashMap<Long, MutableList<IntArray>>() // cell -> [segIndex, vertexIndex]
        fun cell(cx: Double, cy: Double) = (Math.floorDiv(cx.toLong(), 25L) shl 32) xor (Math.floorDiv(cy.toLong(), 25L) and 0xffffffffL)
        for ((si, sg) in segs.withIndex()) for (v in 0 until sg.pts.size / 2) {
            grid.getOrPut(cell(sg.pts[v * 2], sg.pts[v * 2 + 1])) { ArrayList() } += intArrayOf(si, v)
        }
        /** Roads leaving (px, py) other than [self]: (seg, vertex, forward, turn deg) for each direction. */
        fun exits(px: Double, py: Double, self: Seg?): List<Pair<Triple<Seg, Int, Boolean>, Double>> {
            val out = ArrayList<Pair<Triple<Seg, Int, Boolean>, Double>>()
            val c0 = Math.floorDiv(px.toLong(), 25L); val r0 = Math.floorDiv(py.toLong(), 25L)
            for (dxc in -1L..1L) for (dyc in -1L..1L) {
                val list = grid[((c0 + dxc) shl 32) xor ((r0 + dyc) and 0xffffffffL)] ?: continue
                for (e in list) {
                    val o = segs[e[0]]
                    if (o === self) continue
                    val v = e[1]
                    if (hypot(o.pts[v * 2] - px, o.pts[v * 2 + 1] - py) > 12) continue
                    val m = o.pts.size / 2
                    for (fwd in listOf(true, false)) {
                        val ni = if (fwd) v + 1 else v - 1
                        if (ni !in 0 until m) continue
                        val dx = o.pts[ni * 2] - o.pts[v * 2]; val dy = o.pts[ni * 2 + 1] - o.pts[v * 2 + 1]
                        if (hypot(dx, dy) < 0.5) continue
                        val turn = Math.toDegrees(abs(atan2(hx * dy - hy * dx, hx * dx + hy * dy)))
                        out += Triple(o, v, fwd) to turn
                    }
                }
            }
            return out
        }
        val branched = HashSet<Seg>()

        // 2) Follow it
        var guard = 0
        while (cur != null && traveled < horizonM && guard++ < 400) {
            val s = cur
            val n = s.pts.size / 2
            var k = if (forward) curIdx + 1 else curIdx - 1
            while (k in 0 until n && traveled < horizonM) {
                val nx = s.pts[k * 2]; val ny = s.pts[k * 2 + 1]
                val step = hypot(nx - x, ny - y)
                if (step > 0.5) { hx = (nx - x) / step; hy = (ny - y) / step }
                traveled += step; x = nx; y = ny
                mark(x, y)
                // Side streets joining here: preload their first stretch
                if (k in 1 until n - 1) for ((e, turn) in exits(x, y, s)) {
                    if (turn in 25.0..150.0 && e.first !in branched) { branched += e.first; follow(e.first, e.third, e.second, 600.0, branches, p) }
                }
                k += if (forward) 1 else -1
            }
            if (traveled >= horizonM) break
            // End of this line: continue on the road that turns least (bigger roads preferred), even a sharp bend
            val ex = exits(x, y, s)
            val best = ex.filter { it.second <= 110 }.minByOrNull { it.second + (it.first.first.cls - s.cls).coerceAtLeast(0) * 15.0 }
            for ((e, turn) in ex) if (e.first !== best?.first?.first && turn <= 150 && e.first !in branched) {
                branched += e.first; follow(e.first, e.third, e.second, 600.0, branches, p)
            }
            if (best == null) break
            cur = best.first.first; forward = best.first.third; curIdx = best.first.second
        }
        // 3) Past the loaded map: keep going straight
        while (traveled < horizonM) {
            val step = minOf(150.0, horizonM - traveled)
            x += hx * step; y += hy * step; traveled += step
            mark(x, y)
        }
        if (main.isEmpty() || traveled > 0) main += doubleArrayOf(p.lat(y), p.lon(x))
        return Prediction(main, branches)
    }

    /** First [lenM] of a side branch from vertex [start], as points every ~150 m. */
    private fun follow(s: Seg, forward: Boolean, start: Int, lenM: Double, out: MutableList<DoubleArray>, p: LocalProj) {
        val n = s.pts.size / 2
        var k = start
        var px = s.pts[k * 2]; var py = s.pts[k * 2 + 1]
        var d = 0.0; var nextMark = 150.0
        while (true) {
            k += if (forward) 1 else -1
            if (k !in 0 until n || d >= lenM) break
            val nx = s.pts[k * 2]; val ny = s.pts[k * 2 + 1]
            d += hypot(nx - px, ny - py); px = nx; py = ny
            if (d >= nextMark) { out += doubleArrayOf(p.lat(py), p.lon(px)); nextMark += 150 }
        }
        out += doubleArrayOf(p.lat(py), p.lon(px))
    }

    /** Tiles to preload for a prediction: each point's tile, plus the neighbor when within 300 m of an edge. */
    fun tiles(pred: Prediction, z: Int = 14): List<Tiles.TileId> {
        val out = LinkedHashSet<Tiles.TileId>()
        for (pt in pred.main) out += Tiles.around(pt[0], pt[1], 300.0, z)
        for (pt in pred.branches) out += Tiles.around(pt[0], pt[1], 150.0, z)
        return out.toList()
    }
}
