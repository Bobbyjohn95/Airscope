package com.k.airscope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
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
 * Street map (OpenStreetMap roads, buildings, water, parks) with you at the center and devices
 * placed where they actually are: exact for mapped ALPRs / drones, estimated from your movement
 * for radios. Devices whose direction isn't known yet are hollow markers at their estimated distance.
 *
 * Gestures (like a maps app): 1 finger = pan (with fling), pinch = zoom, 2-finger twist = rotate,
 * 2-finger drag up/down = tilt (3D), tap = select, double-tap = zoom in.
 */
class MapView3D(ctx: Context) : View(ctx) {
    var onSelect: ((DeviceView) -> Unit)? = null
    var mode: Mode = Mode.ALL
    /** Where the phone points (deg true north, smoothed by HeadingTracker) and how sure (+- deg). */
    var heading: Float? = null
    var headingAcc: Float = 25f
    /** Read every frame for a smooth needle. */
    var headingSource: HeadingTracker? = null
    var threeD: Boolean = true
        set(v) { field = v; tgtPitch = if (v) 52f else 0f }
    var headingUp: Boolean = false
        set(v) { field = v; if (!v) tgtYaw = 0f }
    var follow: Boolean = true
        set(v) { field = v; if (v) { flingX = 0.0; flingY = 0.0 } }

    private val dp = resources.displayMetrics.density

    // Data
    private var snap: Snapshot = Snapshot.EMPTY
    private var chunks: List<MapData> = emptyList()
    private val map: MapData? get() = chunks.firstOrNull()
    private var selectedKey: String? = null
    private var frame: LocalProj? = null
    /** Offset (m) of the chunk being drawn, relative to the drawing frame. */
    private var offX = 0.0
    private var offY = 0.0
    private var roadLabelCount = 0

    // Camera (meters in `frame`)
    private var camX = 0.0
    private var camY = 0.0
    private var rangeM = 180.0
    private var tgtRange = 180.0
    private var yaw = 0f
    private var tgtYaw = 0f
    private var pitch = 52f
    private var tgtPitch = 52f
    private var flingX = 0.0
    private var flingY = 0.0
    private var lastFrame = 0L
    private var pulse = 0f

    // Per-frame projection constants
    private var cY = 1f; private var sY = 0f; private var cP = 1f; private var sP = 0f
    private var ccx = 0f; private var ccy = 0f; private var rad = 1f
    private val pr = FloatArray(3)

    // Scratch
    private val path = Path()
    private var seg = FloatArray(4096)
    private val hitX = ArrayList<Float>()
    private val hitY = ArrayList<Float>()
    private val hitD = ArrayList<DeviceView>()
    /** rel = position stored relative to you (unplaced devices ride along with you, no lag at speed). */
    private class Anim(var x: Double, var y: Double, var z: Double, var a: Float, var rel: Boolean)
    private val anim = HashMap<String, Anim>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; pathEffect = DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.DIM; textSize = 10f * dp; typeface = Typeface.MONOSPACE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11f * dp; typeface = Typeface.MONOSPACE }
    private val roadLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8FD9E6"); textSize = 9.5f * dp; typeface = Typeface.MONOSPACE }

    private val roadColors = intArrayOf(
        Color.parseColor("#36D6F0"), Color.parseColor("#2AA6BA"), Color.parseColor("#1E7486"),
        Color.parseColor("#174F5C"), Color.parseColor("#2E5A47"),
    )
    private val roadWidths = floatArrayOf(4.2f, 3.0f, 2.0f, 1.3f, 1.0f)

    /** [cs] = street-map chunks, newest first (the newest one defines the drawing frame). */
    fun update(s: Snapshot, cs: List<MapData>, selected: String?) {
        snap = s
        selectedKey = selected
        if (cs !== chunks) {
            chunks = cs
            val nf = cs.firstOrNull()?.proj
            val old = frame
            if (nf != null && (old == null || old.lat0 != nf.lat0 || old.lon0 != nf.lon0)) {
                if (old != null) {
                    // Re-express everything in the new frame so nothing jumps
                    val sx = old.x(nf.lon0); val sy = old.y(nf.lat0)
                    camX -= sx; camY -= sy
                    for (an in anim.values) if (!an.rel) { an.x -= sx; an.y -= sy }
                }
                frame = nf
            }
        }
        if (frame == null && s.myLat != null) frame = LocalProj(s.myLat, s.myLon!!).also { camX = 0.0; camY = 0.0 }
    }

    private val nowNanos: Long get() = SystemClock.elapsedRealtimeNanos()

    /** Your position predicted to this exact frame from the last fix + velocity (smooth at highway speed). */
    private fun userXY(f: LocalProj): Pair<Double, Double>? {
        val la = snap.myLat ?: return null
        val lo = snap.myLon ?: return null
        val age = if (snap.fixNanos > 0) ((nowNanos - snap.fixNanos) / 1e9).coerceIn(0.0, 3.0) else 0.0
        return (f.x(lo) + snap.myVx * age) to (f.y(la) + snap.myVy * age)
    }

    private val speed: Double get() = hypot(snap.myVx, snap.myVy)

    fun zoomBy(f: Double) { tgtRange = (tgtRange * f).coerceIn(MIN_RANGE, MAX_RANGE) }

    // ------------------------------------------------------------------ projection

    private fun project(x: Double, y: Double, z: Double): Boolean {
        val dx = (x + offX - camX).toFloat(); val dy = (y + offY - camY).toFloat()
        val xr = dx * cY - dy * sY
        val yr = dx * sY + dy * cY
        val r = rangeM.toFloat()
        val u = xr / r; val v = yr / r; val w = z.toFloat() / r * Z_EXAG
        val vs = v * cP + w * sP
        val depth = v * sP - w * cP
        val den = CAM + depth
        if (den < 0.35f) return false
        val s = CAM / den
        pr[0] = ccx + u * s * rad
        pr[1] = ccy - vs * s * rad
        pr[2] = s
        return s < 6f
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016 else ((now - lastFrame) / 1000.0).coerceAtMost(0.1)
        lastFrame = now
        headingSource?.let { h -> heading = h.facing?.toFloat(); headingAcc = h.facingAcc.toFloat() }
        stepCamera(dt)

        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t3 = (pitch / 52f).coerceIn(0f, 1.2f)
        ccx = w / 2
        rad = min(w, h) / 2 * 0.95f
        ccy = h / 2 + t3 * h * 0.16f
        val yr = Math.toRadians(yaw.toDouble()); val prr = Math.toRadians(pitch.toDouble())
        cY = cos(yr).toFloat(); sY = sin(yr).toFloat(); cP = cos(prr).toFloat(); sP = sin(prr).toFloat()

        c.drawColor(Ui.BG)
        val f = frame
        val me = f?.let { userXY(it) }
        val ux = me?.first ?: camX
        val uy = me?.second ?: camY
        val ext = rangeM * (if (pitch > 20f) 3.2 else 1.7)

        // Ground plate + horizon fade in 3D
        if (pitch > 5f) {
            fill.color = Ui.PANEL; fill.alpha = 255
            groundCircle(camX, camY, rangeM * 3.0, 64)
            c.drawPath(path, fill)
        }

        if (f != null && chunks.isNotEmpty()) {
            // Older chunks first so the newest (most detailed, nearest you) draws on top
            val order = chunks.asReversed()
            for (m in order) withChunk(f, m) { drawAreas(c, m, ext) }
            for (m in order) withChunk(f, m) { drawRoads(c, m, ext) }
            for ((i, m) in chunks.withIndex()) withChunk(f, m) { drawBuildings(c, m, ext, f, i) }
            val seen = HashSet<String>(); roadLabelCount = 0
            for (m in chunks) withChunk(f, m) { drawRoadLabels(c, m, ext, seen) }
        } else drawGrid(c)

        drawUser(c, ux, uy, now)
        drawDevices(c, f, ux, uy, dt)
        drawOverlay(c, w, h)
        postInvalidateOnAnimation()
    }

    private inline fun withChunk(f: LocalProj, m: MapData, block: () -> Unit) {
        offX = f.x(m.lon0); offY = f.y(m.lat0)
        block()
        offX = 0.0; offY = 0.0
    }

    private fun stepCamera(dt: Double) {
        val f = frame
        val me = f?.let { userXY(it) }
        if (follow && me != null) {
            // Move with your velocity, then close any remaining gap: no trailing lag at speed
            camX += snap.myVx * dt; camY += snap.myVy * dt
            val a = 1 - exp(-dt / 0.25)
            camX += (me.first - camX) * a; camY += (me.second - camY) * a
        } else if (flingX != 0.0 || flingY != 0.0) {
            camX += flingX * dt; camY += flingY * dt
            val decay = exp(-dt / 0.35)
            flingX *= decay; flingY *= decay
            if (hypot(flingX, flingY) < rangeM * 0.02) { flingX = 0.0; flingY = 0.0 }
        }
        val a = (1 - exp(-dt / 0.22)).toFloat()
        rangeM = exp(ln(rangeM) + (ln(tgtRange) - ln(rangeM)) * a)
        if (headingUp) {
            // Driving: turn the map with your GPS course (a compass inside a car is unreliable)
            val crs = snap.myCourse
            if (speed > 5.0 && crs != null) tgtYaw = crs.toFloat() else heading?.let { tgtYaw = it }
        }
        var dy = ((tgtYaw - yaw) % 360 + 540) % 360 - 180
        if (abs(dy) < 0.05f) dy = 0f
        yaw = (yaw + dy * a + 360) % 360
        pitch += (tgtPitch - pitch) * a
        pulse = (pulse + dt.toFloat() / 2.4f) % 1f
    }

    private fun groundCircle(x: Double, y: Double, r: Double, n: Int) {
        path.reset()
        var first = true
        for (k in 0..n) {
            val a = 2 * Math.PI * k / n
            if (!project(x + r * sin(a), y + r * cos(a), 0.0)) continue
            if (first) { path.moveTo(pr[0], pr[1]); first = false } else path.lineTo(pr[0], pr[1])
        }
        path.close()
    }

    private fun polyPath(pts: FloatArray, z: Double): Boolean {
        path.reset()
        var any = false
        var i = 0
        while (i < pts.size) {
            if (project(pts[i].toDouble(), pts[i + 1].toDouble(), z)) {
                if (!any) { path.moveTo(pr[0], pr[1]); any = true } else path.lineTo(pr[0], pr[1])
            }
            i += 2
        }
        if (any) path.close()
        return any
    }

    private fun visible(minX: Float, minY: Float, maxX: Float, maxY: Float, ext: Double): Boolean {
        val cx = camX - offX; val cy = camY - offY
        return maxX >= cx - ext && minX <= cx + ext && maxY >= cy - ext && minY <= cy + ext
    }

    private fun drawAreas(c: Canvas, m: MapData, ext: Double) {
        for (a in m.areas) {
            if (!visible(a.minX, a.minY, a.maxX, a.maxY, ext)) continue
            if (!polyPath(a.pts, 0.0)) continue
            fill.color = if (a.kind == 0) Color.parseColor("#0B2A44") else Color.parseColor("#0C2A1E")
            fill.alpha = 255
            c.drawPath(path, fill)
        }
    }

    private fun drawRoads(c: Canvas, m: MapData, ext: Double) {
        val zoomW = (180.0 / rangeM).pow(0.35).toFloat().coerceIn(0.5f, 1.8f)
        for (cls in 4 downTo 0) {
            if (cls >= 3 && rangeM > 900) continue
            var n = 0
            for (r in m.roads) {
                if (r.cls != cls || !visible(r.minX, r.minY, r.maxX, r.maxY, ext)) continue
                var px = 0f; var py = 0f; var have = false
                var i = 0
                while (i < r.pts.size) {
                    val ok = project(r.pts[i].toDouble(), r.pts[i + 1].toDouble(), 0.0)
                    if (ok && have) {
                        if (n + 4 > seg.size) seg = seg.copyOf(seg.size * 2)
                        seg[n++] = px; seg[n++] = py; seg[n++] = pr[0]; seg[n++] = pr[1]
                    }
                    if (ok) { px = pr[0]; py = pr[1]; have = true } else have = false
                    i += 2
                }
            }
            if (n == 0) continue
            road.color = roadColors[cls]
            road.strokeWidth = roadWidths[cls] * dp * zoomW
            c.drawLines(seg, 0, n, road)
        }
    }

    private fun drawBuildings(c: Canvas, m: MapData, ext: Double, f: LocalProj, idx: Int) {
        if (rangeM > 1200 || m.buildings.isEmpty()) return
        val cx = camX - offX; val cy = camY - offY
        // Skip buildings a newer chunk already covers (overlapping chunks)
        val newer = chunks.subList(0, idx).filter { it.buildings.isNotEmpty() }
            .map { Triple(f.x(it.lon0) - offX, f.y(it.lat0) - offY, MapData.DETAIL_RADIUS * 0.92) }
        val near = m.buildings.filter { b ->
            abs(b.cx - cx) < ext && abs(b.cy - cy) < ext && newer.none { (nx, ny, r) -> hypot(b.cx - nx, b.cy - ny) < r }
        }
            .sortedByDescending { hypot(it.cx - cx, it.cy - cy) }
            .takeLast(500)
        val tall = pitch > 8f
        for (b in near) {
            if (!polyPath(b.pts, 0.0)) continue
            fill.color = Color.parseColor("#0F2E38"); fill.alpha = 255
            c.drawPath(path, fill)
            if (!tall) {
                stroke.color = Color.parseColor("#1E5A68"); stroke.strokeWidth = 1f * dp; stroke.alpha = 255
                c.drawPath(path, stroke)
                continue
            }
            // Extruded walls (edges) + roof
            stroke.color = Color.parseColor("#2A8A9C"); stroke.strokeWidth = 0.8f * dp; stroke.alpha = 150
            val hgt = b.heightM.toDouble()
            var i = 0
            while (i < b.pts.size) {
                val x = b.pts[i].toDouble(); val y = b.pts[i + 1].toDouble()
                if (project(x, y, 0.0)) {
                    val bx = pr[0]; val by = pr[1]
                    if (project(x, y, hgt)) c.drawLine(bx, by, pr[0], pr[1], stroke)
                }
                i += 2
            }
            if (polyPath(b.pts, hgt)) {
                fill.color = Color.parseColor("#123C48"); fill.alpha = 225
                c.drawPath(path, fill)
                stroke.alpha = 255; stroke.color = Color.parseColor("#3FB7CC")
                c.drawPath(path, stroke)
            }
        }
    }

    private fun drawRoadLabels(c: Canvas, m: MapData, ext: Double, seen: HashSet<String>) {
        if (rangeM > 1500) return
        val labelExt = rangeM * 0.9
        val camX = this.camX - offX; val camY = this.camY - offY // chunk-local camera
        for (r in m.roads.asReversed()) {
            if (roadLabelCount >= 12) break
            val name = r.name ?: continue
            if (r.cls > 2 || name in seen || !visible(r.minX, r.minY, r.maxX, r.maxY, labelExt)) continue
            // Point along this road nearest the camera (projected onto each segment)
            var bx = 0.0; var by = 0.0; var bd = Double.MAX_VALUE
            var i = 0
            while (i + 3 < r.pts.size) {
                val x1 = r.pts[i].toDouble(); val y1 = r.pts[i + 1].toDouble(); val x2 = r.pts[i + 2].toDouble(); val y2 = r.pts[i + 3].toDouble()
                val vx = x2 - x1; val vy = y2 - y1; val l2 = vx * vx + vy * vy
                val t = if (l2 < 1e-6) 0.0 else (((camX - x1) * vx + (camY - y1) * vy) / l2).coerceIn(0.0, 1.0)
                val qx = x1 + t * vx; val qy = y1 + t * vy
                val d = hypot(qx - camX, qy - camY)
                if (d < bd) { bd = d; bx = qx; by = qy }
                i += 2
            }
            if (bd > labelExt || !project(bx, by, 0.0)) continue
            seen += name
            roadLabelCount++
            val tw = roadLabel.measureText(name)
            fill.color = Ui.BG; fill.alpha = 170
            c.drawRect(pr[0] - tw / 2 - 3 * dp, pr[1] - 10 * dp, pr[0] + tw / 2 + 3 * dp, pr[1] + 3 * dp, fill)
            c.drawText(name, pr[0] - tw / 2, pr[1], roadLabel)
        }
    }

    private fun drawGrid(c: Canvas) {
        stroke.color = Ui.GRID; stroke.strokeWidth = 1f * dp; stroke.alpha = 255
        for (fr in doubleArrayOf(0.25, 0.5, 1.0)) {
            groundCircle(camX, camY, rangeM * fr, 64)
            c.drawPath(path, stroke)
        }
    }

    private fun drawUser(c: Canvas, ux: Double, uy: Double, now: Long) {
        if (snap.myLat == null) return
        // Accuracy circle
        if (snap.myAccM > 3) {
            groundCircle(ux, uy, snap.myAccM, 40)
            fill.color = Ui.CYAN; fill.alpha = 28
            c.drawPath(path, fill)
        }
        // Scan pulse
        if (snap.scanning) {
            groundCircle(ux, uy, rangeM * 0.6 * pulse + 2, 48)
            stroke.color = Ui.CYAN; stroke.strokeWidth = 1.5f * dp; stroke.alpha = (160 * (1 - pulse)).toInt()
            c.drawPath(path, stroke)
        }
        // Facing cone: its width IS the compass uncertainty (narrow = sure, wide = unsure)
        heading?.let { hd ->
            val half = headingAcc.coerceIn(5f, 60f).toDouble()
            val a0 = Math.toRadians(hd - half); val a1 = Math.toRadians(hd + half); val len = rangeM * 0.26
            path.reset()
            if (project(ux, uy, 0.0)) path.moveTo(pr[0], pr[1])
            for (k in 0..10) {
                val a = a0 + (a1 - a0) * k / 10
                if (project(ux + len * sin(a), uy + len * cos(a), 0.0)) path.lineTo(pr[0], pr[1])
            }
            path.close()
            fill.color = Ui.CYAN; fill.alpha = 60
            c.drawPath(path, fill)
            // Center line = best estimate
            if (project(ux, uy, 0.0)) {
                val bx = pr[0]; val by = pr[1]
                val a = Math.toRadians(hd.toDouble())
                if (project(ux + len * 1.15 * sin(a), uy + len * 1.15 * cos(a), 0.0)) {
                    stroke.color = Ui.CYAN; stroke.strokeWidth = 1.4f * dp; stroke.alpha = 200
                    c.drawLine(bx, by, pr[0], pr[1], stroke)
                }
            }
        }
        if (!project(ux, uy, 0.0)) return
        val px = pr[0]; val py = pr[1]
        val crs = snap.myCourse
        if (crs != null && speed > 1.5) {
            // Moving: navigation arrow along your GPS course
            val a = Math.toRadians(crs)
            if (project(ux + sin(a) * rangeM * 0.05, uy + cos(a) * rangeM * 0.05, 0.0)) {
                var dx = pr[0] - px; var dy = pr[1] - py
                val l = hypot(dx, dy).coerceAtLeast(0.001f); dx /= l; dy /= l
                val s = 11f * dp
                path.reset()
                path.moveTo(px + dx * s * 1.3f, py + dy * s * 1.3f)
                path.lineTo(px - dx * s * 0.8f + dy * s * 0.85f, py - dy * s * 0.8f - dx * s * 0.85f)
                path.lineTo(px - dx * s * 0.35f, py - dy * s * 0.35f)
                path.lineTo(px - dx * s * 0.8f - dy * s * 0.85f, py - dy * s * 0.8f + dx * s * 0.85f)
                path.close()
                fill.color = Color.WHITE; fill.alpha = 255
                stroke.color = Color.WHITE; stroke.strokeWidth = 3f * dp; stroke.alpha = 255
                c.drawPath(path, stroke)
                fill.color = Ui.CYAN
                c.drawPath(path, fill)
                return
            }
        }
        fill.color = Color.WHITE; fill.alpha = 255
        c.drawCircle(px, py, 7.5f * dp, fill)
        fill.color = Ui.CYAN
        c.drawCircle(px, py, 5.5f * dp, fill)
    }

    private fun drawDevices(c: Canvas, f: LocalProj?, ux: Double, uy: Double, dt: Double) {
        hitX.clear(); hitY.clear(); hitD.clear()
        val list = snap.devices.filter { mode.matches(it.category) }
        val a = 1 - exp(-dt / 0.45)

        class Item(val d: DeviceView, val x: Double, val y: Double, val z: Double, val placed: Boolean, var depth: Float = 0f)
        val items = ArrayList<Item>(list.size)
        for (d in list) {
            val placed = d.lat != null && f != null
            val cand = if (!placed && f != null) d.candA else null
            val rel = !placed && cand == null
            val tx: Double; val ty: Double
            when {
                placed -> { tx = f!!.x(d.lon!!); ty = f.y(d.lat!!) }
                cand != null -> { tx = f!!.x(cand.second); ty = f.y(cand.first) }
                else -> {
                    // Direction unknown: shown at its distance, positioned relative to you
                    val ang = Math.toRadians(d.angle.toDouble())
                    val r = d.dist.coerceAtMost(rangeM * 1.4)
                    tx = r * sin(ang); ty = r * cos(ang)
                }
            }
            val tz = when {
                d.kind == Kind.MAP -> 7.0
                !placed -> 0.0
                else -> (6.0 + d.score * 0.3) * (rangeM / 180.0).coerceIn(0.5, 4.0)
            }
            val an = anim.getOrPut(d.key) { Anim(tx, ty, tz, 0f, rel) }
            if (an.rel != rel) { // switch between relative / absolute without a jump
                if (rel) { an.x -= ux; an.y -= uy } else { an.x += ux; an.y += uy }
                an.rel = rel
            }
            an.x += (tx - an.x) * a; an.y += (ty - an.y) * a; an.z += (tz - an.z) * a
            an.a += ((1f - an.a) * a).toFloat()
            items += Item(d, if (rel) an.x + ux else an.x, if (rel) an.y + uy else an.y, an.z, placed)
        }
        if (anim.size > list.size * 2 + 50) { val keep = list.mapTo(HashSet()) { it.key }; anim.keys.retainAll(keep) }
        for (it in items) it.depth = if (project(it.x, it.y, it.z)) pr[2] else 99f
        items.sortBy { it.depth } // far (small scale) first

        for (it in items) {
            val d = it.d
            val alpha = (anim[d.key]?.a ?: 1f)
            val col = if (d.kind == Kind.MAP) Ui.VIOLET else Ui.levelColor(d.level)
            val sel = d.key == selectedKey

            if (it.placed) {
                // Uncertainty halo / camera view cone on the ground
                if (d.kind == Kind.MAP) {
                    Geo.parseDirection(d.facts.firstOrNull { f2 -> f2.first == "Camera faces" }?.second)?.let { dir ->
                        val a0 = Math.toRadians(dir - 22); val a1 = Math.toRadians(dir + 22); val len = 45.0
                        path.reset()
                        if (project(it.x, it.y, 0.0)) path.moveTo(pr[0], pr[1])
                        for (k in 0..6) {
                            val aa = a0 + (a1 - a0) * k / 6
                            if (project(it.x + len * sin(aa), it.y + len * cos(aa), 0.0)) path.lineTo(pr[0], pr[1])
                        }
                        path.close()
                        fill.color = Ui.VIOLET; fill.alpha = (60 * alpha).toInt()
                        c.drawPath(path, fill)
                    }
                } else if (d.errM > 0) {
                    groundCircle(it.x, it.y, d.errM, 32)
                    fill.color = col; fill.alpha = ((if (sel) 45 else 22) * alpha).toInt()
                    c.drawPath(path, fill)
                }
                // Stem
                if (project(it.x, it.y, 0.0)) {
                    val gx = pr[0]; val gy = pr[1]
                    if (project(it.x, it.y, it.z)) {
                        stroke.color = col; stroke.strokeWidth = 1.6f * dp; stroke.alpha = (180 * alpha).toInt()
                        c.drawLine(gx, gy, pr[0], pr[1], stroke)
                    }
                }
            } else if (d.candB != null && f != null) {
                // Two equally good spots (mirror ambiguity): both shown, joined by a dashed line
                val bx = f.x(d.candB.second); val by = f.y(d.candB.first)
                if (project(it.x, it.y, 0.0)) {
                    val ax = pr[0]; val ay = pr[1]
                    if (project(bx, by, 0.0)) {
                        dash.color = col; dash.alpha = ((if (sel) 230 else 120) * alpha).toInt()
                        c.drawLine(ax, ay, pr[0], pr[1], dash)
                        stroke.color = col; stroke.strokeWidth = 1.6f * dp; stroke.alpha = (200 * alpha).toInt()
                        val sz = 5f * dp * pr[2].coerceIn(0.6f, 1.6f)
                        shape(c, d.kind, pr[0], pr[1], sz, stroke)
                        if (sel) { text.color = col; c.drawText("or here?", pr[0] + sz + 4 * dp, pr[1] + 3 * dp, text) }
                        hitX += pr[0]; hitY += pr[1]; hitD += d
                    }
                }
            } else if (sel && snap.myLat != null) {
                // Direction unknown: show the distance band around you
                groundCircle(ux, uy, d.dist, 64)
                dash.color = col; dash.alpha = 220
                c.drawPath(path, dash)
                groundCircle(ux, uy, d.hi, 64); dash.alpha = 90; c.drawPath(path, dash)
                groundCircle(ux, uy, d.lo, 64); c.drawPath(path, dash)
            }

            if (!project(it.x, it.y, it.z)) continue
            val sx = pr[0]; val sy = pr[1]
            val size = ((if (d.level == Level.HIGH) 6.5f else 5f) * dp * pr[2].coerceIn(0.6f, 1.6f))
            if (it.placed) {
                fill.color = col; fill.alpha = (70 * alpha).toInt()
                c.drawCircle(sx, sy, size * 2f, fill)
                fill.alpha = (255 * alpha).toInt()
                shape(c, d.kind, sx, sy, size, fill)
            } else {
                stroke.color = col; stroke.strokeWidth = 1.8f * dp; stroke.alpha = (200 * alpha).toInt()
                shape(c, d.kind, sx, sy, size, stroke)
            }
            if (sel) {
                stroke.color = Color.WHITE; stroke.strokeWidth = 2f * dp; stroke.alpha = 255
                c.drawCircle(sx, sy, size + 7 * dp, stroke)
            }
            if (sel || d.level == Level.HIGH || d.category == Category.POLICE) {
                val p = if (sel) label else text
                p.color = if (sel) Color.WHITE else col
                val t = (if (d.kind == Kind.MAP) d.title.removeSuffix(" (mapped)") else d.title) + if (!it.placed) " ?" else ""
                val s = t.take(if (sel) 28 else 18)
                fill.color = Ui.BG; fill.alpha = 150
                c.drawRect(sx + size + 3 * dp, sy - 8 * dp, sx + size + 6 * dp + p.measureText(s), sy + 4 * dp, fill)
                c.drawText(s, sx + size + 5 * dp, sy + 3 * dp, p)
            }
            hitX += sx; hitY += sy; hitD += d
        }
    }

    private fun shape(c: Canvas, kind: Kind, x: Float, y: Float, s: Float, p: Paint) {
        when (kind) {
            Kind.WIFI -> c.drawCircle(x, y, s, p)
            Kind.BLE -> {
                path.reset()
                path.moveTo(x, y - s * 1.25f); path.lineTo(x + s * 1.25f, y); path.lineTo(x, y + s * 1.25f); path.lineTo(x - s * 1.25f, y); path.close()
                c.drawPath(path, p)
            }
            Kind.MAP -> c.drawRect(x - s, y - s * 0.8f, x + s, y + s * 0.8f, p)
        }
    }

    private fun drawOverlay(c: Canvas, w: Float, h: Float) {
        // North arrow (top-left)
        val nx = 22 * dp; val ny = 22 * dp
        fill.color = Ui.BG; fill.alpha = 190
        c.drawCircle(nx, ny, 15 * dp, fill)
        val a = Math.toRadians(-yaw.toDouble())
        stroke.color = Ui.RED; stroke.strokeWidth = 2.5f * dp; stroke.alpha = 255
        c.drawLine(nx, ny, nx + (11 * dp * sin(a)).toFloat(), ny - (11 * dp * cos(a)).toFloat(), stroke)
        stroke.color = Ui.DIM
        c.drawLine(nx, ny, nx - (8 * dp * sin(a)).toFloat(), ny + (8 * dp * cos(a)).toFloat(), stroke)
        text.color = Color.WHITE
        c.drawText("N", nx + (17 * dp * sin(a)).toFloat() - 3 * dp, ny - (17 * dp * cos(a)).toFloat() + 4 * dp, text)

        // Scale bar (at screen center scale)
        val mPerPx = rangeM / rad
        val target = mPerPx * w * 0.22
        val nice = doubleArrayOf(5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0).lastOrNull { it <= target } ?: 5.0
        val px = (nice / mPerPx).toFloat()
        val by = h - 22 * dp
        stroke.color = Color.WHITE; stroke.strokeWidth = 2f * dp; stroke.alpha = 220
        c.drawLine(10 * dp, by, 10 * dp + px, by, stroke)
        c.drawLine(10 * dp, by - 4 * dp, 10 * dp, by + 1 * dp, stroke)
        c.drawLine(10 * dp + px, by - 4 * dp, 10 * dp + px, by + 1 * dp, stroke)
        text.color = Color.WHITE
        c.drawText((if (nice >= 1000) "${(nice / 1000).toInt()} km" else "${nice.toInt()} m") + if (pitch > 10) " (center)" else "", 10 * dp, by - 6 * dp, text)

        fill.color = Ui.BG; fill.alpha = 200
        c.drawRect(0f, h - 18 * dp, w, h, fill)
        text.color = Ui.DIM
        val attr = "© OpenStreetMap"
        c.drawText(attr, w - text.measureText(attr) - 6 * dp, h - 6 * dp, text)
        c.drawText("● located  ○ walk to locate  ┄ 2 possible spots", 10 * dp, h - 6 * dp, text)

        val msg = when {
            snap.myLat == null -> "Waiting for GPS… (go outside or near a window)"
            map == null -> "Street map: " + snap.mapStatus.ifEmpty { "loading…" }
            else -> null
        }
        if (msg != null) {
            label.color = Ui.AMBER
            c.drawText(msg, w / 2 - label.measureText(msg) / 2, 20 * dp, label)
        }
        if (!follow) {
            text.color = Ui.AMBER
            c.drawText("free view · tap ◎ to follow", w / 2 - text.measureText("free view · tap ◎ to follow") / 2, h - 36 * dp, text)
        }
    }

    // ------------------------------------------------------------------ gestures

    private var twoFinger = false
    private var rotating = false
    private var tilting = false
    private var lastAngle = 0f
    private var lastFocusY = 0f
    private var accRot = 0f
    private var accTilt = 0f
    private var startSpan = 0f
    private var multiTouched = false

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            flingX = 0.0; flingY = 0.0
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (twoFinger || e2.pointerCount > 1) return true
            val (wx, wy) = screenToWorld(dx, dy)
            camX += wx; camY += wy
            follow = false
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (twoFinger || multiTouched || e2.pointerCount > 1) return true
            val (wx, wy) = screenToWorld(-vx, -vy)
            flingX = wx; flingY = wy
            follow = false
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { tap(e.x, e.y); return true }

        override fun onDoubleTap(e: MotionEvent): Boolean { zoomBy(0.5); return true }
    })

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (tilting) return true
            rangeM = (rangeM / d.scaleFactor).coerceIn(MIN_RANGE, MAX_RANGE)
            tgtRange = rangeM
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    /** Screen-pixel drag (dx right, dy down as reported by onScroll distances) -> world meters. */
    private fun screenToWorld(dx: Float, dy: Float): Pair<Double, Double> {
        val mPerPx = rangeM / rad
        val fy = 1.0 / cos(Math.toRadians(pitch.toDouble())).coerceAtLeast(0.45)
        val sxr = dx * mPerPx
        val syr = -dy * mPerPx * fy
        val cy = cos(Math.toRadians(yaw.toDouble())); val sy = sin(Math.toRadians(yaw.toDouble()))
        return (sxr * cy + syr * sy) to (-sxr * sy + syr * cy)
    }

    private fun angleOf(e: MotionEvent): Float =
        Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    private fun spanOf(e: MotionEvent): Float = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                twoFinger = true; rotating = false; tilting = false; accRot = 0f; accTilt = 0f
                lastAngle = angleOf(e); lastFocusY = (e.getY(0) + e.getY(1)) / 2; startSpan = spanOf(e)
            }
            MotionEvent.ACTION_MOVE -> if (twoFinger && e.pointerCount >= 2) {
                val ang = angleOf(e)
                var da = ang - lastAngle
                da = ((da + 540) % 360) - 180
                lastAngle = ang
                val fy = (e.getY(0) + e.getY(1)) / 2
                val dfy = fy - lastFocusY
                lastFocusY = fy
                accRot += da
                accTilt += dfy
                val spanChange = abs(spanOf(e) - startSpan)
                if (!rotating && !tilting) {
                    if (abs(accRot) > 10f) rotating = true
                    else if (abs(accTilt) > 28 * dp && spanChange < 40 * dp) tilting = true
                }
                if (rotating) {
                    yaw = (yaw - da + 360) % 360; tgtYaw = yaw
                    headingUp = false
                }
                if (tilting) {
                    pitch = (pitch - dfy * 0.22f).coerceIn(0f, 65f); tgtPitch = pitch
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount - 1 < 2) {
                twoFinger = false
                if (tilting) threeD = pitch > 6f
                rotating = false; tilting = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { twoFinger = false; rotating = false; tilting = false }
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) multiTouched = false
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) multiTouched = true
        // Always feed the detector every event: it re-anchors on finger up/down, so no jumps.
        gestures.onTouchEvent(e)
        return true
    }

    private fun tap(x: Float, y: Float) {
        var best: DeviceView? = null
        var bd = (38 * dp) * (38 * dp)
        for (i in hitD.indices) {
            val dd = (hitX[i] - x) * (hitX[i] - x) + (hitY[i] - y) * (hitY[i] - y)
            if (dd <= bd) { bd = dd; best = hitD[i] } // later = nearer to camera, wins ties
        }
        best?.let { onSelect?.invoke(it) }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        private const val CAM = 3.4f
        private const val Z_EXAG = 1.6f
        const val MIN_RANGE = 20.0
        const val MAX_RANGE = 3000.0
    }
}
