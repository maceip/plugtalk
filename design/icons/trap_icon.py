#!/usr/bin/env python3
"""Generate every Trap icon asset, adaptive layer, monochrome glyph, and lockup.

    python3 design/icons/trap_icon.py           # write the files
    python3 design/icons/trap_icon.py --check   # fail when they drifted

The canonical V3 cutout artwork lives in `design/icons/src/trap-icon.webp`
alongside its lossless RGBA decode `design/icons/src/trap-icon.png` (kept as
lossless PNG so cross-distro `libwebp` decoder revisions can never introduce
1-LSB YUV drift in CI).

Generated outputs:
  - Legacy & round launcher icons (`mdpi`..`xxxhdpi` `ic_launcher.png` &
    `ic_launcher_round.png`): unmodified resized artwork (`48px`..`192px`).
  - Adaptive 108dp icon layers across all densities (`mdpi` `108px` through
    `xxxhdpi` `432px`):
      * `ic_launcher_foreground.png` — unmodified artwork fitted inside the
        72dp safe zone so launcher masks never clip the 3D breakout lettering.
      * `ic_launcher_background.png` — deep obsidian-plum `#0D080B` backdrop
        sampled directly from the V3 shutter shadow palette.
      * `ic_launcher_monochrome.png` — crisp single-channel white alpha mask of
        the neon shutter `TR/AP` letterforms for Android 13+ themed icons.
  - Interface & store exports: `drawable-nodpi/ov_trap_mark.png` (`512px`),
    `play-store-512.png`, `play-store-1024.png`, and
    `ic_launcher_monochrome_512.png`.
  - Light & dark wordmark lockups (`trap-lockup.png`/`.svg` and
    `trap-lockup-dark.png`/`.svg`).

Only Pillow is needed; it is in the image of the builder.
"""

from __future__ import annotations

import argparse
import base64
import io
import math
import re
import sys
import tempfile
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError as exc:  # pragma: no cover - the dependency is documented
    raise SystemExit(f"trap_icon.py needs Pillow: {exc}")

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SRC_WEBP = HERE / "src" / "trap-icon.webp"
SRC_PNG = HERE / "src" / "trap-icon.png"
OUT = HERE / "out"
RES = ROOT / "android" / "app" / "src" / "main" / "res"

# Standard Android 48dp legacy/round launcher icon pixel sizes per density.
DENSITIES: dict[str, int] = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Standard Android 108dp adaptive icon canvas pixel sizes per density.
ADAPTIVE_DENSITIES: dict[str, int] = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

# Adaptive icon geometry (108dp canvas, 72dp visible safe-zone diameter).
ADAPTIVE_DP = 108
ADAPTIVE_VISIBLE_DP = 72
# Deep obsidian-plum (#0D080B) sampled from the V3 cutout shutter shadow.
ADAPTIVE_BG_RGBA = (0x0D, 0x08, 0x0B, 0xFF)

# Wordmark lockup typography & V3 cutout-matched inks.
WORDMARK = "Trap"
FONT = HERE / "fonts" / "Trap-Bold.ttf"
INK_LIGHT_SURFACE = (0x18, 0x0D, 0x12)
INK_DARK_SURFACE = (0xF5, 0xEE, 0xF4)


# ------------------------------------------------------------------- the source


def _source_artwork() -> Image.Image:
    """Load the canonical unmodified RGBA Trap icon artwork."""
    if not SRC_WEBP.exists():
        raise SystemExit(f"the canonical source WebP is missing: {SRC_WEBP}")
    if not SRC_PNG.exists():
        raise SystemExit(f"the lossless source PNG is missing: {SRC_PNG}")
    return Image.open(io.BytesIO(SRC_PNG.read_bytes())).convert("RGBA")


def resized(image: Image.Image, side: int) -> Image.Image:
    """Resize a square RGBA image cleanly using Lanczos resampling."""
    if image.size == (side, side):
        return image.copy()
    return image.resize((side, side), Image.LANCZOS)


def _max_alpha_radius(image: Image.Image) -> float:
    """Maximum distance from image centre of any non-transparent pixel."""
    width, height = image.size
    cx = (width - 1) / 2.0
    cy = (height - 1) / 2.0
    alpha = image.getchannel("A").load()
    max_r = 0.0
    for y in range(height):
        for x in range(width):
            if alpha[x, y] > 0:
                r = math.hypot(x - cx, y - cy)
                if r > max_r:
                    max_r = r
    return max_r


def fitted(image: Image.Image, canvas: int, max_r: float) -> Image.Image:
    """Centre the unmodified resized icon inside the 72dp adaptive safe zone.

    Every non-transparent pixel stays strictly inside the circular 72dp mask
    (with a 1px margin) so launchers never clip any edge of the 3D lettering.
    """
    visible_radius = (canvas * ADAPTIVE_VISIBLE_DP / ADAPTIVE_DP) / 2.0 - 1.0
    if max_r <= 0.0:
        side = int(round(visible_radius * 2.0))
    else:
        side = int(math.floor(visible_radius * (min(image.size) / max_r)))
    # Keep `side` even so `(canvas - side) // 2` centres with zero subpixel shift.
    side -= side % 2
    side = max(2, min(canvas, side))
    art = resized(image, side)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    offset = (canvas - side) // 2
    out.alpha_composite(art, (offset, offset))
    return out


def monochrome_source(source: Image.Image) -> Image.Image:
    """Extract a crisp white-on-transparent monochrome glyph of the TR/AP mark.

    Both electric cyan (#5BCEF1) and hot neon magenta (#F27AF3) interiors of the
    stacked `TR / AP` graffiti letterforms carry high blue-channel intensity
    (`B >= 135`) while the warm shutter disc, 3D plum extrusion (#361821), and
    horizontal shutter slats sit well below `B <= 115`.  Ramping smoothly across
    that boundary yields an anti-aliased single-color glyph preserving every
    letter counter and shutter stripe for Android 13+ themed icons.
    """
    width, height = source.size
    raw = source.tobytes()
    out = bytearray(len(raw))
    low = 118
    span = 68  # 118..186 smooth anti-aliased ramp
    for idx in range(0, len(raw), 4):
        r = raw[idx]
        g = raw[idx + 1]
        b = raw[idx + 2]
        a = raw[idx + 3]
        if a == 0 or b <= low:
            continue
        # Reject any neutral warm highlights outside the cyan/magenta neon fill
        # where green exceeds blue and red exceeds blue simultaneously.
        neon_signal = b - (min(r, g) // 5)
        if neon_signal <= low:
            continue
        ramp = min(255, ((neon_signal - low) * 255) // span)
        mono_a = (ramp * a) // 255
        if mono_a > 0:
            out[idx] = 255
            out[idx + 1] = 255
            out[idx + 2] = 255
            out[idx + 3] = mono_a
    return Image.frombytes("RGBA", (width, height), bytes(out))


# ------------------------------------------------------------------- lockups


def _font(size: int) -> ImageFont.FreeTypeFont:
    """The font of the wordmark: the subset that is part of the repository."""
    if not FONT.exists():
        raise SystemExit(f"the font of the wordmark is missing: {FONT}")
    return ImageFont.truetype(str(FONT), size)


def lockup_png(
    source: Image.Image,
    ink_rgb: tuple[int, int, int] = INK_LIGHT_SURFACE,
    backdrop_rgba: tuple[int, int, int, int] = (0, 0, 0, 0),
    width: int = 1024,
) -> Image.Image:
    """The unmodified resized icon next to the Trap wordmark."""
    height = int(round(width * 0.28))
    pad = int(round(height * 0.12)) if backdrop_rgba[3] > 0 else 0
    icon = resized(source, height)
    font = _font(int(height * 0.40))
    text_width = int(font.getlength(WORDMARK))
    gap = int(height * 0.16)
    total_w = pad * 2 + height + gap + text_width + int(height * 0.1)
    total_h = pad * 2 + height
    canvas = Image.new("RGBA", (total_w, total_h), backdrop_rgba)
    canvas.alpha_composite(icon, (pad, pad))
    draw = ImageDraw.Draw(canvas)
    box = draw.textbbox((0, 0), WORDMARK, font=font)
    draw.text(
        (pad + height + gap, pad + (height - (box[3] - box[1])) / 2 - box[1]),
        WORDMARK,
        font=font,
        fill=ink_rgb + (255,),
    )
    return canvas


def lockup_svg(
    source: Image.Image,
    ink_hex: str = "#180D12",
    backdrop_hex: str | None = None,
) -> str:
    """The lockup as a vector, embedding the unmodified 256px icon raster."""
    icon = resized(source, 256)
    buffer = io.BytesIO()
    icon.save(buffer, format="PNG", optimize=True)
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    font = base64.b64encode(FONT.read_bytes()).decode("ascii")
    bg_rect = (
        f'\n  <rect x="0" y="0" width="1024" height="296" rx="28" fill="{backdrop_hex}"/>'
        if backdrop_hex
        else ""
    )
    svg_h = 296 if backdrop_hex else 256
    img_x = 20 if backdrop_hex else 0
    img_y = 20 if backdrop_hex else 0
    txt_x = 316 if backdrop_hex else 296
    txt_y = 186 if backdrop_hex else 166
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
     width="1024" height="{svg_h}" viewBox="0 0 1024 {svg_h}">
  <title>{WORDMARK}</title>
  <defs>
    <style>
      @font-face {{
        font-family: "Trap";
        src: url("data:font/ttf;base64,{font}") format("truetype");
      }}
      .wordmark {{ font-family: "Trap", sans-serif; font-weight: bold; }}
    </style>
  </defs>{bg_rect}
  <image x="{img_x}" y="{img_y}" width="256" height="256"
         xlink:href="data:image/png;base64,{encoded}"/>
  <text x="{txt_x}" y="{txt_y}" class="wordmark" font-size="102" fill="{ink_hex}">{WORDMARK}</text>
</svg>
"""


# ------------------------------------------------------------------- writing


def render(target_res: Path, target_out: Path) -> dict[Path, bytes]:
    """Render every icon asset and return the files with their content."""
    source = _source_artwork()
    max_r = _max_alpha_radius(source)
    mono = monochrome_source(source)
    mono_max_r = _max_alpha_radius(mono)
    files: dict[Path, bytes] = {}

    def put(path: Path, data: bytes) -> None:
        files[path] = data

    def put_png(path: Path, image: Image.Image) -> None:
        buffer = io.BytesIO()
        image.save(buffer, format="PNG", optimize=True)
        put(path, buffer.getvalue())

    # 1. Legacy & round launcher icons across all 5 densities (48px..192px).
    for density, px in DENSITIES.items():
        launcher_png = resized(source, px)
        buffer = io.BytesIO()
        launcher_png.save(buffer, format="PNG", optimize=True)
        encoded = buffer.getvalue()
        put(target_res / f"mipmap-{density}" / "ic_launcher.png", encoded)
        put(target_res / f"mipmap-{density}" / "ic_launcher_round.png", encoded)

    # 2. Adaptive 108dp foreground, background & Android 13+ monochrome layers
    #    across all 5 densities (108px..432px).
    for density, canvas_px in ADAPTIVE_DENSITIES.items():
        put_png(
            target_res / f"mipmap-{density}" / "ic_launcher_foreground.png",
            fitted(source, canvas_px, max_r),
        )
        put_png(
            target_res / f"mipmap-{density}" / "ic_launcher_background.png",
            Image.new("RGBA", (canvas_px, canvas_px), ADAPTIVE_BG_RGBA),
        )
        put_png(
            target_res / f"mipmap-{density}" / "ic_launcher_monochrome.png",
            fitted(mono, canvas_px, max(max_r, mono_max_r)),
        )

    # 3. Interface mark and high-res store / monochrome exports.
    icon_512 = resized(source, 512)
    put_png(target_res / "drawable-nodpi" / "ov_trap_mark.png", icon_512)
    put_png(target_out / "play-store-1024.png", resized(source, 1024))
    put_png(target_out / "play-store-512.png", icon_512)
    put_png(target_out / "ic_launcher_monochrome_512.png", resized(mono, 512))

    # 4. Light-surface and dark-surface wordmark lockups (PNG + SVG).
    put_png(
        target_out / "trap-lockup.png",
        lockup_png(source, ink_rgb=INK_LIGHT_SURFACE, backdrop_rgba=(0, 0, 0, 0)),
    )
    put(
        target_out / "trap-lockup.svg",
        lockup_svg(source, ink_hex="#180D12", backdrop_hex=None).encode("utf-8"),
    )
    put_png(
        target_out / "trap-lockup-dark.png",
        lockup_png(source, ink_rgb=INK_DARK_SURFACE, backdrop_rgba=ADAPTIVE_BG_RGBA),
    )
    put(
        target_out / "trap-lockup-dark.svg",
        lockup_svg(source, ink_hex="#F5EEF4", backdrop_hex="#0D080B").encode("utf-8"),
    )

    return files


def stale_files() -> list[Path]:
    """Files from earlier icon pipelines that must not linger in the tree."""
    return [
        RES / "drawable" / "ic_launcher_background.xml",
        OUT / "ic_launcher_monochrome_192.png",
    ]


def unexpected_files(expected_res: set[Path], expected_out: set[Path]) -> list[Path]:
    """Icon files in `res/` or `design/icons/out/` that the pipeline does not write."""
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


_SVG_PNG_RE = re.compile(r"data:image/png;base64,([A-Za-z0-9+/=]+)")


def _png_pixels_equal(actual: bytes, expected: bytes) -> bool:
    if actual == expected:
        return True
    with Image.open(io.BytesIO(actual)) as im_cur, Image.open(io.BytesIO(expected)) as im_exp:
        rgba_cur = im_cur.convert("RGBA")
        rgba_exp = im_exp.convert("RGBA")
        return rgba_cur.size == rgba_exp.size and rgba_cur.tobytes() == rgba_exp.tobytes()


def _asset_matches(current: Path, expected_data: bytes) -> bool:
    """Compare rendered icon assets across platforms with differing zlib PNG encoders."""
    if not current.exists():
        return False
    current_bytes = current.read_bytes()
    if current_bytes == expected_data:
        return True
    suffix = current.suffix.lower()
    if suffix == ".png":
        return _png_pixels_equal(current_bytes, expected_data)
    if suffix == ".svg":
        cur_text = current_bytes.decode("utf-8", errors="strict")
        exp_text = expected_data.decode("utf-8", errors="strict")
        m_cur = _SVG_PNG_RE.search(cur_text)
        m_exp = _SVG_PNG_RE.search(exp_text)
        if not m_cur or not m_exp:
            return False
        cur_skeleton = _SVG_PNG_RE.sub("data:image/png;base64,<PNG>", cur_text, count=1)
        exp_skeleton = _SVG_PNG_RE.sub("data:image/png;base64,<PNG>", exp_text, count=1)
        if cur_skeleton != exp_skeleton:
            return False
        return _png_pixels_equal(
            base64.b64decode(m_cur.group(1)),
            base64.b64decode(m_exp.group(1)),
        )
    return False


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="compare the files of the tree with a fresh rendering",
    )
    arguments = parser.parse_args(argv)

    if arguments.check:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            files = render(root / "res", root / "out")
            stale: list[str] = []
            expected_res: set[Path] = set()
            expected_out: set[Path] = set()
            for path, data in sorted(files.items()):
                relative = path.relative_to(root)
                if relative.parts[0] == "res":
                    current = RES / relative.relative_to("res")
                    expected_res.add(current)
                else:
                    current = OUT / relative.relative_to("out")
                    expected_out.add(current)
                if not _asset_matches(current, data):
                    stale.append(str(current.relative_to(ROOT)))
            for path in stale_files():
                if path.exists():
                    stale.append(str(path.relative_to(ROOT)))
            for path in unexpected_files(expected_res, expected_out):
                stale.append(str(path.relative_to(ROOT)))
            if stale:
                print("the icon lockups drifted:", file=sys.stderr)
                for name in sorted(set(stale)):
                    print(f"  {name}", file=sys.stderr)
                return 1
        print("the icon lockups are up to date")
        return 0

    files = render(RES, OUT)
    for path, data in sorted(files.items()):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    for path in stale_files():
        if path.exists():
            path.unlink()
    print(f"wrote {len(files)} files")
    for path in sorted(files):
        print(f"  {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
