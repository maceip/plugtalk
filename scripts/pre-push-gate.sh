#!/usr/bin/env bash
#
# Mandatory fail-closed local CI gate for Trap (`dev.onionvault`).
#
# Invoked automatically by `.githooks/pre-push` before any non-`[skip ci]` push
# (and runnable directly via `./scripts/pre-push-gate.sh`).
#
# Enforces 5 strict stages before code can reach GitHub:
#   [1/5] Shell & hook syntax (`bash -n`) + Node syntax + Limrun protocol gate self-test (`7/7`)
#   [2/5] Monotonic `VERSION` dry-run + deterministic 1024x1024 & neon icon drift checks
#   [3/5] Zero legacy banned engine references repo-wide + `TrapApplication.kt` child-process
#         `CefpackEngine.initProcess(this)` `ContextImpl` `ClassCastException` guard
#   [4/5] Rust workspace + `services/pgp-totp-onion` + `services/xmr402-swap-onion` formatting,
#         Clippy (`-D warnings`), and tests
#   [5/5] Android JVM/Robolectric unit tests, multi-ABI debug + androidTest APK assembly, and
#         `5/5` `scripts/audit-apk.sh` packaging/ELF/DEX/signature binary audit

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
export GIT_PAGER=cat

# Ensure `.githooks` is active in repository git config whenever the gate runs.
CURRENT_HOOKS_PATH="$(git config --get core.hooksPath 2>/dev/null || true)"
if [[ "$CURRENT_HOOKS_PATH" != ".githooks" ]]; then
    git config core.hooksPath .githooks || true
fi

# Auto-discover Rust (`rustup` Android targets + `cargo-ndk`), Node.js, JDK, Python (`PIL`+`numpy`),
# and Android SDK/NDK so `git push` succeeds deterministically from IDEs, GUI clients, or minimal shells.
discover_pre_push_env() {
python3 - <<'PY'
from __future__ import annotations
import glob
import os
import pathlib
import platform
import re
import shlex
import shutil
import subprocess
import sys

home = os.environ.get("HOME", "")
path_entries = [p for p in os.environ.get("PATH", "").split(":") if p]


def prepend_if_dir(p: str) -> None:
    if p and os.path.isdir(p) and p not in path_entries:
        path_entries.insert(0, p)


def append_if_dir(p: str) -> None:
    if p and os.path.isdir(p) and p not in path_entries:
        path_entries.append(p)


for fallback_dir in ("/opt/homebrew/bin", "/opt/homebrew/sbin", "/usr/local/bin"):
    append_if_dir(fallback_dir)
if home:
    append_if_dir(f"{home}/.local/bin")
    if not shutil.which("node", path=":".join(path_entries)):
        nvm_bins = sorted(glob.glob(f"{home}/.nvm/versions/node/*/bin"), reverse=True)
        if nvm_bins:
            prepend_if_dir(nvm_bins[0])

# Prefer rustup toolchain + ~/.cargo/bin ahead of Homebrew standalone rustc so
# cargo-ndk always finds aarch64-linux-android and x86_64-linux-android target stdlibs.
if home:
    prepend_if_dir(f"{home}/.cargo/bin")
    arch = platform.machine()
    rust_arch = "aarch64" if arch in ("arm64", "aarch64") else arch
    stable_bins = (
        sorted(
            glob.glob(f"{home}/.rustup/toolchains/stable-{rust_arch}-*/bin")
            + glob.glob(f"{home}/.rustup/toolchains/stable-*/bin"),
            reverse=True,
        )
        + sorted(
            glob.glob(f"{home}/.rustup/toolchains/[0-9]*-{rust_arch}-*/bin")
            + glob.glob(f"{home}/.rustup/toolchains/*/bin"),
            reverse=True,
        )
    )
    chosen_rust_bin = ""
    for sb in stable_bins:
        if os.path.isfile(os.path.join(sb, "cargo")) and os.path.isfile(os.path.join(sb, "rustc")):
            if not chosen_rust_bin:
                chosen_rust_bin = sb
            tc_root = os.path.dirname(sb)
            if os.path.isdir(os.path.join(tc_root, "lib", "rustlib", "aarch64-linux-android")):
                chosen_rust_bin = sb
                break
    if chosen_rust_bin:
        prepend_if_dir(chosen_rust_bin)

# Discover JAVA_HOME >= 25 (`cefpack-android.aar` uses class file version 69.0 / JDK 25, matching CI).
def java_major(jh: str) -> int:
    jbin = os.path.join(jh, "bin", "java")
    if not (jh and os.path.isfile(jbin)):
        return 0
    try:
        out = subprocess.check_output([jbin, "-version"], text=True, stderr=subprocess.STDOUT)
        m = re.search(r'version\s+"(\d+)', out)
        return int(m.group(1)) if m else 0
    except Exception:
        return 0


java_home = os.environ.get("JAVA_HOME", "")
if java_major(java_home) < 25:
    java_candidates = [
        "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home",
        "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home",
        "/opt/trap-toolchain/jdk25",
        f"{home}/.trap-toolchain/jdk25" if home else "",
        "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
    ]
    for jc in java_candidates:
        if java_major(jc) >= 25:
            java_home = jc
            break
    if java_major(java_home) < 25 and os.path.isfile("/usr/libexec/java_home"):
        try:
            jh = subprocess.check_output(["/usr/libexec/java_home"], text=True, stderr=subprocess.DEVNULL).strip()
            if jh and os.path.isfile(os.path.join(jh, "bin", "java")):
                java_home = jh
        except Exception:
            pass
if java_home:
    prepend_if_dir(os.path.join(java_home, "bin"))

# Discover Android SDK & valid NDK (skipping empty partial NDK dirs without toolchains/llvm/prebuilt).
sdk_candidates = [
    os.environ.get("ANDROID_SDK_ROOT", ""),
    os.environ.get("ANDROID_HOME", ""),
    "/opt/homebrew/share/android-commandlinetools",
    f"{home}/Library/Android/sdk" if home else "",
    "/usr/local/lib/android/sdk",
    f"{home}/.trap-toolchain/android-sdk" if home else "",
    "/opt/trap-toolchain/android-sdk",
]
sdk_root = ""
for sc in sdk_candidates:
    if sc and os.path.isdir(sc):
        sdk_root = sc
        break


def ver_tuple(text: str) -> tuple[int, ...]:
    nums = [int(tok) for tok in re.findall(r"\d+", text)]
    return tuple(nums) if nums else (0,)


ndk_root = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT") or ""
if not (ndk_root and os.path.isdir(os.path.join(ndk_root, "toolchains", "llvm", "prebuilt"))):
    ndk_root = ""
    ndk_found: list[tuple[int, tuple[int, ...], str]] = []
    for sc in sdk_candidates + ([f"{home}/.trap-toolchain", "/opt/trap-toolchain"] if home else ["/opt/trap-toolchain"]):
        if not sc or not os.path.isdir(sc):
            continue
        ndk_dir = os.path.join(sc, "ndk")
        if os.path.isdir(ndk_dir):
            for entry in os.listdir(ndk_dir):
                full = os.path.join(ndk_dir, entry)
                if os.path.isdir(os.path.join(full, "toolchains", "llvm", "prebuilt")):
                    is_pinned = 1 if entry == "27.2.12479018" else 0
                    ndk_found.append((is_pinned, ver_tuple(entry), full))
        for raw_match in glob.glob(os.path.join(sc, "ndk-*")):
            if os.path.isdir(os.path.join(raw_match, "toolchains", "llvm", "prebuilt")):
                ndk_found.append((0, ver_tuple(os.path.basename(raw_match)), raw_match))
    if ndk_found:
        ndk_found.sort(key=lambda item: (item[0], item[1]), reverse=True)
        ndk_root = ndk_found[0][2]

if sdk_root:
    append_if_dir(os.path.join(sdk_root, "platform-tools"))

joined_path = ":".join(path_entries)

# Locate Python interpreter with Pillow + NumPy for deterministic icon verification.
py_candidates = [sys.executable]
for pat in (
    "/opt/homebrew/bin/python3.*",
    "/opt/homebrew/bin/python3",
    "/usr/local/bin/python3*",
    "/usr/bin/python3",
    f"{home}/.venv/bin/python3" if home else "",
):
    if not pat:
        continue
    for match in sorted(glob.glob(pat), reverse=True) if "*" in pat else ([pat] if os.path.exists(pat) else []):
        if not match.endswith("-config") and os.path.isfile(match) and os.access(match, os.X_OK):
            if match not in py_candidates:
                py_candidates.append(match)

icon_py_cmd = ""
for py in py_candidates:
    rc = subprocess.call(
        [py, "-c", "import PIL, numpy"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if rc == 0:
        icon_py_cmd = shlex.quote(py)
        break

if not icon_py_cmd:
    uv_bin = shutil.which("uv", path=joined_path)
    if uv_bin:
        rc = subprocess.call(
            [uv_bin, "run", "--with", "pillow", "--with", "numpy", "python3", "-c", "import PIL, numpy"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if rc == 0:
            icon_py_cmd = f"{shlex.quote(uv_bin)} run --with pillow --with numpy python3"

print(f"export PATH={shlex.quote(joined_path)}")
if java_home:
    print(f"export JAVA_HOME={shlex.quote(java_home)}")
if sdk_root:
    print(f"export ANDROID_HOME={shlex.quote(sdk_root)}")
    print(f"export ANDROID_SDK_ROOT={shlex.quote(sdk_root)}")
if ndk_root:
    print(f"export ANDROID_NDK_HOME={shlex.quote(ndk_root)}")
    print(f"export ANDROID_NDK_ROOT={shlex.quote(ndk_root)}")
print(f"ICON_PYTHON_CMD={shlex.quote(icon_py_cmd)}")
PY
}
ENV_SETUP="$(discover_pre_push_env)"
eval "$ENV_SETUP"

if [[ -z "${ICON_PYTHON_CMD:-}" ]]; then
    echo "::error::Could not locate a Python 3 environment with 'PIL' (Pillow) and 'numpy' installed for icon verification" >&2
    exit 1
fi

echo "== [pre-push 1/5] Verifying shell/hook syntax, Node syntax, and Limrun gate self-test =="
bash -n "$REPO_ROOT"/scripts/*.sh "$REPO_ROOT/.githooks/pre-push"
node --check "$REPO_ROOT/scripts/limrun-gate.mjs"
node --check "$REPO_ROOT/scripts/test-limrun-gate.mjs"
node "$REPO_ROOT/scripts/test-limrun-gate.mjs"

echo "== [pre-push 2/5] Verifying monotonic VERSION manifest and deterministic icon assets =="
python3 "$REPO_ROOT/scripts/bump-version.py" --current
python3 "$REPO_ROOT/scripts/bump-version.py" --dry-run
eval "$ICON_PYTHON_CMD" '"$REPO_ROOT/design/icons/trap_icon.py"' --check
eval "$ICON_PYTHON_CMD" '"$REPO_ROOT/design/icons/neon/slice_neon.py"' --check

echo "== [pre-push 3/5] Enforcing zero banned engine references & TrapApplication ContextImpl crash guard =="
BANNED_TOKEN="cef""rium"
if git --no-pager -C "$REPO_ROOT" grep -in "$BANNED_TOKEN"; then
    echo "::error::Forbidden legacy '${BANNED_TOKEN}' reference found in tracked repository files" >&2
    exit 1
fi

python3 - "$REPO_ROOT" <<'PY'
from __future__ import annotations
import pathlib
import re
import subprocess
import sys

repo_root = pathlib.Path(sys.argv[1])
banned_token = "cef" + "rium"

# Check untracked non-ignored files as well so unadded files cannot smuggle banned references.
untracked = subprocess.check_output(
    ["git", "-C", str(repo_root), "ls-files", "--others", "--exclude-standard"],
    text=True,
).splitlines()
for rel in untracked:
    p = repo_root / rel
    if not p.is_file():
        continue
    if banned_token in rel.lower():
        raise SystemExit(f"::error::Untracked file path contains forbidden '{banned_token}': {rel}")
    try:
        data = p.read_bytes()
    except OSError:
        continue
    if banned_token.encode("ascii") in data.lower():
        raise SystemExit(f"::error::Untracked file contains forbidden '{banned_token}' reference: {rel}")

trap_app = repo_root / "android/app/src/main/kotlin/dev/onionvault/TrapApplication.kt"
if not trap_app.is_file():
    raise SystemExit(f"::error::Missing {trap_app.relative_to(repo_root)}")

src = trap_app.read_text(encoding="utf-8")
init_args = [
    m.group(1).strip()
    for m in re.finditer(r"CefpackEngine\s*\.\s*initProcess\s*\(\s*([^)]+?)\s*\)", src)
]
if not init_args:
    raise SystemExit("::error::TrapApplication.kt is missing CefpackEngine.initProcess(this)")
bad_args = [arg for arg in init_args if arg != "this"]
if bad_args:
    raise SystemExit(
        f"::error::TrapApplication.kt calls CefpackEngine.initProcess with {bad_args} instead of 'this' "
        "(passing raw ContextImpl causes ClassCastException in PrivilegedProcessService0 / SandboxedProcessService0)"
    )
if "configureChildProcessCreationParams(" not in src:
    raise SystemExit("::error::TrapApplication.kt is missing configureChildProcessCreationParams(...) for Android 17+ SandboxedProcessService routing")
print(f"  [guard] 0 '{banned_token}' matches repo-wide; TrapApplication.kt child-process init verified ({len(init_args)} 'this' call(s))")
PY

echo "== [pre-push 4/5] Running Rust workspace + hidden-service daemons fmt, clippy, and unit/integration tests =="
cargo fmt --manifest-path "$REPO_ROOT/Cargo.toml" --all -- --check
cargo clippy --manifest-path "$REPO_ROOT/Cargo.toml" --workspace --all-targets -- -D warnings
cargo test --manifest-path "$REPO_ROOT/Cargo.toml" --workspace
cargo fmt --manifest-path "$REPO_ROOT/services/pgp-totp-onion/Cargo.toml" --all -- --check
cargo clippy --manifest-path "$REPO_ROOT/services/pgp-totp-onion/Cargo.toml" --locked --all-targets -- -D warnings
cargo test --manifest-path "$REPO_ROOT/services/pgp-totp-onion/Cargo.toml" --locked
cargo fmt --manifest-path "$REPO_ROOT/services/xmr402-swap-onion/Cargo.toml" --all -- --check
cargo clippy --manifest-path "$REPO_ROOT/services/xmr402-swap-onion/Cargo.toml" --locked --all-targets -- -D warnings
cargo test --manifest-path "$REPO_ROOT/services/xmr402-swap-onion/Cargo.toml" --locked

echo "== [pre-push 5/5] Running Android unit tests, debug + androidTest APK builds, and 5/5 APK audit =="
"$REPO_ROOT/android/gradlew" --project-dir "$REPO_ROOT/android" \
    :app:testDebugUnitTest \
    :app:assembleDebug \
    :app:assembleDebugAndroidTest
"$REPO_ROOT/scripts/audit-apk.sh" "$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

echo "== [pre-push PASS] All 5/5 local CI & binary audit gates passed =="
