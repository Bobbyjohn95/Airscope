#!/usr/bin/env python3
"""
Download public vendor tables at build time and write compact TSVs into app assets:
  db/oui.tsv            IEEE MA-L MAC prefixes      (aabbcc<TAB>Vendor)
  db/ble_companies.tsv  Bluetooth SIG company IDs   (004c<TAB>Apple)
  db/ble_uuids.tsv      Bluetooth SIG 16-bit UUIDs  (180d<TAB>Heart Rate)
Never fails the build: if a source is unreachable the app falls back to built-in tables.
Usage: fetch_vendor_db.py <assets_dir>
"""
import csv
import io
import os
import re
import sys
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 (AirScope build; +https://github.com)"}
SUFFIX = re.compile(r"[,.]?\s*(Inc|Incorporated|Ltd|Limited|LLC|Co|Corp|Corporation|GmbH|AG|S\.A|SA|B\.V|BV|AB|Oy|Pty|PLC|"
                    r"Holdings?|Company|Technologies|Technology|Electronics|International|Communications)\.?,?$", re.I)


def short(v: str) -> str:
    s = " ".join(v.split()).strip()
    while True:
        n = SUFFIX.sub("", s).strip().rstrip(",.")
        if n == s or not n:
            return s
        s = n


def get(url: str) -> str:
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def oui() -> dict:
    out = {}
    try:
        text = get("https://standards-oui.ieee.org/oui/oui.csv")
        for row in csv.reader(io.StringIO(text)):
            if len(row) >= 3 and row[0] == "MA-L" and len(row[1]) == 6:
                out[row[1].lower()] = short(row[2])
    except Exception as e:  # noqa: BLE001
        print("IEEE OUI download failed:", e)
    if len(out) < 10000:
        try:  # Fallback: Wireshark's manuf file (also derived from the IEEE registry)
            for line in get("https://www.wireshark.org/download/automated/data/manuf").splitlines():
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                m = parts[0].strip()
                if re.fullmatch(r"[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}", m) and len(parts) >= 2:
                    name = parts[2] if len(parts) >= 3 and parts[2].strip() else parts[1]
                    out.setdefault(m.replace(":", "").lower(), short(name.strip()))
        except Exception as e:  # noqa: BLE001
            print("Wireshark manuf download failed:", e)
    return out


def sig_yaml(url: str, key: str) -> dict:
    """Minimal parser for the Bluetooth SIG assigned-numbers YAML (value/uuid + name pairs)."""
    out = {}
    try:
        text = get(url)
    except Exception as e:  # noqa: BLE001
        print("Download failed:", url, e)
        return out
    cur = None
    for line in text.splitlines():
        m = re.match(r"\s*-?\s*" + key + r":\s*0x([0-9A-Fa-f]+)", line)
        if m:
            cur = int(m.group(1), 16)
            continue
        m = re.match(r"\s*name:\s*['\"]?(.*?)['\"]?\s*$", line)
        if m and cur is not None:
            out[cur] = short(m.group(1).replace("''", "'"))
            cur = None
    return out


def write(path: str, rows):
    with open(path, "w", encoding="utf-8") as f:
        for k, v in rows:
            f.write(f"{k}\t{v}\n")


def main():
    base = os.path.join(sys.argv[1], "db")
    os.makedirs(base, exist_ok=True)
    o = oui()
    if o:
        write(os.path.join(base, "oui.tsv"), sorted(o.items()))
    sig = "https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers"
    comp = sig_yaml(sig + "/company_identifiers/company_identifiers.yaml", "value")
    if comp:
        write(os.path.join(base, "ble_companies.tsv"), ((f"{k:04x}", v) for k, v in sorted(comp.items())))
    uu = sig_yaml(sig + "/uuids/service_uuids.yaml", "uuid")
    uu.update({k: v for k, v in sig_yaml(sig + "/uuids/member_uuids.yaml", "uuid").items() if k not in uu})
    if uu:
        write(os.path.join(base, "ble_uuids.tsv"), ((f"{k:04x}", v) for k, v in sorted(uu.items())))
    print(f"vendor DB: {len(o)} OUIs, {len(comp)} BLE companies, {len(uu)} BLE UUIDs")


if __name__ == "__main__":
    main()
