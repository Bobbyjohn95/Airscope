#!/usr/bin/env bash
# Local check without the Android SDK (works in a sandbox that can't reach Google's servers):
# compiles the app with the Kotlin compiler against a stub android.jar, then runs the JVM test suites.
# CI runs the same tests through Gradle; a release is only published when they pass.
#
# usage: tools/dev_check.sh [bench]      (from the repo root)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${AIRSCOPE_WORK:-$HOME/.airscope-dev}"
mkdir -p "$WORK"

# 1) android.jar (API 35) from a public mirror of the SDK platform jars (GitHub, no Google login needed)
AJ="$WORK/android-platforms/android-35/android.jar"
if [ ! -f "$AJ" ]; then
  echo "fetching android.jar (API 35) from github.com/Sable/android-platforms…"
  git clone -q --filter=blob:none --no-checkout https://github.com/Sable/android-platforms "$WORK/android-platforms"
  (cd "$WORK/android-platforms" && git sparse-checkout set android-35 && git checkout -q)
fi

# 2) Kotlin compiler: use kotlinc if installed, else the jars shipped with a Gradle install
if command -v kotlinc >/dev/null 2>&1; then
  KC="kotlinc"
else
  L="${GRADLE_LIB:-/opt/gradle/lib}"
  CP=$(ls "$L"/kotlin-compiler-embeddable-*.jar "$L"/kotlin-stdlib-2*.jar "$L"/kotlin-script-runtime-*.jar "$L"/kotlin-reflect-*.jar "$L"/trove4j-*.jar "$L"/annotations-*.jar "$L"/kotlinx-coroutines-core-jvm-*.jar 2>/dev/null | tr '\n' ':')
  [ -n "$CP" ] || { echo "No Kotlin compiler found (install kotlinc or set GRADLE_LIB)"; exit 2; }
  KC="java -Xmx2g -cp $CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect"
fi
STD=$(ls "${GRADLE_LIB:-/opt/gradle/lib}"/kotlin-stdlib-2*.jar 2>/dev/null | head -1 || true)
[ -n "$STD" ] || STD=$(find / -name "kotlin-stdlib-2*.jar" 2>/dev/null | head -1)

APP="$WORK/app-classes"; TST="$WORK/test-classes"
rm -rf "$APP" "$TST"
cp "$ROOT/tests/jvm/R.kt.stub" "$WORK/R.kt"
echo "compiling app…"
$KC -jvm-target 17 -cp "$STD:$AJ" -d "$APP" "$ROOT"/app/src/main/java/com/k/airscope/*.kt "$WORK/R.kt" 2>&1 | grep -v "Picked up" | grep -E "error|warning: unre" && { echo "COMPILE FAILED"; exit 1; } || true
echo "compiling tests…"
$KC -jvm-target 17 -cp "$STD:$AJ:$APP" -d "$TST" "$ROOT"/tests/jvm/logic/LogicTest.kt "$ROOT"/tests/jvm/tiles/VectorTileTest.kt "$ROOT"/tests/jvm/bench/LocatorBench.kt 2>&1 | grep -v "Picked up" | grep -E "error" && { echo "TEST COMPILE FAILED"; exit 1; } || true

run_suite() { # name, main class
  echo "== $1"
  set +e
  java -cp "$TST:$APP:$STD" "$2" > "$WORK/$2.log" 2>&1
  local rc=$?
  set -e
  grep -E "FAIL|PASSED|ALL PASS|FAILURE" "$WORK/$2.log" || true
  [ $rc -eq 0 ] || { echo "$1 FAILED (see $WORK/$2.log)"; exit 1; }
}
run_suite "logic tests" airscope.tests.logic.LogicTestKt
run_suite "vector tile tests" airscope.tests.tiles.VectorTileTestKt
if [ "${1:-}" = "bench" ]; then
  echo "== placement benchmark (compare against the numbers in AGENTS.md)"
  java -cp "$TST:$APP:$STD" airscope.tests.bench.LocatorBenchKt 2>&1 | grep -v "Picked up"
fi
echo "ALL CHECKS PASSED"
