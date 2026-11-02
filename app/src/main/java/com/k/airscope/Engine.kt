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

    @Volatile var snapshot: Snapshot = Snapshot.EMPTY
        private set
    @Volatile var snapshotJson: String = "{\"devices\":[],\"timeline\":[]}"
        private set
    @Volatile var running = false
        private set
    @Volatile var wifiOn = true
    @Volatile var bleOn = true
    @Volatile var env = Env.INDOOR
    @Volatile var alprOn = true
    @Volatile var hudOn = false
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
    }

    /** Engine thread. */
    fun startScanning() {
        if (running) return
        running = true
        scanner = SignalScanner(app, handler).also { it.start() }
        if (alprOn) geo.start(app, handler)
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
        publish()
    }

    fun setAlpr(on: Boolean) = post {
        alprOn = on
        if (running) { if (on) geo.start(app, handler) else geo.stop() }
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
            for (d in store.rescore(now)) deviceAlert?.invoke(d)
            if (alprOn) for (h in geo.dueAlerts(now)) alprAlert?.invoke(h)
            publish()
            handler.postDelayed(this, 500)
        }
    }

    /** Builds the immutable snapshot (engine thread). */
    fun publish() {
        val now = System.currentTimeMillis()
        val lat = geo.lat
        val lon = geo.lon
        val views = ArrayList<DeviceView>()
        for (d in store.active(now)) {
            if ((d.kind == Kind.WIFI && !wifiOn) || (d.kind == Kind.BLE && !bleOn)) continue
            views += Snapshot.view(d, env, now, lat, lon)
        }
        if (alprOn && lat != null && lon != null) for (h in geo.hits()) views += Snapshot.alprView(h, now)
        views.sortWith(compareByDescending<DeviceView> { it.score }.thenBy { it.dist })
        val warning = if (running) scanner?.warning() ?: geo.warning() else null
        val s = Snapshot(now, running, warning, views, ArrayList(store.timeline.take(200)), env, lat != null, geo.status, ++version)
        snapshot = s
        if (hudOn) snapshotJson = Exporter.snapshotJson(s)
    }
}
