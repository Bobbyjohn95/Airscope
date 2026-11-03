package com.k.airscope

import java.util.Locale

/**
 * Surveillance / public-safety equipment signatures.
 *
 * Flock Safety, Raven and SoundThinking data is derived from flock-you-esp32
 * (github.com/simeononsecurity/flock-you-esp32, fy_detect.h, Apache License 2.0), which credits
 * @NitekryDPaul, DeFlockJoplin, dougborg, wgreenberg, GainSec, avenstewart and
 * colonelpanichacks/flock-you. See NOTICE.
 * Axon OUI 00:25:DF (Taser International) per public RFParty / ABC research.
 *
 * Phones only see Wi-Fi beacons/probe responses and BLE advertisements, not probe requests,
 * so a camera that only sends probe requests is invisible here. Mapped ALPRs (OpenStreetMap)
 * fill that gap.
 */
object Signatures {
    /** High-confidence Flock Safety OUIs (direct registration or confirmed Flock hardware). */
    val FLOCK_OUI_HIGH = setOf(
        "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "b8:35:32",
        "14:5a:fc", "74:4c:a1", "08:3a:88", "9c:2f:9d", "c0:35:32",
        "94:08:53", "e4:aa:ea", "24:b2:b9",
        "b8:1e:a4", "70:08:94", "58:8e:81", "ec:1b:bd", "3c:71:bf",
        "58:00:e3", "90:35:ea", "5c:93:a2", "64:6e:69", "48:27:ea",
        "a4:cf:12", "e0:4f:43",
        "82:6b:f2",
        "b4:1e:52",
        "04:0d:84", "f0:82:c0", "1c:34:f1", "38:5b:44", "94:34:69", "b4:e3:f9",
    )

    /** Contract manufacturers that build Flock hardware but also lots of unrelated gear: weak alone. */
    val FLOCK_OUI_MFR = setOf("f4:6a:dd", "f8:a2:d6", "14:b5:cd", "00:f4:8d", "d0:39:57", "e8:d0:fc", "e0:0a:f6", "00:03:7f")

    /** Factory-default radio MACs of unprovisioned Flock cameras. */
    val FLOCK_EXACT_MACS = setOf("00:03:7f:50:00:01", "00:03:7f:4f:00:16")

    val SOUNDTHINKING_OUI = setOf("d4:11:d6")

    val AXON_OUI = setOf("00:25:df")

    const val FLOCK_BLE_COMPANY = 0x09C8
    const val FLOCK_ACCESSORY_UUID = "e8ccbb38-9532-46a8-9fe5-1814df172e6f"
    val RAVEN_NAMED = setOf(0x3100, 0x3200, 0x3300, 0x3400, 0x3500)
    val RAVEN_RANGE = 0x3100..0x3500

    private val flockSsidExact = Regex("^flock-[0-9a-f]{6}$", RegexOption.IGNORE_CASE)
    private val flockKeywords = listOf("flocksafety", "flock", "penguin", "pigvision", "fs ext battery")
    private val penguinSerial = Regex("^penguin-\\d{10}$", RegexOption.IGNORE_CASE)
    private val bareSerial = Regex("^\\d{10}$")

    /** Cradlepoint-style default SSID: MODEL-xyz (3 hex from the MAC). */
    val fleetRouterSsid = Regex("^(IBR\\d{3,4}|R\\d{3,4}|AER\\d{4}|E\\d{3,4}|CBA\\d{3,4})(LP\\w*)?-[0-9a-f]{3}$", RegexOption.IGNORE_CASE)

    val publicSafetyVendor = Regex("(axon|taser|motorola solutions|watchguard|digital ally|kustom signals|l3harris|coban|utility associates|getac|soundthinking|shotspotter|flock safety)", RegexOption.IGNORE_CASE)
    val fleetRouterVendor = Regex("(cradlepoint|sierra wireless|inseego|digi international|peplink|pepwave)", RegexOption.IGNORE_CASE)

    fun oui(mac: String): String = mac.lowercase(Locale.US).take(8)

    /** Evidence that a MAC belongs to police / surveillance equipment. */
    fun macFindings(mac: String): List<Finding> {
        val m = mac.lowercase(Locale.US)
        val o = oui(m)
        val f = ArrayList<Finding>()
        if (m in FLOCK_EXACT_MACS) f += Finding(70, "Flock camera factory-default radio MAC")
        if (o in FLOCK_OUI_HIGH) f += Finding(55, "Flock Safety hardware address ($o)")
        if (o in FLOCK_OUI_MFR && m !in FLOCK_EXACT_MACS) f += Finding(10, "Maker also used for Flock hardware ($o): weak on its own")
        if (o in SOUNDTHINKING_OUI) f += Finding(45, "SoundThinking / ShotSpotter sensor address")
        if (o in AXON_OUI) f += Finding(60, "Axon police equipment (body cam / Taser / holster sensor)")
        return f
    }

    fun ssidFindings(ssid: String): List<Finding> {
        if (ssid.isBlank()) return emptyList()
        val s = ssid.lowercase(Locale.US)
        val f = ArrayList<Finding>()
        when {
            flockSsidExact.matches(ssid) -> f += Finding(70, "Flock camera setup network (Flock-XXXXXX)")
            s.contains("flock camera net") -> f += Finding(65, "Flock camera network (\"Flock Camera net.\")")
            flockKeywords.any { s.contains(it) } -> f += Finding(50, "Flock Safety network name")
            s.contains("flck") -> f += Finding(50, "Flock test network name (CVE-2025-59409)")
        }
        return f
    }

    /** Who a network name says it belongs to. */
    enum class Agency(val label: String) {
        POLICE("Police / sheriff / state patrol"),
        FIRE("Fire department"),
        EMS("Ambulance / EMS"),
        TRAFFIC("Traffic / enforcement camera"),
        COMMERCIAL("Commercial, transit or utility fleet"),
    }

    // Network names agencies give their vehicles and gear (e.g. "PCSO-Unit24", "CCPD 1234", "Medic 12", "Engine 5")
    private val lawSsid = Regex(
        "(police|sheriff|trooper|state ?patrol|highway ?patrol|\\bgsp\\b|\\bswat\\b|\\bk-?9\\b|\\bmarshal\\b|" +
            "\\bpd\\b|^[a-z]{1,4}pd[\\s_-]?\\d+\\b|^[a-z]{2,4}so[\\s_-]?\\d+\\b|\\bmdt\\b|\\bdps\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val fireSsid = Regex(
        "(fire ?(dept|department|station|rescue|engine|truck|district|ems)|\\bfd\\b|^[a-z]{1,4}fd[\\s_-]?\\d+\\b|" +
            "\\bengine[\\s_-]?\\d|\\bladder[\\s_-]?\\d|\\bquint[\\s_-]?\\d|\\bbattalion\\b|\\brescue[\\s_-]?\\d|\\bsquad[\\s_-]?\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val emsSsid = Regex("(\\bems\\b|\\bmedic[\\s_-]?\\d|ambulance|\\bamr\\b|\\bals[\\s_-]?\\d|\\bbls[\\s_-]?\\d|paramedic)", RegexOption.IGNORE_CASE)
    private val trafficSsid = Regex(
        "(traffic ?(cam|signal|control|light)|\\bgdot\\b|iteris|miovision|econolite|verra|redflex|blue ?line ?sol|" +
            "speed ?cam|red ?light ?cam|school ?zone|\\bits[\\s_-]?cam|signal ?cab)",
        RegexOption.IGNORE_CASE,
    )
    private val commercialSsid = Regex(
        "(\\bbus\\b|coach|transit|marta|cobblinc|xpress|school|kajeet|\\bups\\b|fedex|amazon|\\bdhl\\b|usps|waste|" +
            "\\bwm[\\s_-]|republic|uber|lyft|taxi|u-?haul|comcast|xfinity|spectrum|\\bat&?t\\b|\\btow|rental|hertz|" +
            "enterprise|delivery|freight|logistics|sysco|ryder|penske|fleet|truck|\\bvan\\b|shuttle|campus|church|hotel|" +
            "power|electric|\\bgas\\b|water|utility|cable|construction|plumb|hvac|pest|lawn)",
        RegexOption.IGNORE_CASE,
    )

    /** Agency from a network name, or null. Law/fire/EMS win over commercial words ("Fire Truck 3"). */
    fun agency(ssid: String): Agency? {
        if (ssid.isBlank()) return null
        return when {
            lawSsid.containsMatchIn(ssid) -> Agency.POLICE
            fireSsid.containsMatchIn(ssid) -> Agency.FIRE
            emsSsid.containsMatchIn(ssid) -> Agency.EMS
            trafficSsid.containsMatchIn(ssid) -> Agency.TRAFFIC
            commercialSsid.containsMatchIn(ssid) -> Agency.COMMERCIAL
            else -> null
        }
    }

    fun bleNameFindings(name: String?): List<Finding> {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return emptyList()
        val l = n.lowercase(Locale.US)
        return when {
            l == "fs ext battery" -> listOf(Finding(60, "Flock \"FS Ext Battery\" pack"))
            penguinSerial.matches(n) || l.startsWith("penguin") -> listOf(Finding(55, "Flock Penguin battery/camera module"))
            l.startsWith("flock") || l.startsWith("pigvision") -> listOf(Finding(55, "Flock Safety BLE device"))
            l.startsWith("axon") -> listOf(Finding(55, "Axon device name"))
            l == "raven" -> listOf(Finding(30, "Possible Raven gunshot detector (name only)"))
            l == "dfutarg" -> listOf(Finding(5, "Nordic firmware-update mode (seen on Flock packs mid-update)"))
            bareSerial.matches(n) -> listOf(Finding(5, "Bare 10-digit serial name (Flock pack format, weak)"))
            else -> emptyList()
        }
    }

    fun ravenFindings(uuid16: Set<Int>, uuid128: Set<String>): List<Finding> {
        val f = ArrayList<Finding>()
        val named = uuid16.filter { it in RAVEN_NAMED }
        val other = uuid16.filter { it in RAVEN_RANGE && it !in RAVEN_NAMED }
        if (named.isNotEmpty()) f += Finding(60, "Raven gunshot detector services (${named.joinToString { String.format(Locale.US, "0x%04X", it) }})")
        else if (other.isNotEmpty()) f += Finding(30, "Raven-range service UUIDs (possible gunshot detector)")
        if (FLOCK_ACCESSORY_UUID in uuid128) f += Finding(60, "Flock accessory service")
        return f
    }
}
