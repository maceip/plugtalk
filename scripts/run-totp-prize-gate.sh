#!/usr/bin/env bash
#
# Live ADB / USB / Wireless 3-Round OpenPGP TOTP `.onion` Prize Gate Runner for Trap (`dev.onionvault`).
#
# Usage:
#   ./scripts/run-totp-prize-gate.sh [screenshot-out.png] [path/to/app-debug.apk] [path/to/app-debug-androidTest.apk]
#
# Environment variables:
#   ADB_SERIAL      Optional ADB device serial (e.g. 10.0.0.230:36845). Auto-detected when 1 device is connected.
#   TOTP_ONION_URL  Optional override for the live 3-round OpenPGP TOTP .onion URL.
#
# Fails closed (exit 1) unless:
#   1. Both `app-debug.apk` and `app-debug-androidTest.apk` install cleanly,
#   2. `dev.onionvault.LiveOnionPgpTotpPrizeTest` passes `OK (1 test)` with zero fatal logcat crashes,
#   3. `TrapPrizeGate: PRIZE_WON_JSON=...` is emitted with `roundsCompleted === 3` and non-empty
#      `prizeBanner` + `decryptedCertificate`,
#   4. A valid non-empty PNG screenshot showing the won & decrypted prize is written to `screenshot-out.png`.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCREENSHOT_OUT="${1:-${REPO_ROOT}/dist/totp-prize/totp-prize-won.png}"
APK="${2:-${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${3:-${REPO_ROOT}/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"

PRIZE_TEST_CLASS="${PRIZE_TEST_CLASS:-dev.onionvault.LiveOnionPgpTotpPrizeTest}"
TEST_RUNNER="${TEST_RUNNER:-dev.onionvault.test/androidx.test.runner.AndroidJUnitRunner}"
DEVICE_TMP_PNG="/data/local/tmp/trap-prize-won.png"

if ! command -v adb >/dev/null 2>&1; then
    echo "::error::adb was not found on PATH" >&2
    exit 1
fi

for f in "$APK" "$TEST_APK"; do
    if [[ ! -s "$f" ]]; then
        echo "::error::Missing or empty APK: $f (build with :app:assembleDebug :app:assembleDebugAndroidTest)" >&2
        exit 1
    fi
done

SERIAL="${ADB_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
    DEVICES=()
    while IFS= read -r line; do
        [[ -n "$line" ]] && DEVICES+=("$line")
    done < <(adb devices | awk 'NR>1 && $2=="device" { print $1 }')
    if [[ "${#DEVICES[@]}" -eq 0 ]]; then
        echo "::error::No connected ADB device in 'device' state (set ADB_SERIAL or connect via adb)" >&2
        exit 1
    elif [[ "${#DEVICES[@]}" -gt 1 ]]; then
        echo "::error::Multiple ADB devices connected (${DEVICES[*]}); set ADB_SERIAL=<serial>" >&2
        exit 1
    fi
    SERIAL="${DEVICES[0]}"
fi

MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r\n')"
REL="$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r\n')"
SDK="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r\n')"
ABI="$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r\n')"

echo "== [1/4] Target device: ${SERIAL} (${MODEL}, Android ${REL} API ${SDK}, ${ABI}) =="

adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$SERIAL" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
adb -s "$SERIAL" shell cmd statusbar collapse >/dev/null 2>&1 || true
adb -s "$SERIAL" shell rm -f "$DEVICE_TMP_PNG" >/dev/null 2>&1 || true

echo "== [2/4] Installing $(basename "$APK") and $(basename "$TEST_APK") =="
adb -s "$SERIAL" install -r -t "$APK"
adb -s "$SERIAL" install -r -t "$TEST_APK"
adb -s "$SERIAL" shell "run-as dev.onionvault rm -f files/crash-java.txt files/crash-native.txt 2>/dev/null || true"
adb -s "$SERIAL" shell cmd statusbar collapse >/dev/null 2>&1 || true

adb -s "$SERIAL" logcat -G 16M >/dev/null 2>&1 || true
adb -s "$SERIAL" logcat -c >/dev/null 2>&1 || true

echo "== [3/4] Running ${PRIZE_TEST_CLASS} over live Tor hidden service =="
INST_ARGS=(shell am instrument -w -r -e class "$PRIZE_TEST_CLASS")
if [[ -n "${TOTP_ONION_URL:-}" ]]; then
    INST_ARGS+=(-e totpOnionUrl "$TOTP_ONION_URL")
fi
INST_ARGS+=("$TEST_RUNNER")

INST_LOG="$(mktemp "${TMPDIR:-/tmp}/trap-totp-inst.XXXXXX")"
LOGCAT_OUT="$(mktemp "${TMPDIR:-/tmp}/trap-totp-logcat.XXXXXX")"
trap 'rm -f "$INST_LOG" "$LOGCAT_OUT"' EXIT

set +e
adb -s "$SERIAL" "${INST_ARGS[@]}" 2>&1 | tee "$INST_LOG"
INST_EXIT="${PIPESTATUS[0]}"
set -e

adb -s "$SERIAL" logcat -d -v threadtime > "$LOGCAT_OUT" || true

mkdir -p "$(dirname "$SCREENSHOT_OUT")"
rm -f "$SCREENSHOT_OUT"
if adb -s "$SERIAL" shell "[ -s '$DEVICE_TMP_PNG' ]" >/dev/null 2>&1; then
    adb -s "$SERIAL" pull "$DEVICE_TMP_PNG" "$SCREENSHOT_OUT" >/dev/null
else
    adb -s "$SERIAL" exec-out screencap -p > "$SCREENSHOT_OUT" || true
fi

if [[ "$INST_EXIT" -ne 0 ]] || ! grep -Eq 'OK \([0-9]+ tests?\)' "$INST_LOG" || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$INST_LOG"; then
    echo "::error::${PRIZE_TEST_CLASS} failed (exit=${INST_EXIT})" >&2
    tail -n 60 "$LOGCAT_OUT" >&2 || true
    exit 1
fi

echo "== [4/4] Verifying won OpenPGP TOTP prize & PNG screenshot =="
python3 - "$LOGCAT_OUT" "$SCREENSHOT_OUT" <<'PY'
import json
import re
import sys

logcat_path, screenshot_path = sys.argv[1], sys.argv[2]

with open(screenshot_path, "rb") as fh:
    png_head = fh.read(8)
    fh.seek(0, 2)
    png_size = fh.tell()

if png_head != b"\x89PNG\r\n\x1a\n" or png_size <= 64:
    print(f"::error::Invalid or empty prize screenshot at {screenshot_path} ({png_size} bytes)", file=sys.stderr)
    sys.exit(1)

with open(logcat_path, "r", encoding="utf-8", errors="replace") as fh:
    log_text = fh.read()

if re.search(r"FATAL EXCEPTION|Fatal signal \d+\b|\*\*\* \*\*\* \*\*\*", log_text):
    print("::error::Fatal Java or native crash signal detected in logcat during prize gate", file=sys.stderr)
    sys.exit(1)

matches = re.findall(r"TrapPrizeGate\s*:[^\n]*?PRIZE_WON_JSON=(\{[^\r\n]+\})", log_text)
if not matches:
    print("::error::Missing mandatory 'TrapPrizeGate: PRIZE_WON_JSON=...' logcat record", file=sys.stderr)
    sys.exit(1)

payload = json.loads(matches[-1])
onion_url = str(payload.get("onionUrl") or "").strip()
rounds = int(payload.get("roundsCompleted") or 0)
banner = str(payload.get("prizeBanner") or "").strip()
cert = str(payload.get("decryptedCertificate") or "").strip()
winner_fp = str(payload.get("winnerFingerprint") or "").strip()

if not re.match(r"^https?://[a-z2-7]{56}\.onion(?:/.*)?$", onion_url, re.IGNORECASE):
    print(f"::error::Invalid .onion URL in PRIZE_WON_JSON: {onion_url!r}", file=sys.stderr)
    sys.exit(1)
if rounds != 3:
    print(f"::error::Expected roundsCompleted == 3, got {rounds}", file=sys.stderr)
    sys.exit(1)
if not banner:
    print("::error::PRIZE_WON_JSON prizeBanner is empty", file=sys.stderr)
    sys.exit(1)
if not cert or banner not in cert:
    print("::error::PRIZE_WON_JSON decryptedCertificate missing or does not contain prizeBanner", file=sys.stderr)
    sys.exit(1)

print("== [PRIZE WON] Trap completed 3/3 OpenPGP TOTP rounds over Tor! ==")
print(f"  Onion Service      : {onion_url}")
print(f"  Rounds Completed   : {rounds}/3")
print(f"  Winner Fingerprint : {winner_fp}")
print(f"  Prize Banner       : {banner}")
print("  Decrypted Cert     :")
for line in cert.splitlines():
    print(f"    {line}")
print(f"  Screenshot         : {screenshot_path} ({png_size} bytes)")
PY
