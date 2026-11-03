package com.k.airscope

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service: keeps the engine scanning with the screen off, holds a partial wake lock,
 * posts the status notification and alerts, and hosts the optional iPhone web HUD.
 */
class ScanService : Service() {
    companion object {
        const val ACTION_START = "com.k.airscope.START"
        const val ACTION_STOP = "com.k.airscope.STOP"
        const val ACTION_HUD = "com.k.airscope.HUD"
        const val NOTIF_ID = 1
        const val HUD_PORT = 8787
        private const val WAKE_MS = 6 * 60 * 60 * 1000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ScanService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            if (Engine.running) ctx.startService(Intent(ctx, ScanService::class.java).setAction(ACTION_STOP))
        }

        fun refreshHud(ctx: Context) {
            if (Engine.running) ctx.startService(Intent(ctx, ScanService::class.java).setAction(ACTION_HUD))
        }

        fun hudUrls(): List<String> = HudServer.localIps().map { "http://$it:$HUD_PORT/?k=${Engine.hudToken}" }
    }

    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopping = false
    private var hud: HudServer? = null
    private var lastNotif = 0L

    private val notifTick = object : Runnable {
        override fun run() {
            if (!Engine.running || stopping) return
            val now = System.currentTimeMillis()
            if (now - lastNotif > 10_000) {
                lastNotif = now
                val s = Engine.snapshot
                val wifiN = s.devices.count { it.kind == Kind.WIFI }
                val bleN = s.devices.count { it.kind == Kind.BLE }
                val police = s.devices.count { it.category == Category.POLICE }
                val high = s.devices.count { it.level == Level.HIGH }
                Notifier.updateOngoing(
                    this@ScanService,
                    "Wi-Fi $wifiN · BLE $bleN · $high high" + (if (police > 0) " · $police police/ALPR" else "") +
                        (s.warning?.let { "  ⚠ $it" } ?: ""),
                )
                if (!stopping) wakeLock?.let { if (!it.isHeld) it.acquire(WAKE_MS) }
            }
            Engine.handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        Notifier.ensureChannels(this)
        val app = applicationContext
        Engine.deviceAlert = { d ->
            val est = Distance.estimate(d, Engine.env, System.currentTimeMillis())
            Notifier.deviceAlert(app, d, "~${fmtDist(est.meters)} m (${fmtDist(est.lo)}–${fmtDist(est.hi)})")
        }
        Engine.alprAlert = { h -> Notifier.alprAlert(app, h, Engine.geo.course) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_HUD -> applyHud()
            else -> startScanning()
        }
        return START_NOT_STICKY
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun startScanning() {
        // connectedDevice is always allowed (CHANGE_WIFI_STATE is granted at install). Location is added when
        // permitted; if Android refuses it (e.g. started from the background), fall back so we never crash with
        // "did not call startForeground".
        val cd = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val loc = if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        var started = false
        var err: Exception? = null
        for (type in listOf(cd or loc, cd).distinct()) {
            try {
                startForeground(NOTIF_ID, Notifier.ongoing(this, "Starting…"), type)
                started = true
                if (type != (cd or loc)) Engine.post { Engine.store.log("SYS", "Started without location access: open AirScope to restore GPS") }
                break
            } catch (e: Exception) { err = e }
        }
        if (!started) {
            Engine.post { Engine.store.log("SYS", "Could not start background scanning: ${err?.message}") }
            stopSelf()
            return
        }
        stopping = false
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirScope:scan")
                .apply { setReferenceCounted(false); acquire(WAKE_MS) }
        }
        Prefs.putBool(this, "was_running", true)
        Engine.post {
            Engine.startScanning()
            Engine.handler.removeCallbacks(notifTick)
            Engine.handler.post(notifTick)
        }
        applyHud()
    }

    private fun applyHud() {
        if (Engine.hudOn) {
            if (hud == null) {
                try {
                    val page = assets.open("hud.html").use { it.readBytes() }
                    val server = HudServer(HUD_PORT, Engine.hudToken, page, { Engine.snapshotJson }, { Engine.mapJson() })
                    server.start()
                    hud = server
                    Engine.post { Engine.publish(); Engine.store.log("SYS", "Web HUD on port $HUD_PORT") }
                } catch (e: Exception) {
                    // On GrapheneOS this happens when the per-app "Network" permission is off.
                    Engine.hudOn = false
                    Engine.post {
                        Engine.store.log("SYS", "Web HUD failed (${e.message}). On GrapheneOS allow the Network permission.")
                    }
                }
            }
        } else {
            hud?.stop()
            hud = null
        }
    }

    private fun cleanup() {
        stopping = true
        Prefs.putBool(this, "was_running", false)
        Engine.post {
            Engine.handler.removeCallbacks(notifTick)
            Engine.stopScanning()
        }
        hud?.stop()
        hud = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * You removed AirScope from Recents ("closed it completely"): stop scanning, GPS, the HUD and the
     * notification, like any app you close. (Going to the home screen doesn't call this: scanning continues.)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Engine.post { Engine.store.log("SYS", "App closed from Recents: everything stopped") }
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (Engine.running) cleanup()
        super.onDestroy()
        // Scanning stopped: an update that was waiting for that can install now (unless you're looking at the app).
        Updater.installPendingAsync(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
