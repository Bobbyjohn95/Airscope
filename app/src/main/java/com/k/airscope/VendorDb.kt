package com.k.airscope

import java.util.Locale

/**
 * Vendor lookups:
 *  - IEEE MAC prefixes (OUI) -> company
 *  - Bluetooth SIG company identifiers (manufacturer data) -> company
 *  - Bluetooth SIG 16-bit UUIDs (GATT services + member UUIDs) -> name
 * The full tables are downloaded by the GitHub build into assets/db (tsv files). Small built-in
 * tables keep the app useful if a build couldn't fetch them.
 */
class VendorDb {
    private var ouiKeys = IntArray(0)
    private var ouiNames = arrayOf<String>()
    private val companies = HashMap<Int, String>(BUILTIN_COMPANIES)
    private val uuids = HashMap<Int, String>(BUILTIN_UUIDS)
    var ouiCount = 0
        private set
    var companyCount = companies.size
        private set

    /** Lines of "aabbcc<TAB>Vendor". */
    fun loadOui(lines: Sequence<String>) {
        val pairs = ArrayList<Pair<Int, String>>(40_000)
        for (line in lines) {
            val t = line.indexOf('\t')
            if (t != 6) continue
            val k = line.substring(0, 6).toIntOrNull(16) ?: continue
            pairs += k to line.substring(7).trim()
        }
        pairs.sortBy { it.first }
        ouiKeys = IntArray(pairs.size) { pairs[it].first }
        ouiNames = Array(pairs.size) { pairs[it].second }
        ouiCount = pairs.size
    }

    /** Lines of "004c<TAB>Apple" (hex id). */
    fun loadCompanies(lines: Sequence<String>) {
        for (line in lines) {
            val t = line.indexOf('\t'); if (t <= 0) continue
            val id = line.substring(0, t).toIntOrNull(16) ?: continue
            companies[id] = line.substring(t + 1).trim()
        }
        companyCount = companies.size
    }

    fun loadUuids(lines: Sequence<String>) {
        for (line in lines) {
            val t = line.indexOf('\t'); if (t <= 0) continue
            val id = line.substring(0, t).toIntOrNull(16) ?: continue
            uuids[id] = line.substring(t + 1).trim()
        }
    }

    fun oui(mac: String): String? {
        val hex = mac.replace(":", "").replace("-", "")
        if (hex.length < 6) return null
        val k = hex.substring(0, 6).toIntOrNull(16) ?: return null
        val i = java.util.Arrays.binarySearch(ouiKeys, k)
        return if (i >= 0) ouiNames[i] else null
    }

    fun company(id: Int): String? = companies[id]

    fun uuid16(u: Int): String? = uuids[u]

    companion object {
        /** Locally administered bit set -> randomized / private MAC. */
        fun isRandomizedMac(mac: String): Boolean {
            val first = mac.take(2).toIntOrNull(16) ?: return false
            return (first and 0x02) != 0
        }

        private val suffix = Regex(
            "[,.]?\\s*(Inc|Incorporated|Ltd|Limited|LLC|L\\.L\\.C|Co|Corp|Corporation|GmbH|AG|S\\.A|SA|B\\.V|BV|AB|Oy|Pty|PLC|" +
                "Holdings?|Company|Technologies|Technology|Electronics|International|Communications)\\.?,?$",
            RegexOption.IGNORE_CASE,
        )

        fun shortVendor(v: String): String {
            var s = v.trim()
            while (true) {
                val n = s.replace(suffix, "").trim().trimEnd(',', '.')
                if (n == s || n.isEmpty()) break
                s = n
            }
            return s.ifEmpty { v }
        }

        val BUILTIN_COMPANIES = mapOf(
            0x004C to "Apple", 0x0006 to "Microsoft", 0x0075 to "Samsung", 0x00E0 to "Google",
            0x0087 to "Garmin", 0x0059 to "Nordic Semiconductor", 0x02E5 to "Espressif", 0x012D to "Sony",
            0x009E to "Bose", 0x0157 to "Huami (Amazfit)", 0x038F to "Xiaomi", 0x0171 to "Amazon",
            0x00D2 to "Dialog Semiconductor", 0x000D to "Texas Instruments", 0x0499 to "Ruuvi",
            Signatures.FLOCK_BLE_COMPANY to "XUNTONG (Flock Safety)",
        )

        val BUILTIN_UUIDS = mapOf(
            0x1800 to "Generic Access", 0x180A to "Device Information", 0x180D to "Heart Rate",
            0x180F to "Battery", 0x1812 to "Human Interface Device", 0x1816 to "Cycling Speed/Cadence",
            0x1818 to "Cycling Power", 0x1814 to "Running Speed/Cadence", 0x1810 to "Blood Pressure",
            0x1809 to "Health Thermometer", 0x181A to "Environmental Sensing", 0x1826 to "Fitness Machine",
            0x184E to "Audio Stream Control", 0xFE2C to "Google Fast Pair", 0xFEAA to "Google (Eddystone / Find Hub)",
            0xFEED to "Tile", 0xFEEC to "Tile", 0xFD5A to "Samsung SmartTag", 0xFCB2 to "Unwanted-tracker alert (DULT)",
            0xFFFA to "ASTM Remote ID", 0xFE33 to "Chipolo", 0xFE9F to "Google", 0xFD6F to "Exposure Notification",
        )
    }
}

/** Vendor-name and device-name patterns used to put things into categories. */
object Patterns {
    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    val phoneName = rx("(iphone|ipad|galaxy (s|a|z|note)|\\bpixel \\d|oneplus|\\bmoto|redmi|poco|xperia|nothing phone|'s phone|android_|\\bphone\\b)")
    val computerName = rx("(macbook|imac|mac mini|mac studio|desktop-|laptop-|thinkpad|surface|chromebook|\\bpc\\b|ideapad|xps)")
    val wearableName = rx("(watch|band|buds|airpods|beats|\\bwh-|\\bwf-|jbl|bose|jabra|sennheiser|soundcore|skullcandy|fitbit|garmin|oura|whoop|amazfit|mi band|versa|forerunner|fenix|venu|polar|suunto|coros|earbuds|headphones|headset|hearing)")
    val vehicleName = rx("(tesla|chevrolet|chevy|\\bgmc\\b|buick|cadillac|(?<![a-z])ford(?![a-z])|myford|fordpass|fordwifi|(?<![a-z])lincoln(?![a-z])|lincolnwifi|mylincoln|toyota|lexus|(?<![a-z])honda|myhonda|acura|nissan|infiniti|hyundai|\\bkia\\b|subaru|mazda|jeep|dodge|chrysler|\\bram\\b|bmw|mercedes|\\baudi\\b|\\bvw\\b|volkswagen|volvo|porsche|rivian|polestar|uconnect|\\bsync\\b|carplay|android auto|\\bmmi\\b|obd|vgate|elm327|dash ?cam|70mai|viofo|blackvue|nextbase|\\bcar\\b|car ?hotspot|my ?car)")
    val teslaBle = Regex("^S[0-9a-f]{16}[CDPR]$")
    // Word boundaries that treat digits and "_" as separators (unlike \b): "cam" must not be followed by a letter
    // (WyzeCam, IPCAM-1, Cam_2, BackyardCams, camera yes; Campbell, Camden, Cambridge, Camry no), and arlo / spy / nest /
    // hue / kasa must start a word (Charlotte, Despy, Ernest, Huerta are not gadgets; Google_Nest, MyArlo, Hue_Bridge are).
    private const val CAM = "cam(era|s)?(?![a-z])|camhi"
    val cameraIotName = rx("($CAM|ipc|dvr|nvr|wyze|v380|tuya|smartlife|esp[_-]|blink|(?<![a-z])arlo|myarlo|^ring|yi[-_]|xmeye|jxl|(?<![a-z])spy|spycam|hidvr|hikvision|dahua|reolink|eufy|tapo|(?<![a-z])kasa(?![a-z])|govee|(?<![a-z])hue(?![a-z])|huebridge|philips.?hue|switchbot|meross|(^|[^a-z])wiz([^a-z]|$)|\\bled\\b|bulb|plug|\\block\\b|roku|chromecast|echo|fire ?tv|sonos|printer|\\bhp-|epson|canon|brother|\\btv\\b|bravia|vizio|(?<![a-z])nest|googlenest|ecobee|doorbell|thermostat|sensor)")
    val droneName = rx("(dji|mavic|tello|skydio|anafi|autel|\\bspark-|phantom|fpv|\\bmini \\d|avata)")
    val cameraName = rx("($CAM|ipc|dvr|nvr|wyze|v380|xmeye|jxl|(?<![a-z])spy|spycam|hidvr|hikvision|dahua|reolink|eufy|blink|(?<![a-z])arlo|myarlo|^ring|esp[_-]|tuya|smartlife)")
    val hotspotName = rx("(iphone|android|galaxy|pixel|hotspot|moto|oneplus|redmi|'s phone|^direct-.*android)")

    val wearableVendor = rx("(garmin|fitbit|polar|suunto|oura|whoop|huami|zepp|amazfit|bose|jabra|gn audio|gn hearing|sennheiser|skullcandy|harman|bang & olufsen|shokz|sonova|oticon|widex|starkey|bragi|nothing technology)")
    val cameraIotVendor = rx("(hikvision|dahua|reolink|wyze|\\barlo\\b|\\bring\\b|\\bblink\\b|eufy|anker|axis communications|hanwha|uniview|amcrest|lorex|swann|verkada|ezviz|imou|espressif|tuya|signify|philips lighting|govee|ecobee|sonos|roku|nest labs|amazon technologies|tp-link|shelly|allterco|lifx|\\bwiz\\b|belkin|chamberlain|\\baugust\\b|\\byale\\b|honeywell|resideo|seeed|raspberry pi|azurewave|texas instruments|silicon labs|nordic semiconductor|hp inc|hewlett|seiko epson|canon|brother industries|lexmark|xerox)")
    val vehicleVendor = rx("(tesla|general motors|ford motor|toyota|\\bhonda\\b|nissan|hyundai|\\bkia\\b|subaru|mazda|stellantis|\\bfca\\b|bmw|daimler|mercedes|volkswagen|volvo car|porsche|rivian|continental automotive|visteon|denso|alps alpine|panasonic automotive|harman becker|bosch car|aptiv|delphi|lg innotek|lear corp|magna|mobileye|ficosa)")
    val droneVendor = rx("(\\bdji\\b|skydio|parrot|autel|yuneec)")
    val phoneVendor = rx("(apple|samsung|google|motorola mobility|oneplus|xiaomi|huawei|oppo|vivo|realme|sony mobile|nothing technology|hmd global|zte|tcl)")
    val computerVendor = rx("(\\bintel\\b|\\bdell\\b|lenovo|asustek|\\bacer\\b|microsoft)")
}
