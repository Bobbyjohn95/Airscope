# AirScope: passive Wi-Fi + Bluetooth radar for Android

A personal signal-observation app for the Pixel 8 Pro (Android 10+, GrapheneOS-friendly).
It **only listens**. It reads Wi-Fi beacons and Bluetooth LE advertisements that devices already
broadcast. It never connects to, jams, deauths or transmits to anything.

## What's new in v2.9: security part 2 (places, disruption watch, pairing spam, report)
- **Places (MORE → Places: home / work):** mark where you are (150 m around the spot). After you've been there on 3
  different days AirScope knows what's normally there; from then on a device with a stable identity that was never
  seen there, present for 3+ minutes, gets a **"new here"** alert (SECURITY chip, `sec:new`). Visitors' phones use
  random addresses and can't be remembered, so this is about new fixed gear (a camera, a hub, a tag left behind), not
  people. Everything stays on the phone.
- **Wi-Fi disruption watch:** when all the networks that were steadily visible vanish at once while you're standing
  still, or your own Wi-Fi connection drops 3+ times in 10 minutes while still, you get a notification and a Security
  log entry with time and place. A phone can't see deauth frames, so it says what it is: an indicator with ordinary
  explanations too (power cut, flaky router), never proof.
- **Bluetooth pairing spam:** a flood of 15+ brand-new "AirPods / Fast Pair / Swift Pair" addresses within 10 s (the
  Flipper-style pop-up prank) raises an alert with the strongest signal level as the best guess of how close the
  source is. The packets themselves can't say who sent them.
- **Security log & report (MORE → Security log):** all such events this session, plus **Save report**: a plain-text
  file in Downloads/AirScope with the events and every device that currently has a security finding (what, when,
  where, signal) to keep or send on.
- MORE → On your Wi-Fi now also lists names heard earlier in the session, not only the last 10 minutes.

## What's new in v2.8: security part 1 (attack tools, evil twins, network grades, "following you")
- **SECURITY chip** (last chip in the row; scroll the row) shows only devices with a security finding. Search terms:
  `sec:attack`, `sec:evil`, `sec:weak`, `sec:follow`, or `sec:` for all of them. The web HUD has the same chip.
- **Attack / audit hardware:** Wi-Fi Pineapple (its default "Pineapple_XXXX" name only when the digits match its
  address, or its address block), ESP8266 Deauther ("pwned"), ESP32 Marauder (default name), O.MG cable implants,
  Flipper Zero (Bluetooth name), Pwnagotchi and Wi-Fi phishing-tool names are labelled and alert at MEDIUM. Honest
  limit: these are the names and addresses the tools use out of the box. Anyone can name a network "pwned", and a
  pentester's Pineapple at a conference is not an attack on you; the text says so. "Pineapple Cafe", "Pineapple WiFi"
  and a school's "Marauders" network are not flagged (at most a low-score note).
- **Evil twins / rogue access points:** AirScope now remembers networks you spend 10+ minutes near on two or more
  days (name, strongest security it used, address blocks of its access points, where; saved on the phone only, expires
  after 60 days unseen; generic names like "Guest" are never learned). Within 2 km of where you learned it, an access
  point claiming that name with weaker security than the network ever had (your WPA2 home network showing up open)
  scores 45 and alerts; a known name from an address block never seen for it is noted at 15 (a new router or mesh
  node more often than a copy). A network name seen open and secured at the same time is noted (25) but does not
  alert on its own: venues do that on purpose.
- **Network security grade** in every Wi-Fi row and in details: A WPA3 · B WPA2 or OWE · C WPA1 or TKIP-only · D WEP
  · F open; WPS advertised lowers the grade one step (older routers: PIN attack risk). The reason is in plain words.
  WPA1, WEP and open networks count as security findings (`sec:weak`).
- **"Following you" for any device, not just trackers:** a phone, earbuds or an unknown Bluetooth device that appeared
  3+ separate times at places 1.5 km apart in one session is tagged and alerts at MEDIUM; merely riding along with you
  (a passenger's phone) is noted but does not alert, and nothing alerts while many devices are travelling with you
  (bus, train, a group). Security alerts follow the Alerts setting: "critical only" turns them off.

## What's new in v2.7: who a device belongs to, one device across address changes, names on your Wi-Fi
- **Owner names:** when a device's own name says whose it is ("Sarah's AirPods", "Mike-iPhone", "iPhone de Juan"), the
  list shows **👤 Sarah** and the details say so. It is only what the owner typed into their device: nothing is looked
  up anywhere, business names ("McDonald's WiFi") are ignored, and a device named "Office" stays unknown. Search with
  `owner:sarah` (or `owner:` for anything with a named owner) and WATCH it like any other term.
- **One device, even when its address changes:** phones, earbuds and hotspots change their random Bluetooth/Wi-Fi
  address every so often; each change used to look like a brand-new device and reset "stayed with you for 40 min".
  Now a device that goes quiet and is replaced within 45 s by exactly one new address with the same name, maker and type
  is kept as one device: presence time and sightings carry over, and the old addresses are listed under "Earlier
  addresses". Watch/ignore and "mine" follow it only when its name is personal ("K's AirPods"), never on a default name
  like "Galaxy Buds2" that a stranger's device could share. If any other device with the same name and maker was heard
  around the change, nothing is merged. Only phone hotspots are linked on Wi-Fi (mesh and guest networks never are).
- **Names on your Wi-Fi (MORE → On your Wi-Fi):** while the phone is on a Wi-Fi network, AirScope receives the
  Bonjour/UPnP announcements devices on that network send anyway ("Johns-MacBook-Pro", "Living Room TV", a printer, a
  Sonos) and lists them with their model and owner. **Receive-only:** it joins the two multicast groups (which tells
  your router to forward them: the standard way to hear these) and never sends a query or connects to anything. Needs
  Android's multicast permission, which is granted at install with no prompt, and costs some battery while scanning on
  Wi-Fi; turn it off in that dialog if you don't want it. A Bluetooth device whose name matches a
  name on your Wi-Fi gets an "On your Wi-Fi" line in its details. Works only on the network you're joined to; shows
  nothing on mobile data.
## What's new in v2.6.1: dots stay on the map
- **Dots that kept disappearing:** a located device's marker was being taken off the map while its row stayed in
  the list, by a single "it's moving" reading (a wall or a passing truck weakening the signal) or because the hold
  ran out while the device was still right there. Now: a device known to stay put (Wi-Fi networks, IoT gear) keeps
  its dot as long as it's still heard and you're standing still, and for 90 s otherwise; one odd reading no longer
  counts, only a change that persists. Phones and other things that can walk off still lose their old spot after
  30 s (the honest answer: their old spot goes stale). Placement accuracy is unchanged.
- The map rests behind dialogs (1 frame/s) so a search can't be starved by a busy map.

## What's new in v2.6: scan radius that behaves, priorities, search and watch rules
- **Scan radius fixed:** devices near the edge no longer blink in and out (a device must be clearly outside to
  leave and clearly inside to return), and a device you're tracking never vanishes: outside the radius it stays in the
  list, dimmed, with "(outside radius)". Long-press the R: button to type an exact radius.
- **Priorities:** long-press a row → Options → **★ Watch** (always first, alerts whenever it appears) or **🔇 Ignore**
  (muted). Saved across sessions.
- **Search (🔍 chip):** filter list and map by name, maker, MAC, keyword or `cat:tracker`. Tap **WATCH** to save the
  term as a rule: anything matching it is starred and alerts. Manage rules under MORE → Watch rules.
- **Alert level** (MORE): critical only / normal / everything MEDIUM and up.
- For contributors and AI coworkers: `AGENTS.md` (rules), `tools/dev_check.sh` (local tests), logic tests now run in
  CI before every build.

## What's new in v2.5: markers stay put at every zoom, scan radius
- **Zoom fix:** markers used to float above their spot by an amount that grew with zoom-out, so a device looked
  farther away the more you zoomed out. Markers now sit exactly on their ground spot at every zoom (the 3D stem is a
  fixed screen height).
- **No made-up spots on the street map:** a device whose direction isn't known yet is no longer drawn at a random
  angle. It stays in the list (and in RADAR view at its true distance); tapping it shows distance rings. The map says
  how many are waiting to be placed.
- **Steady placement:** once a device is located its marker stays on and glides to new estimates instead of blinking
  off and on or jumping between readings; a good fix is held for 30 s while readings are momentarily poor.
- **Scan radius (RADIUS button):** ALL, 25 m, 50, 100, 250, 500, 1 km. Devices farther away are hidden and never
  alert; the map draws the radius and zooms to it.

## What's new in v2.4: fast map, honest placement, steady compass, diagnostics
- **New map engine:** OpenFreeMap vector tiles (pre-built, CDN-served) instead of live Overpass queries. Overpass
  allows each internet address only two queries at a time, and cell carriers put many customers behind one address,
  which is why the map often failed off Wi-Fi. Tiles are saved on the phone (places you've been work with no signal),
  and **MORE → Offline map** saves 3/9/19 miles around you in one go. Overpass remains only as a fallback.
- **Route prediction while driving:** follows the road you're on through bends and junctions and preloads the map
  ahead (dashed line). It predicts roads only, never devices.
- **View modes (VIEW button):** RADAR (no map) · MAP (flat) · 3D (city) · DRIVE (turns with the road, zooms with
  speed, looks ahead).
- **Device placement rebuilt and measured** on 1,000 simulated walks and drives with realistic radio fading:
  "way off" placements fell from 86 to 8, and the error circles are now honest (the truth is inside them most of the
  time instead of 20%). Devices that move, or are only heard faintly, or were passed on one straight road, are no
  longer pinned to a wrong spot: they show distance or "2 possible spots" instead.
- **Facing direction:** gyro-stabilized compass. The magnetometer is only used while the magnetic field matches
  Earth's (strength and dip angle for where you are), so cars, magnetic mounts and steel no longer make it spin.
  GPS travel direction takes over while driving.
- **POLICE/EMS/ALPR mode only shows** police, fire, EMS, law-enforcement gear and traffic/ALPR cameras. Vehicle
  routers (also used by delivery, transit and utility fleets) count only when their network name says police, sheriff,
  fire, EMS etc.; unknown fleets go to VEHICLES.
- **Session log:** devices that disappear for 3+ minutes and come back in the same session get **↻N**; MORE →
  Session log lists everything seen since START (also exported as CSV).
- **Diagnostics (MORE → Diagnostics, or tap the map's ⓘ warning):** permissions, GPS, internet (Data Saver,
  GrapheneOS Network permission), map tiles, radios, compass, HUD, updates, a live network test and "Copy report".
- **Closing:** removing AirScope from Recents stops everything (scan, GPS, HUD, notification). Going to the home screen
  keeps it scanning. MORE → Exit also stops everything.

## What's new in v2.3: tested on a real Android emulator, silent updates, fixes
- **Every build is tested before it reaches your phone.** GitHub runs it on an Android 15 emulator: permissions,
  walking and 70 mph GPS, every main screen, the web HUD, the updater and screen-off scanning. A build that crashes
  or freezes is never released, so the auto-updater can't install a broken AirScope.
- **Updates are really automatic now:** no "Install?" prompt. They install when you leave the app or stop scanning,
  never while you're looking at it or mid-scan, and scanning resumes when you reopen it.
- **Map touch fixed:** two-finger twist and tilt now stay where you put them (they used to spring back). The N button
  returns a hand-rotated map to north. The map redraws at full speed only while something moves.
- **ENV:AUTO (new default):** car model while driving, outdoor/indoor from GPS quality. Tap ENV to pick one yourself.
- **GPS:** after a tunnel or underpass the position restarts at the first good fix instead of lagging or overshooting.
- **Fewer false alarms:** GPS jitter while standing still no longer counts as "moved with you", and a tag seen this
  morning and again this afternoon no longer counts as "stayed with you".
- **Robustness:** a malformed request to the web HUD or a crafted drone broadcast can no longer crash or freeze the
  app; alerts can't fill up Android's notification limit; BLE recovers by itself after a scan error.

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
1. On the Pixel, open https://github.com/Bobbyjohn95/Airscope/releases/latest, tap **AirScope.apk**, and open it from Files.
   (That link always points to the newest build that can update itself; builds marked "one-time key" can't.)
   Allow installs from Files when asked. Play Protect may warn about an unknown developer; that's expected for your own build.
2. Open AirScope. The setup checklist appears: tap **FIX** on each item. It covers Location, Nearby devices, Notifications,
   battery unrestricted, Location and Bluetooth on, and allowing it to install its own updates.
3. Tap **START**.

**Coming from a "one-time key" build (2–8):** uninstall the old AirScope first (it was signed with a different key),
then install the new one. This is needed only once.

## Automatic updates (one-time setup on github.com, no password in the app)
Android only installs an update signed with the **same key** as the installed app. GitHub's build machines are wiped
after every build, so the key is regenerated from one secret passphrase each time. The passphrase lives only in GitHub's
encrypted secrets and never in the code. Builds made without it are published as **pre-releases** marked "one-time key",
and the updater ignores them.

1. **Add the signing secret** (in AirScope: MORE → Updates… → **SET SECRET** opens the page).
   It must be under Settings → Secrets and variables → **Actions** → *Repository secrets*
   (not Environments, Codespaces or Dependabot).
   - Name: `AIRSCOPE_SIGNING_SEED` (exactly)
   - Value: a long passphrase **you make up** (20+ characters). You never type it into the app.
     Keep a copy somewhere safe: if you ever change it, you'll have to reinstall once more.
2. **Make the repo public** so the app can download builds without a token: repo → Settings → General →
   Danger Zone → **Change visibility → Public**. Nothing secret is in the code; the signing passphrase stays private.
3. **Rebuild.** Repo → Actions → **Build APK** → **Run workflow** (or push any change).
4. **Reinstall once.** Uninstall AirScope, then install the new build from **Releases** (not marked "one-time key").
5. In the setup checklist, allow **"Install unknown apps"** for AirScope.

The Updates screen shows whether the installed copy has the permanent key, and why a check failed (repo private, no
auto-installable build yet, rate limit). A private repo still works if an older version saved a GitHub token.

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
