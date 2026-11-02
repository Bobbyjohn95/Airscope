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
 * GPS position + mapped ALPR cameras / gunshot detectors from OpenStreetMap (DeFlock data),
 * fetched for an ~8 km radius and cached so it keeps working offline.
 * All state is touched on the engine thread.
 */
class GeoTracker {
    @Volatile var lat: Double? = null
        private set
    @Volatile var lon: Double? = null
        private set
    var speed: Double? = null
        private set
    var course: Double? = null
        private set
    @Volatile var status: String = ""
        private set

    private var alprs: List<Alpr> = emptyList()
    private var fetchLat: Double? = null
    private var fetchLon: Double? = null
    private var fetchedAt = 0L
    private var fetching = false
    private var lastFailAt = 0L
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

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, h: Handler) {
        if (lm != null) return
        app = ctx.applicationContext
        handler = h
        loadCache()
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            warn = "Location permission needed for mapped ALPRs"
            return
        }
        val m = ctx.getSystemService(LocationManager::class.java) ?: return
        lm = m
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p in m.allProviders) {
                try { m.requestLocationUpdates(p, 3_000L, 5f, listener, h.looper) } catch (_: Exception) { }
            }
        }
        m.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onFix(it) }
        status = if (alprs.isEmpty()) "waiting for GPS" else "${alprs.size} mapped nearby (cached)"
    }

    fun stop() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        lm = null
    }

    private fun onFix(l: Location) {
        if (l.hasAccuracy() && l.accuracy > 150) return
        lat = l.latitude
        lon = l.longitude
        speed = if (l.hasSpeed()) l.speed.toDouble() else null
        course = if (l.hasBearing() && (speed ?: 0.0) > 1.5) l.bearing.toDouble() else null
        warn = null
        maybeFetch()
    }

    private fun maybeFetch() {
        val la = lat ?: return
        val lo = lon ?: return
        val now = System.currentTimeMillis()
        val moved = fetchLat?.let { Geo.distanceM(it, fetchLon!!, la, lo) } ?: Double.MAX_VALUE
        val stale = now - fetchedAt > 6 * 3_600_000L
        if (fetching || now - lastFailAt < 120_000 || (moved < AlprLogic.REFETCH_MOVE_M && !stale)) return
        fetching = true
        status = "downloading map data…"
        val h = handler ?: return
        Thread {
            val result = try { Result.success(download(AlprLogic.overpassQuery(la, lo))) } catch (e: Exception) { Result.failure(e) }
            h.post {
                fetching = false
                result.onSuccess { json ->
                    alprs = AlprLogic.parse(json)
                    fetchLat = la; fetchLon = lo; fetchedAt = System.currentTimeMillis()
                    status = "${alprs.size} mapped within 8 km"
                    saveCache(la, lo, json)
                    Engine.store.log("SYS", "Mapped ALPR data: ${alprs.size} within 8 km")
                }.onFailure {
                    lastFailAt = System.currentTimeMillis()
                    status = if (alprs.isEmpty()) "map data unavailable" else "${alprs.size} mapped (cached)"
                    warn = "Couldn't download mapped ALPRs (no internet, or GrapheneOS Network permission off)"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun download(query: String): String {
        val endpoints = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")
        var last: Exception? = null
        for (ep in endpoints) {
            try {
                val c = URL(ep).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = 40_000
                c.doOutput = true
                c.setRequestProperty("User-Agent", "AirScope/1 (personal passive-RF app)")
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

    private fun cacheFile() = app?.let { File(it.filesDir, "alpr_cache.txt") }

    private fun saveCache(la: Double, lo: Double, json: String) {
        try { cacheFile()?.writeText("$la,$lo,${System.currentTimeMillis()}\n$json") } catch (_: Exception) { }
    }

    private fun loadCache() {
        try {
            val f = cacheFile() ?: return
            if (!f.exists()) return
            val text = f.readText()
            val nl = text.indexOf('\n')
            val head = text.substring(0, nl).split(',')
            alprs = AlprLogic.parse(text.substring(nl + 1))
            fetchLat = head[0].toDouble(); fetchLon = head[1].toDouble(); fetchedAt = head[2].toLong()
        } catch (_: Exception) { }
    }

    fun hits(): List<AlprHit> {
        val la = lat ?: return emptyList()
        val lo = lon ?: return emptyList()
        return AlprLogic.hits(alprs, la, lo)
    }

    /** Cameras that should alert now (each at most once per 20 minutes). */
    fun dueAlerts(now: Long): List<AlprHit> {
        val out = ArrayList<AlprHit>()
        for (h in hits()) {
            if (!AlprLogic.shouldAlert(h, speed, course)) continue
            val last = lastAlert[h.alpr.id] ?: 0L
            if (now - last < 20 * 60_000L) continue
            lastAlert[h.alpr.id] = now
            out += h
        }
        return out
    }

    fun warning(): String? = warn
}
