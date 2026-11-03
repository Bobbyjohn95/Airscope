package com.k.airscope

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Street-map tiles (OpenFreeMap vector tiles, OpenMapTiles schema, zoom 14 ≈ 2 km squares).
 *
 * - Pre-built static files served from a CDN: fast on cellular, no per-IP query limits (unlike Overpass).
 * - Every tile is kept on disk, so places you've been (or downloaded for offline use) load instantly and work
 *   without signal. Tiles older than 30 days are refreshed in the background when online.
 * - Several downloads at once, nearest first; failures back off per tile.
 * Thread-safe. No Android types (unit-testable on a plain JVM).
 */
class TileManager(
    private val dir: File,
    private val fetcher: (String) -> ByteArray = ::httpGet,
    private val clock: () -> Long = System::currentTimeMillis,
    threads: Int = 4,
) {
    companion object {
        const val Z = 14
        const val URL_TEMPLATE = "https://tiles.openfreemap.org/planet/latest/%d/%d/%d.pbf"
        const val ATTRIBUTION = "OpenFreeMap © OpenMapTiles · Data © OpenStreetMap contributors"
        private const val MAX_AGE_MS = 30L * 24 * 3_600_000
        private const val DISK_LIMIT_BYTES = 400L * 1024 * 1024
        private const val MEM_TILES = 60

        fun url(id: Tiles.TileId) = String.format(java.util.Locale.US, URL_TEMPLATE, id.z, id.x, id.y)

        /** Plain HTTPS GET with sensible timeouts; the error message says what went wrong. */
        fun httpGet(url: String): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "AirScope/2 (personal passive-RF app)")
            c.setRequestProperty("Accept-Encoding", "gzip")
            try {
                val code = c.responseCode
                if (code != 200) throw IOException("HTTP $code")
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
    }

    /** Numbers for the diagnostics screen. */
    class Stats {
        @Volatile var downloaded = 0
        @Volatile var failed = 0
        @Volatile var fromDisk = 0
        @Volatile var bytes = 0L
        @Volatile var lastError: String? = null
        @Volatile var lastErrorAt = 0L
        @Volatile var lastOkAt = 0L
        @Volatile var lastLatencyMs = 0L
    }

    val stats = Stats()
    /** Goes up every time a tile becomes available. */
    @Volatile var version = 0L
        private set
    /** Called (on a worker thread) when a tile has loaded. */
    @Volatile var onLoaded: (() -> Unit)? = null

    private val mem = object : LinkedHashMap<String, MapData>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, MapData>) = size > MEM_TILES
    }
    private val inFlight = HashSet<String>()
    private val failures = HashMap<String, LongArray>() // key -> [lastFailAt, count]
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "tiles").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private var saves = 0

    private fun file(id: Tiles.TileId) = File(dir, "${id.z}/${id.x}/${id.y}.pbf")

    fun cached(id: Tiles.TileId): MapData? = synchronized(mem) { mem[id.key] }

    /**
     * Wants these tiles (nearest / most important first). Returns the ones ready now; the rest load in the
     * background and [onLoaded] fires as each arrives.
     */
    fun request(ids: List<Tiles.TileId>): List<MapData> {
        val ready = ArrayList<MapData>()
        val now = clock()
        for (id in ids) {
            val m = synchronized(mem) { mem[id.key] }
            if (m != null) { ready += m; continue }
            synchronized(inFlight) {
                if (id.key in inFlight) return@synchronized
                val f = failures[id.key]
                if (f != null && now - f[0] < backoffMs(f[1].toInt())) return@synchronized
                inFlight += id.key
                pool.execute { load(id, refreshOnly = false) }
            }
        }
        return ready
    }

    private fun backoffMs(count: Int) = (5_000L shl (count - 1).coerceIn(0, 5)).coerceAtMost(120_000L)

    private fun load(id: Tiles.TileId, refreshOnly: Boolean) {
        try {
            val f = file(id)
            var data: MapData? = null
            if (!refreshOnly && f.exists()) {
                val bytes = try { f.readBytes() } catch (_: IOException) { null }
                if (bytes != null && bytes.isNotEmpty()) {
                    data = VectorTile.decode(bytes, id, f.lastModified())
                    stats.fromDisk++
                    publish(id, data)
                }
            }
            val stale = !f.exists() || clock() - f.lastModified() > MAX_AGE_MS
            if (data == null || stale) {
                val t0 = System.nanoTime()
                val bytes = try {
                    fetcher(url(id))
                } catch (e: Exception) {
                    stats.failed++
                    stats.lastError = (e.message ?: e.javaClass.simpleName).take(120)
                    stats.lastErrorAt = clock()
                    synchronized(inFlight) {
                        val c = failures[id.key]
                        failures[id.key] = longArrayOf(clock(), (c?.get(1) ?: 0L) + 1)
                    }
                    return
                }
                stats.lastLatencyMs = (System.nanoTime() - t0) / 1_000_000
                stats.downloaded++
                stats.bytes += bytes.size
                stats.lastOkAt = clock()
                synchronized(inFlight) { failures.remove(id.key) }
                save(f, bytes)
                publish(id, VectorTile.decode(bytes, id, clock()))
            }
        } finally {
            synchronized(inFlight) { inFlight -= id.key }
        }
    }

    private fun publish(id: Tiles.TileId, m: MapData) {
        synchronized(mem) { mem[id.key] = m }
        version++
        onLoaded?.invoke()
    }

    private fun save(f: File, bytes: ByteArray) {
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: IOException) { return }
        if (++saves % 40 == 0) trimDisk()
    }

    /** Oldest-used tiles go first once the cache passes its size limit. */
    @Synchronized
    fun trimDisk() {
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".pbf") }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT_BYTES) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= DISK_LIMIT_BYTES * 9 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    fun diskUsage(): Pair<Int, Long> {
        var n = 0; var b = 0L
        if (dir.exists()) dir.walkTopDown().filter { it.isFile && it.name.endsWith(".pbf") }.forEach { n++; b += it.length() }
        return n to b
    }

    fun clearDisk() {
        dir.deleteRecursively()
        synchronized(mem) { mem.clear() }
    }

    /** Is this tile saved on the phone (usable offline)? */
    fun onDisk(id: Tiles.TileId) = file(id).exists()

    /**
     * Blocking: saves every tile within [radiusM] of a point for offline use. Already-saved fresh tiles are skipped.
     * Reports (done, total, failed); stops early when [cancelled] returns true. Returns tiles that failed.
     */
    fun downloadArea(lat: Double, lon: Double, radiusM: Double, onProgress: (Int, Int, Int) -> Unit, cancelled: () -> Boolean): Int {
        val ids = Tiles.around(lat, lon, radiusM, Z)
        var done = 0; var failed = 0
        val total = ids.size
        val sem = java.util.concurrent.Semaphore(4)
        val dl = Executors.newFixedThreadPool(4) { r -> Thread(r, "tiles-offline").apply { isDaemon = true } }
        val lock = Any()
        for (id in ids) {
            if (cancelled()) break
            val f = file(id)
            if (f.exists() && clock() - f.lastModified() < MAX_AGE_MS) {
                synchronized(lock) { done++ }
                onProgress(done, total, failed)
                continue
            }
            sem.acquire()
            dl.execute {
                try {
                    val bytes = fetcher(url(id))
                    save(f, bytes)
                    stats.downloaded++; stats.bytes += bytes.size; stats.lastOkAt = clock()
                } catch (e: Exception) {
                    synchronized(lock) { failed++ }
                    stats.failed++; stats.lastError = (e.message ?: e.javaClass.simpleName).take(120); stats.lastErrorAt = clock()
                } finally {
                    synchronized(lock) { done++ }
                    onProgress(done, total, failed)
                    sem.release()
                }
            }
        }
        dl.shutdown()
        dl.awaitTermination(10, TimeUnit.MINUTES)
        trimDisk()
        return failed
    }
}
