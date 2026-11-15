#!/usr/bin/env bash
#
# Limrun cloud Android runtime & UI smoke gate wrapper (`dev.onionvault`).
#
# Usage:
#   LIM_API_KEY=lim_... ./scripts/limrun-gate.sh [path/to/app-debug.apk] [output-dir]
#
# Environment variables:
#   LIM_API_KEY / LIMRUN_API_KEY  Required. Fails closed (exit 1) when missing/empty.
#   LIMRUN_TOOLS_DIR              Isolated cache directory for @limrun/api + lim CLI.
#   LIMRUN_OS_VERSION             Android OS major version ('15' default, or '14').
#   LIMRUN_MODEL                  Device form factor ('phone' default, or 'tablet').
#   LIMRUN_BASE_URL               Optional API endpoint override (used by local test harness).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk}"
OUT_DIR="${2:-${REPO_ROOT}/dist/limrun}"

API_KEY="${LIM_API_KEY:-${LIMRUN_API_KEY:-}}"
if [[ -z "${API_KEY//[[:space:]]/}" ]]; then
    echo "::error::Missing Limrun API key: set LIM_API_KEY (or LIMRUN_API_KEY) in GitHub Actions Secrets (https://console.limrun.com/settings). Failing closed so no unverified APK can be released." >&2
    exit 1
fi
export LIM_API_KEY="$API_KEY"

if [[ ! -f "$APK" ]]; then
    echo "::error::APK file does not exist: $APK" >&2
    exit 1
fi

if ! command -v node >/dev/null 2>&1; then
    echo "::error::Node.js >= 20 is required to run scripts/limrun-gate.mjs" >&2
    exit 1
fi

TOOLS_DIR="${LIMRUN_TOOLS_DIR:-${RUNNER_TEMP:-/tmp}/trap-limrun-tools}"
LIMRUN_API_VERSION="${LIMRUN_API_VERSION:-0.73.2}"
LIM_CLI_VERSION="${LIM_CLI_VERSION:-0.57.3}"

if [[ ! -f "${TOOLS_DIR}/node_modules/@limrun/api/package.json" || ! -x "${TOOLS_DIR}/node_modules/.bin/lim" ]]; then
    echo "== Installing @limrun/api@${LIMRUN_API_VERSION} and lim@${LIM_CLI_VERSION} in ${TOOLS_DIR} =="
    mkdir -p "$TOOLS_DIR"
    (
        cd "$TOOLS_DIR"
        if [[ ! -f package.json ]]; then
            printf '{"name":"trap-limrun-tools","private":true}\n' > package.json
        fi
        npm install --no-audit --no-fund --save-exact \
            "@limrun/api@${LIMRUN_API_VERSION}" \
            "lim@${LIM_CLI_VERSION}"
    )
fi

mkdir -p "$OUT_DIR"
INSTANCE_ID_FILE="$(mktemp "${TMPDIR:-/tmp}/trap-limrun-instance.XXXXXX")"

cleanup() {
    local exit_code=$?
    if [[ -f "$INSTANCE_ID_FILE" ]]; then
        local inst_id
        inst_id="$(tr -d '[:space:]' < "$INSTANCE_ID_FILE" 2>/dev/null || true)"
        if [[ -n "$inst_id" && -x "${TOOLS_DIR}/node_modules/.bin/lim" ]]; then
            echo "== [trap-cleanup] Deleting residual Limrun Android instance ${inst_id} =="
            LIM_API_KEY="$LIM_API_KEY" \
                "${TOOLS_DIR}/node_modules/.bin/lim" android delete "$inst_id" >/dev/null 2>&1 || true
        fi
        rm -f "$INSTANCE_ID_FILE"
    fi
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

LIMRUN_TOOLS_DIR="$TOOLS_DIR" \
LIMRUN_INSTANCE_ID_FILE="$INSTANCE_ID_FILE" \
    node "${REPO_ROOT}/scripts/limrun-gate.mjs" "$APK" "$OUT_DIR"

for required_file in \
    "${OUT_DIR}/limrun-launch.png" \
    "${OUT_DIR}/limrun-logcat.txt" \
    "${OUT_DIR}/limrun-summary.json"; do
    if [[ ! -s "$required_file" ]]; then
        echo "::error::Limrun gate exited 0 without producing non-empty ${required_file}" >&2
        exit 1
    fi
done

if [[ "${LIMRUN_SKIP_ONION_SMOKE:-0}" != "1" && ! -s "${OUT_DIR}/limrun-onion.png" ]]; then
    echo "::error::Limrun gate exited 0 without producing non-empty ${OUT_DIR}/limrun-onion.png" >&2
    exit 1
fi
