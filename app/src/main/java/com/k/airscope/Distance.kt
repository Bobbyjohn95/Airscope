package com.k.airscope

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** 1-D Kalman filter for RSSI (dBm). Smooths noise but still follows you when you walk. */
class RssiKalman {
    var x = Double.NaN
        private set
    var p = 0.0
        private set
    private var lastT = 0L

    /** r = measurement variance (dB^2), q = process variance per second (dB^2/s). */
    fun update(z: Double, t: Long, r: Double, q: Double = 0.8): Double {
        if (x.isNaN()) {
            x = z; p = r; lastT = t
            return x
        }
        val dt = ((t - lastT).coerceAtLeast(0L) / 1000.0).coerceAtMost(30.0)
        lastT = t
        p += q * dt + 0.05
        // Very large jumps (person walked between you and it, device moved) get a little extra trust.
        val rr = if (kotlin.math.abs(z - x) > 15) r * 0.6 else r
        val k = p / (p + rr)
        x += k * (z - x)
        p *= (1 - k)
        return x
    }
}

/** Path-loss environment. n = path-loss exponent, sigma = shadowing (dB), extraLoss = body/vehicle loss (dB). */
enum class Env(val label: String, val n: Double, val sigma: Double, val extraLoss: Double) {
    OPEN("Open / outdoor", 2.0, 4.0, 0.0),
    INDOOR("Home / indoor", 2.6, 5.0, 0.0),
    DENSE("Office / dense", 3.1, 6.0, 0.0),
    CAR("Inside a car", 2.3, 6.0, 8.0),
}

data class DistEst(val meters: Double, val lo: Double, val hi: Double, val source: String)

object Distance {
    private const val RTT_FRESH_MS = 20_000L

    fun meters(ref: Double, rssi: Double, env: Env): Double =
        10.0.pow((ref - (rssi + env.extraLoss)) / (10 * env.n)).coerceIn(0.1, 500.0)

    /** Expected RSSI at 1 m, and where that number came from. */
    fun reference(d: Device): Pair<Int, String> {
        d.refAt1m?.let { return it to d.refSource.ifEmpty { "advertised" } }
        return when (d.kind) {
            Kind.WIFI -> {
                val base = when {
                    d.frequency >= 5925 -> -37
                    d.frequency >= 5000 -> -34
                    else -> -28
                }
                val mobile = d.category == Category.PHONE || d.category == Category.VEHICLE ||
                    d.randomAddr == true
                if (mobile) (base - 6) to "hotspot default" else base to "access-point default"
            }
            else -> when (d.category) {
                Category.TRACKER -> -62 to "tracker default"
                Category.PHONE -> -60 to "phone default"
                Category.WEARABLE -> -64 to "wearable default"
                Category.CAMERA_IOT -> -58 to "IoT default"
                Category.VEHICLE -> -55 to "vehicle default"
                Category.DRONE -> -50 to "drone default"
                else -> -60 to "BLE default"
            }
        }
    }

    fun estimate(d: Device, env: Env, now: Long): DistEst {
        val rtt = d.rttMeters
        if (rtt != null && now - d.rttAt < RTT_FRESH_MS) {
            val sd = max(d.rttStd ?: 1.0, 0.5)
            return DistEst(rtt.coerceAtLeast(0.1), (rtt - sd).coerceAtLeast(0.1), rtt + sd, "Wi-Fi RTT (measured)")
        }
        val (ref, src) = reference(d)
        val x = if (d.kalman.x.isNaN()) d.rssi.toDouble() else d.kalman.x
        val sigma = sqrt(d.kalman.p + env.sigma * env.sigma)
        val m = meters(ref.toDouble(), x, env)
        return DistEst(m, meters(ref.toDouble(), x + sigma, env), meters(ref.toDouble(), x - sigma, env), "signal strength, $src $ref dBm@1m")
    }
}
