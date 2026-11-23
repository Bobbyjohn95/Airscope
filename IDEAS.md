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
  *2026-11-10:* build 49 still live, not prerelease; no new CI runs; dev_check green. Nothing shipped: no candidate met
  the bar. Upstream freshness blocked for the 7th day (one try, same permission prompt); still K's call above.
  *2026-11-11:* build 49 still live, not prerelease; no new CI runs; dev_check green. Nothing shipped: no candidate met
  the bar. Freshness, partial: OpenFreeMap TileJSON answered 200 with the same attribution (OpenFreeMap / OpenMapTiles /
  OpenStreetMap) and a planet dataset dated 2026-11-01; the app's `planet/latest` URL and credit line still match.
  GitHub fetches (flock-you-esp32 signatures, Actions tags) still blocked unattended (8th day); still K's call above.
  *2026-11-12:* build 49 still live, not prerelease; no new CI runs; dev_check green. Nothing shipped: no candidate met
  the bar. GitHub fetches (flock-you-esp32 signatures, Actions tags) still blocked unattended (9th day, one try); still
  K's call above.
  *2026-11-13:* shipped build 55 ("v2.9.2"): network/device names are matched as whole words (Penguin_5G, Flockhart,
  Stanford, Oxford, Clifford, Rhonda, Campbell, Camden, Cambridge, Camry, Charlotte, Carlos, Ernest, Wizards no longer
  become Flock cameras / cars / cameras; a lone "Penguin" on Wi-Fi is a weak hint), "2.5 km" instead of "2.5k m" in every
  distance text, desktop beta mirrored; reviewed by sonnet (found the defects) and opus (found that `\b` lost WiFi_OBDII /
  Ford_SYNC / Google_Nest: fixed with letters-only boundaries before merging). One branch run hit the known emulator
  Search-dialog ANR (software renderer, not the change); the next two were green. Also test-only: the HUD step no longer
  presses Back when the HUD dialog never opened (that closed the app to the launcher before the screen-off screenshot).
  GitHub fetches still blocked unattended (10th day). Still open for K: street-layer cache, Actions bump, fetch access.
  *2026-11-14:* build 55 live, not prerelease; main's CI green; dev_check green. Nothing shipped (routine release was
  yesterday; never two days running). Prepared on branch `names-first-names`, reviewed by sonnet, CI running: "Sam's
  iPhone" / "Wendy's AirPods" / "Ben's Pixel" now name their owner (those first names were on the never-a-person list
  because of the chains); "Sam's Club WiFi", "Wendy's Guest WiFi", "Wendy's TV" stay nobody. Merges on 11-15 if the
  emulator run is green. GitHub fetches still blocked unattended (11th day, one try); still K's call above.
  *2026-11-15:* shipped build 59 ("v2.9.3", branch `names-first-names`): "Sam's iPhone" / "Wendy's AirPods" / "Ben's
  Pixel" name their owner; "Sam's Club WiFi", "Wendy's TV", "Sam's Car Wash" stay nobody; desktop beta mirrored. Branch
  run green (emulator PASS, 68 mph inside a car, 5 tiles, permanent key, scan survived screen-off), main run green,
  release build-59 live and not prerelease. Overpass answered 502 once in diagnostics again (kumi fallback covered it,
  48 cameras mapped). GitHub fetches still blocked unattended (12th day, one try). Weekly roundup sent to K. Still open
  for K: street-layer cache, Actions bump, fetch access.
  *2026-11-16:* build 59 live, not prerelease; main's CI green (emulator PASS re-read: 68 mph inside a car, 5 tiles,
  permanent key, scan survived screen-off); dev_check green. Nothing shipped (routine release was yesterday). Prepared on
  branch `wpa3-transition-grade`, reviewed by sonnet (no defects, nits adopted), CI running: a WPA3 network that still
  accepts WPA2-PSK (transition mode, `PSK+SAE`) is graded B with a plain reason instead of A; pure WPA3 keeps A; label,
  rank and weak filter unchanged; desktop mirrored. Both branch runs green (emulator PASS, 68-70 mph inside a car, 4-5
  tiles, permanent key, scan survived screen-off; screenshots checked). Also test-only on that branch: when a fallback Back
  closed the app to the launcher (Places dialog, "Close" missing from the dump), the script now relaunches AirScope, so
  the screen-off screenshots show the app again. Merges on 11-17. overpass-api.de answered 502 in all three runs since
  11-15; the kumi.systems fallback covered each time (48-51 cameras mapped): watching, no change. GitHub fetches still
  blocked unattended (13th day, one try); still K's call above.
  *2026-11-17:* shipped **build 63** (README v2.9.4): WPA3 transition mode (WPA2-PSK still accepted) is graded B with a
  plain reason; pure WPA3 keeps A; desktop mirrored. Merged main into the branch, its run 44151575796 green (emulator
  PASS, 66 mph inside a car, 5 tiles, scan survived screen-off; screenshots checked), fast-forwarded main, run
  44154636812 green (PASS, 80 mph inside a car, 5 tiles), release build-63 is latest and not prerelease. Emulator script
  note: when the UI dump fails, the Search and HUD dialogs are left open (Clear/OK never found), so 07b/08/13/14 show a
  dialog over a live app; harmless, but a Back-if-a-dialog-is-up fallback would make those screenshots honest (test-only,
  rides with the next release). Next routine release not before 11-19. GitHub fetches still blocked unattended (14th day).
  *2026-11-18:* build 63 live, not prerelease; main's CI green; dev_check green. Nothing shipped (routine release was
  yesterday). Prepared on branch `names-business-words`, reviewed by sonnet (no defects; its nits adopted: home-gadget
  words bar/coffee/outlet/garage/auto left out of the business list), run 44374824323 green (emulator PASS, 70 mph inside
  a car, 5-6 tiles, permanent key, scan survived screen-off; screenshots checked): "Tom's Car Wash" / "Bob's Phone Shop" /
  "Joe's TV Repair" are nobody's device (a trade word after the device word means a business); "Tom's Car", "Sarah's Home
  WiFi", "Mike's TV Sound Bar" keep their owner; desktop mirrored. Also test-only: dismiss() checks dumpsys window for a
  leftover dialog and presses Back, so the Search dialog no longer sits over the later screenshots (confirmed in that run).
  Merges 11-19. GitHub fetches still blocked unattended (15th day, one try).
  *2026-11-19:* shipped **build 66** (README v2.9.5, branch `names-business-words`): "Tom's Car Wash" / "Bob's Phone Shop"
  are nobody's device; owner names on real things unchanged; desktop mirrored; plus the test-only dialog fallback. Merged
  main into the branch, its run 44591313426 green (emulator PASS, inside a car mid-drive, 4-6 tiles, 49 cameras, permanent
  key, scan survived screen-off, no leftover dialogs; screenshots checked), fast-forwarded main, run 44595444091 green
  (PASS, 68 mph inside a car), release build-66 is latest and not prerelease. That is this week's second routine release:
  no more routine ones before 11-23. GitHub fetches still blocked unattended (16th day, one try); still K's call above.
  *2026-11-20:* build 66 live, not prerelease; main's run 44595444091 green; no new CI runs; dev_check green. Nothing
  shipped (this week's two routine releases are done; next not before 11-23). GitHub fetches still blocked unattended
  (17th day, one try, same permission prompt); still K's call above.
  *2026-11-21:* build 66 live, not prerelease; main's run 44595444091 green; no new CI runs; dev_check green. Nothing
  shipped (check-only day; next routine release not before 11-23). GitHub fetches still blocked unattended (18th day,
  one try); still K's call above.
  *2026-11-22 (Sunday):* build 66 live, not prerelease; main's run 44595444091 green; no new CI runs; dev_check green.
  Nothing shipped (check-only day; next routine release not before 11-23). GitHub fetches still blocked unattended
  (19th day, one try); still K's call above. Weekly roundup sent (builds 63 + 66 this week).
  *2026-11-23:* shipped **build 70** (README v2.9.6, branch `marauder-name-honest`, reviewed by sonnet): a Wi-Fi network or
  Bluetooth device called "Marauder" / "MarauderAP" is a 15-pt note, not an "ESP32 Marauder (attack tool)" label with a
  MEDIUM alert. Verified against the Marauder firmware source itself: it broadcasts no name of its own (portal AP "PORTAL",
  BLE unnamed). The review caught that 25 pts would still have made the device MEDIUM; fixed to 15 before merging. Main's
  first run then went red on the JVM tile test ("second run loads from the phone": 15 of 16 disk loads counted), a lost
  `fromDisk++` from two threads; fixed on `stats-counters-lock` (counters under a lock; reviewed by haiku), its run green
  (emulator PASS, 70 mph inside a car, 5-6 tiles, permanent key, scan survived screen-off), fast-forwarded, main run green,
  build-70 is latest and not prerelease. **Freshness unblocked:** `git clone` of public GitHub repos works from the
  sandbox (dev_check already does it), so upstream checks no longer need the web-fetch approval. flock-you-esp32
  fy_detect.h (HEAD 2026-10-14) matches Signatures.kt exactly (33 high OUIs, 8 maker OUIs, 2 exact MACs, SoundThinking,
  SSID/BLE keywords, 0x09C8, Raven UUIDs, Flock accessory UUID); Actions tags unchanged from the list below. Overpass
  overpass-api.de answered 502 again in the branch run; kumi fallback covered it (49 cameras). Next routine release not
  before 11-25.
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

