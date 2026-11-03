package com.k.airscope

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build

object Notifier {
    private const val CH_SCAN = "scan"
    private const val CH_ALERT = "alerts"
    private const val CH_POLICE = "police"
    private const val CH_UPDATE = "updates"
    const val EXTRA_SELECT = "select_key"
    const val UPDATE_ID = 900

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SCAN, "Background scanning", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while AirScope is scanning"; setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Tracker & threat alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Trackers following you and high-risk signals"; enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_POLICE, "Police, ALPR & drone alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Flock / ALPR cameras, Axon equipment, gunshot detectors, drones"; enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UPDATE, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New AirScope builds from GitHub"
            },
        )
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openApp(ctx: Context, requestCode: Int, selectKey: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (selectKey != null) i.putExtra(EXTRA_SELECT, selectKey)
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun ongoing(ctx: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, ScanService::class.java).setAction(ScanService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(ctx, CH_SCAN)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("AirScope is scanning")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx, 0, null))
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_radar), "Stop", stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    fun updateOngoing(ctx: Context, text: String) {
        if (!canPost(ctx)) return
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ScanService.NOTIF_ID, ongoing(ctx, text))
        } catch (_: SecurityException) { }
    }

    // Android drops an app's notifications past ~50 active ones, so alerts reuse a small ring of ids
    // (the oldest alert of that kind is replaced) instead of one id per device / camera.
    private val slots = LinkedHashMap<String, Int>()

    private fun slotId(base: Int, size: Int, key: String): Int = synchronized(slots) {
        val k = "$base|$key"
        slots.remove(k)?.let { slots[k] = it; return it } // refresh recency
        val mine = slots.filterKeys { it.startsWith("$base|") }
        val id = (base until base + size).firstOrNull { it !in mine.values } ?: run {
            val oldest = mine.keys.first()
            slots.remove(oldest)!!
        }
        slots[k] = id
        id
    }

    private fun post(
        ctx: Context, channel: String, id: Int, title: String, text: String, intent: PendingIntent,
        timeoutMs: Long = 0L, group: String? = null,
    ) {
        if (!canPost(ctx)) return
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(intent)
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        if (group != null) b.setGroup(group)
        val n = b.build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) { }
    }

    /** Called from the engine thread with a live Device. */
    fun deviceAlert(ctx: Context, d: Device, distText: String) {
        val title = when {
            d.category == Category.POLICE -> when {
                d.label.startsWith("Axon") -> "Police equipment nearby (Axon)"
                d.label.startsWith("Flock") -> "Flock Safety device detected"
                d.label.startsWith("Raven") -> "Gunshot detector nearby"
                d.label.startsWith("Police vehicle") -> "Police vehicle nearby (by its network name)"
                d.label.startsWith("Traffic") -> "Traffic / enforcement camera nearby"
                else -> d.label + " nearby"
            }
            d.category == Category.DRONE -> "Drone nearby: ${d.label}"
            d.bleType in Classifier.TRACKERS && d.level == Level.HIGH -> "Tracker may be traveling with you"
            d.bleType in Classifier.TRACKERS -> "Tracker detected nearby"
            else -> "High-risk signal: ${d.displayName()}"
        }
        val text = "${d.displayName()} · ${d.smoothRssi} dBm · $distText\n" + d.flags.joinToString(" · ") +
            "\n\nTap to open and track its signal."
        val id = slotId(1000, 15, d.key)
        val ch = if (d.category == Category.POLICE || d.category == Category.DRONE) CH_POLICE else CH_ALERT
        post(ctx, ch, id, title, text, openApp(ctx, id, d.key), group = "devices")
    }

    fun alprAlert(ctx: Context, h: AlprHit, course: Double?) {
        val a = h.alpr
        val where = if (course != null && Geo.angleDiff(h.bearing, course) <= 45) "ahead" else Geo.cardinal(h.bearing)
        val title = "${a.title().removeSuffix(" (mapped)")} ${fmtDist(h.dist)} m $where"
        val text = listOfNotNull(
            a.operator?.let { "Operator: $it" },
            a.direction?.let { "Camera faces: $it" },
            "Mapped on OpenStreetMap (DeFlock).",
        ).joinToString("\n")
        val id = slotId(200_000, 8, "MAP:${a.id}")
        // A passed camera stops mattering after a while: the alert clears itself after 20 minutes.
        post(ctx, CH_POLICE, id, title, text, openApp(ctx, id, "MAP:${a.id}"), timeoutMs = 20 * 60_000L, group = "alpr")
    }

    fun update(ctx: Context, title: String, text: String, intent: PendingIntent? = null) {
        post(ctx, CH_UPDATE, UPDATE_ID, title, text, intent ?: openApp(ctx, UPDATE_ID, null))
    }
}
