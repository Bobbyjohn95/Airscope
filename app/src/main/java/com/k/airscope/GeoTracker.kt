package com.k.airscope

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Position (GNSS -> Kalman filter), the street map around and ahead of you, and mapped ALPRs.
 * - Asks Android for its highest-accuracy, fastest location updates and keeps the GNSS chip in
 *   full-tracking mode (no duty cycling) for better accuracy and responsiveness.
 * - Every radio reading can be tagged with where you were at its exact reception time.
 * - At highway speed it prefetches roads-only map chunks about a minute ahead of you.
 * All state is touched on the engine thread.
 */
class GeoTracker {
    val filter = PositionFilter()

    @Volatile var lat: Double? = null
        private set
    @Volatile var lon: Double? = null
        private set
    @Volatile var accuracy: Double = 0.0
        private set
    @Volatile var vx = 0.0
        private set
    @Volatile var vy = 0.0
        private set
    @Volatile var fixNanos = 0L
        private set
    var lastRawAt: Long = 0L
        private set
    val speed: Double get() = kotlin.math.hypot(vx, vy)
    val course: Double? get() = if (speed > 1.5) (Math.toDegrees(Math.atan2(vx, vy)) + 360) % 360 else null

    @Volatile var gnss: String = ""
        private set
    @Volatile var status: String = ""
        private set
    @Volatile var mapStatus: String = ""
        private set
    /** Newest first. */
    @Volatile var chunks: List<MapData> = emptyList()
        private set
    val map: MapData? get() = chunks.firstOrNull()
    @Volatile var mapVersion: Long = 0
        private set

    var alprEnabled = true
    private var alprs: List<Alpr> = emptyList()
    private var alprLat: Double? = null
    private var alprLon: Double? = null
    private var alprAt = 0L
    private var alprFetching = false
    private var alprFailAt = 0L
    private var mapFetching = false
    private var mapFailAt = 0L
    private var warn: String? = null
    private val lastAlert = HashMap<Long, Long>()
    private var lm: LocationManager? = null
    private var handler: Handler? = null
    private var app: Context? = null
    private var executor: Executor? = null
    private var lastGpsNanos = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(l)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val gnssStatus = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            var used = 0; var dual = false; var cn0 = 0.0
            val consts = sortedSetOf<String>()
            for (i in 0 until s.satelliteCount) {
                if (!s.usedInFix(i)) continue
                used++
                cn0 += s.getCn0DbHz(i)
                consts += when (s.getConstellationType(i)) {
                    GnssStatus.CONSTELLATION_GPS -> "GPS"; GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"; GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                    GnssStatus.CONSTELLATION_QZSS -> "QZSS"; GnssStatus.CONSTELLATION_IRNSS -> "NavIC"; else -> "other"
                }
                if (s.hasCarrierFrequencyHz(i) && s.getCarrierFrequencyHz(i) < 1.3e9f) dual = true
            }
            gnss = "$used/${s.satelliteCount} sats" + (if (dual) " · L1+L5" else "") +
                (if (used > 0) String.format(Locale.US, " · %.0f dB-Hz", cn0 / used) else "") +
                (if (consts.isNotEmpty()) " · " + consts.joinToString("+") else "")
        }
    }

    private val measurements = if (Build.VERSION.SDK_INT >= 31) object : GnssMeasurementsEvent.Callback() {} else null

    /** True when we know where you were at time t precisely enough to place devices. */
    fun positionAt(tNanos: Long): PositionFilter.Pos? {
        if (!filter.initialized) return null
        if (SystemClock.elapsedRealtimeNanos() - filter.lastNanos > 6_000_000_000L) return null
        val p = filter.at(tNanos) ?: return null
        return if (p.accM <= 20.0) p else null
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, h: Handler) {
        if (lm != null) return
        app = ctx.applicationContext
        handler = h
        val ex = Executor { h.post(it) }
        executor = ex
        if (chunks.isEmpty()) loadMapCache()
        if (alprs.isEmpty()) loadAlprCache()
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            warn = "Location permission needed for GPS and the map"
            return
        }
        val m = ctx.getSystemService(LocationManager::class.java) ?: return
        lm = m
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val fast = LocationRequest.Builder(500L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(0L).setMinUpdateDistanceMeters(0f).build()
                if (LocationManager.GPS_PROVIDER in m.allProviders) m.requestLocationUpdates(LocationManager.GPS_PROVIDER, fast, ex, listener)
                val coarse = LocationRequest.Builder(2_000L).setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY).build()
                if (LocationManager.NETWORK_PROVIDER in m.allProviders) m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, coarse, ex, listener)
                // Full tracking: keeps the GNSS chip from duty-cycling (Developer option "Force full GNSS measurements")
                measurements?.let {
                    try { m.registerGnssMeasurementsCallback(GnssMeasurementRequest.Builder().setFullTracking(true).build(), ex, it) } catch (_: Exception) { }
                }
            } else {
                if (LocationManager.GPS_PROVIDER in m.allProviders) m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, h.looper)
                if (LocationManager.NETWORK_PROVIDER in m.allProviders) m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2_000L, 0f, listener, h.looper)
            }
        } catch (_: Exception) { }
        try {
            if (Build.VERSION.SDK_INT >= 30) m.registerGnssStatusCallback(ex, gnssStatus) else m.registerGnssStatusCallback(gnssStatus, h)
        } catch (_: Exception) { }
        (m.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: m.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let {
            if (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 120_000_000_000L) onFix(it)
        }
        if (status.isEmpty()) status = "waiting for GPS"
    }

    fun stop() {
        val m = lm ?: return
        try { m.removeUpdates(listener) } catch (_: Exception) { }
        try { m.unregisterGnssStatusCallback(gnssStatus) } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= 31) measurements?.let { try { m.unregisterGnssMeasurementsCallback(it) } catch (_: Exception) { } }
        lm = null
    }

    private fun onFix(l: Location) {
        if (!l.hasAccuracy() || l.accuracy > 150) return
        val isGps = l.provider == LocationManager.GPS_PROVIDER
        val tN = l.elapsedRealtimeNanos
        if (isGps) lastGpsNanos = tN
        // Coarse network fixes only fill in when GPS has gone quiet (indoors).
        else if (tN - lastGpsNanos < 10_000_000_000L) return
        if (tN <= filter.lastNanos) return
        filter.update(
            PositionFilter.Fix(
                l.latitude, l.longitude, l.accuracy.toDouble(),
                if (l.hasSpeed()) l.speed.toDouble() else null,
                if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond.toDouble() else null,
                if (l.hasBearing()) l.bearing.toDouble() else null,
                if (l.hasBearingAccuracy()) l.bearingAccuracyDegrees.toDouble() else null,
                tN,
            ),
        )
        val p = filter.current() ?: return
        lat = p.lat; lon = p.lon; accuracy = p.accM; vx = p.vx; vy = p.vy; fixNanos = filter.lastNanos
        lastRawAt = System.currentTimeMillis()
        warn = null
        maybeFetchMap()
        if (alprEnabled) maybeFetchAlprs()
    }

    // ---------------------------------------------------------------- street map (chunks)

    private fun maybeFetchMap() {
        val la = lat ?: return
        val lo = lon ?: return
        val now = System.currentTimeMillis()
        if (mapFetching || now - mapFailAt < 60_000) return
        val fast = speed > 12.0 // ~27 mph+
        // Look ahead along the road at speed
        val lead = if (fast) (speed * 60).coerceAtMost(2500.0) else 0.0
        val c = course
        val tLat: Double; val tLon: Double
        if (c != null && lead > 0) {
            val pr = LocalProj(la, lo)
            tLat = pr.lat(lead * Math.cos(Math.toRadians(c))); tLon = pr.lon(lead * Math.sin(Math.toRadians(c)))
        } else { tLat = la; tLon = lo }
        val covered = chunks.any { ch ->
            Geo.distanceM(ch.lat0, ch.lon0, tLat, tLon) < ch.radiusM * 0.55 && (fast || !ch.fast) &&
                now - ch.fetchedAt < 14L * 24 * 3_600_000
        }
        if (covered) return
        mapFetching = true
        mapStatus = if (fast) "loading roads ahead…" else "downloading street map…"
        val h = handler ?: return
        Thread {
            val res = try {
                val json = download(if (fast) MapData.fastQuery(tLat, tLon) else MapData.query(tLat, tLon))
                Result.success(json to MapData.parse(json, tLat, tLon, System.currentTimeMillis(), fast))
            } catch (e: Exception) { Result.failure(e) }
            h.post {
                mapFetching = false
                res.onSuccess { (json, data) ->
                    chunks = (listOf(data) + chunks).take(4)
                    mapVersion++
                    mapStatus = "${data.roads.size} roads" + if (!data.fast) " · ${data.buildings.size} buildings" else " (highway mode)"
                    if (!data.fast) saveCache("map_cache.txt", tLat, tLon, json)
                }.onFailure {
                    mapFailAt = System.currentTimeMillis()
                    mapStatus = if (chunks.isEmpty()) "no street map (needs internet / Network permission)" else "street map (cached)"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun loadMapCache() {
        readCache("map_cache.txt")?.let { (la, lo, at, json) ->
            try {
                chunks = listOf(MapData.parse(json, la, lo, at))
                mapVersion++
                mapStatus = "street map (cached)"
            } catch (_: Exception) { }
        }
    }

    /** OSM building containing / nearest to a point, with its label. */
    fun buildingAt(la: Double, lo: Double, maxM: Double = 0.0): MapData.Building? {
        for (ch in chunks) {
            if (ch.fast || ch.buildings.isEmpty()) continue
            ch.buildingAt(ch.proj.x(lo), ch.proj.y(la), maxM)?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------- mapped ALPRs

    private fun maybeFetchAlprs() {
        val la = lat ?: return
        val lo = lon ?: return
        val now = System.currentTimeMillis()
        val moved = alprLat?.let { Geo.distanceM(it, alprLon!!, la, lo) } ?: Double.MAX_VALUE
        val stale = now - alprAt > 6 * 3_600_000L
        if (alprFetching || now - alprFailAt < 120_000 || (moved < AlprLogic.REFETCH_MOVE_M && !stale)) return
        alprFetching = true
        status = "downloading ALPR map data…"
        val h = handler ?: return
        Thread {
            val res = try { Result.success(download(AlprLogic.overpassQuery(la, lo))) } catch (e: Exception) { Result.failure(e) }
            h.post {
                alprFetching = false
                res.onSuccess { json ->
                    alprs = AlprLogic.parse(json)
                    alprLat = la; alprLon = lo; alprAt = System.currentTimeMillis()
                    status = "${alprs.size} mapped within 8 km"
                    saveCache("alpr_cache.txt", la, lo, json)
                    Engine.store.log("SYS", "Mapped ALPR data: ${alprs.size} within 8 km")
                }.onFailure {
                    alprFailAt = System.currentTimeMillis()
                    status = if (alprs.isEmpty()) "ALPR data unavailable" else "${alprs.size} mapped (cached)"
                    warn = "Couldn't download map data (no internet, or GrapheneOS Network permission off)"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun loadAlprCache() {
        readCache("alpr_cache.txt")?.let { (la, lo, at, json) ->
            try {
                alprs = AlprLogic.parse(json)
                alprLat = la; alprLon = lo; alprAt = at
                status = "${alprs.size} mapped (cached)"
            } catch (_: Exception) { }
        }
    }

    fun hits(): List<AlprHit> {
        val la = lat ?: return emptyList()
        val lo = lon ?: return emptyList()
        return AlprLogic.hits(alprs, la, lo, 3_000.0)
    }

    /** Cameras that should alert now (each at most once per 20 minutes). */
    fun dueAlerts(now: Long): List<AlprHit> {
        if (!alprEnabled || now - lastRawAt > 10_000) return emptyList()
        val out = ArrayList<AlprHit>()
        for (h in hits()) {
            if (!AlprLogic.shouldAlert(h, speed, course)) continue
            if (now - (lastAlert[h.alpr.id] ?: 0L) < 20 * 60_000L) continue
            lastAlert[h.alpr.id] = now
            out += h
        }
        return out
    }

    fun warning(): String? = warn

    // ---------------------------------------------------------------- network + cache

    private fun download(query: String): String {
        val endpoints = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")
        var last: Exception? = null
        for (ep in endpoints) {
            try {
                val c = URL(ep).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                c.doOutput = true
                c.setRequestProperty("User-Agent", "AirScope/2 (personal passive-RF app)")
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no endpoint")
    }

    private fun saveCache(name: String, la: Double, lo: Double, json: String) {
        try { File(app!!.filesDir, name).writeText("$la,$lo,${System.currentTimeMillis()}\n$json") } catch (_: Exception) { }
    }

    private data class Cached(val lat: Double, val lon: Double, val at: Long, val json: String)

    private fun readCache(name: String): Cached? = try {
        val f = File(app!!.filesDir, name)
        if (!f.exists()) null else {
            val text = f.readText()
            val nl = text.indexOf('\n')
            val head = text.substring(0, nl).split(',')
            Cached(head[0].toDouble(), head[1].toDouble(), head[2].toLong(), text.substring(nl + 1))
        }
    } catch (_: Exception) { null }
}
