#!/usr/bin/env bash
# Runs AirScope on an Android emulator the way a person would use it, takes screenshots, and fails
# if the app crashes, freezes (ANR) or its scan service dies. Used by CI before a build is released,
# so a broken build never auto-installs on the phone.
#
# usage: emulator_smoke.sh <AirScope.apk> <output dir>
set -u
APK="$1"
OUT="$2"
PKG=com.k.airscope
mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
: > "$SUMMARY"
FAIL=0

note() { echo "$*" | tee -a "$SUMMARY"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; note "screenshot $1"; }
alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }

# uiautomator waits for the screen to go idle; the map redraws constantly, so a dump often fails
# ("could not get idle state", ~10 s each) or leaves a truncated file. Try twice, never keep a stale or
# broken dump, record every failure, and remember where each piece of text was last seen so a button can
# still be tapped from its last known place when a dump fails.
dump_ui() {
  local try err
  for try in 1 2; do
    adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
    err=$(adb shell uiautomator dump /sdcard/ui.xml 2>&1)
    rm -f "$OUT/ui.xml"
    adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
    if python3 - "$OUT/ui.xml" "$OUT/ui-cache.tsv" <<'PY' 2>/dev/null
import re, sys, xml.etree.ElementTree as ET
path, cache = sys.argv[1:3]
root = ET.parse(path).getroot()
seen = {}
try:
    for line in open(cache, encoding="utf-8"):
        k, v = line.rstrip("\n").split("\t")
        seen[k] = v
except Exception:
    pass
for n in root.iter("node"):
    t = (n.get("text") or "").strip()
    if t and "\n" not in t and len(t) < 40:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        seen[t] = "%d %d" % ((x1 + x2) // 2, (y1 + y2) // 2)
with open(cache, "w", encoding="utf-8") as f:
    for k, v in seen.items(): f.write(k + "\t" + v + "\n")
PY
    then
      [ "$try" -gt 1 ] && echo "ui dump ok after $try tries: $err" >> "$OUT/ui-dump-errors.txt"
      return 0
    fi
    echo "ui dump try $try failed: $err" >> "$OUT/ui-dump-errors.txt"
    sleep 1
  done
  return 1
}

# Another app's "isn't responding" dialog (the emulator's own apps are slow) blocks taps: close it.
# The same dialog for AirScope is a real failure. Works on the dump already in $OUT/ui.xml.
clear_anr_dialogs() {
  for _ in 1 2 3; do
    grep -q "isn't responding" "$OUT/ui.xml" 2>/dev/null || return 0
    if grep -q "AirScope isn't responding" "$OUT/ui.xml"; then note "FAIL: AirScope isn't responding (ANR dialog)"; FAIL=1; fi
    local xy
    xy=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    if (n.get("text") or "").strip() in ("Close app", "Wait"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds"))); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
    [ -n "$xy" ] && adb shell input tap $xy
    sleep 1
    dump_ui || return 0
  done
}

# Find a view by its text (exact, then "contains") in the UI hierarchy and tap its center.
tap() {
  local want="$1" mode="${2:-exact}" long="${3:-}"
  local xy=""
  if dump_ui; then
    clear_anr_dialogs
    xy=$(python3 - "$OUT/ui.xml" "$want" "$mode" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, want, mode = sys.argv[1:4]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    t = n.get("text") or ""
    if (mode == "exact" and t.strip() == want) or (mode == "contains" and want in t):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
    if [ -z "$xy" ]; then note "  (could not find \"$want\" on screen)"; return 1; fi
  else
    # No usable dump: tap where this text was last seen (the button bar keeps its layout between steps)
    xy=$(python3 - "$OUT/ui-cache.tsv" "$want" "$mode" <<'PY' 2>/dev/null
import sys
cache, want, mode = sys.argv[1:4]
for line in open(cache, encoding="utf-8"):
    k, v = line.rstrip("\n").split("\t")
    if (mode == "exact" and k == want) or (mode == "contains" and want in k):
        print(v); break
PY
)
    if [ -z "$xy" ]; then note "  (ui dump failed and \"$want\" was never seen: skipped, see ui-dump-errors.txt)"; return 1; fi
    note "  (ui dump failed: tapping \"$want\" where it was last seen)"
  fi
  if [ -n "$long" ]; then
    set -- $xy; adb shell input swipe "$1" "$2" "$1" "$2" 900
  else
    adb shell input tap $xy
  fi
  sleep 1.5
}

screen_text() {
  dump_ui || return 0
  python3 -c "import sys,xml.etree.ElementTree as E; print('\n'.join(n.get('text') for n in E.parse(sys.argv[1]).getroot().iter('node') if n.get('text')))" "$OUT/ui.xml" 2>/dev/null
}

# GPS: lon lat (adb emu geo fix takes longitude first). Start near Dallas, GA.
LAT0=33.9212
LON0=-84.8410
gps() { adb emu geo fix "$2" "$1" >/dev/null 2>&1; }

# Walk a 30 m circle (~1.5 m/s) for N seconds
walk() {
  python3 - "$1" "$LAT0" "$LON0" <<'EOF' | while read -r la lo; do gps "$la" "$lo"; sleep 1; done
import math, sys
n, la0, lo0 = int(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3])
for t in range(n):
    a = t / 20.0
    x, y = 30 * math.sin(a), 30 * math.cos(a)
    print("%.7f %.7f" % (la0 + y / 111320.0, lo0 + x / (111320.0 * math.cos(math.radians(la0)))))
EOF
}

# Drive in a straight line at 31 m/s (~70 mph) for N seconds
drive() {
  python3 - "$1" "$LAT0" "$LON0" <<'EOF' | while read -r la lo; do gps "$la" "$lo"; sleep 1; done
import math, sys
n, la0, lo0 = int(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3])
for t in range(n):
    d = 31.0 * t
    x, y = d * math.sin(math.radians(70)), d * math.cos(math.radians(70))
    print("%.7f %.7f" % (la0 + y / 111320.0, lo0 + x / (111320.0 * math.cos(math.radians(la0)))))
EOF
}

note "== AirScope emulator smoke test =="
adb shell getprop ro.build.version.release | sed 's/^/Android /' | tee -a "$SUMMARY"
adb logcat -c
adb install -r -g "$APK" | tee -a "$SUMMARY"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_SCAN NEARBY_WIFI_DEVICES POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1 || true
done
adb shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
adb shell settings put secure location_mode 3 >/dev/null 2>&1 || true
adb shell svc wifi enable >/dev/null 2>&1 || true
# The emulator's Messages app often freezes on CI and covers the screen with a dialog.
adb shell pm disable-user --user 0 com.google.android.apps.messaging >/dev/null 2>&1 || true
gps "$LAT0" "$LON0"

note "-- launch (first run shows the setup checklist)"
adb shell am start -W -n "$PKG/.MainActivity" | grep -E "Status|TotalTime" | tee -a "$SUMMARY"
sleep 6
shot 01-setup-checklist
screen_text > "$OUT/01-setup-checklist.txt"
tap "Done"
sleep 2
shot 02-main-idle

note "-- start scanning, walk a 30 m loop for 45 s"
tap "START"
walk 45
shot 03-scanning-walking
screen_text > "$OUT/03-scanning-walking.txt"

note "-- drive at ~70 mph for 50 s (screenshots taken while moving)"
drive 50 &
DRIVE_PID=$!
sleep 22
shot 04-highway-mid
screen_text > "$OUT/04-highway-mid.txt"
sleep 18
shot 05-highway
screen_text > "$OUT/05-highway.txt"
wait $DRIVE_PID
if grep -q "mph" "$OUT/05-highway.txt" && grep -qi "inside a car" "$OUT/05-highway.txt"; then
  note "  highway detected ✓ ($(grep -o '[0-9]* mph' "$OUT/05-highway.txt" | head -1), car distance model)"
else
  note "  highway NOT detected: $(grep -E 'GPS|auto' "$OUT/05-highway.txt" | head -1)"
fi

note "-- modes, 2D view, log"
if grep -qE "tiles · [0-9]+ roads" "$OUT/03-scanning-walking.txt"; then
  note "  map tiles loaded ✓ ($(grep -oE '[0-9]+ tiles · [0-9]+ roads · [0-9]+ buildings' "$OUT/03-scanning-walking.txt" | head -1))"
else
  note "  map tiles NOT loaded: $(grep -iE 'map|tiles' "$OUT/03-scanning-walking.txt" | head -1)"
fi
note "-- scan radius: cycle through and make sure the list never empties / flickers"
BEFORE=$(screen_text | grep -c "dBm\|(mapped)")
for step in 1 2 3; do
  tap "R:" contains; sleep 2
  N=$(screen_text | grep -c "dBm\|(mapped)")
  R=$(screen_text | grep -oE "R:[A-Za-z0-9]+" | head -1)
  note "  $R -> $N rows listed (was $BEFORE)"
done
shot 05b-radius
for step in 1 2 3 4; do tap "R:" contains; sleep 1; done
note "-- search"
if tap "🔍" contains; then
  adb shell input text "flock"; sleep 1
  tap "Search"; sleep 2
  N=$(screen_text | grep -c "Flock")
  note "  search 'flock' -> $N Flock rows"
  shot 05c-search
  tap "🔍" contains; tap "Clear"
fi

note "-- SECURITY chip (last chip: swipe the chip row left first; the row sits at y~440 on the 1080x2400 emulator)"
adb shell input swipe 900 440 100 440 300; sleep 1
if tap "SECURITY"; then
  sleep 2; shot 05d-security-chip
  screen_text > "$OUT/05d-security.txt"
  grep -E "SECURITY:" "$OUT/05d-security.txt" | head -1 | sed 's/^/  /' | tee -a "$SUMMARY"
  adb shell input swipe 100 440 900 440 300; sleep 1
  tap "ALL" || note "  (could not return to ALL)"
else
  note "  (SECURITY chip not found after the swipe)"
fi

note "-- view modes"
tap "3D"; sleep 3; shot 06-view-drive
tap "DRIVE"; sleep 3; shot 07-view-radar
tap "RADAR"; sleep 3; shot 07b-view-map
tap "MAP"; sleep 2
tap "LOG"; shot 08-log
tap "RADAR"

note "-- device details (long-press the first list row)"
if tap "dBm" contains long; then sleep 1.5; shot 09-device-details; adb shell input keyevent 4; sleep 1; fi
if tap "dBm" contains; then sleep 2; shot 10-tracking; fi

note "-- updates screen + check (anonymous GitHub API)"
if tap "MORE ▾"; then
  if tap "Updates…"; then
    tap "Check now"; sleep 8
    shot 11-updates
    screen_text > "$OUT/11-updates.txt"
    grep -E "Result|Signed|one-time" "$OUT/11-updates.txt" | sed 's/^/  /' | tee -a "$SUMMARY"
    tap "Close" || adb shell input keyevent 4
  fi
fi

note "-- diagnostics + network test"
if tap "MORE ▾"; then
  if tap "Diagnostics"; then
    shot 11b-diagnostics
    tap "Test network"; sleep 12
    screen_text > "$OUT/11c-diagnostics.txt"
    shot 11c-diagnostics-network
    grep -E "Map tiles|ALPR data|Updates \(GitHub\)|reachable" "$OUT/11c-diagnostics.txt" | sed 's/^/  /' | tee -a "$SUMMARY"
    tap "Close" || adb shell input keyevent 4
  fi
fi
if tap "MORE ▾"; then
  if tap "Session log (seen again)"; then shot 11d-session-log; tap "Close" || adb shell input keyevent 4; fi
fi
note "-- names on your Wi-Fi (mDNS/SSDP receive-only; the emulator network has none, the dialog must still open)"
if tap "MORE ▾"; then
  if tap "On your Wi-Fi" contains; then
    sleep 1; shot 11e-lan-names
    screen_text > "$OUT/11e-lan-names.txt"
    grep -E "Listening|OFF|Nothing heard|can't listen" "$OUT/11e-lan-names.txt" | head -2 | sed 's/^/  /' | tee -a "$SUMMARY"
    tap "Close" || adb shell input keyevent 4
  fi
fi

note "-- web HUD"
tap "HUD"; sleep 3
URL=$(screen_text | grep -oE "http://[0-9.]+:8787/\?k=[A-Za-z0-9_-]+" | head -1)
shot 12-hud-dialog
tap "OK" || adb shell input keyevent 4
if [ -n "$URL" ]; then
  KEY="${URL##*k=}"
  adb forward tcp:8787 tcp:8787 >/dev/null
  CODE=$(curl -s -o "$OUT/hud-state.json" -w "%{http_code}" "http://127.0.0.1:8787/api/state?k=$KEY")
  BAD=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:8787/api/state?k=wrong")
  PAGE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:8787/?k=$KEY")
  note "  HUD /api/state=$CODE  page=$PAGE  wrong-key=$BAD"
  python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print('  HUD JSON ok:', len(d.get('devices',[])), 'devices, me =', d.get('me'))" "$OUT/hud-state.json" 2>&1 | tee -a "$SUMMARY"
  [ "$CODE" = "200" ] && [ "$BAD" != "200" ] || { note "FAIL: HUD did not answer correctly"; FAIL=1; }
else
  note "  (no HUD URL shown: emulator Wi-Fi may be off)"
fi
tap "HUD"

note "-- screen off for 40 s while moving (scan must keep running)"
adb shell input keyevent 26
walk 40
adb shell input keyevent 224
sleep 2
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb shell input keyevent 82 >/dev/null 2>&1 || true
sleep 2
shot 13-after-screen-off
if adb shell dumpsys activity services "$PKG" | grep -q "ScanService"; then note "  scan service still running ✓"; else note "FAIL: scan service died with the screen off"; FAIL=1; fi

note "-- stop scanning"
tap "STOP"
sleep 2
shot 14-stopped

note "== results =="
adb logcat -d -v time > "$OUT/logcat-full.txt"
adb logcat -d -b crash -v time > "$OUT/logcat-crash.txt"
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt"
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt"
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" "$OUT/gfxinfo.txt" | head -5 | sed 's/^/  /' | tee -a "$SUMMARY"
grep -E "TOTAL PSS|TOTAL:" "$OUT/meminfo.txt" | head -1 | sed 's/^/  memory /' | tee -a "$SUMMARY"
grep -E "AirScope|airscope" "$OUT/logcat-full.txt" | grep -iE " W/| E/" | grep -v "uiautomator" > "$OUT/logcat-app-warnings.txt"
note "  app warnings/errors in log: $(wc -l < "$OUT/logcat-app-warnings.txt")"
[ -f "$OUT/ui-dump-errors.txt" ] && note "  ui dump retries/failures: $(wc -l < "$OUT/ui-dump-errors.txt") (ui-dump-errors.txt)"

if grep -q "Process: $PKG" "$OUT/logcat-crash.txt" || grep -A2 "FATAL EXCEPTION" "$OUT/logcat-full.txt" | grep -q "$PKG"; then
  note "FAIL: AirScope crashed"
  grep -A30 "FATAL EXCEPTION" "$OUT/logcat-full.txt" | head -60 >> "$SUMMARY"
  FAIL=1
fi
if grep -q "ANR in $PKG" "$OUT/logcat-full.txt"; then note "FAIL: AirScope froze (ANR)"; FAIL=1; fi
if ! alive; then note "FAIL: AirScope is not running at the end"; FAIL=1; fi
[ "$FAIL" = 0 ] && note "PASS: no crashes, no freezes, scan survived screen-off"
exit $FAIL
