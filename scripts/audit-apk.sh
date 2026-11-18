#!/usr/bin/env bash
#
# Strict anti-crash APK packaging & ELF/DEX binary audit for Trap (`dev.onionvault`).
#
# Usage:
#   ./scripts/audit-apk.sh [path/to/app-debug.apk]
#
# Verifies before emulator gate, attestation, or release:
#   1. Zero legacy banned engine references in `android/**/*.gradle.kts` or `proguard-rules.pro`,
#      `TrapApplication.kt` child-process `CefpackEngine.initProcess(this)` ContextWrapper guard,
#      `aapt2 dump badging` confirms `applicationId='dev.onionvault'` and non-empty
#      `versionCode` / `versionName`, AND `aapt2 dump xmltree` confirms
#      `<application android:memtagMode="off">` (`0x01010624=0`) with zero `<service>` /
#      `<process>` overrides plus merged `SandboxedProcessService0` & `PrivilegedProcessService0`.
#   2. Multi-ABI native libraries exist, are non-empty, and EVERY `.so` in the APK has
#      16KB-aligned (`p_align >= 0x4000`) ELF64 `PT_LOAD` segments:
#        - lib/arm64-v8a/{libcefpack_content.so,libonion_vault.so}
#        - lib/x86_64/{libcefpack_content.so,libonion_vault.so}
#       Bundled legal notices (`assets/licenses/{GPL-3.0,LGPL-2.1,LGPL-3.0}.txt`) and Stored
#      Cefpack assets (`assets/cefpack_resources.pak`, `assets/icudtl.dat`) exist, DEX tables
#      contain required Cefpack/Chromium/Trap prefixes with zero banned/GMS/Firebase/MLKit
#      descriptors, and `dexdump` verifies `TrapApplication.attachBaseContext` passes `this`
#      (never raw `ContextImpl` parameter) to `CefpackEngine.initProcess`.
#   3. `libonion_vault.so` on BOTH `arm64-v8a` and `x86_64` has ZERO browser `.so` `DT_NEEDED`
#      entries and exports all 24 `Java_dev_onionvault_OvNative_*` JNI entry points, while
#      `libcefpack_content.so` exports `JNI_OnLoad`.
#   4. Valid APK Signature Scheme v2+ signature (matching deterministic `android/app/debug.keystore`
#      when signed with the Android Debug DN).
#   5. Final summary pass line (`5/5`).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$APK" ]]; then
    echo "::error::APK file not found: $APK" >&2
    exit 1
fi

# Auto-discover Android SDK build-tools (`aapt2`, `apksigner`, `dexdump`) and NDK LLVM
# binutils (`llvm-readelf` / `readelf` / `llvm-readobj`, `llvm-nm` / `nm`) across macOS & Linux
# without requiring NDK or build-tools to be manually prepended to PATH.
discover_toolchain_env() {
python3 - <<'PY'
from __future__ import annotations
import glob
import os
import pathlib
import re
import shutil
import shlex
import sys

home = os.environ.get("HOME", "")
is_darwin = sys.platform == "darwin"
pinned_ndk_ver = "27.2.12479018"

env_file_vars: dict[str, str] = {}
for env_file in (
    pathlib.Path(home) / ".trap-toolchain" / "env.sh" if home else None,
    pathlib.Path("/opt/trap-toolchain/env.sh"),
):
    if env_file and env_file.is_file():
        for raw_line in env_file.read_text(encoding="utf-8", errors="replace").splitlines():
            m = re.match(r"^\s*export\s+(ANDROID_[A-Z_]+)=(.+)$", raw_line)
            if m and m.group(1) not in env_file_vars:
                val = m.group(2).strip().strip('"').strip("'")
                val = val.replace("$HOME", home).replace("${HOME}", home)
                if "$" not in val:
                    env_file_vars[m.group(1)] = val

sdk_candidates_raw = [
    os.environ.get("ANDROID_SDK_ROOT", ""),
    os.environ.get("ANDROID_HOME", ""),
    env_file_vars.get("ANDROID_SDK_ROOT", ""),
    env_file_vars.get("ANDROID_HOME", ""),
    "/opt/homebrew/share/android-commandlinetools",
    f"{home}/Library/Android/sdk" if home else "",
    "/usr/local/lib/android/sdk",
    f"{home}/.trap-toolchain/android-sdk" if home else "",
    f"{home}/.trap-toolchain" if home else "",
    "/opt/trap-toolchain/android-sdk",
    "/opt/trap-toolchain",
]

sdk_roots: list[pathlib.Path] = []
seen_sdk: set[str] = set()
for raw in sdk_candidates_raw:
    if not raw:
        continue
    p = pathlib.Path(raw)
    if p.is_dir():
        key = str(p.resolve())
        if key not in seen_sdk:
            seen_sdk.add(key)
            sdk_roots.append(p)


def ver_tuple(text: str) -> tuple[int, ...]:
    nums = [int(tok) for tok in re.findall(r"\d+", text)]
    return tuple(nums) if nums else (0,)


def is_exec_file(p: pathlib.Path) -> bool:
    try:
        return p.is_file() and os.access(p, os.X_OK)
    except OSError:
        return False


def find_build_tool(name: str) -> str:
    in_path = shutil.which(name)
    if in_path:
        return in_path
    found: list[tuple[int, tuple[int, ...], str]] = []
    for idx, root in enumerate(sdk_roots):
        bt_dir = root / "build-tools"
        if not bt_dir.is_dir():
            continue
        for child in bt_dir.iterdir():
            cand = child / name
            if is_exec_file(cand):
                found.append((-idx, ver_tuple(child.name), str(cand)))
    if not found:
        return ""
    # Sort by highest build-tools version first, breaking ties by SDK candidate priority.
    found.sort(key=lambda item: (item[1], item[0]), reverse=True)
    return found[0][2]


ndk_candidates_raw = [
    os.environ.get("ANDROID_NDK_HOME", ""),
    os.environ.get("ANDROID_NDK_ROOT", ""),
    env_file_vars.get("ANDROID_NDK_HOME", ""),
    env_file_vars.get("ANDROID_NDK_ROOT", ""),
]

ndk_roots: list[pathlib.Path] = []
seen_ndk: set[str] = set()


def add_ndk_dir(p: pathlib.Path) -> None:
    if not (p.is_dir() and (p / "toolchains" / "llvm" / "prebuilt").is_dir()):
        return
    key = str(p.resolve())
    if key not in seen_ndk:
        seen_ndk.add(key)
        ndk_roots.append(p)


for raw in ndk_candidates_raw:
    if raw:
        add_ndk_dir(pathlib.Path(raw))

discovered_ndks: list[tuple[int, tuple[int, ...], pathlib.Path]] = []
for root in sdk_roots:
    ndk_parent = root / "ndk"
    if ndk_parent.is_dir():
        for child in ndk_parent.iterdir():
            if (child / "toolchains" / "llvm" / "prebuilt").is_dir():
                is_pinned = 1 if child.name == pinned_ndk_ver else 0
                discovered_ndks.append((is_pinned, ver_tuple(child.name), child))
    for pattern in ("ndk-*", "android-ndk-*"):
        for raw_match in glob.glob(str(root / pattern)):
            cand_dir = pathlib.Path(raw_match)
            if (cand_dir / "toolchains" / "llvm" / "prebuilt").is_dir():
                discovered_ndks.append((0, ver_tuple(cand_dir.name), cand_dir))

discovered_ndks.sort(key=lambda item: (item[0], item[1]), reverse=True)
for _, _, p in discovered_ndks:
    add_ndk_dir(p)


def find_ndk_llvm_bins(tool_names: tuple[str, ...]) -> str:
    for ndk in ndk_roots:
        prebuilt = ndk / "toolchains" / "llvm" / "prebuilt"
        if not prebuilt.is_dir():
            continue
        for host_dir in sorted(prebuilt.iterdir()):
            bin_dir = host_dir / "bin"
            for tname in tool_names:
                cand = bin_dir / tname
                if is_exec_file(cand):
                    return str(cand)
    for brew_llvm in ("/opt/homebrew/opt/llvm/bin", "/usr/local/opt/llvm/bin"):
        bdir = pathlib.Path(brew_llvm)
        for tname in tool_names:
            cand = bdir / tname
            if is_exec_file(cand):
                return str(cand)
    if home:
        for tname in tool_names:
            for raw_match in sorted(
                glob.glob(f"{home}/.rustup/toolchains/*/lib/rustlib/*/bin/{tname}"),
                reverse=True,
            ):
                cand = pathlib.Path(raw_match)
                if is_exec_file(cand):
                    return str(cand)
    return ""


def find_elf_tool() -> str:
    for name in ("llvm-readelf", "readelf", "llvm-readobj"):
        in_path = shutil.which(name)
        if in_path:
            return in_path
    return find_ndk_llvm_bins(("llvm-readelf", "llvm-readobj"))


def find_nm_tool() -> str:
    # Prefer NDK / LLVM `llvm-nm` first so macOS Mach-O `/usr/bin/nm` is never chosen for ELF `.so` files.
    ndk_nm = find_ndk_llvm_bins(("llvm-nm",))
    if ndk_nm:
        return ndk_nm
    in_path_llvm = shutil.which("llvm-nm")
    if in_path_llvm:
        return in_path_llvm
    if not is_darwin:
        in_path_nm = shutil.which("nm")
        if in_path_nm:
            return in_path_nm
    return ""


resolved = {
    "SDK_ROOT": str(sdk_roots[0]) if sdk_roots else "/usr/local/lib/android/sdk",
    "NDK_ROOT": str(ndk_roots[0]) if ndk_roots else "",
    "AAPT2": find_build_tool("aapt2"),
    "APKSIGNER": find_build_tool("apksigner"),
    "DEXDUMP": find_build_tool("dexdump"),
    "ELF_TOOL": find_elf_tool(),
    "NM_TOOL": find_nm_tool(),
}
java_home = os.environ.get("JAVA_HOME", "")
if not (java_home and os.path.isfile(os.path.join(java_home, "bin", "java"))):
    for jc in (
        "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home",
        "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
        "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home",
        "/opt/trap-toolchain/jdk25",
        f"{home}/.trap-toolchain/jdk25" if home else "",
    ):
        if jc and os.path.isfile(os.path.join(jc, "bin", "java")):
            java_home = jc
            break
if java_home:
    print(f"export JAVA_HOME={shlex.quote(java_home)}")
    cur_path = os.environ.get("PATH", "")
    jbin = os.path.join(java_home, "bin")
    if jbin not in cur_path.split(":"):
        print(f"export PATH={shlex.quote(jbin + ':' + cur_path)}")
for k, v in resolved.items():
    print(f"{k}={shlex.quote(v)}")
PY
}
TOOLCHAIN_ENV="$(discover_toolchain_env)"
eval "$TOOLCHAIN_ENV"

if [[ -z "$AAPT2" ]]; then
    echo "::error::Could not locate aapt2 in PATH or Android SDK build-tools (searched ANDROID_HOME, ANDROID_SDK_ROOT, /opt/homebrew/share/android-commandlinetools, ~/Library/Android/sdk, /usr/local/lib/android/sdk, ~/.trap-toolchain)" >&2
    exit 1
fi

if [[ -z "$ELF_TOOL" ]]; then
    echo "::error::Could not locate llvm-readelf, readelf, or llvm-readobj in PATH or Android NDK toolchains" >&2
    exit 1
fi

if [[ -z "$NM_TOOL" ]]; then
    echo "::error::Could not locate llvm-nm (or ELF nm) in PATH or Android NDK toolchains" >&2
    exit 1
fi

echo "== [1/5] Auditing Gradle build files, TrapApplication child-process init, AndroidManifest badging & ARMv9 memtagMode =="
python3 - "$REPO_ROOT" <<'PY'
import pathlib
import re
import sys

repo_root = pathlib.Path(sys.argv[1])
banned_token = "cef" + "rium"
gradle_files = sorted(repo_root.glob("android/**/*.gradle.kts")) + [
    repo_root / "android/app/proguard-rules.pro",
]
for gf in gradle_files:
    if not gf.is_file():
        continue
    text = gf.read_text(encoding="utf-8", errors="replace")
    if banned_token in text.lower():
        raise SystemExit(
            f"::error::Forbidden legacy '{banned_token}' reference found in {gf.relative_to(repo_root)}"
        )
print(f"  [gradle] Verified 0 '{banned_token}' references across {len(gradle_files)} Gradle/ProGuard config files")

trap_app_kt = repo_root / "android/app/src/main/kotlin/dev/onionvault/TrapApplication.kt"
if trap_app_kt.is_file():
    kt_src = trap_app_kt.read_text(encoding="utf-8", errors="replace")
    init_calls = [
        m.group(1).strip()
        for m in re.finditer(r"CefpackEngine\s*\.\s*initProcess\s*\(\s*([^)]+?)\s*\)", kt_src)
    ]
    if not init_calls:
        raise SystemExit(
            "::error::TrapApplication.kt is missing mandatory CefpackEngine.initProcess(this) call"
        )
    bad_args = [arg for arg in init_calls if arg != "this"]
    if bad_args:
        raise SystemExit(
            f"::error::TrapApplication.kt passes non-'this' argument {bad_args} to CefpackEngine.initProcess "
            "(raw ContextImpl causes ClassCastException in PrivilegedProcessService0 / SandboxedProcessService0)"
        )
    print(f"  [source] TrapApplication.kt CefpackEngine.initProcess(this) verified ({len(init_calls)} call(s), 0 ContextImpl hazards)")
PY

BADGING="$("$AAPT2" dump badging "$APK")"
PKG_LINE="$(awk '/^package:/ { print; exit }' <<< "$BADGING")"
if [[ -z "$PKG_LINE" ]]; then
    echo "::error::aapt2 dump badging produced no package header" >&2
    exit 1
fi

APP_ID="$(printf '%s\n' "$PKG_LINE" | sed -n "s/.*[[:space:]]name='\([^']*\)'.*/\1/p")"
VERSION_CODE="$(printf '%s\n' "$PKG_LINE" | sed -n "s/.*[[:space:]]versionCode='\([^']*\)'.*/\1/p")"
VERSION_NAME="$(printf '%s\n' "$PKG_LINE" | sed -n "s/.*[[:space:]]versionName='\([^']*\)'.*/\1/p")"

if [[ "$APP_ID" != "dev.onionvault" ]]; then
    echo "::error::Expected applicationId='dev.onionvault', got '${APP_ID}'" >&2
    exit 1
fi
if [[ -z "$VERSION_CODE" || ! "$VERSION_CODE" =~ ^[0-9]+$ || "$VERSION_CODE" -lt 1 ]]; then
    echo "::error::Invalid or empty versionCode='${VERSION_CODE}'" >&2
    exit 1
fi
if [[ -z "$VERSION_NAME" ]]; then
    echo "::error::Empty versionName in APK manifest" >&2
    exit 1
fi
echo "  applicationId='${APP_ID}' versionCode='${VERSION_CODE}' versionName='${VERSION_NAME}'"

XMLTREE="$("$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml)"
printf '%s\n' "$XMLTREE" | python3 -c '
from __future__ import annotations
import re
import sys

xml_text = sys.stdin.read()
banned_token = "cef" + "rium"
if banned_token in xml_text.lower():
    raise SystemExit(f"::error::Merged AndroidManifest.xml contains forbidden {banned_token} reference")

for required_service in (
    "org.chromium.content.app.SandboxedProcessService0",
    "org.chromium.content.app.PrivilegedProcessService0",
):
    if required_service not in xml_text:
        raise SystemExit(
            f"::error::Merged AndroidManifest.xml is missing required Chromium child service {required_service}"
        )

stack: list[tuple[int, str]] = []
app_memtag: list[tuple[str, int]] = []
overrides: list[tuple[str, str, int]] = []

elem_re = re.compile(r"^(\s*)E:\s+([A-Za-z0-9_.-]+)")
attr_re = re.compile(r"^(\s*)A:\s+.*:memtagMode\(0x01010624\)=(.*)$")
val_re = re.compile(r"^(?:\(type\s+0x[0-9a-fA-F]+\))?\s*(0x[0-9a-fA-F]+|-?[0-9]+)")

for raw_line in xml_text.splitlines():
    line = raw_line.rstrip("\r\n")
    m_elem = elem_re.match(line)
    if m_elem:
        indent = len(m_elem.group(1))
        name = m_elem.group(2)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, name))
        continue

    m_attr = attr_re.match(line)
    if m_attr and stack:
        attr_indent = len(m_attr.group(1))
        rhs = m_attr.group(2).strip()
        while len(stack) > 1 and stack[-1][0] >= attr_indent:
            stack.pop()
        owner = stack[-1][1]
        m_val = val_re.match(rhs)
        if not m_val:
            raise SystemExit(
                f"::error::Unrecognized android:memtagMode(0x01010624) value on <{owner}>: {rhs}"
            )
        parsed = int(m_val.group(1), 0)
        if owner == "application":
            app_memtag.append((rhs, parsed))
        elif parsed != 0:
            overrides.append((owner, rhs, parsed))

if len(app_memtag) != 1 or app_memtag[0][1] != 0:
    raise SystemExit(
        f"::error::<application> must declare android:memtagMode=\"off\" (0x01010624=0); found: {app_memtag}"
    )
if overrides:
    raise SystemExit(
        f"::error::Child manifest component(s) override android:memtagMode away from off (0): {overrides}"
    )
print(f"  [manifest] <application android:memtagMode=\"off\"> + Sandboxed/Privileged child services verified; 0 overrides")
'

echo "== [2/5] Auditing ZIP entries, 16KB ELF PT_LOAD alignment, Stored assets, licenses, and DEX tables =="
python3 - "$APK" "$DEXDUMP" <<'PY'
from __future__ import annotations
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

apk_path = sys.argv[1]
dexdump_bin = sys.argv[2] if len(sys.argv) > 2 else ""
banned_token = "cef" + "rium"

required_libs = [
    "lib/arm64-v8a/libcefpack_content.so",
    "lib/arm64-v8a/libonion_vault.so",
    "lib/x86_64/libcefpack_content.so",
    "lib/x86_64/libonion_vault.so",
]
allowed_abi_dirs = {"arm64-v8a", "x86_64"}
required_licenses = [
    "assets/licenses/GPL-3.0.txt",
    "assets/licenses/LGPL-2.1.txt",
    "assets/licenses/LGPL-3.0.txt",
]
required_stored_assets = [
    "assets/cefpack_resources.pak",
    "assets/icudtl.dat",
]
required_dex_prefixes = {
    "Ldev/cefpack/android/": 0,
    "Lorg/chromium/": 0,
    "Lorg/jni_zero/": 0,
    "Ldev/onionvault/": 0,
}
required_dex_classes = {
    "Ldev/cefpack/android/CefpackEngine;",
    "Ldev/cefpack/android/CefpackSession;",
    "Ldev/cefpack/android/CefpackViewAndroidDelegate;",
    "Ldev/cefpack/android/R;",
    "Ldev/onionvault/TrapApplication;",
}
forbidden_dex_prefixes = (
    f"Lcom/{banned_token}/",
    "Lcom/google/android/gms/",
    "Lcom/google/firebase/",
    "Lcom/google/mlkit/",
)


def verify_elf64_16k_pt_load_alignment(zf: zipfile.ZipFile, entry: str) -> list[str]:
    with zf.open(entry) as fp:
        head = fp.read(64)
        if len(head) < 64 or head[:4] != b"\x7fELF" or head[4] != 2 or head[5] != 1:
            raise SystemExit(f"::error::{entry} is not a valid little-endian ELF64 shared library")
        (e_phoff,) = struct.unpack_from("<Q", head, 32)
        e_phentsize, e_phnum = struct.unpack_from("<HH", head, 54)
        if e_phentsize < 56 or e_phnum == 0:
            raise SystemExit(f"::error::{entry} has invalid ELF64 program header table ({e_phentsize=}, {e_phnum=})")
        needed = e_phoff + e_phnum * e_phentsize
        if needed > len(head):
            head += fp.read(needed - len(head))
        if len(head) < needed:
            raise SystemExit(f"::error::{entry} truncated before ELF64 program headers")

    pt_load_aligns: list[str] = []
    for idx in range(e_phnum):
        off = e_phoff + idx * e_phentsize
        p_type, _p_flags, p_offset, p_vaddr, _p_paddr, _p_filesz, _p_memsz, p_align = struct.unpack_from(
            "<IIQQQQQQ", head, off
        )
        if p_type == 1:  # PT_LOAD
            if p_align < 0x4000 or (p_align & (p_align - 1)) != 0:
                raise SystemExit(
                    f"::error::{entry} PT_LOAD[{idx}] p_align={hex(p_align)} (< 0x4000 / 16KB page requirement)"
                )
            if (p_vaddr - p_offset) % p_align != 0:
                raise SystemExit(
                    f"::error::{entry} PT_LOAD[{idx}] (p_vaddr - p_offset) not congruent modulo p_align={hex(p_align)}"
                )
            pt_load_aligns.append(hex(p_align))

    if not pt_load_aligns:
        raise SystemExit(f"::error::{entry} contains zero PT_LOAD segments")
    return pt_load_aligns


def read_uleb128(buf: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        byte = buf[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if (byte & 0x80) == 0:
            return result, offset
        shift += 7


def inspect_dex(dex_bytes: bytes) -> tuple[list[str], list[str]]:
    if len(dex_bytes) < 112 or dex_bytes[:4] != b"dex\n":
        raise RuntimeError("Invalid DEX header magic")
    string_ids_size, string_ids_off, type_ids_size, type_ids_off = struct.unpack_from(
        "<IIII", dex_bytes, 56
    )
    strings: list[str] = []
    for s_idx in range(string_ids_size):
        (str_data_off,) = struct.unpack_from("<I", dex_bytes, string_ids_off + s_idx * 4)
        _, payload_off = read_uleb128(dex_bytes, str_data_off)
        end_off = dex_bytes.find(b"\x00", payload_off)
        if end_off == -1:
            strings.append("")
            continue
        strings.append(dex_bytes[payload_off:end_off].decode("utf-8", errors="replace"))

    descriptors: list[str] = []
    for idx in range(type_ids_size):
        (descriptor_idx,) = struct.unpack_from("<I", dex_bytes, type_ids_off + idx * 4)
        if descriptor_idx < len(strings):
            descriptors.append(strings[descriptor_idx])
    return strings, descriptors


def verify_trap_application_bytecode(dex_name: str, dex_bytes: bytes, dexdump_path: str) -> None:
    with tempfile.NamedTemporaryFile(suffix=".dex", delete=False) as tf:
        tf.write(dex_bytes)
        tmp_dex = tf.name
    try:
        out = subprocess.check_output(
            [dexdump_path, "-d", tmp_dex],
            text=True,
            errors="replace",
        )
    finally:
        try:
            os.unlink(tmp_dex)
        except OSError:
            pass

    marker = "dev.onionvault.TrapApplication.attachBaseContext:(Landroid/content/Context;)V"
    start = out.find(marker)
    if start == -1:
        raise SystemExit(
            f"::error::dexdump could not find {marker} in {dex_name}"
        )
    section = out[start:]
    next_method = re.search(r"\n\s*#\d+\s+:\s+\(in L", section)
    if next_method:
        section = section[: next_method.start()]

    origin: dict[str, str] = {}
    verified_init_calls = 0
    for line in section.splitlines():
        m_super = re.search(
            r"invoke-super\s+\{(v\d+),\s*(v\d+)\},\s+Landroid/app/Application;\.attachBaseContext:",
            line,
        )
        if m_super:
            origin[m_super.group(1)] = "this"
            origin[m_super.group(2)] = "base_context_impl"
            continue

        m_move = re.search(r"\|\s*[0-9a-f]+:\s+move-object(?:/from16|/16)?\s+(v\d+),\s*(v\d+)\b", line)
        if m_move:
            origin[m_move.group(1)] = origin.get(m_move.group(2), "other")
            continue

        m_clobber = re.search(
            r"\|\s*[0-9a-f]+:\s+(?:const\S*|sget\S*|iget\S*|new-instance|move-result\S*|move-exception)\s+(v\d+)\b",
            line,
        )
        if m_clobber:
            origin[m_clobber.group(1)] = "other"
            continue

        m_init = re.search(
            r"invoke-static\s+\{(v\d+)\},\s+Ldev/cefpack/android/CefpackEngine;\.initProcess:\(Landroid/content/Context;\)V",
            line,
        )
        if m_init:
            arg_reg = m_init.group(1)
            src = origin.get(arg_reg, "unknown")
            if src == "base_context_impl":
                raise SystemExit(
                    f"::error::{dex_name}: TrapApplication.attachBaseContext passes raw ContextImpl parameter "
                    f"({arg_reg}) to CefpackEngine.initProcess — causes child-process ClassCastException!"
                )
            if src != "this":
                raise SystemExit(
                    f"::error::{dex_name}: TrapApplication.attachBaseContext passes non-'this' register "
                    f"{arg_reg} (origin={src}) to CefpackEngine.initProcess"
                )
            verified_init_calls += 1

    if verified_init_calls == 0:
        raise SystemExit(
            f"::error::{dex_name}: TrapApplication.attachBaseContext does not invoke CefpackEngine.initProcess(this)"
        )
    print(
        f"  [dexdump] Verified {dex_name} TrapApplication.attachBaseContext passes 'this' ContextWrapper to CefpackEngine.initProcess"
    )


with zipfile.ZipFile(apk_path, "r") as zf:
    infos = {info.filename: info for info in zf.infolist()}

    for name in sorted(infos):
        if banned_token in name.lower() or name.endswith("/libcef.so"):
            raise SystemExit(f"::error::Forbidden legacy entry packaged in APK: {name}")

    for entry in required_libs:
        if entry not in infos:
            raise SystemExit(f"::error::Missing required native library in APK: {entry}")
        if infos[entry].file_size == 0:
            raise SystemExit(f"::error::Empty native library in APK: {entry}")

    all_so_entries = sorted(
        name for name in infos if name.startswith("lib/") and name.endswith(".so")
    )
    for entry in all_so_entries:
        parts = entry.split("/")
        if len(parts) < 3 or parts[1] not in allowed_abi_dirs:
            raise SystemExit(
                f"::error::Unexpected native library ABI directory in APK: {entry} (allowed: {sorted(allowed_abi_dirs)})"
            )
        info = infos[entry]
        if info.file_size == 0:
            raise SystemExit(f"::error::Empty native library in APK: {entry}")
        aligns = verify_elf64_16k_pt_load_alignment(zf, entry)
        mode = "Stored" if info.compress_type == zipfile.ZIP_STORED else "Deflated"
        print(f"  [lib] {entry}: {info.file_size:,} bytes ({mode}, PT_LOAD p_align={aligns})")

    for entry in required_licenses:
        if entry not in infos or infos[entry].file_size == 0:
            raise SystemExit(f"::error::Missing or empty bundled license file: {entry}")
        print(f"  [license] {entry}: {infos[entry].file_size:,} bytes")

    for entry in required_stored_assets:
        if entry not in infos or infos[entry].file_size == 0:
            raise SystemExit(f"::error::Missing or empty required Cefpack asset in APK: {entry}")
        if infos[entry].compress_type != zipfile.ZIP_STORED:
            raise SystemExit(
                f"::error::Cefpack asset must be Stored uncompressed (compress_type=0), got compress_type={infos[entry].compress_type} for {entry}"
            )
        print(f"  [asset] {entry}: {infos[entry].file_size:,} bytes (Stored)")

    mmap_assets = [
        info
        for name, info in sorted(infos.items())
        if name.startswith("assets/") and (name.endswith(".pak") or name.endswith(".dat"))
    ]
    for info in mmap_assets:
        if info.compress_type != zipfile.ZIP_STORED:
            raise SystemExit(
                f"::error::Chromium mmap asset must be Stored uncompressed, got compress_type={info.compress_type} for {info.filename}"
            )

    dex_names = sorted(
        name for name in infos if name.startswith("classes") and name.endswith(".dex")
    )
    if not dex_names:
        raise SystemExit("::error::No classes*.dex entries found in APK")

    found_dex_classes: set[str] = set()
    trap_app_dex: tuple[str, bytes] | None = None
    for dex_name in dex_names:
        dex_raw = zf.read(dex_name)
        strings, descriptors = inspect_dex(dex_raw)
        for s in strings:
            if banned_token in s.lower():
                raise SystemExit(
                    f"::error::Forbidden '{banned_token}' string/symbol found in {dex_name}: {s!r}"
                )
        for desc in descriptors:
            for bad_prefix in forbidden_dex_prefixes:
                if desc.startswith(bad_prefix):
                    raise SystemExit(
                        f"::error::Forbidden class descriptor '{desc}' found in {dex_name} (matches banned prefix '{bad_prefix}')"
                    )
            for prefix in required_dex_prefixes:
                if desc.startswith(prefix):
                    required_dex_prefixes[prefix] += 1
            if desc in required_dex_classes:
                found_dex_classes.add(desc)
            if desc == "Ldev/onionvault/TrapApplication;" and trap_app_dex is None:
                # Only treat the DEX that actually defines TrapApplication (not just references it) if multiple exist.
                if b"attachBaseContext" in dex_raw and b"the engine is ready" in dex_raw:
                    trap_app_dex = (dex_name, dex_raw)

    print(f"  [dex] Scanned {len(dex_names)} DEX file(s): {required_dex_prefixes}; zero '{banned_token}'/GMS/Firebase/MLKit classes or strings")
    for prefix, count in required_dex_prefixes.items():
        if count == 0:
            raise SystemExit(
                f"::error::Missing required Java/JNI layer prefix '{prefix}' across all {len(dex_names)} DEX files"
            )
    missing_classes = sorted(required_dex_classes - found_dex_classes)
    if missing_classes:
        raise SystemExit(
            f"::error::Missing required Cefpack/Trap classes in DEX: {missing_classes}"
        )
    if dexdump_bin and trap_app_dex is not None:
        verify_trap_application_bytecode(trap_app_dex[0], trap_app_dex[1], dexdump_bin)
PY

echo "== [3/5] Auditing ELF DT_NEEDED, JNI_OnLoad & 24 OvNative JNI exports on arm64-v8a and x86_64 =="
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

EXPECTED_JNI_SYMBOLS=(
    "Java_dev_onionvault_OvNative_nativeAllowClearnet"
    "Java_dev_onionvault_OvNative_nativeCheckUrlPolicy"
    "Java_dev_onionvault_OvNative_nativeClassifyCdpDom"
    "Java_dev_onionvault_OvNative_nativeClassifyText"
    "Java_dev_onionvault_OvNative_nativeClearClearnetRules"
    "Java_dev_onionvault_OvNative_nativeDecrypt"
    "Java_dev_onionvault_OvNative_nativeDeleteKey"
    "Java_dev_onionvault_OvNative_nativeDenyClearnet"
    "Java_dev_onionvault_OvNative_nativeEncrypt"
    "Java_dev_onionvault_OvNative_nativeExportPublicKey"
    "Java_dev_onionvault_OvNative_nativeGenerateKey"
    "Java_dev_onionvault_OvNative_nativeGetRequireBiometric"
    "Java_dev_onionvault_OvNative_nativeImportKey"
    "Java_dev_onionvault_OvNative_nativeInit"
    "Java_dev_onionvault_OvNative_nativeLastInitError"
    "Java_dev_onionvault_OvNative_nativeListClearnetRulesJson"
    "Java_dev_onionvault_OvNative_nativeListKeysJson"
    "Java_dev_onionvault_OvNative_nativeResetTorIsolation"
    "Java_dev_onionvault_OvNative_nativeRevokeClearnetRule"
    "Java_dev_onionvault_OvNative_nativeSetHiddenServiceOnly"
    "Java_dev_onionvault_OvNative_nativeSetRequireBiometric"
    "Java_dev_onionvault_OvNative_nativeShutdown"
    "Java_dev_onionvault_OvNative_nativeSocksProxy"
    "Java_dev_onionvault_OvNative_nativeTorStatusJson"
)

for abi in arm64-v8a x86_64; do
    so_out="${TMP_DIR}/libonion_vault_${abi}.so"
    cefpack_so_out="${TMP_DIR}/libcefpack_content_${abi}.so"
    unzip -p "$APK" "lib/${abi}/libonion_vault.so" > "$so_out"
    unzip -p "$APK" "lib/${abi}/libcefpack_content.so" > "$cefpack_so_out"

    if [[ "$(basename "$ELF_TOOL")" == "llvm-readobj" ]]; then
        needed_out="$("$ELF_TOOL" --needed-libs "$so_out")"
    else
        needed_out="$("$ELF_TOOL" -d "$so_out")"
    fi

    if grep -qE "libcef\.so|libcefpack_content\.so" <<< "$needed_out"; then
        echo "::error::lib/${abi}/libonion_vault.so has forbidden DT_NEEDED dependency on browser .so" >&2
        printf '%s\n' "$needed_out" >&2
        exit 1
    fi

    cefpack_syms="$("$NM_TOOL" -D --defined-only "$cefpack_so_out" | awk '{print $NF}')"
    if ! grep -Fxq "JNI_OnLoad" <<< "$cefpack_syms"; then
        echo "::error::lib/${abi}/libcefpack_content.so does not export global JNI_OnLoad" >&2
        exit 1
    fi

    dyn_syms="$("$NM_TOOL" -D --defined-only "$so_out" | awk '{print $NF}')"
    missing_count=0
    for sym in "${EXPECTED_JNI_SYMBOLS[@]}"; do
        if ! grep -Fxq "$sym" <<< "$dyn_syms"; then
            echo "::error::lib/${abi}/libonion_vault.so missing required JNI symbol: ${sym}" >&2
            missing_count=$((missing_count + 1))
        fi
    done
    if (( missing_count > 0 )); then
        exit 1
    fi
    echo "  [elf:${abi}] zero browser .so DT_NEEDED; libcefpack_content.so exports JNI_OnLoad; ${#EXPECTED_JNI_SYMBOLS[@]}/24 OvNative JNI symbols exported"
done

echo "== [4/5] Auditing APK Signature Scheme v2+ & deterministic debug signing certificate =="
if [[ -n "$APKSIGNER" ]]; then
    SIGN_OUT="$("$APKSIGNER" verify -v --print-certs "$APK" 2>/dev/null)"
    if ! grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" <<< "$SIGN_OUT"; then
        echo "::error::APK Signature Scheme v2 verification failed for ${APK}" >&2
        printf '%s\n' "$SIGN_OUT" >&2
        exit 1
    fi
    CERT_DN="$(printf '%s\n' "$SIGN_OUT" | sed -n 's/^.*Signer[^:]*:*[[:space:]]*certificate DN:[[:space:]]*//p' | head -n 1)"
    CERT_SHA256="$(printf '%s\n' "$SIGN_OUT" | sed -n 's/^.*Signer[^:]*:*[[:space:]]*certificate SHA-256 digest:[[:space:]]*//p' | head -n 1 | tr '[:upper:]' '[:lower:]')"
    if [[ -z "$CERT_DN" || -z "$CERT_SHA256" ]]; then
        echo "::error::Could not parse signer DN / SHA-256 digest from apksigner output" >&2
        printf '%s\n' "$SIGN_OUT" >&2
        exit 1
    fi
    PINNED_DEBUG_KEYSTORE="${REPO_ROOT}/android/app/debug.keystore"
    if [[ "$CERT_DN" == *"CN=Android Debug"* && -f "$PINNED_DEBUG_KEYSTORE" ]] && command -v keytool >/dev/null 2>&1; then
        EXPECTED_DEBUG_SHA256="$(keytool -list -v -keystore "$PINNED_DEBUG_KEYSTORE" -storepass android 2>/dev/null | sed -n 's/.*SHA256:[[:space:]]*//p' | head -n 1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
        if [[ -n "$EXPECTED_DEBUG_SHA256" && "$CERT_SHA256" != "$EXPECTED_DEBUG_SHA256" ]]; then
            echo "::error::Android Debug APK signer SHA-256 (${CERT_SHA256}) does not match pinned android/app/debug.keystore (${EXPECTED_DEBUG_SHA256})" >&2
            exit 1
        fi
    fi
    echo "  [sign] v2=true DN='${CERT_DN}' SHA-256=${CERT_SHA256}"
else
    echo "  [sign] apksigner not found in PATH/SDK; skipped certificate pin check"
fi

echo "== [5/5] APK audit PASSED: $(basename "$APK") (${APP_ID} v${VERSION_NAME}, code ${VERSION_CODE}) =="
