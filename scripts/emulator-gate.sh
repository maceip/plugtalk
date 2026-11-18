#!/usr/bin/env bash
#
# Emulator runtime gate: installs the APK on a booted Android device/AVD,
# launches `dev.onionvault/.MainActivity`, verifies that:
#   1. Emulator finishes full system + package manager boot (`sys.boot_completed=1`,
#      `dev.bootcomplete=1`, `pm path android` ready),
#   2. `MainActivity` starts cleanly (retrying `am start -W` once if `ActivityManager`
#      is still settling post-boot),
#   3. Cefpack initializes cleanly ("Initialized Cefpack content engine" / "the engine is ready"),
#   4. `ov_tor` binds its loopback SOCKS5 proxy (`127.0.0.1:<port>`),
#   5. The browser & Chromium child services (`PrivilegedProcessService*` /
#      `SandboxedProcessService*`) hold steady for 12s with zero Java FATAL EXCEPTIONs,
#      ANRs, native fatal signals, or CrashReporter files,
#   6. Zero UnsatisfiedLinkError / ClassNotFoundException / NoSuchMethodError / NoSuchFieldError
#      appear anywhere in logcat,
#   7. Captures a valid non-empty PNG screenshot (`emulator-launch.png`).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk}"
SCREENSHOT_PATH="${2:-${EMULATOR_SCREENSHOT_PATH:-${REPO_ROOT}/android/app/build/outputs/emulator-gate/emulator-launch.png}}"
PACKAGE="dev.onionvault"
ACTIVITY="dev.onionvault/.MainActivity"
BOOT_WAIT_SECONDS="${BOOT_WAIT_SECONDS:-240}"
WAIT_SECONDS="${WAIT_SECONDS:-90}"
STABILITY_SECONDS="${STABILITY_SECONDS:-12}"
LOG="$(mktemp "${TMPDIR:-/tmp}/trap-gate.XXXXXX.log")"
trap 'rm -f "$LOG"' EXIT

if [[ ! -f "$APK" ]]; then
    echo "::error::APK file does not exist: $APK" >&2
    exit 1
fi

wait_broadcast_idle() {
    local max_sec="${1:-45}"
    python3 - "$max_sec" <<'PY' >/dev/null 2>&1 || true
import subprocess
import sys

timeout_sec = int(sys.argv[1])
subprocess.run(
    ["adb", "shell", "am", "wait-for-broadcast-idle"],
    timeout=timeout_sec,
    stdout=subprocess.DEVNULL,
    stderr=subprocess.DEVNULL,
    check=False,
)
PY
}

echo "== waiting up to ${BOOT_WAIT_SECONDS}s for emulator/device full boot =="
adb wait-for-device
boot_ready=0
boot_elapsed=0
sys_boot=""
dev_boot=""
pm_ready=""
while (( boot_elapsed < BOOT_WAIT_SECONDS )); do
    sys_boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)"
    dev_boot="$(adb shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r\n' || true)"
    pm_ready="$(adb shell pm path android 2>/dev/null | tr -d '\r\n' || true)"
    if [[ "$sys_boot" == "1" && "$dev_boot" == "1" && "$pm_ready" == package:* ]]; then
        boot_ready=1
        break
    fi
    sleep 2
    boot_elapsed=$(( boot_elapsed + 2 ))
done

if (( boot_ready == 0 )); then
    echo "::error::Emulator/device did not reach full boot within ${BOOT_WAIT_SECONDS}s (sys.boot_completed='${sys_boot}', dev.bootcomplete='${dev_boot}', pm='${pm_ready}')" >&2
    exit 1
fi
echo "  boot ready after ${boot_elapsed}s (sys.boot_completed=1, dev.bootcomplete=1, pm=${pm_ready})"

# Wake & unlock screen so MainActivity renders visibly in the captured screenshot.
adb shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
adb shell wm dismiss-keyguard 2>/dev/null || true

echo "== installing $(basename "$APK") =="
if ! adb install -r -t "$APK" >/dev/null 2>&1; then
    echo "  PackageManager busy right after boot; retrying adb install in 3s..."
    sleep 3
    adb install -r -t "$APK" >/dev/null
fi
adb shell cmd package compile -m speed-profile -f "$PACKAGE" >/dev/null 2>&1 || true

echo "== waiting for post-boot broadcast storm to settle =="
wait_broadcast_idle 45

echo "== clearing logcat & stale crash reports =="
adb shell "run-as ${PACKAGE} rm -f files/crash-java.txt files/crash-native.txt" 2>/dev/null || true
adb logcat -G 16M 2>/dev/null || true
adb logcat -c

launch_activity_once() {
    local out rc=0
    set +e
    out="$(adb shell am start -W -n "$ACTIVITY" 2>&1)"
    rc=$?
    set -e
    printf '%s\n' "$out"
    if (( rc != 0 )); then
        return 1
    fi
    if grep -qE "^Error:|Exception|Status:[[:space:]]*timeout|unable to resolve Intent" <<< "$out"; then
        return 1
    fi
    return 0
}

echo "== launching ${ACTIVITY} =="
if ! launch_activity_once; then
    echo "  ActivityManager not ready on first launch attempt; settling for 5s and retrying ${ACTIVITY} once..." >&2
    sleep 5
    wait_broadcast_idle 15
    adb shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
    adb shell wm dismiss-keyguard 2>/dev/null || true
    adb logcat -c
    if ! launch_activity_once; then
        echo "::error::MainActivity failed to launch after retry" >&2
        exit 1
    fi
fi

echo "== waiting up to ${WAIT_SECONDS}s for Cefpack readiness + ov_tor loopback SOCKS5 bind =="
engine_ready=0
socks_bound=0
elapsed=0
while (( elapsed < WAIT_SECONDS )); do
    adb logcat -d > "$LOG" 2>/dev/null || true
    if grep -qE "the engine is ready|Initialized Cefpack content engine" "$LOG"; then
        engine_ready=1
    fi
    if grep -qE "(ov_tor: SOCKS5 listener bound to|Tor SOCKS5 listener bound on) 127\.0\.0\.1:[0-9]+" "$LOG"; then
        socks_bound=1
    fi
    if grep -qE "FATAL EXCEPTION|ANR in ${PACKAGE}|Unable to create service org\.chromium\.content\.app\.(Privileged|Sandboxed)ProcessService|Fatal signal [0-9]+|SetHeapTaggingLevel: set_tcf_on_all_threads failed|Failed to initialize Cefpack content engine|Cefpack initialization failed" "$LOG"; then
        break
    fi
    if (( engine_ready == 1 && socks_bound == 1 )); then
        break
    fi
    sleep 2
    elapsed=$(( elapsed + 2 ))
done

if (( engine_ready == 1 && socks_bound == 1 && STABILITY_SECONDS > 0 )); then
    echo "== engine + SOCKS5 ready after ${elapsed}s; holding ${STABILITY_SECONDS}s to verify browser + child-process stability =="
    held=0
    while (( held < STABILITY_SECONDS )); do
        sleep 2
        held=$(( held + 2 ))
        adb logcat -d > "$LOG" 2>/dev/null || true
        if grep -qE "FATAL EXCEPTION|ANR in ${PACKAGE}|Unable to create service org\.chromium\.content\.app\.(Privileged|Sandboxed)ProcessService|Fatal signal [0-9]+" "$LOG"; then
            break
        fi
    done
fi

adb logcat -d > "$LOG" 2>/dev/null || true

echo "== evaluating runtime gate =="
failed=0

if grep -qE "Unable to create service org\.chromium\.content\.app\.(Privileged|Sandboxed)ProcessService|Process: ${PACKAGE}:(privileged|sandboxed)_process" "$LOG"; then
    echo "::error::Chromium child process (PrivilegedProcessService / SandboxedProcessService) crash detected" >&2
    grep -E -B 2 -A 35 "Unable to create service org\.chromium\.content\.app\.(Privileged|Sandboxed)ProcessService|Process: ${PACKAGE}:(privileged|sandboxed)_process" "$LOG" | head -80 >&2 || true
    failed=1
fi

if grep -qE "FATAL EXCEPTION|ANR in ${PACKAGE}" "$LOG"; then
    echo "::error::Java exception or ANR detected" >&2
    grep -E -A 30 "FATAL EXCEPTION|ANR in ${PACKAGE}" "$LOG" | head -60 >&2 || true
    failed=1
fi

if grep -qE "Fatal signal (11|6|5|4|7|8|31)|Fatal signal [0-9]+|\*\*\* \*\*\* \*\*\*" "$LOG"; then
    echo "::error::native fatal signal detected" >&2
    grep -E -A 30 "Fatal signal|\*\*\* \*\*\* \*\*\*" "$LOG" | head -60 >&2 || true
    failed=1
fi

if grep -qE "SetHeapTaggingLevel: set_tcf_on_all_threads failed|PartitionAlloc: Failed to enable memory tagging" "$LOG"; then
    echo "::error::ARMv9 MTE / PartitionAlloc heap-tagging error detected in logcat" >&2
    grep -E -C 5 "SetHeapTaggingLevel: set_tcf_on_all_threads failed|PartitionAlloc: Failed to enable memory tagging" "$LOG" | head -60 >&2 || true
    failed=1
fi

if grep -qE "UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError" "$LOG"; then
    echo "::error::JNI/DEX linkage error (UnsatisfiedLinkError / ClassNotFoundException / NoSuchMethodError / NoSuchFieldError) detected in logcat" >&2
    grep -E -C 5 "UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError" "$LOG" | head -60 >&2 || true
    failed=1
fi

if grep -qE "Trap startup failed|Cefpack initialization failed|Failed to initialize Cefpack content engine|Failed to load libonion_vault\.so" "$LOG"; then
    echo "::error::Cefpack or native startup error logged" >&2
    grep -E -C 5 "Trap startup failed|Cefpack initialization failed|Failed to initialize Cefpack content engine|Failed to load libonion_vault\.so" "$LOG" | head -40 >&2 || true
    failed=1
fi

if adb shell "run-as ${PACKAGE} ls files" 2>/dev/null | grep -q "crash"; then
    echo "::error::CrashReporter recorded an uncaught error" >&2
    adb shell "run-as ${PACKAGE} cat files/crash-java.txt files/crash-native.txt" 2>/dev/null | head -40 >&2 || true
    failed=1
fi

if (( engine_ready == 0 )); then
    echo "::error::Cefpack never logged 'Initialized Cefpack content engine' / 'the engine is ready'" >&2
    failed=1
fi

socks_endpoint="$(grep -Eo "(SOCKS5 listener bound (to|on)) 127\.0\.0\.1:[0-9]+" "$LOG" | tail -n 1 | awk '{print $NF}' || true)"
if (( socks_bound == 0 )) || [[ -z "$socks_endpoint" ]]; then
    echo "::error::ov_tor never bound its loopback 127.0.0.1 SOCKS5 proxy in logcat" >&2
    failed=1
fi

app_pid="$(adb shell pidof "${PACKAGE}" 2>/dev/null | tr -d '\r\n' || true)"
if [[ -z "$app_pid" ]]; then
    echo "::error::${PACKAGE} process is no longer running" >&2
    failed=1
fi

mkdir -p "$(dirname "$SCREENSHOT_PATH")"
adb exec-out screencap -p > "$SCREENSHOT_PATH" 2>/dev/null || true
if ! python3 - "$SCREENSHOT_PATH" <<'PY'
import pathlib
import sys

p = pathlib.Path(sys.argv[1])
if not p.is_file() or p.stat().st_size <= 64:
    sys.exit(1)
with p.open("rb") as fh:
    if fh.read(8) != b"\x89PNG\r\n\x1a\n":
        sys.exit(1)
PY
then
    echo "::error::Failed to capture valid non-empty PNG emulator screenshot at ${SCREENSHOT_PATH}" >&2
    failed=1
fi

if [[ "$failed" != "0" ]]; then
    echo "::error::application failed the emulator gate" >&2
    tail -80 "$LOG" >&2 || true
    exit 1
fi

echo "PASS: ${PACKAGE} (pid ${app_pid}) initialized Cefpack, bound ov_tor SOCKS5 on ${socks_endpoint}, zero crashes/linkage errors (screenshot: ${SCREENSHOT_PATH})"
