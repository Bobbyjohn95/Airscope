package com.k.airscope

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        private const val REQ_PERMS = 42
        private const val REQ_START = 43
    }

    private val ui = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var radar: MapView3D
    private lateinit var btnCompass: TextView
    private lateinit var status: TextView
    private lateinit var trackBar: TextView
    private lateinit var btnScan: Button
    private lateinit var btnView: Button
    private lateinit var btnEnv: Button
    private lateinit var btnLog: Button
    private lateinit var btnWifi: Button
    private lateinit var btnBle: Button
    private lateinit var btnHud: Button
    private lateinit var list: ListView
    private val chips = HashMap<Mode, TextView>()
    private val adapter = RowAdapter()

    private var mode = Mode.ALL
    private var showTimeline = false
    private var selectedKey: String? = null
    private var lastListVersion = -1L
    private var lastListAt = 0L
    private var pendingStart = false
    private var hudUrl: String? = null
    private var hudUrlAt = 0L

    // Which way you're facing (true north, with accuracy)
    private lateinit var headingTracker: HeadingTracker
    private var lastCalToast = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        Notifier.ensureChannels(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mode = runCatching { Mode.valueOf(Prefs.getString(this, "mode", Mode.ALL.name)) }.getOrDefault(Mode.ALL)
        setContentView(buildUi())
        radar.viewMode = runCatching { MapView3D.ViewMode.valueOf(Prefs.getString(this, "view_mode", "CITY")) }.getOrDefault(MapView3D.ViewMode.CITY)
        radar.onViewChanged = { Prefs.putString(this, "view_mode", radar.viewMode.name) }
        radar.predictedRoute = { Engine.geo.predicted }
        radar.onStatusTap = { showDiagnostics() }
        handleIntent(intent)
        headingTracker = HeadingTracker(this)
        radar.headingSource = headingTracker
        Updater.schedule(this)
        if (System.currentTimeMillis() - Prefs.getLong(this, Updater.PREF_LAST) > 3 * 3_600_000L) {
            Updater.checkAsync(this, manual = false)
        }
        // Scanning was on when the app last stopped (e.g. it was updated or Android closed it): pick it back up.
        if (Prefs.getBool(this, "was_running", false) && !Engine.running && granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            ScanService.start(this)
            toast("Scanning resumed")
        }
        if (!Prefs.getBool(this, "setup_seen", false)) {
            Prefs.putBool(this, "setup_seen", true)
            ui.post { showSetup(firstRun = true) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra(Notifier.EXTRA_SELECT)?.let {
            selectedKey = it
            showTimeline = false
        }
    }

    override fun onStart() {
        super.onStart()
        Updater.uiVisible = true
    }

    override fun onStop() {
        super.onStop()
        Updater.uiVisible = false
        // A downloaded update installs now that you've left the app (if you're not scanning).
        if (!Engine.running) Updater.installPendingAsync(this)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
        headingTracker.start()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker) // scanning continues in ScanService
        headingTracker.stop()
        Engine.heading = null
    }

    // ---------- permissions / setup ----------

    private fun runtimePermissions(): List<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_SCAN
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.NEARBY_WIFI_DEVICES
            p += Manifest.permission.POST_NOTIFICATIONS
        }
        return p
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun missingRuntime() = runtimePermissions().filterNot { granted(it) }

    private fun requestRuntime(): Boolean {
        val m = missingRuntime()
        if (m.isEmpty()) return true
        requestPermissions(m.toTypedArray(), REQ_PERMS)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS && requestCode != REQ_START) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Location permission is required: Android only gives Wi-Fi/BLE scan results to apps with it.")
        }
        if (pendingStart && granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pendingStart = false
            ScanService.start(this)
        }
        if (requestCode == REQ_PERMS) ui.postDelayed({ showSetup(firstRun = false) }, 300)
    }

    private data class Check(val title: String, val ok: Boolean, val why: String, val fix: (() -> Unit)?)

    private fun checks(): List<Check> {
        val pm = getSystemService(PowerManager::class.java)
        val lm = getSystemService(LocationManager::class.java)
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        val list = ArrayList<Check>()
        list += Check(
            "Location, Nearby devices & Notifications", missingRuntime().isEmpty(),
            "Needed to see Wi-Fi/BLE results, measure distance and send alerts.",
        ) { requestRuntime() }
        list += Check("Location turned on", lm?.isLocationEnabled == true, "Android hides scan results while Location is off.") {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        list += Check("Bluetooth turned on", bt?.isEnabled == true, "Needed for trackers, Axon, Flock BLE and drones.") {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        list += Check(
            "Battery: unrestricted", pm.isIgnoringBatteryOptimizations(packageName),
            "Keeps scanning reliable with the screen off.",
        ) { requestBatteryExemption() }
        list += Check(
            "Network access", granted(Manifest.permission.INTERNET),
            "Only for mapped ALPRs, app updates and the iPhone HUD (GrapheneOS: Network permission).",
        ) { openAppSettings() }
        list += Check(
            "Allowed to install its own updates", packageManager.canRequestPackageInstalls(),
            "Lets AirScope update itself from your GitHub releases.",
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        list += Check(
            "Optional: Wi-Fi scan throttling off", false,
            "Developer options → Networking → Wi-Fi scan throttling → off. Otherwise Wi-Fi updates ~every 30 s.",
        ) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) {
                toast("Turn on Developer options first: Settings → About phone → tap Build number 7 times")
            }
        }
        list += Check(
            "Optional: sharper GPS",
            false,
            "AirScope already asks for full-rate dual-band GPS (L1+L5). For the best fix keep the phone where it sees the sky " +
                "(dash or window), and if your build has it, Developer options → \"Force full GNSS measurements\" → on.",
        ) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) {
                toast("Turn on Developer options first: Settings → About phone → tap Build number 7 times")
            }
        }
        list += Check(
            "Compass calibrated",
            !headingTracker.needsCalibration && !headingTracker.interference,
            if (headingTracker.interference) "Strong magnetic interference right now (car, steel, magnet case). Move away from metal, then wave the phone in a figure-8."
            else "Wave the phone in a slow figure-8 a few times if the facing cone looks wide or wrong.",
        ) { toast("Wave the phone in a slow figure-8, rotating it on all axes, for ~10 s") }
        return list
    }

    private fun showSetup(firstRun: Boolean) {
        if (isFinishing || isDestroyed) return
        val items = checks()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(4)) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (firstRun) "Set up AirScope" else "Setup check")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .create()
        if (firstRun) box.addView(text("Tap each item that isn't ✓ yet. AirScope only listens: it never connects to or transmits to other devices.\n", 12.5f, Color.LTGRAY))
        for (c in items) {
            val mark = if (c.ok) "✓" else if (c.title.startsWith("Optional")) "○" else "✗"
            val color = if (c.ok) Ui.GREEN else if (c.title.startsWith("Optional")) Ui.DIM else Ui.AMBER
            box.addView(text("$mark  ${c.title}", 14f, color, bold = true))
            box.addView(text(c.why, 12f, Color.LTGRAY).apply { setPadding(dp(22), 0, 0, dp(2)) })
            if (!c.ok && c.fix != null) {
                box.addView(button("FIX") { dialog.dismiss(); c.fix.invoke() }.apply {
                    layoutParams = LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(22); bottomMargin = dp(8) }
                })
            }
        }
        dialog.show()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Battery is already unrestricted.")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    // ---------- actions ----------

    private fun toggleScan() {
        if (Engine.running) {
            ScanService.stop(this)
        } else if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            ScanService.start(this)
            // Nice-to-have permissions (Nearby devices, notifications): ask once, never block scanning.
            val optional = missingRuntime()
            if (optional.isNotEmpty() && !Prefs.getBool(this, "asked_optional", false)) {
                Prefs.putBool(this, "asked_optional", true)
                requestPermissions(optional.toTypedArray(), REQ_START)
            }
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName) && !Prefs.getBool(this, "asked_battery", false)) {
                Prefs.putBool(this, "asked_battery", true)
                requestBatteryExemption()
            }
        } else {
            pendingStart = true
            requestPermissions(missingRuntime().toTypedArray(), REQ_START)
        }
    }

    private fun toggleHud() {
        Engine.hudOn = !Engine.hudOn
        if (Engine.hudOn && !Engine.running) toast("HUD will start with the scan")
        ScanService.refreshHud(this)
        if (Engine.hudOn) showHudInfo()
    }

    private fun showHudInfo() {
        val urls = ScanService.hudUrls()
        val msg = if (urls.isEmpty()) {
            "Connect this phone to Wi-Fi (or turn on its hotspot and join it from the iPhone), then tap HUD again."
        } else {
            "On your iPhone (same Wi-Fi), open this in Safari:\n\n${urls.joinToString("\n")}\n\n" +
                "Then Share → Add to Home Screen. The key in the link keeps others on the network out.\n\n" +
                "GrapheneOS: the Network permission must be on for the HUD."
        }
        AlertDialog.Builder(this).setTitle("Web HUD for iPhone").setMessage(msg).setPositiveButton("OK", null).show()
    }

    /** AUTO -> OPEN -> INDOOR -> DENSE -> CAR -> AUTO */
    private fun cycleEnv() {
        val all = Env.values()
        if (Engine.envAuto) {
            Engine.envAuto = false
            Engine.env = all[0]
        } else if (Engine.env.ordinal == all.size - 1) {
            Engine.envAuto = true
        } else {
            Engine.env = all[Engine.env.ordinal + 1]
        }
        Prefs.save(this)
        toast(if (Engine.envAuto) "Distance model: automatic (car when driving, outdoor/indoor from GPS)" else "Distance model: ${Engine.env.label}")
    }

    private fun setMode(m: Mode) {
        mode = m
        Prefs.putString(this, "mode", m.name)
        radar.mode = m
        for ((k, v) in chips) styleChip(v, k == m)
        lastListVersion = -1
    }

    private fun showMore(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Setup & permissions")
        pm.menu.add(0, 2, 0, "Updates…")
        pm.menu.add(0, 3, 0, if (Engine.alprOn) "Mapped ALPR alerts: ON" else "Mapped ALPR alerts: OFF")
        pm.menu.add(0, 4, 0, "Export CSV")
        pm.menu.add(0, 5, 0, "Clear session")
        pm.menu.add(0, 7, 0, "Session log (seen again)")
        pm.menu.add(0, 8, 0, "Diagnostics")
        pm.menu.add(0, 9, 0, "Offline map for this area…")
        pm.menu.add(0, 6, 0, "About & limits")
        pm.menu.add(0, 10, 0, "Exit (stop everything)")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showSetup(false)
                2 -> showUpdates()
                3 -> { Engine.setAlpr(!Engine.alprOn); toast(if (!Engine.alprOn) "Mapped ALPR alerts on" else "Mapped ALPR alerts off") }
                4 -> export()
                5 -> { Engine.clear(); selectedKey = null }
                6 -> showAbout()
                7 -> showSession()
                8 -> showDiagnostics()
                9 -> showOfflineMap()
                10 -> exitApp()
            }
            true
        }
        pm.show()
    }

    /** Every device seen since START, ones that came back (↻) first. */
    private fun showSession() {
        val rows = Engine.session
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        val again = rows.count { it.sightings >= 2 }
        val sb = StringBuilder()
        if (Engine.sessionStartedAt == 0L) sb.append("No scan session yet: tap START.\n")
        else sb.append("Session since ${tf.format(Date(Engine.sessionStartedAt))} · ${rows.size} devices · $again came back (↻)\n")
        sb.append("↻N = seen N separate times (gone 3+ min in between).\n\n")
        for (r in rows.take(300)) {
            val mark = if (r.sightings >= 2) "↻${r.sightings} " else "   "
            sb.append(mark).append(r.title.take(30)).append('\n')
            sb.append("    ").append(r.category.short).append(" · ").append(tf.format(Date(r.firstSeen))).append("–")
                .append(tf.format(Date(r.lastSeen))).append(" · best ").append(r.bestRssi).append(" dBm")
            if (r.spreadM >= 300) sb.append(" · ").append(fmtDist(r.spreadM)).append(" m apart")
            if (r.score > 0) sb.append(" · [").append(r.score).append("]")
            sb.append('\n')
        }
        val tv = text(sb.toString(), 11.5f, Color.LTGRAY).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("Session log")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Export CSV") { _, _ -> export() }
            .show()
    }

    /** Everything that can go wrong, in plain words, plus a live network test and a copyable report. */
    private fun showDiagnostics() {
        if (isFinishing || isDestroyed) return
        val tv = text(diagnosticsReport(), 11.5f, Color.LTGRAY).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setTextIsSelectable(true) }
        val d = AlertDialog.Builder(this).setTitle("Diagnostics")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Test network", null)
            .setNeutralButton("Copy report", null)
            .setNegativeButton("Close", null)
            .create()
        d.setOnShowListener {
            val testBtn = d.getButton(AlertDialog.BUTTON_POSITIVE)
            testBtn.setOnClickListener {
                testBtn.isEnabled = false
                tv.text = diagnosticsReport() + "\n\n== Network test ==\nTesting…"
                Thread {
                    val res = networkTest()
                    ui.post { if (!isDestroyed) { tv.text = diagnosticsReport() + "\n\n== Network test ==\n" + res; testBtn.isEnabled = true } }
                }.apply { isDaemon = true }.start()
            }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("AirScope diagnostics", tv.text))
                toast("Diagnostics copied: paste it into a message")
            }
        }
        d.show()
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "NO"

    private fun diagnosticsReport(): String = buildString {
        val s = Engine.snapshot
        val now = System.currentTimeMillis()
        fun ago(t: Long) = if (t <= 0L) "never" else "${(now - t) / 1000} s ago"
        append("AirScope build ").append(runCatching { Updater.installedBuild(this@MainActivity) }.getOrDefault(0L))
            .append(" · scanning: ").append(yesNo(s.scanning)).append('\n')
        s.warning?.let { append("⚠ ").append(it).append('\n') }

        append("\n== Permissions ==\n")
        append("Precise location: ").append(yesNo(granted(Manifest.permission.ACCESS_FINE_LOCATION))).append('\n')
        if (Build.VERSION.SDK_INT >= 31) append("Nearby devices (Bluetooth): ").append(yesNo(granted(Manifest.permission.BLUETOOTH_SCAN))).append('\n')
        if (Build.VERSION.SDK_INT >= 33) {
            append("Nearby Wi-Fi: ").append(yesNo(granted(Manifest.permission.NEARBY_WIFI_DEVICES))).append('\n')
            append("Notifications: ").append(yesNo(granted(Manifest.permission.POST_NOTIFICATIONS))).append('\n')
        }
        append("Battery unrestricted: ").append(yesNo(getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))).append('\n')

        append("\n== GPS ==\n")
        append("Location on: ").append(yesNo(getSystemService(LocationManager::class.java)?.isLocationEnabled == true)).append('\n')
        if (s.myLat != null) {
            append("Fix: ±").append(fmt1(s.myAccM)).append(" m · ").append(s.gnss.ifEmpty { "satellites unknown" }).append('\n')
            append("Speed: ").append((kotlin.math.hypot(s.myVx, s.myVy) * 2.23694).toInt()).append(" mph · distance model: ")
                .append(if (Engine.envAuto) "auto (" + Engine.env.label + ")" else Engine.env.label).append('\n')
        } else append("No fix yet: go outside or near a window. Indoors GPS can take minutes.\n")

        append("\n== Internet ==\n")
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps == null) append("No network connection at all.\n") else {
            val kind = when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "other"
            }
            append("Connected via ").append(kind)
                .append(" · internet verified: ").append(yesNo(caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))).append('\n')
            val ds = cm.restrictBackgroundStatus
            if (ds == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                append("⚠ Data Saver is ON and limits AirScope on cellular in the background: Settings → Network → Data Saver → allow AirScope.\n")
            }
        }
        append("GrapheneOS: the per-app \"Network\" permission must be on (App info → Permissions).\n")

        append("\n== Street map ==\n")
        append("Status: ").append(s.mapStatus.ifEmpty { "not started (tap START)" }).append('\n')
        val tm = Engine.geo.tiles(this@MainActivity)
        val st = tm.stats
        append("Source: OpenFreeMap vector tiles (fast, cached on the phone)\n")
        append("Downloaded ").append(st.downloaded).append(" · from phone storage ").append(st.fromDisk)
            .append(" · failed ").append(st.failed).append(" · last download ").append(st.lastLatencyMs).append(" ms\n")
        append("Last success: ").append(ago(st.lastOkAt)).append('\n')
        st.lastError?.let { append("Last error: ").append(it).append(" (").append(ago(st.lastErrorAt)).append(")\n") }
        val (n, bytes) = tm.diskUsage()
        append("Saved on phone: ").append(n).append(" tiles, ").append(bytes / (1024 * 1024)).append(" MB (works offline)\n")
        if (Engine.geo.predicted.isNotEmpty()) append("Route prediction: preloading ").append(Engine.geo.predicted.size).append(" points ahead\n")

        append("\n== Mapped ALPR cameras ==\n").append(s.alprStatus.ifEmpty { if (Engine.alprOn) "not loaded yet" else "off" }).append('\n')

        append("\n== Radios ==\n")
        val bt = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        append("Bluetooth on: ").append(yesNo(bt?.isEnabled == true)).append(" · BLE devices now: ").append(s.devices.count { it.kind == Kind.BLE }).append('\n')
        append("Wi-Fi networks now: ").append(s.devices.count { it.kind == Kind.WIFI }).append('\n')

        append("\n== Compass ==\n").append(headingTracker.diagnostics()).append('\n')

        append("\n== Web HUD ==\n").append(if (Engine.hudOn) "on · " + (ScanService.hudUrls().firstOrNull() ?: "no Wi-Fi address") else "off").append('\n')
        append("\n== Updates ==\n").append(Prefs.getString(this@MainActivity, Updater.PREF_RESULT, "never checked")).append('\n')

        val recent = s.timeline.filter { it.tag == "SYS" && now - it.time < 3_600_000 }.take(8)
        if (recent.isNotEmpty()) {
            append("\n== Recent events ==\n")
            for (e in recent) append(timeFmt.format(Date(e.time))).append("  ").append(e.text).append('\n')
        }
    }

    /** Background thread: can AirScope reach the map tiles, the ALPR database and GitHub right now? */
    private fun networkTest(): String = buildString {
        val s = Engine.snapshot
        val la = s.myLat ?: 33.92; val lo = s.myLon ?: -84.84
        val tile = Tiles.around(la, lo, 10.0, TileManager.Z).first()
        val checks = listOf(
            "Map tiles (OpenFreeMap)" to TileManager.url(tile),
            "ALPR data (Overpass)" to "https://overpass-api.de/api/status",
            "Updates (GitHub)" to UpdateCheck.LATEST_URL,
        )
        var okCount = 0
        for ((name, url) in checks) {
            val t0 = System.nanoTime()
            val r = try {
                val bytes = TileManager.httpGet(url)
                okCount++
                "OK in ${(System.nanoTime() - t0) / 1_000_000} ms (${bytes.size / 1024} KB)"
            } catch (e: Exception) {
                "FAILED after ${(System.nanoTime() - t0) / 1_000_000} ms: ${e.message ?: e.javaClass.simpleName}"
            }
            append(name).append(": ").append(r).append('\n')
        }
        when (okCount) {
            0 -> append("\nNothing reachable: AirScope has no internet. On GrapheneOS check the app's Network permission; also Data Saver, VPN or airplane mode.")
            checks.size -> append("\nAll reachable.")
            else -> append("\nSome services are down or blocked; the rest work. Saved map tiles still work offline.")
        }
    }

    /** Save the map around you to the phone so it works with no signal. */
    private fun showOfflineMap() {
        val s = Engine.snapshot
        val la = s.myLat; val lo = s.myLon
        if (la == null || lo == null) { toast("Needs your GPS position: tap START, wait for a fix, then try again"); return }
        val tm = Engine.geo.tiles(this)
        val options = listOf(5_000.0, 15_000.0, 30_000.0)
        val labels = options.map { r ->
            val n = Tiles.around(la, lo, r, TileManager.Z).size
            "${(r / 1609).toInt()} mi around here · $n tiles · about ${(n * 70 / 1024).coerceAtLeast(1)} MB"
        }
        AlertDialog.Builder(this).setTitle("Offline map (Wi-Fi recommended)")
            .setItems(labels.toTypedArray()) { _, which ->
                val radius = options[which]
                val msg = text("Starting…", 13f, Color.LTGRAY).apply { setPadding(dp(18), dp(12), dp(18), dp(8)) }
                var cancel = false
                val prog = AlertDialog.Builder(this).setTitle("Saving map").setView(msg)
                    .setNegativeButton("Cancel") { _, _ -> cancel = true }.setCancelable(false).show()
                Thread {
                    val failed = tm.downloadArea(la, lo, radius, { done, total, fail ->
                        ui.post { msg.text = "$done of $total tiles" + if (fail > 0) " · $fail failed" else "" }
                    }, { cancel })
                    ui.post {
                        if (!isDestroyed) prog.dismiss()
                        toast(if (cancel) "Stopped" else if (failed == 0) "Map saved: it now works here with no signal" else "Saved, but $failed tiles failed: try again on Wi-Fi")
                    }
                }.apply { isDaemon = true }.start()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Stop scanning, GPS, HUD and the notification, then close: nothing keeps running. */
    private fun exitApp() {
        Prefs.putBool(this, "was_running", false)
        if (Engine.running) ScanService.stop(this)
        finishAndRemoveTask()
    }

    private fun showUpdates() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val build = try { Updater.installedBuild(this) } catch (_: Exception) { 0L }
        val stable = Updater.signingKind(this) == "stable"
        box.addView(text("Installed build: $build\nSource: github.com/${UpdateCheck.OWNER}/${UpdateCheck.REPO} (Releases)\n" +
            "Checks every ~6 hours when online. Installs by itself, without asking, when you leave the app or stop " +
            "scanning, never while you're using it. No password needed.", 12.5f, Color.LTGRAY))
        box.addView(text(
            if (stable) "\n✓ Signed with your permanent key: this install can update itself."
            else "\n⚠ This install was signed with a one-time key, so it can't update itself. After GitHub has the " +
                "AIRSCOPE_SIGNING_SEED secret (SET SECRET), uninstall AirScope once and install the newest build from RELEASES.",
            12.5f, if (stable) Ui.GREEN else Ui.AMBER,
        ))
        if (!stable) box.addView(row(
            button("SET SECRET") { openUrl(Updater.SECRET_URL) },
            button("RELEASES") { openUrl(Updater.RELEASES_URL) },
        ))
        if (Prefs.getString(this, Updater.PREF_RESULT).contains("private")) {
            box.addView(text(
                "\nThe GitHub repo is private: REPO SETTINGS → scroll to Danger Zone → Change visibility → Public. " +
                    "Then the app can download updates with no token or password.",
                12f, Color.LTGRAY,
            ))
            box.addView(row(button("REPO SETTINGS") { openUrl(Updater.REPO_SETTINGS_URL) }))
        }
        val result = text("Last result: " + Prefs.getString(this, Updater.PREF_RESULT, "never checked"), 12.5f, Ui.AMBER)
        box.addView(result)
        val d = AlertDialog.Builder(this)
            .setTitle("Updates")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Check now", null)
            .setNegativeButton("Close", null)
            .create()
        d.setOnShowListener {
            val btn = d.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                result.text = "Checking…"
                btn.isEnabled = false
                Updater.checkAsync(this, manual = true) { msg ->
                    ui.post { result.text = "Result: $msg"; btn.isEnabled = true }
                }
            }
        }
        d.show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast("No browser found to open $url")
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("About AirScope").setMessage(
            "Passive Wi-Fi + Bluetooth observer. It only listens; it never connects, jams or transmits to other devices.\n\n" +
                "Map:\n• Phones can't measure the direction of a radio signal. AirScope places a device on the map by " +
                "measuring it from several GPS spots as you walk or drive (hollow = not located yet, solid = located ±error circle).\n" +
                "• Mapped ALPRs and Remote ID drones are placed at their exact positions.\n\nLimits:\n" +
                "• Distance from signal strength is an estimate (shown as a range). Wi-Fi RTT gives real distance for access points that support it.\n" +
                "• Android can't see Wi-Fi probe requests, so some Flock cameras are invisible over the air: mapped ALPR alerts (OpenStreetMap/DeFlock) cover those.\n" +
                "• With the screen off Android pauses unfiltered BLE scans; trackers, Flock BLE, Raven and Remote ID keep working.\n\n" +
                "Vendor DB: ${Engine.dbStatus}\n\n" +
                "Credits: Flock/Raven signatures from flock-you-esp32 (Apache-2.0) and the research it credits; ALPR map data © OpenStreetMap contributors (ODbL).",
        ).setPositiveButton("OK", null).show()
    }

    private fun export() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val s = Engine.snapshot
        try {
            saveToDownloads("airscope_devices_$stamp.csv", Exporter.devicesCsv(s.devices))
            saveToDownloads("airscope_timeline_$stamp.csv", Exporter.timelineCsv(s.timeline))
            saveToDownloads("airscope_session_$stamp.csv", Exporter.sessionCsv(Engine.session))
            toast("Saved 3 CSV files to Downloads/AirScope")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun saveToDownloads(name: String, content: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirScope")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("could not create file")
        (contentResolver.openOutputStream(uri) ?: throw IllegalStateException("could not open file")).use {
            it.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun select(key: String) {
        selectedKey = if (selectedKey == key) null else key
        lastListVersion = -1
    }

    private fun showDetails(d: DeviceView) {
        val msg = buildString {
            appendLine("Type: ${d.category.label}")
            if (d.vendor.isNotBlank()) appendLine("Maker: ${d.vendor}")
            if (d.name.isNotBlank()) appendLine("Broadcast name: ${d.name}")
            if (d.kind != Kind.MAP) appendLine("Signal: ${d.rssi} dBm now · ${d.avg} dBm smoothed")
            appendLine("Distance: ~${fmtDist(d.dist)} m (likely ${fmtDist(d.lo)}–${fmtDist(d.hi)} m)")
            appendLine("Method: ${d.distSrc}")
            if (d.kind != Kind.MAP) appendLine(
                if (d.located) String.format(Locale.US, "Map position: %.5f, %.5f (±%d m, from %d spots)", d.lat, d.lon, d.errM.toInt(), d.spots)
                else "Map position: not located yet (measured from ${d.spots} spots, moved ${d.spreadM.toInt()} m)",
            )
            if (d.kind != Kind.MAP) appendLine("Seen for: ${d.ageSec / 60} min ${d.ageSec % 60} s")
            appendLine("Score: ${d.score} (${d.level})")
            if (d.facts.isNotEmpty()) {
                appendLine()
                d.facts.forEach { appendLine("${it.first}: ${it.second}") }
            }
            if (d.flags.isNotEmpty()) {
                appendLine()
                d.flags.forEach { appendLine("• $it") }
            }
        }
        val b = AlertDialog.Builder(this)
            .setTitle(d.title)
            .setMessage(msg)
            .setPositiveButton("Track") { _, _ -> selectedKey = d.key; lastListVersion = -1 }
            .setNegativeButton("Close", null)
        if (d.kind != Kind.MAP) {
            b.setNeutralButton(if (d.trusted) "Unmark mine" else "Mark as mine") { _, _ -> Engine.setTrusted(d.key, !d.trusted) }
        }
        b.show()
    }

    // ---------- rendering ----------

    private fun refresh() {
        val s = Engine.snapshot
        val vis = s.devices.filter { mode.matches(it.category) }
        radar.update(s, Engine.geo.chunks, selectedKey, Engine.geo.origin)
        headingTracker.updateLocation(s.myLat, s.myLon)
        headingTracker.updateMotion(kotlin.math.hypot(s.myVx, s.myVy), s.myCourse, s.myAccM)
        Engine.heading = headingTracker.facing
        Engine.headingAcc = headingTracker.facingAcc
        if (headingTracker.needsCalibration && System.currentTimeMillis() - lastCalToast > 300_000L) {
            lastCalToast = System.currentTimeMillis()
            toast("Compass is unsure: wave the phone in a figure-8 a few times")
        }

        val wifiN = s.devices.count { it.kind == Kind.WIFI }
        val bleN = s.devices.count { it.kind == Kind.BLE }
        val police = s.devices.count { it.category == Category.POLICE }
        val sb = StringBuilder()
        sb.append(if (s.scanning) "● SCANNING" else "○ IDLE")
        sb.append("  WIFI $wifiN  BLE $bleN  HIGH ${s.devices.count { it.level == Level.HIGH }}")
        if (police > 0) sb.append("  POLICE $police")
        sb.append("\n").append(mode.label).append(": ").append(vis.size).append(" · ").append(if (Engine.envAuto) "auto: " + Engine.env.label.lowercase() else Engine.env.label)
        sb.append(" · ").append(if (s.hasFix) "GPS ±${fmt1(s.myAccM)} m" else "no GPS")
        if (s.gnss.isNotEmpty()) sb.append(" · ").append(s.gnss)
        val spd = kotlin.math.hypot(s.myVx, s.myVy)
        if (spd > 1.5) sb.append(" · ").append((spd * 2.23694).toInt()).append(" mph")
        val located = vis.count { it.located && it.kind != Kind.MAP }
        if (vis.isNotEmpty()) sb.append(" · located $located/${vis.count { it.kind != Kind.MAP }}")
        if (Engine.alprOn && (mode == Mode.POLICE || mode == Mode.ALL) && s.alprStatus.isNotEmpty()) sb.append(" · ALPR map: ").append(s.alprStatus)
        if (Engine.hudOn && s.scanning) {
            val now = System.currentTimeMillis()
            if (now - hudUrlAt > 10_000) { hudUrlAt = now; hudUrl = ScanService.hudUrls().firstOrNull() }
            hudUrl?.let { sb.append("\nHUD ").append(it) }
        }
        if (s.scanning && s.mapStatus.isNotEmpty() && radar.viewMode != MapView3D.ViewMode.RADAR) sb.append("\nMap: ").append(s.mapStatus.take(70))
        headingTracker.label()?.let { sb.append("\n").append(it) }
        s.warning?.let { sb.append("\n⚠ ").append(it) }
        setIfChanged(status, sb.toString())
        status.setTextColor(if (s.warning != null) Ui.AMBER else Ui.DIM)

        btnScan.text = if (s.scanning) "STOP" else "START"
        btnView.text = radar.viewMode.label
        btnCompass.text = if (radar.headingUp) "⬆" else "N"
        btnEnv.text = if (Engine.envAuto) "ENV:AUTO" else "ENV:" + Engine.env.name
        btnLog.text = if (showTimeline) "RADAR" else "LOG"
        btnWifi.alpha = if (Engine.wifiOn) 1f else 0.4f
        btnBle.alpha = if (Engine.bleOn) 1f else 0.4f
        btnHud.alpha = if (Engine.hudOn) 1f else 0.4f

        updateTrack(s)
        val now = System.currentTimeMillis()
        if (s.version != lastListVersion && now - lastListAt > 900) {
            lastListVersion = s.version
            lastListAt = now
            adapter.items = if (showTimeline) s.timeline else vis
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateTrack(s: Snapshot) {
        val d = selectedKey?.let { k -> s.devices.firstOrNull { it.key == k } }
        if (d == null) {
            trackBarText = if (selectedKey != null) "Signal lost: keep moving slowly, or tap another device" else
                "Tap a device to track it · long-press a row for full details"
            trackBar.setTextColor(Ui.DIM)
            return
        }
        if (d.kind == Kind.MAP) {
            val b = d.bearing!!.toDouble()
            trackBarText = "TRACK ${d.title.take(22)}  ${fmtDist(d.dist)} m ${Geo.cardinal16(b)}  ${guidance(s, b)}\n" +
                "Exact mapped position (OpenStreetMap)"
            trackBar.setTextColor(Ui.VIOLET)
            return
        }
        val bars = ((d.avg + 100).coerceIn(0, 70) * 12) / 70
        val ca = d.candA; val cb = d.candB
        val where = when {
            d.located && d.bearing != null -> {
                val b = d.bearing.toDouble()
                "${fmtDist(d.dist)} m ${Geo.cardinal16(b)} ±${d.errM.toInt()} m · ${guidance(s, b)}"
            }
            !s.hasFix -> "needs GPS to place on the map"
            ca != null && cb != null && s.myLat != null && s.myLon != null -> {
                val da = Geo.distanceM(s.myLat, s.myLon, ca.first, ca.second); val ba = Geo.bearingDeg(s.myLat, s.myLon, ca.first, ca.second)
                val db = Geo.distanceM(s.myLat, s.myLon, cb.first, cb.second); val bb = Geo.bearingDeg(s.myLat, s.myLon, cb.first, cb.second)
                "2 possible spots: ${fmtDist(da)} m ${Geo.cardinal16(ba)} or ${fmtDist(db)} m ${Geo.cardinal16(bb)} · walk sideways to settle it"
            }
            d.moving -> "it's moving (or being carried): no fixed spot, follow the signal strength"
            d.followM > 300 -> "moving with you (${fmtDist(d.followM)} so far)"
            else -> "direction unknown: walk ~${maxOf(10, (d.dist * 0.4).toInt())} m around (moved ${d.spreadM.toInt()} m, ${d.spots} spots)"
        }
        trackBarText = "TRACK ${d.title.take(20)}  ${d.avg}dBm  ~${fmtDist(d.dist)}m (${fmtDist(d.lo)}–${fmtDist(d.hi)})\n" +
            "[" + "█".repeat(bars) + "░".repeat(12 - bars) + "] " + where
        trackBar.setTextColor(Ui.levelColor(d.level))
    }

    /** Turn guidance toward a bearing: clock position when driving, "turn left 30°" when on foot. */
    private fun guidance(s: Snapshot, bearing: Double): String {
        val crs = s.myCourse
        if (kotlin.math.hypot(s.myVx, s.myVy) > 5.0 && crs != null) return "at ${HeadingMath.clock(bearing, crs)} o'clock"
        val f = headingTracker.facing ?: return ""
        return HeadingMath.turn(bearing, f, headingTracker.facingAcc)
    }

    private var trackBarText: String
        get() = trackBar.text.toString()
        set(v) { setIfChanged(trackBar, v) }

    private fun setIfChanged(tv: TextView, v: String) { if (tv.text.toString() != v) tv.text = v }

    private fun deviceLine(d: DeviceView): String {
        val icon = when (d.kind) { Kind.WIFI -> "●"; Kind.BLE -> "◆"; Kind.MAP -> "■" }
        val sig = if (d.kind == Kind.MAP) "map" else "${d.avg}dBm"
        val flags = if (d.flags.isEmpty()) "" else "\n   ! " + d.flags.take(3).joinToString("; ")
        val again = if (d.againMark.isNotEmpty()) "${d.againMark} " else ""
        return "$icon $again${d.title.take(34)}\n   ${sig.padEnd(7)} ~${fmtDist(d.dist)}m (${fmtDist(d.lo)}–${fmtDist(d.hi)})  " +
            "${d.category.short}${if (d.vendor.isNotBlank() && !d.title.contains(d.vendor)) " · " + d.vendor.take(18) else ""}  [${d.score}]$flags"
    }

    private inner class RowAdapter : BaseAdapter() {
        var items: List<Any> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            when (val item = items[position]) {
                is DeviceView -> {
                    tv.tag = item
                    tv.text = deviceLine(item)
                    tv.setTextColor(if (item.kind == Kind.MAP) Ui.VIOLET else Ui.levelColor(item.level))
                    tv.setBackgroundColor(if (item.key == selectedKey) Ui.SELECT_BG else Color.TRANSPARENT)
                }
                is DeviceStore.LogEntry -> {
                    tv.tag = null
                    tv.text = "${timeFmt.format(Date(item.time))} ${item.tag.padEnd(6)} ${item.text}"
                    tv.setTextColor(when (item.tag) { "HIGH" -> Ui.RED; "MEDIUM" -> Ui.AMBER; "SYS" -> Ui.CYAN; else -> Ui.DIM })
                    tv.setBackgroundColor(Color.TRANSPARENT)
                }
            }
            return tv
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left + dp(10), b.top + dp(8), b.right + dp(10), b.bottom + dp(8))
                WindowInsets.CONSUMED
            }
        }

        root.addView(text("AIRSCOPE // PASSIVE RF HUD", 17f, Ui.CYAN, bold = true))
        status = text("", 11.5f, Ui.DIM)
        root.addView(status)

        // Mode chips
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(2)) }
        for (m in Mode.values()) {
            val chip = TextView(this).apply {
                text = m.label
                textSize = 11f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { setMode(m) }
            }
            chips[m] = chip
            styleChip(chip, m == mode)
            chipRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })

        radar = MapView3D(this).apply {
            onSelect = { d -> select(d.key) }
            mode = this@MainActivity.mode
        }
        val mapBox = FrameLayout(this)
        mapBox.addView(radar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun mapBtn(t: String, onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Ui.CYAN)
            background = GradientDrawable().apply { setColor(Color.argb(200, 5, 9, 12)); setStroke(dp(1), Ui.CYAN); cornerRadius = dp(20).toFloat() }
            setOnClickListener { onClick() }
        }.also { tools.addView(it, LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(8) }) }
        mapBtn("◎") { radar.follow = true; toast("Following your position") }
        btnCompass = mapBtn("N") {
            toast(if (radar.cycleOrientation()) "Map turns with you (heading up)" else "North up")
        }
        mapBtn("＋") { radar.zoomBy(0.6) }
        mapBtn("－") { radar.zoomBy(1.6) }
        mapBox.addView(tools, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            topMargin = dp(6); rightMargin = dp(4)
        })
        root.addView(mapBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.35f))

        trackBar = text("", 12f, Ui.DIM).apply { setPadding(0, dp(4), 0, dp(4)) }
        root.addView(trackBar)

        btnScan = button("START") { toggleScan() }
        btnView = button("3D") {
            val m = radar.cycleView()
            Prefs.putString(this, "view_mode", m.name)
            toast(when (m) {
                MapView3D.ViewMode.RADAR -> "Radar: devices by distance, no street map"
                MapView3D.ViewMode.MAP -> "Street map (flat)"
                MapView3D.ViewMode.CITY -> "3D city: two-finger drag up/down to tilt, twist to rotate"
                MapView3D.ViewMode.DRIVE -> "Drive: turns with the road, zooms with speed, looks ahead"
            })
        }
        btnEnv = button("ENV") { cycleEnv() }
        btnLog = button("LOG") { showTimeline = !showTimeline; lastListVersion = -1 }
        root.addView(row(btnScan, btnView, btnEnv, btnLog))

        btnWifi = button("WI-FI") { Engine.wifiOn = !Engine.wifiOn; Prefs.save(this) }
        btnBle = button("BLE") { Engine.bleOn = !Engine.bleOn; Prefs.save(this) }
        btnHud = button("HUD") { toggleHud() }
        val btnMore = button("MORE ▾") {}
        btnMore.setOnClickListener { showMore(it) }
        root.addView(row(btnWifi, btnBle, btnHud, btnMore))

        list = ListView(this).apply {
            divider = ColorDrawable(Ui.GRID)
            dividerHeight = 1
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, v, _, _ -> (v.tag as? DeviceView)?.let { select(it.key) } }
            setOnItemLongClickListener { _, v, _, _ ->
                (v.tag as? DeviceView)?.let { tapped ->
                    showDetails(Engine.snapshot.devices.firstOrNull { it.key == tapped.key } ?: tapped)
                }
                true
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------- view helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun styleChip(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Ui.BG else Ui.CYAN)
        v.background = GradientDrawable().apply {
            setStroke(dp(1), Ui.CYAN)
            setColor(if (on) Ui.CYAN else Color.TRANSPARENT)
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(Ui.CYAN)
        textSize = 11f
        typeface = Typeface.MONOSPACE
        isAllCaps = true
        background = GradientDrawable().apply {
            setStroke(dp(1), Ui.CYAN)
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(2).toFloat()
        }
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(3), 0, dp(3))
        views.forEachIndexed { i, v ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(6)
            addView(v, lp)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
