package com.k.airscope

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * Background engine: owns the device store on its own thread so radio callbacks, classification
 * and scoring never block the UI. The UI and web HUD read immutable [snapshot]s.
 */
object Engine {
    private var thread: HandlerThread? = null
    lateinit var handler: Handler
        private set
    private lateinit var app: Context

    /** Engine-thread only. */
    val store = DeviceStore()
    val db = VendorDb()
    val geo = GeoTracker()
    val history = HistoryDb()
    private var lastHistorySave = 0L
    private var lastHistoryTouch = 0L

    @Volatile var snapshot: Snapshot = Snapshot.EMPTY
        private set
    @Volatile var snapshotJson: String = "{\"devices\":[],\"timeline\":[]}"
        private set
    private var mapJsonCache: Pair<Long, String>? = null

    /** Street-map geometry for the web HUD (cached per map version). */
    fun mapJson(): String {
        val v = geo.mapVersion
        mapJsonCache?.let { if (it.first == v) return it.second }
        val o = geo.origin
        val json = if (o != null && geo.chunks.isNotEmpty()) Exporter.mapJsonMerged(geo.chunks, geo.lat ?: o.lat0, geo.lon ?: o.lon0) else Exporter.mapJson(geo.map)
        return json.also { mapJsonCache = v to it }
    }
    @Volatile var running = false
        private set
    @Volatile var wifiOn = true
    @Volatile var bleOn = true
    /** One device seen during the current scan session (also ones no longer in range). */
    class SessionRow(
        val key: String, val title: String, val kind: Kind, val category: Category, val vendor: String,
        val sightings: Int, val firstSeen: Long, val lastSeen: Long, val bestRssi: Int, val spreadM: Double, val score: Int,
    )

    /** Everything seen this session, came-back devices first. Refreshed every few seconds. */
    @Volatile var session: List<SessionRow> = emptyList()
    @Volatile var sessionStartedAt = 0L
    private var lastSessionAt = 0L

    private fun refreshSession(now: Long) {
        if (now - lastSessionAt < 5_000) return
        lastSessionAt = now
        sessionStartedAt = store.sessionStart
        session = store.devices.values.asSequence()
            .filter { it.lastSeen >= store.sessionStart && it.readings > 0 }
            .map { SessionRow(it.key, it.displayName(), it.kind, it.category, it.vendor, it.sightings.coerceAtLeast(1),
                maxOf(it.firstSeen, store.sessionStart), it.lastSeen, it.rssiMax, it.sightSpreadM, it.score) }
            .sortedWith(compareByDescending<SessionRow> { it.sightings }.thenByDescending { it.score }.thenByDescending { it.lastSeen })
            .take(1000)
            .toList()
    }

    /** Distance model in use. With [envAuto] it follows what you're doing; otherwise it's your choice. */
    @Volatile var env = Env.INDOOR
    @Volatile var envAuto = true
    private var drivingUntil = 0L
    private var envCandidate = Env.INDOOR
    private var envCandidateSince = 0L

    /**
     * Engine thread. Driving (over ~16 mph) -> car, and it stays "car" for a minute after you slow down (traffic lights).
     * Otherwise a clear, fresh GPS fix means outdoors; a weak or missing one means indoors. Changes need 20 s to stick.
     */
    private fun updateAutoEnv(now: Long) {
        if (!envAuto) return
        val fresh = android.os.SystemClock.elapsedRealtimeNanos() - geo.fixNanos < 10_000_000_000L && geo.lat != null
        if (fresh && geo.speed > 7.0) drivingUntil = now + 60_000
        val want = when {
            now < drivingUntil -> Env.CAR
            fresh && geo.accuracy <= 8.0 -> Env.OPEN
            else -> Env.INDOOR
        }
        if (want == Env.CAR) { env = Env.CAR; envCandidate = want; return } // switch to car right away
        if (want != envCandidate) { envCandidate = want; envCandidateSince = now }
        if (env != want && now - envCandidateSince >= 20_000) env = want
    }
    @Volatile var alprOn = true
    @Volatile var hudOn = false
    /** Phone heading (true north) and its accuracy, set by the screen while it's open; shared with the HUD. */
    @Volatile var heading: Double? = null
    @Volatile var headingAcc: Double = 45.0
    @Volatile var hudToken = ""
    @Volatile var dbStatus = "built-in tables"

    /** Called on the engine thread when a device should raise a notification. */
    @Volatile var deviceAlert: ((Device) -> Unit)? = null
    @Volatile var alprAlert: ((AlprHit) -> Unit)? = null

    private var scanner: SignalScanner? = null
    private var version = 0L

    fun init(ctx: Context) {
        if (thread != null) return
        app = ctx.applicationContext
        Prefs.load(app)
        val t = HandlerThread("airscope-engine", Process.THREAD_PRIORITY_DEFAULT)
        t.start()
        thread = t
        handler = Handler(t.looper)
        Exporter.elapsedNanos = { android.os.SystemClock.elapsedRealtimeNanos() }
        Exporter.heading = { heading?.let { it to headingAcc } }
        handler.post {
            loadDb()
            publish()
        }
    }

    fun post(r: () -> Unit) {
        handler.post(r)
    }

    private fun loadDb() {
        fun lines(name: String): Sequence<String>? = try {
            app.assets.open("db/$name").bufferedReader().readLines().asSequence()
        } catch (_: Exception) {
            null
        }
        lines("oui.tsv")?.let { db.loadOui(it) }
        lines("ble_companies.tsv")?.let { db.loadCompanies(it) }
        lines("ble_uuids.tsv")?.let { db.loadUuids(it) }
        dbStatus = if (db.ouiCount > 0) "${db.ouiCount} MAC vendors · ${db.companyCount} BLE companies" else "built-in tables (full vendor DB not bundled)"
        try {
            val f = java.io.File(app.filesDir, "history.tsv")
            if (f.exists()) history.load(f.readLines().asSequence())
        } catch (_: Exception) { }
    }

    private fun saveHistory() {
        if (!history.dirty) return
        try { java.io.File(app.filesDir, "history.tsv").writeText(history.dump()); history.dirty = false } catch (_: Exception) { }
    }

    /** Engine thread. */
    fun startScanning() {
        if (running) return
        running = true
        store.sessionStart = System.currentTimeMillis()
        store.log("SYS", "New scan session")
        scanner = SignalScanner(app, handler).also { it.start() }
        geo.alprEnabled = alprOn
        geo.start(app, handler)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** Engine thread. */
    fun stopScanning() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        scanner?.stop()
        scanner = null
        geo.stop()
        saveHistory()
        publish()
    }

    fun setAlpr(on: Boolean) = post {
        alprOn = on
        geo.alprEnabled = on
        Prefs.save(app)
    }

    fun setTrusted(key: String, on: Boolean) = post {
        store.setTrusted(key, on)
        Prefs.saveTrusted(app, store.trustedKeys)
        publish()
    }

    fun clear() = post {
        store.clear()
        publish()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            scanner?.syncBle()
            updateAutoEnv(now)
            refreshSession(now)
            for (d in store.rescore(now)) deviceAlert?.invoke(d)
            // Re-estimate map positions for devices with new GPS-tagged samples: least recently estimated
            // first, so a newly appeared device is never starved by busy older ones.
            store.devices.values.asSequence()
                .filter { it.track.dirty && store.isActive(it, now) }
                .sortedBy { it.track.estAt }
                .take(20)
                .toList()
                .forEach { locate(it, now) }
            if (now - lastHistoryTouch > 60_000) {
                lastHistoryTouch = now
                for (d in store.active(now)) if (HistoryDb.shouldTrack(d)) {
                    val r = history.touch(d.key, now)
                    d.firstEver = r.first; d.daysSeen = r.days
                }
            }
            if (now - lastHistorySave > 5 * 60_000) { lastHistorySave = now; saveHistory() }
            if (alprOn) for (h in geo.dueAlerts(now)) alprAlert?.invoke(h)
            publish()
            handler.postDelayed(this, 500)
        }
    }

    private val MOBILE = setOf(Category.PHONE, Category.WEARABLE, Category.TRACKER, Category.VEHICLE)
    private val FIXED = setOf(Category.NETWORK, Category.CAMERA_IOT)

    /** Engine thread: estimate where a device is, using the street map to settle ambiguous cases. */
    private fun locate(d: Device, now: Long) {
        val tr = d.track
        if (d.followM > 300) { // it's moving with you: there is no fixed spot to find
            tr.dirty = false; tr.located = false; tr.ambiguous = false; tr.estAt = now
            return
        }
        val (prior, sigma) = Distance.locatorPrior(d)
        Locator.estimate(tr, prior, sigma, env, now, if (d.category in MOBILE) 120_000L else null)
        d.sideFromMap = false
        // Two equally good spots? Wi-Fi gear and IoT devices sit inside buildings: pick the side with one.
        if (tr.ambiguous && d.category in FIXED && tr.altLat != null) {
            val a = geo.buildingAt(tr.estLat, tr.estLon, 4.0)
            val b = geo.buildingAt(tr.altLat!!, tr.altLon!!, 4.0)
            if ((a != null) != (b != null)) {
                if (b != null) { val la = tr.estLat; val lo = tr.estLon; tr.estLat = tr.altLat!!; tr.estLon = tr.altLon!!; tr.altLat = la; tr.altLon = lo }
                tr.errM = tr.rawErrM
                tr.located = tr.rawErrM <= 45
                tr.ambiguous = !tr.located
                d.sideFromMap = tr.located
            }
        }
        d.building = if (tr.located && d.category in FIXED + Category.POLICE) {
            geo.buildingAt(tr.estLat, tr.estLon, minOf(tr.errM, 15.0))?.let { it.label ?: "a building" }
        } else null
    }

    /**
     * Engine thread: tag a reading with where you were at its exact reception time (Wi-Fi results
     * can arrive seconds after the beacon was heard: ~90 m at highway speed), and track whether the
     * device is traveling with you.
     */
    fun recordObservation(d: Device, rssi: Int, now: Long, rxNanos: Long) {
        val pos = geo.positionAt(rxNanos) ?: return
        // Remember where each sighting began
        if (d.sightPlaces.size < d.sightings.coerceAtMost(10)) {
            for (p in d.sightPlaces) d.sightSpreadM = maxOf(d.sightSpreadM, Geo.distanceM(p[0], p[1], pos.lat, pos.lon))
            d.sightPlaces += doubleArrayOf(pos.lat, pos.lon)
        }
        Locator.add(d.track, pos.lat, pos.lon, rssi.toDouble(), now, pos.accM, env)
        // Following: how far you really traveled while it stayed close and in continuous view.
        // Counted from an anchor point in 10 m+ steps, so GPS jitter while standing still adds nothing.
        val gap = if (d.lastObsNanos > 0) rxNanos - d.lastObsNanos else -1L
        when {
            d.lastObsNanos == 0L || gap > 45_000_000_000L -> {
                if (gap > 45_000_000_000L) d.followM = 0.0
                d.lastObsLat = pos.lat; d.lastObsLon = pos.lon
            }
            gap > 0 && d.smoothRssi > -85 -> {
                val moved = Geo.distanceM(d.lastObsLat, d.lastObsLon, pos.lat, pos.lon)
                if (moved >= 10.0 + pos.accM) { d.followM += moved; d.lastObsLat = pos.lat; d.lastObsLon = pos.lon }
            }
            gap > 0 -> { d.lastObsLat = pos.lat; d.lastObsLon = pos.lon } // weak: not "with you" right now
        }
        if (rxNanos > d.lastObsNanos) d.lastObsNanos = rxNanos
    }

    /** Engine thread: a measured Wi-Fi RTT distance. */
    fun recordRange(d: Device, meters: Double, std: Double, now: Long, rxNanos: Long) {
        val pos = geo.positionAt(rxNanos) ?: return
        Locator.addRtt(d.track, pos.lat, pos.lon, meters, std, now, pos.accM)
    }

    /** Builds the immutable snapshot (engine thread). */
    fun publish() {
        val now = System.currentTimeMillis()
        val lat = geo.lat
        val lon = geo.lon
        val views = ArrayList<DeviceView>()
        val active = store.active(now)
        val ssidCount = HashMap<String, Int>()
        for (d in active) if (d.kind == Kind.WIFI && d.name.isNotBlank()) ssidCount[d.name] = (ssidCount[d.name] ?: 0) + 1
        // Your position predicted to "now" so the list/HUD match the map
        val me = geo.filter.at(android.os.SystemClock.elapsedRealtimeNanos())
        for (d in active) {
            if ((d.kind == Kind.WIFI && !wifiOn) || (d.kind == Kind.BLE && !bleOn)) continue
            views += Snapshot.view(d, env, now, me?.lat ?: lat, me?.lon ?: lon, if (d.kind == Kind.WIFI) ssidCount[d.name] ?: 0 else 0)
        }
        if (alprOn && lat != null && lon != null) for (h in geo.hits()) views += Snapshot.alprView(h, now)
        views.sortWith(compareByDescending<DeviceView> { it.score }.thenBy { it.dist })
        val warning = if (running) scanner?.warning() ?: geo.warning() else null
        val s = Snapshot(
            now, running, warning, views, ArrayList(store.timeline.take(200)), env, geo.status, geo.mapStatus, ++version,
            lat, lon, geo.accuracy, geo.course, geo.mapVersion, geo.vx, geo.vy, geo.fixNanos, geo.gnss,
        )
        snapshot = s
        if (hudOn) snapshotJson = Exporter.snapshotJson(s)
    }
}
