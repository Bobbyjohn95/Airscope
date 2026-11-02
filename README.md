# AirScope: passive Wi-Fi + Bluetooth radar for Android

A personal signal-observation app for the Pixel 8 Pro (Android 10+, GrapheneOS-friendly).
It **only listens**. It reads Wi-Fi beacons and Bluetooth LE advertisements that devices already
broadcast. It never connects to, jams, deauths or transmits to anything.

## What's new in v2
- **Search modes:** ALL · PHONES · WEAR/AUDIO · TRACKERS · CAMS/IOT · VEHICLES · DRONES · POLICE/ALPR · NETWORKS.
- **Police / ALPR detection**
  - **Flock Safety** over the air: Flock hardware MAC prefixes, `Flock-XXXXXX` / "Flock Camera net." networks,
    Penguin / "FS Ext Battery" packs, the Flock BLE company ID, the Flock accessory service.
  - **Raven / ShotSpotter** gunshot detectors (BLE service IDs, SoundThinking MAC prefix).
  - **Axon** body cams, Tasers and holster sensors (BLE prefix `00:25:DF`).
  - **Fleet routers** (Cradlepoint-style `IBR900-xxx`, Sierra Wireless, etc.). These are common in police cars, but also
    in ambulances, buses and businesses, so they're shown without a pop-up.
  - **Mapped ALPRs:** 336k+ cameras volunteers mapped on OpenStreetMap (DeFlock). AirScope downloads the ones within 8 km,
    caches them for offline use, and alerts when one is **ahead of you within 400 m while driving**, or within 150 m on foot.
    These have **real distance and direction** from GPS.
- **3D radar:** distance from the center = estimated range, wedge around the ring = device type, height = threat score.
  Drag to orbit/tilt, pinch to zoom, tap to select, and **3D/2D** to switch. Devices glide smoothly between updates.
- **Smoother:** all scanning, classification and scoring runs on a background engine thread. The UI only draws snapshots.
- **Better distance**
  - Kalman-filtered signal strength, so readings don't jump around.
  - Per-device calibration from iBeacon/Eddystone measured power or advertised TX power.
  - Environment profiles (**ENV:** OPEN / INDOOR / DENSE / CAR).
  - A likely **range** (e.g. "~3.2 m (2.0–5.1)") instead of a single fake-precise number.
  - **Wi-Fi RTT:** real measured distance to access points that support 802.11mc. The Pixel 8 Pro supports this.
  - **Drone Remote ID:** a drone that broadcasts its GPS position gets a real distance and bearing.
- **Better labels**
  - Full IEEE MAC-vendor and Bluetooth SIG company/service tables, downloaded when GitHub builds the app.
  - Apple Continuity decoding: AirPods/Beats model, iPhone, Watch, HomePod, Find My.
  - Device type from the BLE "appearance" field.
  - WPS info from Wi-Fi beacons (e.g. "HP OfficeJet Pro 9015 (Printer/scanner)").
  - Tesla phone keys, dash cams, OBD dongles, car hotspots, phone hotspots, smart-home gear.
  - Long-press any row for every detail.
- **Self-updates** from this repo's GitHub Releases (see below).
- **Setup checklist** (MORE → Setup & permissions) that requests every permission and setting the app needs.

## Install
1. On the Pixel, open this repo's **Releases**, tap the newest **AirScope.apk**, and open it from Files.
   Allow installs from Files when asked. Play Protect may warn about an unknown developer; that's expected for your own build.
2. Open AirScope. The setup checklist appears: tap **FIX** on each item. It covers Location, Nearby devices, Notifications,
   battery unrestricted, Location and Bluetooth on, and allowing it to install its own updates.
3. Tap **START**.

**Upgrading from build 2 or earlier:** uninstall the old AirScope first (it was signed with a different key), then install the new one.

## Automatic updates (one-time setup, about 2 minutes on your phone)
Android only installs an update signed with the **same key** as the installed app. GitHub's build machines are wiped
after every build, so the key is regenerated from one secret passphrase each time. The passphrase lives only in GitHub's
encrypted secrets and never in the code.

1. **Add the signing secret.** On github.com go to this repo → Settings → Secrets and variables → Actions →
   **New repository secret**.
   - Name: `AIRSCOPE_SIGNING_SEED`
   - Value: a long passphrase **you make up** (20+ characters, e.g. five random words). Don't reuse a password.
     Write it down somewhere safe; you only need it again if you rebuild the secret.
2. **Rebuild.** Repo → Actions → **Build APK** → **Run workflow**. Install that build (uninstall the old one first, once).
3. **Give the app read access to the repo** (the repo is private):
   - On github.com go to Settings → Developer settings → **Fine-grained personal access tokens** → Generate new token.
   - Repository access: *Only select repositories* → `Airscope`. Permissions: **Contents → Read-only**. Pick an expiration.
   - In AirScope: MORE → **Updates…** → paste the token → **Save & check now**.
4. In the setup checklist, allow **"Install unknown apps"** for AirScope.

After that, AirScope checks for new builds about every 6 hours.
- **Not scanning:** it installs the update by itself.
- **Scanning:** it asks first with a notification, because installing restarts the app.

Every push to this repo creates a new build and release.

## GrapheneOS
- No Google Play Services, no native code, no dynamic code loading, and v2/v3 APK signatures.
- **Network permission:** scanning works with it **off**. Turn it on for mapped ALPRs, self-updates and the iPhone HUD
  (Settings → Apps → AirScope → Permissions → Network).
- Keep the system **Location** toggle on (Google location services aren't needed). Allow **Nearby devices** and **Notifications**.
- **Battery:** Unrestricted. **Wi-Fi scan throttling:** Developer options → Networking → off (optional, for faster Wi-Fi updates).

## Screen-off behavior
- A foreground service, wake lock and filtered BLE scans keep it working in your pocket.
- Android pauses *unfiltered* BLE scans with the screen off. Trackers, Flock BLE, Raven, Remote ID drones and
  Apple/Samsung devices keep updating. Generic BLE gadgets and Axon (matched by address prefix) need the screen on,
  or the app in the foreground.
- Wi-Fi and mapped-ALPR alerts keep working with the screen off.

## Web HUD on your iPhone
iPhones can't scan Wi-Fi or Bluetooth from a browser, so the Pixel scans and the iPhone displays it.
1. In AirScope tap **START**, then **HUD**. A link like `http://192.168.1.23:8787/?k=xxxxxxxxxx` appears.
2. Open it in Safari on the same Wi-Fi (or join the Pixel's hotspot).
3. Share → **Add to Home Screen**.

The HUD has the same modes and 3D radar. The `k=` key keeps other people on the network out.

## Honest limits
- **Direction:** phones can't measure where a radio signal comes from. Position around the ring is the device *type*,
  except for mapped ALPRs and drones that broadcast GPS.
- **Distance:** distance from signal strength is an estimate, so it's shown as a range. Bodies, walls and pockets change it.
  Wi-Fi RTT and GPS-based items are measured.
- **Flock cameras over the air:** phones can't capture Wi-Fi probe requests, which is how ESP32 detectors catch most of them.
  The mapped-ALPR alerts cover that gap.
- **Flags:** a flag is a reason to look closer, not proof. Fleet routers and contract-manufacturer MAC prefixes in particular
  have innocent explanations.
- **Not detectable on a phone:** cell-site simulators ("Stingrays"). That needs dedicated hardware such as EFF's Rayhunter.

## Build it yourself
- **GitHub:** every push runs `.github/workflows/build-apk.yml` and publishes `AirScope.apk` under Releases.
- **Android Studio:** open the folder → Build → Build APK(s). Local builds use the debug key and lack the full vendor tables
  unless you run `python3 tools/fetch_vendor_db.py app/src/main/assets`.

Credits and licenses for third-party data: see `NOTICE`.
