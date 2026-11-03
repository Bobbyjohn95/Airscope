package com.k.airscope

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Constant-velocity Kalman filter for GPS (state x, y, vx, vy in local meters).
 * Fuses position (with the fix's accuracy), speed and course; rejects outlier jumps; holds still
 * when you're stationary; keeps a short history so any radio reading can be tagged with where you
 * were at the exact moment it was received, and predicts between fixes so the map moves smoothly
 * at highway speed. Times are elapsedRealtime nanoseconds.
 */
class PositionFilter {
    class Fix(
        val lat: Double, val lon: Double, val accM: Double,
        val speed: Double?, val speedAcc: Double?, val bearing: Double?, val bearingAcc: Double?,
        val tNanos: Long,
    )

    class State(val tNanos: Long, val x: Double, val y: Double, val vx: Double, val vy: Double, val sigma: Double)

    class Pos(val lat: Double, val lon: Double, val accM: Double, val vx: Double, val vy: Double) {
        val speed: Double get() = hypot(vx, vy)
        /** Course over ground (deg) when moving, else null. */
        val course: Double? get() = if (speed > 1.5) (Math.toDegrees(Math.atan2(vx, vy)) + 360) % 360 else null
    }

    var proj: LocalProj? = null
        private set
    private var x = 0.0
    private var y = 0.0
    private var vx = 0.0
    private var vy = 0.0
    private val p = DoubleArray(16) // 4x4 covariance, row-major
    private var t = 0L
    private var rejects = 0
    // Raw fix-to-fix movement, to catch a GPS that reports speed 0 (or nothing) while you're clearly moving
    private var rawLat = 0.0
    private var rawLon = 0.0
    private var rawT = 0L
    private var rawVx = 0.0
    private var rawVy = 0.0
    private var rawVOk = false
    /** After the speed field was caught contradicting real movement, ignore it for a few seconds. */
    private var distrustSpeedUntil = 0L
    private val hist = ArrayDeque<State>()

    val initialized: Boolean get() = proj != null
    val lastNanos: Long get() = t

    fun reset() {
        proj = null; hist.clear(); rejects = 0
    }

    fun update(f: Fix) {
        val pr = proj
        if (pr == null || abs(pr.x(f.lon)) > 20_000 || abs(pr.y(f.lat)) > 20_000) {
            init(f)
            return
        }
        if (f.tNanos <= t) return
        val gap = (f.tNanos - t) / 1e9
        if (gap > 5.0) { // tunnel / underpass / indoors: don't trust a 5+ s dead-reckoned guess, restart here
            resume(f, pr)
            return
        }
        val dt = gap
        val zx = pr.x(f.lon)
        val zy = pr.y(f.lat)
        val sigma = max(f.accM * 0.75, 1.0)
        // Two fixes in a row moving the same way at the same speed = real movement, whatever the speed field says.
        val motion = rawMotion(f, pr)
        val badSpeed = motion != null && (f.speed == null || f.speed < 0.5 * hypot(motion.first, motion.second))
        if (badSpeed) distrustSpeedUntil = f.tNanos + 5_000_000_000L
        val distrust = f.tNanos < distrustSpeedUntil
        if (badSpeed && motion != null) {
            val mx = motion.first; val my = motion.second
            val gate = hypot(zx - (x + vx * dt), zy - (y + vy * dt))
            if (gate > 3 * sigma + 5) { // the filter was still assuming you were stopped: catch up in one step
                x = zx; y = zy; vx = mx; vy = my
                p.fill(0.0); p[0] = sigma * sigma; p[5] = sigma * sigma; p[10] = 9.0; p[15] = 9.0
                t = f.tNanos; rejects = 0
                pushHistory()
                return
            }
        }
        val moving = (if (distrust) null else f.speed ?: hypot(vx, vy))?.let { it > 2.0 } ?: true
        predict(dt, if (moving) 3.0 else 1.2)
        // Outlier gate on the position innovation
        val ix = zx - x; val iy = zy - y
        val sxx = p[0] + sigma * sigma; val syy = p[5] + sigma * sigma
        val maha = ix * ix / sxx + iy * iy / syy
        if (maha > 30.0 && rejects < 3) {
            rejects++
            t = f.tNanos
            pushHistory()
            return
        }
        if (maha > 30.0) { init(f); return } // the jump persisted: accept the new position
        rejects = 0
        updatePos(zx, zy, sigma)
        val sp = f.speed
        if (badSpeed && motion != null) {
            updateVel(motion.first, motion.second, 3.0)
        } else if (distrust) {
            // speed field not trusted right now: velocity comes from the positions alone
        } else if (sp != null) {
            if (sp < 0.4) {
                updateVel(0.0, 0.0, 0.35) // standing still: stop drift and wander
            } else if (f.bearing != null) {
                val b = Math.toRadians(f.bearing)
                val sa = f.speedAcc ?: 0.6
                val ba = Math.toRadians(f.bearingAcc ?: 6.0)
                updateVel(sp * sin(b), sp * cos(b), sqrt(sa * sa + (sp * ba) * (sp * ba)).coerceAtLeast(0.25))
            }
        }
        t = f.tNanos
        pushHistory()
    }

    /**
     * Velocity from the last two raw fixes, returned only when it agrees with the fix before
     * (same direction within 30°, speed within 35%, faster than 5 m/s, decent accuracy).
     * A one-off GPS jump never passes; steady travel does.
     */
    private fun rawMotion(f: Fix, pr: LocalProj): Pair<Double, Double>? {
        var out: Pair<Double, Double>? = null
        if (rawT > 0) {
            val rdt = (f.tNanos - rawT) / 1e9
            if (rdt in 0.2..3.0 && f.accM < 15) {
                val vx2 = (pr.x(f.lon) - pr.x(rawLon)) / rdt
                val vy2 = (pr.y(f.lat) - pr.y(rawLat)) / rdt
                val s2 = hypot(vx2, vy2)
                if (rawVOk) {
                    val s1 = hypot(rawVx, rawVy)
                    val cosA = if (s1 * s2 > 0) (vx2 * rawVx + vy2 * rawVy) / (s1 * s2) else -1.0
                    if (s2 > 5 && s1 > 5 && cosA > 0.866 && s2 / s1 in 0.65..1.35) out = vx2 to vy2
                }
                rawVx = vx2; rawVy = vy2; rawVOk = true
            } else rawVOk = false
        }
        rawLat = f.lat; rawLon = f.lon; rawT = f.tNanos
        return out
    }

    /** Restart at a fresh fix after a GPS gap, keeping the map projection and history (the gap stays a gap). */
    private fun resume(f: Fix, pr: LocalProj) {
        rawLat = f.lat; rawLon = f.lon; rawT = f.tNanos; rawVOk = false
        x = pr.x(f.lon); y = pr.y(f.lat)
        val b = f.bearing
        val s = f.speed
        if (b != null && s != null && s > 0.5) { vx = s * sin(Math.toRadians(b)); vy = s * cos(Math.toRadians(b)) }
        else if (s != null && s < 0.4) { vx = 0.0; vy = 0.0 }
        p.fill(0.0)
        val sg = max(f.accM * 0.75, 1.0)
        p[0] = sg * sg; p[5] = sg * sg; p[10] = 9.0; p[15] = 9.0
        t = f.tNanos
        rejects = 0
        pushHistory()
    }

    private fun init(f: Fix) {
        rawLat = f.lat; rawLon = f.lon; rawT = f.tNanos; rawVOk = false
        val pr = LocalProj(f.lat, f.lon)
        proj = pr
        x = 0.0; y = 0.0
        val b = f.bearing
        val s = f.speed ?: 0.0
        if (b != null && s > 0.5) { vx = s * sin(Math.toRadians(b)); vy = s * cos(Math.toRadians(b)) } else { vx = 0.0; vy = 0.0 }
        p.fill(0.0)
        val sg = max(f.accM * 0.75, 1.0)
        p[0] = sg * sg; p[5] = sg * sg; p[10] = 4.0; p[15] = 4.0
        t = f.tNanos
        rejects = 0
        hist.clear()
        pushHistory()
    }

    private fun predict(dt: Double, accelSigma: Double) {
        x += vx * dt; y += vy * dt
        // P = F P F^T + Q  (F = [[1,0,dt,0],[0,1,0,dt],[0,0,1,0],[0,0,0,1]])
        val n = DoubleArray(16)
        for (r in 0..3) for (c in 0..3) {
            var s = p[r * 4 + c]
            if (r < 2) s += dt * p[(r + 2) * 4 + c]
            n[r * 4 + c] = s
        }
        val m = DoubleArray(16)
        for (r in 0..3) for (c in 0..3) {
            var s = n[r * 4 + c]
            if (c < 2) s += dt * n[r * 4 + c + 2]
            m[r * 4 + c] = s
        }
        val q = accelSigma * accelSigma
        val dt2 = dt * dt; val dt3 = dt2 * dt / 2; val dt4 = dt2 * dt2 / 4
        m[0] += q * dt4; m[5] += q * dt4
        m[2] += q * dt3; m[8] += q * dt3; m[7] += q * dt3; m[13] += q * dt3
        m[10] += q * dt2; m[15] += q * dt2
        System.arraycopy(m, 0, p, 0, 16)
    }

    /** Measurement of state components (i, j) = (zi, zj) with equal variance r*r, independent axes. */
    private fun update2(i: Int, j: Int, zi: Double, zj: Double, r: Double) {
        val rr = r * r
        val s00 = p[i * 4 + i] + rr; val s01 = p[i * 4 + j]; val s11 = p[j * 4 + j] + rr
        val det = s00 * s11 - s01 * s01
        if (abs(det) < 1e-12) return
        val i00 = s11 / det; val i01 = -s01 / det; val i11 = s00 / det
        val st = doubleArrayOf(x, y, vx, vy)
        val yi = zi - st[i]; val yj = zj - st[j]
        val k = DoubleArray(8) // 4x2 gain
        for (r2 in 0..3) {
            val pi = p[r2 * 4 + i]; val pj = p[r2 * 4 + j]
            k[r2 * 2] = pi * i00 + pj * i01
            k[r2 * 2 + 1] = pi * i01 + pj * i11
        }
        for (r2 in 0..3) st[r2] += k[r2 * 2] * yi + k[r2 * 2 + 1] * yj
        x = st[0]; y = st[1]; vx = st[2]; vy = st[3]
        val np = DoubleArray(16)
        for (r2 in 0..3) for (c in 0..3) {
            np[r2 * 4 + c] = p[r2 * 4 + c] - (k[r2 * 2] * p[i * 4 + c] + k[r2 * 2 + 1] * p[j * 4 + c])
        }
        System.arraycopy(np, 0, p, 0, 16)
    }

    private fun updatePos(zx: Double, zy: Double, sigma: Double) = update2(0, 1, zx, zy, sigma)
    private fun updateVel(zvx: Double, zvy: Double, sigma: Double) = update2(2, 3, zvx, zvy, sigma)

    private fun sigmaNow() = sqrt(max(0.0, (p[0] + p[5]) / 2))

    private fun pushHistory() {
        hist.addLast(State(t, x, y, vx, vy, sigmaNow()))
        while (hist.size > 2 && t - hist.first().tNanos > 120_000_000_000L) hist.removeFirst()
    }

    /** Filtered position at time tNanos: interpolated within history, predicted up to 3 s ahead. */
    fun at(tNanos: Long): Pos? {
        val pr = proj ?: return null
        if (hist.isEmpty()) return null
        val last = hist.last()
        val sx: Double; val sy: Double; val svx: Double; val svy: Double; val sg: Double
        if (tNanos >= last.tNanos) {
            val full = (tNanos - last.tNanos) / 1e9
            val dt = full.coerceAtMost(3.0)
            sx = last.x + last.vx * dt; sy = last.y + last.vy * dt; svx = last.vx; svy = last.vy
            // Past 3 s we stop moving the guess, so the uncertainty has to grow with your speed instead.
            sg = last.sigma + 1.5 * dt + hypot(last.vx, last.vy) * (full - dt)
        } else {
            if (tNanos < hist.first().tNanos) return null
            var a = hist.first(); var b = last
            for (s in hist) { if (s.tNanos <= tNanos) a = s; if (s.tNanos >= tNanos) { b = s; break } }
            val span = (b.tNanos - a.tNanos).toDouble()
            val u = if (span <= 0) 0.0 else (tNanos - a.tNanos) / span
            sx = a.x + (b.x - a.x) * u; sy = a.y + (b.y - a.y) * u
            svx = a.vx + (b.vx - a.vx) * u; svy = a.vy + (b.vy - a.vy) * u
            // Between two fixes far apart in time (GPS gap) a straight line is only a guess: widen the error.
            val near = min(tNanos - a.tNanos, b.tNanos - tNanos) / 1e9
            sg = max(a.sigma, b.sigma) + if (span > 1.5e9) (1.5 + 0.5 * max(hypot(a.vx, a.vy), hypot(b.vx, b.vy))) * near else 0.0
        }
        return Pos(pr.lat(sy), pr.lon(sx), (sg * 1.33).coerceAtLeast(1.0), svx, svy)
    }

    fun current(): Pos? = if (hist.isEmpty()) null else at(hist.last().tNanos)

    /** Meters traveled between two times (along filtered history). */
    fun traveled(fromNanos: Long, toNanos: Long): Double {
        var d = 0.0
        var prev: State? = null
        for (s in hist) {
            if (s.tNanos < fromNanos || s.tNanos > toNanos) continue
            prev?.let { d += hypot(s.x - it.x, s.y - it.y) }
            prev = s
        }
        return d
    }

    companion object {
        fun minOfNullable(a: Double?, b: Double): Double = if (a == null) b else min(a, b)
    }
}
