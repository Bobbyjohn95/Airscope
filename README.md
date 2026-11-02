# AirScope: passive Wi-Fi + Bluetooth radar for Android

A personal, original signal-observation app for the Pixel 8 Pro (Android 10+).
It **only listens**. It reads Wi-Fi access-point beacons and Bluetooth LE advertisements
that devices already broadcast. It never connects to, jams, deauths or probes anything.

## Features
- **Radar HUD**: Wi-Fi (●) in the top half, BLE (◆) in the bottom half, distance rings 2–40 m.
  Phones can't measure direction, so bearing is a stable placeholder. Distance is an estimate from signal strength.
- **Risk scoring** (LOW / MEDIUM / HIGH) with reasons:
  - Trackers: Apple Find My (flags "away from owner" mode), Tile, Samsung SmartTag, Chipolo, Google Find Hub, cross-platform tracker alerts
  - **"Tracker has stayed with you for N min"**: the key signal for an unknown tag traveling with you
  - Camera/IoT-style Wi-Fi names (ESP_, cam, DVR, V380, Tuya…), open/WEP networks, hidden SSIDs
  - Possible evil twin: same SSID broadcast both open and secured
  - Phone hotspots (randomized MACs) lingering nearby
- **Track mode**: tap a blip or row to get a live signal meter. Walk around; more bars = closer. Good for finding a hidden tag.
- **Mark as mine**: long-press a row to whitelist your own AirPods, watch, etc.
- **Intel log**: automatic timeline of escalations and scan summaries.
- **Background scanning with the screen off**: foreground service + wake lock + screen-off BLE tracker filters.
- **Notifications**: an ongoing status notification (with Stop) and high-priority alerts when a tracker or high-risk signal shows up.
- **CSV export** to `Downloads/AirScope/`.
- **Web HUD for iPhone**: the Pixel serves a live dashboard on your Wi-Fi. See below.

## Get the APK (pick one)

### Option A: cloud build on GitHub, no PC software needed
1. Create a free GitHub account and a new **private** repository.
2. Upload this whole folder (keep the folder structure, including `.github/`).
   Easiest from a computer: drag-and-drop on the repo's "Add file → Upload files" page, or `git push`.
3. Open the **Actions** tab. The "Build APK" workflow runs automatically (about 4 minutes).
4. Open the finished run, then under **Artifacts** download `AirScope-apk`. Unzip it to get `AirScope.apk`.

### Option B: Android Studio
1. Install Android Studio, then **File → Open** this folder.
2. Let Gradle sync finish. It downloads the Android SDK pieces automatically.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
   Output: `app/build/outputs/apk/debug/app-debug.apk`.
   Or plug in the Pixel with USB debugging on and press ▶ Run.

## Install on the Pixel 8 Pro
1. Copy the APK to the phone and open it. Allow "Install unknown apps" for your file manager or browser when asked.
   Play Protect may warn about an app from an unknown developer. That's expected for your own build.
2. Open AirScope and allow **Location**, **Nearby devices** and **Notifications**.
3. Tap **BATTERY** and choose *Allow* so Android doesn't pause scanning with the screen off.
4. Keep **Location** turned on in quick settings. Android hides Wi-Fi/BLE results when it's off.
5. Recommended: Settings → About phone → tap *Build number* 7× → Developer options → turn **off "Wi-Fi scan throttling"**.
   Otherwise Android limits Wi-Fi scans to 4 per 2 minutes. BLE is unaffected.

## GrapheneOS
AirScope is GrapheneOS-friendly by design: no Google Play Services or any other Google library, no native code
(so memory tagging and hardened-malloc settings don't affect it), no dynamic code loading, and modern v2/v3 APK signatures.
- **Install:** open the APK from Files. GrapheneOS asks once to allow installs from Files. Turn that back off afterwards if you like.
- **Network permission:** GrapheneOS shows a per-app *Network* toggle at install time. Scanning works **without** it,
  so you can deny it for a fully offline app. Allow it only if you want the iPhone web HUD
  (Settings → Apps → AirScope → Permissions → Network). If it's off, the app shows a warning when you tap HUD.
- **Location:** keep the system Location toggle on. GrapheneOS doesn't need Google location services. The toggle only unlocks Wi-Fi/BLE scan results.
- **Nearby devices + Notifications:** allow both when asked.
- **Battery:** tap **BATTERY** in the app, or Settings → Apps → AirScope → App battery usage → **Unrestricted**.
- **Wi-Fi scan throttling:** GrapheneOS keeps the stock Developer options switch (Settings → System → Developer options → Networking).

## Signing and updates
No signing key is stored in this repository.
- **Default:** each GitHub build is signed with a one-time key. To install a newer build, uninstall the old AirScope first.
  You'll lose your "Mark as mine" list.
- **Same key every build (optional):** create a keystore once
  (`keytool -genkeypair -keystore airscope.jks -storetype PKCS12 -alias airscope -keyalg RSA -keysize 3072 -validity 10000`),
  then in the repo go to Settings → Secrets and variables → Actions and add two secrets:
  `AIRSCOPE_KEYSTORE_B64` (output of `base64 -w0 airscope.jks`) and `AIRSCOPE_KEYSTORE_PASSWORD`.
  After that, every build installs over the previous one. The build log prints the certificate's SHA-256 so you can check it.

## Screen-off behavior
- Wi-Fi keeps scanning on its timer while the service runs. With throttling on, updates come about every 30 s.
- Android pauses *unfiltered* BLE scans when the screen is off, so AirScope also runs a *filtered* scan for
  Apple / Samsung / Tile / Chipolo / Google Find Hub / tracker-alert advertisements. Tracker detection keeps working
  with the screen off. Generic BLE gadgets only update while the screen is on.
- Stop scanning from the app or the notification's **Stop** button.

## Web HUD on your iPhone
iPhones (Safari and every iOS browser) have no API for Wi-Fi scanning or passive Bluetooth scanning,
so the iPhone can't be the sensor. Instead the Pixel scans and the iPhone shows a live dashboard:
1. In AirScope tap **START**, then **HUD**. A link like `http://192.168.1.23:8787/?k=xxxxxxxxxx` appears.
2. On the iPhone, on the **same Wi-Fi** (or joined to the Pixel's hotspot), open that link in Safari.
3. Share → **Add to Home Screen** to get it as an app icon.

The `k=` key is random per install, and requests without it get a 403. The HUD is read-only and only exists on your local network.
The page also opens without a phone (demo data), and **LOAD CSV** lets it view an exported scan.

## Limits to know
- Distance is ±2–3× (walls, bodies and pockets absorb signal). Treat it as near / mid / far.
- Randomized BLE MACs: phones rotate addresses every ~15 min, so they show up as new devices. Trackers that are
  away from their owner rotate only about daily, which is why the "stayed with you" rule works.
- A flag is a reason to look closer, not proof. A "camera-style" name is just a name.

## Project layout
```
app/src/main/java/com/k/airscope/
  MainActivity.kt   UI (radar, list, log, buttons, permissions)
  RadarView.kt      animated radar + Ui colors
  ScanService.kt    foreground service, wake lock, alerts, web HUD host
  SignalScanner.kt  Wi-Fi + BLE scanning (screen-on and screen-off scans)
  Notifier.kt       notification channels, status + alert notifications
  BleClassifier.kt  tracker / vendor recognition from raw advertisement data
  Heuristics.kt     risk scoring rules
  DeviceStore.kt    observations + timeline
  Exporter.kt       CSV + JSON
  HudServer.kt      tiny local HTTP server for the iPhone HUD
app/src/main/assets/hud.html   the web HUD
```
