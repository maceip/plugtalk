#!/usr/bin/env python3
"""Generate real, spec-compliant CycloneDX 1.5 and SPDX 2.3 SBOMs for Trap's Android APK.

Inspects three empirical sources with zero placeholder or synthetic entries:
  1. `cargo metadata --format-version 1 --locked` + `Cargo.lock` archive SHA-256 checksums
     (all Rust workspace & third-party crates, versions, licenses, purls, dependency graph).
  2. `./android/gradlew --project-dir android :app:dependencies --configuration debugRuntimeClasspath`
     + resolved `.aar` / `.jar` / `.pom` files in `${GRADLE_USER_HOME:-~/.gradle}/caches/modules-2/files-2.1`
     (all Android/Maven runtime libraries including `com.cefrium:cefrium-sdk:0.19.0`, AndroidX,
     Jetpack Compose, Kotlin/Coroutines, with real SHA-256 digests and POM license metadata).
  3. Built `app-debug.apk` (`aapt2 dump badging` manifest metadata, full APK SHA-256, and
     embedded `lib/{arm64-v8a,x86_64}/{libcef.so,libonion_vault.so}` SHA-256 digests).

Emits:
  - `<output-dir>/app-debug.sbom.cdx.json`  (CycloneDX 1.5 JSON)
  - `<output-dir>/app-debug.sbom.spdx.json` (SPDX 2.3 JSON, ready for `actions/attest-sbom`)
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile
from typing import Any


SBOM_NAMESPACE_UUID = uuid.UUID("7f7426ea-648f-4e6a-9870-50599fd4d0d1")

# Well-known Maven POM license names -> canonical SPDX license identifiers.
MAVEN_LICENSE_MAP: dict[str, str] = {
    "apache-2.0": "Apache-2.0",
    "apache 2.0": "Apache-2.0",
    "apache license, version 2.0": "Apache-2.0",
    "the apache software license, version 2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0",
    "asf 2.0": "Apache-2.0",
    "mit": "MIT",
    "mit license": "MIT",
    "the mit license": "MIT",
    "bsd-3-clause": "BSD-3-Clause",
    "the 3-clause bsd license": "BSD-3-Clause",
    "bsd-2-clause": "BSD-2-Clause",
    "lgpl-3.0-or-later": "LGPL-3.0-or-later",
    "gnu lesser general public license v3.0 or later": "LGPL-3.0-or-later",
    "lgpl-2.1-or-later": "LGPL-2.1-or-later",
    "gpl-3.0-or-later": "GPL-3.0-or-later",
    "epl-2.0": "EPL-2.0",
    "eclipse public license v2.0": "EPL-2.0",
    "mpl-2.0": "MPL-2.0",
    "mozilla public license 2.0": "MPL-2.0",
    "isc": "ISC",
    "cc0-1.0": "CC0-1.0",
}

VALID_SPDX_TOKEN_RE = re.compile(r"^[A-Za-z0-9.\-+]+$")


def repo_root() -> pathlib.Path:
    return pathlib.Path(__file__).resolve().parent.parent


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_zip_member(zf: zipfile.ZipFile, member: str) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    with zf.open(member, "r") as fh:
        while True:
            chunk = fh.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
    return digest.hexdigest(), total


def sanitize_spdx_id(raw: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9.\-]+", "-", raw).strip("-")
    return f"SPDXRef-{cleaned}"


def normalize_spdx_expression(raw: str | None) -> str | None:
    """Normalize Cargo/Maven license strings to valid SPDX expressions when unambiguous."""
    if not raw:
        return None
    text = raw.strip()
    if not text:
        return None

    mapped = MAVEN_LICENSE_MAP.get(text.lower())
    if mapped:
        return mapped

    # Legacy Cargo manifests sometimes join alternatives with '/' instead of ' OR '.
    if "/" in text and "://" not in text:
        parts = [p.strip() for p in text.split("/") if p.strip()]
        if len(parts) >= 2 and all(VALID_SPDX_TOKEN_RE.match(p) for p in parts):
            return " OR ".join(parts)

    # Validate tokens in compound SPDX expressions (AND / OR / WITH / parentheses).
    tokens = re.findall(r"\(|\)|[A-Za-z0-9.\-+]+|\S+", text)
    keywords = {"AND", "OR", "WITH", "(", ")"}
    for tok in tokens:
        if tok in keywords:
            continue
        if not VALID_SPDX_TOKEN_RE.match(tok):
            return None
    return " ".join(tokens).replace("( ", "(").replace(" )", ")")


def find_aapt2() -> str:
    direct = shutil.which("aapt2")
    if direct:
        return direct
    sdk_root = pathlib.Path(
        os.environ.get(
            "ANDROID_SDK_ROOT",
            os.environ.get("ANDROID_HOME", "/usr/local/lib/android/sdk"),
        )
    )
    build_tools = sdk_root / "build-tools"
    if build_tools.is_dir():
        candidates = sorted(build_tools.glob("*/aapt2"))
        if candidates:
            return str(candidates[-1])
    raise RuntimeError(f"Could not locate aapt2 in PATH or {build_tools}")


def inspect_apk(apk_path: pathlib.Path) -> dict[str, Any]:
    aapt2 = find_aapt2()
    proc = subprocess.run(
        [aapt2, "dump", "badging", str(apk_path)],
        check=True,
        capture_output=True,
        text=True,
    )
    pkg_line = next(
        (ln for ln in proc.stdout.splitlines() if ln.startswith("package:")),
        "",
    )
    app_id_m = re.search(r"\bname='([^']+)'", pkg_line)
    ver_code_m = re.search(r"\bversionCode='([^']+)'", pkg_line)
    ver_name_m = re.search(r"\bversionName='([^']+)'", pkg_line)
    min_sdk_m = re.search(r"^minSdkVersion:'([^']+)'", proc.stdout, re.MULTILINE)
    target_sdk_m = re.search(r"^targetSdkVersion:'([^']+)'", proc.stdout, re.MULTILINE)

    if not (app_id_m and ver_code_m and ver_name_m):
        raise RuntimeError(f"Could not parse package metadata from aapt2 output: {pkg_line!r}")

    apk_sha256 = sha256_file(apk_path)
    apk_size = apk_path.stat().st_size

    embedded_binaries: list[dict[str, Any]] = []
    target_members = [
        ("lib/arm64-v8a/libcef.so", "LGPL-3.0-or-later", "Cefrium / Chromium native engine (arm64-v8a)"),
        ("lib/x86_64/libcef.so", "LGPL-3.0-or-later", "Cefrium / Chromium native engine (x86_64)"),
        ("lib/arm64-v8a/libonion_vault.so", "Apache-2.0 OR MIT", "Trap Rust JNI shared library (arm64-v8a)"),
        ("lib/x86_64/libonion_vault.so", "Apache-2.0 OR MIT", "Trap Rust JNI shared library (x86_64)"),
    ]
    with zipfile.ZipFile(apk_path, "r") as zf:
        names = set(zf.namelist())
        for member, lic, desc in target_members:
            if member not in names:
                raise RuntimeError(f"APK is missing required native binary {member}")
            digest, size = sha256_zip_member(zf, member)
            abi, filename = member.split("/")[1], member.split("/")[2]
            embedded_binaries.append(
                {
                    "member": member,
                    "abi": abi,
                    "filename": filename,
                    "sha256": digest,
                    "size": size,
                    "license": lic,
                    "description": desc,
                }
            )

    return {
        "application_id": app_id_m.group(1),
        "version_code": ver_code_m.group(1),
        "version_name": ver_name_m.group(1),
        "min_sdk": min_sdk_m.group(1) if min_sdk_m else "",
        "target_sdk": target_sdk_m.group(1) if target_sdk_m else "",
        "sha256": apk_sha256,
        "size": apk_size,
        "embedded_binaries": embedded_binaries,
    }


def parse_cargo_lock_checksums(lock_path: pathlib.Path) -> dict[tuple[str, str], str]:
    checksums: dict[tuple[str, str], str] = {}
    content = lock_path.read_text(encoding="utf-8")
    for block in content.split("[[package]]"):
        name_m = re.search(r'^name\s*=\s*"([^"]+)"', block, re.MULTILINE)
        ver_m = re.search(r'^version\s*=\s*"([^"]+)"', block, re.MULTILINE)
        chk_m = re.search(r'^checksum\s*=\s*"([0-9a-fA-F]{64})"', block, re.MULTILINE)
        if name_m and ver_m and chk_m:
            checksums[(name_m.group(1), ver_m.group(1))] = chk_m.group(1).lower()
    return checksums


def collect_cargo_components(root: pathlib.Path) -> tuple[list[dict[str, Any]], dict[str, list[str]], list[str]]:
    proc = subprocess.run(
        ["cargo", "metadata", "--format-version", "1", "--locked"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    meta = json.loads(proc.stdout)
    lock_checksums = parse_cargo_lock_checksums(root / "Cargo.lock")

    pkg_by_id: dict[str, dict[str, Any]] = {p["id"]: p for p in meta["packages"]}
    ws_member_ids = set(meta.get("workspace_members", []))

    id_to_ref: dict[str, str] = {}
    components: list[dict[str, Any]] = []

    for pkg_id in sorted(pkg_by_id):
        pkg = pkg_by_id[pkg_id]
        name = pkg["name"]
        version = pkg["version"]
        source = pkg.get("source")
        is_workspace = pkg_id in ws_member_ids or source is None

        if source == "registry+https://github.com/rust-lang/crates.io-index":
            purl = f"pkg:cargo/{name}@{version}"
            download_url = f"https://crates.io/api/v1/crates/{name}/{version}/download"
            sha256 = lock_checksums.get((name, version))
        elif is_workspace:
            purl = f"pkg:cargo/{name}@{version}?vcs_url=https://github.com/maceip/trap"
            download_url = "https://github.com/maceip/trap"
            sha256 = None
        else:
            purl = f"pkg:cargo/{name}@{version}"
            download_url = pkg.get("repository") or "NOASSERTION"
            sha256 = lock_checksums.get((name, version))

        bom_ref = purl if purl not in id_to_ref.values() else f"{purl}#id={len(id_to_ref)}"
        id_to_ref[pkg_id] = bom_ref

        raw_license = pkg.get("license")
        spdx_license = normalize_spdx_expression(raw_license)

        components.append(
            {
                "bom_ref": bom_ref,
                "spdx_id": sanitize_spdx_id(f"cargo-{name}-{version}"),
                "ecosystem": "cargo",
                "group": None,
                "name": name,
                "version": version,
                "purl": purl,
                "sha256": sha256,
                "license_spdx": spdx_license,
                "license_raw": raw_license,
                "download_url": download_url,
                "description": pkg.get("description"),
                "is_workspace": is_workspace,
            }
        )

    # Deduplicate any colliding SPDXIDs if multiple versions have identical sanitized strings.
    seen_spdx: set[str] = set()
    for comp in components:
        base = comp["spdx_id"]
        idx = 2
        while comp["spdx_id"] in seen_spdx:
            comp["spdx_id"] = f"{base}-{idx}"
            idx += 1
        seen_spdx.add(comp["spdx_id"])

    edges: dict[str, list[str]] = {}
    for node in meta.get("resolve", {}).get("nodes", []):
        src_ref = id_to_ref.get(node["id"])
        if not src_ref:
            continue
        dep_refs = sorted(
            {
                id_to_ref[dep_id]
                for dep_id in node.get("dependencies", [])
                if dep_id in id_to_ref and id_to_ref[dep_id] != src_ref
            }
        )
        edges[src_ref] = dep_refs

    ws_refs = sorted(id_to_ref[mid] for mid in ws_member_ids if mid in id_to_ref)
    return components, edges, ws_refs


def parse_pom_metadata(pom_path: pathlib.Path) -> tuple[str | None, str | None, str | None]:
    try:
        tree = ET.parse(pom_path)
    except ET.ParseError:
        return None, None, None
    root_el = tree.getroot()
    ns = ""
    if root_el.tag.startswith("{"):
        ns = root_el.tag.split("}")[0] + "}"

    lic_names: list[str] = []
    for lic in root_el.findall(f"./{ns}licenses/{ns}license"):
        name_el = lic.find(f"{ns}name")
        if name_el is not None and name_el.text and name_el.text.strip():
            lic_names.append(name_el.text.strip())

    url_el = root_el.find(f"./{ns}url")
    desc_el = root_el.find(f"./{ns}description")
    raw_lic = " AND ".join(lic_names) if lic_names else None
    spdx_lic = None
    if len(lic_names) == 1:
        spdx_lic = normalize_spdx_expression(lic_names[0])
    elif len(lic_names) > 1:
        mapped_parts = [normalize_spdx_expression(n) for n in lic_names]
        if all(mapped_parts):
            spdx_lic = " AND ".join(
                f"({p})" if (" OR " in p or " AND " in p) else p  # type: ignore[arg-type]
                for p in mapped_parts
                if p is not None
            )

    url = url_el.text.strip() if (url_el is not None and url_el.text) else None
    desc = desc_el.text.strip() if (desc_el is not None and desc_el.text) else None
    return spdx_lic, raw_lic, url or desc


def locate_gradle_cache_artifact(
    cache_root: pathlib.Path, group: str, artifact: str, version: str
) -> tuple[pathlib.Path | None, pathlib.Path | None]:
    ver_dir = cache_root / group / artifact / version
    if not ver_dir.is_dir():
        return None, None

    all_files = [p for p in ver_dir.rglob("*") if p.is_file()]
    pom_file = next(
        (p for p in sorted(all_files) if p.name == f"{artifact}-{version}.pom"),
        None,
    )
    for ext in (".aar", ".jar"):
        exact = [p for p in sorted(all_files) if p.name == f"{artifact}-{version}{ext}"]
        if exact:
            return exact[0], pom_file
    for ext in (".aar", ".jar"):
        prefixed = [
            p
            for p in sorted(all_files)
            if p.suffix == ext
            and not p.name.endswith(("-sources.jar", "-javadoc.jar"))
        ]
        if prefixed:
            return prefixed[0], pom_file
    return pom_file, pom_file


def collect_gradle_components(
    root: pathlib.Path,
) -> tuple[list[dict[str, Any]], dict[str, list[str]], list[str]]:
    gradlew = root / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    env = os.environ.copy()
    env["ONIONVAULT_SKIP_CARGO"] = "1"
    proc = subprocess.run(
        [
            str(gradlew),
            "-q",
            "--project-dir",
            str(root / "android"),
            ":app:dependencies",
            "--configuration",
            "debugRuntimeClasspath",
        ],
        cwd=root,
        env=env,
        check=True,
        capture_output=True,
        text=True,
    )

    gradle_home = pathlib.Path(
        os.environ.get("GRADLE_USER_HOME", str(pathlib.Path.home() / ".gradle"))
    )
    cache_root = gradle_home / "caches" / "modules-2" / "files-2.1"

    node_re = re.compile(
        r"^(?P<prefix>[| ]*[+\\]---\s+)"
        r"(?P<group>[^\s:]+):(?P<artifact>[^\s:]+)"
        r"(?::(?P<req_ver>[^\s(*]+))?"
        r"(?:\s+->\s+(?P<sel_ver>[^\s(*]+))?"
        r"(?P<flags>(?:\s+\([*cn]\))*)\s*$"
    )

    coords: set[tuple[str, str, str]] = set()
    parent_stack: list[tuple[int, tuple[str, str, str]]] = []
    raw_edges: dict[tuple[str, str, str], set[tuple[str, str, str]]] = {}
    direct_roots: set[tuple[str, str, str]] = set()

    for line in proc.stdout.splitlines():
        match = node_re.match(line)
        if not match:
            continue
        flags = match.group("flags") or ""
        if "(c)" in flags or "(n)" in flags:
            continue
        group = match.group("group")
        artifact = match.group("artifact")
        version = match.group("sel_ver") or match.group("req_ver")
        if not version:
            continue

        depth = len(match.group("prefix")) // 5
        coord = (group, artifact, version)
        coords.add(coord)
        raw_edges.setdefault(coord, set())

        while parent_stack and parent_stack[-1][0] >= depth:
            parent_stack.pop()
        if parent_stack:
            raw_edges[parent_stack[-1][1]].add(coord)
        else:
            direct_roots.add(coord)
        parent_stack.append((depth, coord))

    # Sanity-check mandatory runtime libraries so truncated/broken Gradle output fails immediately.
    coord_names = {f"{g}:{a}" for g, a, _ in coords}
    for required_ga in (
        "com.cefrium:cefrium-sdk",
        "com.cefrium:cefrium-sdk-core",
        "androidx.appcompat:appcompat",
        "androidx.compose.ui:ui",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android",
    ):
        if required_ga not in coord_names:
            raise RuntimeError(
                f"Gradle debugRuntimeClasspath did not resolve expected dependency '{required_ga}'"
            )

    coord_to_ref: dict[tuple[str, str, str], str] = {}
    components: list[dict[str, Any]] = []

    for group, artifact, version in sorted(coords):
        purl = f"pkg:maven/{group}/{artifact}@{version}"
        coord_to_ref[(group, artifact, version)] = purl

        bin_path, pom_path = locate_gradle_cache_artifact(cache_root, group, artifact, version)
        sha256 = sha256_file(bin_path) if bin_path is not None else None
        spdx_lic, raw_lic, url_or_desc = (
            parse_pom_metadata(pom_path) if pom_path is not None else (None, None, None)
        )

        if group == "com.cefrium":
            download_url = (
                f"https://maven.cefrium.com/{group.replace('.', '/')}/{artifact}/{version}/"
            )
        elif group.startswith(("androidx.", "com.google.android.")):
            download_url = (
                f"https://dl.google.com/dl/android/maven2/{group.replace('.', '/')}/{artifact}/{version}/"
            )
        else:
            download_url = (
                f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/"
            )

        components.append(
            {
                "bom_ref": purl,
                "spdx_id": sanitize_spdx_id(f"maven-{group}-{artifact}-{version}"),
                "ecosystem": "maven",
                "group": group,
                "name": artifact,
                "version": version,
                "purl": purl,
                "sha256": sha256,
                "artifact_filename": bin_path.name if bin_path is not None else None,
                "license_spdx": spdx_lic,
                "license_raw": raw_lic,
                "download_url": download_url,
                "description": url_or_desc,
                "is_workspace": False,
            }
        )

    edges: dict[str, list[str]] = {
        coord_to_ref[src]: sorted(coord_to_ref[dst] for dst in dsts if dst != src)
        for src, dsts in raw_edges.items()
    }
    root_refs = sorted(coord_to_ref[c] for c in direct_roots)
    return components, edges, root_refs


def build_cyclonedx_15(
    apk_info: dict[str, Any],
    cargo_comps: list[dict[str, Any]],
    cargo_edges: dict[str, list[str]],
    cargo_roots: list[str],
    gradle_comps: list[dict[str, Any]],
    gradle_edges: dict[str, list[str]],
    gradle_roots: list[str],
    timestamp: str,
) -> dict[str, Any]:
    ver = apk_info["version_name"]
    code = apk_info["version_code"]
    app_id = apk_info["application_id"]
    serial = uuid.uuid5(SBOM_NAMESPACE_UUID, f"cdx:{app_id}:{ver}:{apk_info['sha256']}")
    root_ref = f"pkg:generic/{app_id}@{ver}?versionCode={code}"

    cdx_components: list[dict[str, Any]] = []
    binary_refs: list[str] = []

    for eb in apk_info["embedded_binaries"]:
        b_ref = f"apk-member:{eb['member']}@{ver}"
        binary_refs.append(b_ref)
        cdx_components.append(
            {
                "type": "library",
                "bom-ref": b_ref,
                "name": eb["member"],
                "version": ver,
                "description": eb["description"],
                "hashes": [{"alg": "SHA-256", "content": eb["sha256"]}],
                "licenses": [{"expression": eb["license"]}],
                "properties": [
                    {"name": "android:abi", "value": eb["abi"]},
                    {"name": "apk:entrySizeBytes", "value": str(eb["size"])},
                ],
            }
        )

    for comp in [*cargo_comps, *gradle_comps]:
        entry: dict[str, Any] = {
            "type": "library",
            "bom-ref": comp["bom_ref"],
            "name": comp["name"],
            "version": comp["version"],
            "purl": comp["purl"],
        }
        if comp["group"]:
            entry["group"] = comp["group"]
        if comp.get("sha256"):
            entry["hashes"] = [{"alg": "SHA-256", "content": comp["sha256"]}]
        if comp.get("license_spdx"):
            entry["licenses"] = [{"expression": comp["license_spdx"]}]
        elif comp.get("license_raw"):
            entry["licenses"] = [{"license": {"name": comp["license_raw"]}}]

        props = [{"name": "sbom:ecosystem", "value": comp["ecosystem"]}]
        if comp.get("artifact_filename"):
            props.append({"name": "gradle:cachedArtifact", "value": comp["artifact_filename"]})
        entry["properties"] = props
        cdx_components.append(entry)

    all_edges: dict[str, list[str]] = {
        root_ref: sorted({*binary_refs, *cargo_roots, *gradle_roots}),
    }
    for b_ref in binary_refs:
        all_edges[b_ref] = []
    for comp in cargo_comps:
        all_edges[comp["bom_ref"]] = cargo_edges.get(comp["bom_ref"], [])
    for comp in gradle_comps:
        all_edges[comp["bom_ref"]] = gradle_edges.get(comp["bom_ref"], [])

    dependencies = [
        {"ref": ref, "dependsOn": deps}
        for ref, deps in sorted(all_edges.items())
    ]

    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{serial}",
        "version": 1,
        "metadata": {
            "timestamp": timestamp,
            "tools": {
                "components": [
                    {
                        "type": "application",
                        "name": "trap-generate-sbom",
                        "version": "1.0.0",
                    }
                ]
            },
            "component": {
                "type": "application",
                "bom-ref": root_ref,
                "group": app_id,
                "name": "trap",
                "version": ver,
                "purl": f"pkg:generic/{app_id}@{ver}",
                "hashes": [{"alg": "SHA-256", "content": apk_info["sha256"]}],
                "licenses": [{"expression": "Apache-2.0 OR MIT"}],
                "properties": [
                    {"name": "android:applicationId", "value": app_id},
                    {"name": "android:versionCode", "value": code},
                    {"name": "android:minSdkVersion", "value": apk_info["min_sdk"]},
                    {"name": "android:targetSdkVersion", "value": apk_info["target_sdk"]},
                    {"name": "apk:sizeBytes", "value": str(apk_info["size"])},
                ],
            },
        },
        "components": cdx_components,
        "dependencies": dependencies,
    }


def build_spdx_23(
    apk_info: dict[str, Any],
    cargo_comps: list[dict[str, Any]],
    cargo_edges: dict[str, list[str]],
    cargo_roots: list[str],
    gradle_comps: list[dict[str, Any]],
    gradle_edges: dict[str, list[str]],
    gradle_roots: list[str],
    timestamp: str,
) -> dict[str, Any]:
    ver = apk_info["version_name"]
    app_id = apk_info["application_id"]
    doc_uuid = uuid.uuid5(SBOM_NAMESPACE_UUID, f"spdx:{app_id}:{ver}:{apk_info['sha256']}")
    root_spdx_id = "SPDXRef-APK-dev.onionvault"

    packages: list[dict[str, Any]] = [
        {
            "SPDXID": root_spdx_id,
            "name": app_id,
            "versionInfo": ver,
            "packageFileName": "app-debug.apk",
            "downloadLocation": f"https://github.com/maceip/trap/releases/download/v{ver}/app-debug.apk",
            "filesAnalyzed": False,
            "checksums": [{"algorithm": "SHA256", "checksumValue": apk_info["sha256"]}],
            "licenseConcluded": "Apache-2.0 OR MIT",
            "licenseDeclared": "Apache-2.0 OR MIT",
            "copyrightText": "NOASSERTION",
            "primaryPackagePurpose": "APPLICATION",
            "externalRefs": [
                {
                    "referenceCategory": "PACKAGE-MANAGER",
                    "referenceType": "purl",
                    "referenceLocator": f"pkg:generic/{app_id}@{ver}",
                }
            ],
        }
    ]

    relationships: list[dict[str, str]] = [
        {
            "spdxElementId": "SPDXRef-DOCUMENT",
            "relationshipType": "DESCRIBES",
            "relatedSpdxElement": root_spdx_id,
        }
    ]

    for eb in apk_info["embedded_binaries"]:
        eb_spdx_id = sanitize_spdx_id(f"binary-{eb['abi']}-{eb['filename']}")
        packages.append(
            {
                "SPDXID": eb_spdx_id,
                "name": eb["member"],
                "versionInfo": ver,
                "packageFileName": eb["member"],
                "downloadLocation": "NOASSERTION",
                "filesAnalyzed": False,
                "checksums": [{"algorithm": "SHA256", "checksumValue": eb["sha256"]}],
                "licenseConcluded": eb["license"],
                "licenseDeclared": eb["license"],
                "copyrightText": "NOASSERTION",
                "primaryPackagePurpose": "LIBRARY",
                "description": eb["description"],
            }
        )
        relationships.append(
            {
                "spdxElementId": root_spdx_id,
                "relationshipType": "CONTAINS",
                "relatedSpdxElement": eb_spdx_id,
            }
        )

    ref_to_spdx: dict[str, str] = {}
    for comp in [*cargo_comps, *gradle_comps]:
        ref_to_spdx[comp["bom_ref"]] = comp["spdx_id"]
        lic = comp.get("license_spdx") or "NOASSERTION"
        pkg_entry: dict[str, Any] = {
            "SPDXID": comp["spdx_id"],
            "name": f"{comp['group']}:{comp['name']}" if comp["group"] else comp["name"],
            "versionInfo": comp["version"],
            "downloadLocation": comp["download_url"] or "NOASSERTION",
            "filesAnalyzed": False,
            "licenseConcluded": lic,
            "licenseDeclared": lic,
            "copyrightText": "NOASSERTION",
            "primaryPackagePurpose": "LIBRARY",
            "externalRefs": [
                {
                    "referenceCategory": "PACKAGE-MANAGER",
                    "referenceType": "purl",
                    "referenceLocator": comp["purl"],
                }
            ],
        }
        if comp.get("sha256"):
            pkg_entry["checksums"] = [
                {"algorithm": "SHA256", "checksumValue": comp["sha256"]}
            ]
        packages.append(pkg_entry)

    for root_dep_ref in sorted({*cargo_roots, *gradle_roots}):
        if root_dep_ref in ref_to_spdx:
            relationships.append(
                {
                    "spdxElementId": root_spdx_id,
                    "relationshipType": "DEPENDS_ON",
                    "relatedSpdxElement": ref_to_spdx[root_dep_ref],
                }
            )

    for edge_map in (cargo_edges, gradle_edges):
        for src_ref, dst_refs in sorted(edge_map.items()):
            src_spdx = ref_to_spdx.get(src_ref)
            if not src_spdx:
                continue
            for dst_ref in dst_refs:
                dst_spdx = ref_to_spdx.get(dst_ref)
                if dst_spdx:
                    relationships.append(
                        {
                            "spdxElementId": src_spdx,
                            "relationshipType": "DEPENDS_ON",
                            "relatedSpdxElement": dst_spdx,
                        }
                    )

    return {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"{app_id}-{ver}",
        "documentNamespace": f"https://github.com/maceip/trap/sbom/spdx/{app_id}-{ver}-{doc_uuid}",
        "creationInfo": {
            "created": timestamp,
            "creators": [
                "Tool: trap-generate-sbom-1.0.0",
                "Organization: Trap (https://github.com/maceip/trap)",
            ],
            "licenseListVersion": "3.25",
        },
        "documentDescribes": [root_spdx_id],
        "packages": packages,
        "relationships": relationships,
    }


def main() -> int:
    root = repo_root()
    default_apk = root / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "apk",
        nargs="?",
        type=pathlib.Path,
        default=default_apk,
        help="Path to built app-debug.apk (defaults to android/app/build/outputs/apk/debug/app-debug.apk)",
    )
    parser.add_argument(
        "--output-dir",
        type=pathlib.Path,
        default=None,
        help="Output directory for app-debug.sbom.cdx.json and app-debug.sbom.spdx.json (defaults to APK parent directory)",
    )
    parser.add_argument("--cdx-out", type=pathlib.Path, default=None, help="Explicit CycloneDX 1.5 output path")
    parser.add_argument("--spdx-out", type=pathlib.Path, default=None, help="Explicit SPDX 2.3 output path")
    args = parser.parse_args()

    apk_path = args.apk.resolve()
    if not apk_path.is_file():
        print(f"::error::APK file not found: {apk_path}", file=sys.stderr)
        return 1

    out_dir = (args.output_dir or apk_path.parent).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    cdx_path = (args.cdx_out or (out_dir / "app-debug.sbom.cdx.json")).resolve()
    spdx_path = (args.spdx_out or (out_dir / "app-debug.sbom.spdx.json")).resolve()

    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    print(f"== [1/4] Inspecting APK & embedded native libraries: {apk_path.name} ==")
    apk_info = inspect_apk(apk_path)
    print(
        f"  {apk_info['application_id']} v{apk_info['version_name']} (code {apk_info['version_code']}) "
        f"sha256={apk_info['sha256'][:16]}... ({len(apk_info['embedded_binaries'])} embedded .so digests)"
    )

    print("== [2/4] Collecting Rust workspace & crate graph via cargo metadata --locked ==")
    cargo_comps, cargo_edges, cargo_roots = collect_cargo_components(root)
    cargo_hashed = sum(1 for c in cargo_comps if c.get("sha256"))
    print(f"  {len(cargo_comps)} Cargo crates ({cargo_hashed} registry SHA-256 checksums)")

    print("== [3/4] Collecting Android/Maven runtime graph & Gradle cache SHA-256 digests ==")
    gradle_comps, gradle_edges, gradle_roots = collect_gradle_components(root)
    gradle_hashed = sum(1 for c in gradle_comps if c.get("sha256"))
    print(f"  {len(gradle_comps)} Maven libraries ({gradle_hashed} Gradle cache SHA-256 digests)")

    print("== [4/4] Writing CycloneDX 1.5 and SPDX 2.3 JSON SBOMs ==")
    cdx_doc = build_cyclonedx_15(
        apk_info, cargo_comps, cargo_edges, cargo_roots, gradle_comps, gradle_edges, gradle_roots, timestamp
    )
    spdx_doc = build_spdx_23(
        apk_info, cargo_comps, cargo_edges, cargo_roots, gradle_comps, gradle_edges, gradle_roots, timestamp
    )

    cdx_path.parent.mkdir(parents=True, exist_ok=True)
    spdx_path.parent.mkdir(parents=True, exist_ok=True)
    cdx_path.write_text(json.dumps(cdx_doc, indent=2) + "\n", encoding="utf-8")
    spdx_path.write_text(json.dumps(spdx_doc, indent=2) + "\n", encoding="utf-8")

    print(f"  CycloneDX 1.5: {cdx_path} ({cdx_path.stat().st_size:,} bytes, {len(cdx_doc['components'])} components)")
    print(f"  SPDX 2.3:      {spdx_path} ({spdx_path.stat().st_size:,} bytes, {len(spdx_doc['packages'])} packages)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
