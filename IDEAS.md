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
  *2026-11-04:* tried. The chip row can't be scrolled by the test at all while the map redraws (a swipe arrives as
  a tap and selects the chip under the finger), so the SECURITY chip has never been exercised on the emulator; it
  is only checked by the JVM tests and by eye on the phone. The same branch run also hit the Search-dialog ANR again
  (main thread waiting on the render thread, 88% in the emulator's software renderer), the one v2.6.1 worked around: it
  is flaky, emulator-specific as far as we can tell, and the street-layer cache above is the real fix for both. Branch
  `smoke-security-chip` holds the script tidy-up (off-screen chips no longer poison the tap cache; plain WARN when the
  row didn't scroll); it merges with the next real release, not on its own.
  *2026-11-05:* the same branch also fixes the "highway NOT detected" line that every run since v2.3 has shown: the
  app does show "71 mph · inside a car" mid-drive, the script just read the later screenshot, taken after the simulated
  drive had ended. Test-only, no release.
  *2026-11-06:* the script tidy-up shipped with the build that fixed the offline-map progress race (the "Offline area:
  every tile saved" JVM test failed on CI once on 11-05 because two download threads reported progress out of order).
  Freshness checks of upstream sources (flock-you-esp32, OpenFreeMap TileJSON, Overpass, Actions tags) are still
  blocked for the unattended coworker (web fetch needs an approval nobody is there to give); the emulator run shows
  tiles + Overpass live. overpass-api.de answered 502 once in that run; the kumi.systems fallback covered it.
  *2026-11-07:* build 49 live, its emulator run green (66 mph inside a car mid-drive, 5 tiles, 51 ALPR cameras mapped
  after screen-off, no crash). Nothing shipped (routine release was yesterday). Upstream freshness is blocked for the
  4th day: the unattended coworker can't fetch outside the repo (web fetch waits for an approval nobody gives; `gh api`
  to other repos is refused). **K's call:** either allow the coworker's web fetches to github.com, openfreemap.org,
  overpass-api.de, ieee.org, wireshark.org and bitbucket.org, or accept that signatures / Actions versions are only
  checked when K is around. The emulator run already proves tiles + Overpass work each day.
  *2026-11-08:* build 49 still live and healthy (no new CI runs; dev_check green; the only compiler warnings are two
  harmless "condition is always true" null checks in PositionFilter). Nothing shipped: no candidate met the bar.
  Upstream freshness blocked for the 5th day (same reason), not retried further. Weekly roundup sent to K.
  *2026-11-09:* build 49 still live, not prerelease; no new CI runs; dev_check green. Nothing shipped: no candidate met
  the bar (the only `@Suppress("DEPRECATION")` spots are intentional: edge-to-edge on older Android and the updater,
  which coworkers don't touch). Upstream freshness blocked for the 6th day (one try, same permission prompt).
- **CI actions are several major versions behind** (checkout v5→v7, setup-java v5→v6, setup-python v5→v7,
  upload-artifact v4→v7, download-artifact v4→v8, gradle/actions v4→v6, emulator-runner v2 current). Everything
  still works. Bumping touches the publish job (download-artifact), which coworkers must not change, and a failed
  bump would block releases: K's call. Size: 10 lines; needs one branch run to prove the emulator job still works.

## Approved by K (2026-11-03, in chat): build and ship in small releases
Delivery: normal auto-installing releases, one feature group at a time, each through the full ship path.

1. **Names (v2.7)** — owner name from broadcast names; one device across random-address changes; Bonjour/UPnP names on
   the joined Wi-Fi (receive-only, optional multicast permission). *Status: shipped (build 33).*
2. **Security part 1 (v2.8)** — hacker-tool detection (Wi-Fi Pineapple, Flipper Zero, ESP32 Marauder, O.MG, Hak5, BLE
   spam flooders) by broadcast fingerprint; evil-twin / rogue-AP alerts; network security grading with a weak-network
   filter; "following me" alerts for any BLE device that keeps appearing across places. *Status: built, in CI.
   BLE spam flooders moved to part 2 (they're detected by rate, not by name). Shipped.*
3. **Security part 2 (v2.9)** — home/work baseline + new-device alert at a marked place; Wi-Fi disruption watch
   (all networks vanishing, own connection dropping: indicator, not proof); BLE spoof/spam detection; security incident
   report export. *Status: shipped.*
All of it stays passive: nothing is sent to any device.

## Asked by K (2026-11-03): Windows and Linux beta versions
- **What:** a desktop AirScope for Windows and Linux. The Android app can't run there, so this is a separate program
  that shares the ideas (and ideally the vendor tables, signatures and scoring) but not the Android code.
- **Why:** multi-platform; a laptop can watch the same things at a desk or in a car.
- **Risk / honest limits:** passive scanning on desktops is uneven. Windows: Wi-Fi networks via WlanAPI (no client
  devices, no raw frames), BLE adverts via WinRT (names, manufacturer data). Linux: Wi-Fi via nl80211 scan (needs the
  Wi-Fi adapter; monitor mode is optional and adapter-dependent), BLE via BlueZ. No GPS on most laptops, so placement
  and the map need another position source (manual, or the phone's). Must not become something that transmits.
- **K decided (2026-11-03, in chat):** Python; scans on its own AND mirrors the phone's HUD feed; lives in this repo
  under `desktop/`; builds come out as CI artifacts (nothing auto-installs). Shares `app/src/main/assets/db` tables and
  the signature lists. *Status: first beta shipped (desktop/, workflow "Desktop beta", artifacts
  airscope-desktop-windows / -linux). Next: Places and the one-shot BLE spam watch on desktop; a proper dBm reading on
  Windows (WlanAPI) instead of the signal percentage.*
- **Rough size:** 2–4 weeks of coworker runs for a usable beta per OS; beta = unsigned builds attached to CI, not
  auto-installed anywhere.

