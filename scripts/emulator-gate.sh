#!/usr/bin/env bash
#
# Emulator runtime gate: installs the APK on a booted Android device/AVD,
# launches `dev.onionvault/.MainActivity`, verifies that Cefrium initializes
# ("the engine is ready"), and verifies that the browser process stays alive
# with zero Java exceptions, ANRs, native fatal signals, or crash reports.

set -euo pipefail

APK="${1:?APK path argument is required (e.g. scripts/emulator-gate.sh app-debug.apk)}"
PACKAGE="dev.onionvault"
ACTIVITY="dev.onionvault/.MainActivity"
WAIT_SECONDS="${WAIT_SECONDS:-90}"
STABILITY_SECONDS="${STABILITY_SECONDS:-12}"
LOG="${TMPDIR:-/tmp}/trap-gate.log"

if [[ ! -f "$APK" ]]; then
    echo "::error::APK file does not exist: $APK"
    exit 1
fi

echo "== waiting for emulator/device =="
adb wait-for-device
for _ in $(seq 1 90); do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        break
    fi
    sleep 2
done

echo "== installing $(basename "$APK") =="
adb install -r -t "$APK" >/dev/null

echo "== clearing logcat & stale crash reports =="
adb shell "run-as ${PACKAGE} rm -f files/crash-java.txt files/crash-native.txt" 2>/dev/null || true
adb logcat -c

echo "== launching ${ACTIVITY} =="
adb shell am start -W -n "$ACTIVITY" || {
    echo "::error::MainActivity failed to launch"
    exit 1
}

echo "== waiting up to ${WAIT_SECONDS}s for Cefrium engine readiness =="
engine_ready=0
elapsed=0
while (( elapsed < WAIT_SECONDS )); do
    adb logcat -d > "$LOG" 2>/dev/null || true
    if grep -q "the engine is ready" "$LOG"; then
        engine_ready=1
        break
    fi
    if grep -qE "FATAL EXCEPTION|ANR in ${PACKAGE}|Fatal signal (11|6|4|8)" "$LOG"; then
        break
    fi
    sleep 2
    elapsed=$(( elapsed + 2 ))
done

if (( engine_ready == 1 && STABILITY_SECONDS > 0 )); then
    echo "== engine ready after ${elapsed}s; holding ${STABILITY_SECONDS}s to verify stability =="
    sleep "$STABILITY_SECONDS"
fi

adb logcat -d > "$LOG" 2>/dev/null || true

echo "== evaluating runtime gate =="
failed=0

if grep -qE "FATAL EXCEPTION|ANR in ${PACKAGE}" "$LOG"; then
    echo "::error::Java exception or ANR detected"
    grep -E -A 30 "FATAL EXCEPTION|ANR in ${PACKAGE}" "$LOG" | head -60 || true
    failed=1
fi

if grep -qE "Fatal signal (11|6|4|8)|\*\*\* \*\*\* \*\*\*" "$LOG"; then
    echo "::error::native fatal signal detected"
    grep -E -A 30 "Fatal signal" "$LOG" | head -60 || true
    failed=1
fi

if adb shell "run-as ${PACKAGE} ls files" 2>/dev/null | grep -q "crash"; then
    echo "::error::CrashReporter recorded an uncaught error"
    adb shell "run-as ${PACKAGE} cat files/crash-java.txt" 2>/dev/null | head -40 || true
    failed=1
fi

if (( engine_ready == 0 )); then
    echo "::error::Cefrium never logged 'the engine is ready'"
    failed=1
fi

app_pid="$(adb shell pidof "${PACKAGE}" 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$app_pid" ]]; then
    echo "::error::${PACKAGE} process is no longer running"
    failed=1
fi

adb exec-out screencap -p > "emulator-$(date +%s).png" 2>/dev/null || true

if [[ "$failed" != "0" ]]; then
    echo "::error::application failed the emulator gate"
    tail -80 "$LOG" || true
    exit 1
fi

echo "PASS: ${PACKAGE} (pid ${app_pid}) initialized Cefrium and remained healthy"
