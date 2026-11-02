package com.k.airscope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Ui {
    val BG = Color.parseColor("#05090C")
    val PANEL = Color.parseColor("#0A1419")
    val CYAN = Color.parseColor("#00E5FF")
    val DIM = Color.parseColor("#4F8A99")
    val GRID = Color.parseColor("#1A3F49")
    val GREEN = Color.parseColor("#39FF88")
    val AMBER = Color.parseColor("#FFC400")
    val RED = Color.parseColor("#FF3B5C")
    val VIOLET = Color.parseColor("#B388FF")
    val SELECT_BG = Color.parseColor("#14303A")

    fun levelColor(l: Level): Int = when (l) {
        Level.LOW -> GREEN
        Level.MEDIUM -> AMBER
        Level.HIGH -> RED
    }
}

/**
 * Pseudo-3D radar drawn with Canvas (no OpenGL needed):
 *  - distance from center = estimated distance (square-root scale)
 *  - wedge around the ring = device category (phones, wearables, trackers...)
 *  - height above the floor = threat score
 *  - mapped ALPRs / self-locating drones sit at their real compass bearing
 * Drag to orbit, drag up/down to tilt, pinch to zoom, tap to select.
 */
class Radar3DView(ctx: Context) : View(ctx) {
    var onSelect: ((DeviceView) -> Unit)? = null
    /** Phone heading (compass azimuth, degrees) or null if unknown. */
    var heading: Float? = null
    var mode: Mode = Mode.ALL
    var threeD: Boolean = true
        set(v) {
            field = v
            targetPitch = if (v) 58f else 0f
            if (!v) targetYaw = 0f
        }
    /** Outer ring distance in meters (pinch to change). */
    var maxRange = 40.0
        private set

    private val dp = resources.displayMetrics.density
    private var items: List<DeviceView> = emptyList()
    private var selectedKey: String? = null

    private class Anim(var x: Float, var y: Float, var z: Float, var a: Float)

    private val anim = HashMap<String, Anim>()
    private var yaw = 0f
    private var targetYaw = 0f
    private var pitch = 58f
    private var targetPitch = 58f
    private var lastTouch = 0L
    private var sweep = 0f
    private var lastFrame = 0L

    // Per-frame scratch (no allocations while drawing)
    private val path = Path()
    private val pt = FloatArray(3)
    private var sx = FloatArray(64)
    private var sy = FloatArray(64)
    private var ss = FloatArray(64)
    private var depth = FloatArray(64)
    private var drawn: List<DeviceView> = emptyList()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * dp; color = Ui.GRID }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.DIM; textSize = 10f * dp; typeface = Typeface.MONOSPACE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11f * dp; typeface = Typeface.MONOSPACE }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            maxRange = (maxRange / d.scaleFactor).coerceIn(5.0, 150.0)
            lastTouch = SystemClock.uptimeMillis()
            return true
        }
    })

    fun update(list: List<DeviceView>, selected: String?) {
        items = list
        selectedKey = selected
    }

    private fun groundRadius(dist: Double): Float = (sqrt(min(dist, maxRange) / maxRange) * 0.95).toFloat()

    /** Ground angle (degrees, clockwise from screen-up before orbit). */
    private fun angleOf(d: DeviceView): Float {
        val b = d.bearing ?: return d.angle
        return b - (heading ?: 0f)
    }

    private fun project(x: Float, y: Float, z: Float, cx: Float, cy: Float, r: Float) {
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        val cyw = cos(yr).toFloat(); val syw = sin(yr).toFloat()
        val xr = x * cyw - y * syw
        val yy = x * syw + y * cyw
        val cp = cos(pr).toFloat(); val sp = sin(pr).toFloat()
        val v = yy * cp + z * sp
        val dpth = yy * sp - z * cp
        val s = CAM / (CAM + dpth)
        pt[0] = cx + xr * s * r
        pt[1] = cy - v * s * r
        pt[2] = s
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = now
        sweep = (sweep + dt * 90f) % 360f

        // Camera easing + slow auto-orbit in 3D when idle and nothing is selected
        val ease = 1f - exp(-dt / 0.25f)
        pitch += (targetPitch - pitch) * ease
        if (threeD && selectedKey == null && now - lastTouch > 6_000) targetYaw += dt * 3f
        yaw += (targetYaw - yaw) * ease

        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val t3 = (pitch / 58f).coerceIn(0f, 1f)
        val cx = w / 2
        val r = min(w, h) / 2 * (0.92f - 0.03f * t3)
        val cy = h / 2 + t3 * r * 0.06f

        // Floor
        fill.color = Ui.PANEL
        fill.alpha = 255
        polyCircle(1f, cx, cy, r)
        c.drawPath(path, fill)

        // Category wedges
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0)
            project(0f, 0f, 0f, cx, cy, r); val x0 = pt[0]; val y0 = pt[1]
            project(sin(a).toFloat(), cos(a).toFloat(), 0f, cx, cy, r)
            line.color = Ui.GRID; line.alpha = 200
            c.drawLine(x0, y0, pt[0], pt[1], line)
        }
        if (mode == Mode.ALL) {
            for (cat in Category.values()) {
                val a = Math.toRadians(cat.sector * 45.0 + 22.5)
                project((sin(a) * 1.07).toFloat(), (cos(a) * 1.07).toFloat(), 0f, cx, cy, r)
                text.color = Ui.DIM
                val tw = text.measureText(cat.short)
                c.drawText(cat.short, pt[0] - tw / 2, pt[1] + 4 * dp, text)
            }
        } else mode.category?.let { cat ->
            // Highlight the active wedge
            path.reset()
            project(0f, 0f, 0f, cx, cy, r); path.moveTo(pt[0], pt[1])
            for (s in 0..9) {
                val a = Math.toRadians(cat.sector * 45.0 + s * 5.0)
                project(sin(a).toFloat(), cos(a).toFloat(), 0f, cx, cy, r); path.lineTo(pt[0], pt[1])
            }
            path.close()
            fill.color = Ui.CYAN; fill.alpha = 28
            c.drawPath(path, fill)
        }

        // Range rings
        val rings = doubleArrayOf(0.05, 0.125, 0.25, 0.5, 1.0)
        line.color = Ui.GRID; line.alpha = 255
        for (fr in rings) {
            val rr = groundRadius(fr * maxRange)
            polyCircle(rr, cx, cy, r)
            c.drawPath(path, line)
            // Label on the right-hand side of each ring so they don't stack when tilted
            val la = Math.toRadians(100.0)
            project((sin(la) * rr).toFloat(), (cos(la) * rr).toFloat(), 0f, cx, cy, r)
            text.color = Ui.DIM
            c.drawText(fmtDist(fr * maxRange) + "m", pt[0] - 6 * dp, pt[1] + 11 * dp, text)
        }

        // Sweep (fan with fading alpha)
        for (k in 0 until 12) {
            val a0 = Math.toRadians((sweep - (k + 1) * 4).toDouble())
            val a1 = Math.toRadians((sweep - k * 4).toDouble())
            path.reset()
            project(0f, 0f, 0f, cx, cy, r); path.moveTo(pt[0], pt[1])
            project(sin(a0).toFloat(), cos(a0).toFloat(), 0f, cx, cy, r); path.lineTo(pt[0], pt[1])
            project(sin(a1).toFloat(), cos(a1).toFloat(), 0f, cx, cy, r); path.lineTo(pt[0], pt[1])
            path.close()
            fill.color = Ui.CYAN; fill.alpha = (70 * (1 - k / 12f)).toInt()
            c.drawPath(path, fill)
        }
        project(0f, 0f, 0f, cx, cy, r); val ox = pt[0]; val oy = pt[1]
        val sa = Math.toRadians(sweep.toDouble())
        project(sin(sa).toFloat(), cos(sa).toFloat(), 0f, cx, cy, r)
        line.color = Ui.CYAN; line.strokeWidth = 2f * dp
        c.drawLine(ox, oy, pt[0], pt[1], line)
        line.strokeWidth = 1.2f * dp

        // North marker (only meaningful for mapped items)
        heading?.let { hd ->
            val a = Math.toRadians((-hd).toDouble())
            project((sin(a) * 1.0).toFloat(), (cos(a) * 1.0).toFloat(), 0f, cx, cy, r)
            text.color = Ui.VIOLET
            c.drawText("N", pt[0] - 3 * dp, pt[1] - 4 * dp, text)
        }

        // Devices
        val list = items.filter { mode.matches(it.category) }
        ensureCapacity(list.size)
        val a = 1f - exp(-dt / 0.3f)
        for ((i, d) in list.withIndex()) {
            val ang = Math.toRadians(angleOf(d).toDouble())
            val gr = if (d.kind == Kind.MAP && d.dist > maxRange) 1.03f else groundRadius(d.dist)
            val tx = (sin(ang) * gr).toFloat()
            val ty = (cos(ang) * gr).toFloat()
            val tz = if (d.kind == Kind.MAP) 0.38f else 0.04f + d.score / 100f * 0.45f
            val an = anim.getOrPut(d.key) { Anim(tx, ty, 0f, 0f) }
            an.x += (tx - an.x) * a; an.y += (ty - an.y) * a; an.z += (tz - an.z) * a
            an.a += (1f - an.a) * a
            project(an.x, an.y, an.z, cx, cy, r)
            sx[i] = pt[0]; sy[i] = pt[1]; ss[i] = pt[2]
            depth[i] = -pt[2]
        }
        if (anim.size > list.size * 2 + 50) {
            val keep = list.mapTo(HashSet()) { it.key }
            anim.keys.retainAll(keep)
        }
        // Painter's algorithm: farthest (smallest perspective scale) first
        val ord = (0 until list.size).sortedByDescending { depth[it] }
        for (i in ord) {
            val d = list[i]
            val an = anim[d.key] ?: continue
            project(an.x, an.y, 0f, cx, cy, r)
            val gx = pt[0]; val gy = pt[1]
            val col = if (d.kind == Kind.MAP) Ui.VIOLET else Ui.levelColor(d.level)
            // Stem + floor shadow
            line.color = col; line.alpha = (110 * an.a).toInt()
            c.drawLine(gx, gy, sx[i], sy[i], line)
            fill.color = col; fill.alpha = (60 * an.a).toInt()
            c.drawCircle(gx, gy, 2.5f * dp * ss[i], fill)
            // Glow + body, brighter right after the sweep passes
            var delta = sweep - angleOf(d)
            delta = ((delta % 360) + 360) % 360
            val glow = 0.45f + 0.55f * (1 - delta / 360f)
            val size = (if (d.level == Level.HIGH) 6.5f else 4.8f) * dp * ss[i]
            fill.alpha = (70 * glow * an.a).toInt()
            c.drawCircle(sx[i], sy[i], size * 2.2f, fill)
            fill.alpha = (255 * an.a).toInt()
            when (d.kind) {
                Kind.WIFI -> c.drawCircle(sx[i], sy[i], size, fill)
                Kind.BLE -> {
                    path.reset()
                    path.moveTo(sx[i], sy[i] - size * 1.2f); path.lineTo(sx[i] + size * 1.2f, sy[i])
                    path.lineTo(sx[i], sy[i] + size * 1.2f); path.lineTo(sx[i] - size * 1.2f, sy[i]); path.close()
                    c.drawPath(path, fill)
                }
                Kind.MAP -> c.drawRect(sx[i] - size, sy[i] - size * 0.8f, sx[i] + size, sy[i] + size * 0.8f, fill)
            }
            val sel = d.key == selectedKey
            if (sel) {
                line.color = Color.WHITE; line.alpha = 255; line.strokeWidth = 2f * dp
                c.drawCircle(sx[i], sy[i], size + 7 * dp, line)
                line.strokeWidth = 1.2f * dp
            }
            if (sel || d.level == Level.HIGH || d.category == Category.POLICE || d.kind == Kind.MAP) {
                val p = if (sel) label else text
                p.color = if (sel) Color.WHITE else col
                val t = (if (d.kind == Kind.MAP) "${d.title.removeSuffix(" (mapped)")} ${fmtDist(d.dist)}m" else d.title).take(if (sel) 26 else 18)
                c.drawText(t, sx[i] + size + 5 * dp, sy[i] + 4 * dp, p)
            }
        }
        drawn = list

        if (list.isEmpty()) {
            text.color = Ui.DIM
            val msg = if (mode == Mode.ALL) "Nothing yet: tap START" else "No ${mode.label.lowercase()} signals right now"
            c.drawText(msg, cx - text.measureText(msg) / 2, cy, text)
        }
        text.color = Ui.DIM
        c.drawText("ring = distance · wedge = type · height = threat", 4 * dp, h - 4 * dp, text)
        postInvalidateOnAnimation()
    }

    private fun polyCircle(rr: Float, cx: Float, cy: Float, r: Float) {
        path.reset()
        for (k in 0..72) {
            val a = Math.toRadians(k * 5.0)
            project((sin(a) * rr).toFloat(), (cos(a) * rr).toFloat(), 0f, cx, cy, r)
            if (k == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
        }
        path.close()
    }

    private fun ensureCapacity(n: Int) {
        if (n <= sx.size) return
        val m = n * 2
        sx = FloatArray(m); sy = FloatArray(m); ss = FloatArray(m); depth = FloatArray(m)
    }

    // ---------- touch ----------
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1) { moved = true; return true }
                if (abs(e.x - downX) + abs(e.y - downY) > 12 * dp) moved = true
                if (moved && threeD) {
                    targetYaw += (e.x - lastX) * 0.35f
                    yaw = targetYaw
                    targetPitch = (targetPitch - (e.y - lastY) * 0.25f).coerceIn(15f, 78f)
                    pitch = targetPitch
                }
                lastX = e.x; lastY = e.y
                lastTouch = SystemClock.uptimeMillis()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) tap(e.x, e.y)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun tap(x: Float, y: Float) {
        var best: DeviceView? = null
        var bestD = (34 * dp) * (34 * dp)
        for ((i, d) in drawn.withIndex()) {
            if (i >= sx.size) break
            val dd = (sx[i] - x) * (sx[i] - x) + (sy[i] - y) * (sy[i] - y)
            if (dd < bestD) { bestD = dd; best = d }
        }
        best?.let { onSelect?.invoke(it) }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val CAM = 3.2f
    }
}
