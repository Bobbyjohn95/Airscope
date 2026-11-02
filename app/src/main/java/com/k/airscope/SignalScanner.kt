@file:Suppress("DEPRECATION")

package com.k.airscope

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import android.bluetooth.le.ScanResult as BleResult

/**
 * Passive radio observer. Only listens: Wi-Fi beacons/probe responses from the OS scanner,
 * BLE advertisements, and Wi-Fi RTT ranging with access points that advertise support for it.
 * Everything runs on the engine thread.
 */
@SuppressLint("MissingPermission")
class SignalScanner(ctx: Context, private val handler: Handler) {
    private val app = ctx.applicationContext
    private val store get() = Engine.store
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val bt: BluetoothAdapter? = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val rtt: WifiRttManager? =
        if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) app.getSystemService(WifiRttManager::class.java) else null
    private val executor = Executor { handler.post(it) }

    var running = false
        private set
    private var bleActive = false
    private var bleError: String? = null
    private var lastWifiScanOk = true
    private val rttCandidates = HashMap<String, ScanResult>()
    private var rttBusy = false

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = readWifi()
    }

    private val wifiTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (Engine.wifiOn) {
                lastWifiScanOk = try { wifi.startScan() } catch (_: SecurityException) { false }
                readWifi()
            }
            handler.postDelayed(this, 8_000)
        }
    }

    private val rttTick = object : Runnable {
        override fun run() {
            if (!running) return
            rangeAccessPoints()
            handler.postDelayed(this, 5_000)
        }
    }

    // Long unfiltered BLE scans get quietly downgraded by Android after ~30 min; restart periodically.
    private val bleRestart = object : Runnable {
        override fun run() {
            if (!running) return
            if (bleActive) { stopBle(); startBle() }
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    private val openCallback = makeCallback()
    private val filteredCallback = makeCallback()

    private fun makeCallback() = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleResult) {
            handler.post { handleBle(result) }
        }
        override fun onBatchScanResults(results: MutableList<BleResult>) {
            val copy = ArrayList(results)
            handler.post { for (r in copy) handleBle(r) }
        }
        override fun onScanFailed(errorCode: Int) {
            handler.post { bleError = "BLE scan failed (code $errorCode)" }
        }
    }

    fun start() {
        if (running) return
        running = true
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(wifiReceiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(wifiReceiver, filter, null, handler)
        }
        handler.post(wifiTick)
        handler.postDelayed(rttTick, 3_000)
        syncBle()
        handler.postDelayed(bleRestart, 5 * 60_000L)
        store.log("SYS", "Scan started")
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(wifiTick)
        handler.removeCallbacks(rttTick)
        handler.removeCallbacks(bleRestart)
        try { app.unregisterReceiver(wifiReceiver) } catch (_: Exception) { }
        stopBle()
        store.log("SYS", "Scan stopped")
    }

    fun syncBle() {
        val want = running && Engine.bleOn && bt?.isEnabled == true
        if (want && !bleActive) startBle()
        if (!want && bleActive) stopBle()
    }

    private fun startBle() {
        val scanner = bt?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        try {
            // 1) Unfiltered scan (extended advertising too): sees everything while the screen is on.
            val open = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            if (bt.isLeExtendedAdvertisingSupported) {
                open.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
            scanner.startScan(null, open.build(), openCallback)
            // 2) Filtered scan for trackers, Flock/Raven, drones and key vendors: Android keeps
            //    filtered scans running with the screen off; unfiltered ones are paused.
            val filtered = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(screenOffFilters(), filtered, filteredCallback)
            bleActive = true
            bleError = null
        } catch (_: SecurityException) {
            bleError = "Nearby devices permission missing"
        } catch (e: Exception) {
            bleError = "BLE start failed: ${e.message}"
        }
    }

    private fun stopBle() {
        val scanner = bt?.bluetoothLeScanner
        try {
            scanner?.stopScan(openCallback)
            scanner?.stopScan(filteredCallback)
        } catch (_: Exception) { }
        bleActive = false
    }

    private fun screenOffFilters(): List<ScanFilter> {
        val list = ArrayList<ScanFilter>()
        val services = listOf(0xFEED, 0xFEEC, 0xFD5A, 0xFCB2, 0xFE33, 0xFFFA) + Signatures.RAVEN_NAMED
        for (u in services) list += ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid16(u))).build()
        for (u in listOf(0xFEAA, 0xFD5A, 0xFFFA)) list += ScanFilter.Builder().setServiceData(ParcelUuid(uuid16(u)), ByteArray(0)).build()
        for (id in listOf(0x004C, 0x0075, Signatures.FLOCK_BLE_COMPANY)) list += ScanFilter.Builder().setManufacturerData(id, ByteArray(0)).build()
        list += ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(Signatures.FLOCK_ACCESSORY_UUID))).build()
        list += ScanFilter.Builder().setDeviceName("FS Ext Battery").build()
        return list
    }

    private fun uuid16(v: Int): UUID = UUID.fromString(String.format(Locale.US, "0000%04X-0000-1000-8000-00805F9B34FB", v))

    private fun handleBle(r: BleResult) {
        if (!Engine.bleOn || !running) return
        val dev = r.device ?: return
        val addr = dev.address ?: return
        val now = System.currentTimeMillis()
        val d = store.upsert(addr, Kind.BLE, now)
        val raw = r.scanRecord?.bytes
        val h = raw?.contentHashCode() ?: 0
        if (h != d.dataHash || now - d.classifiedAt > 15_000) {
            d.dataHash = h
            d.classifiedAt = now
            val adv = AdvParser.parse(raw)
            r.scanRecord?.deviceName?.takeIf { it.isNotBlank() }?.let { if (adv.name == null) adv.name = it }
            adv.name?.let { d.name = it }
            val random: Boolean? = if (Build.VERSION.SDK_INT >= 35) {
                when (dev.addressType) {
                    BluetoothDevice.ADDRESS_TYPE_RANDOM -> true
                    BluetoothDevice.ADDRESS_TYPE_PUBLIC -> false
                    else -> null
                }
            } else null
            d.randomAddr = random
            val c = Classifier.classifyBle(addr, random, adv, Engine.db)
            apply(d, c)
            if (r.isLegacy.not()) d.facts = d.facts + ("Advertising" to "extended (Bluetooth 5)")
            c.remoteId?.let { rid -> if (rid.lat != null) { d.selfLat = rid.lat; d.selfLon = rid.lon } }
            d.txPower = adv.txPower
        }
        d.addReading(r.rssi, now)
    }

    private fun apply(d: Device, c: Classification) {
        d.category = c.category
        d.label = c.label
        d.vendor = c.vendor
        d.facts = LinkedHashMap(c.facts)
        d.findings = ArrayList(c.findings)
        if (c.bleType.isNotEmpty()) d.bleType = c.bleType
        d.refAt1m = c.ref
        d.refSource = c.refSource
    }

    private fun readWifi() {
        if (!Engine.wifiOn || !running) return
        val results = try { wifi.scanResults } catch (_: SecurityException) { return } ?: return
        val now = System.currentTimeMillis()
        val bootNow = SystemClock.elapsedRealtime()
        for (r in results) {
            val bssid = r.BSSID?.lowercase(Locale.US) ?: continue
            if (bootNow - r.timestamp / 1000 > 60_000) continue // stale cached result
            val d = store.upsert(bssid, Kind.WIFI, now)
            if (r.timestamp == d.lastWifiTs) continue
            d.lastWifiTs = r.timestamp
            val ssid = (r.SSID ?: "").let { if (it == "<unknown ssid>") "" else it }
            val responder = r.is80211mcResponder
            d.rttCapable = responder
            if (responder) rttCandidates[bssid] = r
            val h = (ssid + r.capabilities + r.frequency).hashCode()
            if (h != d.dataHash || now - d.classifiedAt > 30_000) {
                d.dataHash = h
                d.classifiedAt = now
                d.name = ssid
                d.frequency = r.frequency
                d.security = r.capabilities ?: ""
                val ie = if (Build.VERSION.SDK_INT >= 30) {
                    WifiIe.parse(r.informationElements.map { e ->
                        val bb = e.bytes
                        val arr = ByteArray(bb.remaining()); bb.duplicate().get(arr)
                        Ie(e.id, arr)
                    })
                } else null
                val std = if (Build.VERSION.SDK_INT >= 30) r.wifiStandard else 0
                val width = when (r.channelWidth) {
                    ScanResult.CHANNEL_WIDTH_20MHZ -> 20
                    ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                    ScanResult.CHANNEL_WIDTH_80MHZ -> 80
                    ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
                    else -> if (Build.VERSION.SDK_INT >= 33 && r.channelWidth == ScanResult.CHANNEL_WIDTH_320MHZ) 320 else 0
                }
                val c = Classifier.classifyWifi(bssid, ssid, r.frequency, d.security, ie, std, width, responder, Engine.db)
                d.randomAddr = VendorDb.isRandomizedMac(bssid)
                apply(d, c)
            }
            d.addReading(r.level, now)
        }
    }

    /** Wi-Fi RTT (802.11mc FTM): real measured distance to access points that support it. */
    private fun rangeAccessPoints() {
        val m = rtt ?: return
        if (rttBusy || !Engine.wifiOn || !m.isAvailable || rttCandidates.isEmpty()) return
        val now = System.currentTimeMillis()
        val fresh = rttCandidates.values.filter { store.devices["WIFI:" + it.BSSID.lowercase(Locale.US)]?.let { d -> now - d.lastSeen < 60_000 } == true }
            .sortedByDescending { it.level }
            .take(RangingRequest.getMaxPeers())
        if (fresh.isEmpty()) return
        try {
            val req = RangingRequest.Builder().addAccessPoints(fresh).build()
            rttBusy = true
            m.startRanging(req, executor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) { rttBusy = false }
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    rttBusy = false
                    val t = System.currentTimeMillis()
                    for (res in results) {
                        if (res.status != RangingResult.STATUS_SUCCESS) continue
                        val mac = res.macAddress?.toString()?.lowercase(Locale.US) ?: continue
                        val d = store.devices["WIFI:$mac"] ?: continue
                        d.rttMeters = res.distanceMm / 1000.0
                        d.rttStd = res.distanceStdDevMm / 1000.0
                        d.rttAt = t
                    }
                }
            })
        } catch (_: SecurityException) {
            rttBusy = false
        } catch (_: Exception) {
            rttBusy = false
        }
    }

    fun warning(): String? {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return when {
            lm != null && !lm.isLocationEnabled -> "Location is OFF: Wi-Fi/BLE results need it on"
            Engine.bleOn && bt?.isEnabled != true -> "Bluetooth is OFF"
            bleError != null -> bleError
            Engine.wifiOn && !lastWifiScanOk -> "Wi-Fi scans throttled by Android (Setup explains how to turn that off)"
            else -> null
        }
    }
}
