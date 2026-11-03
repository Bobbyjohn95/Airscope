package com.k.airscope

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Which way you're facing, robust to magnets and metal (pure logic, unit-tested).
 *
 * - The gyro ("game rotation vector": gyro + accelerometer, no magnetometer) tracks every turn smoothly and is
 *   immune to magnetic interference, but its "north" is arbitrary.
 * - The compass supplies the missing offset to true north, but ONLY while the magnetic field looks like Earth's
 *   (right strength and dip angle, steady). Near a car, a magnetic mount or steel the offset is frozen and the gyro
 *   carries on: the heading no longer spins or swings.
 * - Driving: the GPS travel direction is the reference for the map (a compass inside a car is useless), with
 *   hysteresis so stop-and-go traffic doesn't flip back and forth.
 */
class HeadingFusion {
    enum class Source(val label: String) {
        COMPASS("compass"),
        GYRO("gyro, compass blocked by metal/magnets"),
        GPS("GPS travel direction"),
        COMPASS_ONLY("compass (no gyro)"),
        NONE(""),
    }

    private var relAz: Double? = null
    private var relT = 0L
    private var offset: Double? = null
    /** 1-sigma uncertainty of the gyro->true-north offset (deg). */
    private var offsetSigma = 180.0
    private var offsetT = 0L
    private var compassAz: Double? = null
    private var compassOk = false
    private var compassAcc = 30.0
    private var lastGoodCompassT = 0L

    private var course: Double? = null
    private var courseAcc = 180.0
    private var fastSince = 0L
    private var slowSince = 0L

    var driving = false
        private set
    var hasGyro = false
        private set

    /** Gyro look direction (arbitrary zero), deg. */
    fun onGyro(az: Double, tNanos: Long) {
        hasGyro = true
        relAz = az
        if (relT != 0L && !compassOkRecently(tNanos)) {
            // Offset held without a compass check: uncertainty grows with gyro drift (~1-2 deg/min)
            val dt = ((tNanos - relT) / 1e9).coerceIn(0.0, 1.0)
            offsetSigma = min(90.0, offsetSigma + DRIFT_DEG_S * dt)
        }
        relT = tNanos
    }

    /**
     * Compass look direction already converted to TRUE north. [ok] = the field passed the Earth-field checks;
     * [accDeg] = the sensor's own heading accuracy.
     */
    fun onCompass(az: Double, tNanos: Long, ok: Boolean, accDeg: Double) {
        compassAz = az
        compassOk = ok
        compassAcc = accDeg.coerceIn(2.0, 90.0)
        if (ok) lastGoodCompassT = tNanos
        val rel = relAz ?: return
        val meas = (az - rel + 360) % 360
        val off = offset
        if (off == null) {
            // First reading: take it, but only trust it as much as it deserves
            offset = meas
            offsetSigma = if (ok) max(compassAcc, 10.0) else 60.0
            offsetT = tNanos
            return
        }
        if (!ok) return
        val dt = if (offsetT == 0L) 0.05 else ((tNanos - offsetT) / 1e9).coerceIn(0.0, 1.0)
        offsetT = tNanos
        // Converge fast when unsure, slowly (filtering out sway) once settled
        val tau = if (offsetSigma > 20) 0.6 else 4.0
        val a = 1 - exp(-dt / tau)
        offset = HeadingMath.blend(off, meas, a)
        offsetSigma += (max(compassAcc * 0.7, 3.0) - offsetSigma) * a
    }

    fun onCourse(courseDeg: Double?, speed: Double, courseAccDeg: Double, tNanos: Long) {
        course = courseDeg
        courseAcc = courseAccDeg
        if (speed > DRIVE_ON_MS) { if (fastSince == 0L) fastSince = tNanos; slowSince = 0L }
        else if (speed < DRIVE_OFF_MS) { if (slowSince == 0L) slowSince = tNanos; fastSince = 0L }
        else { fastSince = 0L; slowSince = 0L }
        if (!driving && fastSince != 0L && tNanos - fastSince >= 3_000_000_000L) driving = true
        if (driving && slowSince != 0L && tNanos - slowSince >= 8_000_000_000L) driving = false
    }

    private fun compassOkRecently(tNanos: Long) = lastGoodCompassT != 0L && tNanos - lastGoodCompassT < 1_500_000_000L

    /** Where the phone points (true north), or null if nothing is known yet. */
    val facing: Double?
        get() {
            val rel = relAz
            val off = offset
            if (hasGyro && rel != null && off != null) {
                // Driving and the north reference is stale: the travel direction is better
                if (driving && offsetSigma > 25 && course != null) return course
                return (rel + off + 360) % 360
            }
            if (driving && course != null) return course
            return compassAz
        }

    /** ±deg for [facing]. */
    val accuracy: Double
        get() = when (source) {
            Source.GPS -> courseAcc.coerceIn(2.0, 90.0)
            Source.COMPASS, Source.GYRO -> sqrt(offsetSigma * offsetSigma + 4.0).coerceIn(3.0, 90.0)
            Source.COMPASS_ONLY -> compassAcc * (if (compassOk) 1.0 else 2.5)
            Source.NONE -> 180.0
        }

    val source: Source
        get() = when {
            hasGyro && relAz != null && offset != null ->
                if (driving && offsetSigma > 25 && course != null) Source.GPS
                else if (lastGoodCompassT != 0L && relT - lastGoodCompassT < 1_500_000_000L) Source.COMPASS else Source.GYRO
            driving && course != null -> Source.GPS
            compassAz != null -> Source.COMPASS_ONLY
            else -> Source.NONE
        }

    /** Direction for turning a heading-up map: travel direction while driving, else where you face. */
    val mapHeading: Double?
        get() = if (driving && course != null) course else facing

    companion object {
        const val DRIFT_DEG_S = 0.03
        const val DRIVE_ON_MS = 6.0
        const val DRIVE_OFF_MS = 2.5

        /**
         * Is this magnetic field Earth's own? Strength within 20% of the model for this place, dip angle within
         * 10°, and steady (a passing car, a magnet or a steel beam changes it).
         */
        fun earthField(measuredUt: Double, expectedUt: Double, dipDeg: Double?, expectedDipDeg: Double?, changeUtPerS: Double): Boolean {
            if (expectedUt > 1 && abs(measuredUt / expectedUt - 1) > 0.2) return false
            if (expectedUt <= 1 && (measuredUt < 22 || measuredUt > 68)) return false
            if (dipDeg != null && expectedDipDeg != null && abs(dipDeg - expectedDipDeg) > 10) return false
            return changeUtPerS < 6.0
        }
    }
}
