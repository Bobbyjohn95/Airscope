"""
AirScope desktop core: device store, classification, scoring. A port of the Android app's pure-logic files
(Classifier, Signatures, Threats, Names, Heuristics, DeviceStore) to Python, trimmed to what a laptop can see.

Everything here is passive: it only interprets what Wi-Fi access points and BLE devices already broadcast.
No Android, no network calls. Vendor tables come from the same TSVs the app bundles (tools/fetch_vendor_db.py).
"""
from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

# ----------------------------------------------------------------------------- categories

CATEGORIES = ["PHONE", "WEARABLE", "TRACKER", "CAMERA_IOT", "VEHICLE", "DRONE", "POLICE", "NETWORK"]
CAT_LABEL = {
    "PHONE": "Phones & computers", "WEARABLE": "Wearables & audio", "TRACKER": "Trackers", "CAMERA_IOT": "Cameras & IoT",
    "VEHICLE": "Vehicles", "DRONE": "Drones", "POLICE": "Police, fire, EMS & cameras", "NETWORK": "Networks & other",
}


def _rx(s: str) -> re.Pattern:
    return re.compile(s, re.I)


# Same patterns as VendorDb.Patterns in the app (keep in step).
PHONE_NAME = _rx(r"(iphone|ipad|galaxy (s|a|z|note)|\bpixel \d|oneplus|\bmoto|redmi|poco|xperia|nothing phone|'s phone|android_|\bphone\b)")
COMPUTER_NAME = _rx(r"(macbook|imac|mac mini|mac studio|desktop-|laptop-|thinkpad|surface|chromebook|\bpc\b|ideapad|xps)")
WEARABLE_NAME = _rx(r"(watch|band|buds|airpods|beats|\bwh-|\bwf-|jbl|bose|jabra|sennheiser|soundcore|skullcandy|fitbit|garmin|oura|whoop|amazfit|mi band|versa|forerunner|fenix|venu|polar|suunto|coros|earbuds|headphones|headset|hearing)")
VEHICLE_NAME = _rx(r"(tesla|chevrolet|chevy|\bgmc\b|buick|cadillac|(?<![a-z])ford(?![a-z])|myford|fordpass|fordwifi|(?<![a-z])lincoln(?![a-z])|lincolnwifi|mylincoln|toyota|lexus|(?<![a-z])honda|myhonda|acura|nissan|infiniti|hyundai|\bkia\b|subaru|mazda|jeep|dodge|chrysler|\bram\b|bmw|mercedes|\baudi\b|\bvw\b|volkswagen|volvo|porsche|rivian|polestar|uconnect|\bsync\b|carplay|android auto|\bmmi\b|obd|vgate|elm327|dash ?cam|70mai|viofo|blackvue|nextbase|\bcar\b|car ?hotspot|my ?car)")
# Letters-only word boundaries (digits and "_" separate): WyzeCam, IPCAM-1, Cam_2, camera yes; Campbell, Camden, Camry no.
_CAM = r"cam(era|s)?(?![a-z])|camhi"
CAMERA_IOT_NAME = _rx(r"(" + _CAM + r"|ipc|dvr|nvr|wyze|v380|tuya|smartlife|esp[_-]|blink|(?<![a-z])arlo|myarlo|^ring|yi[-_]|xmeye|jxl|(?<![a-z])spy|spycam|hidvr|hikvision|dahua|reolink|eufy|tapo|(?<![a-z])kasa(?![a-z])|govee|(?<![a-z])hue(?![a-z])|huebridge|philips.?hue|switchbot|meross|(^|[^a-z])wiz([^a-z]|$)|\bled\b|bulb|plug|\block\b|roku|chromecast|echo|fire ?tv|sonos|printer|\bhp-|epson|canon|brother|\btv\b|bravia|vizio|(?<![a-z])nest|googlenest|ecobee|doorbell|thermostat|sensor)")
DRONE_NAME = _rx(r"(dji|mavic|tello|skydio|anafi|autel|\bspark-|phantom|fpv|\bmini \d|avata)")
HOTSPOT_NAME = _rx(r"(iphone|android|galaxy|pixel|hotspot|moto|oneplus|redmi|'s phone|^direct-.*android)")
FLEET_ROUTER_SSID = _rx(r"^(IBR\d{3,4}|R\d{3,4}|AER\d{4}|E\d{3,4}|CBA\d{3,4})(LP\w*)?-[0-9a-f]{3}$")

WEARABLE_VENDOR = _rx(r"(garmin|fitbit|polar|suunto|oura|whoop|huami|zepp|amazfit|bose|jabra|gn audio|sennheiser|skullcandy|harman|bang & olufsen|shokz|sonova|oticon|widex|starkey|bragi|nothing technology)")
CAMERA_IOT_VENDOR = _rx(r"(hikvision|dahua|reolink|wyze|\barlo\b|\bring\b|\bblink\b|eufy|anker|axis communications|hanwha|uniview|amcrest|lorex|swann|verkada|ezviz|imou|espressif|tuya|signify|philips lighting|govee|ecobee|sonos|roku|nest labs|amazon technologies|tp-link|shelly|allterco|lifx|\bwiz\b|belkin|chamberlain|\baugust\b|\byale\b|honeywell|resideo|seeed|raspberry pi|azurewave|texas instruments|silicon labs|nordic semiconductor|hp inc|hewlett|seiko epson|canon|brother industries|lexmark|xerox)")
VEHICLE_VENDOR = _rx(r"(tesla|general motors|ford motor|toyota|\bhonda\b|nissan|hyundai|\bkia\b|subaru|mazda|stellantis|bmw|daimler|mercedes|volkswagen|volvo car|porsche|rivian|continental automotive|visteon|denso|alps alpine|panasonic automotive|harman becker|bosch car|aptiv|delphi|lg innotek|lear corp|magna|mobileye|ficosa)")
DRONE_VENDOR = _rx(r"(\bdji\b|skydio|parrot|autel|yuneec)")
PHONE_VENDOR = _rx(r"(apple|samsung|google|motorola mobility|oneplus|xiaomi|huawei|oppo|vivo|realme|sony mobile|nothing technology|hmd global|zte|tcl)")
COMPUTER_VENDOR = _rx(r"(\bintel\b|\bdell\b|lenovo|asustek|\bacer\b|microsoft)")

# ----------------------------------------------------------------------------- signatures (Signatures.kt / Threats.kt)

FLOCK_OUI_HIGH = {
    "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "b8:35:32", "14:5a:fc", "74:4c:a1", "08:3a:88", "9c:2f:9d", "c0:35:32",
    "94:08:53", "e4:aa:ea", "24:b2:b9", "b8:1e:a4", "70:08:94", "58:8e:81", "ec:1b:bd", "3c:71:bf", "58:00:e3", "90:35:ea",
    "5c:93:a2", "64:6e:69", "48:27:ea", "a4:cf:12", "e0:4f:43", "82:6b:f2", "b4:1e:52", "04:0d:84", "f0:82:c0", "1c:34:f1",
    "38:5b:44", "94:34:69", "b4:e3:f9",
}
FLOCK_OUI_MFR = {"f4:6a:dd", "f8:a2:d6", "14:b5:cd", "00:f4:8d", "d0:39:57", "e8:d0:fc", "e0:0a:f6", "00:03:7f"}
FLOCK_EXACT_MACS = {"00:03:7f:50:00:01", "00:03:7f:4f:00:16"}
SOUNDTHINKING_OUI = {"d4:11:d6"}
AXON_OUI = {"00:25:df"}
PINEAPPLE_OUI = {"00:13:37"}
FLOCK_BLE_COMPANY = 0x09C8
RAVEN_NAMED = {0x3100, 0x3200, 0x3300, 0x3400, 0x3500}

FLOCK_SSID_EXACT = _rx(r"^flock-[0-9a-f]{6}$")
# Whole words only: "Flockhart", "Club Penguin" or "Penguin_5G" are home networks, not cameras.
FLOCK_WORD = _rx(r"(^|[^a-z])flock([^a-z]|$)")
PENGUIN_WORD = _rx(r"(^|[^a-z])penguin([^a-z]|$)")
FLOCK_START = _rx(r"^flock([^a-z]|$)")
PENGUIN_START = _rx(r"^penguin([^a-z]|$)")
PENGUIN_SERIAL = _rx(r"^penguin-\d{10}$")
PUBLIC_SAFETY_VENDOR = _rx(r"(axon|taser|motorola solutions|watchguard|digital ally|kustom signals|l3harris|coban|utility associates|getac|soundthinking|shotspotter|flock safety)")
FLEET_ROUTER_VENDOR = _rx(r"(cradlepoint|sierra wireless|inseego|digi international|peplink|pepwave)")

LAW_SSID = _rx(r"(police|sheriff|trooper|state ?patrol|highway ?patrol|\bgsp\b|\bswat\b|\bk-?9\b|\bmarshal\b|\bpd\b|^[a-z]{1,4}pd[\s_-]?\d+\b|^[a-z]{2,4}so[\s_-]?\d+\b|\bmdt\b|\bdps\b)")
FIRE_SSID = _rx(r"(fire ?(dept|department|station|rescue|engine|truck|district|ems)|\bfd\b|^[a-z]{1,4}fd[\s_-]?\d+\b|\bengine[\s_-]?\d|\bladder[\s_-]?\d|\bquint[\s_-]?\d|\bbattalion\b|\brescue[\s_-]?\d|\bsquad[\s_-]?\d)")
EMS_SSID = _rx(r"(\bems\b|\bmedic[\s_-]?\d|ambulance|\bamr\b|\bals[\s_-]?\d|\bbls[\s_-]?\d|paramedic)")
TRAFFIC_SSID = _rx(r"(traffic ?(cam|signal|control|light)|\bgdot\b|iteris|miovision|econolite|verra|redflex|blue ?line ?sol|speed ?cam|red ?light ?cam|school ?zone|\bits[\s_-]?cam|signal ?cab)")

PINEAPPLE_DEFAULT = _rx(r"^pineapple[_ -]?([0-9a-f]{4})$")
PINEAPPLE_WORD = _rx(r"(wi-?fi ?pineapple|pineapple ?(mk ?\d+|nano|tetra|hak5)\b|hak5 ?pineapple)")
MARAUDER_DEFAULT = _rx(r"^marauder(ap|_[0-9a-f]{2,6})?$")
OMG_DEFAULT = _rx(r"^o\.mg([ _-].*)?$")
FLIPPER_NAME = _rx(r"^flipper\s+\S{3,}$")


def oui(mac: str) -> str:
    return mac.lower()[:8]


def is_randomized_mac(mac: str) -> bool:
    try:
        return (int(mac[:2], 16) & 0x02) != 0
    except ValueError:
        return False


@dataclass
class Finding:
    points: int
    text: str


def police_mac_findings(mac: str) -> List[Finding]:
    m = mac.lower()
    o = oui(m)
    f = []
    if m in FLOCK_EXACT_MACS:
        f.append(Finding(70, "Flock camera factory-default radio MAC"))
    if o in FLOCK_OUI_HIGH:
        f.append(Finding(55, f"Flock Safety hardware address ({o})"))
    if o in FLOCK_OUI_MFR and m not in FLOCK_EXACT_MACS:
        f.append(Finding(10, f"Maker also used for Flock hardware ({o}): weak on its own"))
    if o in SOUNDTHINKING_OUI:
        f.append(Finding(45, "SoundThinking / ShotSpotter sensor address"))
    if o in AXON_OUI:
        f.append(Finding(60, "Axon police equipment (body cam / Taser / holster sensor)"))
    return f


def police_ssid_findings(ssid: str) -> List[Finding]:
    if not ssid.strip():
        return []
    s = ssid.lower()
    if FLOCK_SSID_EXACT.match(ssid):
        return [Finding(70, "Flock camera setup network (Flock-XXXXXX)")]
    if PENGUIN_SERIAL.match(ssid):
        return [Finding(55, "Flock Penguin module name (Penguin-<serial>)")]
    if "flock camera net" in s:
        return [Finding(65, 'Flock camera network ("Flock Camera net.")')]
    if "flocksafety" in s or "pigvision" in s or "fs ext battery" in s or FLOCK_WORD.search(s):
        return [Finding(50, "Flock Safety network name")]
    if PENGUIN_WORD.search(s):  # Flock's battery module word; on Wi-Fi a weak hint on its own
        return [Finding(20, '"Penguin" in network name (Flock module word; weak on its own)')]
    if "flck" in s:
        return [Finding(50, "Flock test network name (CVE-2025-59409)")]
    return []


def agency(ssid: str) -> Optional[str]:
    if not ssid.strip():
        return None
    if LAW_SSID.search(ssid):
        return "POLICE"
    if FIRE_SSID.search(ssid):
        return "FIRE"
    if EMS_SSID.search(ssid):
        return "EMS"
    if TRAFFIC_SSID.search(ssid):
        return "TRAFFIC"
    return None


def threat_ssid_findings(ssid: str, bssid: str = "") -> List[Finding]:
    """Attack / audit hardware hints from a network name (Threats.kt). Hints, never proof."""
    s = ssid.strip()
    if not s:
        return []
    low = s.lower()
    tail = bssid.lower().replace(":", "")[-4:]
    pine = PINEAPPLE_DEFAULT.match(s)
    if pine and (pine.group(1).lower() == tail or oui(bssid) in PINEAPPLE_OUI):
        return [Finding(60, "Wi-Fi Pineapple default network name matching its address (Hak5 audit/attack tool)")]
    if PINEAPPLE_WORD.search(s):
        return [Finding(45, "Network name says Wi-Fi Pineapple (Hak5 audit/attack tool), or someone's joke")]
    if pine:
        return [Finding(15, "Looks like a Wi-Fi Pineapple default name, but the digits don't match its address (probably just a name)")]
    if low == "pwned":
        return [Finding(55, '"pwned": default network of the ESP8266 Deauther (Wi-Fi jamming tool), or someone\'s joke name')]
    if "deauther" in low:
        return [Finding(55, "Network name says Deauther (ESP8266 Wi-Fi jamming tool)")]
    if MARAUDER_DEFAULT.match(s):
        return [Finding(50, "ESP32 Marauder default network name (Wi-Fi/BLE attack tool)")]
    if "marauder" in low:
        return [Finding(10, 'Name contains "marauder" (a school mascot or a car more often than the attack tool)')]
    if OMG_DEFAULT.match(s):
        return [Finding(55, "O.MG default network name (malicious-cable implant with Wi-Fi)")]
    if "hak5" in low:
        return [Finding(45, "Network name says Hak5 (audit/attack hardware maker)")]
    if low.startswith("pwnagotchi"):
        return [Finding(40, "Pwnagotchi (handshake-collecting Wi-Fi toy) name")]
    if any(k in low for k in ("evil twin", "eviltwin", "wifiphisher", "fluxion")):
        return [Finding(40, "Network name taken from a Wi-Fi phishing tool")]
    if low in ("free wifi", "free_wifi", "free public wifi"):
        return [Finding(10, 'Generic "Free WiFi" name: the classic bait name (often harmless)')]
    return []


def threat_mac_findings(mac: str) -> List[Finding]:
    o = oui(mac)
    if o in PINEAPPLE_OUI:
        return [Finding(40, f"Address block used by Wi-Fi Pineapples ({o}; the IEEE list names Orient Power for it)")]
    return []


def threat_ble_name_findings(name: Optional[str]) -> List[Finding]:
    n = (name or "").strip()
    if not n:
        return []
    low = n.lower()
    if FLIPPER_NAME.match(n):
        return [Finding(45, "Flipper Zero (multi-tool: can clone, replay and spam signals; often just a hobbyist)")]
    if MARAUDER_DEFAULT.match(n):
        return [Finding(50, "ESP32 Marauder name (Wi-Fi/BLE attack tool)")]
    if low.startswith("o.mg") or low.startswith("omg-"):
        return [Finding(50, "O.MG implant name (malicious cable)")]
    if "hak5" in low:
        return [Finding(40, "Hak5 (audit/attack hardware maker) in the name")]
    if low == "pwnagotchi":
        return [Finding(35, "Pwnagotchi (handshake-collecting Wi-Fi toy)")]
    return []


def threat_label(findings: List[Finding]) -> Optional[str]:
    if not findings:
        return None
    t = max(findings, key=lambda f: f.points).text.lower()
    for key, label in (("pineapple", "Wi-Fi Pineapple / Hak5 tool"), ("hak5", "Wi-Fi Pineapple / Hak5 tool"), ("deauther", "Wi-Fi deauther (jamming tool)"),
                       ("pwned", "Wi-Fi deauther (jamming tool)"), ("marauder", "ESP32 Marauder (attack tool)"), ("o.mg", "O.MG cable implant"),
                       ("flipper", "Flipper Zero"), ("pwnagotchi", "Pwnagotchi"), ("phishing", "Wi-Fi phishing tool name")):
        if key in t:
            return label
    return None


# ----------------------------------------------------------------------------- Wi-Fi security (WifiSecurity.kt)

def security_label(caps: str) -> str:
    c = caps.upper()
    if "WEP" in c:
        return "WEP"
    if "SAE" in c or "WPA3" in c:
        return "WPA3"
    if "OWE" in c:
        return "OWE"
    if "RSN" in c or "WPA2" in c:
        return "WPA2"
    if "WPA" in c:
        return "WPA"
    return "OPEN"


def security_grade(caps: str, wps: bool = False) -> Tuple[str, str]:
    label = security_label(caps)
    c = caps.upper()
    tkip_only = "TKIP" in c and "CCMP" not in c and "GCMP" not in c and "AES" not in c
    g = {"WPA3": "A", "OWE": "B", "WPA2": "C" if tkip_only else "B", "WPA": "C", "WEP": "D"}.get(label, "F")
    if wps and g in "ABC":
        g = chr(ord(g) + 1)
    why = {
        "WPA3": "WPA3: current standard", "OWE": "OWE: encrypted open network (no password, traffic still protected)",
        "WPA2": "WPA2 with TKIP only (old cipher)" if tkip_only else "WPA2", "WPA": "WPA1 (2003, outdated; crackable)",
        "WEP": "WEP (broken since 2001: anyone can read the traffic)",
    }.get(label, "Open: no encryption, anyone nearby can read the traffic")
    if wps:
        why += " · WPS advertised (older routers: PIN attack risk)"
    return g, why


def is_weak(caps: str, wps: bool = False) -> bool:
    return security_grade(caps, wps)[0] >= "D" or security_label(caps) == "WPA"


# ----------------------------------------------------------------------------- owner names (Names.kt)

_DEVICE_WORDS = _rx(
    r"^(iphone|ipad|ipod|mac|macbook|macbook ?(pro|air)|imac|mac ?mini|mac ?studio|apple ?watch|watch|airpods( ?(pro|max))?|"
    r"beats\w*|buds\w*|galaxy\s?\w*|pixel\s?\w*|phone|android|tablet|laptop|notebook|pc|desktop|surface\w*|chromebook|kindle|fire|echo\w*|"
    r"speaker|headphones|earbuds|fitbit\w*|garmin\w*|oura\w*|ring|band|car|tesla|jeep|truck|bike|scooter|hotspot|wifi|wi-fi|network|home|house|"
    r"place|room|apartment|flat|tv|xbox|playstation|ps[345]|switch|nintendo|oneplus\w*|xiaomi\w*|huawei\w*|motorola\w*|moto\w*|"
    r"s\d{1,2}\+?( ultra| fe)?|a\d{1,2}|z ?(fold|flip)\s?\d*|note\s?\d*|nest\w*|sonos\w*|bose\w*)\b.*$")
_NOT_A_PERSON = set("""mcdonald wendy denny macy kohl lowe dillard dunkin domino applebee chili arby carl hardee kroger sam bj fry friday harry ben
jerry levi kohls lowes church guest customer visitor student employee staff member driver rider owner admin everyone nobody someone user default
free public city county school hotel cafe coffee bar pub shop store office clinic library gym club salon starbucks hilton marriott hyatt ihg
walmart target costco panera chipotle subway living livingroom kitchen bedroom bathroom basement garage family game dining master upstairs
downstairs work old new spare test main shared kids kid baby patio porch attic lobby hall garden pool front back backyard sala casa cocina cuarto
salon chambre maison bureau wohnzimmer kuche haus la el los las le les der die das the a an my our your mobile wireless smart home house apartment
flat room car cars truck boat""".split()) | {"trader joe", "raising cane", "jimmy john", "papa john", "tgi friday", "ben & jerry"}
_BRANDS = set("""apple samsung google android xiaomi redmi huawei honor oneplus oppo vivo realme motorola moto nokia sony lg htc asus acer dell hp
lenovo msi razer microsoft amazon tesla ford toyota honda bmw audi vw kia hyundai nissan bose jbl sonos beats garmin fitbit polar wahoo tile chipolo
direct desktop laptop iphone ipad galaxy pixel nest ring wyze arlo eufy roku fire echo tp tplink netgear linksys att xfinity spectrum verizon tmobile
orbi eero ubnt hidden unknown mobile wireless network guest home office free public my""".split())
_ARTICLES = {"the", "la", "el", "le", "les", "los", "las", "der", "die", "das", "de", "a", "an"}
_POSSESSIVE = re.compile(r"^([^\W\d_][^\W\d_.\- ]{0,30}?(?:[.\- ][^\W\d_]+){0,2}?)\s*(['’`´]s|['’`´])\s+(.+)$", re.I | re.U)
_OF_FORM = re.compile(r"^(.{2,40}?)\s+(de|di|von|van|do|da|du|des)\s+([^\W\d_][^\W\d_'\- ]{1,30})$", re.I | re.U)
_HYPHEN = re.compile(r"^([^\W\d_]{2,20})[-_]([^\W\d_].*)$", re.U)
_DROPPED = re.compile(r"^([^\W\d_]{2,20})s\s+(.+)$", re.U)
_HOST_POSS = re.compile(r"^([^\W\d_]{2,20})s-(.+)$", re.I | re.U)


def _person(who: str) -> bool:
    w = who.strip().rstrip(".").lower()
    if not w or len(w) > 30 or w in _NOT_A_PERSON or w in _BRANDS:
        return False
    words = [x for x in w.split(" ") if x]
    if not words or len(words) > 3 or words[0] in _ARTICLES:
        return False
    if any(x in _BRANDS or x in _NOT_A_PERSON or len(x) < 2 for x in words):
        return False
    return all(all(c.isalpha() or c in ".-'" for c in x) and sum(c.isalpha() for c in x) >= 2 for x in words)


def _cap(who: str) -> str:
    out = []
    for w in who.strip().rstrip(".").split(" "):
        if not w:
            continue
        letters = [c for c in w if c.isalpha()]
        out.append(w.lower().capitalize() if letters and (all(c.isupper() for c in letters) or all(c.islower() for c in letters)) else w)
    return " ".join(out)


def owner(name: Optional[str]) -> Optional[str]:
    """Person named by a device's own broadcast name ("Sarah's AirPods" -> Sarah). Nothing is looked up."""
    n = re.sub(r"\s+", " ", (name or "").strip())
    if len(n) < 4 or len(n) > 64:
        return None
    m = _POSSESSIVE.match(n)
    if m and _DEVICE_WORDS.match(m.group(3).strip()) and _person(m.group(1)):
        return _cap(m.group(1))
    m = _OF_FORM.match(n)
    if m and _DEVICE_WORDS.match(m.group(1).strip()) and _person(m.group(3)):
        return _cap(m.group(3))
    m = _HYPHEN.match(n)
    if m and _DEVICE_WORDS.match(m.group(2).replace("-", " ").replace("_", " ")) and _person(m.group(1)):
        return _cap(m.group(1))
    m = _DROPPED.match(n)
    if m and _DEVICE_WORDS.match(m.group(2).strip()) and _person(m.group(1) + "s") and _person(m.group(1)):
        return _cap(m.group(1) + "s")
    return None


def owner_from_hostname(host: Optional[str]) -> Optional[str]:
    h = (host or "").strip().rstrip(".")
    if h.lower().endswith(".local"):
        h = h[:-6]
    if len(h) < 4:
        return None
    m = _HOST_POSS.match(h)
    if m and _DEVICE_WORDS.match(m.group(2).replace("-", " ")) and _person(m.group(1) + "s") and _person(m.group(1)):
        return _cap(m.group(1) + "s")
    return owner(h) or owner(h.replace("-", " ").replace("_", " "))


# ----------------------------------------------------------------------------- vendor DB

class VendorDb:
    """OUI -> maker and BLE company ID -> name, from the app's TSVs (aabbcc<TAB>Vendor / 004c<TAB>Apple)."""

    SUFFIX = re.compile(r"[,.]?\s*(Inc|Incorporated|Ltd|Limited|LLC|Co|Corp|Corporation|GmbH|AG|S\.A|SA|B\.V|BV|AB|Oy|Pty|PLC|Holdings?|Company|"
                        r"Technologies|Technology|Electronics|International|Communications)\.?,?$", re.I)

    def __init__(self) -> None:
        self.oui: Dict[str, str] = {}
        self.companies: Dict[int, str] = {}

    def load_oui(self, lines) -> None:
        for line in lines:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 2 and len(p[0]) == 6:
                self.oui[p[0].lower()] = p[1]

    def load_companies(self, lines) -> None:
        for line in lines:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 2:
                try:
                    self.companies[int(p[0], 16)] = p[1]
                except ValueError:
                    pass

    @classmethod
    def short(cls, v: str) -> str:
        s = " ".join(v.split()).strip()
        while True:
            n = cls.SUFFIX.sub("", s).strip().rstrip(",.")
            if n == s or not n:
                return s
            s = n

    def vendor(self, mac: str) -> Optional[str]:
        if is_randomized_mac(mac):
            return None
        v = self.oui.get(mac.lower().replace(":", "")[:6])
        return self.short(v) if v else None

    def company(self, cid: int) -> Optional[str]:
        v = self.companies.get(cid)
        return self.short(v) if v else None


# ----------------------------------------------------------------------------- device model

@dataclass
class Device:
    id: str
    kind: str  # WIFI | BLE
    first_seen: float
    last_seen: float
    name: str = ""
    rssi: int = -100
    readings: int = 0
    rssi_sum: float = 0.0
    category: str = "NETWORK"
    label: str = ""
    vendor: str = ""
    ble_type: str = ""
    owner: str = ""
    facts: Dict[str, str] = field(default_factory=dict)
    findings: List[Finding] = field(default_factory=list)
    attack_tool: bool = False
    security: str = ""
    wps: bool = False
    frequency: int = 0
    present_since: float = 0.0
    score: int = 0
    flags: List[str] = field(default_factory=list)
    sec_tags: List[str] = field(default_factory=list)
    alerted: bool = False

    @property
    def key(self) -> str:
        return f"{self.kind}:{self.id}"

    @property
    def avg(self) -> int:
        return int(round(self.rssi_sum / self.readings)) if self.readings else self.rssi

    @property
    def level(self) -> str:
        return "HIGH" if self.score >= 50 else "MEDIUM" if self.score >= 25 else "LOW"

    def display_name(self) -> str:
        if self.label and self.name and self.name.lower() not in self.label.lower():
            return f"{self.name} · {self.label}"
        return self.label or self.name or ("<hidden SSID>" if self.kind == "WIFI" else "Unknown BLE")

    def add_reading(self, rssi: int, now: float) -> None:
        if now - self.last_seen > 120:
            self.present_since = now
        self.rssi = rssi
        self.last_seen = now
        self.readings += 1
        self.rssi_sum += rssi


TRACKERS = {"Apple Find My tag (away from owner)", "Google Find Hub tag (away from owner)", "Google Find Hub tag", "Tile tracker",
            "Samsung SmartTag", "Chipolo tracker", "Location tracker (cross-platform alert)"}


class Classification:
    def __init__(self) -> None:
        self.category = "NETWORK"
        self.label = ""
        self.prio = 0
        self.vendor = ""
        self.ble_type = ""
        self.facts: Dict[str, str] = {}
        self.findings: List[Finding] = []
        self.attack_tool = False

    def set(self, cat: str, label: str, prio: int) -> None:
        if prio > self.prio:
            self.category, self.label, self.prio = cat, label, prio


def _with_vendor(vendor: str, what: str) -> str:
    return what if not vendor or vendor.lower() in what.lower() else f"{vendor} {what}"


def _police_label(f: List[Finding]) -> str:
    top = max(f, key=lambda x: x.points).text.lower() if f else ""
    if "axon" in top:
        return "Axon police equipment"
    if "raven" in top:
        return "Raven gunshot detector"
    if "soundthinking" in top or "shotspotter" in top:
        return "SoundThinking / ShotSpotter sensor"
    if "flock" in top or "penguin" in top:
        return "Flock Safety device"
    if "law-enforcement" in top:
        return "Police vehicle" if "vehicle router" in top else "Law-enforcement network"
    if "fire department" in top:
        return "Fire truck" if "vehicle router" in top else "Fire department network"
    if "ambulance" in top:
        return "Ambulance / EMS vehicle" if "vehicle router" in top else "EMS network"
    if "traffic" in top:
        return "Traffic / enforcement camera"
    return "Public-safety equipment"


def classify_wifi(bssid: str, ssid: str, caps: str, db: VendorDb, wps: bool = False, freq: int = 0) -> Classification:
    c = Classification()
    randomized = is_randomized_mac(bssid)
    v = db.vendor(bssid) if not randomized else None
    c.vendor = v or ""
    c.facts["BSSID"] = bssid + (" (randomized)" if randomized else "")
    if v:
        c.facts["MAC vendor"] = v
    if freq:
        band = "6 GHz" if freq >= 5925 else "5 GHz" if freq >= 5000 else "2.4 GHz"
        c.facts["Band"] = f"{band} · {freq} MHz"
    c.facts["Security"] = f"{security_label(caps)}  {caps}"
    g, why = security_grade(caps, wps)
    c.facts["Security grade"] = f"{g} · {why}"

    police = police_mac_findings(bssid) + police_ssid_findings(ssid)
    fleet = bool(FLEET_ROUTER_SSID.match(ssid)) or bool(v and FLEET_ROUTER_VENDOR.search(v))
    if v and not any(x.points >= 50 for x in police) and PUBLIC_SAFETY_VENDOR.search(v):
        police.append(Finding(30, f"Public-safety equipment maker: {v}"))
    ag = agency(ssid)
    if ag == "POLICE":
        police.append(Finding(55 if fleet else 40, "Law-enforcement network name" + (" on a vehicle router (patrol car / unmarked unit)" if fleet else "")))
    elif ag == "FIRE":
        police.append(Finding(30, "Fire department network name" + (" on a vehicle router (fire truck)" if fleet else "")))
    elif ag == "EMS":
        police.append(Finding(30, "Ambulance / EMS network name" + (" on a vehicle router" if fleet else "")))
    elif ag == "TRAFFIC":
        police.append(Finding(35, "Traffic / enforcement camera network name"))
    if fleet and ag is None and not any(x.points >= 50 for x in police):
        c.set("VEHICLE", "Fleet vehicle router (unknown fleet)", 60)
    c.findings += police
    if sum(x.points for x in police) >= 25:
        c.set("POLICE", _police_label(police), 100)

    attack = threat_ssid_findings(ssid, bssid) + threat_mac_findings(bssid)
    if attack:
        c.findings += attack
        c.attack_tool = any(x.points >= 40 for x in attack)
        if c.attack_tool:
            lab = threat_label(attack)
            if lab:
                c.set("NETWORK", lab, 95)

    if ssid:
        if DRONE_NAME.search(ssid):
            c.set("DRONE", "Drone / controller network", 55)
        elif ssid.upper().startswith("DIRECT-") and re.search("android", ssid, re.I):
            c.set("PHONE", "Android phone (Wi-Fi Direct)", 56)
        elif HOTSPOT_NAME.search(ssid):
            low = ssid.lower()
            c.set("PHONE", "iPhone hotspot" if "iphone" in low else "Samsung Galaxy hotspot" if "galaxy" in low else "Google Pixel hotspot" if "pixel" in low else "Phone hotspot", 55)
        elif VEHICLE_NAME.search(ssid):
            c.set("VEHICLE", "Car Wi-Fi / vehicle hotspot", 56)
        elif ssid.upper().startswith("DIRECT-"):
            c.set("CAMERA_IOT", "Wi-Fi Direct device (printer / TV / camera)", 52)
        elif CAMERA_IOT_NAME.search(ssid):
            c.set("CAMERA_IOT", _with_vendor(c.vendor, "camera / IoT setup network"), 50)
    if v:
        if DRONE_VENDOR.search(v):
            c.set("DRONE", f"{v} drone network", 45)
        elif VEHICLE_VENDOR.search(v):
            c.set("VEHICLE", f"{v} vehicle Wi-Fi", 45)
        elif CAMERA_IOT_VENDOR.search(v):
            c.set("CAMERA_IOT", f"{v} device", 40)
    if c.prio == 0:
        c.set("NETWORK", f"{c.vendor} access point" if c.vendor else "Wi-Fi network", 1)
    return c


APPLE_AUDIO = {0x0220: "AirPods", 0x0F20: "AirPods (2nd gen)", 0x1320: "AirPods (3rd gen)", 0x0E20: "AirPods Pro", 0x1420: "AirPods Pro 2",
               0x0A20: "AirPods Max", 0x1020: "Beats Flex", 0x1120: "Beats Studio Buds", 0x1220: "Beats Fit Pro"}


def classify_ble(addr: str, name: Optional[str], mfr: Dict[int, bytes], uuid16: set, svc_data: Dict[int, bytes], db: VendorDb,
                 random_addr: Optional[bool] = None) -> Classification:
    c = Classification()
    companies = [db.company(k) for k in mfr if db.company(k)]
    random = random_addr if random_addr is not None else is_randomized_mac(addr)
    ov = db.vendor(addr) if not random else None
    c.vendor = companies[0] if companies else (ov or "")
    c.facts["Address"] = addr + (" (random / private)" if random else " (public)")
    if ov:
        c.facts["MAC vendor"] = ov
    if uuid16:
        c.facts["Services"] = ", ".join(f"0x{u:04X}" for u in sorted(uuid16))

    police = police_mac_findings(addr)
    n = (name or "").strip()
    low = n.lower()
    if low == "fs ext battery":
        police.append(Finding(60, 'Flock "FS Ext Battery" pack'))
    elif PENGUIN_SERIAL.match(n) or PENGUIN_START.search(low):
        police.append(Finding(55, "Flock Penguin battery/camera module"))
    elif FLOCK_START.search(low) or low.startswith("flocksafety") or low.startswith("pigvision"):
        police.append(Finding(55, "Flock Safety BLE device"))
    elif low.startswith("axon"):
        police.append(Finding(55, "Axon device name"))
    if uuid16 & RAVEN_NAMED:
        police.append(Finding(60, "Raven gunshot detector services"))
    if FLOCK_BLE_COMPANY in mfr:
        police.append(Finding(55, "Flock Safety BLE (company ID 0x09C8)"))
    c.findings += police
    if sum(x.points for x in police) >= 25:
        c.set("POLICE", _police_label(police), 100)

    attack = threat_ble_name_findings(name) + ([] if random else threat_mac_findings(addr))
    if attack:
        c.findings += attack
        c.attack_tool = True
        lab = threat_label(attack)
        if lab:
            c.set("NETWORK", lab, 95)

    apple = mfr.get(0x004C)
    if apple is not None:
        c.vendor = "Apple"
        i = 0
        while i + 1 < len(apple):
            t, ln = apple[i], apple[i + 1]
            p = i + 2
            if t == 0x12:
                if ln == 0x19:
                    c.ble_type = "Apple Find My tag (away from owner)"
                    c.findings.append(Finding(40, c.ble_type))
                    c.set("TRACKER", c.ble_type, 80)
                else:
                    c.set("TRACKER", "Find My device (owner nearby)", 65)
            elif t == 0x07 and p + 2 < len(apple):
                model = (apple[p + 1] << 8) | apple[p + 2]
                c.set("WEARABLE", f"Apple {APPLE_AUDIO[model]}" if model in APPLE_AUDIO else f"AirPods / Beats (model 0x{model:04X})", 72)
            elif t in (0x10, 0x0C, 0x0E, 0x0D, 0x05, 0x0F, 0x0A):
                c.set("PHONE", "Apple iPhone / iPad / Mac", 70)
            elif t == 0x0B:
                c.set("WEARABLE", "Apple Watch", 71)
            elif t in (0x09, 0x06):
                c.set("CAMERA_IOT", "Apple TV / HomePod / HomeKit", 70)
            if ln == 0:
                break
            i = p + ln
        c.set("PHONE", "Apple device", 35)

    def tracker(t: str, vendor: str) -> None:
        c.ble_type = t
        if vendor:
            c.vendor = vendor
        c.findings.append(Finding(35, t))
        c.set("TRACKER", t, 80)

    if 0xFEED in uuid16 or 0xFEEC in uuid16:
        tracker("Tile tracker", "Tile")
    elif 0xFD5A in uuid16:
        tracker("Samsung SmartTag", "Samsung")
    elif 0xFE33 in uuid16:
        tracker("Chipolo tracker", "Chipolo")
    elif 0xFCB2 in uuid16:
        tracker("Location tracker (cross-platform alert)", "")
    eddy = svc_data.get(0xFEAA)
    if eddy:
        if eddy[0] == 0x41:
            tracker("Google Find Hub tag (away from owner)", "Google")
        elif eddy[0] == 0x40:
            tracker("Google Find Hub tag", "Google")
        else:
            c.set("NETWORK", "Eddystone beacon", 60)
    if 0xFFFA in svc_data:
        c.findings.append(Finding(35, "Drone broadcasting FAA Remote ID"))
        c.set("DRONE", "Drone (Remote ID)", 90)
    if 0xFE2C in uuid16 or 0xFE2C in svc_data:
        c.set("WEARABLE", _with_vendor(c.vendor, "Fast Pair accessory (earbuds/headphones)"), 66)
    if 0x180D in uuid16:
        c.set("WEARABLE", _with_vendor(c.vendor, "heart-rate sensor"), 62)
    ms = mfr.get(0x0006)
    if ms is not None:
        c.set("PHONE", "Windows PC (Connected Devices)", 68)

    if n:
        if DRONE_NAME.search(n):
            c.set("DRONE", _with_vendor(c.vendor, "drone / controller"), 55)
        elif VEHICLE_NAME.search(n):
            c.set("VEHICLE", "Vehicle / car accessory", 50)
        elif WEARABLE_NAME.search(n):
            c.set("WEARABLE", _with_vendor(c.vendor, "wearable / audio"), 50)
        elif PHONE_NAME.search(n):
            c.set("PHONE", _with_vendor(c.vendor, "phone / tablet"), 50)
        elif COMPUTER_NAME.search(n):
            c.set("PHONE", _with_vendor(c.vendor, "computer"), 50)
        elif CAMERA_IOT_NAME.search(n):
            c.set("CAMERA_IOT", _with_vendor(c.vendor, "smart-home / IoT device"), 48)
    for v in companies + ([ov] if ov else []):
        if DRONE_VENDOR.search(v):
            c.set("DRONE", f"{v} drone / controller", 42)
        elif VEHICLE_VENDOR.search(v):
            c.set("VEHICLE", f"{v} vehicle system", 41)
        elif WEARABLE_VENDOR.search(v):
            c.set("WEARABLE", f"{v} wearable / audio", 40)
        elif CAMERA_IOT_VENDOR.search(v):
            c.set("CAMERA_IOT", f"{v} IoT device", 40)
        elif PHONE_VENDOR.search(v):
            c.set("PHONE", f"{v} device (likely phone)", 30)
        elif COMPUTER_VENDOR.search(v):
            c.set("PHONE", f"{v} computer", 30)
    if c.prio == 0:
        c.set("NETWORK", f"{c.vendor} BLE device" if c.vendor else "Unknown BLE device", 1)
    return c


def apply(d: Device, c: Classification) -> None:
    d.category, d.label, d.vendor = c.category, c.label, c.vendor
    d.facts, d.findings, d.attack_tool = dict(c.facts), list(c.findings), c.attack_tool
    if c.ble_type:
        d.ble_type = c.ble_type
    d.owner = owner(d.name) or d.owner


# ----------------------------------------------------------------------------- scoring (Heuristics.kt, trimmed: no GPS on a laptop)

PERSIST_S = 10 * 60


def score(d: Device, active: List[Device], now: float) -> None:
    f: List[str] = []
    sec: List[str] = []
    s = 0
    if d.attack_tool:
        sec.append("attack-tool")
    for x in d.findings:
        s += x.points
        f.append(x.text)
    present = now - d.present_since if d.present_since else 0
    mins = int(present // 60)
    if d.kind == "WIFI":
        lab = security_label(d.security)
        if lab == "WEP":
            s += 20; f.append("WEP encryption (broken)")
        elif lab == "OPEN":
            s += 15; f.append("Open network (no encryption)")
        elif lab == "WPA":
            s += 10; f.append("WPA1 only (outdated, crackable)")
        if d.wps:
            s += 5; f.append("WPS advertised (older routers: PIN attack risk)")
        if is_weak(d.security, d.wps):
            sec.append("weak-wifi")
        if not d.name:
            s += 10; f.append("Hidden SSID")
        else:
            open_ = lab == "OPEN"
            twins = [t for t in active if t.kind == "WIFI" and t.key != d.key and t.name == d.name]
            if any((security_label(t.security) == "OPEN") != open_ for t in twins):
                s += 25 if open_ else 5
                f.append("Same name as a secured network nearby but open (a venue's guest portal, or an evil twin)" if open_ else "An open copy of this network's name is nearby (see that one)")
        if is_randomized_mac(d.id):
            s += 10; f.append("Randomized MAC (phone hotspot, guest or mesh network)")
            if present > PERSIST_S and d.category == "PHONE":
                s += 15; f.append(f"Mobile hotspot nearby for {mins} min")
        if d.avg > -35:
            s += 10; f.append("Very close (about 1–2 m)")
    else:
        if present > PERSIST_S:
            if d.ble_type in TRACKERS:
                s += 30; f.append(f"Tracker has stayed near you for {mins} min")
            elif d.category == "POLICE":
                s += 10; f.append(f"Present for {mins} min")
            elif d.category != "NETWORK":
                s += 10; f.append(f"Nearby for {mins} min")
        if d.avg > -45:
            s += 10; f.append("Very close (about 1 m)")
    d.score = max(0, min(100, s))
    d.flags = f
    d.sec_tags = sorted(set(sec))


def wants_alert(d: Device) -> bool:
    return (d.level == "HIGH" or (d.ble_type in TRACKERS and d.level != "LOW") or (d.category == "POLICE" and d.score >= 45)
            or (d.category == "DRONE" and d.score >= 25) or (d.level != "LOW" and "attack-tool" in d.sec_tags))


# ----------------------------------------------------------------------------- store

class DeviceStore:
    """All observations plus the timeline, like the app's DeviceStore (single-threaded here)."""

    def __init__(self) -> None:
        self.devices: Dict[str, Device] = {}
        self.timeline: List[Tuple[float, str, str]] = []  # newest first
        self.session_start = time.time()

    def upsert(self, id_: str, kind: str, now: float) -> Device:
        key = f"{kind}:{id_}"
        d = self.devices.get(key)
        if d is None:
            d = Device(id_, kind, now, now, present_since=now)
            self.devices[key] = d
        return d

    def is_active(self, d: Device, now: float) -> bool:
        return now - d.last_seen < (75 if d.kind == "WIFI" else 30)

    def active(self, now: float) -> List[Device]:
        return [d for d in self.devices.values() if self.is_active(d, now)]

    def log(self, tag: str, text: str, now: float) -> None:
        self.timeline.insert(0, (now, tag, text))
        del self.timeline[500:]

    def rescore(self, now: float) -> List[Device]:
        act = self.active(now)
        alerts = []
        for d in act:
            score(d, act, now)
            if not d.alerted and wants_alert(d):
                d.alerted = True
                alerts.append(d)
                self.log(d.level, f"{d.category} {d.display_name()} [{d.id}] {'; '.join(d.flags)}", now)
        if len(self.devices) > 3000:
            for k in [k for k, v in self.devices.items() if now - v.last_seen > 15 * 60]:
                del self.devices[k]
        return alerts


# ----------------------------------------------------------------------------- distance (Distance.kt, simplified log-distance model)

def estimate_distance(d: Device) -> Tuple[float, float, float, str]:
    """(meters, low, high, method) from the average RSSI. Honest: a wide range, no GPS placement on a laptop."""
    ref = -45.0 if d.kind == "WIFI" else -59.0
    n = 2.6
    m = 10 ** ((ref - d.avg) / (10 * n))
    m = max(0.3, min(m, 500.0))
    return m, m / 2.2, m * 2.2, f"signal strength ({d.avg} dBm, indoor model)"
