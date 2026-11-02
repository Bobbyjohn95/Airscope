@file:Suppress("DEPRECATION")

package com.k.airscope

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.Locale
import java.util.UUID
import android.bluetooth.le.ScanResult as BleResult

/**
 * Passive radio observer. Only listens: Wi-Fi beacon/probe-response results from the OS
 * scanner and BLE advertisements. Nothing is transmitted to or connected to other devices.
 */
@SuppressLint("MissingPermission")
class SignalScanner(ctx: Context, private val store: DeviceStore) {
    private val app = ctx.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val bt: BluetoothAdapter? = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    var running = false
        private set
    private var bleActive = false
    private var bleError: String? = null
    private var lastWifiScanOk = true
    private var wifiIntervalMs = 8_000L

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = readWifi()
    }

    private val wifiTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (ScanState.wifiEnabled) {
                lastWifiScanOk = try {
                    wifi.startScan()
                } catch (_: SecurityException) {
                    false
                }
                readWifi()
            }
            handler.postDelayed(this, wifiIntervalMs)
        }
    }

    // Long unfiltered BLE scans get quietly downgraded by Android after ~30 min; restart periodically.
    private val bleRestart = object : Runnable {
        override fun run() {
            if (!running) return
            if (bleActive) {
                stopBle()
                startBle()
            }
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    private val openCallback = makeCallback()
    private val trackerCallback = makeCallback()

    private fun makeCallback() = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleResult) = handleBle(result)
        override fun onBatchScanResults(results: MutableList<BleResult>) {
            for (r in results) handleBle(r)
        }
        override fun onScanFailed(errorCode: Int) {
            bleError = "BLE scan failed (code $errorCode)"
        }
    }

    fun start() {
        if (running) return
        running = true
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(wifiReceiver, filter)
        }
        handler.post(wifiTick)
        syncBle()
        handler.postDelayed(bleRestart, 5 * 60_000L)
        store.log("SYS", "Scan started")
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(wifiTick)
        handler.removeCallbacks(bleRestart)
        try {
            app.unregisterReceiver(wifiReceiver)
        } catch (_: Exception) {
        }
        stopBle()
        store.log("SYS", "Scan stopped")
    }

    /** Called every service tick so the BLE toggle and Bluetooth on/off are picked up. */
    fun syncBle() {
        val want = running && ScanState.bleEnabled && bt?.isEnabled == true
        if (want && !bleActive) startBle()
        if (!want && bleActive) stopBle()
    }

    private fun startBle() {
        val scanner = bt?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            // 1) Unfiltered scan: sees everything while the screen is on.
            scanner.startScan(null, settings, openCallback)
            // 2) Filtered scan for trackers/major vendors: Android keeps filtered scans
            //    running with the screen off, unfiltered ones are paused.
            scanner.startScan(trackerFilters(), settings, trackerCallback)
            bleActive = true
            bleError = null
        } catch (e: SecurityException) {
            bleError = "Nearby devices permission missing"
        } catch (e: Exception) {
            bleError = "BLE start failed: ${e.message}"
        }
    }

    private fun stopBle() {
        val scanner = bt?.bluetoothLeScanner
        try {
            scanner?.stopScan(openCallback)
            scanner?.stopScan(trackerCallback)
        } catch (_: Exception) {
        }
        bleActive = false
    }

    private fun trackerFilters(): List<ScanFilter> {
        val list = ArrayList<ScanFilter>()
        for (u in BleClassifier.FILTER_SERVICE_UUIDS) {
            list += ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid16(u))).build()
        }
        for (u in BleClassifier.FILTER_SERVICE_DATA) {
            list += ScanFilter.Builder().setServiceData(ParcelUuid(uuid16(u)), ByteArray(0)).build()
        }
        for (id in BleClassifier.FILTER_COMPANY_IDS) {
            list += ScanFilter.Builder().setManufacturerData(id, ByteArray(0)).build()
        }
        return list
    }

    private fun uuid16(v: Int): UUID =
        UUID.fromString(String.format(Locale.US, "0000%04X-0000-1000-8000-00805F9B34FB", v))

    private fun short16(p: ParcelUuid): Int = ((p.uuid.mostSignificantBits ushr 32) and 0xFFFF).toInt()

    private fun handleBle(r: BleResult) {
        if (!ScanState.bleEnabled || !running) return
        val addr = r.device?.address ?: return
        val now = System.currentTimeMillis()
        val d = store.upsert(addr, Kind.BLE, now)
        val rec = r.scanRecord
        if (rec != null) {
            rec.deviceName?.takeIf { it.isNotBlank() }?.let { d.name = it }
            val tx = rec.txPowerLevel
            if (tx in -100..20) d.txPower = tx

            val msd = HashMap<Int, ByteArray>()
            rec.manufacturerSpecificData?.let { sa ->
                for (i in 0 until sa.size()) msd[sa.keyAt(i)] = sa.valueAt(i) ?: ByteArray(0)
            }
            val uuids = HashSet<Int>()
            rec.serviceUuids?.forEach { uuids += short16(it) }
            val sd = HashMap<Int, ByteArray>()
            rec.serviceData?.forEach { (k, v) -> sd[short16(k)] = v ?: ByteArray(0) }

            val c = BleClassifier.classify(msd, uuids, sd)
            if (c.type.isNotEmpty()) d.bleType = c.type
            if (c.maker.isNotEmpty()) d.manufacturer = c.maker
        }
        d.addReading(r.rssi, now)
    }

    private fun readWifi() {
        if (!ScanState.wifiEnabled || !running) return
        val results = try {
            wifi.scanResults
        } catch (_: SecurityException) {
            return
        } ?: return
        val now = System.currentTimeMillis()
        val bootNow = SystemClock.elapsedRealtime()
        for (r in results) {
            val bssid = r.BSSID ?: continue
            val tsMs = r.timestamp / 1000 // microseconds since boot -> ms
            if (bootNow - tsMs > 60_000) continue // stale cached result
            val d = store.upsert(bssid, Kind.WIFI, now)
            if (r.timestamp == d.lastWifiTs) continue // already counted this observation
            d.lastWifiTs = r.timestamp
            d.name = (r.SSID ?: "").let { if (it == "<unknown ssid>") "" else it }
            d.frequency = r.frequency
            d.security = r.capabilities ?: ""
            d.addReading(r.level, now)
        }
    }

    fun warning(): String? {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return when {
            lm != null && !lm.isLocationEnabled -> "Location is OFF: Wi-Fi/BLE results need it on"
            ScanState.bleEnabled && bt?.isEnabled != true -> "Bluetooth is OFF"
            bleError != null -> bleError
            ScanState.wifiEnabled && !lastWifiScanOk -> "Wi-Fi scans throttled by Android (see README to disable)"
            else -> null
        }
    }
}
