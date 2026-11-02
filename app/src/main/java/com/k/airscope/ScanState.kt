package com.k.airscope

/** Process-wide state shared by the scan service and the UI (main thread only, except snapshotJson). */
object ScanState {
    const val HUD_PORT = 8787

    val store = DeviceStore()

    var running = false
    var wifiEnabled = true
    var bleEnabled = true
    var hudEnabled = false
    var hudToken = ""
    var warning: String? = null

    @Volatile
    var snapshotJson: String = "{\"devices\":[],\"timeline\":[]}"

    fun hudUrls(): List<String> =
        HudServer.localIps().map { "http://$it:$HUD_PORT/?k=$hudToken" }
}
