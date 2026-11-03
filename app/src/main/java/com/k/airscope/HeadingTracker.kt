package com.k.airscope

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Which way you're looking.
 *
 * - Uses the fused rotation-vector sensor (gyro + accelerometer + magnetometer) at game rate, so it
 *   reacts instantly when you turn but doesn't drift.
 * - Look direction = the phone's top edge when flat, the back camera when held upright, blended between.
 * - Converted from magnetic north to TRUE north (local declination from the World Magnetic Model), so it
 *   lines up with GPS bearings and the street map.
 * - Knows how sure it is: the sensor's own heading-accuracy estimate, the calibration status, and a
 *   magnetic-interference check (field strength vs. Earth's field here: cars, steel, magnets).
 * - At driving speed the compass is unreliable inside a car, so travel direction comes from GPS course.
 */
class HeadingTracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)

    /** Where the phone is pointing, degrees from true north. */
    @Volatile var facing: Double? = null
        private set
    /** +- degrees (1 sigma-ish). */
    @Volatile var facingAcc: Double = 45.0
        private set
    @Volatile var interference = false
        private set
    /** Compass reports low accuracy: ask for a figure-8 calibration. */
    @Volatile var needsCalibration = false
        private set
    @Volatile var declination = 0.0
        private set

    private var magStatus = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var rvStatus = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var expectedUt = 0.0
    private var magAvg = 0.0
    private var declAt = 0L
    private var lastT = 0L
    private val rot = FloatArray(9)
    private var running = false

    fun start() {
        if (running || sm == null) return
        running = true
        lastT = 0L
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(this)
    }

    /** Refresh declination / expected field strength for where you are (cheap; every 10 min). */
    fun updateLocation(lat: Double?, lon: Double?) {
        if (lat == null || lon == null) return
        val now = System.currentTimeMillis()
        if (now - declAt < 600_000L && declAt != 0L) return
        declAt = now
        val g = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, now)
        declination = g.declination.toDouble()
        expectedUt = g.fieldStrength / 1000.0 // nT -> uT
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                val raw = HeadingMath.lookAzimuth(rot) ?: return
                val az = (raw + declination + 360) % 360
                val dt = if (lastT == 0L) 0.02 else ((e.timestamp - lastT) / 1e9).coerceIn(0.001, 0.5)
                lastT = e.timestamp
                val prev = facing
                facing = if (prev == null) az else {
                    val rate = abs(Geo.signedDiff(prev, az)) / dt
                    HeadingMath.blend(prev, az, HeadingMath.smoothAlpha(rate, dt))
                }
                // values[4] = the sensor's own heading accuracy estimate (radians), when the device provides it
                val est = if (e.values.size >= 5 && e.values[4] > 0f) Math.toDegrees(e.values[4].toDouble())
                else HeadingMath.accuracyFromStatus(minOf(magStatus, rvStatus))
                facingAcc = (est * (if (interference) 2.5 else 1.0)).coerceIn(3.0, 90.0)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val m = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                magAvg = if (magAvg == 0.0) m else magAvg * 0.92 + m * 0.08
                interference = HeadingMath.interference(magAvg, expectedUt)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor?.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> magStatus = accuracy
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> rvStatus = accuracy
        }
        needsCalibration = minOf(magStatus, rvStatus) <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    /** Short status for the UI, e.g. "Facing 247° WSW ±8°". */
    fun label(): String? {
        val f = facing ?: return null
        val warn = when {
            interference -> " · ⚠ magnetic interference"
            needsCalibration -> " · ⚠ calibrate: figure-8"
            else -> ""
        }
        return "Facing ${Math.round(f) % 360}° ${Geo.cardinal16(f)} ±${Math.round(facingAcc)}°$warn"
    }
}
