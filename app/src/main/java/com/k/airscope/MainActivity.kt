package com.k.airscope

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        private const val REQ_PERMS = 42
    }

    private val store get() = ScanState.store
    private val ui = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var radar: RadarView
    private lateinit var status: TextView
    private lateinit var trackBar: TextView
    private lateinit var btnScan: Button
    private lateinit var btnLog: Button
    private lateinit var btnHud: Button
    private lateinit var list: ListView
    private val adapter = RowAdapter()

    private var showTimeline = false
    private var selectedKey: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        handleIntent(intent)
        if (!ScanState.running) requestNeededPermissions()
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
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker) // scanning continues in ScanService
    }

    // ---------- permissions ----------

    private fun wantedPermissions(): List<String> {
        val p = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_SCAN
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.NEARBY_WIFI_DEVICES
            p += Manifest.permission.POST_NOTIFICATIONS
        }
        return p
    }

    private fun missing(): List<String> =
        wantedPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun requestNeededPermissions(): Boolean {
        val m = missing()
        if (m.isEmpty()) return true
        requestPermissions(m.toTypedArray(), REQ_PERMS)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            toast("Location permission is required: Android only gives Wi-Fi/BLE scan results to apps with it.")
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Notifications denied: you won't get tracker alerts.")
        }
    }

    // ---------- actions ----------

    private fun startScan() {
        if (ScanState.running) return
        ScanService.start(this)
    }

    private fun toggleScan() {
        if (ScanState.running) {
            ScanService.stop(this)
        } else if (requestNeededPermissions()) {
            startScan()
        }
        ui.postDelayed({ refresh() }, 300)
    }

    private fun toggleHud() {
        ScanState.hudEnabled = !ScanState.hudEnabled
        if (ScanState.hudEnabled && !ScanState.running) {
            toast("HUD will start with the scan")
        }
        ScanService.refreshHud(this)
        ui.postDelayed({ refresh() }, 400)
        if (ScanState.hudEnabled) showHudInfo()
    }

    private fun showHudInfo() {
        val urls = ScanState.hudUrls()
        val msg = if (urls.isEmpty()) {
            "Connect this phone to Wi-Fi (or turn on its hotspot and join it from the iPhone), then tap HUD again."
        } else {
            "On your iPhone (same Wi-Fi), open this in Safari:\n\n${urls.joinToString("\n")}\n\n" +
                "Then Share → Add to Home Screen. The key in the link keeps other people on the network out."
        }
        AlertDialog.Builder(this)
            .setTitle("Web HUD for iPhone")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Battery: already unrestricted. Screen-off scanning is good to go.")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun export() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        try {
            saveToDownloads("airscope_devices_$stamp.csv", Exporter.devicesCsv(store))
            saveToDownloads("airscope_timeline_$stamp.csv", Exporter.timelineCsv(store))
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
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("could not create file")
        val out = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("could not open file")
        out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    private fun select(key: String) {
        selectedKey = if (selectedKey == key) null else key
        refresh()
    }

    private fun showDetails(d: Device) {
        val now = System.currentTimeMillis()
        val msg = buildString {
            appendLine("Type: ${if (d.kind == Kind.WIFI) "Wi-Fi access point" else "Bluetooth LE"}")
            appendLine("ID: ${d.id}")
            if (d.name.isNotBlank()) appendLine("Name: ${d.name}")
            appendLine("Details: ${d.detail()}")
            appendLine("Signal: ${d.rssi} dBm now, ${d.smoothRssi} avg")
            appendLine("Distance: ~${fmt1(d.distanceMeters())} m (rough)")
            appendLine("Seen for: ${(now - d.firstSeen) / 60_000} min, ${d.readings} readings")
            appendLine("Score: ${d.score} (${d.level})")
            if (d.flags.isNotEmpty()) {
                appendLine()
                d.flags.forEach { appendLine("• $it") }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(d.displayName())
            .setMessage(msg)
            .setPositiveButton("Track") { _, _ ->
                selectedKey = d.key
                refresh()
            }
            .setNeutralButton(if (d.trusted) "Unmark mine" else "Mark as mine") { _, _ ->
                store.setTrusted(d, !d.trusted)
                Prefs.saveTrusted(this)
                refresh()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------- rendering ----------

    private fun refresh() {
        val now = System.currentTimeMillis()
        val act = store.active(now).filter {
            (it.kind == Kind.WIFI && ScanState.wifiEnabled) || (it.kind == Kind.BLE && ScanState.bleEnabled)
        }
        radar.update(act, selectedKey)

        val wifiN = act.count { it.kind == Kind.WIFI }
        val high = act.count { it.level == Level.HIGH }
        val med = act.count { it.level == Level.MEDIUM }
        val sb = StringBuilder()
        sb.append(if (ScanState.running) "● SCANNING" else "○ IDLE")
        sb.append("   WIFI $wifiN   BLE ${act.size - wifiN}   MED $med   HIGH $high")
        if (ScanState.hudEnabled && ScanState.running) {
            ScanState.hudUrls().firstOrNull()?.let { sb.append("\nHUD ").append(it) }
        }
        ScanState.warning?.let { sb.append("\n⚠ ").append(it) }
        status.text = sb.toString()
        status.setTextColor(if (ScanState.warning != null) Ui.AMBER else Ui.DIM)

        btnScan.text = if (ScanState.running) "STOP" else "START"
        btnHud.alpha = if (ScanState.hudEnabled) 1f else 0.45f
        btnLog.text = if (showTimeline) "RADAR" else "LOG"

        adapter.items = if (showTimeline) {
            store.timeline.toList()
        } else {
            act.sortedWith(compareByDescending<Device> { it.score }.thenByDescending { it.smoothRssi })
        }
        adapter.notifyDataSetChanged()
        updateTrack(now)
    }

    private fun updateTrack(now: Long) {
        val d = selectedKey?.let { store.devices[it] }
        if (d == null) {
            trackBar.text = "Tap a blip or row to track it · long-press a row for details"
            trackBar.setTextColor(Ui.DIM)
            return
        }
        val stale = now - d.lastSeen > 15_000
        val bars = ((d.smoothRssi + 100).coerceIn(0, 70) * 10) / 70
        val meter = "█".repeat(bars) + "░".repeat(10 - bars)
        trackBar.text = "TRACK ${d.displayName().take(16)}  ${d.smoothRssi}dBm ~${fmt1(d.distanceMeters())}m\n[$meter]" +
            if (stale) "  signal lost" else "  move around: more bars = closer"
        trackBar.setTextColor(Ui.levelColor(d.level))
    }

    private fun deviceLine(d: Device): String {
        val tag = if (d.kind == Kind.WIFI) "WIFI" else "BLE "
        val flags = if (d.flags.isEmpty()) "" else "\n     ! " + d.flags.joinToString("; ")
        return "$tag ${d.smoothRssi.toString().padStart(4)}dBm ~${fmt1(d.distanceMeters()).padStart(4)}m " +
            "${d.displayName().take(26)}\n     ${d.id}  ${d.detail()}  [${d.score}]$flags"
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
                is Device -> {
                    tv.text = deviceLine(item)
                    tv.setTextColor(Ui.levelColor(item.level))
                    tv.setBackgroundColor(if (item.key == selectedKey) Ui.SELECT_BG else Color.TRANSPARENT)
                }
                is DeviceStore.LogEntry -> {
                    tv.text = "${timeFmt.format(Date(item.time))} ${item.tag.padEnd(6)} ${item.text}"
                    tv.setTextColor(
                        when (item.tag) {
                            "HIGH" -> Ui.RED
                            "MEDIUM" -> Ui.AMBER
                            "SYS" -> Ui.CYAN
                            else -> Ui.DIM
                        },
                    )
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
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left + dp(10), b.top + dp(8), b.right + dp(10), b.bottom + dp(8))
                WindowInsets.CONSUMED
            }
        }

        root.addView(text("AIRSCOPE // PASSIVE RF HUD", 17f, Ui.CYAN, bold = true))
        status = text("", 11.5f, Ui.DIM)
        root.addView(status)

        radar = RadarView(this).apply { onSelect = { d -> select(d.key) } }
        root.addView(radar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f))

        trackBar = text("", 12f, Ui.DIM).apply { setPadding(0, dp(4), 0, dp(4)) }
        root.addView(trackBar)

        btnScan = button("START") { toggleScan() }
        btnLog = button("LOG") {
            showTimeline = !showTimeline
            refresh()
        }
        btnHud = button("HUD") { toggleHud() }
        root.addView(
            row(
                btnScan,
                toggle("WI-FI", ScanState.wifiEnabled) { ScanState.wifiEnabled = it },
                toggle("BLE", ScanState.bleEnabled) { ScanState.bleEnabled = it },
                btnLog,
            ),
        )
        root.addView(
            row(
                btnHud,
                button("BATTERY") { requestBatteryExemption() },
                button("EXPORT") { export() },
                button("CLEAR") {
                    store.clear()
                    selectedKey = null
                    refresh()
                },
            ),
        )

        list = ListView(this).apply {
            divider = ColorDrawable(Ui.GRID)
            dividerHeight = 1
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                (this@MainActivity.adapter.items.getOrNull(pos) as? Device)?.let { select(it.key) }
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                (this@MainActivity.adapter.items.getOrNull(pos) as? Device)?.let { showDetails(it) }
                true
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------- small view helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setOnClickListener { onClick() }
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Button {
        var on = initial
        val b = button(label) {}
        b.alpha = if (on) 1f else 0.45f
        b.setOnClickListener {
            on = !on
            b.alpha = if (on) 1f else 0.45f
            onChange(on)
            refresh()
        }
        return b
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
