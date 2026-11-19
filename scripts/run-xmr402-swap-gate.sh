#!/usr/bin/env bash
#
# Live ADB / USB / Wireless Zero-JS `BTC -> XMR` Swap + `XMR402` (`OutProofV2`) `.onion` Gate Runner for Trap (`dev.onionvault`).
#
# Usage:
#   ./scripts/run-xmr402-swap-gate.sh [screenshot-out.png] [path/to/app-debug.apk] [path/to/app-debug-androidTest.apk]
#
# Environment variables:
#   ADB_SERIAL         Optional ADB device serial (e.g. 67080DLDV009AD). Auto-detected when 1 device is connected.
#   XMR402_ONION_URL   Optional override for the live XMR402 + BTC->XMR Swap .onion URL.
#
# Fails closed (exit 1) unless:
#   1. Both `app-debug.apk` and `app-debug-androidTest.apk` install cleanly,
#   2. `dev.onionvault.LiveOnionXmr402BtcSwapTest` passes `OK (1 test)` with zero fatal logcat crashes,
#   3. `TrapXmr402Gate: XMR402_SWAP_WON_JSON=...` is emitted with valid `.onion` `onionUrl`,
#      non-empty Bech32 `swapBtcAddress`, `8...` `swapXmrSubaddress`, 64-hex `xmr402Txid`,
#      `OutProofV2...` `proofPrefix`, and `OBSIDIAN-MONERO-CITADEL` `prizeBanner`,
#   4. A valid non-empty PNG screenshot showing the unlocked vault prize is written to `screenshot-out.png`.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCREENSHOT_OUT="${1:-${REPO_ROOT}/dist/xmr402-swap/xmr402-swap-won.png}"
APK="${2:-${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${3:-${REPO_ROOT}/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"

XMR402_TEST_CLASS="${XMR402_TEST_CLASS:-dev.onionvault.LiveOnionXmr402BtcSwapTest}"
TEST_RUNNER="${TEST_RUNNER:-dev.onionvault.test/androidx.test.runner.AndroidJUnitRunner}"
DEVICE_TMP_PNG="/data/local/tmp/trap-xmr402-swap-won.png"

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
adb -s "$SERIAL" shell "run-as dev.onionvault rm -f files/crash-java.txt files/crash-native.txt files/xmr402-swap-won.png files/xmr402-remote-blade.png files/xmr402-wallet-qr.png 2>/dev/null || true"
adb -s "$SERIAL" shell cmd statusbar collapse >/dev/null 2>&1 || true

adb -s "$SERIAL" logcat -G 16M >/dev/null 2>&1 || true
adb -s "$SERIAL" logcat -c >/dev/null 2>&1 || true

echo "== [3/4] Running ${XMR402_TEST_CLASS} over live Tor hidden service =="
INST_ARGS=(shell am instrument -w -r -e class "$XMR402_TEST_CLASS")
if [[ -n "${XMR402_ONION_URL:-}" ]]; then
    INST_ARGS+=(-e xmr402OnionUrl "$XMR402_ONION_URL")
fi
if [[ "${CAPTURE_ERGONOMIC_SHOTS:-0}" == "1" ]]; then
    INST_ARGS+=(-e captureErgonomicShots true)
fi
INST_ARGS+=("$TEST_RUNNER")

INST_LOG="$(mktemp "${TMPDIR:-/tmp}/trap-xmr402-inst.XXXXXX")"
LOGCAT_OUT="$(mktemp "${TMPDIR:-/tmp}/trap-xmr402-logcat.XXXXXX")"
trap 'rm -f "$INST_LOG" "$LOGCAT_OUT"' EXIT

set +e
adb -s "$SERIAL" "${INST_ARGS[@]}" 2>&1 | tee "$INST_LOG"
INST_EXIT="${PIPESTATUS[0]}"
set -e

adb -s "$SERIAL" logcat -d -v threadtime > "$LOGCAT_OUT" || true

mkdir -p "$(dirname "$SCREENSHOT_OUT")"
rm -f "$SCREENSHOT_OUT"
if adb -s "$SERIAL" shell "run-as dev.onionvault test -s files/xmr402-swap-won.png" >/dev/null 2>&1; then
    adb -s "$SERIAL" exec-out run-as dev.onionvault cat files/xmr402-swap-won.png > "$SCREENSHOT_OUT"
elif adb -s "$SERIAL" shell "[ -s '$DEVICE_TMP_PNG' ]" >/dev/null 2>&1; then
    adb -s "$SERIAL" pull "$DEVICE_TMP_PNG" "$SCREENSHOT_OUT" >/dev/null
else
    adb -s "$SERIAL" exec-out screencap -p > "$SCREENSHOT_OUT" || true
fi

if [[ -n "${ERGONOMIC_REMOTE_OUT:-}" ]] && adb -s "$SERIAL" shell "run-as dev.onionvault test -s files/xmr402-remote-blade.png" >/dev/null 2>&1; then
    mkdir -p "$(dirname "$ERGONOMIC_REMOTE_OUT")"
    adb -s "$SERIAL" exec-out run-as dev.onionvault cat files/xmr402-remote-blade.png > "$ERGONOMIC_REMOTE_OUT"
fi
if [[ -n "${ERGONOMIC_WALLET_QR_OUT:-}" ]] && adb -s "$SERIAL" shell "run-as dev.onionvault test -s files/xmr402-wallet-qr.png" >/dev/null 2>&1; then
    mkdir -p "$(dirname "$ERGONOMIC_WALLET_QR_OUT")"
    adb -s "$SERIAL" exec-out run-as dev.onionvault cat files/xmr402-wallet-qr.png > "$ERGONOMIC_WALLET_QR_OUT"
fi

if [[ "$INST_EXIT" -ne 0 ]] || ! grep -Eq 'OK \([0-9]+ tests?\)' "$INST_LOG" || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$INST_LOG"; then
    echo "::error::${XMR402_TEST_CLASS} failed (exit=${INST_EXIT})" >&2
    tail -n 60 "$LOGCAT_OUT" >&2 || true
    exit 1
fi

echo "== [4/4] Verifying unlocked XMR402 + BTC->XMR swap prize & PNG screenshot =="
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
    print(f"::error::Invalid or empty XMR402 prize screenshot at {screenshot_path} ({png_size} bytes)", file=sys.stderr)
    sys.exit(1)

with open(logcat_path, "r", encoding="utf-8", errors="replace") as fh:
    log_text = fh.read()

if re.search(r"FATAL EXCEPTION|Fatal signal \d+\b|\*\*\* \*\*\* \*\*\*", log_text):
    print("::error::Fatal Java or native crash signal detected in logcat during XMR402 swap gate", file=sys.stderr)
    sys.exit(1)

matches = re.findall(r"TrapXmr402Gate\s*:[^\n]*?XMR402_SWAP_WON_JSON=(\{[^\r\n]+\})", log_text)
if not matches:
    print("::error::Missing mandatory 'TrapXmr402Gate: XMR402_SWAP_WON_JSON=...' logcat record", file=sys.stderr)
    sys.exit(1)

payload = json.loads(matches[-1])
onion_url = str(payload.get("onionUrl") or "").strip()
swap_btc_addr = str(payload.get("swapBtcAddress") or "").strip()
swap_xmr_subaddr = str(payload.get("swapXmrSubaddress") or "").strip()
xmr402_txid = str(payload.get("xmr402Txid") or "").strip()
proof_prefix = str(payload.get("proofPrefix") or "").strip()
banner = str(payload.get("prizeBanner") or "").strip()

if not re.match(r"^https?://[a-z2-7]{56}\.onion(?:/.*)?$", onion_url, re.IGNORECASE):
    print(f"::error::Invalid .onion URL in XMR402_SWAP_WON_JSON: {onion_url!r}", file=sys.stderr)
    sys.exit(1)
if not re.match(r"^(?:bcrt1|tb1|bc1)[ac-hj-np-z02-9]{6,}$", swap_btc_addr, re.IGNORECASE):
    print(f"::error::Invalid Bech32 swapBtcAddress in XMR402_SWAP_WON_JSON: {swap_btc_addr!r}", file=sys.stderr)
    sys.exit(1)
if not re.match(r"^8[1-9A-HJ-NP-Za-km-z]{94}$", swap_xmr_subaddr):
    print(f"::error::Invalid 8... swapXmrSubaddress in XMR402_SWAP_WON_JSON: {swap_xmr_subaddr!r}", file=sys.stderr)
    sys.exit(1)
if not re.match(r"^[0-9a-f]{64}$", xmr402_txid, re.IGNORECASE):
    print(f"::error::Invalid 64-hex xmr402Txid in XMR402_SWAP_WON_JSON: {xmr402_txid!r}", file=sys.stderr)
    sys.exit(1)
if not proof_prefix.startswith("OutProofV2"):
    print(f"::error::Expected proofPrefix starting with 'OutProofV2', got {proof_prefix!r}", file=sys.stderr)
    sys.exit(1)
if "OBSIDIAN-MONERO-CITADEL" not in banner:
    print(f"::error::Expected prizeBanner containing 'OBSIDIAN-MONERO-CITADEL', got {banner!r}", file=sys.stderr)
    sys.exit(1)

print("== [XMR402 + SWAP WON] Trap completed BTC->XMR swap & XMR402 OutProofV2 unlock over Tor! ==")
print(f"  Onion Service      : {onion_url}")
print(f"  Swap BTC Deposit   : {swap_btc_addr}")
print(f"  Swap XMR Subaddress: {swap_xmr_subaddr}")
print(f"  XMR402 TxID        : {xmr402_txid}")
print(f"  OutProofV2 Prefix  : {proof_prefix}")
print(f"  Prize Banner       : {banner}")
print(f"  Screenshot         : {screenshot_path} ({png_size} bytes)")
PY
