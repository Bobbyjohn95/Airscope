package com.k.airscope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Ui {
    val BG = Color.parseColor("#05090C")
    val CYAN = Color.parseColor("#00E5FF")
    val DIM = Color.parseColor("#4F8A99")
    val GRID = Color.parseColor("#1A3F49")
    val SWEEP = Color.parseColor("#8800E5FF")
    val GREEN = Color.parseColor("#39FF88")
    val AMBER = Color.parseColor("#FFC400")
    val RED = Color.parseColor("#FF3B5C")
    val SELECT_BG = Color.parseColor("#14303A")

    fun levelColor(l: Level): Int = when (l) {
        Level.LOW -> GREEN
        Level.MEDIUM -> AMBER
        Level.HIGH -> RED
    }

    /**
     * Phones can't measure bearing, so each device gets a stable pseudo-angle from its ID:
     * Wi-Fi in the top half, BLE in the bottom half. Same formula as the web HUD.
     */
    fun angleFor(d: Device): Float {
        val h = (d.key.hashCode().toLong() and 0xffffffffL) % 180
        return (if (d.kind == Kind.WIFI) 180f else 0f) + h.toFloat()
    }
}

class RadarView(ctx: Context) : View(ctx) {
    var onSelect: ((Device) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var devices: List<Device> = emptyList()
    private var selectedKey: String? = null
    private var sweep = 0f
    private var lastFrame = 0L
    private val maxRange = 40.0
    private val rings = doubleArrayOf(2.0, 5.0, 10.0, 20.0, 40.0)
    private val positions = HashMap<String, PointF>()
    private var shader: SweepGradient? = null
    private val shaderMatrix = Matrix()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = Ui.GRID
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Ui.CYAN
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.DIM; textSize = 10f * density; typeface = Typeface.MONOSPACE
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11f * density; typeface = Typeface.MONOSPACE
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.WHITE
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.CYAN }

    fun update(list: List<Device>, selected: String?) {
        devices = list
        selectedKey = selected
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Ui.SWEEP),
            floatArrayOf(0f, 0.82f, 1f),
        )
        sweepPaint.shader = shader
    }

    private fun radiusFor(d: Double, r: Float): Float =
        r * sqrt(min(d, maxRange) / maxRange).toFloat()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = SystemClock.uptimeMillis()
        if (lastFrame != 0L) sweep = (sweep + (now - lastFrame) * 0.09f) % 360f // ~4 s per turn
        lastFrame = now

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 6f * density
        if (r <= 0) return

        // Range rings (square-root scale so close devices get room)
        for (ring in rings) {
            val rr = radiusFor(ring, r)
            c.drawCircle(cx, cy, rr, gridPaint)
            c.drawText("${ring.toInt()}m", cx + 4 * density, cy - rr + 11 * density, labelPaint)
        }
        c.drawLine(cx - r, cy, cx + r, cy, gridPaint)
        c.drawLine(cx, cy - r, cx, cy + r, gridPaint)
        c.drawText("WI-FI", cx - r + 4 * density, cy - 4 * density, labelPaint)
        c.drawText("BLE", cx - r + 4 * density, cy + 13 * density, labelPaint)

        // Sweep
        shader?.let {
            shaderMatrix.setRotate(sweep, cx, cy)
            it.setLocalMatrix(shaderMatrix)
            c.drawCircle(cx, cy, r, sweepPaint)
        }
        val rad = Math.toRadians(sweep.toDouble())
        c.drawLine(cx, cy, cx + (r * cos(rad)).toFloat(), cy + (r * sin(rad)).toFloat(), edgePaint)

        // Blips
        positions.clear()
        for (d in devices) {
            val ang = Ui.angleFor(d)
            val dist = radiusFor(d.distanceMeters(), r)
            val a = Math.toRadians(ang.toDouble())
            val x = cx + (dist * cos(a)).toFloat()
            val y = cy + (dist * sin(a)).toFloat()
            positions[d.key] = PointF(x, y)

            var delta = sweep - ang
            if (delta < 0) delta += 360f
            val glow = 1f - delta / 360f // brightest right after the sweep passes
            blipPaint.color = Ui.levelColor(d.level)
            blipPaint.alpha = (80 + 175 * glow).toInt().coerceIn(0, 255)
            val s = (if (d.level == Level.HIGH) 6.5f else 4.5f) * density

            if (d.kind == Kind.WIFI) {
                c.drawCircle(x, y, s, blipPaint)
            } else {
                c.save()
                c.rotate(45f, x, y)
                c.drawRect(x - s * 0.8f, y - s * 0.8f, x + s * 0.8f, y + s * 0.8f, blipPaint)
                c.restore()
            }
            if (d.key == selectedKey) {
                c.drawCircle(x, y, s + 7 * density, selPaint)
                c.drawText(d.displayName().take(18), x + s + 9 * density, y + 4 * density, namePaint)
            } else if (d.level == Level.HIGH) {
                c.drawText(d.displayName().take(14), x + s + 4 * density, y + 4 * density, labelPaint)
            }
        }

        c.drawCircle(cx, cy, 3.5f * density, centerPaint)
        c.drawText("● wifi  ◆ ble  · bearing not measured", 4 * density, height - 4 * density, labelPaint)
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val maxD = 32 * density
                var best: Device? = null
                var bestD = maxD * maxD
                for (d in devices) {
                    val p = positions[d.key] ?: continue
                    val dx = p.x - e.x
                    val dy = p.y - e.y
                    val dd = dx * dx + dy * dy
                    if (dd < bestD) {
                        bestD = dd
                        best = d
                    }
                }
                best?.let { onSelect?.invoke(it) }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
