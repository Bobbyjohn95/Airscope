# Ideas and proposals (not shipped: K decides)

Coworkers: add bigger ideas here with what / why / risk / size. K: delete a line to say no, or reply in chat to say yes.

- **Map: draw the street layer once, not every frame.** What: cache roads/areas/buildings into a bitmap when the
  camera hasn't moved, redraw only the user/device/overlay layer per frame. Why: the map redraws at 20 fps even
  when nothing moves; on the emulator that saturates the CPU (90th-percentile frame 350-500 ms) and caused an
  ANR with the Search dialog open (worked around in v2.6.1 by resting behind dialogs); on the phone it costs
  battery and heat. Risk: medium (3D tilt + depth order, cache invalidation on zoom/tilt/new tiles); needs the
  emulator screenshots checked closely. Size: ~150 lines in MapView3D, one day on a branch.
- **Emulator test: the UI dump often fails ("could not get idle state" / "null root node") because of that
  same constant redraw**, so many steps run blind or are skipped (see summary.txt of any run). The caching above
  fixes the cause; meanwhile the script could press Back after the search step and use the cached button
  positions more. Small, test-only; no release needed. (Coworker can do this without a yes.)

## Approved by K (2026-11-03, in chat): build and ship in small releases
Delivery: normal auto-installing releases, one feature group at a time, each through the full ship path.

1. **Names (v2.7)** — owner name from broadcast names; one device across random-address changes; Bonjour/UPnP names on
   the joined Wi-Fi (receive-only, optional multicast permission). *Status: built, in CI.*
2. **Security part 1 (v2.8)** — hacker-tool detection (Wi-Fi Pineapple, Flipper Zero, ESP32 Marauder, O.MG, Hak5, BLE
   spam flooders) by broadcast fingerprint; evil-twin / rogue-AP alerts; network security grading with a weak-network
   filter; "following me" alerts for any BLE device that keeps appearing across places. *Status: next.*
3. **Security part 2 (v2.9)** — home/work baseline + new-device alert at a marked place; Wi-Fi disruption watch
   (all networks vanishing, own connection dropping: indicator, not proof); BLE spoof/spam detection; security incident
   report export. *Status: after v2.8.*
All of it stays passive: nothing is sent to any device.

## Asked by K (2026-11-03): Windows and Linux beta versions
- **What:** a desktop AirScope for Windows and Linux. The Android app can't run there, so this is a separate program
  that shares the ideas (and ideally the vendor tables, signatures and scoring) but not the Android code.
- **Why:** multi-platform; a laptop can watch the same things at a desk or in a car.
- **Risk / honest limits:** passive scanning on desktops is uneven. Windows: Wi-Fi networks via WlanAPI (no client
  devices, no raw frames), BLE adverts via WinRT (names, manufacturer data). Linux: Wi-Fi via nl80211 scan (needs the
  Wi-Fi adapter; monitor mode is optional and adapter-dependent), BLE via BlueZ. No GPS on most laptops, so placement
  and the map need another position source (manual, or the phone's). Must not become something that transmits.
- **Decisions K needs to make (asked in chat):** language/stack (Python is quickest; Kotlin/JVM shares the most code
  with the app; Rust/Go gives single-file binaries); whether the desktop version scans on its own, mirrors the phone
  (the web HUD already does this today), or both; where it lives (this repo under `desktop/`, or its own repo).
- **Rough size:** 2–4 weeks of coworker runs for a usable beta per OS; beta = unsigned builds attached to CI, not
  auto-installed anywhere.

