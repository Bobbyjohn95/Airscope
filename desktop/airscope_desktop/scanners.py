"""
Passive radio input for the desktop beta.

Wi-Fi: the operating system already scans for networks on its own; we only read its list.
  Windows: `netsh wlan show networks mode=bssid` (cached scan results, no admin needed).
  Linux:   `nmcli -t ... dev wifi list --rescan no` (NetworkManager's cache, no root) or `iw dev <if> scan` (root).
BLE: advertisements through the optional `bleak` package (WinRT on Windows, BlueZ on Linux). Receive-only.

Nothing here connects to, probes or transmits to any device. The parsers are pure so they can be unit-tested
against captured output.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional


@dataclass
class WifiNet:
    bssid: str
    ssid: str
    rssi: int
    caps: str  # Android-style capability string, e.g. "[WPA2-PSK-CCMP][ESS]"
    freq: int = 0
    wps: bool = False


def _pct_to_dbm(pct: int) -> int:
    """Windows reports signal quality 0..100 %; the usual mapping is dBm = pct/2 - 100."""
    return int(round(max(0, min(100, pct)) / 2.0 - 100))


def _chan_to_freq(ch: int) -> int:
    if 1 <= ch <= 13:
        return 2407 + 5 * ch
    if ch == 14:
        return 2484
    if 32 <= ch <= 177:
        return 5000 + 5 * ch
    return 0


def _caps_from_words(auth: str, cipher: str = "") -> str:
    """Build an Android-style capability string from OS auth/cipher words (good enough for the grading rules)."""
    a = auth.upper()
    c = cipher.upper()
    cip = "CCMP" if ("CCMP" in c or "AES" in c or "GCMP" in c) else "TKIP" if "TKIP" in c else "CCMP"
    out = []
    if "WPA3" in a or "SAE" in a:
        out.append(f"[RSN-SAE-{cip}]")
    if "WPA2" in a or "RSN" in a:
        out.append(f"[WPA2-{'EAP' if 'ENTERPRISE' in a or '802.1X' in a or 'EAP' in a else 'PSK'}-{cip}]")
    if re.search(r"\bWPA\b|WPA-|WPA1", a) and "WPA2" not in a and "WPA3" not in a:
        out.append(f"[WPA-PSK-{cip}]")
    if "WEP" in a:
        out.append("[WEP]")
    if "OWE" in a:
        out.append("[OWE]")
    out.append("[ESS]")
    return "".join(out)


# ----------------------------------------------------------------------------- Windows: netsh

def parse_netsh(text: str) -> List[WifiNet]:
    nets: List[WifiNet] = []
    ssid = ""
    auth = ""
    enc = ""
    cur: Optional[WifiNet] = None
    for raw in text.splitlines():
        line = raw.strip()
        m = re.match(r"^SSID \d+\s*:\s*(.*)$", line)
        if m:
            ssid = m.group(1).strip()
            auth = enc = ""
            cur = None
            continue
        m = re.match(r"^Authentication\s*:\s*(.*)$", line, re.I)
        if m:
            auth = m.group(1).strip()
            continue
        m = re.match(r"^Encryption\s*:\s*(.*)$", line, re.I)
        if m:
            enc = m.group(1).strip()
            continue
        m = re.match(r"^BSSID \d+\s*:\s*([0-9a-fA-F:]{17})$", line)
        if m:
            cur = WifiNet(m.group(1).lower(), ssid, -100, _caps_from_words(auth if auth else "Open", enc))
            nets.append(cur)
            continue
        if cur is None:
            continue
        m = re.match(r"^Signal\s*:\s*(\d+)\s*%$", line, re.I)
        if m:
            cur.rssi = _pct_to_dbm(int(m.group(1)))
            continue
        m = re.match(r"^Channel\s*:\s*(\d+)$", line, re.I)
        if m:
            cur.freq = _chan_to_freq(int(m.group(1)))
    return nets


def scan_windows() -> List[WifiNet]:
    out = subprocess.run(["netsh", "wlan", "show", "networks", "mode=bssid"], capture_output=True, text=True, timeout=20, errors="replace")
    return parse_netsh(out.stdout)


# ----------------------------------------------------------------------------- Linux: nmcli / iw

NMCLI_FIELDS = "SSID,BSSID,CHAN,FREQ,SIGNAL,SECURITY"


def parse_nmcli(text: str) -> List[WifiNet]:
    """`nmcli -t -f SSID,BSSID,CHAN,FREQ,SIGNAL,SECURITY dev wifi list --rescan no` (colons in the BSSID are escaped as \\:)."""
    nets: List[WifiNet] = []
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = re.split(r"(?<!\\):", line)
        if len(parts) < 6:
            continue
        ssid, bssid, chan, freq, signal, security = [p.replace("\\:", ":") for p in parts[:6]]
        if not re.match(r"^[0-9A-Fa-f:]{17}$", bssid):
            continue
        try:
            sig = int(signal)
            fr = int(re.sub(r"\D", "", freq) or 0)
        except ValueError:
            sig, fr = 0, 0
        caps = _caps_from_words(security if security.strip() else "Open")
        nets.append(WifiNet(bssid.lower(), ssid, _pct_to_dbm(sig), caps, fr or _chan_to_freq(int(chan) if chan.isdigit() else 0)))
    return nets


def parse_iw(text: str) -> List[WifiNet]:
    """`iw dev <iface> scan` output (needs root; passive dump of the kernel's scan results)."""
    nets: List[WifiNet] = []
    cur: Optional[WifiNet] = None
    for raw in text.splitlines():
        m = re.match(r"^BSS ([0-9a-f:]{17})", raw)
        if m:
            cur = WifiNet(m.group(1), "", -100, "[ESS]")
            nets.append(cur)
            continue
        if cur is None:
            continue
        line = raw.strip()
        m = re.match(r"^SSID:\s?(.*)$", line)
        if m:
            cur.ssid = m.group(1)
        m = re.match(r"^signal:\s*(-?\d+(\.\d+)?) dBm", line)
        if m:
            cur.rssi = int(float(m.group(1)))
        m = re.match(r"^freq:\s*(\d+)", line)
        if m:
            cur.freq = int(m.group(1))
        if line.startswith("RSN:"):
            cur.caps = cur.caps.replace("[ESS]", "") + "[WPA2-PSK-CCMP]" + "[ESS]"
        elif line.startswith("WPA:") and "[WPA2" not in cur.caps:
            cur.caps = cur.caps.replace("[ESS]", "") + "[WPA-PSK-TKIP]" + "[ESS]"
        elif "Authentication suites: SAE" in line:
            cur.caps = cur.caps.replace("[WPA2-PSK-CCMP]", "[RSN-SAE-CCMP]")
        elif line.startswith("WPS:"):
            cur.wps = True
        elif "Privacy" in line and "capability:" in line and "[WPA" not in cur.caps and "[RSN" not in cur.caps:
            cur.caps = "[WEP][ESS]"
    return nets


def scan_linux() -> List[WifiNet]:
    if shutil.which("nmcli"):
        out = subprocess.run(["nmcli", "-t", "-f", NMCLI_FIELDS, "dev", "wifi", "list", "--rescan", "no"], capture_output=True, text=True, timeout=20, errors="replace")
        if out.returncode == 0 and out.stdout.strip():
            return parse_nmcli(out.stdout)
    if shutil.which("iw"):
        ifs = subprocess.run(["iw", "dev"], capture_output=True, text=True, timeout=10, errors="replace").stdout
        for iface in re.findall(r"Interface (\S+)", ifs):
            out = subprocess.run(["iw", "dev", iface, "scan"], capture_output=True, text=True, timeout=30, errors="replace")
            if out.returncode == 0:
                return parse_iw(out.stdout)
    return []


def scan_wifi() -> List[WifiNet]:
    try:
        if sys.platform.startswith("win"):
            return scan_windows()
        if sys.platform.startswith("linux"):
            return scan_linux()
    except Exception:
        return []
    return []


def wifi_status() -> str:
    if sys.platform.startswith("win"):
        return "Windows: reading the Wi-Fi list Windows keeps (netsh)" if shutil.which("netsh") else "netsh not found"
    if sys.platform.startswith("linux"):
        if shutil.which("nmcli"):
            return "Linux: reading NetworkManager's Wi-Fi list (nmcli)"
        if shutil.which("iw"):
            return "Linux: iw scan (needs root)"
        return "Linux: neither nmcli nor iw found"
    return f"{sys.platform}: no Wi-Fi reader for this OS yet"


# ----------------------------------------------------------------------------- BLE through bleak (optional)

class BleAdvert:
    def __init__(self, addr: str, name: Optional[str], rssi: int, mfr: Dict[int, bytes], uuids: Iterable[str], svc_data: Dict[str, bytes]) -> None:
        self.addr = addr.lower()
        self.name = name
        self.rssi = rssi
        self.mfr = mfr
        self.uuid16 = {_uuid16(u) for u in uuids if _uuid16(u) is not None}
        self.svc_data = {_uuid16(k): v for k, v in svc_data.items() if _uuid16(k) is not None}


def _uuid16(u: str) -> Optional[int]:
    u = u.lower()
    if u.endswith("-0000-1000-8000-00805f9b34fb") and u.startswith("0000"):
        return int(u[4:8], 16)
    if len(u) == 4:
        return int(u, 16)
    return None


def ble_available() -> bool:
    try:
        import bleak  # noqa: F401
        return True
    except Exception:
        return False
