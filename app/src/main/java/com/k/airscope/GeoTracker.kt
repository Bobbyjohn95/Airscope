package com.k.airscope

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GPS position, the street map around you, and mapped ALPR cameras / gunshot detectors.
 * Map and ALPR data come from OpenStreetMap (Overpass API) and are cached so they work offline.
 * All state is touched on the engine thread.
 */
class GeoTracker {
    @Volatile var lat: Double? = null
        private set
    @Volatile var lon: Double? = null
        private set
    @Volatile var accuracy: Double = 0.0
        private set
    var fixAt: Long = 0L
        private set
    var speed: Double? = null
        private set
    var course: Double? = null
        private set
    @Volatile var status: String = ""
        private set
    @Volatile var mapStatus: String = ""
        private set
    @Volatile var map: MapData? = null
        private set
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

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(l)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** True when the position is fresh and accurate enough to place devices on the map. */
    fun goodFix(now: Long): Boolean = lat != null && now - fixAt < 6_000 && accuracy <= 30.0

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, h: Handler) {
        if (lm != null) return
        app = ctx.applicationContext
        handler = h
        if (map == null) loadMapCache()
        if (alprs.isEmpty()) loadAlprCache()
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            warn = "Location permission needed for the map"
            return
        }
        val m = ctx.getSystemService(LocationManager::class.java) ?: return
        lm = m
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p in m.allProviders) {
                try { m.requestLocationUpdates(p, 1_000L, 1f, listener, h.looper) } catch (_: Exception) { }
            }
        }
        (m.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: m.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { onFix(it, stale = true) }
        if (status.isEmpty()) status = "waiting for GPS"
    }

    fun stop() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        lm = null
    }

    private fun onFix(l: Location, stale: Boolean = false) {
        if (l.hasAccuracy() && l.accuracy > 200) return
        // Prefer GPS: ignore a coarse network fix if a good GPS fix is recent.
        if (l.provider == LocationManager.NETWORK_PROVIDER && System.currentTimeMillis() - fixAt < 10_000 && accuracy < 30) return
        lat = l.latitude
        lon = l.longitude
        accuracy = if (l.hasAccuracy()) l.accuracy.toDouble() else 50.0
        fixAt = if (stale) 0L else System.currentTimeMillis()
        speed = if (l.hasSpeed()) l.speed.toDouble() else null
        course = if (l.hasBearing() && (speed ?: 0.0) > 1.5) l.bearing.toDouble() else null
        warn = null
        maybeFetchMap()
        if (alprEnabled) maybeFetchAlprs()
    }

    // ---------------------------------------------------------------- street map

    private fun maybeFetchMap() {
        val la = lat ?: return
        val lo = lon ?: return
        val now = System.currentTimeMillis()
        val m = map
        val moved = m?.let { Geo.distanceM(it.lat0, it.lon0, la, lo) } ?: Double.MAX_VALUE
        val stale = m != null && now - m.fetchedAt > 14L * 24 * 3_600_000
        if (mapFetching || now - mapFailAt < 90_000 || (moved < MapData.REFETCH_M && !stale)) return
        mapFetching = true
        mapStatus = "downloading street map…"
        val h = handler ?: return
        Thread {
            val res = try {
                val json = download(MapData.query(la, lo))
                Result.success(json to MapData.parse(json, la, lo, System.currentTimeMillis()))
            } catch (e: Exception) { Result.failure(e) }
            h.post {
                mapFetching = false
                res.onSuccess { (json, data) ->
                    map = data
                    mapVersion++
                    mapStatus = "${data.roads.size} roads · ${data.buildings.size} buildings"
                    saveCache("map_cache.txt", la, lo, json)
                }.onFailure {
                    mapFailAt = System.currentTimeMillis()
                    mapStatus = if (map == null) "no street map (needs internet / Network permission)" else "street map (cached)"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun loadMapCache() {
        readCache("map_cache.txt")?.let { (la, lo, at, json) ->
            try {
                map = MapData.parse(json, la, lo, at)
                mapVersion++
                mapStatus = "street map (cached)"
            } catch (_: Exception) { }
        }
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
        if (!alprEnabled || now - fixAt > 10_000) return emptyList()
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
