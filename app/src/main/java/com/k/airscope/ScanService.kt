package com.k.airscope

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service: keeps scanning with the screen off, holds a partial wake lock,
 * posts the ongoing notification and threat alerts, and hosts the optional web HUD.
 */
class ScanService : Service() {
    companion object {
        const val ACTION_START = "com.k.airscope.START"
        const val ACTION_STOP = "com.k.airscope.STOP"
        const val ACTION_HUD = "com.k.airscope.HUD"
        const val NOTIF_ID = 1
        private const val WAKE_MS = 6 * 60 * 60 * 1000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ScanService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            if (ScanState.running) ctx.startService(Intent(ctx, ScanService::class.java).setAction(ACTION_STOP))
        }

        fun refreshHud(ctx: Context) {
            if (ScanState.running) ctx.startService(Intent(ctx, ScanService::class.java).setAction(ACTION_HUD))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: SignalScanner? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var hud: HudServer? = null
    private var lastNotif = 0L

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val store = ScanState.store
            scanner?.syncBle()
            for (d in store.rescore(now)) {
                if (d.level == Level.HIGH || d.bleType in BleClassifier.TRACKERS) {
                    Notifier.alert(this@ScanService, d)
                }
            }
            ScanState.warning = scanner?.warning()
            if (hud != null) {
                ScanState.snapshotJson = Exporter.snapshotJson(store, now, true, ScanState.warning)
            }
            if (now - lastNotif > 10_000) {
                lastNotif = now
                val act = store.active(now)
                val wifiN = act.count { it.kind == Kind.WIFI }
                val high = act.count { it.level == Level.HIGH }
                val med = act.count { it.level == Level.MEDIUM }
                Notifier.updateOngoing(
                    this@ScanService,
                    "Wi-Fi $wifiN · BLE ${act.size - wifiN} · $med medium · $high high" +
                        (ScanState.warning?.let { "  ⚠ $it" } ?: ""),
                )
            }
            wakeLock?.let { if (!it.isHeld) it.acquire(WAKE_MS) }
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.load(this)
        Notifier.ensureChannels(this)
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
        val notif = Notifier.ongoing(this, "Starting…")
        try {
            var type = 0
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_SCAN)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            if (type == 0) throw SecurityException("Location or Nearby devices permission is required")
            startForeground(NOTIF_ID, notif, type)
        } catch (e: Exception) {
            ScanState.warning = "Could not start background scanning: ${e.message}"
            ScanState.running = false
            stopSelf()
            return
        }

        if (ScanState.running && scanner != null) return
        ScanState.running = true

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirScope:scan").apply {
            setReferenceCounted(false)
            acquire(WAKE_MS)
        }
        scanner = SignalScanner(this, ScanState.store).also { it.start() }
        applyHud()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun applyHud() {
        if (ScanState.hudEnabled && ScanState.running) {
            if (hud == null) {
                try {
                    val page = assets.open("hud.html").use { it.readBytes() }
                    val server = HudServer(ScanState.HUD_PORT, ScanState.hudToken, page) { ScanState.snapshotJson }
                    ScanState.snapshotJson =
                        Exporter.snapshotJson(ScanState.store, System.currentTimeMillis(), true, ScanState.warning)
                    server.start()
                    hud = server
                    ScanState.store.log("SYS", "Web HUD on port ${ScanState.HUD_PORT}")
                } catch (e: Exception) {
                    // On GrapheneOS this happens when the per-app "Network" permission is off.
                    ScanState.hudEnabled = false
                    ScanState.warning = "Web HUD failed (${e.message}). On GrapheneOS: Settings > Apps > AirScope > " +
                        "Permissions > Network > Allow. Scanning itself doesn't need it."
                    ScanState.store.log("SYS", "Web HUD could not open a network socket")
                }
            }
        } else {
            hud?.stop()
            hud = null
        }
    }

    private fun cleanup() {
        handler.removeCallbacks(tick)
        scanner?.stop()
        scanner = null
        hud?.stop()
        hud = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ScanState.running = false
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
