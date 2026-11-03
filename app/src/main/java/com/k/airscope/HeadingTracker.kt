package com.k.airscope

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Which way you're facing: reads the sensors and feeds [HeadingFusion].
 *
 * - Gyro (TYPE_GAME_ROTATION_VECTOR, game rate): smooth turns, immune to magnets.
 * - Compass (TYPE_ROTATION_VECTOR) + raw magnetic field + gravity: true-north reference, used only while the field
 *   passes Earth-field checks (strength and dip angle from the World Magnetic Model for where you are).
 * - GPS course while driving (fed by the screen from the snapshot).
 * Look direction = top edge when the phone is flat, back camera when upright, blended in between.
 */
class HeadingTracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    val fusion = HeadingFusion()

    /** Where the phone points, degrees from true north. */
    val facing: Double? get() = fusion.facing
    /** ± degrees. */
    val facingAcc: Double get() = fusion.accuracy
    /** For a heading-up map: travel direction while driving, else facing. */
    val mapHeading: Double? get() = fusion.mapHeading

    /** The magnetic field doesn't look like Earth's right now (metal, magnets, car). */
    @Volatile var interference = false
        private set
    /** Compass reports low accuracy: ask for a figure-8 calibration. */
    @Volatile var needsCalibration = false
        private set
    @Volatile var declination = 0.0
        private set
    /** Last raw readings, for the diagnostics screen. */
    @Volatile var fieldUt = 0.0
        private set
    @Volatile var dipDeg: Double? = null
        private set
    @Volatile var expectedUt = 0.0
        private set
    @Volatile var expectedDip: Double? = null
        private set

    private var magStatus = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var rvStatus = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var declAt = 0L
    private val rot = FloatArray(9)
    private val grav = FloatArray(3)
    private var haveGrav = false
    private var lastMag = 0.0
    private var lastMagT = 0L
    private var magChange = 0.0 // uT/s, smoothed
    private var running = false

    val hasGyro: Boolean get() = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null

    fun start() {
        if (running || sm == null) return
        running = true
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        (sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR))
            ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(this)
    }

    /** Declination, expected field strength and dip angle for where you are (every 10 min). */
    fun updateLocation(lat: Double?, lon: Double?) {
        if (lat == null || lon == null) return
        val now = System.currentTimeMillis()
        if (now - declAt < 600_000L && declAt != 0L) return
        declAt = now
        val g = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, now)
        declination = g.declination.toDouble()
        expectedUt = g.fieldStrength / 1000.0 // nT -> uT
        expectedDip = g.inclination.toDouble()
    }

    /** GPS speed (m/s) and course (deg) from the latest snapshot. */
    fun updateMotion(speed: Double, course: Double?, accM: Double) {
        fusion.onCourse(course, speed, HeadingMath.courseAccuracy(speed, accM), SystemClock.elapsedRealtimeNanos())
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                HeadingMath.lookAzimuth(rot)?.let { fusion.onGyro(it, e.timestamp) }
            }
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                val raw = HeadingMath.lookAzimuth(rot) ?: return
                val az = (raw + declination + 360) % 360
                val est = if (e.values.size >= 5 && e.values[4] > 0f) Math.toDegrees(e.values[4].toDouble())
                else HeadingMath.accuracyFromStatus(minOf(magStatus, rvStatus))
                val ok = !interference && minOf(magStatus, rvStatus) >= SensorManager.SENSOR_STATUS_ACCURACY_LOW
                fusion.onCompass(az, e.timestamp, ok, est)
            }
            Sensor.TYPE_GRAVITY -> {
                grav[0] = e.values[0]; grav[1] = e.values[1]; grav[2] = e.values[2]; haveGrav = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val mx = e.values[0].toDouble(); val my = e.values[1].toDouble(); val mz = e.values[2].toDouble()
                val m = sqrt(mx * mx + my * my + mz * mz)
                fieldUt = m
                if (lastMagT != 0L) {
                    val dt = ((e.timestamp - lastMagT) / 1e9).coerceIn(0.01, 1.0)
                    val rate = kotlin.math.abs(m - lastMag) / dt
                    magChange += (rate - magChange) * (1 - kotlin.math.exp(-dt / 0.5))
                }
                lastMag = m; lastMagT = e.timestamp
                // Dip angle: how far the field points below the horizon (gravity sensor points "up")
                dipDeg = if (haveGrav && m > 1) {
                    val gl = sqrt((grav[0] * grav[0] + grav[1] * grav[1] + grav[2] * grav[2]).toDouble())
                    if (gl > 1) {
                        val up = (mx * grav[0] + my * grav[1] + mz * grav[2]) / gl
                        Math.toDegrees(asin((-up / m).coerceIn(-1.0, 1.0)))
                    } else null
                } else null
                interference = !HeadingFusion.earthField(m, expectedUt, dipDeg, expectedDip, magChange)
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

    /** Short status for the UI, e.g. "Facing 247° WSW ±8° · compass". */
    fun label(): String? {
        val f = facing ?: return null
        val src = fusion.source
        val what = if (src == HeadingFusion.Source.GPS) "Heading" else "Facing"
        val warn = when {
            src == HeadingFusion.Source.GYRO -> ""
            interference -> " · ⚠ magnetic interference"
            needsCalibration -> " · ⚠ calibrate: figure-8"
            else -> ""
        }
        return "$what ${Math.round(f) % 360}° ${Geo.cardinal16(f)} ±${Math.round(facingAcc)}° · ${src.label}$warn"
    }

    /** Text for the diagnostics screen. */
    fun diagnostics(): String = buildString {
        append("Gyro: ").append(if (hasGyro) "yes" else "NO (compass only, can swing)").append('\n')
        append("Source: ").append(fusion.source.label.ifEmpty { "none yet" }).append('\n')
        append("Field: %.0f µT (Earth here %.0f µT)".format(java.util.Locale.US, fieldUt, expectedUt)).append('\n')
        append("Dip angle: ").append(dipDeg?.let { "%.0f°".format(java.util.Locale.US, it) } ?: "?")
            .append(" (expected ").append(expectedDip?.let { "%.0f°".format(java.util.Locale.US, it) } ?: "?").append(")\n")
        append("Magnetic interference: ").append(if (interference) "YES (metal, magnet or car nearby)" else "no").append('\n')
        append("Calibration: ").append(if (needsCalibration) "needed (figure-8)" else "ok").append('\n')
        append("Declination: %.1f°".format(java.util.Locale.US, declination))
    }
}
