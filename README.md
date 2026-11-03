# AirScope: passive Wi-Fi + Bluetooth radar for Android

A personal signal-observation app for the Pixel 8 Pro (Android 10+, GrapheneOS-friendly).
It **only listens**. It reads Wi-Fi beacons and Bluetooth LE advertisements that devices already
broadcast. It never connects to, jams, deauths or transmits to anything.

## What's new in v2.2: GPS, placement, heading, details
- **GPS built for speed:**
  - Full-rate (0.5 s), high-accuracy, dual-band (L1+L5) GPS with raw measurement tracking turned on.
  - A Kalman filter smooths the fix, rejects jumps and predicts your position between fixes, so the map moves
    smoothly at 70+ mph instead of stepping. The status line shows accuracy, satellites used, bands and speed.
- **Device placement:**
  - Every reading is stamped with the moment the radio actually heard it (not when Android delivered it) and matched
    to where you were at that instant. At highway speed that alone cuts placement error by about two thirds.
  - The locator also learns each device's signal strength, uses Wi-Fi RTT (measured distance) when the access point
    supports it, and breaks left/right-of-road ties with the street map. When two spots fit equally well it shows both
    (dashed line) instead of guessing.
  - Things that move with you (your car, a passenger's phone, a following tracker) are recognized and not "placed".
- **Which way you're facing:**
  - A fused gyro + compass heading converted to *true* north, reading the back camera when you hold the phone up and
    the top edge when it's flat.
  - The cone on the map is as wide as the compass uncertainty, and the app warns about magnetic interference or
    calibration.
  - Tracking a device gives "turn left 30°" / "dead ahead" guidance on foot and "at 2 o'clock" while driving (the map
    turns with your GPS course at speed, since a compass inside a car is unreliable).
- **More device detail:**
  - Signal stats and advertising interval, connectable / long-range Bluetooth, and AirPods battery levels.
  - iBeacon / Eddystone decoding, Fast Pair and Swift Pair.
  - Wi-Fi AP name, number of connected clients and channel load, country, roaming and Passpoint support.
  - How many access points share a network name, first seen and days seen, and the building a device is likely in.

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
- **Live street map (v2.1):** OpenStreetMap roads, street names, buildings, water and parks around your GPS position,
  in 2D or a tilted **3D** view with raised buildings. You're the blue dot with an accuracy circle and heading cone.
  - **Mapped ALPRs** sit at their real positions with a cone showing which way the camera faces. Remote ID drones sit
    where they report being.
  - **Radios are placed on the map from your movement.** A phone can't tell which direction a signal comes from, so
    AirScope records signal strength at each GPS spot as you walk or drive and works out where the device must be.
    That's the same idea wardriving tools use.
    - Solid pin + error circle = located.
    - Hollow marker = not located yet. It sits at the right *distance* but a placeholder direction. Walk 10–20 m in a
      couple of directions to pin it down.
    - Selecting an unlocated device shows its distance band as dashed rings around you.
  - **Gestures (like a maps app):**
    - One finger: pan, with fling.
    - Pinch: zoom. Double-tap: zoom in.
    - Two-finger twist: rotate.
    - Two-finger drag up/down: tilt, in 3D.
    - Tap: select.
  - **Map buttons:** ◎ follows you again, N/⬆ switches north-up or heading-up, and ＋/－ zoom.
  - Map data downloads once per area (about 1.5 km) and is cached for offline use. It needs the Network permission on GrapheneOS.
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

## Automatic updates (one-time setup, about 3 minutes on your phone)
Android only installs an update signed with the **same key** as the installed app. GitHub's build machines are wiped
after every build, so the key is regenerated from one secret passphrase each time. The passphrase lives only in GitHub's
encrypted secrets and never in the code. Builds made without it are published as **pre-releases** marked "one-time key",
and the updater ignores them.

1. **Add the signing secret** (in AirScope: MORE → Updates… → **SET SECRET** opens the page).
   - Name: `AIRSCOPE_SIGNING_SEED`
   - Value: a long passphrase **you make up** (20+ characters, e.g. five random words). Don't reuse a password.
     Keep a copy somewhere safe: if you ever change it, you'll have to reinstall once more.
2. **Rebuild.** Repo → Actions → **Build APK** → **Run workflow** (or push any change).
3. **Reinstall once.** Uninstall AirScope, then install the new build from **Releases** (not marked "one-time key").
4. **Give the app read access to the repo** (the repo is private). In AirScope: MORE → **Updates…**
   - **CREATE TOKEN** opens GitHub with everything filled in (name, Contents: Read-only, 1-year expiry).
   - Set Repository access → *Only select repositories* → `Airscope`, tap **Generate token**, then copy it.
   - Back in AirScope tap **PASTE**, then **Save & check**. "Up to date" means it works.
5. In the setup checklist, allow **"Install unknown apps"** for AirScope.

The Updates screen tells you if the installed copy uses a one-time key, if the token is missing, expired, or can't see the
repo, and if there's no auto-installable build yet.

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
- **Direction:** phones can't measure where a radio signal comes from. Devices get a map position only after you've
  measured them from spots far enough apart (walking a loop works best; a straight line can't tell which side of the
  road something is on, so it stays uncertain). Mapped ALPRs and Remote ID drones are exact.
- **GPS:** a phone fix is typically 1–3 m under open sky and worse in cities, tunnels or deep inside a car.
  Nothing on a phone makes it "perfect"; the filter makes it steadier and honest about its error.
- **Heading:** compasses are thrown off by cars, steel and magnetic cases. The app detects this and widens the cone,
  but it can't remove the interference.
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
