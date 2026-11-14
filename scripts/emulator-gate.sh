#!/usr/bin/env bash
#
# The gate: the application has to start on the emulator and stay up.
#
# A unit test that passes while the application crashes on launch is worse
# than no test, because it buys false confidence.  This script is the answer
# to that: it installs the APK, starts it, and reads the log of the system
# for the two things that matter — that the application did not die, and that
# the browser became ready.
#
# The script fails when the application dies, when it hangs, or when it is
# killed by the kernel.  It never passes because the test suite passed.

set -euo pipefail

APK="${1:?the APK to test is missing}"
PACKAGE="dev.onionvault"
ACTIVITY="dev.onionvault/.MainActivity"
WAIT_SECONDS="${WAIT_SECONDS:-120}"
LOG="/tmp/trap-gate.log"

echo "== waiting for the emulator =="
adb wait-for-device
for _ in $(seq 1 60); do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        break
    fi
    sleep 2
done

echo "== installing $(basename "$APK") =="
adb install -r -t "$APK" >/dev/null

echo "== clearing the log =="
adb logcat -c

echo "== starting the application =="
adb shell am start -W -n "$ACTIVITY" || {
    echo "::error::the application did not start"
    exit 1
}

echo "== waiting ${WAIT_SECONDS}s =="
sleep "$WAIT_SECONDS"

adb logcat -d > "$LOG" 2>/dev/null || true

echo "== the verdict =="
failed=0

if grep -qE "FATAL EXCEPTION|ANR in ${PACKAGE}" "$LOG"; then
    echo "::error::the application died"
    grep -E -A 30 "FATAL EXCEPTION" "$LOG" | head -60 || true
    failed=1
fi

# A native crash of the engine is a signal, not an exception: the kernel
# writes a tombstone and the process is gone.
if grep -qE "Fatal signal (11|6|4|8)|\*\*\* \*\*\* \*\*\*" "$LOG"; then
    echo "::error::the engine crashed"
    grep -E -A 30 "Fatal signal" "$LOG" | head -60 || true
    failed=1
fi

# The crash guard of the application writes a report when the engine dies.
if adb shell "run-as ${PACKAGE} ls files" 2>/dev/null | grep -q "crash"; then
    echo "::error::the crash guard of the application caught a crash"
    adb shell "run-as ${PACKAGE} cat files/crash-native.txt" 2>/dev/null | head -40 || true
    failed=1
fi

# The browser has to become ready.
if ! grep -q "the engine is ready" "$LOG"; then
    echo "::error::the engine never became ready"
    failed=1
fi

# The screenshot of the running application.
adb exec-out screencap -p > "emulator-$(date +%s).png" 2>/dev/null || true

if [[ "$failed" != "0" ]]; then
    echo "::error::the application did not survive the gate"
    tail -80 "$LOG" || true
    exit 1
fi

echo "the application started and stayed up"
