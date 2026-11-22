#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 DroidAmp Contributors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.

"""
generate_showcase_skins.py — Deterministic generator for DroidAmp's two built-in
Augmented `.wsz` showcase skins (`ADR-028`):
  1. `amber_hifi_rack.wsz` ("Amber Hi-Fi Rack" — brushed champagne-bronze & glowing amber VFD)
  2. `cyber_ice_99.wsz`    ("Cyber Ice '99"    — Y2K cobalt/arctic-silver MiniDisc pill chrome)

Each output `.wsz` archive is a 100% valid Winamp `.wsz` ZIP containing:
  - `droidamp.json` (`schemaVersion: 1`, `shapeStyle`, complete WCAG-verified palettes, `sprites`)
  - 46 crisp pixel-art RGBA PNGs under `sprites/*.png` with true alpha-transparent outer corners
  - Legacy-compatible `viscolor.txt`, `pledit.txt`, and a `275x116` 24-bit `main.bmp`
"""

from __future__ import annotations

import argparse
import io
import json
from pathlib import Path
import sys
from typing import Dict, List, Optional, Sequence, Tuple
import zipfile

# Allow running from any working directory while sharing pure-Python encoders from `wsz_augment.py`
_THIS_DIR = Path(__file__).resolve().parent
if str(_THIS_DIR) not in sys.path:
    sys.path.insert(0, str(_THIS_DIR))

from wsz_augment import (  # noqa: E402
    RgbaImage,
    encode_bmp_24,
    encode_rgba_png,
    hex_to_rgb,
)

RGBA = Tuple[int, int, int, int]
RGB = Tuple[int, int, int]

# Fixed ZIP timestamp so generated `.wsz` binaries are 100% deterministic byte-for-byte
DETERMINISTIC_ZIP_DT = (2026, 1, 1, 0, 0, 0)


# ---------------------------------------------------------------------------
# Crisp 5x9 Pixel Bitmap Font (Uppercase A-Z, 0-9, '/', ':', '-', "'", ' ')
# Proportioned specifically for late-90s hardware rack buttons & titlebars.
# ---------------------------------------------------------------------------

FONT_5X9: Dict[str, Tuple[str, ...]] = {
    "A": (
        ".###.",
        "##.##",
        "##.##",
        "##.##",
        "#####",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
    ),
    "B": (
        "####.",
        "##.##",
        "##.##",
        "####.",
        "####.",
        "##.##",
        "##.##",
        "##.##",
        "####.",
    ),
    "C": (
        ".####",
        "##...",
        "##...",
        "##...",
        "##...",
        "##...",
        "##...",
        "##...",
        ".####",
    ),
    "D": (
        "####.",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "####.",
    ),
    "E": (
        "#####",
        "##...",
        "##...",
        "##...",
        "####.",
        "##...",
        "##...",
        "##...",
        "#####",
    ),
    "F": (
        "#####",
        "##...",
        "##...",
        "##...",
        "####.",
        "##...",
        "##...",
        "##...",
        "##...",
    ),
    "G": (
        ".####",
        "##...",
        "##...",
        "##...",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        ".####",
    ),
    "H": (
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "#####",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
    ),
    "I": (
        "#####",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        "#####",
    ),
    "J": (
        "..###",
        "...##",
        "...##",
        "...##",
        "...##",
        "...##",
        "##.##",
        "##.##",
        ".###.",
    ),
    "K": (
        "##.##",
        "##.##",
        "##.#.",
        "###..",
        "###..",
        "##.#.",
        "##.##",
        "##.##",
        "##.##",
    ),
    "L": (
        "##...",
        "##...",
        "##...",
        "##...",
        "##...",
        "##...",
        "##...",
        "##...",
        "#####",
    ),
    "M": (
        "#...#",
        "##.##",
        "#####",
        "#####",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
    ),
    "N": (
        "#...#",
        "##..#",
        "###.#",
        "###.#",
        "#.###",
        "#..##",
        "#..##",
        "#...#",
        "#...#",
    ),
    "O": (
        ".###.",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        ".###.",
    ),
    "P": (
        "####.",
        "##.##",
        "##.##",
        "##.##",
        "####.",
        "##...",
        "##...",
        "##...",
        "##...",
    ),
    "Q": (
        ".###.",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "#####",
        ".###.",
        "...##",
    ),
    "R": (
        "####.",
        "##.##",
        "##.##",
        "##.##",
        "####.",
        "###..",
        "##.#.",
        "##.##",
        "##.##",
    ),
    "S": (
        ".####",
        "##...",
        "##...",
        ".###.",
        "..###",
        "...##",
        "...##",
        "##.##",
        ".###.",
    ),
    "T": (
        "#####",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
    ),
    "U": (
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        ".###.",
    ),
    "V": (
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        ".###.",
        ".###.",
        "..#..",
    ),
    "W": (
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "#####",
        "#####",
        "##.##",
        "#...#",
    ),
    "X": (
        "##.##",
        "##.##",
        ".###.",
        "..#..",
        "..#..",
        ".###.",
        "##.##",
        "##.##",
        "##.##",
    ),
    "Y": (
        "##.##",
        "##.##",
        "##.##",
        ".###.",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
    ),
    "Z": (
        "#####",
        "...##",
        "..##.",
        "..##.",
        ".##..",
        ".##..",
        "##...",
        "##...",
        "#####",
    ),
    "0": (
        ".###.",
        "##.##",
        "##.##",
        "#.###",
        "#####",
        "###.#",
        "##.##",
        "##.##",
        ".###.",
    ),
    "1": (
        "..#..",
        ".##..",
        "###..",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        "..#..",
        ".###.",
    ),
    "2": (
        ".###.",
        "##.##",
        "...##",
        "..##.",
        ".##..",
        "##...",
        "##...",
        "##...",
        "#####",
    ),
    "3": (
        "####.",
        "...##",
        "...##",
        ".###.",
        "...##",
        "...##",
        "...##",
        "##.##",
        ".###.",
    ),
    "4": (
        "..##.",
        ".###.",
        "##.#.",
        "##.#.",
        "#####",
        "...#.",
        "...#.",
        "...#.",
        "...#.",
    ),
    "5": (
        "#####",
        "##...",
        "##...",
        "####.",
        "...##",
        "...##",
        "...##",
        "##.##",
        ".###.",
    ),
    "6": (
        ".###.",
        "##...",
        "##...",
        "####.",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        ".###.",
    ),
    "7": (
        "#####",
        "...##",
        "...##",
        "..##.",
        "..##.",
        ".##..",
        ".##..",
        ".##..",
        ".##..",
    ),
    "8": (
        ".###.",
        "##.##",
        "##.##",
        ".###.",
        ".###.",
        "##.##",
        "##.##",
        "##.##",
        ".###.",
    ),
    "9": (
        ".###.",
        "##.##",
        "##.##",
        "##.##",
        ".####",
        "...##",
        "...##",
        "...##",
        ".###.",
    ),
    "/": (
        "....#",
        "...##",
        "...#.",
        "..##.",
        "..#..",
        ".##..",
        ".#...",
        "##...",
        "#....",
    ),
    ":": (
        "..",
        "..",
        "##",
        "##",
        "..",
        "##",
        "##",
        "..",
        "..",
    ),
    "'": (
        "##",
        "##",
        ".#",
        "#.",
        "..",
        "..",
        "..",
        "..",
        "..",
    ),
    "-": (
        ".....",
        ".....",
        ".....",
        ".....",
        "#####",
        ".....",
        ".....",
        ".....",
        ".....",
    ),
    " ": (
        "...",
        "...",
        "...",
        "...",
        "...",
        "...",
        "...",
        "...",
        "...",
    ),
}


def _rgba(hex_or_rgb: str | RGB, alpha: int = 255) -> RGBA:
    rgb = hex_to_rgb(hex_or_rgb) if isinstance(hex_or_rgb, str) else hex_or_rgb
    return (rgb[0], rgb[1], rgb[2], alpha)


def blend_rgba(img: RgbaImage, x: int, y: int, src: RGBA) -> None:
    """Alpha-composite `src` over `img` at `(x, y)` without punching holes in opaque pixels."""
    if not (0 <= x < img.width and 0 <= y < img.height):
        return
    sa = src[3]
    if sa <= 0:
        return
    if sa >= 255:
        img.set_rgba(x, y, src)
        return
    dr, dg, db, da = img.get_rgba(x, y)
    if da == 0:
        # Never bleed translucent drop-shadows outside alpha-masked corners
        return
    inv = 255 - sa
    out_r = (src[0] * sa + dr * inv) // 255
    out_g = (src[1] * sa + dg * inv) // 255
    out_b = (src[2] * sa + db * inv) // 255
    out_a = max(da, sa)
    img.set_rgba(x, y, (out_r, out_g, out_b, out_a))


def _lerp_rgb(a: RGB, b: RGB, t: float) -> RGB:
    tc = max(0.0, min(1.0, t))
    return (
        int(round(a[0] + (b[0] - a[0]) * tc)),
        int(round(a[1] + (b[1] - a[1]) * tc)),
        int(round(a[2] + (b[2] - a[2]) * tc)),
    )


def measure_pixel_text(text: str, spacing: int = 1) -> Tuple[int, int]:
    if not text:
        return 0, 9
    total_w = 0
    for idx, ch in enumerate(text.upper()):
        glyph = FONT_5X9.get(ch, FONT_5X9[" "])
        total_w += len(glyph[0])
        if idx + 1 < len(text):
            total_w += spacing
    return total_w, 9


def draw_pixel_text(
    img: RgbaImage,
    text: str,
    x: int,
    y: int,
    fg: RGBA,
    shadow: Optional[RGBA] = None,
    spacing: int = 1,
) -> None:
    if shadow is not None:
        cursor_x = x
        for ch in text.upper():
            glyph = FONT_5X9.get(ch, FONT_5X9[" "])
            gw = len(glyph[0])
            for row_idx, row_str in enumerate(glyph):
                for col_idx, cell in enumerate(row_str):
                    if cell == "#":
                        blend_rgba(img, cursor_x + col_idx + 1, y + row_idx + 1, shadow)
            cursor_x += gw + spacing

    cursor_x = x
    for ch in text.upper():
        glyph = FONT_5X9.get(ch, FONT_5X9[" "])
        gw = len(glyph[0])
        for row_idx, row_str in enumerate(glyph):
            for col_idx, cell in enumerate(row_str):
                if cell == "#":
                    blend_rgba(img, cursor_x + col_idx, y + row_idx, fg)
        cursor_x += gw + spacing


# ---------------------------------------------------------------------------
# Shape Masks & Hardware Bevel Keycap Renderers
# ---------------------------------------------------------------------------


def _inside_chamfer(x: int, y: int, w: int, h: int, chamfer: int) -> bool:
    dx = min(x, w - 1 - x)
    dy = min(y, h - 1 - y)
    return (dx + dy) >= chamfer


def _inside_rounded(x: int, y: int, w: int, h: int, radius: int) -> bool:
    if radius <= 0:
        return True
    cx = radius if x < radius else (w - 1 - radius if x > w - 1 - radius else x)
    cy = radius if y < radius else (h - 1 - radius if y > h - 1 - radius else y)
    if cx == x and cy == y:
        return True
    # Sub-pixel centered circle check so outer corners are cleanly alpha=0
    dist_sq = (x - cx) ** 2 + (y - cy) ** 2
    return dist_sq <= (radius - 0.35) ** 2


def render_amber_shell(
    width: int,
    height: int,
    *,
    face_top: RGB,
    face_bottom: RGB,
    hi_outer: RGB,
    hi_inner: RGB,
    sh_inner: RGB,
    sh_outer: RGB,
    pressed: bool = False,
    chamfer: int = 2,
) -> RgbaImage:
    """Render a warm brushed champagne-bronze chamfered hardware keycap with alpha corners."""
    img = RgbaImage.blank(width, height, (0, 0, 0, 0))
    inside = [[_inside_chamfer(x, y, width, height, chamfer) for x in range(width)] for y in range(height)]

    def is_in(px: int, py: int) -> bool:
        return 0 <= px < width and 0 <= py < height and inside[py][px]

    tl_outer = sh_outer if pressed else hi_outer
    tl_inner = sh_inner if pressed else hi_inner
    br_outer = hi_outer if pressed else sh_outer
    br_inner = hi_inner if pressed else sh_inner

    for y in range(height):
        vt = y / max(1, height - 1)
        base_rgb = _lerp_rgb(face_bottom if pressed else face_top, face_top if pressed else face_bottom, vt)
        # Subtle brushed horizontal metal grain
        grain = ((y * 7) % 5) - 2
        for x in range(width):
            if not inside[y][x]:
                continue
            micro = (((x * 13 + y * 31) % 3) - 1) + grain
            fill = (
                max(0, min(255, base_rgb[0] + micro)),
                max(0, min(255, base_rgb[1] + micro)),
                max(0, min(255, base_rgb[2] + micro)),
            )

            # Determine outer / inner bevel membership along chamfered perimeter
            top_or_left_1 = (not is_in(x, y - 1)) or (not is_in(x - 1, y))
            bot_or_right_1 = (not is_in(x, y + 1)) or (not is_in(x + 1, y))
            top_or_left_2 = (not is_in(x, y - 2)) or (not is_in(x - 2, y))
            bot_or_right_2 = (not is_in(x, y + 2)) or (not is_in(x + 2, y))

            if top_or_left_1:
                rgb = tl_outer
            elif bot_or_right_1:
                rgb = br_outer
            elif top_or_left_2:
                rgb = tl_inner
            elif bot_or_right_2:
                rgb = br_inner
            else:
                rgb = fill
            img.set_rgba(x, y, (rgb[0], rgb[1], rgb[2], 255))

    return img


def render_cyber_pill_shell(
    width: int,
    height: int,
    *,
    face_top: RGB,
    face_mid: RGB,
    face_bottom: RGB,
    rim_light: RGB,
    rim_dark: RGB,
    glow_rgb: Optional[RGB] = None,
    pressed: bool = False,
    radius: int = 6,
) -> RgbaImage:
    """Render a late-90s MiniDisc cobalt/silver rounded-pill button with transparent outer corners."""
    img = RgbaImage.blank(width, height, (0, 0, 0, 0))
    r = min(radius, min(width, height) // 2 - 1)
    inside = [[_inside_rounded(x, y, width, height, r) for x in range(width)] for y in range(height)]

    def is_in(px: int, py: int) -> bool:
        return 0 <= px < width and 0 <= py < height and inside[py][px]

    top_rim = rim_dark if pressed else rim_light
    bot_rim = rim_light if pressed else rim_dark

    for y in range(height):
        vt = y / max(1, height - 1)
        if pressed:
            grad = _lerp_rgb(face_bottom, face_mid, vt)
        else:
            # Y2K acrylic/anodized specular upper sheen
            if vt < 0.45:
                grad = _lerp_rgb(face_top, face_mid, vt / 0.45)
            else:
                grad = _lerp_rgb(face_mid, face_bottom, (vt - 0.45) / 0.55)

        for x in range(width):
            if not inside[y][x]:
                continue
            top_edge = (not is_in(x, y - 1)) or (not is_in(x - 1, y))
            bot_edge = (not is_in(x, y + 1)) or (not is_in(x + 1, y))
            inner_top = (not is_in(x, y - 2)) and glow_rgb is not None

            if top_edge:
                rgb = top_rim
            elif bot_edge:
                rgb = bot_rim
            elif inner_top and glow_rgb is not None:
                rgb = glow_rgb
            else:
                rgb = grad
            img.set_rgba(x, y, (rgb[0], rgb[1], rgb[2], 255))

    return img


# ---------------------------------------------------------------------------
# Hardware Glyphs & LED Indicators
# ---------------------------------------------------------------------------


def draw_led_lamp(
    img: RgbaImage,
    x: int,
    y: int,
    w: int,
    h: int,
    *,
    is_on: bool,
    led_on: RGB,
    led_core: RGB,
    led_off: RGB,
    bezel_dark: RGB,
    bezel_light: RGB,
) -> None:
    # Recessed bezel frame
    for bx in range(x, x + w):
        img.set_rgba(bx, y, _rgba(bezel_dark))
        img.set_rgba(bx, y + h - 1, _rgba(bezel_light))
    for by in range(y, y + h):
        img.set_rgba(x, by, _rgba(bezel_dark))
        img.set_rgba(x + w - 1, by, _rgba(bezel_light))

    fill = led_on if is_on else led_off
    for iy in range(y + 1, y + h - 1):
        for ix in range(x + 1, x + w - 1):
            img.set_rgba(ix, iy, _rgba(fill))

    if is_on and w >= 5 and h >= 6:
        # Specular bright phosphor core
        for iy in range(y + 2, y + h - 2):
            for ix in range(x + 2, x + w - 2):
                img.set_rgba(ix, iy, _rgba(led_core))


def draw_transport_glyph(
    img: RgbaImage,
    kind: str,
    fg: RGBA,
    shadow: RGBA,
    shift: int = 0,
) -> None:
    cx = img.width // 2 + shift
    cy = img.height // 2 + shift
    pts: List[Tuple[int, int]] = []

    def plot(px: int, py: int) -> None:
        pts.append((px, py))

    def rect(x0: int, y0: int, rw: int, rh: int) -> None:
        for yy in range(y0, y0 + rh):
            for xx in range(x0, x0 + rw):
                pts.append((xx, yy))

    if kind == "play":
        # Crisp right-pointing triangle (11px tall, 9px wide)
        for dy in range(-5, 6):
            span = 5 - abs(dy)
            for dx in range(span * 2 - 1):
                if dx <= 8:
                    plot(cx - 4 + dx, cy + dy)
    elif kind == "pause":
        rect(cx - 5, cy - 5, 4, 11)
        rect(cx + 2, cy - 5, 4, 11)
    elif kind == "stop":
        rect(cx - 5, cy - 5, 10, 10)
    elif kind == "prev":
        rect(cx - 6, cy - 5, 2, 11)
        for dy in range(-5, 6):
            w_row = (5 - abs(dy)) + 1
            for dx in range(w_row):
                plot(cx + 1 - dx, cy + dy)
                plot(cx + 6 - dx, cy + dy)
    elif kind == "next":
        for dy in range(-5, 6):
            w_row = (5 - abs(dy)) + 1
            for dx in range(w_row):
                plot(cx - 6 + dx, cy + dy)
                plot(cx - 1 + dx, cy + dy)
        rect(cx + 5, cy - 5, 2, 11)
    elif kind == "eject":
        for row in range(6):
            for dx in range(-row, row + 1):
                plot(cx + dx, cy - 6 + row)
        rect(cx - 5, cy + 2, 11, 3)

    for px, py in pts:
        blend_rgba(img, px + 1, py + 1, shadow)
    for px, py in pts:
        blend_rgba(img, px, py, fg)


def draw_plus_glyph(img: RgbaImage, fg: RGBA, shadow: RGBA, shift: int = 0) -> None:
    cx = img.width // 2 + shift
    cy = img.height // 2 + shift
    for d in range(-4, 5):
        for t in (-1, 0):
            blend_rgba(img, cx + d + 1, cy + t + 1, shadow)
            blend_rgba(img, cx + t + 1, cy + d + 1, shadow)
    for d in range(-4, 5):
        for t in (-1, 0):
            blend_rgba(img, cx + d, cy + t, fg)
            blend_rgba(img, cx + t, cy + d, fg)


def draw_play_small_glyph(img: RgbaImage, fg: RGBA, shadow: RGBA, shift: int = 0) -> None:
    cx = img.width // 2 + shift
    cy = img.height // 2 + shift
    for dy in range(-4, 5):
        span = (4 - abs(dy)) * 2 + 1
        for dx in range(min(7, span)):
            blend_rgba(img, cx - 3 + dx + 1, cy + dy + 1, shadow)
    for dy in range(-4, 5):
        span = (4 - abs(dy)) * 2 + 1
        for dx in range(min(7, span)):
            blend_rgba(img, cx - 3 + dx, cy + dy, fg)


def draw_gear_glyph(img: RgbaImage, fg: RGBA, shadow: RGBA, shift: int = 0) -> None:
    """Crisp 15x15 8-tooth machined hardware gear icon."""
    pattern = (
        "......###......",
        "..##..###..##..",
        ".####.###.####.",
        "..###########..",
        "...#########...",
        "...###...###...",
        "#####.....#####",
        "#####.....#####",
        "#####.....#####",
        "...###...###...",
        "...#########...",
        "..###########..",
        ".####.###.####.",
        "..##..###..##..",
        "......###......",
    )
    ox = (img.width - 15) // 2 + shift
    oy = (img.height - 15) // 2 + shift
    for ry, row in enumerate(pattern):
        for rx, ch in enumerate(row):
            if ch == "#":
                blend_rgba(img, ox + rx + 1, oy + ry + 1, shadow)
    for ry, row in enumerate(pattern):
        for rx, ch in enumerate(row):
            if ch == "#":
                blend_rgba(img, ox + rx, oy + ry, fg)


def draw_chevron(
    img: RgbaImage,
    cx: int,
    cy: int,
    direction: str,
    fg: RGBA,
    shadow: RGBA,
) -> None:
    rows = 5
    for r in range(rows):
        span = r if direction == "up" else (rows - 1 - r)
        py = cy - 2 + r
        for dx in range(-span, span + 1):
            blend_rgba(img, cx + dx + 1, py + 1, shadow)
    for r in range(rows):
        span = r if direction == "up" else (rows - 1 - r)
        py = cy - 2 + r
        for dx in range(-span, span + 1):
            blend_rgba(img, cx + dx, py, fg)


# ---------------------------------------------------------------------------
# Legacy-Compatible `viscolor.txt`, `pledit.txt` & `275x116` `main.bmp`
# ---------------------------------------------------------------------------


def format_viscolor_txt(viscolor_hex: Sequence[str], title: str) -> bytes:
    comments = [
        "background",
        "grid dots",
        *[f"spectrum {i}" for i in range(1, 17)],
        *[f"oscilloscope {i}" for i in range(1, 6)],
        "peak dots",
    ]
    lines = [f"// {title} — DroidAmp Augmented .wsz viscolor.txt"]
    for idx, hx in enumerate(viscolor_hex):
        r, g, b = hex_to_rgb(hx)
        lines.append(f"{r},{g},{b} // {idx}: {comments[idx]}")
    return ("\n".join(lines) + "\n").encode("utf-8")


def format_pledit_txt(playlist: Dict[str, str]) -> bytes:
    lines = [
        "[Text]",
        f"Normal={playlist['normalText']}",
        f"Current={playlist['currentText']}",
        f"NormalBG={playlist['normalBackground']}",
        f"SelectedBG={playlist['selectedBackground']}",
        f"mbBG={playlist['breadcrumbBackground']}",
        f"mbFG={playlist['breadcrumbText']}",
        "Font=Arial",
    ]
    return ("\n".join(lines) + "\n").encode("utf-8")


def build_legacy_main_bmp(
    chassis: Dict[str, str],
    bevel: Dict[str, str],
    vfd: Dict[str, str],
    title_text: str,
) -> bytes:
    """Build a valid 275x116 24-bit Winamp `main.bmp` matching the showcase skin palette."""
    w, h = 275, 116
    panel = hex_to_rgb(chassis["panelSurface"])
    panel_alt = hex_to_rgb(chassis["panelSurfaceAlt"])
    t_start = hex_to_rgb(chassis["titleBarActiveStart"])
    t_end = hex_to_rgb(chassis["titleBarActiveEnd"])
    t_text = _rgba(chassis["titleBarText"])
    hi = hex_to_rgb(bevel["highlightOuter"])
    sh = hex_to_rgb(bevel["shadowOuter"])
    well = hex_to_rgb(vfd["wellBackground"])
    phos = _rgba(vfd["phosphorPrimary"])

    img = RgbaImage.blank(w, h, (panel[0], panel[1], panel[2], 255))

    # Top 14px titlebar strip
    for y in range(14):
        for x in range(w):
            t = 1.0 - abs((x / (w - 1)) * 2.0 - 1.0)
            rgb = _lerp_rgb(t_start, t_end, t)
            img.set_rgba(x, y, (rgb[0], rgb[1], rgb[2], 255))

    tw, _ = measure_pixel_text(title_text, spacing=1)
    draw_pixel_text(img, title_text, (w - tw) // 2, 3, t_text, _rgba(sh, 200), spacing=1)

    # Outer chassis bevel
    for x in range(w):
        img.set_rgba(x, 0, _rgba(hi))
        img.set_rgba(x, h - 1, _rgba(sh))
    for y in range(h):
        img.set_rgba(0, y, _rgba(hi))
        img.set_rgba(w - 1, y, _rgba(sh))

    # Recessed VFD window (24, 24, 96, 40) + marquee slot (110, 24, 154, 16)
    for rx, ry, rw, rh in ((16, 22, 92, 42), (114, 22, 148, 16)):
        for yy in range(ry, ry + rh):
            for xx in range(rx, rx + rw):
                img.set_rgba(xx, yy, _rgba(well))

    draw_pixel_text(img, "00:00", 48, 28, phos, None, spacing=1)
    draw_pixel_text(img, "HI-FI READY", 122, 26, phos, None, spacing=1)

    # Lower transport deck recess
    for yy in range(84, 108):
        for xx in range(12, 263):
            img.set_rgba(xx, yy, _rgba(panel_alt))

    return encode_bmp_24(img)


# ---------------------------------------------------------------------------
# Complete 46-Sprite Pack Builder (Shared Geometry, Distinct Per-Skin Craft)
# ---------------------------------------------------------------------------


def _build_skin_sprites(
    *,
    style: str,  # "amber" | "cyber"
    chassis: Dict[str, str],
    bevel: Dict[str, str],
    vfd: Dict[str, str],
) -> Dict[str, bytes]:
    sprites: Dict[str, RgbaImage] = {}

    face_rgb = hex_to_rgb(bevel["buttonFace"])
    face_pressed_rgb = hex_to_rgb(bevel["buttonFacePressed"])
    hi_outer = hex_to_rgb(bevel["highlightOuter"])
    hi_inner = hex_to_rgb(bevel["highlightInner"])
    sh_inner = hex_to_rgb(bevel["shadowInner"])
    sh_outer = hex_to_rgb(bevel["shadowOuter"])
    well_dark = hex_to_rgb(bevel["wellBorderDark"])
    well_light = hex_to_rgb(bevel["wellBorderLight"])

    txt_normal = _rgba(bevel["buttonText"])
    txt_active = _rgba(bevel["buttonTextActive"])
    txt_shadow = _rgba(sh_outer, 235)

    led_on = hex_to_rgb(vfd["ledOn"])
    led_core = hex_to_rgb(vfd["phosphorBright"])
    led_off = hex_to_rgb(vfd["ledOff"])

    def make_keycap(w: int, h: int, active_or_pressed: bool, is_toggle_on: bool = False) -> RgbaImage:
        if style == "amber":
            top_c = _lerp_rgb(face_rgb, hi_inner, 0.28)
            bot_c = _lerp_rgb(face_rgb, sh_inner, 0.25)
            if active_or_pressed:
                top_c = _lerp_rgb(face_pressed_rgb, sh_outer, 0.20)
                bot_c = face_pressed_rgb
            return render_amber_shell(
                w,
                h,
                face_top=top_c,
                face_bottom=bot_c,
                hi_outer=hi_outer,
                hi_inner=hi_inner,
                sh_inner=sh_inner,
                sh_outer=sh_outer,
                pressed=active_or_pressed,
                chamfer=2,
            )
        else:
            top_c = _lerp_rgb(face_rgb, hi_outer, 0.42)
            mid_c = face_rgb
            bot_c = _lerp_rgb(face_rgb, sh_inner, 0.45)
            if active_or_pressed:
                top_c = face_pressed_rgb
                mid_c = _lerp_rgb(face_pressed_rgb, face_rgb, 0.35)
                bot_c = _lerp_rgb(face_pressed_rgb, sh_outer, 0.25)
            rad = 6 if min(w, h) >= 28 else 4
            return render_cyber_pill_shell(
                w,
                h,
                face_top=top_c,
                face_mid=mid_c,
                face_bottom=bot_c,
                rim_light=led_on if is_toggle_on else hi_outer,
                rim_dark=sh_outer,
                glow_rgb=_lerp_rgb(face_pressed_rgb, led_on, 0.45) if is_toggle_on else None,
                pressed=active_or_pressed,
                radius=rad,
            )

    # 1. Panel Headers (4 sprites, 275x22, transparent chamfered/rounded outer corners)
    t_start = hex_to_rgb(chassis["titleBarActiveStart"])
    t_end = hex_to_rgb(chassis["titleBarActiveEnd"])
    stripe_l = hex_to_rgb(chassis["titleBarStripeLight"])
    stripe_d = hex_to_rgb(chassis["titleBarStripeDark"])
    title_fg = _rgba(chassis["titleBarText"])

    def make_header(
        label: str,
        *,
        chevron: Optional[str] = None,
        with_grip: bool = False,
        expanded_accent: bool = False,
    ) -> RgbaImage:
        hw, hh = 275, 22
        if style == "amber":
            hdr = render_amber_shell(
                hw,
                hh,
                face_top=t_end if expanded_accent else t_start,
                face_bottom=t_start if expanded_accent else _lerp_rgb(t_start, sh_inner, 0.35),
                hi_outer=led_on if expanded_accent else hi_outer,
                hi_inner=hi_inner,
                sh_inner=sh_inner,
                sh_outer=sh_outer,
                pressed=False,
                chamfer=2,
            )
        else:
            hdr = render_cyber_pill_shell(
                hw,
                hh,
                face_top=_lerp_rgb(t_end, stripe_l, 0.22) if expanded_accent else t_end,
                face_mid=t_start,
                face_bottom=_lerp_rgb(t_start, sh_inner, 0.45),
                rim_light=led_on if expanded_accent else stripe_l,
                rim_dark=sh_outer,
                glow_rgb=led_on if expanded_accent else None,
                pressed=False,
                radius=4,
            )

        if expanded_accent:
            # Glowing top accent strip distinguishing expanded full-screen drawer
            for ax in range(6, hw - 6):
                hdr.set_rgba(ax, 2, _rgba(led_on))
                hdr.set_rgba(ax, 3, _rgba(_lerp_rgb(led_on, t_end, 0.5)))

        tw, th = measure_pixel_text(label, spacing=1)
        grip_extra = 18 if with_grip else 0
        block_w = grip_extra + tw
        start_x = (hw - block_w) // 2
        text_x = start_x + grip_extra
        text_y = (hh - th) // 2

        # Horizontal machined titlebar pinstripes flanking label
        left_end = max(10, start_x - 7)
        right_start = min(hw - 26, text_x + tw + 7)
        right_end = hw - (24 if chevron else 10)
        for sy in (6, 10, 14):
            for sx in range(10, left_end):
                blend_rgba(hdr, sx, sy, _rgba(stripe_l, 185))
                blend_rgba(hdr, sx, sy + 1, _rgba(stripe_d, 210))
            for sx in range(right_start, right_end):
                blend_rgba(hdr, sx, sy, _rgba(stripe_l, 185))
                blend_rgba(hdr, sx, sy + 1, _rgba(stripe_d, 210))

        if with_grip:
            # Tactile 12x6 ribbed drag-grip capsule to the left of drawer title
            gx = start_x
            gy = (hh - 6) // 2
            for ry in range(0, 6, 2):
                for rx in range(12):
                    hdr.set_rgba(gx + rx, gy + ry, _rgba(stripe_l))
                    hdr.set_rgba(gx + rx, gy + ry + 1, _rgba(stripe_d))

        draw_pixel_text(
            hdr,
            label,
            text_x,
            text_y,
            _rgba(led_core) if expanded_accent else title_fg,
            txt_shadow,
            spacing=1,
        )

        if chevron is not None:
            draw_chevron(
                hdr,
                cx=hw - 14,
                cy=hh // 2,
                direction=chevron,
                fg=_rgba(led_core if expanded_accent else led_on),
                shadow=txt_shadow,
            )
        return hdr

    sprites["header_deck"] = make_header("DROIDAMP")
    sprites["header_eq"] = make_header("DROIDAMP EQUALIZER")
    sprites["header_drawer_collapsed"] = make_header("QUEUE / LIBRARY", chevron="up", with_grip=True)
    sprites["header_drawer_expanded"] = make_header(
        "QUEUE / LIBRARY", chevron="down", with_grip=True, expanded_accent=True
    )

    # 2. Transport Buttons (12 sprites, 48x36)
    for kind in ("prev", "play", "pause", "stop", "next", "eject"):
        norm_img = make_keycap(48, 36, active_or_pressed=False)
        draw_transport_glyph(norm_img, kind, txt_normal, txt_shadow, shift=0)
        sprites[f"transport_{kind}_normal"] = norm_img

        press_img = make_keycap(48, 36, active_or_pressed=True, is_toggle_on=True)
        draw_transport_glyph(press_img, kind, txt_active, txt_shadow, shift=1)
        sprites[f"transport_{kind}_pressed"] = press_img

    # Helper for LED toggle buttons (off / on)
    def make_led_toggle(w: int, h: int, label: str, is_on: bool) -> RgbaImage:
        btn = make_keycap(w, h, active_or_pressed=is_on, is_toggle_on=is_on)
        led_w, led_h = (6, 10) if h >= 28 else (5, 8)
        gap = 5
        tw, th = measure_pixel_text(label, spacing=1)
        total_w = led_w + gap + tw
        ox = (w - total_w) // 2
        led_y = (h - led_h) // 2
        draw_led_lamp(
            btn,
            ox,
            led_y,
            led_w,
            led_h,
            is_on=is_on,
            led_on=led_on,
            led_core=led_core,
            led_off=led_off,
            bezel_dark=well_dark,
            bezel_light=well_light,
        )
        draw_pixel_text(
            btn,
            label,
            ox + led_w + gap,
            (h - th) // 2,
            txt_active if is_on else txt_normal,
            txt_shadow,
            spacing=1,
        )
        return btn

    # Helper for momentary labeled buttons (normal / pressed)
    def make_label_button(w: int, h: int, label: str, pressed: bool, spacing: int = 1) -> RgbaImage:
        btn = make_keycap(w, h, active_or_pressed=pressed, is_toggle_on=pressed)
        tw, th = measure_pixel_text(label, spacing=spacing)
        shift = 1 if pressed else 0
        draw_pixel_text(
            btn,
            label,
            (w - tw) // 2 + shift,
            (h - th) // 2 + shift,
            txt_active if pressed else txt_normal,
            txt_shadow,
            spacing=spacing,
        )
        return btn

    # 3. Deck & EQ Toggles + Presets (14 sprites)
    toggle_specs = [
        ("shuffle", 96, 28, "SHUF"),
        ("repeat", 80, 28, "REP"),
        ("eq_btn", 60, 28, "EQ"),
        ("queue_btn", 68, 28, "QUEUE"),
        ("eq_on", 46, 28, "ON"),
        ("eq_auto", 56, 28, "AUTO"),
    ]
    for prefix, bw, bh, lbl in toggle_specs:
        sprites[f"{prefix}_off"] = make_led_toggle(bw, bh, lbl, is_on=False)
        sprites[f"{prefix}_on"] = make_led_toggle(bw, bh, lbl, is_on=True)

    sprites["eq_presets_normal"] = make_label_button(74, 28, "PRESETS", pressed=False, spacing=1)
    sprites["eq_presets_pressed"] = make_label_button(74, 28, "PRESETS", pressed=True, spacing=1)

    # 4. Mobile Controls (16 sprites)
    sprites["mobile_tab_queue_off"] = make_led_toggle(140, 30, "QUEUE", is_on=False)
    sprites["mobile_tab_queue_on"] = make_led_toggle(140, 30, "QUEUE", is_on=True)
    sprites["mobile_tab_library_off"] = make_led_toggle(140, 30, "LIBRARY", is_on=False)
    sprites["mobile_tab_library_on"] = make_led_toggle(140, 30, "LIBRARY", is_on=True)

    for action_key, action_lbl in (("rem", "REM"), ("misc", "MISC"), ("list", "LIST")):
        sprites[f"mobile_{action_key}_normal"] = make_label_button(96, 32, action_lbl, pressed=False, spacing=2)
        sprites[f"mobile_{action_key}_pressed"] = make_label_button(96, 32, action_lbl, pressed=True, spacing=2)

    # Inline row `[▶]` and `[+]` (32x28)
    row_play_n = make_keycap(32, 28, active_or_pressed=False)
    draw_play_small_glyph(row_play_n, txt_normal, txt_shadow, shift=0)
    sprites["mobile_row_play_normal"] = row_play_n

    row_play_p = make_keycap(32, 28, active_or_pressed=True, is_toggle_on=True)
    draw_play_small_glyph(row_play_p, txt_active, txt_shadow, shift=1)
    sprites["mobile_row_play_pressed"] = row_play_p

    row_enq_n = make_keycap(32, 28, active_or_pressed=False)
    draw_plus_glyph(row_enq_n, txt_normal, txt_shadow, shift=0)
    sprites["mobile_row_enqueue_normal"] = row_enq_n

    row_enq_p = make_keycap(32, 28, active_or_pressed=True, is_toggle_on=True)
    draw_plus_glyph(row_enq_p, txt_active, txt_shadow, shift=1)
    sprites["mobile_row_enqueue_pressed"] = row_enq_p

    # Settings gear `⚙` (32x32)
    gear_n = make_keycap(32, 32, active_or_pressed=False)
    draw_gear_glyph(gear_n, txt_normal, txt_shadow, shift=0)
    sprites["mobile_settings_gear_normal"] = gear_n

    gear_p = make_keycap(32, 32, active_or_pressed=True, is_toggle_on=True)
    draw_gear_glyph(gear_p, txt_active, txt_shadow, shift=1)
    sprites["mobile_settings_gear_pressed"] = gear_p

    return {key: encode_rgba_png(img) for key, img in sprites.items()}


# ---------------------------------------------------------------------------
# Showcase Skin Definitions (`Amber Hi-Fi Rack` & `Cyber Ice '99`)
# ---------------------------------------------------------------------------


def _package_showcase_wsz(
    *,
    skin_id: str,
    skin_name: str,
    author: str,
    corner_radius_dp: float,
    bevel_thickness_dp: float,
    double_step_bevel: bool,
    style: str,
    chassis: Dict[str, str],
    bevel: Dict[str, str],
    vfd: Dict[str, str],
    playlist: Dict[str, str],
    viscolor: List[str],
) -> bytes:
    sprite_pngs = _build_skin_sprites(style=style, chassis=chassis, bevel=bevel, vfd=vfd)
    sprites_map = {key: f"sprites/{key}.png" for key in sorted(sprite_pngs)}

    manifest = {
        "schemaVersion": 1,
        "id": skin_id,
        "name": skin_name,
        "author": author,
        "inheritLegacyBmpSprites": False,
        "shapeStyle": {
            "cornerRadiusDp": corner_radius_dp,
            "bevelThicknessDp": bevel_thickness_dp,
            "doubleStepBevel": double_step_bevel,
        },
        "palette": {
            "chassis": chassis,
            "bevel": bevel,
            "vfd": vfd,
            "playlist": playlist,
            "viscolor": viscolor,
        },
        "sprites": sprites_map,
    }

    entries: Dict[str, bytes] = {
        "droidamp.json": (json.dumps(manifest, indent=2) + "\n").encode("utf-8"),
        "viscolor.txt": format_viscolor_txt(viscolor, skin_name),
        "pledit.txt": format_pledit_txt(playlist),
        "main.bmp": build_legacy_main_bmp(chassis, bevel, vfd, skin_name.upper()),
    }
    for key, png_bytes in sorted(sprite_pngs.items()):
        entries[f"sprites/{key}.png"] = png_bytes

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for path in sorted(entries):
            zinfo = zipfile.ZipInfo(filename=path, date_time=DETERMINISTIC_ZIP_DT)
            zinfo.compress_type = zipfile.ZIP_DEFLATED
            zinfo.external_attr = 0o644 << 16
            zf.writestr(zinfo, entries[path])
    return buf.getvalue()


def build_amber_hifi_rack_wsz() -> bytes:
    chassis = {
        "windowBackground": "#14100C",
        "panelSurface": "#2C251E",
        "panelSurfaceAlt": "#221C16",
        "titleBarActiveStart": "#3A2C1D",
        "titleBarActiveEnd": "#523E29",
        "titleBarStripeLight": "#9E825E",
        "titleBarStripeDark": "#1C150E",
        "titleBarText": "#FFE8BE",
        "divider": "#140F0A",
        "bottomLip": "#1E1813",
        "labelText": "#EAD4B2",
        "labelMuted": "#A89274",
    }
    bevel = {
        "highlightOuter": "#7A6750",
        "highlightInner": "#564838",
        "shadowInner": "#1B1611",
        "shadowOuter": "#0C0906",
        "wellBorderDark": "#080604",
        "wellBorderLight": "#645340",
        "buttonFace": "#382F25",
        "buttonFacePressed": "#211B15",
        "buttonText": "#F4DEC0",
        "buttonTextActive": "#FFC048",
        "sliderThumbTop": "#B89B74",
        "sliderThumbFace": "#6E5B43",
        "sliderThumbGroove": "#201912",
    }
    vfd = {
        "wellBackground": "#090603",
        "phosphorPrimary": "#FFB028",
        "phosphorBright": "#FFD878",
        "phosphorDim": "#B07618",
        "segmentUnlit": "#1E140A",
        "ledOn": "#FFB830",
        "ledOff": "#291D10",
        "ledAmber": "#FF9418",
        "eqCurveStroke": "#FFC240",
        "eqCurveFill": "#38FFB028",
        "eqCurveZeroLine": "#332412",
    }
    playlist = {
        "normalText": "#FFB438",
        "currentText": "#FFF0CC",
        "normalBackground": "#0B0805",
        "selectedBackground": "#4A3114",
        "selectedText": "#FFF4D8",
        "indexNumberText": "#B88228",
        "folderAccentText": "#FFD268",
        "breadcrumbBackground": "#16100A",
        "breadcrumbText": "#FFD070",
    }
    # 24-entry warm glowing gold -> amber -> tangerine -> ruby-red spectrum + amber oscilloscope
    viscolor = [
        "#090603",  # 0: background
        "#24190D",  # 1: grid dots
        "#E82418",  # 2: peak ruby red
        "#EE3618",  # 3
        "#F44818",  # 4
        "#F85A18",  # 5: deep orange-red
        "#FC6C18",  # 6
        "#FE7E18",  # 7: tangerine
        "#FF901A",  # 8: warm amber
        "#FFA01E",  # 9
        "#FFAE24",  # 10: gold-amber
        "#FFBC2C",  # 11
        "#FFC836",  # 12: champagne gold
        "#FFD244",  # 13
        "#FFDA52",  # 14
        "#F6D458",  # 15
        "#E8C44C",  # 16: warm brass base
        "#D4AE3E",  # 17: low gold base
        "#FFD468",  # 18: osc 1 bright gold
        "#FFB838",  # 19: osc 2
        "#E09824",  # 20: osc 3
        "#B07418",  # 21: osc 4
        "#784C10",  # 22: osc 5
        "#FFF0C8",  # 23: peak cap ivory-gold
    ]
    return _package_showcase_wsz(
        skin_id="amber-hifi-rack",
        skin_name="Amber Hi-Fi Rack",
        author="DroidAmp Studio",
        corner_radius_dp=2.0,
        bevel_thickness_dp=1.5,
        double_step_bevel=True,
        style="amber",
        chassis=chassis,
        bevel=bevel,
        vfd=vfd,
        playlist=playlist,
        viscolor=viscolor,
    )


def build_cyber_ice_99_wsz() -> bytes:
    chassis = {
        "windowBackground": "#0B121E",
        "panelSurface": "#1B283E",
        "panelSurfaceAlt": "#141F31",
        "titleBarActiveStart": "#163868",
        "titleBarActiveEnd": "#255898",
        "titleBarStripeLight": "#7CC4F8",
        "titleBarStripeDark": "#0C1D38",
        "titleBarText": "#ECFAFF",
        "divider": "#0A111C",
        "bottomLip": "#121C2C",
        "labelText": "#D6ECFA",
        "labelMuted": "#7FA0C4",
    }
    bevel = {
        "highlightOuter": "#648EC0",
        "highlightInner": "#426490",
        "shadowInner": "#101928",
        "shadowOuter": "#060B12",
        "wellBorderDark": "#04080E",
        "wellBorderLight": "#4C72A2",
        "buttonFace": "#243654",
        "buttonFacePressed": "#152136",
        "buttonText": "#E6F6FF",
        "buttonTextActive": "#52F4FF",
        "sliderThumbTop": "#A4D4F8",
        "sliderThumbFace": "#547CA8",
        "sliderThumbGroove": "#142238",
    }
    vfd = {
        "wellBackground": "#040A12",
        "phosphorPrimary": "#3CF0FF",
        "phosphorBright": "#A8FCFF",
        "phosphorDim": "#2496B0",
        "segmentUnlit": "#0C1F30",
        "ledOn": "#3CF0FF",
        "ledOff": "#12283C",
        "ledAmber": "#FF58D8",
        "eqCurveStroke": "#48F4FF",
        "eqCurveFill": "#383CF0FF",
        "eqCurveZeroLine": "#14324A",
    }
    playlist = {
        "normalText": "#48ECFF",
        "currentText": "#FFFFFF",
        "normalBackground": "#050C16",
        "selectedBackground": "#163C72",
        "selectedText": "#F2FCFF",
        "indexNumberText": "#2CA4C0",
        "folderAccentText": "#96E8FF",
        "breadcrumbBackground": "#0A1728",
        "breadcrumbText": "#8CF2FF",
    }
    # 24-entry electric ice-cyan -> azure/cobalt -> neon violet -> cyber magenta peak
    viscolor = [
        "#040A12",  # 0: background
        "#102438",  # 1: grid dots
        "#FF38C8",  # 2: peak cyber magenta
        "#F43CE0",  # 3
        "#DC44F4",  # 4: neon orchid
        "#BC4CFF",  # 5: electric violet
        "#9856FF",  # 6
        "#7664FF",  # 7: indigo-cobalt
        "#5876FF",  # 8
        "#428BFF",  # 9: cobalt blue
        "#34A0FF",  # 10
        "#2CB4FF",  # 11: azure
        "#28C6FF",  # 12
        "#2AD6FF",  # 13
        "#30E4FF",  # 14: ice cyan
        "#3CEEFF",  # 15
        "#52F6FF",  # 16: bright cyan
        "#72FAFF",  # 17: arctic base
        "#84FAFF",  # 18: osc 1
        "#4CF0FF",  # 19: osc 2
        "#2CC4E8",  # 20: osc 3
        "#1C8FB8",  # 21: osc 4
        "#105A7A",  # 22: osc 5
        "#F2FEFF",  # 23: peak cap arctic white
    ]
    return _package_showcase_wsz(
        skin_id="cyber-ice-99",
        skin_name="Cyber Ice '99",
        author="DroidAmp Studio",
        corner_radius_dp=4.0,
        bevel_thickness_dp=1.5,
        double_step_bevel=False,
        style="cyber",
        chassis=chassis,
        bevel=bevel,
        vfd=vfd,
        playlist=playlist,
        viscolor=viscolor,
    )


def generate_showcase_skins(out_dir: Path) -> List[Path]:
    """Write `amber_hifi_rack.wsz` and `cyber_ice_99.wsz` into `out_dir`."""
    out_dir.mkdir(parents=True, exist_ok=True)
    amber_path = out_dir / "amber_hifi_rack.wsz"
    cyber_path = out_dir / "cyber_ice_99.wsz"

    amber_path.write_bytes(build_amber_hifi_rack_wsz())
    cyber_path.write_bytes(build_cyber_ice_99_wsz())
    return [amber_path, cyber_path]


def main(argv: Optional[Sequence[str]] = None) -> int:
    default_out = (_THIS_DIR / "../../app/src/main/assets/skins").resolve()
    parser = argparse.ArgumentParser(
        prog="generate_showcase_skins.py",
        description="Generate DroidAmp's built-in Augmented .wsz showcase skins.",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=default_out,
        help=f"Output directory for generated .wsz files (default: {default_out})",
    )
    args = parser.parse_args(argv)
    written = generate_showcase_skins(args.out_dir)
    for p in written:
        print(f"Generated {p} ({p.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
