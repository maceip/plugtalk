#!/usr/bin/env bash
#
# Render the live Android Jetpack Compose `@Preview` homescreen (`BrowserChromePreview`)
# and companion state `@Preview` composables via Robolectric Native Graphics (Skia),
# extracting exact Compose Semantics (`getUnclippedBoundsInRoot()`) component coordinates
# into `site/generated/homescreen-sync.json` and `site/generated/assets/*.png`.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-${REPO_ROOT}/site/generated}"
mkdir -p "$OUT_DIR"

VERSION_STR="$(tr -d '[:space:]' < "${REPO_ROOT}/VERSION" 2>/dev/null || echo "0.1.43")"
COMMIT_SHA="$(git -C "${REPO_ROOT}" rev-parse --short=12 HEAD 2>/dev/null || echo "synced")"

export ONIONVAULT_SKIP_CARGO=1
export TRAP_SYNC_PAGES_DIR="$OUT_DIR"
export TRAP_SYNC_VERSION="$VERSION_STR"
export TRAP_SYNC_COMMIT_SHA="$COMMIT_SHA"

GRADLE_EXTRA_ARGS=()
if [[ -n "${TRAP_LOW_MEMORY_GRADLE:-}" ]]; then
    GRADLE_EXTRA_ARGS+=(
        --no-daemon
        --max-workers=1
        -Pkotlin.compiler.execution.strategy=in-process
        "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
    )
fi

"${REPO_ROOT}/android/gradlew" --project-dir "${REPO_ROOT}/android" \
    :app:testDebugUnitTest \
    --tests dev.onionvault.HomescreenPagesSyncTest \
    --rerun \
    "${GRADLE_EXTRA_ARGS[@]}" \
    --console=plain

test -s "${OUT_DIR}/homescreen-sync.json"
test -s "${OUT_DIR}/assets/homescreen-preview.webp"

SPRITES_SRC="${REPO_ROOT}/android/app/src/main/res/drawable-nodpi"
SPRITES_DST="${OUT_DIR}/assets/sprites"
mkdir -p "${SPRITES_DST}"

for sprite in \
    bg_trap_street_zine.webp \
    pod_crt_pewter_tv.webp \
    pod_pewter_back_key.webp \
    pod_red_switchblade.webp \
    pod_tor_purple_orb.webp \
    pod_zk_tor_remote.webp; do
    cp -f "${SPRITES_SRC}/${sprite}" "${SPRITES_DST}/${sprite}"
    test -s "${SPRITES_DST}/${sprite}"
done

echo "Synced Compose @Preview homescreen, hardware sprites & semantics manifest -> ${OUT_DIR}"
