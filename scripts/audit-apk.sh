#!/usr/bin/env bash
#
# Strict anti-crash APK packaging & ELF/DEX binary audit for Trap (`dev.onionvault`).
#
# Usage:
#   ./scripts/audit-apk.sh [path/to/app-debug.apk]
#
# Verifies before emulator gate, attestation, or release:
#   1. `aapt2 dump badging` confirms `applicationId='dev.onionvault'` (`package: name='dev.onionvault'`)
#      and non-empty `versionCode` / `versionName`, AND `aapt2 dump xmltree` confirms
#      `<application android:memtagMode="off">` (`0x01010624=0`) with zero `<service>` /
#      `<process>` overrides (preventing ARMv9 MTE `SetHeapTaggingLevel` `SIGTRAP` crashes).
#   2. Multi-ABI native libraries exist, are non-empty, and have 16KB-aligned (`p_align >= 0x4000`)
#      ELF64 `PT_LOAD` segments:
#        - lib/arm64-v8a/{libcef.so,libonion_vault.so}
#        - lib/x86_64/{libcef.so,libonion_vault.so}
#   3. Bundled legal notices exist:
#        - assets/licenses/{GPL-3.0,LGPL-2.1,LGPL-3.0}.txt
#   4. Chromium `.pak` assets exist and are stored uncompressed (`Stored`, compress_type=0).
#   5. `classes*.dex` type tables contain `Lorg/chromium/`, `Lcom/cefrium/`, `Lorg/jni_zero/`,
#      and `Ldev/onionvault/` (catching the missing-Java-layer crash from INCIDENT-REPORT.md).
#   6. `libonion_vault.so` on BOTH `arm64-v8a` and `x86_64` has ZERO `libcef.so` `DT_NEEDED`
#      entries and exports all 24 `Java_dev_onionvault_OvNative_*` JNI entry points.
#   7. Valid APK Signature Scheme v2+ signature (matching deterministic `android/app/debug.keystore`
#      when signed with the Android Debug DN).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$APK" ]]; then
    echo "::error::APK file not found: $APK" >&2
    exit 1
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_ROOT" && -d "${SDK_ROOT}/ndk" ]]; then
    NDK_ROOT="$(find "${SDK_ROOT}/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
fi

find_aapt2() {
    if command -v aapt2 >/dev/null 2>&1; then
        command -v aapt2
        return 0
    fi
    if [[ -d "${SDK_ROOT}/build-tools" ]]; then
        local found
        found="$(find "${SDK_ROOT}/build-tools" -type f -name aapt2 2>/dev/null | sort -V | tail -n 1 || true)"
        if [[ -n "$found" ]]; then
            echo "$found"
            return 0
        fi
    fi
    return 1
}

find_elf_needed_tool() {
    for candidate in llvm-readelf readelf llvm-readobj; do
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return 0
        fi
    done
    if [[ -n "$NDK_ROOT" && -d "${NDK_ROOT}/toolchains/llvm/prebuilt" ]]; then
        local found
        found="$(find "${NDK_ROOT}/toolchains/llvm/prebuilt" -type f \( -name llvm-readelf -o -name llvm-readobj \) 2>/dev/null | sort | head -n 1 || true)"
        if [[ -n "$found" ]]; then
            echo "$found"
            return 0
        fi
    fi
    return 1
}

find_nm_tool() {
    if [[ -n "$NDK_ROOT" && -d "${NDK_ROOT}/toolchains/llvm/prebuilt" ]]; then
        local found
        found="$(find "${NDK_ROOT}/toolchains/llvm/prebuilt" -type f -name llvm-nm 2>/dev/null | sort | head -n 1 || true)"
        if [[ -n "$found" ]]; then
            echo "$found"
            return 0
        fi
    fi
    for candidate in llvm-nm nm; do
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return 0
        fi
    done
    return 1
}

find_apksigner() {
    if command -v apksigner >/dev/null 2>&1; then
        command -v apksigner
        return 0
    fi
    if [[ -d "${SDK_ROOT}/build-tools" ]]; then
        local found
        found="$(find "${SDK_ROOT}/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
        if [[ -n "$found" ]]; then
            echo "$found"
            return 0
        fi
    fi
    return 1
}

AAPT2="$(find_aapt2 || true)"
if [[ -z "$AAPT2" ]]; then
    echo "::error::Could not locate aapt2 in PATH or ${SDK_ROOT}/build-tools" >&2
    exit 1
fi

ELF_TOOL="$(find_elf_needed_tool || true)"
if [[ -z "$ELF_TOOL" ]]; then
    echo "::error::Could not locate llvm-readelf, readelf, or llvm-readobj" >&2
    exit 1
fi

NM_TOOL="$(find_nm_tool || true)"
if [[ -z "$NM_TOOL" ]]; then
    echo "::error::Could not locate llvm-nm or nm" >&2
    exit 1
fi

APKSIGNER="$(find_apksigner || true)"

echo "== [1/5] Auditing AndroidManifest badging & ARMv9 memtagMode with $(basename "$AAPT2") =="
BADGING="$("$AAPT2" dump badging "$APK")"
PKG_LINE="$(printf '%s\n' "$BADGING" | grep -m 1 '^package:' || true)"
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
import re
import sys

stack: list[tuple[int, str]] = []
app_memtag: list[tuple[str, int]] = []
overrides: list[tuple[str, str, int]] = []

elem_re = re.compile(r"^(\s*)E:\s+([A-Za-z0-9_.-]+)")
attr_re = re.compile(r"^(\s*)A:\s+.*:memtagMode\(0x01010624\)=(.*)$")
val_re = re.compile(r"^(?:\(type\s+0x[0-9a-fA-F]+\))?\s*(0x[0-9a-fA-F]+|-?[0-9]+)")

for raw_line in sys.stdin:
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
print(f"  [manifest] <application android:memtagMode=\"off\"> verified ({app_memtag[0][0]}); 0 component overrides")
'

echo "== [2/5] Auditing ZIP entries, 16KB ELF PT_LOAD alignment, .pak storage, licenses, and DEX descriptors =="
python3 - "$APK" <<'PY'
import struct
import sys
import zipfile

apk_path = sys.argv[1]

required_libs = [
    "lib/arm64-v8a/libcef.so",
    "lib/arm64-v8a/libonion_vault.so",
    "lib/x86_64/libcef.so",
    "lib/x86_64/libonion_vault.so",
]
required_licenses = [
    "assets/licenses/GPL-3.0.txt",
    "assets/licenses/LGPL-2.1.txt",
    "assets/licenses/LGPL-3.0.txt",
]
required_dex_prefixes = {
    "Lorg/chromium/": 0,
    "Lcom/cefrium/": 0,
    "Lorg/jni_zero/": 0,
    "Ldev/onionvault/": 0,
}


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


def collect_dex_type_descriptors(dex_bytes: bytes) -> list[str]:
    if len(dex_bytes) < 112 or dex_bytes[:4] != b"dex\n":
        raise RuntimeError("Invalid DEX header magic")
    string_ids_size, string_ids_off, type_ids_size, type_ids_off = struct.unpack_from(
        "<IIII", dex_bytes, 56
    )
    descriptors: list[str] = []
    for idx in range(type_ids_size):
        (descriptor_idx,) = struct.unpack_from("<I", dex_bytes, type_ids_off + idx * 4)
        if descriptor_idx >= string_ids_size:
            continue
        (str_data_off,) = struct.unpack_from("<I", dex_bytes, string_ids_off + descriptor_idx * 4)
        _, payload_off = read_uleb128(dex_bytes, str_data_off)
        end_off = dex_bytes.find(b"\x00", payload_off)
        if end_off == -1:
            continue
        descriptors.append(dex_bytes[payload_off:end_off].decode("utf-8", errors="replace"))
    return descriptors


with zipfile.ZipFile(apk_path, "r") as zf:
    infos = {info.filename: info for info in zf.infolist()}

    for entry in required_libs:
        if entry not in infos:
            raise SystemExit(f"::error::Missing required native library in APK: {entry}")
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

    pak_infos = [
        info
        for name, info in sorted(infos.items())
        if name.startswith("assets/") and (name.endswith(".pak") or ".pak+" in name)
    ]
    if not pak_infos:
        raise SystemExit("::error::No Chromium .pak assets found under assets/ in APK")
    for info in pak_infos:
        if info.compress_type != zipfile.ZIP_STORED:
            raise SystemExit(
                f"::error::Chromium .pak asset must be Stored uncompressed, got compress_type={info.compress_type} for {info.filename}"
            )
    print(f"  [pak] {len(pak_infos)} Chromium .pak asset(s) verified Stored (uncompressed)")

    dex_names = sorted(
        name for name in infos if name.startswith("classes") and name.endswith(".dex")
    )
    if not dex_names:
        raise SystemExit("::error::No classes*.dex entries found in APK")

    for dex_name in dex_names:
        descriptors = collect_dex_type_descriptors(zf.read(dex_name))
        for desc in descriptors:
            for prefix in required_dex_prefixes:
                if desc.startswith(prefix):
                    required_dex_prefixes[prefix] += 1

    print(f"  [dex] Scanned {len(dex_names)} DEX file(s): {required_dex_prefixes}")
    for prefix, count in required_dex_prefixes.items():
        if count == 0:
            raise SystemExit(
                f"::error::Missing required Java/JNI layer prefix '{prefix}' across all {len(dex_names)} DEX files"
            )
PY

echo "== [3/5] Auditing ELF DT_NEEDED & 24 JNI exports on arm64-v8a and x86_64 =="
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
    unzip -p "$APK" "lib/${abi}/libonion_vault.so" > "$so_out"

    if [[ "$(basename "$ELF_TOOL")" == "llvm-readobj" ]]; then
        needed_out="$("$ELF_TOOL" --needed-libs "$so_out")"
    else
        needed_out="$("$ELF_TOOL" -d "$so_out")"
    fi

    if printf '%s\n' "$needed_out" | grep -q "libcef\.so"; then
        echo "::error::lib/${abi}/libonion_vault.so has forbidden DT_NEEDED dependency on libcef.so" >&2
        printf '%s\n' "$needed_out" >&2
        exit 1
    fi

    dyn_syms="$("$NM_TOOL" -D --defined-only "$so_out" | awk '{print $NF}')"
    missing_count=0
    for sym in "${EXPECTED_JNI_SYMBOLS[@]}"; do
        if ! printf '%s\n' "$dyn_syms" | grep -Fxq "$sym"; then
            echo "::error::lib/${abi}/libonion_vault.so missing required JNI symbol: ${sym}" >&2
            missing_count=$((missing_count + 1))
        fi
    done
    if (( missing_count > 0 )); then
        exit 1
    fi
    echo "  [elf:${abi}] zero libcef.so DT_NEEDED; ${#EXPECTED_JNI_SYMBOLS[@]}/24 OvNative JNI symbols exported"
done

echo "== [4/5] Auditing APK Signature Scheme v2+ & deterministic debug signing certificate =="
if [[ -n "$APKSIGNER" ]]; then
    SIGN_OUT="$("$APKSIGNER" verify -v --print-certs "$APK")"
    if ! printf '%s\n' "$SIGN_OUT" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true"; then
        echo "::error::APK Signature Scheme v2 verification failed for ${APK}" >&2
        printf '%s\n' "$SIGN_OUT" >&2
        exit 1
    fi
    CERT_DN="$(printf '%s\n' "$SIGN_OUT" | sed -n 's/^Signer #1 certificate DN:[[:space:]]*//p' | head -n 1)"
    CERT_SHA256="$(printf '%s\n' "$SIGN_OUT" | sed -n 's/^Signer #1 certificate SHA-256 digest:[[:space:]]*//p' | head -n 1 | tr '[:upper:]' '[:lower:]')"
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
