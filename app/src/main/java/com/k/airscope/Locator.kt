package com.k.airscope

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
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
class Obs(
    val x: Double, val y: Double, var rssi: Double, var n: Int, var t: Long, val gpsErr: Double,
    val rttM: Double? = null, val rttStd: Double = 0.0,
    /** Path-loss exponent and extra loss (car body) in effect when this was measured. */
    val pathN: Double = 2.6, val loss: Double = 0.0,
)

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
    /** Shown position: smoothed and held steady so the marker doesn't jitter or flicker between fixes. */
    var shownLat = 0.0
    var shownLon = 0.0
    var shownErr = 0.0
    var shownSince = 0L
    var lastGoodAt = 0L
    /** When the device was last heard at all (ms). */
    var lastHeardAt = 0L
    /** Counts measurements taken from a new place (not repeats where you stand); copy taken at the last good fix. */
    var spotSeq = 0
    var spotSeqAtGood = -1
    /** Where the last measurement was taken (local m), to tell a new place from the same one. */
    var lastSpotX = Double.NaN
    var lastSpotY = Double.NaN
    /** Whether the fit uses only recent readings (devices that can move): no long holds for those. */
    var windowed = false
    /** The latest raw fit (before smoothing / holding), for callers that weigh it against the other candidate. */
    var fitLat = 0.0
    var fitLon = 0.0
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
    /** The readings only make sense if the device itself was moving: there's no fixed spot to show. */
    var moving = false
    /** When "moving" was first concluded for a device that was on the map (0 = not currently), and last seen. */
    var movingSince = 0L
    var lastMovingAt = 0L
    /** Recent estimates (time ms, x, y, error m), for checking that a device that can move stayed put. */
    val history = ArrayDeque<DoubleArray>()
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
    /** Readings closer than this share the same shadowing (count as one independent reading). */
    private const val CORR_M = 8L
    /** Typical slow shadowing + fading spread of a single averaged reading (dB). */
    private const val SHADOW_DB = 5.5
    /** How far a device's path-loss exponent may differ from the environment's (1 sigma). */
    private const val DN_SIGMA = 0.35
    /** Measured: the raw ~95% radius still comes out too small with real-world shadowing; this makes it honest. */
    private const val CALIBRATION = 1.7
    /** Below this error radius a fix is always good enough to show. */
    private const val MIN_OK_ERR = 25.0
    /** Above this a "spot" isn't useful on a street map: show distance only. */
    private const val MAX_OK_ERR = 45.0
    /** How long a good fix stays on the map while newer estimates are momentarily poor. */
    private const val HOLD_MS = 30_000L
    /** ...and while the device is still being heard: the spot was good and nothing says it moved. */
    private const val HOLD_HEARD_MS = 90_000L
    /** ...and while you have not moved since the good fix either (nothing new to learn from), up to this. */
    private const val HOLD_STILL_MS = 10 * 60_000L
    private const val HEARD_RECENTLY_MS = 20_000L
    /**
     * A device that is on the map has to look "moving" for this long before its marker is taken down.
     * One estimate taken while a wall or a passing truck weakens the signal looks exactly like "it drove off";
     * a device that really moves keeps looking that way.
     */
    private const val MOVING_CONFIRM_MS = 12_000L
    /** Moving verdicts further apart than this are separate events, not one persisting one. */
    private const val MOVING_LINK_MS = 30_000L

    fun add(tr: LocTrack, lat: Double, lon: Double, rssi: Double, t: Long, gpsErr: Double = 4.0, env: Env = Env.INDOOR) {
        val p = tr.proj ?: LocalProj(lat, lon).also { tr.proj = it }
        val x = p.x(lon); val y = p.y(lat)
        heardAt(tr, x, y, t)
        val last = tr.obs.lastOrNull { it.rttM == null }
        if (last != null && hypot(x - last.x, y - last.y) < SAME_SPOT_M && t - last.t < 20_000 &&
            last.pathN == env.n && last.loss == env.extraLoss
        ) {
            last.rssi = (last.rssi * last.n + rssi) / (last.n + 1)
            last.n = min(last.n + 1, 30)
            last.t = t
            tr.dirty = true
            return
        }
        tr.obs += Obs(x, y, rssi, 1, t, gpsErr, pathN = env.n, loss = env.extraLoss)
        if (tr.obs.size > MAX_OBS) prune(tr.obs)
        tr.dirty = true
    }

    /** A measured Wi-Fi RTT distance from a GPS position. */
    fun addRtt(tr: LocTrack, lat: Double, lon: Double, meters: Double, std: Double, t: Long, gpsErr: Double) {
        val p = tr.proj ?: LocalProj(lat, lon).also { tr.proj = it }
        heardAt(tr, p.x(lon), p.y(lat), t)
        tr.obs += Obs(p.x(lon), p.y(lat), Double.NaN, 1, t, gpsErr, meters, std)
        if (tr.obs.size > MAX_OBS) prune(tr.obs)
        tr.dirty = true
    }

    /** Note a reading: when it was taken and whether from a new place (more than a couple of metres away). */
    private fun heardAt(tr: LocTrack, x: Double, y: Double, t: Long) {
        tr.lastHeardAt = t
        if (tr.lastSpotX.isNaN() || hypot(x - tr.lastSpotX, y - tr.lastSpotY) >= SAME_SPOT_M) {
            tr.spotSeq++; tr.lastSpotX = x; tr.lastSpotY = y
        }
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

    /** cxx/cyy/cxy: position covariance (unscaled). dn: learned path-loss offset. vx/vy: device velocity (motion model). */
    private class Fit(
        val x: Double, val y: Double, val p0: Double, val cost: Double,
        val cxx: Double, val cyy: Double, val cxy: Double, val m: Int,
        val dn: Double = 0.0, val vx: Double = 0.0, val vy: Double = 0.0,
    )

    /** Result of fitting one set of readings: best spot, its ~95% error radius, and a rival spot if one fits as well. */
    private class Solved(val best: Fit, val err: Double, val rival: Fit?, val scale: Double)

    /**
     * @param p0Prior expected RSSI at 1 m; @param p0Sigma how sure we are of it (dB)
     * @param env kept for callers; each reading carries the distance model that applied when it was taken
     * @param windowMs only use measurements newer than this (devices that move), or null for all
     */
    fun estimate(tr: LocTrack, p0Prior: Double, p0Sigma: Double, env: Env, now: Long, windowMs: Long? = null) {
        tr.dirty = false
        tr.estAt = now
        // Results from an older fit must not survive an early return below
        tr.ambiguous = false; tr.altLat = null; tr.altLon = null; tr.moving = false
        tr.windowed = windowMs != null
        val p = tr.proj ?: return
        val o = if (windowMs == null) tr.obs else tr.obs.filter { now - it.t <= windowMs }
        tr.usedObs = o.size
        val n = o.size
        var spread = 0.0
        for (i in 0 until n) for (j in i + 1 until n) spread = max(spread, hypot(o[i].x - o[j].x, o[i].y - o[j].y))
        tr.spreadM = spread
        val rssiObs = o.filter { it.rttM == null }
        val hasRtt = o.any { it.rttM != null }
        // Too little to go on right now: a spot that was good stays on the map for a while (settle's hold)
        if (n < 5 || (rssiObs.size < 4 && !hasRtt)) { settle(tr, false, 0.0, 0.0, 0.0, now); return }
        val strongest = rssiObs.maxByOrNull { it.rssi }
        val dNear = strongest?.let { dist(it, p0Prior) } ?: (o.mapNotNull { it.rttM }.minOrNull() ?: 20.0)
        if (spread < max(10.0, 0.45 * dNear)) { settle(tr, false, 0.0, 0.0, 0.0, now); return }

        val sol = solveAll(o, p0Prior, p0Sigma) ?: run { settle(tr, false, 0.0, 0.0, 0.0, now); return }
        val best = sol.best
        var err = sol.err
        tr.rawErrM = err * CALIBRATION
        if (sol.rival != null) {
            val f = sol.rival
            tr.ambiguous = true
            tr.altLat = p.lat(f.y); tr.altLon = p.lon(f.x)
            err = max(err, hypot(f.x - best.x, f.y - best.y) / 2 + err / 2)
        }

        // Did the device move while you measured it? Compare "fixed spot" with "moving at a steady velocity":
        // if letting it move explains the readings far better, it was moving and there is no spot to show.
        if (n >= 10) {
            val tMid = (o.minOf { it.t } + o.maxOf { it.t }) / 2
            val mv = lm(o, best.x, best.y, best.p0, p0Prior, p0Sigma, motion = true, tMid = tMid, dn0 = best.dn)
            if (mv != null) {
                val speed = hypot(mv.vx, mv.vy)
                val gain = (best.cost - mv.cost) / sol.scale
                if (speed > 0.5 && gain > 12) tr.moving = true
            }
        }

        // How much of the compass around the device you measured it from: from one side only,
        // distance (and so position) is poorly pinned down whatever the math says.
        val coverage = angularCoverage(o, best.x, best.y)
        if (coverage < 90) err *= 1 + (90 - coverage) / 60

        // Measured along an almost straight line (driving past): which side of the road, and how far from it,
        // can't be told apart from signal strength. Show both sides instead of guessing.
        val straight = straightness(o) < 0.12 && !hasRtt
        if (straight && !tr.ambiguous) {
            val (mx, my) = mirrorAcrossPath(o, best.x, best.y)
            if (hypot(mx - best.x, my - best.y) > 8) {
                tr.ambiguous = true
                tr.altLat = p.lat(my); tr.altLon = p.lon(mx)
                err = max(err, hypot(mx - best.x, my - best.y) / 2 + err / 2)
            }
        }

        tr.errM = (err * CALIBRATION).coerceIn(3.0, 500.0)
        val medianD = rssiObs.map { dist(it, best.p0) }.sorted().let { if (it.isEmpty()) dNear else it[it.size / 2] }
        // A model that doesn't fit (scale >> 1) means something we don't model is going on (moving, odd antenna).
        val fitsWell = sol.scale < 3.5
        // The signal has to change as you move (and be reasonably strong) for its position to be knowable:
        // a weak, flat signal fits "weak and close" as well as "strong and far away".
        val levels = rssiObs.map { it.rssi + it.loss }.sorted()
        val informative = hasRtt || (levels.isNotEmpty() && levels.last() >= -90 &&
            levels[(levels.size * 9) / 10] - levels[levels.size / 10] >= 6.0)
        var ok = !tr.ambiguous && !tr.moving && fitsWell && informative && coverage >= 40 &&
            tr.errM <= min(MAX_OK_ERR, max(MIN_OK_ERR, 0.8 * medianD))

        // Devices that can move (phones, wearables, trackers, cars) must hold still: two estimates at least
        // 45 s apart have to agree before a spot is shown, otherwise it's "moving" (distance only).
        if (windowMs != null) {
            while (tr.history.size > 8) tr.history.removeFirst()
            val older = tr.history.lastOrNull { now - it[0] >= 45_000 }
            // Not heard in the last 20 s: it may have walked or driven off, so its old spot isn't shown
            if (now - o.maxOf { it.t } > 20_000) ok = false
            // Latest readings much weaker than this spot predicts from where you are now: it has moved away
            val recent = rssiObs.filter { it.t >= now - 15_000 }
            // Only heard now and then (a lucky reading from far away): not "here" any more
            if (recent.size < 3 && o.none { it.rttM != null && it.t >= now - 15_000 }) ok = false
            if (recent.size >= 2) {
                val resid = recent.map { s ->
                    val d = max(hypot(best.x - s.x, best.y - s.y), 1.0)
                    s.rssi + s.loss - (best.p0 - 10 * (s.pathN + best.dn) * log10(d))
                }.sorted()
                if (resid[resid.size / 2] < -10) { ok = false; tr.moving = true }
            }
            if (ok) {
                if (older == null) ok = false
                else if (hypot(older[1] - best.x, older[2] - best.y) > max(12.0, 0.5 * (older[3] + tr.errM))) { ok = false; tr.moving = true }
            }
            // Only estimates sharp enough to tell "moved" from "noise" are kept for the comparison
            if (!tr.ambiguous && fitsWell && tr.errM <= MAX_OK_ERR) tr.history.addLast(doubleArrayOf(now.toDouble(), best.x, best.y, tr.errM))
        }
        tr.p0 = if (ok && tr.errM < 25 && rssiObs.size >= 6) best.p0 else null
        val rawLat = p.lat(best.y); val rawLon = p.lon(best.x)
        tr.estLat = rawLat; tr.estLon = rawLon
        tr.fitLat = rawLat; tr.fitLon = rawLon
        settle(tr, ok, rawLat, rawLon, tr.errM, now)
    }

    /**
     * Hysteresis + smoothing for what the map shows. A fix that was good stays shown for up to 30 s while the
     * next estimates wobble; the shown spot moves toward a new estimate only as far as the error circles justify,
     * so a marker never jumps and never blinks on and off between readings.
     */
    fun settlePublic(tr: LocTrack, ok: Boolean, lat: Double, lon: Double, err: Double, now: Long) = settle(tr, ok, lat, lon, err, now)

    private fun settle(tr: LocTrack, ok: Boolean, lat: Double, lon: Double, err: Double, now: Long) {
        // "Moving" only cancels the hold once it has persisted; a one-off verdict is treated as a poor estimate.
        // Verdicts count as one persisting event while they come at most MOVING_LINK_MS apart (estimates in
        // between may say nothing at all, e.g. too few readings); a good fix clears it.
        if (tr.moving && tr.located) {
            if (tr.movingSince == 0L || now - tr.lastMovingAt > MOVING_LINK_MS) tr.movingSince = now
            tr.lastMovingAt = now
        }
        if (ok || !tr.located) tr.movingSince = 0L
        val movingConfirmed = tr.moving && (!tr.located || now - tr.movingSince >= MOVING_CONFIRM_MS)
        if (ok) {
            if (!tr.located || tr.shownSince == 0L) {
                tr.shownLat = lat; tr.shownLon = lon; tr.shownErr = err; tr.shownSince = now
            } else {
                val d = Geo.distanceM(tr.shownLat, tr.shownLon, lat, lon)
                // Weight by relative confidence; a move smaller than the noise is mostly ignored
                val w = (err * err).let { e2 -> val s2 = tr.shownErr * tr.shownErr; (s2 / (s2 + e2)).coerceIn(0.08, 0.6) }
                val gain = if (d < 0.5 * (tr.shownErr + err)) w * 0.5 else w
                tr.shownLat += (lat - tr.shownLat) * gain; tr.shownLon += (lon - tr.shownLon) * gain
                tr.shownErr += (err - tr.shownErr) * 0.3
            }
            tr.lastGoodAt = now
            tr.spotSeqAtGood = tr.spotSeq
            tr.located = true
        } else if (tr.located && !movingConfirmed && now - tr.lastGoodAt < holdMs(tr, lat, lon, err, now)) {
            // keep showing the last good spot; let its circle grow a little to say "not refreshed"
            // (not while standing still: nothing new was measured, so nothing got less certain)
            if (tr.spotSeq != tr.spotSeqAtGood) tr.shownErr = minOf(tr.shownErr * 1.05, MAX_OK_ERR * 1.3)
        } else {
            tr.located = false
            tr.shownSince = 0L
        }
        if (tr.located) { tr.estLat = tr.shownLat; tr.estLon = tr.shownLon; tr.errM = tr.shownErr }
    }

    /**
     * How long a good spot survives poor estimates. Devices that can move (phones, wearables, trackers, cars:
     * fitted on recent readings only) get 30 s, full stop: their old spot goes stale. Fixed gear gets 90 s while
     * it is still being heard and the latest (poor) fit does not land somewhere else entirely, and up to 10 min
     * when you have not moved since the good fix either: standing still, nothing new is learned about where it
     * is, so a persistent "it moved" verdict is the only honest reason to take it down.
     * err <= 0 means "no fit at all" (too few readings).
     */
    private fun holdMs(tr: LocTrack, lat: Double, lon: Double, err: Double, now: Long): Long {
        if (tr.windowed || now - tr.lastHeardAt >= HEARD_RECENTLY_MS) return HOLD_MS
        // A fit that found a second candidate spot is a reason to doubt the shown one, not to keep it
        val agrees = err <= 0.0 || (!tr.ambiguous && err <= 2 * MAX_OK_ERR && Geo.distanceM(tr.shownLat, tr.shownLon, lat, lon) <= tr.shownErr)
        if (!agrees) return HOLD_MS
        return if (tr.spotSeq == tr.spotSeqAtGood) HOLD_STILL_MS else HOLD_HEARD_MS
    }

    /** Minor/major spread of the measurement spots: ~0 for a straight line, ~1 for a loop. */
    private fun straightness(o: List<Obs>): Double {
        val n = o.size
        val mx = o.sumOf { it.x } / n; val my = o.sumOf { it.y } / n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (s in o) { val dx = s.x - mx; val dy = s.y - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val tr2 = (sxx + syy) / 2
        val dd = sqrt(max(0.0, (sxx - syy) * (sxx - syy) / 4 + sxy * sxy))
        val l1 = tr2 + dd; val l2 = max(0.0, tr2 - dd)
        return if (l1 <= 0) 0.0 else sqrt(l2 / l1)
    }

    /** Reflect (x, y) across the best-fit line through the measurement spots. */
    private fun mirrorAcrossPath(o: List<Obs>, x: Double, y: Double): Pair<Double, Double> {
        val n = o.size
        val mx = o.sumOf { it.x } / n; val my = o.sumOf { it.y } / n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (s in o) { val dx = s.x - mx; val dy = s.y - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val ang = 0.5 * atan2(2 * sxy, sxx - syy)
        val ux = cos(ang); val uy = kotlin.math.sin(ang)
        val px = x - mx; val py = y - my
        val along = px * ux + py * uy
        return (mx + 2 * along * ux - px) to (my + 2 * along * uy - py)
    }

    private fun dist(s: Obs, p0: Double): Double =
        10.0.pow((p0 - (s.rssi + s.loss)) / (10 * s.pathN)).coerceIn(0.5, 500.0)

    /** Largest arc (deg, 0..360) of directions from (x, y) toward the measurement spots that contains them all. */
    private fun angularCoverage(o: List<Obs>, x: Double, y: Double): Double {
        val angles = o.map { Math.toDegrees(atan2(it.x - x, it.y - y)) }.map { (it + 360) % 360 }.sorted()
        if (angles.size < 2) return 0.0
        var maxGap = 360 - angles.last() + angles.first()
        for (i in 1 until angles.size) maxGap = max(maxGap, angles[i] - angles[i - 1])
        return 360 - maxGap
    }

    /** Fit from several starting points (and the mirror of the best), with an honest ~95% error radius. */
    private fun solveAll(o: List<Obs>, p0Prior: Double, p0Sigma: Double, hint: Fit? = null): Solved? {
        val rssiObs = o.filter { it.rttM == null }
        val starts = ArrayList<DoubleArray>()
        hint?.let { starts += doubleArrayOf(it.x, it.y) }
        run { // power-weighted centroid (pulled toward where it was strongest)
            var sx = 0.0; var sy = 0.0; var sw = 0.0
            for (s in rssiObs) { val w = 10.0.pow((s.rssi + 100) / 10.0); sx += s.x * w; sy += s.y * w; sw += w }
            if (sw > 0) starts += doubleArrayOf(sx / sw, sy / sw)
        }
        for (s in rssiObs.sortedByDescending { it.rssi }.take(3)) {
            starts += doubleArrayOf(s.x, s.y)
            // Path direction around this sample -> try both sides of the path at the estimated distance
            val idx = o.indexOf(s)
            val a = o.getOrNull(idx - 1) ?: s; val b = o.getOrNull(idx + 1) ?: s
            var dx = b.x - a.x; var dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len > 1) {
                dx /= len; dy /= len
                val d = dist(s, p0Prior).coerceIn(2.0, 300.0)
                starts += doubleArrayOf(s.x - dy * d, s.y + dx * d)
                starts += doubleArrayOf(s.x + dy * d, s.y - dx * d)
            }
        }
        val first = starts.mapNotNull { st -> solve(o, st[0], st[1], p0Prior, p0Sigma) }.sortedBy { it.cost }
        if (first.isEmpty()) return null
        // A loop-shaped path can't tell a point from its mirror (circle inversion) when the transmit
        // power is unknown, so always try the mirror of the best answer as well.
        val n = o.size
        val cx = o.sumOf { it.x } / n; val cy = o.sumOf { it.y } / n
        val r2 = o.sumOf { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) } / n
        val b0 = first[0]
        val vx = b0.x - cx; val vy = b0.y - cy; val vv = vx * vx + vy * vy
        val mirror = if (vv > 1.0) solve(o, cx + vx * r2 / vv, cy + vy * r2 / vv, p0Prior, p0Sigma) else null
        val fits = (first + listOfNotNull(mirror)).sortedBy { it.cost }
        val best = fits[0]

        // ---- uncertainty from the (x, y) block of the inverse normal matrix, scaled by fit quality and by how
        // many truly independent readings there are: readings a few meters apart share the same shadowing
        // (walls, trees, cars), so 60 readings along 20 m are worth far fewer than 60.
        val dof = max(1, best.m - 4)
        val scale = max(1.0, best.cost / dof)
        val cells = HashSet<Long>()
        for (s in o) cells += (Math.floorDiv(s.x.toLong(), CORR_M) shl 32) xor (Math.floorDiv(s.y.toLong(), CORR_M) and 0xffffffffL)
        val inflate = max(1.0, n.toDouble() / max(1, cells.size))
        var err = if (best.cxx.isNaN()) 500.0 else {
            val k = scale * inflate
            val cxx = best.cxx * k; val cyy = best.cyy * k; val cxy = best.cxy * k
            val tr2 = (cxx + cyy) / 2
            val lam = tr2 + sqrt(max(0.0, (cxx - cyy) * (cxx - cyy) / 4 + cxy * cxy))
            2.0 * sqrt(max(lam, 0.0)) // ~95% radius along the worst axis
        }
        // GPS error is correlated over a walk: it shifts the whole track, so it adds to the error directly
        val gps = o.map { it.gpsErr }.average()
        err = sqrt(err * err + gps * gps).coerceIn(2.0, 500.0)

        // ---- ambiguity: a clearly different solution that fits nearly as well
        var rival: Fit? = null
        for (f in fits.drop(1)) {
            val sep = hypot(f.x - best.x, f.y - best.y)
            if (sep > max(err, 8.0) && (f.cost - best.cost) / scale < 3.0) { rival = f; break }
        }
        return Solved(best, err, rival, scale)
    }

    private fun solve(o: List<Obs>, x0: Double, y0: Double, p0Prior: Double, p0Sigma: Double): Fit? =
        lm(o, x0, y0, p0Prior, p0Prior, p0Sigma)

    /**
     * Robust Levenberg-Marquardt. Parameters: device x, y, its transmit level P0, a per-device correction to the
     * path-loss exponent (walls and antennas differ: learned within about ±0.35), and with [motion] a velocity.
     * Readings far weaker than the model (fades, your body, a car in between) are down-weighted instead of
     * dragging the answer away. Returns null if it diverges.
     */
    private fun lm(
        o: List<Obs>, x0: Double, y0: Double, p0Start: Double, p0Prior: Double, p0Sigma: Double,
        motion: Boolean = false, tMid: Long = 0L, dn0: Double = 0.0,
    ): Fit? {
        val np = if (motion) 6 else 4
        val q = DoubleArray(np)
        q[0] = x0; q[1] = y0; q[2] = p0Start; q[3] = dn0
        var lambda = 1e-2
        // Transmit power stays physically plausible around the prior (stops absurd distances)
        val p0Lo = p0Prior - (2.5 * p0Sigma + 5); val p0Hi = p0Prior + (2.5 * p0Sigma + 5)
        val jrow = DoubleArray(np)

        fun eval(p: DoubleArray, a: DoubleArray?, g: DoubleArray?): Double {
            var cost = 0.0
            a?.fill(0.0); g?.fill(0.0)
            val dn = p[3]
            for (s in o) {
                val tau = if (motion) (s.t - tMid) / 1000.0 else 0.0
                val px = p[0] + (if (motion) p[4] * tau else 0.0)
                val py = p[1] + (if (motion) p[5] * tau else 0.0)
                val dx = px - s.x; val dy = py - s.y
                val d = max(hypot(dx, dy), 1.0)
                val ux = dx / d; val uy = dy / d
                val r: Double
                val kLo: Double; val kHi: Double
                jrow.fill(0.0)
                if (s.rttM != null) {
                    val sg = sqrt(max(s.rttStd, 0.8) * max(s.rttStd, 0.8) + s.gpsErr * s.gpsErr * 0.5)
                    r = (d - s.rttM) / sg
                    jrow[0] = ux / sg; jrow[1] = uy / sg
                    kLo = 2.5; kHi = 2.5
                } else {
                    val nExp = (s.pathN + dn).coerceAtLeast(1.5)
                    val gpsDb = 10 * nExp / LN10 * s.gpsErr * 0.75 / max(d, 3.0)
                    val sg = sqrt(SHADOW_DB * SHADOW_DB * (0.45 + 0.55 / min(s.n, 8)) + gpsDb * gpsDb)
                    val ld = log10(d)
                    val model = p[2] - 10 * nExp * ld
                    r = (s.rssi + s.loss - model) / sg
                    val dm = -10 * nExp / (LN10 * d) // d model / d distance
                    jrow[0] = -dm * ux / sg; jrow[1] = -dm * uy / sg
                    jrow[2] = -1.0 / sg
                    jrow[3] = 10 * ld / sg
                    // Fades make readings weaker far more often than stronger: trust weak outliers less
                    kLo = 1.2; kHi = 2.0
                }
                if (motion) { jrow[4] = jrow[0] * tau; jrow[5] = jrow[1] * tau }
                val k = if (r < 0) kLo else kHi
                val ar = abs(r)
                val w = if (ar <= k) 1.0 else k / ar
                cost += if (ar <= k) r * r else 2 * k * ar - k * k // Huber
                if (a != null && g != null) addRow(a, g, jrow, r, w, np)
            }
            // Priors: transmit level, path-loss correction, and (motion) a gentle pull toward "not moving"
            fun prior(idx: Int, value: Double, sigma: Double) {
                val r = value / sigma
                cost += r * r
                if (a != null && g != null) { jrow.fill(0.0); jrow[idx] = 1.0 / sigma; addRow(a, g, jrow, r, 1.0, np) }
            }
            prior(2, p[2] - p0Prior, p0Sigma)
            prior(3, dn, DN_SIGMA)
            if (motion) { prior(4, p[4], 3.0); prior(5, p[5], 3.0) }
            return cost
        }

        val a = DoubleArray(np * np); val g = DoubleArray(np)
        var cost = eval(q, a, g)
        val trial = DoubleArray(np)
        for (iter in 0 until 60) {
            val m = a.copyOf()
            for (k in 0 until np) m[k * np + k] = a[k * np + k] * (1 + lambda) + 1e-9
            val step = solveN(m, DoubleArray(np) { -g[it] }, np) ?: break
            for (k in 0 until np) trial[k] = q[k] + step[k]
            trial[2] = trial[2].coerceIn(p0Lo, p0Hi)
            trial[3] = trial[3].coerceIn(-1.0, 1.2)
            val nc = eval(trial, null, null)
            if (nc < cost) {
                System.arraycopy(trial, 0, q, 0, np); cost = nc
                lambda = max(lambda / 3, 1e-7)
                eval(q, a, g)
                if (hypot(step[0], step[1]) < 0.02 && abs(step[2]) < 0.01) break // converged
            } else {
                lambda = min(lambda * 5, 1e7)
            }
        }
        if (q[0].isNaN() || q[1].isNaN() || hypot(q[0] - x0, q[1] - y0) > 3000) return null
        eval(q, a, g)
        val inv = invertN(a, np)
        return Fit(
            q[0], q[1], q[2], cost,
            inv?.get(0) ?: Double.NaN, inv?.get(np + 1) ?: Double.NaN, inv?.get(1) ?: 0.0, o.size + 2,
            q[3], if (motion) q[4] else 0.0, if (motion) q[5] else 0.0,
        )
    }

    private fun addRow(a: DoubleArray, g: DoubleArray, j: DoubleArray, r: Double, w: Double, np: Int) {
        for (i in 0 until np) {
            val ji = j[i]
            if (ji == 0.0) continue
            g[i] += w * ji * r
            for (k in 0 until np) a[i * np + k] += w * ji * j[k]
        }
    }

    /** Solve m·x = b (m is np×np, row-major) by Gaussian elimination with partial pivoting. */
    private fun solveN(m: DoubleArray, b: DoubleArray, np: Int): DoubleArray? {
        val a = m.copyOf(); val x = b.copyOf()
        for (c in 0 until np) {
            var piv = c
            for (r in c + 1 until np) if (abs(a[r * np + c]) > abs(a[piv * np + c])) piv = r
            if (abs(a[piv * np + c]) < 1e-14) return null
            if (piv != c) {
                for (k in 0 until np) { val t = a[c * np + k]; a[c * np + k] = a[piv * np + k]; a[piv * np + k] = t }
                val t = x[c]; x[c] = x[piv]; x[piv] = t
            }
            for (r in c + 1 until np) {
                val f = a[r * np + c] / a[c * np + c]
                if (f == 0.0) continue
                for (k in c until np) a[r * np + k] -= f * a[c * np + k]
                x[r] -= f * x[c]
            }
        }
        for (c in np - 1 downTo 0) {
            var s = x[c]
            for (k in c + 1 until np) s -= a[c * np + k] * x[k]
            x[c] = s / a[c * np + c]
        }
        return x
    }

    private fun invertN(m: DoubleArray, np: Int): DoubleArray? {
        val out = DoubleArray(np * np)
        for (c in 0 until np) {
            val e = DoubleArray(np); e[c] = 1.0
            val col = solveN(m, e, np) ?: return null
            for (r in 0 until np) out[r * np + c] = col[r]
        }
        return out
    }

    /** Natural log helper kept for callers converting between dB and power. */
    fun lnPow(db: Double) = db / 10 * ln(10.0)
}
