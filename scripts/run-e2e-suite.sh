#!/usr/bin/env bash
#
# Unified 2-Test Live Tor Hidden-Service E2E Runner for Trap (`dev.onionvault`).
#
# Runs Trap's exact two mandatory live `.onion` hidden-service E2E tests sequentially
# and fail-closed on a connected physical Android device or emulator:
#   [1/2] `dev.onionvault.LiveOnionPgpTotpPrizeTest` (`./scripts/run-totp-prize-gate.sh`)
#         3-round OpenPGP TOTP over `b36xrf3dwtizfea3z6pl2i2el2vzraju4gdddben5eqpicdswuvfgoyd.onion`
#   [2/2] `dev.onionvault.LiveOnionXmr402BtcSwapTest` (`./scripts/run-xmr402-swap-gate.sh`)
#         Zero-JS `BTC -> XMR` Swap + `XMR402` (`HTTP 402`) `OutProofV2` Unlock over
#         `6e52wfxr64n2zxjhalrldumrjuqfzjh5gbnyarrkuxrvjrusf25g6yad.onion`
#
# Usage:
#   ./scripts/run-e2e-suite.sh [out-dir] [path/to/app-debug.apk] [path/to/app-debug-androidTest.apk]
#
# Environment variables:
#   ADB_SERIAL             Optional ADB device serial (auto-detected when 1 device is connected).
#   TOTP_ONION_URL         Optional override for Hidden Service #1 URL.
#   XMR402_ONION_URL       Optional override for Hidden Service #2 URL.
#   TOTP_SCREENSHOT_OUT    Optional override for Gate #1 screenshot output path.
#   XMR402_SCREENSHOT_OUT  Optional override for Gate #2 screenshot output path.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-}"
APK="${2:-${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${3:-${REPO_ROOT}/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"

if [[ -n "$OUT_DIR" ]]; then
    mkdir -p "$OUT_DIR"
    TOTP_OUT="${TOTP_SCREENSHOT_OUT:-${OUT_DIR}/totp-prize-won.png}"
    XMR402_OUT="${XMR402_SCREENSHOT_OUT:-${OUT_DIR}/xmr402-swap-won.png}"
else
    TOTP_OUT="${TOTP_SCREENSHOT_OUT:-${REPO_ROOT}/dist/totp-prize/totp-prize-won.png}"
    XMR402_OUT="${XMR402_SCREENSHOT_OUT:-${REPO_ROOT}/dist/xmr402-swap/xmr402-swap-won.png}"
fi

# Ensure adb is discoverable even from minimal non-login shells.
if ! command -v adb >/dev/null 2>&1; then
    for sdk_cand in \
        "${ANDROID_SDK_ROOT:-}" \
        "${ANDROID_HOME:-}" \
        "${HOME:-}/Library/Android/sdk" \
        "/opt/homebrew/share/android-commandlinetools" \
        "/usr/local/lib/android/sdk"; do
        if [[ -n "$sdk_cand" && -x "${sdk_cand}/platform-tools/adb" ]]; then
            export PATH="${sdk_cand}/platform-tools:${PATH}"
            break
        fi
    done
    if ! command -v adb >/dev/null 2>&1 && [[ -x "/opt/homebrew/bin/adb" ]]; then
        export PATH="/opt/homebrew/bin:${PATH}"
    fi
fi

# If no ADB device is currently attached, auto-connect a single paired Wireless ADB mDNS target.
if [[ -z "${ADB_SERIAL:-}" ]] && command -v adb >/dev/null 2>&1; then
    ATTACHED_COUNT="$(adb devices | awk 'NR>1 && $2=="device" { count++ } END { print count+0 }')"
    if [[ "$ATTACHED_COUNT" -eq 0 ]]; then
        MDNS_ENDPOINTS=()
        while IFS= read -r ep; do
            [[ -n "$ep" ]] && MDNS_ENDPOINTS+=("$ep")
        done < <(adb mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/ && NF>=3 { print $3 }')
        if [[ "${#MDNS_ENDPOINTS[@]}" -eq 1 ]]; then
            echo "== Auto-connecting Wireless ADB mDNS device ${MDNS_ENDPOINTS[0]} =="
            adb connect "${MDNS_ENDPOINTS[0]}" >/dev/null || true
        fi
    fi
fi

now_monotonic() {
    python3 -c 'import time; print(f"{time.monotonic():.2f}")'
}

elapsed_since() {
    python3 - "$1" "$2" <<'PY'
import sys
t0, t1 = float(sys.argv[1]), float(sys.argv[2])
print(f"{max(0.0, t1 - t0):.2f}")
PY
}

SUITE_T0="$(now_monotonic)"

echo "========================================================================"
echo "== [1/2] Live Hidden-Service Gate #1: LiveOnionPgpTotpPrizeTest       =="
echo "========================================================================"
GATE1_T0="$(now_monotonic)"
"${REPO_ROOT}/scripts/run-totp-prize-gate.sh" "$TOTP_OUT" "$APK" "$TEST_APK"
GATE1_T1="$(now_monotonic)"
GATE1_SECS="$(elapsed_since "$GATE1_T0" "$GATE1_T1")"

echo ""
echo "========================================================================"
echo "== [2/2] Live Hidden-Service Gate #2: LiveOnionXmr402BtcSwapTest      =="
echo "========================================================================"
GATE2_T0="$(now_monotonic)"
"${REPO_ROOT}/scripts/run-xmr402-swap-gate.sh" "$XMR402_OUT" "$APK" "$TEST_APK"
GATE2_T1="$(now_monotonic)"
GATE2_SECS="$(elapsed_since "$GATE2_T0" "$GATE2_T1")"

SUITE_T1="$(now_monotonic)"
TOTAL_SECS="$(elapsed_since "$SUITE_T0" "$SUITE_T1")"

for shot in "$TOTP_OUT" "$XMR402_OUT"; do
    if [[ ! -s "$shot" ]]; then
        echo "::error::Missing or empty E2E screenshot after suite completion: $shot" >&2
        exit 1
    fi
done

echo ""
echo "========================================================================"
echo "2/2 Live Hidden-Service E2E Gates PASSED (${TOTAL_SECS}s total)"
echo "  [1/2] LiveOnionPgpTotpPrizeTest  : PASS (${GATE1_SECS}s) -> ${TOTP_OUT}"
echo "  [2/2] LiveOnionXmr402BtcSwapTest : PASS (${GATE2_SECS}s) -> ${XMR402_OUT}"
echo "========================================================================"
