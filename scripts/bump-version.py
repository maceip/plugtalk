#!/usr/bin/env python3
"""Monotonic version manager for Trap (VERSION, Cargo.toml, Cargo.lock, Android Gradle).

Inspects the repository's `VERSION` file, `Cargo.toml`, `android/app/build.gradle.kts`,
and existing git tags (`v*.*.*` and legacy `build-<N>-*`) to compute or apply a strictly
monotonic semantic version (`MAJOR.MINOR.PATCH`) and Android `versionCode`.

Modes:
  --current   Print the current versionName and versionCode configured in the working tree.
  --dry-run   Compute the next monotonic versionName and versionCode without modifying files.
  --apply     Write the bumped (or explicitly specified) version across VERSION, Cargo.toml,
              Cargo.lock, and android/app/build.gradle.kts.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys
from typing import NamedTuple


WORKSPACE_CRATES = frozenset(
    {
        "onion-vault",
        "ov-cef",
        "ov-core",
        "ov-pgp",
        "ov-tor",
        "ov-xmr",
    }
)

SEMVER_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
TAG_SEMVER_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
TAG_BUILD_RE = re.compile(r"^build-(\d+)(?:$|[-._])")


class SemVer(NamedTuple):
    major: int
    minor: int
    patch: int

    @classmethod
    def parse(cls, raw: str) -> "SemVer":
        match = SEMVER_RE.match(raw.strip())
        if not match:
            raise ValueError(f"Invalid semantic version '{raw}' (expected MAJOR.MINOR.PATCH)")
        return cls(int(match.group(1)), int(match.group(2)), int(match.group(3)))

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"

    def encoded_version_code(self) -> int:
        if self.major > 0 or self.minor > 1:
            return self.major * 100_000 + self.minor * 1_000 + self.patch
        return self.patch


def repo_root() -> pathlib.Path:
    return pathlib.Path(__file__).resolve().parent.parent


def read_version_file(root: pathlib.Path) -> SemVer:
    version_file = root / "VERSION"
    if not version_file.is_file():
        raise FileNotFoundError(f"Missing VERSION file at {version_file}")
    return SemVer.parse(version_file.read_text(encoding="utf-8"))


def read_cargo_versions(root: pathlib.Path) -> tuple[SemVer, SemVer]:
    cargo_toml = (root / "Cargo.toml").read_text(encoding="utf-8")
    pkg_version: SemVer | None = None
    ws_version: SemVer | None = None
    section = ""
    for line in cargo_toml.splitlines():
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            section = stripped[1:-1].strip()
            continue
        m = re.match(r'^version\s*=\s*"([^"]+)"\s*$', stripped)
        if m:
            if section == "package" and pkg_version is None:
                pkg_version = SemVer.parse(m.group(1))
            elif section == "workspace.package" and ws_version is None:
                ws_version = SemVer.parse(m.group(1))
    if pkg_version is None or ws_version is None:
        raise RuntimeError("Could not parse [package] and [workspace.package] versions in Cargo.toml")
    return pkg_version, ws_version


def read_gradle_version_code_floor(root: pathlib.Path) -> int:
    gradle_kts = (root / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r"^\s*val\s+trapVersionCodeFloor\s*=\s*(\d+)\s*$", gradle_kts, re.MULTILINE)
    if m:
        return int(m.group(1))
    m_legacy = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", gradle_kts, re.MULTILINE)
    if m_legacy:
        return int(m_legacy.group(1))
    raise RuntimeError("Could not locate trapVersionCodeFloor in android/app/build.gradle.kts")


def list_git_tags(root: pathlib.Path) -> list[str]:
    proc = subprocess.run(
        ["git", "tag", "--list"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def compute_current(root: pathlib.Path) -> tuple[SemVer, int]:
    ver = read_version_file(root)
    floor = read_gradle_version_code_floor(root)
    code = max(floor, ver.encoded_version_code())
    return ver, code


def compute_next(root: pathlib.Path) -> tuple[SemVer, int, dict[str, object]]:
    file_ver = read_version_file(root)
    pkg_ver, ws_ver = read_cargo_versions(root)
    gradle_floor = read_gradle_version_code_floor(root)
    tags = list_git_tags(root)

    semver_tags: list[SemVer] = []
    build_nums: list[int] = []
    for tag in tags:
        m_sem = TAG_SEMVER_RE.match(tag)
        if m_sem:
            semver_tags.append(
                SemVer(int(m_sem.group(1)), int(m_sem.group(2)), int(m_sem.group(3)))
            )
            continue
        m_bld = TAG_BUILD_RE.match(tag)
        if m_bld:
            build_nums.append(int(m_bld.group(1)))

    max_build_num = max(build_nums, default=0)
    candidates = [file_ver, pkg_ver, ws_ver, *semver_tags]
    if max_build_num > 0:
        candidates.append(SemVer(0, 1, max_build_num))

    highest = max(candidates)
    next_ver = SemVer(highest.major, highest.minor, highest.patch + 1)

    existing_tag_set = set(tags)
    while f"v{next_ver}" in existing_tag_set:
        next_ver = SemVer(next_ver.major, next_ver.minor, next_ver.patch + 1)

    highest_code = max(
        gradle_floor,
        max_build_num,
        highest.encoded_version_code(),
        *(t.encoded_version_code() for t in semver_tags),
    )
    next_code = max(highest_code + 1, next_ver.encoded_version_code())

    details = {
        "previous_version": str(file_ver),
        "previous_version_code": max(gradle_floor, file_ver.encoded_version_code()),
        "highest_tag_semver": str(max(semver_tags)) if semver_tags else None,
        "highest_build_tag_number": max_build_num,
        "tag_count": len(tags),
    }
    return next_ver, next_code, details


def update_cargo_toml(cargo_toml_path: pathlib.Path, new_ver: SemVer) -> None:
    lines = cargo_toml_path.read_text(encoding="utf-8").splitlines(keepends=True)
    section = ""
    updated_sections: set[str] = set()
    out: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            section = stripped[1:-1].strip()
            out.append(line)
            continue
        if section in ("package", "workspace.package") and re.match(
            r'^version\s*=\s*"[^"]+"\s*$', stripped
        ):
            newline = "\r\n" if line.endswith("\r\n") else "\n"
            out.append(f'version = "{new_ver}"{newline}')
            updated_sections.add(section)
        else:
            out.append(line)

    if updated_sections != {"package", "workspace.package"}:
        raise RuntimeError(
            f"Expected to update [package] and [workspace.package] in Cargo.toml, updated: {updated_sections}"
        )
    cargo_toml_path.write_text("".join(out), encoding="utf-8")


def update_cargo_lock(cargo_lock_path: pathlib.Path, new_ver: SemVer) -> None:
    content = cargo_lock_path.read_text(encoding="utf-8")
    pattern = re.compile(
        r'(\[\[package\]\]\r?\nname = "(?P<name>[^"]+)"\r?\nversion = ")(?P<ver>[^"]+)("(?P<rest>(?:\r?\n(?!\[\[package\]\]).*)*))'
    )
    updated_crates: set[str] = set()

    def repl(match: re.Match[str]) -> str:
        name = match.group("name")
        rest = match.group("rest")
        has_external_source = any(
            line.strip().startswith("source = ") for line in rest.splitlines()
        )
        if name in WORKSPACE_CRATES and not has_external_source:
            updated_crates.add(name)
            return f"{match.group(1)}{new_ver}{match.group(4)}"
        return match.group(0)

    new_content = pattern.sub(repl, content)
    missing = WORKSPACE_CRATES - updated_crates
    if missing:
        raise RuntimeError(f"Failed to update workspace crates in Cargo.lock: {sorted(missing)}")
    cargo_lock_path.write_text(new_content, encoding="utf-8")


def update_gradle_kts(gradle_kts_path: pathlib.Path, new_ver: SemVer, new_code: int) -> None:
    content = gradle_kts_path.read_text(encoding="utf-8")
    content_floor, count_floor = re.subn(
        r"^(\s*val\s+trapVersionCodeFloor\s*=\s*)\d+(\s*)$",
        rf"\g<1>{new_code}\2",
        content,
        count=1,
        flags=re.MULTILINE,
    )
    if count_floor != 1:
        raise RuntimeError("Failed to update trapVersionCodeFloor in android/app/build.gradle.kts")

    content_fallback, count_fallback = re.subn(
        r'^(\s*\?:\s*")\d+\.\d+\.\d+("\s*)$',
        rf"\g<1>{new_ver}\2",
        content_floor,
        count=1,
        flags=re.MULTILINE,
    )
    if count_fallback != 1:
        raise RuntimeError(
            "Failed to update default fallback version string in android/app/build.gradle.kts"
        )
    gradle_kts_path.write_text(content_fallback, encoding="utf-8")


def apply_version(root: pathlib.Path, target_ver: SemVer, target_code: int) -> None:
    (root / "VERSION").write_text(f"{target_ver}\n", encoding="utf-8")
    update_cargo_toml(root / "Cargo.toml", target_ver)
    update_cargo_lock(root / "Cargo.lock", target_ver)
    update_gradle_kts(root / "android" / "app" / "build.gradle.kts", target_ver, target_code)


def write_github_output(path: pathlib.Path, payload: dict[str, object]) -> None:
    with path.open("a", encoding="utf-8") as fh:
        fh.write(f"version={payload['version']}\n")
        fh.write(f"version_code={payload['version_code']}\n")
        fh.write(f"tag={payload['tag']}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--current", action="store_true", help="Print current version and versionCode")
    mode.add_argument("--dry-run", action="store_true", help="Preview next monotonic bump without writing")
    mode.add_argument("--apply", action="store_true", help="Apply monotonic bump across repo manifests")

    parser.add_argument(
        "--set-version",
        help="Explicit MAJOR.MINOR.PATCH to apply with --apply (must still be valid SemVer)",
    )
    parser.add_argument(
        "--set-version-code",
        type=int,
        help="Explicit integer versionCode to apply with --apply",
    )
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON to stdout")
    parser.add_argument(
        "--github-output",
        type=pathlib.Path,
        help="Append version, version_code, and tag outputs to GITHUB_OUTPUT file",
    )
    args = parser.parse_args()

    root = repo_root()

    if args.current:
        cur_ver, cur_code = compute_current(root)
        payload: dict[str, object] = {
            "mode": "current",
            "version": str(cur_ver),
            "version_code": cur_code,
            "tag": f"v{cur_ver}",
        }
    elif args.dry_run:
        next_ver, next_code, details = compute_next(root)
        payload = {
            "mode": "dry-run",
            "version": str(next_ver),
            "version_code": next_code,
            "tag": f"v{next_ver}",
            **details,
        }
    else:
        if args.set_version is not None:
            target_ver = SemVer.parse(args.set_version)
            target_code = (
                args.set_version_code
                if args.set_version_code is not None
                else max(read_gradle_version_code_floor(root), target_ver.encoded_version_code())
            )
            details = {"explicit": True}
        else:
            target_ver, target_code, details = compute_next(root)
            if args.set_version_code is not None:
                if args.set_version_code < target_code:
                    raise ValueError(
                        f"--set-version-code ({args.set_version_code}) < monotonic floor ({target_code})"
                    )
                target_code = args.set_version_code

        apply_version(root, target_ver, target_code)
        payload = {
            "mode": "apply",
            "version": str(target_ver),
            "version_code": target_code,
            "tag": f"v{target_ver}",
            **details,
        }

    if args.github_output:
        write_github_output(args.github_output, payload)

    if args.json:
        print(json.dumps(payload, indent=2, sort_keys=True))
    else:
        print(
            f"[{payload['mode']}] version={payload['version']} "
            f"versionCode={payload['version_code']} tag={payload['tag']}"
        )
        if "previous_version" in payload:
            print(
                f"  previous={payload['previous_version']} "
                f"(code={payload['previous_version_code']}, "
                f"highestBuildTag={payload['highest_build_tag_number']}, "
                f"highestSemverTag={payload['highest_tag_semver']})"
            )

    return 0


if __name__ == "__main__":
    sys.exit(main())
