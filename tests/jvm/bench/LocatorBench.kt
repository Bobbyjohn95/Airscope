package airscope.tests.bench

import com.k.airscope.*
import kotlin.math.*

/**
 * Realistic benchmark for device placement. Same seeds every run, so results compare across code changes.
 * Radio model: per-device unknown transmit power and path-loss exponent, spatially correlated shadowing,
 * Rayleigh fading (BLE), body shadowing, AR(1) GPS error, receiver sensitivity.
 */
class Rng(seed: Long) {
    val r = java.util.Random(seed)
    fun g() = r.nextGaussian()
    fun u() = r.nextDouble()
    fun u(a: Double, b: Double) = a + (b - a) * r.nextDouble()
}

/** Gaussian random field (sum of random cosines): smooth shadowing with ~corrM correlation length. */
class Field(rng: Rng, val sigma: Double, corrM: Double) {
    private val k = 16
    private val ax = DoubleArray(k) { rng.g() / corrM }
    private val ay = DoubleArray(k) { rng.g() / corrM }
    private val ph = DoubleArray(k) { rng.u() * 2 * PI }
    private val amp = sigma * sqrt(2.0 / k)
    fun at(x: Double, y: Double): Double { var s = 0.0; for (i in 0 until k) s += amp * cos(ax[i] * x + ay[i] * y + ph[i]); return s }
}

class Pt(val t: Long, val x: Double, val y: Double, val hdg: Double, val inCar: Boolean)

data class Result(val located: Boolean, val err: Double, val claimed: Double, val ambiguous: Boolean)

val P = LocalProj(33.92, -84.84)

fun path(kind: String, rng: Rng): List<Pt> {
    val out = ArrayList<Pt>()
    var t = 0L
    when (kind) {
        "walk-loop" -> {
            val r = rng.u(15.0, 40.0); val laps = 1.5; val v = 1.3
            val total = (2 * PI * r * laps / v).toInt()
            for (s in 0..total * 3) { // 3 samples/s
                val a = v * (s / 3.0) / r
                out += Pt(t, r * sin(a), r * cos(a), Math.toDegrees(a) + 90, false); t += 333
            }
        }
        "walk-L" -> {
            val v = 1.3
            for (s in 0..(200 / v * 3).toInt()) { out += Pt(t, -100 + v * s / 3.0, 0.0, 90.0, false); t += 333 }
            for (s in 0..(100 / v * 3).toInt()) { out += Pt(t, 100.0, v * s / 3.0, 0.0, false); t += 333 }
        }
        "drive-straight" -> {
            val v = 15.0
            for (s in 0..(700 / v * 2).toInt()) { out += Pt(t, -350 + v * s / 2.0, 0.0, 90.0, true); t += 500 }
        }
        "drive-turn" -> {
            val v = 12.0
            for (s in 0..(300 / v * 2).toInt()) { out += Pt(t, -300 + v * s / 2.0, 0.0, 90.0, true); t += 500 }
            for (s in 1..(300 / v * 2).toInt()) { out += Pt(t, 0.0, v * s / 2.0, 0.0, true); t += 500 }
        }
    }
    return out
}

fun run(scenario: String, trials: Int, seed: Long): List<Result> {
    val res = ArrayList<Result>()
    for (trial in 0 until trials) {
        val rng = Rng(seed * 1000 + trial)
        val ble = scenario.startsWith("walk-loop") || scenario == "moving"
        val pathKind = when (scenario) { "moving" -> "walk-loop"; else -> scenario }
        val pts = path(pathKind, rng)
        // Device
        var dx: Double; var dy: Double
        when (scenario) {
            "walk-loop", "moving" -> { val a = rng.u() * 2 * PI; val r = rng.u(0.0, 70.0); dx = r * sin(a); dy = r * cos(a) }
            "walk-L" -> { dx = rng.u(-70.0, 70.0); dy = (if (rng.u() < 0.5) -1 else 1) * rng.u(10.0, 45.0) }
            "drive-straight" -> { dx = rng.u(-200.0, 200.0); dy = (if (rng.u() < 0.5) -1 else 1) * rng.u(15.0, 60.0) }
            else -> { dx = rng.u(-80.0, 80.0); dy = rng.u(-80.0, 80.0) }
        }
        val p0True = if (ble) -60 + rng.g() * 6 else -34 + rng.g() * 6
        val nTrue = if (ble) rng.u(1.9, 2.8) else rng.u(2.2, 3.2)
        val shadow = Field(rng, if (ble) 4.0 else 5.0, 6.0)
        val moveVx = if (scenario == "moving") 1.3 * cos(rng.u() * 2 * PI) else 0.0
        val moveVy = if (scenario == "moving") sqrt(1.69 - moveVx * moveVx) * (if (rng.u() < 0.5) -1 else 1) else 0.0
        // GPS AR(1) error
        var ex = rng.g() * 3; var ey = rng.g() * 3
        val tr = LocTrack()
        val wifiEveryMs = if (scenario.startsWith("drive")) 3000L else 4000L
        var lastWifi = -wifiEveryMs
        val prior = if (ble) -60.0 else -34.0
        val sigmaPrior = 8.0
        val env = when {
            scenario.startsWith("drive") -> Env.CAR
            else -> Env.OPEN
        }
        val evalAt = setOf(pts.size / 2, pts.size * 3 / 4, pts.size - 1)
        var worst: Result? = null
        for ((i, p) in pts.withIndex()) {
            val tSec = p.t / 1000.0
            val devX = dx + moveVx * tSec; val devY = dy + moveVy * tSec
            ex = ex * 0.97 + rng.g() * 3 * sqrt(1 - 0.97 * 0.97); ey = ey * 0.97 + rng.g() * 3 * sqrt(1 - 0.97 * 0.97)
            val heard = if (ble) true else (p.t - lastWifi >= wifiEveryMs).also { if (it) lastWifi = p.t }
            if (heard) {
                val d = max(hypot(devX - p.x, devY - p.y), 1.0)
                var rssi = p0True - 10 * nTrue * log10(d) + shadow.at(p.x, p.y)
                if (ble) rssi += max(-20.0, 10 * log10(-ln(1 - rng.u() * 0.999999))) // Rayleigh fading
                else rssi += rng.g() * 2.0
                // body shadowing: device behind you
                val bearing = Math.toDegrees(atan2(devX - p.x, devY - p.y))
                if (!p.inCar && Geo.angleDiff(bearing, p.hdg) > 120) rssi -= 7.0
                if (p.inCar) rssi -= 8.0 // car body
                if (rssi > -100) {
                    val gx = p.x + ex; val gy = p.y + ey
                    Locator.add(tr, P.lat(gy), P.lon(gx), Math.round(rssi).toDouble(), p.t, 4.0, env)
                }
            }
            if (i in evalAt) {
                Locator.estimate(tr, prior, sigmaPrior, env, p.t, if (scenario == "moving") 120_000L else null)
                if (tr.located || tr.ambiguous) {
                    val e = Geo.distanceM(tr.estLat, tr.estLon, P.lat(devY), P.lon(devX))
                    val r = Result(tr.located, e, tr.errM, tr.ambiguous)
                    if (tr.located && (worst == null || e > worst.err)) worst = r
                }
            }
        }
        // Report the final state, but remember the worst located answer shown during the run
        val proj = tr.proj
        val tEnd = pts.last().t / 1000.0
        val fx = dx + moveVx * tEnd; val fy = dy + moveVy * tEnd
        val finalErr = if (proj != null && tr.located) Geo.distanceM(tr.estLat, tr.estLon, P.lat(fy), P.lon(fx)) else Double.NaN
        if (System.getProperty("dbgmove") != null && scenario == "moving" && tr.located) {
            val tEndMs = pts.last().t
            println("trial=$trial err=%.0f claimed=%.0f lastObsAgo=%ds obs=%d hist=%s".format(finalErr, tr.errM, (tEndMs - tr.obs.maxOf { it.t }) / 1000, tr.obs.size,
                tr.history.joinToString { "[t=%.0f e=%.0f]".format(it[0] / 1000, it[3]) }))
        }
        res += Result(tr.located, if (tr.located) finalErr else (worst?.err ?: Double.NaN), tr.errM, worst != null && !tr.located)
    }
    return res
}

fun pct(n: Int, d: Int) = if (d == 0) "  -  " else "%4.0f%%".format(100.0 * n / d)

fun main() {
    println("scenario         located  median   p90   overconf  way-off(>40m or >3x claim)")
    var totalWayOff = 0; var totalLocated = 0
    for ((i, sc) in listOf("walk-loop", "walk-L", "drive-straight", "drive-turn", "moving").withIndex()) {
        val r = run(sc, 200, 7L + i)
        val loc = r.filter { it.located }
        val errs = loc.map { it.err }.sorted()
        val med = if (errs.isEmpty()) Double.NaN else errs[errs.size / 2]
        val p90 = if (errs.isEmpty()) Double.NaN else errs[(errs.size * 9) / 10]
        val over = loc.count { it.err > it.claimed }
        val wayOff = loc.count { it.err > 40 || it.err > 3 * it.claimed }
        totalWayOff += wayOff; totalLocated += loc.size
        println("%-16s %s   %5.1f  %5.1f   %s     %s".format(sc, pct(loc.size, r.size), med, p90, pct(over, loc.size), pct(wayOff, loc.size)))
        if (System.getProperty("ratios") != null && loc.isNotEmpty()) {
            val q = loc.map { it.err / it.claimed }.sorted()
            println("    actual/claimed  p50=%.2f p80=%.2f p90=%.2f".format(q[q.size / 2], q[(q.size * 8) / 10], q[(q.size * 9) / 10]))
        }
    }
    println("TOTAL located=$totalLocated wayOff=$totalWayOff")
}
