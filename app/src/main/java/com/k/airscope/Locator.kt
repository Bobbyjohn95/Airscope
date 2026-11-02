package com.k.airscope

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
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

/** One signal-strength sample taken at a GPS position (local meters). Same-spot samples are averaged. */
class Obs(val x: Double, val y: Double, var rssi: Double, var n: Int, val t: Long)

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
}

/**
 * Finds a device on the map from signal strength measured at different GPS positions
 * (the same idea wardriving tools use). Standing still can't reveal direction, so a device is
 * only "located" once you've taken samples from spots far enough apart.
 */
object Locator {
    const val MAX_OBS = 60
    private const val SAME_SPOT_M = 2.5

    fun add(tr: LocTrack, lat: Double, lon: Double, rssi: Double, t: Long) {
        val p = tr.proj ?: LocalProj(lat, lon).also { tr.proj = it }
        val x = p.x(lon)
        val y = p.y(lat)
        val last = tr.obs.lastOrNull()
        if (last != null && hypot(x - last.x, y - last.y) < SAME_SPOT_M) {
            last.rssi = (last.rssi * last.n + rssi) / (last.n + 1)
            last.n = min(last.n + 1, 20)
            tr.dirty = true
            return
        }
        tr.obs += Obs(x, y, rssi, 1, t)
        if (tr.obs.size > MAX_OBS) prune(tr.obs)
        tr.dirty = true
    }

    /** Drop the sample that adds the least spatial information (closest to another), keeping the newest few. */
    private fun prune(o: ArrayList<Obs>) {
        var worst = -1
        var worstD = Double.MAX_VALUE
        for (i in 0 until o.size - 5) {
            var nd = Double.MAX_VALUE
            for (j in o.indices) if (j != i) nd = min(nd, hypot(o[i].x - o[j].x, o[i].y - o[j].y))
            if (nd < worstD) { worstD = nd; worst = i }
        }
        if (worst >= 0) o.removeAt(worst)
    }

    /** Re-estimates position. ref = expected RSSI at 1 m for this device. */
    fun estimate(tr: LocTrack, ref: Double, env: Env) {
        tr.dirty = false
        val o = tr.obs
        val p = tr.proj ?: return
        val n = o.size
        var spread = 0.0
        for (i in 0 until n) for (j in i + 1 until n) spread = max(spread, hypot(o[i].x - o[j].x, o[i].y - o[j].y))
        tr.spreadM = spread
        if (n < 5) { tr.located = false; return }
        val d = DoubleArray(n) { Distance.meters(ref, o[it].rssi, env) }
        val dMin = d.min()
        // Need to have moved a meaningful amount relative to how far away it is.
        if (spread < max(10.0, 0.5 * dMin)) { tr.located = false; return }

        // Sigma model: RSSI ranging error grows with distance. Averaged samples are more trustworthy.
        val w = DoubleArray(n) { val s = 0.45 * d[it] + 2.0; o[it].n.coerceAtMost(6) / (s * s) }

        // Start at the inverse-distance-weighted centroid of the sample positions.
        var px = 0.0; var py = 0.0; var sw = 0.0
        for (i in 0 until n) { val ww = 1.0 / (d[i] * d[i] + 1); px += o[i].x * ww; py += o[i].y * ww; sw += ww }
        px /= sw; py /= sw

        // Levenberg-Marquardt on sum w_i (|p - s_i| - d_i)^2
        var lambda = 1e-3
        var a11 = 0.0; var a12 = 0.0; var a22 = 0.0
        fun cost(x: Double, y: Double): Double {
            var c = 0.0
            for (i in 0 until n) { val r = hypot(x - o[i].x, y - o[i].y) - d[i]; c += w[i] * r * r }
            return c
        }
        var c0 = cost(px, py)
        repeat(40) {
            a11 = 0.0; a12 = 0.0; a22 = 0.0
            var b1 = 0.0; var b2 = 0.0
            for (i in 0 until n) {
                val dx = px - o[i].x; val dy = py - o[i].y
                val r = max(hypot(dx, dy), 0.5)
                val res = r - d[i]
                val jx = dx / r; val jy = dy / r
                a11 += w[i] * jx * jx; a12 += w[i] * jx * jy; a22 += w[i] * jy * jy
                b1 += w[i] * jx * res; b2 += w[i] * jy * res
            }
            val m11 = a11 * (1 + lambda) + 1e-9; val m22 = a22 * (1 + lambda) + 1e-9
            val det = m11 * m22 - a12 * a12
            if (abs(det) < 1e-12) return@repeat
            val sx = -(m22 * b1 - a12 * b2) / det
            val sy = -(-a12 * b1 + m11 * b2) / det
            val nx = px + sx; val ny = py + sy
            val c1 = cost(nx, ny)
            if (c1 < c0) {
                px = nx; py = ny; c0 = c1; lambda = max(lambda / 3, 1e-6)
                if (hypot(sx, sy) < 0.05) return@repeat
            } else {
                lambda = min(lambda * 4, 1e6)
            }
        }

        // Uncertainty: covariance from the normal matrix, scaled by the fit quality, plus GPS error.
        val sumW = w.sum()
        val rms = sqrt(c0 / sumW)
        val det = a11 * a22 - a12 * a12
        val covTrace = if (det > 1e-12) (a11 + a22) / det else Double.MAX_VALUE
        var err = sqrt(covTrace * max(1.0, (rms * rms) / 4.0)) + 4.0
        // Samples on (nearly) a straight line can't tell which side of the line it's on.
        val e = collinearity(o)
        if (e < 0.15) err = max(err, dMin * 1.3)
        err = min(err, 500.0)
        tr.errM = err
        tr.located = err < max(25.0, dMin * 0.8)
        tr.estLat = p.lat(py)
        tr.estLon = p.lon(px)
    }

    /** Ratio of the minor to major spread of the sample positions (0 = a straight line). */
    private fun collinearity(o: List<Obs>): Double {
        val mx = o.sumOf { it.x } / o.size
        val my = o.sumOf { it.y } / o.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (s in o) { sxx += (s.x - mx) * (s.x - mx); syy += (s.y - my) * (s.y - my); sxy += (s.x - mx) * (s.y - my) }
        val tr = sxx + syy
        val disc = sqrt(max(0.0, (sxx - syy) * (sxx - syy) / 4 + sxy * sxy))
        val l1 = tr / 2 + disc
        val l2 = tr / 2 - disc
        return if (l1 <= 1e-9) 0.0 else sqrt(max(0.0, l2) / l1)
    }
}
