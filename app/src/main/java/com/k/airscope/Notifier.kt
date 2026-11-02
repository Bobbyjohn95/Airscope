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
    const val EXTRA_SELECT = "select_key"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SCAN, "Background scanning", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while AirScope is scanning"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Threat alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Trackers following you and suspicious networks"
                enableVibration(true)
            },
        )
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, requestCode: Int, selectKey: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (selectKey != null) i.putExtra(EXTRA_SELECT, selectKey)
        return PendingIntent.getActivity(
            ctx, requestCode, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun ongoing(ctx: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, ScanService::class.java).setAction(ScanService.ACTION_STOP),
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
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_stat_radar), "Stop", stop,
                ).build(),
            )
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    fun updateOngoing(ctx: Context, text: String) {
        if (!canPost(ctx)) return
        try {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(ScanService.NOTIF_ID, ongoing(ctx, text))
        } catch (_: SecurityException) {
        }
    }

    fun alert(ctx: Context, d: Device) {
        if (!canPost(ctx)) return
        val tracker = d.bleType in BleClassifier.TRACKERS
        val title = when {
            tracker && d.level == Level.HIGH -> "Tracker may be traveling with you"
            tracker -> "Tracker detected nearby"
            else -> "High-risk signal: ${d.displayName()}"
        }
        val text = "${d.displayName()} · ${d.smoothRssi} dBm · ~${fmt1(d.distanceMeters())} m\n" +
            d.flags.joinToString(" · ")
        val id = 1000 + (d.key.hashCode() and 0x7fffffff) % 100_000
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text + "\n\nTap to open and track its signal."))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp(ctx, id, d.key))
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
