# AirScope desktop (beta) — Windows and Linux

A passive Wi-Fi + Bluetooth LE observer for a laptop, with the same web HUD as the phone app. **Beta:** it is a
separate program that shares the phone app's ideas, vendor tables and signatures, not its code.

## What it does
- Reads the Wi-Fi network list your operating system already keeps (Windows: `netsh`; Linux: NetworkManager's
  `nmcli`, or `iw` as root) and Bluetooth LE advertisements (through the optional `bleak` package: WinRT on Windows,
  BlueZ on Linux). **It only listens.** Nothing is sent to any device; nothing leaves the computer.
- Classifies what it sees the way the phone does: phones, wearables, trackers (AirTag away from owner, Tile,
  SmartTag, Chipolo), cameras/IoT, vehicles, drones, police/Flock/Axon gear, attack hardware (Pineapple, deauther,
  Marauder, O.MG, Flipper Zero), owner names from device names, Wi-Fi security grades A–F, evil-twin hints.
- Shows it in your browser at http://127.0.0.1:8787/ using the phone's HUD page (radar view, list, SECURITY chip).
- **Mirror mode** shows the phone's live HUD instead: `--mirror http://<phone-ip>:8787/?k=<token>` (the address the
  phone shows under HUD).

## Honest limits
- No GPS on a laptop: no map placement, no "moved with you", no mapped ALPR cameras. Distances are rough ranges
  from signal strength only.
- Wi-Fi updates only as often as the OS rescans (Windows rescans every few seconds while disconnected and much less
  often while connected; NetworkManager every 10–30 s). Nothing is forced.
- Windows reports signal as a percentage; it is converted to dBm approximately.
- Bluetooth needs `bleak` (`pip install -r requirements.txt`) and a BLE adapter; without it the Wi-Fi part still works.

## Run from source
    cd desktop
    pip install -r requirements.txt        # optional, for Bluetooth
    python -m airscope_desktop              # opens the HUD in your browser
    python -m airscope_desktop --no-ble --no-browser --port 8787
    python -m airscope_desktop --mirror "http://192.168.1.20:8787/?k=abcdefghjk"

## Builds
Every push that touches `desktop/` runs the tests on Windows and Linux and attaches `airscope-desktop-windows` and
`airscope-desktop-linux` (PyInstaller folders) to the workflow run under GitHub → Actions → "Desktop beta".
Download, unzip, run `airscope-desktop` (Linux: `chmod +x` first). Unsigned: Windows SmartScreen will ask once.

## Tests
    python -m unittest discover -s desktop/tests -v
