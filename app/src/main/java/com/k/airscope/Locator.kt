package com.k.airscope

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Local flat-earth projection around an origin: x = meters east, y = meters north. */
class LocalProj(val lat0: Double, val lon0: Double) {
    private val k = Math.toRadians(1.0) * 6_371_000.0
    private val kx = k * cos(Math.toRadians(lat0))

    fun x(lon: Double) = (lon - lon0) * kx
    fun y(lat: Double) = (lat - lat0) * k
    fun lat(y: Double) = lat0 + y / k
    fun lon(x: Double) = lon0 + x / kx
}

/**
 * One measurement taken at a GPS position (local meters).
 * RSSI samples at the same spot are averaged; rttM is set for a Wi-Fi RTT (measured) distance.
 */
class Obs(val x: Double, val y: Double, var rssi: Double, var n: Int, var t: Long, val gpsErr: Double, val rttM: Double? = null, val rttStd: Double = 0.0)

/** Everything we know about where a device is. */
class LocTrack {
    var proj: LocalProj? = null
    val obs = ArrayList<Obs>()
    var dirty = false
    var located = false
    var estLat = 0.0
    var estLon = 0.0
    var errM = 0.0
    var spreadM = 0.0
    /** Learned signal level at 1 m (dBm) once the fit is good; null until then. */
    var p0: Double? = null
    var ambiguous = false
    /** When ambiguous: the other side's position and the un-inflated error radius. */
    var altLat: Double? = null
    var altLon: Double? = null
    var rawErrM = 0.0
    var usedObs = 0
    /** When the position was last estimated (ms), so every device gets its turn. */
    var estAt = 0L
}

/**
 * Finds a device on the map from measurements taken at different GPS positions.
 *
 * Model: rssi = P0 - 10·n·log10(distance) + noise, solved jointly for the device's x, y AND its
 * transmit level P0 (devices transmit at very different powers, which is the biggest error source
 * if assumed). A prior keeps P0 sensible; GPS error is converted into signal-strength error so
 * fuzzy fixes count less; Wi-Fi RTT distances are mixed in as precise range measurements.
 * Several starting points are tried so a walk along a road doesn't get stuck on the wrong side;
 * if both sides fit about equally well the device is reported as ambiguous instead of guessed.
 */
object Locator {
    const val MAX_OBS = 80
    private const val SAME_SPOT_M = 2.5
    private const val LN10 = 2.302585092994046

    fun add(tr: LocTrack, lat: Double, lon: Double, rssi: Double, t: Long, gpsErr: Double = 4.0) {
        val p = tr.proj ?: LocalProj(lat, lon).also { tr.proj = it }
        val x = p.x(lon); val y = p.y(lat)
        val last = tr.obs.lastOrNull { it.rttM == null }
        if (last != null && hypot(x - last.x, y - last.y) < SAME_SPOT_M && t - last.t < 20_000) {
            last.rssi = (last.rssi * last.n + rssi) / (last.n + 1)
            last.n = min(last.n + 1, 30)
            last.t = t
            tr.dirty = true
            return
        }
        tr.obs += Obs(x, y, rssi, 1, t, gpsErr)
        if (tr.obs.size > MAX_OBS) prune(tr.obs)
        tr.dirty = true
    }

    /** A measured Wi-Fi RTT distance from a GPS position. */
    fun addRtt(tr: LocTrack, lat: Double, lon: Double, meters: Double, std: Double, t: Long, gpsErr: Double) {
        val p = tr.proj ?: LocalProj(lat, lon).also { tr.proj = it }
        tr.obs += Obs(p.x(lon), p.y(lat), Double.NaN, 1, t, gpsErr, meters, std)
        if (tr.obs.size > MAX_OBS) prune(tr.obs)
        tr.dirty = true
    }

    /** Drop the sample that adds the least spatial information, keeping the newest few. */
    private fun prune(o: ArrayList<Obs>) {
        var worst = -1
        var worstD = Double.MAX_VALUE
        for (i in 0 until o.size - 6) {
            var nd = Double.MAX_VALUE
            for (j in o.indices) if (j != i) nd = min(nd, hypot(o[i].x - o[j].x, o[i].y - o[j].y))
            if (nd < worstD) { worstD = nd; worst = i }
        }
        if (worst >= 0) o.removeAt(worst)
    }

    private class Fit(val x: Double, val y: Double, val p0: Double, val cost: Double, val a: DoubleArray, val m: Int)

    /**
     * @param p0Prior expected RSSI at 1 m; @param p0Sigma how sure we are of it (dB)
     * @param windowMs only use measurements newer than this (devices that move), or null for all
     */
    fun estimate(tr: LocTrack, p0Prior: Double, p0Sigma: Double, env: Env, now: Long, windowMs: Long? = null) {
        tr.dirty = false
        tr.estAt = now
        // Results from an older fit must not survive an early return below
        tr.ambiguous = false; tr.altLat = null; tr.altLon = null
        val p = tr.proj ?: return
        val o = if (windowMs == null) tr.obs else tr.obs.filter { now - it.t <= windowMs }
        tr.usedObs = o.size
        val n = o.size
        var spread = 0.0
        for (i in 0 until n) for (j in i + 1 until n) spread = max(spread, hypot(o[i].x - o[j].x, o[i].y - o[j].y))
        tr.spreadM = spread
        val rssiObs = o.filter { it.rttM == null }
        val hasRtt = o.any { it.rttM != null }
        if (n < 5 || (rssiObs.size < 4 && !hasRtt)) { tr.located = false; return }
        val nExp = env.n
        fun dist(rssi: Double, p0: Double) = Distance.meters(p0, rssi, env)
        val strongest = rssiObs.maxByOrNull { it.rssi }
        val dNear = strongest?.let { dist(it.rssi, p0Prior) } ?: (o.mapNotNull { it.rttM }.minOrNull() ?: 20.0)
        if (spread < max(10.0, 0.45 * dNear)) { tr.located = false; return }

        // ---- starting points
        val starts = ArrayList<DoubleArray>()
        run { // power-weighted centroid (pulled toward where it was strongest)
            var sx = 0.0; var sy = 0.0; var sw = 0.0
            for (s in rssiObs) { val w = Math.pow(10.0, (s.rssi + 100) / 10.0); sx += s.x * w; sy += s.y * w; sw += w }
            if (sw > 0) starts += doubleArrayOf(sx / sw, sy / sw)
        }
        val byStrength = rssiObs.sortedByDescending { it.rssi }.take(3)
        for (s in byStrength) {
            starts += doubleArrayOf(s.x, s.y)
            // Path direction around this sample -> try both sides of the path at the estimated distance
            val idx = o.indexOf(s)
            val a = o.getOrNull(idx - 1) ?: s; val b = o.getOrNull(idx + 1) ?: s
            var dx = b.x - a.x; var dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len > 1) {
                dx /= len; dy /= len
                val d = dist(s.rssi, p0Prior).coerceIn(2.0, 300.0)
                starts += doubleArrayOf(s.x - dy * d, s.y + dx * d)
                starts += doubleArrayOf(s.x + dy * d, s.y - dx * d)
            }
        }

        val first = starts.mapNotNull { st -> solve(o, st[0], st[1], p0Prior, p0Sigma, env, nExp) }.sortedBy { it.cost }
        if (first.isEmpty()) { tr.located = false; return }
        // A loop-shaped path can't tell a point from its mirror (circle inversion) when the transmit
        // power is unknown, so always try the mirror of the best answer as well.
        val cx = o.sumOf { it.x } / n; val cy = o.sumOf { it.y } / n
        val r2 = o.sumOf { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) } / n
        val b0 = first[0]
        val vx = b0.x - cx; val vy = b0.y - cy; val vv = vx * vx + vy * vy
        val mirror = if (vv > 1.0) solve(o, cx + vx * r2 / vv, cy + vy * r2 / vv, p0Prior, p0Sigma, env, nExp) else null
        val fits = (first + listOfNotNull(mirror)).sortedBy { it.cost }
        val best = fits[0]

        // ---- uncertainty from the (x, y) block of the inverse normal matrix, scaled by fit quality
        val a = best.a
        val inv = invert3(a)
        val dof = max(1, best.m - 3)
        val scale = max(1.0, best.cost / dof)
        var err: Double
        if (inv == null) err = 500.0 else {
            val cxx = inv[0] * scale; val cyy = inv[4] * scale; val cxy = inv[1] * scale
            val tr2 = (cxx + cyy) / 2
            val lam = tr2 + sqrt(max(0.0, (cxx - cyy) * (cxx - cyy) / 4 + cxy * cxy))
            err = 2.0 * sqrt(max(lam, 0.0)) // ~95% radius along the worst axis
        }
        err += o.map { it.gpsErr }.average() * 0.3
        err = err.coerceIn(2.0, 500.0)

        // ---- ambiguity: a clearly different solution that fits nearly as well
        tr.ambiguous = false
        tr.altLat = null; tr.altLon = null
        tr.rawErrM = err
        for (f in fits.drop(1)) {
            val sep = hypot(f.x - best.x, f.y - best.y)
            // Likelihood test: delta chi-square (normalized by fit quality) under ~1.5 = no real preference
            if (sep > max(err, 8.0) && (f.cost - best.cost) / scale < 3.0) {
                tr.ambiguous = true
                tr.altLat = p.lat(f.y); tr.altLon = p.lon(f.x)
                err = max(err, sep / 2 + err / 2)
                break
            }
        }

        tr.errM = err
        val medianD = rssiObs.map { dist(it.rssi, best.p0) }.sorted().let { if (it.isEmpty()) dNear else it[it.size / 2] }
        // Two equally good spots (e.g. a point and its mirror across your path): don't guess.
        // The engine may still pick one using the street map (Wi-Fi gear sits inside buildings).
        tr.located = !tr.ambiguous && err <= max(18.0, 0.55 * medianD)
        tr.p0 = if (!tr.ambiguous && err < 25 && rssiObs.size >= 6) best.p0 else null
        tr.estLat = p.lat(best.y)
        tr.estLon = p.lon(best.x)
    }

    /** Levenberg-Marquardt over (x, y, P0). Returns null if it diverges. */
    private fun solve(o: List<Obs>, x0: Double, y0: Double, p0Prior: Double, p0Sigma: Double, env: Env, nExp: Double): Fit? {
        var x = x0; var y = y0; var p0 = p0Prior
        var lambda = 1e-2
        val sShadow2 = env.sigma * env.sigma

        fun residuals(xx: Double, yy: Double, pp: Double, jac: DoubleArray?, out: DoubleArray?): Double {
            var cost = 0.0
            if (jac != null) jac.fill(0.0)
            if (out != null) out.fill(0.0)
            for (s in o) {
                val dx = xx - s.x; val dy = yy - s.y
                val d = max(hypot(dx, dy), 1.0)
                val ux = dx / d; val uy = dy / d
                val r: Double; val jx: Double; val jy: Double; val jp: Double
                if (s.rttM != null) {
                    val sg = sqrt(max(s.rttStd, 0.8) * max(s.rttStd, 0.8) + s.gpsErr * s.gpsErr * 0.5)
                    r = (d - s.rttM) / sg
                    jx = ux / sg; jy = uy / sg; jp = 0.0
                } else {
                    val gpsDb = 10 * nExp / LN10 * s.gpsErr * 0.75 / max(d, 3.0)
                    val sg = sqrt(sShadow2 * (0.45 + 0.55 / min(s.n, 8)) + gpsDb * gpsDb)
                    val model = pp - 10 * nExp * log10(d) + 0.0
                    r = (s.rssi + env.extraLoss - model) / sg
                    val dm = -10 * nExp / (LN10 * d) // d model / d distance
                    jx = -dm * ux / sg; jy = -dm * uy / sg; jp = -1.0 / sg
                }
                cost += r * r
                if (jac != null && out != null) accumulate(jac, out, jx, jy, jp, r)
            }
            // Prior on P0
            val rp = (pp - p0Prior) / p0Sigma
            cost += rp * rp
            if (jac != null && out != null) accumulate(jac, out, 0.0, 0.0, 1.0 / p0Sigma, rp)
            return cost
        }

        val a = DoubleArray(9); val g = DoubleArray(3)
        var cost = residuals(x, y, p0, a, g)
        for (iter in 0 until 60) {
            val m = a.copyOf()
            for (k in 0..2) m[k * 4] = a[k * 4] * (1 + lambda) + 1e-9
            val step = solve3(m, doubleArrayOf(-g[0], -g[1], -g[2])) ?: break
            val nx = x + step[0]; val ny = y + step[1]; val np = (p0 + step[2]).coerceIn(-110.0, 10.0)
            val nc = residuals(nx, ny, np, null, null)
            if (nc < cost) {
                x = nx; y = ny; p0 = np; cost = nc
                lambda = max(lambda / 3, 1e-7)
                residuals(x, y, p0, a, g)
                if (hypot(step[0], step[1]) < 0.02 && abs(step[2]) < 0.01) break // converged
            } else {
                lambda = min(lambda * 5, 1e7)
            }
        }
        if (x.isNaN() || y.isNaN() || hypot(x - x0, y - y0) > 3000) return null
        residuals(x, y, p0, a, g)
        return Fit(x, y, p0, cost, a.copyOf(), o.size + 1)
    }

    private fun accumulate(a: DoubleArray, g: DoubleArray, jx: Double, jy: Double, jp: Double, r: Double) {
        val j = doubleArrayOf(jx, jy, jp)
        for (i in 0..2) {
            g[i] += j[i] * r
            for (k in 0..2) a[i * 3 + k] += j[i] * j[k]
        }
    }

    private fun solve3(m: DoubleArray, b: DoubleArray): DoubleArray? {
        val inv = invert3(m) ?: return null
        return DoubleArray(3) { i -> inv[i * 3] * b[0] + inv[i * 3 + 1] * b[1] + inv[i * 3 + 2] * b[2] }
    }

    private fun invert3(m: DoubleArray): DoubleArray? {
        val (a, b, c) = Triple(m[0], m[1], m[2])
        val (d, e, f) = Triple(m[3], m[4], m[5])
        val (g, h, i) = Triple(m[6], m[7], m[8])
        val co0 = e * i - f * h; val co1 = -(d * i - f * g); val co2 = d * h - e * g
        val det = a * co0 + b * co1 + c * co2
        if (abs(det) < 1e-14) return null
        return doubleArrayOf(
            co0 / det, -(b * i - c * h) / det, (b * f - c * e) / det,
            co1 / det, (a * i - c * g) / det, -(a * f - c * d) / det,
            co2 / det, -(a * h - b * g) / det, (a * e - b * d) / det,
        )
    }

    /** Natural log helper kept for callers converting between dB and power. */
    fun lnPow(db: Double) = db / 10 * ln(10.0)
}
