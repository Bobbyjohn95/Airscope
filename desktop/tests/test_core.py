"""Unit tests for the desktop beta (stdlib unittest; run with `python -m unittest discover -s desktop/tests`)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from airscope_desktop import core, scanners  # noqa: E402

NETSH = """
Interface name : Wi-Fi
There are 3 networks currently visible.

SSID 1 : HomeNet
    Network type            : Infrastructure
    Authentication          : WPA2-Personal
    Encryption              : CCMP
    BSSID 1                 : a4:11:22:33:44:55
         Signal             : 80%
         Radio type         : 802.11ac
         Channel            : 36
    BSSID 2                 : a4:11:22:33:44:56
         Signal             : 40%
         Channel            : 6

SSID 2 : Pineapple_3F2A
    Network type            : Infrastructure
    Authentication          : Open
    Encryption              : None
    BSSID 1                 : 00:13:37:aa:3f:2a
         Signal             : 60%
         Channel            : 1

SSID 3 :
    Network type            : Infrastructure
    Authentication          : WPA3-Personal
    Encryption              : CCMP
    BSSID 1                 : 10:20:30:40:50:60
         Signal             : 20%
         Channel            : 149
"""

NMCLI = """HomeNet:A4\\:11\\:22\\:33\\:44\\:55:36:5180 MHz:80:WPA2
Guest Cafe:B4\\:11\\:22\\:33\\:44\\:55:6:2437 MHz:55:
Old:C4\\:11\\:22\\:33\\:44\\:55:11:2462 MHz:30:WEP
"""

IW = """BSS a4:11:22:33:44:55(on wlan0)
\tfreq: 5180
\tsignal: -52.00 dBm
\tSSID: HomeNet
\tRSN:\t * Version: 1
\t\t * Authentication suites: PSK
BSS c4:11:22:33:44:55(on wlan0)
\tfreq: 2462
\tsignal: -80.00 dBm
\tcapability: ESS Privacy (0x0411)
\tSSID: Old
\tWPS:\t * Version: 1.0
"""


class ParserTests(unittest.TestCase):
    def test_netsh(self):
        nets = scanners.parse_netsh(NETSH)
        self.assertEqual([n.bssid for n in nets], ["a4:11:22:33:44:55", "a4:11:22:33:44:56", "00:13:37:aa:3f:2a", "10:20:30:40:50:60"])
        self.assertEqual(nets[0].ssid, "HomeNet"); self.assertEqual(nets[0].rssi, -60); self.assertEqual(nets[0].freq, 5180)
        self.assertIn("[WPA2-PSK-CCMP]", nets[0].caps)
        self.assertEqual(nets[2].caps, "[ESS]"); self.assertEqual(nets[2].freq, 2412)
        self.assertEqual(nets[3].ssid, ""); self.assertIn("SAE", nets[3].caps)

    def test_nmcli(self):
        nets = scanners.parse_nmcli(NMCLI)
        self.assertEqual(len(nets), 3)
        self.assertEqual(nets[0].bssid, "a4:11:22:33:44:55"); self.assertEqual(nets[0].freq, 5180); self.assertIn("WPA2", nets[0].caps)
        self.assertEqual(nets[1].ssid, "Guest Cafe"); self.assertEqual(nets[1].caps, "[ESS]")
        self.assertIn("[WEP]", nets[2].caps)

    def test_iw(self):
        nets = scanners.parse_iw(IW)
        self.assertEqual(len(nets), 2)
        self.assertEqual(nets[0].ssid, "HomeNet"); self.assertEqual(nets[0].rssi, -52); self.assertIn("WPA2", nets[0].caps)
        self.assertIn("[WEP]", nets[1].caps); self.assertTrue(nets[1].wps)


class CoreTests(unittest.TestCase):
    def setUp(self):
        self.db = core.VendorDb()
        self.db.load_oui(["a41122\tSome Router Co., Ltd.", "003044\tCradlepoint, Inc", "0025df\tTaser International"])
        self.db.load_companies(["004c\tApple, Inc.", "0075\tSamsung Electronics Co. Ltd."])

    def test_vendor(self):
        self.assertEqual(self.db.vendor("A4:11:22:00:00:01"), "Some Router")
        self.assertIsNone(self.db.vendor("a6:11:22:00:00:01"))  # randomized
        self.assertEqual(self.db.company(0x004C), "Apple")

    def test_wifi_classification(self):
        c = core.classify_wifi("00:13:37:aa:3f:2a", "Pineapple_3F2A", "[ESS]", self.db)
        self.assertTrue(c.attack_tool); self.assertEqual(c.label, "Wi-Fi Pineapple / Hak5 tool")
        self.assertFalse(core.classify_wifi("a4:11:22:33:44:55", "Pineapple Cafe", "[WPA2-PSK-CCMP][ESS]", self.db).attack_tool)
        self.assertFalse(core.classify_wifi("a4:11:22:33:44:55", "Marauders", "[WPA2-PSK-CCMP][ESS]", self.db).attack_tool)
        pd = core.classify_wifi("00:30:44:00:00:01", "IBR900-1a2", "[WPA2-PSK-CCMP][ESS]", self.db)
        self.assertEqual(pd.category, "VEHICLE")
        pol = core.classify_wifi("00:30:44:00:00:02", "CCPD 1234", "[WPA2-PSK-CCMP][ESS]", self.db)
        self.assertEqual(pol.category, "POLICE"); self.assertEqual(pol.label, "Police vehicle")
        self.assertEqual(core.classify_wifi("a6:00:00:00:00:01", "Mike's iPhone", "[WPA2-PSK-CCMP][ESS]", self.db).label, "iPhone hotspot")
        self.assertEqual(core.security_grade("[RSN-SAE-CCMP][ESS]")[0], "A")
        self.assertEqual(core.security_grade("[WPA2-PSK-CCMP][ESS]", wps=True)[0], "C")
        self.assertTrue(core.is_weak("[WEP][ESS]")); self.assertFalse(core.is_weak("[WPA2-PSK-CCMP][ESS]"))

    def test_ble_classification(self):
        tag = core.classify_ble("f1:22:33:44:55:66", None, {0x004C: bytes([0x12, 0x19]) + bytes(25)}, set(), {}, self.db, True)
        self.assertEqual(tag.category, "TRACKER"); self.assertIn("Find My", tag.ble_type)
        pods = core.classify_ble("4a:11:22:33:44:55", None, {0x004C: bytes([0x07, 0x19, 0x01, 0x14, 0x20, 0x55])}, set(), {}, self.db, True)
        self.assertEqual(pods.label, "Apple AirPods Pro 2")
        axon = core.classify_ble("00:25:df:12:34:56", "AXON_CAMERA", {}, set(), {}, self.db, False)
        self.assertEqual(axon.category, "POLICE"); self.assertEqual(axon.label, "Axon police equipment")
        flip = core.classify_ble("80:e1:26:00:00:01", "Flipper Kx9ab3", {}, set(), {}, self.db, False)
        self.assertTrue(flip.attack_tool); self.assertEqual(flip.label, "Flipper Zero")
        tile = core.classify_ble("c1:00:00:00:00:01", None, {}, {0xFEED}, {}, self.db, True)
        self.assertEqual(tile.ble_type, "Tile tracker")

    def test_owner(self):
        self.assertEqual(core.owner("Sarah's AirPods"), "Sarah")
        self.assertEqual(core.owner("iPhone de Juan"), "Juan")
        self.assertEqual(core.owner("Anna-Pixel 8"), "Anna")
        self.assertEqual(core.owner("James iPhone"), "James")
        for bad in ("McDonald's Free WiFi", "Living-Room-TV", "Pineapple House", "Galaxy Buds2", "Kids' iPad", "The Smith's House", "Sonos Speaker"):
            self.assertIsNone(core.owner(bad), bad)
        self.assertEqual(core.owner_from_hostname("Johns-MacBook-Pro.local"), "Johns")
        self.assertIsNone(core.owner_from_hostname("DESKTOP-ABC123"))

    def test_store_and_scoring(self):
        st = core.DeviceStore()
        t0 = 1_700_000_000.0
        d = st.upsert("00:13:37:aa:3f:2a", "WIFI", t0)
        d.name, d.security = "Pineapple_3F2A", "[ESS]"
        core.apply(d, core.classify_wifi(d.id, d.name, d.security, self.db))
        d.add_reading(-60, t0)
        alerts = st.rescore(t0 + 1)
        self.assertEqual(alerts, [d]); self.assertEqual(d.level, "HIGH"); self.assertIn("attack-tool", d.sec_tags); self.assertIn("weak-wifi", d.sec_tags)
        self.assertEqual(st.rescore(t0 + 2), [])  # alerts once
        m, lo, hi, _ = core.estimate_distance(d)
        self.assertTrue(lo < m < hi)
        self.assertTrue(st.is_active(d, t0 + 60)); self.assertFalse(st.is_active(d, t0 + 100))


if __name__ == "__main__":
    unittest.main()
