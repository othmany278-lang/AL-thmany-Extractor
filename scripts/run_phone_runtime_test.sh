#!/usr/bin/env bash
set -euo pipefail

APP="com.althmany.extractor"
COMPONENT="$APP/com.althmany.extractor.MainActivity"
SERVICE="$APP/com.althmany.extractor.accessibility.WhatsAppAccessibilityService"

echo "PHONE: emulator boot completed"

adb wait-for-device

BOOT="$(adb shell getprop sys.boot_completed | tr -d '\r')"
echo "sys.boot_completed=$BOOT" | tee "$GITHUB_WORKSPACE/phone-emulator-boot.txt"

if [[ "$BOOT" != "1" ]]; then
    echo "PHONE FAIL: emulator is connected but boot is incomplete"
    exit 1
fi

echo "PHONE: installing APK=$PHONE_APK"
adb install -r "$PHONE_APK"

: > "$GITHUB_WORKSPACE/phone-startup.txt"
: > "$GITHUB_WORKSPACE/phone-logcat.txt"

# ------------------------------------------------------------
# Cold-start test x3
# ------------------------------------------------------------
for ROUND in 1 2 3; do

    echo "===== COLD START $ROUND =====" \
        | tee -a "$GITHUB_WORKSPACE/phone-startup.txt"

    adb shell am force-stop "$APP" || true
    adb logcat -c

    adb shell am start -W -n "$COMPONENT" \
        | tee -a "$GITHUB_WORKSPACE/phone-startup.txt"

    sleep 4

    PID="$(adb shell pidof "$APP" | tr -d '\r' || true)"

    echo "pid=$PID" \
        | tee -a "$GITHUB_WORKSPACE/phone-startup.txt"

    if [[ -z "$PID" ]]; then
        adb logcat -d >> "$GITHUB_WORKSPACE/phone-logcat.txt" || true

        echo "PHONE FAIL: process stopped on cold start $ROUND"
        exit 1
    fi

    adb logcat -d >> "$GITHUB_WORKSPACE/phone-logcat.txt" || true

    if adb logcat -d | grep -E \
        "FATAL EXCEPTION|ANR in com\.althmany\.extractor|Process: com\.althmany\.extractor.*has died"
    then
        echo "PHONE FAIL: crash/ANR during cold start $ROUND"
        exit 1
    fi

done

echo "PHONE: three cold starts passed"

# ------------------------------------------------------------
# Accessibility test
# ------------------------------------------------------------

echo "PHONE: enabling AccessibilityService"

adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1

SETTING="$(
    adb shell settings get secure enabled_accessibility_services \
    | tr -d '\r'
)"

echo "enabled_accessibility_services=$SETTING" \
    | tee "$GITHUB_WORKSPACE/phone-accessibility-setting.txt"

if [[ "$SETTING" != *"$SERVICE"* ]]; then
    echo "PHONE FAIL: Android did not accept Accessibility component"

    adb shell dumpsys accessibility \
        > "$GITHUB_WORKSPACE/phone-accessibility.txt" || true

    exit 1
fi

BOUND=0

for _ in $(seq 1 100); do

    adb shell dumpsys accessibility \
        > "$GITHUB_WORKSPACE/phone-accessibility.txt" || true

    if sed -n \
        '/Bound services:/,/Enabled services:/p' \
        "$GITHUB_WORKSPACE/phone-accessibility.txt" \
        | grep -q "WhatsAppAccessibilityService"
    then
        BOUND=1
        break
    fi

    sleep 0.25
done

if [[ "$BOUND" -ne 1 ]]; then

    echo "PHONE FAIL: Accessibility enabled but service did not bind"

    cat "$GITHUB_WORKSPACE/phone-accessibility.txt" || true

    adb logcat -d \
        > "$GITHUB_WORKSPACE/phone-logcat-final.txt" || true

    exit 1
fi

echo "PHONE: AccessibilityService bound"

# ------------------------------------------------------------
# Final health verification
# ------------------------------------------------------------

adb logcat -d \
    > "$GITHUB_WORKSPACE/phone-logcat-final.txt" || true

adb exec-out screencap -p \
    > "$GITHUB_WORKSPACE/phone-screen.png" || true

PID="$(adb shell pidof "$APP" | tr -d '\r' || true)"

if [[ -z "$PID" ]]; then
    echo "PHONE FAIL: app stopped after Accessibility binding"
    exit 1
fi

if grep -E \
    "FATAL EXCEPTION|ANR in com\.althmany\.extractor|Process: com\.althmany\.extractor.*has died" \
    "$GITHUB_WORKSPACE/phone-logcat-final.txt"
then
    echo "PHONE FAIL: crash/ANR after Accessibility binding"
    exit 1
fi

{
    echo "AL-thmany PHYSICAL PHONE Runtime"
    echo "================================"
    echo "Package: $APP"
    echo "PID: $PID"
    echo "Cold start x3: PASS"
    echo "Accessibility setting: PASS"
    echo "Accessibility bound: PASS"
    echo "Process alive after bind: PASS"
    echo "Crash/ANR scan: PASS"
    echo "Result: READY FOR REAL PHONE TEST"
} | tee "$GITHUB_WORKSPACE/phone-runtime-report.txt"

echo "✅ PHONE RUNTIME TEST PASSED"
