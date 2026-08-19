#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
cd "$ROOT"

APP_PACKAGE="com.althmany.extractor.debug"
TEST_PACKAGE="com.althmany.extractor.debug.test"
SERVICE="$APP_PACKAGE/com.althmany.extractor.accessibility.WhatsAppAccessibilityService"
RUNNER="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"

APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SIM_APK="$ROOT/whatsapp-simulator/build/outputs/apk/debug/whatsapp-simulator-debug.apk"

# AGP normally emits the exact names above. Fall back to discovery so harmless output-name
# changes do not break the E2E harness itself.
if [[ ! -f "$APP_APK" ]]; then
  APP_APK="$(find "$ROOT/app/build/outputs/apk/debug" -type f -name '*.apk' | head -n1 || true)"
fi
if [[ ! -f "$TEST_APK" ]]; then
  TEST_APK="$(find "$ROOT/app/build/outputs/apk/androidTest/debug" -type f -name '*.apk' | head -n1 || true)"
fi
if [[ ! -f "$SIM_APK" ]]; then
  SIM_APK="$(find "$ROOT/whatsapp-simulator/build/outputs/apk/debug" -type f -name '*.apk' | head -n1 || true)"
fi

[[ -f "$APP_APK" ]] || { echo "E2E FAIL: app APK missing"; exit 1; }
[[ -f "$TEST_APK" ]] || { echo "E2E FAIL: androidTest APK missing"; exit 1; }
[[ -f "$SIM_APK" ]] || { echo "E2E FAIL: WhatsApp simulator APK missing"; exit 1; }

echo "E2E: installing WhatsApp simulator"
adb install -r "$SIM_APK"
echo "E2E: installing AL-thmany"
adb install -r "$APP_APK"
echo "E2E: installing instrumentation tests"
adb install -r "$TEST_APK"

# First bring the application out of Android's stopped state.
# IMPORTANT: do not force-stop the package after enabling Accessibility.
adb shell am force-stop "$APP_PACKAGE" || true
adb shell am start -W -n "$APP_PACKAGE/com.althmany.extractor.MainActivity" > "$ROOT/e2e-app-launch.txt" 2>&1 || {
  cat "$ROOT/e2e-app-launch.txt" || true
  echo "E2E FAIL: AL-thmany launcher failed"
  exit 1
}
cat "$ROOT/e2e-app-launch.txt"
sleep 2

echo "E2E: enabling production AccessibilityService"
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1

SETTING="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
echo "E2E: enabled_accessibility_services=$SETTING"

if [[ "$SETTING" != *"$SERVICE"* ]]; then
  echo "E2E FAIL: Accessibility setting was not accepted"
  adb shell dumpsys accessibility > "$ROOT/e2e-accessibility.txt" || true
  exit 1
fi

BOUND=0
for _ in $(seq 1 40); do
  if adb shell dumpsys accessibility 2>/dev/null | grep -Fq "$SERVICE"; then
    BOUND=1
    break
  fi
  sleep 0.5
done

adb shell dumpsys accessibility > "$ROOT/e2e-accessibility-before-test.txt" || true

if [[ "$BOUND" -ne 1 ]]; then
  echo "E2E FAIL: AccessibilityService did not bind"
  cat "$ROOT/e2e-accessibility-before-test.txt" || true
  exit 1
fi

echo "E2E: AccessibilityService enabled/bound"

# The launcher may display Android's runtime-permission activity on a clean API 35 emulator.
# Instrumentation does not depend on that UI, so continue and let the test open the simulator.
adb logcat -c

set +e
adb shell am instrument -w -r \
  -e class com.althmany.extractor.e2e.WhatsAppJoinE2ETest \
  "$RUNNER" > "$ROOT/e2e-result.txt" 2>&1
TEST_RC=$?
set -e
cat "$ROOT/e2e-result.txt"

adb logcat -d > "$ROOT/e2e-logcat.txt" || true
adb exec-out screencap -p > "$ROOT/e2e-screen.png" || true
adb shell dumpsys accessibility > "$ROOT/e2e-accessibility.txt" || true
adb shell uiautomator dump /sdcard/e2e-window.xml >/dev/null 2>&1 || true
adb pull /sdcard/e2e-window.xml "$ROOT/e2e-window.xml" >/dev/null 2>&1 || true

{
  echo "AL-thmany WhatsApp E2E Report"
  echo "============================"
  echo
  echo "Instrumentation:"
  cat "$ROOT/e2e-result.txt"
  echo
  echo "AL-thmany / WhatsApp simulator trace:"
  grep -E 'ALThmanyE2E|WhatsAppSim' "$ROOT/e2e-logcat.txt" || true
} > "$ROOT/whatsapp-e2e-report.txt"

if [[ "$TEST_RC" -ne 0 ]] || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_CODE: -1' "$ROOT/e2e-result.txt"; then
  echo "E2E FAIL: instrumentation reported a failure"
  exit 1
fi
if ! grep -Eq 'OK \([0-9]+ tests?\)' "$ROOT/e2e-result.txt"; then
  echo "E2E FAIL: no successful JUnit completion marker"
  exit 1
fi
if grep -q 'NAVIGATION_FAILURE' "$ROOT/e2e-logcat.txt"; then
  echo "E2E FAIL: next invite opened before X/Back closed the previous one"
  exit 1
fi
if ! grep -q 'CLICK_JOIN.*type=GROUP' "$ROOT/e2e-logcat.txt"; then
  echo "E2E FAIL: Join group click was not observed"
  exit 1
fi
if ! grep -q 'CLICK_REQUEST' "$ROOT/e2e-logcat.txt"; then
  echo "E2E FAIL: Request-to-join click was not observed"
  exit 1
fi
if ! grep -q 'CLICK_JOIN.*type=COMMUNITY' "$ROOT/e2e-logcat.txt"; then
  echo "E2E FAIL: Join community click was not observed"
  exit 1
fi
if ! grep -q 'CLICK_CONFIRM' "$ROOT/e2e-logcat.txt"; then
  echo "E2E FAIL: Continue/Confirm click was not observed"
  exit 1
fi
if ! grep -q 'CLOSE code=' "$ROOT/e2e-logcat.txt"; then
  echo "E2E FAIL: X/Close path was not observed"
  exit 1
fi
if ! grep -q 'BACK code=' "$ROOT/e2e-logcat.txt"; then
  echo "E2E FAIL: Back fallback path was not observed"
  exit 1
fi
if ! grep -q 'JOIN_ONLY PASS' "$ROOT/e2e-logcat.txt"; then
  echo "E2E FAIL: JOIN_ONLY result verification missing"
  exit 1
fi
if ! grep -q 'SCAN_ONLY PASS' "$ROOT/e2e-logcat.txt"; then
  echo "E2E FAIL: SCAN_ONLY no-click verification missing"
  exit 1
fi

echo "E2E PASS: JOIN_ONLY + SCAN_ONLY + Join + Request + Community + Continue + X/Back + next-link progression"
