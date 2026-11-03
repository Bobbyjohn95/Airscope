# Working on AirScope (for people and AI coworkers)

AirScope is K's personal, passive Wi-Fi/BLE observer for a Pixel 8 Pro on GrapheneOS. Every push to `main`
becomes a release that the phone installs **by itself**. That makes the bar for a change simple:
**would K be glad this landed on their phone unasked?** If you can't say yes, don't ship it.

## Non-negotiables
- The app **only listens**. Never add anything that connects to, probes, jams or transmits to other devices.
- **No third-party libraries** in the app (test-only dependencies are fine). Framework APIs only, no native code.
- **GrapheneOS first:** no Google Play Services, no dynamic code loading. Any new permission needs a line in the
  setup checklist explaining why, and must be optional (the scan must still start without it).
- **Never touch** `AIRSCOPE_SIGNING_SEED`, `tools/derive_signing_key.py`, the signing block in
  `app/build.gradle.kts`, or the `publish` job's rules in `.github/workflows/build-apk.yml`. A different key means
  K has to uninstall and reinstall by hand.
- **Never commit secrets** (keys, tokens, passphrases). Scan the diff before committing.
- Keep the honest-limits spirit: never show a confident position or claim the app can't back up. Prefer "unknown"
  or a wide error circle over a guess. Mapped data stays credited (OpenStreetMap, OpenMapTiles, flock-you-esp32).

## How a change ships (the only path)
1. Work on a branch. Run `tools/dev_check.sh` (compiles with kotlinc + stub android.jar, runs the logic and tile
   suites) and, for placement/locator changes, `tools/dev_check.sh bench`.
2. Push the branch. CI runs the JVM tests, builds, and runs the app on an Android 15 emulator
   (`tools/emulator_smoke.sh`). Download the `emulator-report` artifact and **look at the screenshots** and
   `summary.txt`. No release is published from a branch.
3. Only when CI is green and the screenshots look right: fast-forward `main` (`git merge --ff-only`) and push.
   `main` runs the same pipeline and publishes `build-N`.
4. Put a short, plain-language entry at the top of `README.md` ("What's new") for anything K will notice.

## Benchmarks to keep (regressions are bugs)
- `tools/dev_check.sh bench` placement benchmark, 200 trials x 5 scenarios: **way-off <= 10 of ~95 located**,
  walk-loop median error <= 9 m, walk-L median <= 18 m, `moving` scenario located <= 3%.
- Logic suite: all PASS (174+ checks). Vector tile suite: ALL PASS.
- Emulator run: PASS, no crash/ANR, scan service survives screen-off, map tiles loaded, highway detected.

## What a good autonomous update looks like
- Fixes a real problem visible in the emulator report, a crash in a recent CI log, a stale external detail
  (a vendor OUI table, a Flock/Axon signature from flock-you-esp32, an Overpass/OpenFreeMap URL change, an Android
  API deprecation), or a clear, small usability gain.
- Small and reviewable: one topic per commit, under ~400 changed lines unless it's mechanical.
- Includes a test when the change is logic, and an emulator-script step when the change is UI.
- **Not** a redesign, a renamed concept, a new permission, a new dependency, a new network endpoint, or a change
  to update/signing behavior. Those get proposed to K (see below), not shipped.
- At most **one release per coworker run**, and none if nothing meets the bar. Silence is a fine outcome.

## Proposing instead of shipping
When an idea is bigger than the bar above, write it up in `IDEAS.md` (what, why, risk, rough size) and mention it
in the run summary so K can say yes or no. Don't start it unasked.

## Where things are
- `app/src/main/java/com/k/airscope/` — app. Pure-logic files (no `android.*` imports) are unit-testable:
  Locator, PositionFilter, HeadingFusion, HeadingMath, RoutePredictor, TileManager, VectorTile, MapData, Classifier,
  Signatures, Heuristics, DeviceStore, Model, Geo, Exporter, HudServer, MiniJson, UpdateCheck, WifiIe, AdvParser,
  Names, LanParser, LanDirectory, Threats (+ WifiSecurity, SsidMemory), SecurityWatch (Places, DisruptionWatch,
  BleSpamWatch, SecurityLog).
- `tests/jvm/` — logic/tile suites (run by Gradle in CI and by `tools/dev_check.sh` locally), placement benchmark.
- `tests/hud/` — the iPhone web HUD simulator + Playwright test (needs Node/Playwright; optional).
- `tools/emulator_smoke.sh` — what CI does on the emulator; add a step when you add UI.
- `app/src/main/assets/hud.html` — the web HUD (keep it in step with MapView3D behavior).

## Model mix
Use the best tool for each part: a cheaper, faster model for mechanical work and independent review passes, a
stronger one for locator/filter math and anything touching the release path. Independent review by a *different*
model than the author catches more; a review that sees the author's conclusions catches less, so brief it with
the code, not the opinion.
