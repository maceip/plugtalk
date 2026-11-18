#!/usr/bin/env python3
"""Sync and verify Trap's unresized 1024x1024 nodpi Android icon assets.

    python3 design/icons/trap_icon.py           # write the files
    python3 design/icons/trap_icon.py --check   # fail when they drifted

Canonical 1024x1024 8-bit RGBA sources in `design/icons/src/`:
  - `trap-icon.png`            — full-color 1024x1024 TR/AP squircle icon
  - `trap-icon-monochrome.png` — monochrome 1024x1024 TR/AP squircle icon

Strict zero-resizing invariant:
  Every icon PNG written to `android/app/src/main/res/` and `design/icons/out/`
  remains strictly `1024x1024` (`Image.resize` is never called).
  - Full-color copies are written byte-for-byte identically from
    `src/trap-icon.png` into `res/mipmap-nodpi/{ic_launcher,ic_launcher_round,
    ic_launcher_foreground}.png`, `res/drawable-nodpi/ov_trap_mark.png`, and
    `design/icons/out/{play-store-1024,play-store-512}.png`.
  - `res/mipmap-nodpi/ic_launcher_monochrome.png` converts the RGB luminance of
    `src/trap-icon-monochrome.png` into an exact `1024x1024` white-on-alpha mask
    (`RGB=(255,255,255), A = luminance * orig_A / 255`) required by Android 13+
    `AdaptiveIconDrawable` `<monochrome>` (`PorterDuff.Mode.SRC_IN` alpha tint).

Uses only the Python 3 standard library (`struct`, `zlib`) so `--check` runs
identically across macOS and Linux CI without external imaging dependencies.
"""

from __future__ import annotations

import argparse
import struct
import sys
import tempfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SRC_COLOR_PNG = HERE / "src" / "trap-icon.png"
SRC_MONO_PNG = HERE / "src" / "trap-icon-monochrome.png"
OUT = HERE / "out"
RES = ROOT / "android" / "app" / "src" / "main" / "res"

ICON_SIDE = 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
DENSITY_BUCKETS = ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")


# --------------------------------------------------------- stdlib PNG RGBA I/O


def decode_rgba_png(data: bytes, label: str = "PNG") -> tuple[int, int, bytes]:
    """Decode a non-interlaced 8-bit RGBA PNG into `(width, height, rgba_bytes)`."""
    if len(data) < 8 or data[:8] != PNG_SIGNATURE:
        raise SystemExit(f"{label}: invalid PNG signature")

    pos = 8
    width = 0
    height = 0
    saw_ihdr = False
    idat = bytearray()

    while pos + 8 <= len(data):
        length, chunk_type = struct.unpack(">I4s", data[pos : pos + 8])
        chunk_start = pos + 8
        chunk_end = chunk_start + length
        if chunk_end + 4 > len(data):
            raise SystemExit(f"{label}: truncated PNG chunk {chunk_type!r}")
        chunk = data[chunk_start:chunk_end]
        pos = chunk_end + 4

        if chunk_type == b"IHDR":
            if length != 13:
                raise SystemExit(f"{label}: invalid IHDR length {length}")
            (
                width,
                height,
                bit_depth,
                color_type,
                compression,
                filter_method,
                interlace,
            ) = struct.unpack(">IIBBBBB", chunk)
            if (bit_depth, color_type, compression, filter_method, interlace) != (
                8,
                6,
                0,
                0,
                0,
            ):
                raise SystemExit(
                    f"{label}: expected 8-bit non-interlaced RGBA PNG, got "
                    f"depth={bit_depth} color_type={color_type} interlace={interlace}"
                )
            saw_ihdr = True
        elif chunk_type == b"IDAT":
            idat.extend(chunk)
        elif chunk_type == b"IEND":
            break

    if not saw_ihdr or width <= 0 or height <= 0:
        raise SystemExit(f"{label}: missing valid IHDR")

    raw = zlib.decompress(bytes(idat))
    stride = width * 4
    expected_raw = height * (1 + stride)
    if len(raw) != expected_raw:
        raise SystemExit(
            f"{label}: decompressed scanline length {len(raw)} != {expected_raw}"
        )

    out = bytearray(width * height * 4)
    in_pos = 0
    out_pos = 0
    for y in range(height):
        ftype = raw[in_pos]
        in_pos += 1
        row = bytearray(raw[in_pos : in_pos + stride])
        in_pos += stride

        if ftype == 0:
            pass
        elif ftype == 1:  # Sub
            for i in range(4, stride):
                row[i] = (row[i] + row[i - 4]) & 0xFF
        elif ftype == 2:  # Up
            if y > 0:
                prev = out[out_pos - stride : out_pos]
                for i in range(stride):
                    row[i] = (row[i] + prev[i]) & 0xFF
        elif ftype == 3:  # Average
            prev = out[out_pos - stride : out_pos] if y > 0 else bytes(stride)
            for i in range(stride):
                a = row[i - 4] if i >= 4 else 0
                b = prev[i]
                row[i] = (row[i] + ((a + b) >> 1)) & 0xFF
        elif ftype == 4:  # Paeth
            prev = out[out_pos - stride : out_pos] if y > 0 else bytes(stride)
            for i in range(stride):
                a = row[i - 4] if i >= 4 else 0
                b = prev[i]
                c = prev[i - 4] if i >= 4 else 0
                p = a + b - c
                pa = abs(p - a)
                pb = abs(p - b)
                pc = abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                row[i] = (row[i] + pr) & 0xFF
        else:
            raise SystemExit(f"{label}: unsupported PNG filter type {ftype}")

        out[out_pos : out_pos + stride] = row
        out_pos += stride

    return width, height, bytes(out)


def _png_chunk(chunk_type: bytes, payload: bytes) -> bytes:
    crc = zlib.crc32(chunk_type)
    crc = zlib.crc32(payload, crc) & 0xFFFFFFFF
    return struct.pack(">I4s", len(payload), chunk_type) + payload + struct.pack(">I", crc)


def encode_rgba_png(width: int, height: int, rgba: bytes) -> bytes:
    """Encode `(width, height, rgba)` as an 8-bit non-interlaced RGBA PNG."""
    stride = width * 4
    if len(rgba) != height * stride:
        raise ValueError(f"RGBA buffer length {len(rgba)} != {height * stride}")

    scanlines = bytearray(height * (1 + stride))
    in_pos = 0
    out_pos = 0
    for _ in range(height):
        row = rgba[in_pos : in_pos + stride]
        in_pos += stride
        # PNG Sub filter (1) compresses uniform RGB=(255,255,255) alpha masks tightly.
        scanlines[out_pos] = 1
        out_pos += 1
        scanlines[out_pos : out_pos + 4] = row[:4]
        for i in range(4, stride):
            scanlines[out_pos + i] = (row[i] - row[i - 4]) & 0xFF
        out_pos += stride

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    compressed = zlib.compress(bytes(scanlines), level=9)
    return (
        PNG_SIGNATURE
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", compressed)
        + _png_chunk(b"IEND", b"")
    )


# --------------------------------------------------------- canonical rendering


def _load_canonical_sources() -> tuple[bytes, bytes]:
    """Load and validate both 1024x1024 source PNGs without resizing."""
    if not SRC_COLOR_PNG.exists():
        raise SystemExit(f"missing canonical color icon: {SRC_COLOR_PNG}")
    if not SRC_MONO_PNG.exists():
        raise SystemExit(f"missing canonical monochrome icon: {SRC_MONO_PNG}")

    color_bytes = SRC_COLOR_PNG.read_bytes()
    cw, ch, _ = decode_rgba_png(color_bytes, str(SRC_COLOR_PNG))
    if (cw, ch) != (ICON_SIDE, ICON_SIDE):
        raise SystemExit(
            f"{SRC_COLOR_PNG} must be {ICON_SIDE}x{ICON_SIDE}, got {cw}x{ch}"
        )

    mono_src_bytes = SRC_MONO_PNG.read_bytes()
    mw, mh, mono_rgba = decode_rgba_png(mono_src_bytes, str(SRC_MONO_PNG))
    if (mw, mh) != (ICON_SIDE, ICON_SIDE):
        raise SystemExit(
            f"{SRC_MONO_PNG} must be {ICON_SIDE}x{ICON_SIDE}, got {mw}x{mh}"
        )

    mono_alpha_bytes = _monochrome_alpha_png(mw, mh, mono_rgba)
    return color_bytes, mono_alpha_bytes


def _monochrome_alpha_png(width: int, height: int, rgba: bytes) -> bytes:
    """Convert a 1024x1024 luminance monochrome PNG to white-on-alpha at 1024x1024.

    Android 13+ `<monochrome>` tints ONLY the alpha channel (`PorterDuff.Mode.SRC_IN`)
    and ignores RGB values. Converting `A = luminance * orig_A / 255` with
    `RGB = (255, 255, 255)` preserves the exact 1024x1024 resolution (zero resizing)
    while rendering the crisp TR/AP letterforms in Material You themed icons.
    """
    out = bytearray(len(rgba))
    for idx in range(0, len(rgba), 4):
        a = rgba[idx + 3]
        if a == 0:
            continue
        r = rgba[idx]
        g = rgba[idx + 1]
        b = rgba[idx + 2]
        lum = (299 * r + 587 * g + 114 * b + 500) // 1000
        mono_a = (lum * a + 127) // 255
        if mono_a > 0:
            out[idx] = 255
            out[idx + 1] = 255
            out[idx + 2] = 255
            out[idx + 3] = mono_a
    return encode_rgba_png(width, height, bytes(out))


def render(target_res: Path, target_out: Path) -> dict[Path, tuple[bytes, bool]]:
    """Return `{path: (png_bytes, require_exact_bytes)}` for every 1024x1024 output."""
    color_bytes, mono_alpha_bytes = _load_canonical_sources()
    return {
        # Android nodpi launcher & in-app mark (byte-for-byte 1024x1024 copies)
        target_res / "mipmap-nodpi" / "ic_launcher.png": (color_bytes, True),
        target_res / "mipmap-nodpi" / "ic_launcher_round.png": (color_bytes, True),
        target_res / "mipmap-nodpi" / "ic_launcher_foreground.png": (color_bytes, True),
        target_res / "drawable-nodpi" / "ov_trap_mark.png": (color_bytes, True),
        # Android 13+ nodpi Material You monochrome mask (unresized 1024x1024)
        target_res / "mipmap-nodpi" / "ic_launcher_monochrome.png": (
            mono_alpha_bytes,
            False,
        ),
        # Store & README exports (unresized 1024x1024 byte-for-byte copies)
        target_out / "play-store-1024.png": (color_bytes, True),
        target_out / "play-store-512.png": (color_bytes, True),
    }


def stale_files() -> list[Path]:
    """Files from earlier resized icon pipelines that must not exist."""
    stale: list[Path] = [
        HERE / "src" / "trap-icon.webp",
        RES / "drawable" / "ic_launcher_background.xml",
        OUT / "ic_launcher_monochrome_192.png",
        OUT / "ic_launcher_monochrome_512.png",
        OUT / "trap-lockup.png",
        OUT / "trap-lockup.svg",
        OUT / "trap-lockup-dark.png",
        OUT / "trap-lockup-dark.svg",
    ]
    for density in DENSITY_BUCKETS:
        bucket = RES / f"mipmap-{density}"
        if bucket.is_dir():
            stale.extend(sorted(bucket.glob("ic_launcher*.png")))
    return stale


def unexpected_files(expected_res: set[Path], expected_out: set[Path]) -> list[Path]:
    """Unexpected launcher or `out/` files not written by the nodpi pipeline."""
    found: list[Path] = []
    for directory in sorted(RES.glob("mipmap-*")):
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("ic_launcher*.png")):
            if path not in expected_res:
                found.append(path)
    for path in sorted(RES.glob("drawable/ic_launcher*")):
        if path not in expected_res:
            found.append(path)
    if OUT.is_dir():
        for path in sorted(OUT.iterdir()):
            if path.is_file() and path not in expected_out:
                found.append(path)
    return found


def _asset_matches(current: Path, expected_data: bytes, exact_bytes: bool) -> bool:
    if not current.exists():
        return False
    current_bytes = current.read_bytes()
    w, h, cur_rgba = decode_rgba_png(current_bytes, str(current))
    if (w, h) != (ICON_SIDE, ICON_SIDE):
        return False
    if current_bytes == expected_data:
        return True
    if exact_bytes:
        return False
    ew, eh, exp_rgba = decode_rgba_png(expected_data, f"expected:{current.name}")
    return (w, h) == (ew, eh) and cur_rgba == exp_rgba


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify every icon PNG is 1024x1024 and matches the canonical sources",
    )
    arguments = parser.parse_args(argv)

    if arguments.check:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            rendered = render(root / "res", root / "out")
            drifted: list[str] = []
            expected_res: set[Path] = set()
            expected_out: set[Path] = set()
            for path, (data, exact_bytes) in sorted(rendered.items()):
                relative = path.relative_to(root)
                if relative.parts[0] == "res":
                    current = RES / relative.relative_to("res")
                    expected_res.add(current)
                else:
                    current = OUT / relative.relative_to("out")
                    expected_out.add(current)
                if not _asset_matches(current, data, exact_bytes):
                    drifted.append(str(current.relative_to(ROOT)))
            for path in stale_files():
                if path.exists():
                    drifted.append(str(path.relative_to(ROOT)))
            for path in unexpected_files(expected_res, expected_out):
                drifted.append(str(path.relative_to(ROOT)))
            if drifted:
                print("the icon assets drifted:", file=sys.stderr)
                for name in sorted(set(drifted)):
                    print(f"  {name}", file=sys.stderr)
                return 1
        print("the 1024x1024 nodpi icon assets are up to date")
        return 0

    rendered = render(RES, OUT)
    for path, (data, _) in sorted(rendered.items()):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    for path in stale_files():
        if path.exists():
            path.unlink()
    for density in DENSITY_BUCKETS:
        bucket = RES / f"mipmap-{density}"
        if bucket.is_dir() and not any(bucket.iterdir()):
            bucket.rmdir()
    print(f"wrote {len(rendered)} unresized {ICON_SIDE}x{ICON_SIDE} icon files")
    for path in sorted(rendered):
        print(f"  {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
