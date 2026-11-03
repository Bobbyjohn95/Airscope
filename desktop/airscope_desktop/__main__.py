"""
AirScope desktop (beta): passive Wi-Fi + BLE observer for Windows and Linux, with the same web HUD as the phone.

  python -m airscope_desktop                 scan with this computer's radios, open http://127.0.0.1:8787/
  python -m airscope_desktop --mirror URL    show the phone's live HUD instead (URL = the phone's HUD address)
  python -m airscope_desktop --no-ble        Wi-Fi only (or when `bleak` isn't installed)

Only listens. Reads the Wi-Fi list the OS already keeps and BLE advertisements; never connects or transmits.
Honest limits: a laptop has no GPS, so there is no map placement and distances are rough ranges from signal
strength; Wi-Fi updates only as often as the OS rescans (Windows: ~every few seconds while not connected,
much slower while connected; Linux NetworkManager: every ~10-30 s).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import webbrowser

from . import core, scanners
from .server import HudState, serve


def _db_dir() -> str:
    here = os.path.dirname(os.path.abspath(__file__))
    for p in (os.path.join(getattr(sys, "_MEIPASS", ""), "db") if getattr(sys, "_MEIPASS", "") else "",
              os.path.join(here, "db"), os.path.join(here, "..", "..", "app", "src", "main", "assets", "db")):
        if p and os.path.isdir(p):
            return p
    return ""


def load_db() -> core.VendorDb:
    db = core.VendorDb()
    d = _db_dir()
    if d:
        for name, fn in (("oui.tsv", db.load_oui), ("ble_companies.tsv", db.load_companies)):
            p = os.path.join(d, name)
            if os.path.isfile(p):
                with open(p, encoding="utf-8", errors="replace") as f:
                    fn(f)
    return db


def _json(s) -> str:
    return json.dumps(s, ensure_ascii=False)


def device_json(d: core.Device, now: float) -> str:
    m, lo, hi, src = core.estimate_distance(d)
    facts = list(d.facts.items())
    if d.owner:
        facts.append(("Owner", f"{d.owner} (that's what the device's own name says: nothing was looked up)"))
    if d.readings > 1:
        facts.append(("Signal stats", f"avg {d.avg} dBm · {d.readings} readings"))
    detail = " · ".join(x for x in ([d.vendor, core.security_label(d.security) + " " + core.security_grade(d.security, d.wps)[0]] if d.kind == "WIFI" else [d.vendor, d.category]) if x)
    o = {
        "key": d.key, "id": d.id, "kind": d.kind, "cat": d.category, "name": d.display_name(), "vendor": d.vendor, "avg": d.avg,
        "dist": round(m, 1), "lo": round(lo, 1), "hi": round(hi, 1), "src": src, "score": d.score, "level": d.level, "detail": detail,
        "angle": float((hash(d.key) & 0xFFFFFFFF) % 360), "lat": None, "lon": None, "err": 0, "spots": 0, "bearing": None,
        "flags": d.flags, "facts": [[k, v] for k, v in facts], "ageSec": int(now - d.first_seen),
    }
    if d.sec_tags:
        o["sec"] = d.sec_tags
    return _json(o)


def state_json(store: core.DeviceStore, now: float, warning: str, scanning: bool) -> str:
    views = sorted(store.active(now), key=lambda d: (-d.score, core.estimate_distance(d)[0]))
    return ('{"time":%d,"scanning":%s,"warning":%s,"env":"Home / indoor","alpr":"","mapStatus":"no map on a laptop (no GPS)","mapV":0,"me":null,"devices":[%s],"timeline":[%s]}'
            % (int(now * 1000), "true" if scanning else "false", _json(warning) if warning else "null",
               ",".join(device_json(d, now) for d in views),
               ",".join(_json({"time": int(t * 1000), "tag": tag, "text": text}) for t, tag, text in store.timeline[:60])))


class Scanner:
    def __init__(self, db: core.VendorDb, ble: bool) -> None:
        self.db = db
        self.store = core.DeviceStore()
        self.lock = threading.Lock()
        self.ble_wanted = ble
        self.ble_status = "off"
        self.wifi_status = scanners.wifi_status()
        self.last_wifi = 0.0

    def wifi_pass(self, now: float) -> None:
        nets = scanners.scan_wifi()
        with self.lock:
            for n in nets:
                d = self.store.upsert(n.bssid, "WIFI", now)
                changed = d.name != n.ssid or d.security != n.caps or d.readings == 0
                d.name, d.security, d.frequency, d.wps = n.ssid, n.caps, n.freq, n.wps
                if changed:
                    core.apply(d, core.classify_wifi(n.bssid, n.ssid, n.caps, self.db, n.wps, n.freq))
                d.add_reading(n.rssi, now)
            self.last_wifi = now

    def on_ble(self, adv: scanners.BleAdvert, now: float) -> None:
        with self.lock:
            d = self.store.upsert(adv.addr, "BLE", now)
            if adv.name:
                d.name = adv.name
            if d.readings == 0 or d.readings % 30 == 0:
                core.apply(d, core.classify_ble(adv.addr, adv.name, adv.mfr, adv.uuid16, adv.svc_data, self.db))
            d.add_reading(adv.rssi, now)

    def ble_loop(self) -> None:
        if not self.ble_wanted:
            self.ble_status = "off (--no-ble)"
            return
        if not scanners.ble_available():
            self.ble_status = "off: install the 'bleak' package for Bluetooth (pip install bleak)"
            return
        import asyncio
        from bleak import BleakScanner

        def cb(device, adv):
            self.on_ble(scanners.BleAdvert(device.address, adv.local_name, adv.rssi, dict(adv.manufacturer_data or {}),
                                           list(adv.service_uuids or []), dict(adv.service_data or {})), time.time())

        async def run():
            while True:
                try:
                    async with BleakScanner(cb):
                        self.ble_status = "listening"
                        await asyncio.sleep(300)  # restart every 5 min, like the app, in case the adapter stalls
                except Exception as e:
                    self.ble_status = f"error: {e}"
                    await asyncio.sleep(15)

        asyncio.run(run())

    def warning(self, now: float) -> str:
        parts = []
        if now - self.last_wifi > 60:
            parts.append("no Wi-Fi list yet (" + self.wifi_status + ")")
        if self.ble_status not in ("listening",):
            parts.append("BLE " + self.ble_status)
        return " · ".join(parts)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="airscope-desktop", description="AirScope desktop beta: passive Wi-Fi/BLE observer with the phone's web HUD")
    ap.add_argument("--mirror", metavar="URL", help="show the phone's live HUD (its HUD address, e.g. http://192.168.1.20:8787/?k=abcd)")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--host", default="127.0.0.1", help="bind address (127.0.0.1 = this computer only)")
    ap.add_argument("--no-ble", action="store_true")
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--once", action="store_true", help="one Wi-Fi scan, print what was seen as JSON, exit (for tests)")
    args = ap.parse_args(argv)

    db = load_db()
    sc = Scanner(db, ble=not args.no_ble)
    if args.once:
        now = time.time()
        sc.wifi_pass(now)
        sc.store.rescore(now)
        print(state_json(sc.store, now, sc.warning(now), True))
        return 0

    state = HudState()
    state.mirror = args.mirror
    httpd = serve(state, args.host, args.port)
    url = f"http://{args.host}:{args.port}/?k=local"
    print(f"AirScope desktop beta · {url}" + (f" · mirroring {args.mirror}" if args.mirror else f" · {sc.wifi_status}"))
    if not args.no_browser:
        webbrowser.open(url)
    if not args.mirror:
        threading.Thread(target=sc.ble_loop, name="airscope-ble", daemon=True).start()
    try:
        while True:
            now = time.time()
            if not args.mirror:
                if now - sc.last_wifi >= 8:
                    sc.wifi_pass(now)
                with sc.lock:
                    for d in sc.store.rescore(now):
                        print(f"ALERT {d.level} {d.display_name()} [{d.id}] {'; '.join(d.flags)}")
                    state.state_json = state_json(sc.store, now, sc.warning(now), True)
            time.sleep(1.0)
    except KeyboardInterrupt:
        httpd.shutdown()
        return 0


if __name__ == "__main__":
    sys.exit(main())
