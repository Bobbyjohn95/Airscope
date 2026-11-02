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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity(), SensorEventListener {
    companion object {
        private const val REQ_PERMS = 42
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

    // Compass (for mapped ALPR bearings)
    private var sensors: SensorManager? = null
    private var headingDeg: Float? = null
    private val rot = FloatArray(9)
    private val orient = FloatArray(3)

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
        radar.threeD = Prefs.getBool(this, "view3d", true)
        handleIntent(intent)
        sensors = getSystemService(SensorManager::class.java)
        Updater.schedule(this)
        if (System.currentTimeMillis() - Prefs.getLong(this, Updater.PREF_LAST) > 3 * 3_600_000L &&
            Prefs.getString(this, Updater.PREF_TOKEN).isNotEmpty()
        ) {
            Updater.checkAsync(this, manual = false)
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

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker) // scanning continues in ScanService
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, orient)
        val az = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
        val prev = headingDeg
        val next = if (prev == null) az else {
            // Circular low-pass + dead-band so the map doesn't jitter
            val a = Math.toRadians(prev.toDouble()); val b = Math.toRadians(az.toDouble())
            val x = 0.9 * cos(a) + 0.1 * cos(b); val y = 0.9 * sin(a) + 0.1 * sin(b)
            ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        }
        if (prev == null || Geo.angleDiff(prev.toDouble(), next.toDouble()) > 0.8) {
            headingDeg = next
            radar.heading = next
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
        if (requestCode != REQ_PERMS) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Location permission is required: Android only gives Wi-Fi/BLE scan results to apps with it.")
        }
        if (pendingStart && granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pendingStart = false
            ScanService.start(this)
        }
        ui.postDelayed({ showSetup(firstRun = false) }, 300)
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
        return list
    }

    private fun showSetup(firstRun: Boolean) {
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
        } else if (requestRuntime()) {
            ScanService.start(this)
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName) && !Prefs.getBool(this, "asked_battery", false)) {
                Prefs.putBool(this, "asked_battery", true)
                requestBatteryExemption()
            }
        } else {
            pendingStart = true
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

    private fun cycleEnv() {
        val all = Env.values()
        Engine.env = all[(Engine.env.ordinal + 1) % all.size]
        Prefs.save(this)
        toast("Distance model: ${Engine.env.label}")
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
        pm.menu.add(0, 6, 0, "About & limits")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showSetup(false)
                2 -> showUpdates()
                3 -> { Engine.setAlpr(!Engine.alprOn); toast(if (!Engine.alprOn) "Mapped ALPR alerts on" else "Mapped ALPR alerts off") }
                4 -> export()
                5 -> { Engine.clear(); selectedKey = null }
                6 -> showAbout()
            }
            true
        }
        pm.show()
    }

    private fun showUpdates() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val build = try { Updater.installedBuild(this) } catch (_: Exception) { 0L }
        box.addView(text("Installed build: $build\nSource: github.com/${UpdateCheck.OWNER}/${UpdateCheck.REPO} (Releases)\n" +
            "Checks every ~6 hours when online, installs automatically when you're not scanning.\n", 12.5f, Color.LTGRAY))
        box.addView(text("GitHub token (read-only, needed for a private repo):", 12.5f, Ui.CYAN))
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "github_pat_…"
            setText(Prefs.getString(this@MainActivity, Updater.PREF_TOKEN))
        }
        box.addView(input)
        val result = text("Last result: " + Prefs.getString(this, Updater.PREF_RESULT, "never checked"), 12.5f, Ui.AMBER)
        box.addView(result)
        val d = AlertDialog.Builder(this)
            .setTitle("Updates")
            .setView(box)
            .setPositiveButton("Save & check now", null)
            .setNegativeButton("Close", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                Prefs.putString(this, Updater.PREF_TOKEN, input.text.toString().trim())
                result.text = "Checking…"
                Updater.checkAsync(this, manual = true) { msg -> ui.post { result.text = "Result: $msg" } }
            }
        }
        d.show()
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
            toast("Saved 2 CSV files to Downloads/AirScope")
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
        radar.update(s, Engine.geo.map, selectedKey)

        val wifiN = s.devices.count { it.kind == Kind.WIFI }
        val bleN = s.devices.count { it.kind == Kind.BLE }
        val police = s.devices.count { it.category == Category.POLICE }
        val sb = StringBuilder()
        sb.append(if (s.scanning) "● SCANNING" else "○ IDLE")
        sb.append("  WIFI $wifiN  BLE $bleN  HIGH ${s.devices.count { it.level == Level.HIGH }}")
        if (police > 0) sb.append("  POLICE $police")
        sb.append("\n").append(mode.label).append(": ").append(vis.size).append(" · ").append(Engine.env.label)
        sb.append(" · ").append(if (s.hasFix) "GPS ±${s.myAccM.toInt()} m" else "no GPS")
        val located = vis.count { it.located && it.kind != Kind.MAP }
        if (vis.isNotEmpty()) sb.append(" · located $located/${vis.count { it.kind != Kind.MAP }}")
        if (Engine.alprOn && (mode == Mode.POLICE || mode == Mode.ALL) && s.alprStatus.isNotEmpty()) sb.append(" · ALPR map: ").append(s.alprStatus)
        if (Engine.hudOn && s.scanning) ScanService.hudUrls().firstOrNull()?.let { sb.append("\nHUD ").append(it) }
        s.warning?.let { sb.append("\n⚠ ").append(it) }
        status.text = sb.toString()
        status.setTextColor(if (s.warning != null) Ui.AMBER else Ui.DIM)

        btnScan.text = if (s.scanning) "STOP" else "START"
        btnView.text = if (radar.threeD) "3D" else "2D"
        btnCompass.text = if (radar.headingUp) "⬆" else "N"
        btnEnv.text = "ENV:" + Engine.env.name
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
            trackBar.text = if (selectedKey != null) "Signal lost: keep moving slowly, or tap another device" else
                "Tap a device to track it · long-press a row for full details"
            trackBar.setTextColor(Ui.DIM)
            return
        }
        if (d.kind == Kind.MAP) {
            trackBar.text = "TRACK ${d.title.take(22)}  ${fmtDist(d.dist)} m ${Geo.cardinal(d.bearing!!.toDouble())}\n" +
                "Exact mapped position (OpenStreetMap)"
            trackBar.setTextColor(Ui.VIOLET)
            return
        }
        val bars = ((d.avg + 100).coerceIn(0, 70) * 12) / 70
        val where = when {
            d.located && d.bearing != null -> "on map ±${d.errM.toInt()} m · ${fmtDist(d.dist)} m ${Geo.cardinal(d.bearing.toDouble())} of you"
            !s.hasFix -> "needs GPS to place on the map"
            else -> "direction unknown: walk ~${maxOf(10, (d.dist * 0.4).toInt())} m around (moved ${d.spreadM.toInt()} m, ${d.spots} spots)"
        }
        trackBar.text = "TRACK ${d.title.take(20)}  ${d.avg}dBm  ~${fmtDist(d.dist)}m (${fmtDist(d.lo)}–${fmtDist(d.hi)})\n" +
            "[" + "█".repeat(bars) + "░".repeat(12 - bars) + "] " + where
        trackBar.setTextColor(Ui.levelColor(d.level))
    }

    private fun deviceLine(d: DeviceView): String {
        val icon = when (d.kind) { Kind.WIFI -> "●"; Kind.BLE -> "◆"; Kind.MAP -> "■" }
        val sig = if (d.kind == Kind.MAP) "map" else "${d.avg}dBm"
        val flags = if (d.flags.isEmpty()) "" else "\n   ! " + d.flags.take(3).joinToString("; ")
        return "$icon ${d.title.take(34)}\n   ${sig.padEnd(7)} ~${fmtDist(d.dist)}m (${fmtDist(d.lo)}–${fmtDist(d.hi)})  " +
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
                    tv.text = deviceLine(item)
                    tv.setTextColor(if (item.kind == Kind.MAP) Ui.VIOLET else Ui.levelColor(item.level))
                    tv.setBackgroundColor(if (item.key == selectedKey) Ui.SELECT_BG else Color.TRANSPARENT)
                }
                is DeviceStore.LogEntry -> {
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
            radar.headingUp = !radar.headingUp
            toast(if (radar.headingUp) "Map turns with you (heading up)" else "North up")
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
            radar.threeD = !radar.threeD
            Prefs.putBool(this, "view3d", radar.threeD)
            toast(if (radar.threeD) "3D: two-finger drag up/down to tilt, twist to rotate" else "2D map")
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
            setOnItemClickListener { _, _, pos, _ -> (this@MainActivity.adapter.items.getOrNull(pos) as? DeviceView)?.let { select(it.key) } }
            setOnItemLongClickListener { _, _, pos, _ ->
                (this@MainActivity.adapter.items.getOrNull(pos) as? DeviceView)?.let { showDetails(it) }
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
