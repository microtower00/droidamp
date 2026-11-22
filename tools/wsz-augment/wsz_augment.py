#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 DroidAmp Contributors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.

"""
wsz_augment.py — Upgrade legacy 1999 Winamp 2.x `.wsz` skins into DroidAmp
Augmented `.wsz` archives (`ADR-028`) containing `droidamp.json` palette/shape
tokens and optional chroma/flood-keyed RGBA PNG `sprites/*.png` assets.

Works out-of-the-box with the Python 3 standard library (`zipfile`, `json`,
`struct`, `zlib`, `argparse`, `pathlib`) via a built-in pure-Python 8/24/32-bit
BMP decoder + RGBA PNG encoder, and automatically delegates exotic/RLE BMP
decoding to Pillow (`PIL.Image`) when installed.
"""

from __future__ import annotations

import argparse
from collections import deque
from dataclasses import dataclass
import io
import json
from pathlib import Path
import re
import struct
import sys
from typing import Dict, Iterable, List, Optional, Sequence, Tuple
import zipfile
import zlib

try:
    from PIL import Image as _PilImage  # type: ignore
    HAS_PIL = True
except Exception:  # pragma: no cover - exercised when Pillow is absent
    _PilImage = None
    HAS_PIL = False


CHROMA_MAGENTA_RGB: Tuple[int, int, int] = (255, 0, 255)

# Exact 24-entry fallback viscolor palette from `ClassicDroidAmpSkin.kt`
CLASSIC_VISCOLOR: List[str] = [
    "#050806",  # 0: background
    "#142018",  # 1: grid dots
    "#F02014",  # 2: peak red
    "#F43814",  # 3
    "#F65014",  # 4
    "#F86814",  # 5: orange-red
    "#FA8214",  # 6: orange
    "#FC9C14",  # 7
    "#FDB814",  # 8: amber
    "#FED416",  # 9: yellow-orange
    "#F8EC1A",  # 10: yellow
    "#D8F01E",  # 11: yellow-green
    "#B0F020",  # 12
    "#84F022",  # 13
    "#58F024",  # 14
    "#38F026",  # 15
    "#28E428",  # 16: phosphor green
    "#1CC824",  # 17: base green
    "#38FF48",  # 18: osc primary
    "#28E038",  # 19: osc 2
    "#20B82E",  # 20: osc 3
    "#188C24",  # 21: osc 4
    "#105C18",  # 22: osc 5
    "#DCEEE0",  # 23: peak cap dot
]

CLASSIC_PLAYLIST: Dict[str, str] = {
    "normalText": "#28E828",
    "currentText": "#FFFFFF",
    "normalBackground": "#050806",
    "selectedBackground": "#1A3A7A",
    "selectedText": "#FFFFFF",
    "indexNumberText": "#1A9624",
    "folderAccentText": "#F4C842",
    "breadcrumbBackground": "#0D141C",
    "breadcrumbText": "#8CD8FF",
}

CLASSIC_PANEL_SURFACE_RGB: Tuple[int, int, int] = (0x26, 0x2D, 0x3B)
CLASSIC_LED_AMBER_HEX = "#FFB020"

# Authoritative Winamp 2.x sprite coordinate table matching `WszSpriteCoordinates.kt`
# Format: (sprite_key, bmp_basename, (x, y, width, height), apply_corner_flood)
LEGACY_SPRITE_SLICES: List[Tuple[str, str, Tuple[int, int, int, int], bool]] = [
    # cbuttons.bmp (136x36)
    ("transport_prev_normal", "cbuttons.bmp", (0, 0, 23, 18), True),
    ("transport_play_normal", "cbuttons.bmp", (23, 0, 23, 18), True),
    ("transport_pause_normal", "cbuttons.bmp", (46, 0, 23, 18), True),
    ("transport_stop_normal", "cbuttons.bmp", (69, 0, 23, 18), True),
    ("transport_next_normal", "cbuttons.bmp", (92, 0, 22, 18), True),
    ("transport_eject_normal", "cbuttons.bmp", (114, 0, 22, 16), True),
    ("transport_prev_pressed", "cbuttons.bmp", (0, 18, 23, 18), True),
    ("transport_play_pressed", "cbuttons.bmp", (23, 18, 23, 18), True),
    ("transport_pause_pressed", "cbuttons.bmp", (46, 18, 23, 18), True),
    ("transport_stop_pressed", "cbuttons.bmp", (69, 18, 23, 18), True),
    ("transport_next_pressed", "cbuttons.bmp", (92, 18, 22, 18), True),
    ("transport_eject_pressed", "cbuttons.bmp", (114, 16, 22, 16), True),
    # shufrep.bmp (92x85)
    ("repeat_off", "shufrep.bmp", (0, 0, 28, 15), True),
    ("repeat_on", "shufrep.bmp", (0, 30, 28, 15), True),
    ("shuffle_off", "shufrep.bmp", (28, 0, 47, 15), True),
    ("shuffle_on", "shufrep.bmp", (28, 30, 47, 15), True),
    ("eq_btn_off", "shufrep.bmp", (0, 61, 23, 12), True),
    ("eq_btn_on", "shufrep.bmp", (0, 73, 23, 12), True),
    ("queue_btn_off", "shufrep.bmp", (23, 61, 23, 12), True),
    ("queue_btn_on", "shufrep.bmp", (23, 73, 23, 12), True),
    # eqmain.bmp (275x315)
    ("eq_on_off", "eqmain.bmp", (10, 119, 25, 12), True),
    ("eq_on_on", "eqmain.bmp", (69, 119, 25, 12), True),
    ("eq_auto_off", "eqmain.bmp", (35, 119, 33, 12), True),
    ("eq_auto_on", "eqmain.bmp", (94, 119, 33, 12), True),
    ("eq_presets_normal", "eqmain.bmp", (224, 164, 44, 12), True),
    ("eq_presets_pressed", "eqmain.bmp", (224, 176, 44, 12), True),
    ("eq_thumb", "eqmain.bmp", (0, 164, 11, 11), True),
    # playpaus.bmp (42x9)
    ("vfd_play", "playpaus.bmp", (0, 0, 9, 9), False),
    ("vfd_pause", "playpaus.bmp", (9, 0, 9, 9), False),
    ("vfd_stop", "playpaus.bmp", (18, 0, 9, 9), False),
    # monoster.bmp (58x24)
    ("stereo_active", "monoster.bmp", (0, 0, 29, 12), False),
    ("stereo_inactive", "monoster.bmp", (0, 12, 29, 12), False),
    ("mono_active", "monoster.bmp", (29, 0, 29, 12), False),
    ("mono_inactive", "monoster.bmp", (29, 12, 29, 12), False),
    # posbar.bmp (307x10 or 248x10)
    ("seekbar_track", "posbar.bmp", (0, 0, 248, 10), False),
    ("seekbar_thumb", "posbar.bmp", (248, 0, 29, 10), True),
    # volume.bmp / balance.bmp thumbs (when height >= 433)
    ("volume_thumb", "volume.bmp", (15, 422, 14, 11), True),
    ("balance_thumb", "balance.bmp", (15, 422, 14, 11), True),
]


@dataclass
class RgbaImage:
    """Lightweight top-down 8-bit RGBA image buffer."""

    width: int
    height: int
    pixels: bytearray  # len == width * height * 4 (R, G, B, A)

    def __post_init__(self) -> None:
        expected = self.width * self.height * 4
        if len(self.pixels) != expected:
            raise ValueError(f"Expected {expected} RGBA bytes, got {len(self.pixels)}")

    @classmethod
    def blank(cls, width: int, height: int, rgba: Tuple[int, int, int, int] = (0, 0, 0, 0)) -> "RgbaImage":
        r, g, b, a = rgba
        buf = bytearray([r, g, b, a] * (width * height))
        return cls(width=width, height=height, pixels=buf)

    def get_rgba(self, x: int, y: int) -> Tuple[int, int, int, int]:
        idx = (y * self.width + x) * 4
        p = self.pixels
        return p[idx], p[idx + 1], p[idx + 2], p[idx + 3]

    def set_rgba(self, x: int, y: int, rgba: Tuple[int, int, int, int]) -> None:
        if 0 <= x < self.width and 0 <= y < self.height:
            idx = (y * self.width + x) * 4
            self.pixels[idx] = rgba[0]
            self.pixels[idx + 1] = rgba[1]
            self.pixels[idx + 2] = rgba[2]
            self.pixels[idx + 3] = rgba[3]

    def crop(self, x: int, y: int, w: int, h: int) -> Optional["RgbaImage"]:
        if x < 0 or y < 0 or w <= 0 or h <= 0:
            return None
        if x + w > self.width or y + h > self.height:
            return None
        out = bytearray(w * h * 4)
        row_bytes = w * 4
        for row in range(h):
            src_off = ((y + row) * self.width + x) * 4
            dst_off = row * row_bytes
            out[dst_off : dst_off + row_bytes] = self.pixels[src_off : src_off + row_bytes]
        return RgbaImage(width=w, height=h, pixels=out)

    def blit(self, src: "RgbaImage", dst_x: int, dst_y: int) -> None:
        for sy in range(src.height):
            dy = dst_y + sy
            if dy < 0 or dy >= self.height:
                continue
            for sx in range(src.width):
                dx = dst_x + sx
                if dx < 0 or dx >= self.width:
                    continue
                self.set_rgba(dx, dy, src.get_rgba(sx, sy))

    def clone(self) -> "RgbaImage":
        return RgbaImage(width=self.width, height=self.height, pixels=bytearray(self.pixels))


# ---------------------------------------------------------------------------
# Pure-Python BMP Decoder / Encoder + Pure-Python RGBA PNG Encoder / Decoder
# ---------------------------------------------------------------------------


def _mask_shift_and_scale(mask: int) -> Tuple[int, int]:
    if mask == 0:
        return 0, 0
    shift = (mask & -mask).bit_length() - 1
    bits = (mask >> shift).bit_length()
    max_val = (1 << bits) - 1
    return shift, max_val


def decode_bmp_stdlib(data: bytes) -> RgbaImage:
    """Decode uncompressed 8-bit, 24-bit, or 32-bit Windows BMP using stdlib only."""
    if len(data) < 54 or data[:2] != b"BM":
        raise ValueError("Not a valid Windows BMP header")

    bf_off_bits = struct.unpack_from("<I", data, 10)[0]
    dib_size = struct.unpack_from("<I", data, 14)[0]
    if dib_size < 40:
        raise ValueError(f"Unsupported BMP DIB header size: {dib_size}")

    width, raw_height, planes, bpp, compression, _, _, _, clr_used, _ = struct.unpack_from(
        "<iiHHIIiiII", data, 18
    )
    if planes != 1 or width <= 0 or raw_height == 0:
        raise ValueError(f"Invalid BMP dimensions ({width}x{raw_height}, planes={planes})")

    top_down = raw_height < 0
    height = abs(raw_height)

    # BI_RGB = 0, BI_BITFIELDS = 3
    if compression not in (0, 3):
        raise ValueError(f"Compressed BMP (compression={compression}) requires Pillow")
    if bpp not in (8, 24, 32):
        raise ValueError(f"Unsupported BMP bit depth without Pillow: {bpp}")

    out = bytearray(width * height * 4)

    if bpp == 8:
        if compression != 0:
            raise ValueError("8-bit BMP with bitfields is invalid")
        palette_entries = clr_used if clr_used > 0 else 256
        pal_offset = 14 + dib_size
        if pal_offset + palette_entries * 4 > len(data):
            raise ValueError("Truncated 8-bit BMP color table")
        palette: List[Tuple[int, int, int]] = []
        for i in range(palette_entries):
            b, g, r, _ = struct.unpack_from("<BBBB", data, pal_offset + i * 4)
            palette.append((r, g, b))

        stride = (width + 3) & ~3
        if bf_off_bits + stride * height > len(data):
            raise ValueError("Truncated 8-bit BMP pixel array")

        for row in range(height):
            src_row = row if top_down else (height - 1 - row)
            row_start = bf_off_bits + src_row * stride
            dst_base = row * width * 4
            for col in range(width):
                idx = data[row_start + col]
                r, g, b = palette[idx] if idx < len(palette) else (0, 0, 0)
                d = dst_base + col * 4
                out[d] = r
                out[d + 1] = g
                out[d + 2] = b
                out[d + 3] = 255
        return RgbaImage(width=width, height=height, pixels=out)

    if bpp == 24:
        stride = ((width * 3) + 3) & ~3
        if bf_off_bits + stride * height > len(data):
            raise ValueError("Truncated 24-bit BMP pixel array")
        for row in range(height):
            src_row = row if top_down else (height - 1 - row)
            row_start = bf_off_bits + src_row * stride
            dst_base = row * width * 4
            for col in range(width):
                s = row_start + col * 3
                d = dst_base + col * 4
                out[d] = data[s + 2]
                out[d + 1] = data[s + 1]
                out[d + 2] = data[s]
                out[d + 3] = 255
        return RgbaImage(width=width, height=height, pixels=out)

    # 32-bit BMP (BGRA / BGRX or BI_BITFIELDS)
    r_mask, g_mask, b_mask, a_mask = 0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000
    if compression == 3 and len(data) >= 14 + 40 + 12:
        r_mask, g_mask, b_mask = struct.unpack_from("<III", data, 54)
        a_mask = struct.unpack_from("<I", data, 66)[0] if dib_size >= 56 and len(data) >= 70 else 0

    r_shift, r_max = _mask_shift_and_scale(r_mask)
    g_shift, g_max = _mask_shift_and_scale(g_mask)
    b_shift, b_max = _mask_shift_and_scale(b_mask)
    a_shift, a_max = _mask_shift_and_scale(a_mask)

    stride = width * 4
    if bf_off_bits + stride * height > len(data):
        raise ValueError("Truncated 32-bit BMP pixel array")

    any_non_zero_alpha = False
    for row in range(height):
        src_row = row if top_down else (height - 1 - row)
        row_start = bf_off_bits + src_row * stride
        dst_base = row * width * 4
        for col in range(width):
            px = struct.unpack_from("<I", data, row_start + col * 4)[0]
            r = (((px & r_mask) >> r_shift) * 255 // r_max) if r_max else 0
            g = (((px & g_mask) >> g_shift) * 255 // g_max) if g_max else 0
            b = (((px & b_mask) >> b_shift) * 255 // b_max) if b_max else 0
            if a_max:
                a = ((px & a_mask) >> a_shift) * 255 // a_max
                if a != 0:
                    any_non_zero_alpha = True
            else:
                a = 255
            d = dst_base + col * 4
            out[d] = r
            out[d + 1] = g
            out[d + 2] = b
            out[d + 3] = a

    # Classic 32-bit BGRX BMPs store 0 in the unused X byte; treat all-zero alpha as opaque.
    if a_max and not any_non_zero_alpha:
        for i in range(3, len(out), 4):
            out[i] = 255

    return RgbaImage(width=width, height=height, pixels=out)


def decode_bmp(data: bytes, allow_pil: bool = True) -> RgbaImage:
    """
    Decode a Windows BMP image into an `RgbaImage`.
    Uses pure-Python stdlib first for 8/24/32-bit uncompressed BMPs, and falls back
    to `PIL.Image` automatically for RLE4/RLE8/1-bit/4-bit/exotic BMP variants.
    """
    try:
        return decode_bmp_stdlib(data)
    except Exception as stdlib_err:
        if allow_pil and HAS_PIL and _PilImage is not None:
            with _PilImage.open(io.BytesIO(data)) as im:
                rgba = im.convert("RGBA")
                w, h = rgba.size
                return RgbaImage(width=w, height=h, pixels=bytearray(rgba.tobytes()))
        raise stdlib_err


def encode_bmp_24(image: RgbaImage) -> bytes:
    """Encode an `RgbaImage` as an uncompressed bottom-up 24-bit Windows BMP."""
    w, h = image.width, image.height
    stride = ((w * 3) + 3) & ~3
    pixel_bytes = bytearray(stride * h)
    for y in range(h):
        bmp_row = h - 1 - y
        row_off = bmp_row * stride
        for x in range(w):
            r, g, b, _ = image.get_rgba(x, y)
            p = row_off + x * 3
            pixel_bytes[p] = b
            pixel_bytes[p + 1] = g
            pixel_bytes[p + 2] = r

    file_size = 54 + len(pixel_bytes)
    header = struct.pack(
        "<2sIHHI",
        b"BM",
        file_size,
        0,
        0,
        54,
    )
    dib = struct.pack(
        "<IiiHHIIiiII",
        40,
        w,
        h,
        1,
        24,
        0,
        len(pixel_bytes),
        2835,
        2835,
        0,
        0,
    )
    return header + dib + bytes(pixel_bytes)


def _png_chunk(chunk_type: bytes, payload: bytes) -> bytes:
    crc = zlib.crc32(chunk_type)
    crc = zlib.crc32(payload, crc) & 0xFFFFFFFF
    return struct.pack(">I", len(payload)) + chunk_type + payload + struct.pack(">I", crc)


def encode_rgba_png(image: RgbaImage) -> bytes:
    """Encode an `RgbaImage` into a deterministic 8-bit RGBA PNG using `struct` + `zlib`."""
    w, h = image.width, image.height
    row_bytes = w * 4
    raw = bytearray((row_bytes + 1) * h)
    pos = 0
    for y in range(h):
        raw[pos] = 0  # Filter type 0 (None)
        pos += 1
        src = y * row_bytes
        raw[pos : pos + row_bytes] = image.pixels[src : src + row_bytes]
        pos += row_bytes

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    compressed = zlib.compress(bytes(raw), level=9)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", compressed)
        + _png_chunk(b"IEND", b"")
    )


def decode_rgba_png_stdlib(data: bytes) -> RgbaImage:
    """Minimal pure-Python reader for 8-bit RGBA PNGs (filter 0..4) for verification/tests."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Invalid PNG signature")
    pos = 8
    width = height = 0
    idat = bytearray()
    while pos + 8 <= len(data):
        length = struct.unpack_from(">I", data, pos)[0]
        ctype = data[pos + 4 : pos + 8]
        payload = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            width, height, bit_depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", payload)
            if bit_depth != 8 or color_type != 6 or interlace != 0:
                raise ValueError("decode_rgba_png_stdlib supports non-interlaced 8-bit RGBA PNGs")
        elif ctype == b"IDAT":
            idat.extend(payload)
        elif ctype == b"IEND":
            break

    raw = zlib.decompress(bytes(idat))
    bpp = 4
    row_bytes = width * bpp
    expected = (row_bytes + 1) * height
    if len(raw) != expected:
        raise ValueError(f"Unexpected decompressed PNG size {len(raw)} != {expected}")

    out = bytearray(width * height * bpp)
    for y in range(height):
        f_type = raw[y * (row_bytes + 1)]
        scan = raw[y * (row_bytes + 1) + 1 : (y + 1) * (row_bytes + 1)]
        dst_off = y * row_bytes
        prev_off = (y - 1) * row_bytes
        for i in range(row_bytes):
            x_byte = scan[i]
            a = out[dst_off + i - bpp] if i >= bpp else 0
            b = out[prev_off + i] if y > 0 else 0
            c = out[prev_off + i - bpp] if (y > 0 and i >= bpp) else 0
            if f_type == 0:
                val = x_byte
            elif f_type == 1:
                val = (x_byte + a) & 0xFF
            elif f_type == 2:
                val = (x_byte + b) & 0xFF
            elif f_type == 3:
                val = (x_byte + ((a + b) >> 1)) & 0xFF
            elif f_type == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                val = (x_byte + pr) & 0xFF
            else:
                raise ValueError(f"Unsupported PNG filter {f_type}")
            out[dst_off + i] = val
    return RgbaImage(width=width, height=height, pixels=out)


# ---------------------------------------------------------------------------
# Color Math, `viscolor.txt` & `pledit.txt` Parsers, & Exact Palette Sampling
# ---------------------------------------------------------------------------


def rgb_to_hex(rgb: Tuple[int, int, int]) -> str:
    r, g, b = (max(0, min(255, int(c))) for c in rgb)
    return f"#{r:02X}{g:02X}{b:02X}"


def rgba_to_hex(rgb: Tuple[int, int, int], alpha_float: float) -> str:
    a = max(0, min(255, int(round(alpha_float * 255.0))))
    r, g, b = (max(0, min(255, int(c))) for c in rgb)
    if a == 255:
        return f"#{r:02X}{g:02X}{b:02X}"
    return f"#{a:02X}{r:02X}{g:02X}{b:02X}"


def hex_to_rgb(hex_str: str) -> Tuple[int, int, int]:
    cleaned = hex_str.strip().lstrip("#")
    if len(cleaned) == 8:
        cleaned = cleaned[2:]
    if len(cleaned) != 6:
        raise ValueError(f"Invalid hex color: {hex_str!r}")
    val = int(cleaned, 16)
    return (val >> 16) & 0xFF, (val >> 8) & 0xFF, val & 0xFF


def scale_rgb(rgb: Tuple[int, int, int], factor: float) -> Tuple[int, int, int]:
    return (
        max(0, min(255, int(round(rgb[0] * factor)))),
        max(0, min(255, int(round(rgb[1] * factor)))),
        max(0, min(255, int(round(rgb[2] * factor)))),
    )


def _srgb_luma(rgb: Tuple[int, int, int]) -> float:
    return (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255.0


def _pick_highest_contrast(
    bg: Tuple[int, int, int],
    candidates: Sequence[Tuple[int, int, int]],
) -> Tuple[int, int, int]:
    bg_lum = _srgb_luma(bg)
    return max(candidates, key=lambda c: abs(_srgb_luma(c) - bg_lum))


_INT_RE = re.compile(r"\d+")


def parse_viscolor(content: Optional[str]) -> List[str]:
    """Parse Winamp `viscolor.txt` (24 RGB lines), matching `ViscolorParser.kt`."""
    if not content or not content.strip():
        return list(CLASSIC_VISCOLOR)

    parsed: List[str] = []
    for raw_line in content.splitlines():
        if len(parsed) >= 24:
            break
        stripped = raw_line.split("//", 1)[0].split(";", 1)[0].strip()
        if not stripped:
            continue
        nums = [int(m.group(0)) for m in _INT_RE.finditer(stripped)]
        if len(nums) < 3:
            continue
        r = max(0, min(255, nums[0]))
        g = max(0, min(255, nums[1]))
        b = max(0, min(255, nums[2]))
        parsed.append(rgb_to_hex((r, g, b)))

    if not parsed:
        return list(CLASSIC_VISCOLOR)
    while len(parsed) < 24:
        parsed.append(CLASSIC_VISCOLOR[len(parsed)])
    return parsed


def _parse_pledit_hex(raw: str) -> Optional[Tuple[int, int, int]]:
    h = raw.strip().lstrip("#")
    if len(h) != 6:
        return None
    try:
        val = int(h, 16)
    except ValueError:
        return None
    return (val >> 16) & 0xFF, (val >> 8) & 0xFF, val & 0xFF


def parse_pledit(content: Optional[str]) -> Dict[str, str]:
    """Parse Winamp `pledit.txt` (`[Text]`), matching `PleditParser.kt`."""
    if not content or not content.strip():
        return dict(CLASSIC_PLAYLIST)

    values: Dict[str, Tuple[int, int, int]] = {}
    for raw_line in content.splitlines():
        line = raw_line.split(";", 1)[0].split("//", 1)[0].strip()
        if not line or line.startswith("["):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        key = k.strip().lower()
        if not key:
            continue
        rgb = _parse_pledit_hex(v.strip())
        if rgb is not None:
            values[key] = rgb

    if not values:
        return dict(CLASSIC_PLAYLIST)

    normal = values.get("normal", hex_to_rgb(CLASSIC_PLAYLIST["normalText"]))
    current = values.get("current", hex_to_rgb(CLASSIC_PLAYLIST["currentText"]))
    normal_bg = values.get("normalbg", hex_to_rgb(CLASSIC_PLAYLIST["normalBackground"]))
    selected_bg = values.get("selectedbg", hex_to_rgb(CLASSIC_PLAYLIST["selectedBackground"]))
    mb_bg = values.get("mbbg", normal_bg)
    mb_fg = values.get("mbfg", current)

    return {
        "normalText": rgb_to_hex(normal),
        "currentText": rgb_to_hex(current),
        "normalBackground": rgb_to_hex(normal_bg),
        "selectedBackground": rgb_to_hex(selected_bg),
        "selectedText": rgb_to_hex(current),
        "indexNumberText": rgba_to_hex(normal, 0.72),
        "folderAccentText": rgb_to_hex(current),
        "breadcrumbBackground": rgb_to_hex(mb_bg),
        "breadcrumbText": rgb_to_hex(mb_fg),
    }


def sample_patch_color(
    bmp: Optional[RgbaImage],
    x: int,
    y: int,
    w: int,
    h: int,
) -> Optional[Tuple[int, int, int]]:
    """Sample average non-magenta RGB patch at exact `WszPaletteExtractor.kt` coordinates."""
    if bmp is None or bmp.width <= 0 or bmp.height <= 0:
        return None
    x0 = max(0, min(bmp.width - 1, x))
    y0 = max(0, min(bmp.height - 1, y))
    x1 = max(x0 + 1, min(bmp.width, x + w))
    y1 = max(y0 + 1, min(bmp.height, y + h))

    step_x = max(1, (x1 - x0) // 12)
    step_y = max(1, (y1 - y0) // 8)
    sum_r = sum_g = sum_b = count = 0

    py = y0
    while py < y1:
        px = x0
        while px < x1:
            r, g, b, _ = bmp.get_rgba(px, py)
            if (r, g, b) != CHROMA_MAGENTA_RGB:
                sum_r += r
                sum_g += g
                sum_b += b
                count += 1
            px += step_x
        py += step_y

    if count == 0:
        return None
    return (sum_r // count, sum_g // count, sum_b // count)


def sample_single_pixel(bmp: Optional[RgbaImage], x: int, y: int) -> Optional[Tuple[int, int, int]]:
    if bmp is None or not (0 <= x < bmp.width and 0 <= y < bmp.height):
        return None
    r, g, b, _ = bmp.get_rgba(x, y)
    if (r, g, b) == CHROMA_MAGENTA_RGB:
        return None
    return (r, g, b)


def extract_palettes(
    main_bmp: Optional[RgbaImage],
    titlebar_bmp: Optional[RgbaImage],
    cbuttons_bmp: Optional[RgbaImage],
    nums_bmp: Optional[RgbaImage],
    viscolor: List[str],
    playlist: Dict[str, str],
) -> Dict[str, object]:
    """Extract `chassis`, `bevel`, `vfd`, `playlist`, and `viscolor` matching `WszPaletteExtractor.kt`."""
    panel_surface = (
        sample_patch_color(main_bmp, 180, 45, 85, 22)
        or sample_patch_color(main_bmp, 8, 76, 60, 14)
        or CLASSIC_PANEL_SURFACE_RGB
    )
    panel_surface_alt = scale_rgb(panel_surface, 0.84)
    window_bg = scale_rgb(panel_surface, 0.62)
    title_start = sample_patch_color(titlebar_bmp, 35, 4, 50, 6) or scale_rgb(panel_surface, 0.90)
    title_end = sample_patch_color(titlebar_bmp, 140, 4, 60, 6) or title_start

    normal_rgb = hex_to_rgb(playlist["normalText"])
    current_rgb = hex_to_rgb(playlist["currentText"])
    breadcrumb_fg_rgb = hex_to_rgb(playlist["breadcrumbText"])

    chassis = {
        "windowBackground": rgb_to_hex(window_bg),
        "panelSurface": rgb_to_hex(panel_surface),
        "panelSurfaceAlt": rgb_to_hex(panel_surface_alt),
        "titleBarActiveStart": rgb_to_hex(title_start),
        "titleBarActiveEnd": rgb_to_hex(title_end),
        "titleBarStripeLight": rgb_to_hex(scale_rgb(title_start, 1.35)),
        "titleBarStripeDark": rgb_to_hex(scale_rgb(title_start, 0.55)),
        "titleBarText": playlist["currentText"],
        "divider": rgb_to_hex(scale_rgb(panel_surface, 0.45)),
        "bottomLip": rgb_to_hex(panel_surface_alt),
        "labelText": playlist["currentText"],
        "labelMuted": rgba_to_hex(normal_rgb, 0.72),
    }

    button_face = sample_patch_color(cbuttons_bmp, 28, 4, 12, 10) or scale_rgb(panel_surface, 1.15)
    button_face_pressed = sample_patch_color(cbuttons_bmp, 28, 22, 12, 10) or scale_rgb(button_face, 0.78)
    best_ink = _pick_highest_contrast(button_face, [breadcrumb_fg_rgb, current_rgb, normal_rgb])

    bevel = {
        "highlightOuter": rgb_to_hex(scale_rgb(panel_surface, 1.55)),
        "highlightInner": rgb_to_hex(scale_rgb(panel_surface, 1.25)),
        "shadowInner": rgb_to_hex(scale_rgb(panel_surface, 0.62)),
        "shadowOuter": rgb_to_hex(scale_rgb(panel_surface, 0.32)),
        "wellBorderDark": rgb_to_hex(scale_rgb(panel_surface, 0.28)),
        "wellBorderLight": rgb_to_hex(scale_rgb(panel_surface, 1.38)),
        "buttonFace": rgb_to_hex(button_face),
        "buttonFacePressed": rgb_to_hex(button_face_pressed),
        "buttonText": rgb_to_hex(best_ink),
        "buttonTextActive": playlist["normalText"],
        "sliderThumbTop": rgb_to_hex(scale_rgb(button_face, 1.45)),
        "sliderThumbFace": rgb_to_hex(button_face),
        "sliderThumbGroove": rgb_to_hex(scale_rgb(button_face, 0.45)),
    }

    well_bg = sample_single_pixel(nums_bmp, 0, 0) or hex_to_rgb(viscolor[0])
    osc_primary = hex_to_rgb(viscolor[18])
    led_amber_hex = viscolor[8] if len(viscolor) > 8 else CLASSIC_LED_AMBER_HEX

    vfd = {
        "wellBackground": rgb_to_hex(well_bg),
        "phosphorPrimary": playlist["normalText"],
        "phosphorBright": playlist["currentText"],
        "phosphorDim": rgba_to_hex(normal_rgb, 0.65),
        "segmentUnlit": rgb_to_hex(scale_rgb(well_bg, 1.40)),
        "ledOn": playlist["normalText"],
        "ledOff": rgb_to_hex(scale_rgb(well_bg, 1.35)),
        "ledAmber": led_amber_hex,
        "eqCurveStroke": viscolor[18],
        "eqCurveFill": rgba_to_hex(osc_primary, 0.22),
        "eqCurveZeroLine": viscolor[1],
    }

    return {
        "chassis": chassis,
        "bevel": bevel,
        "vfd": vfd,
        "playlist": dict(playlist),
        "viscolor": list(viscolor),
    }


# ---------------------------------------------------------------------------
# Chroma-Key + Corner Flood-Keying + Consistent 4-Header Synthesis
# ---------------------------------------------------------------------------


def apply_chroma_and_corner_key(
    sprite: RgbaImage,
    chroma_tolerance: int = 12,
    flood_corners: bool = True,
) -> RgbaImage:
    """
    Convert `#FF00FF` magenta chroma pixels to `alpha = 0`, and optionally flood-key
    baked chassis background pixels connected to the 4 outer corners within
    `chroma_tolerance` per channel (preserving the interior button face when an
    outline/bevel separates the corners from the button center).
    """
    out = sprite.clone()
    w, h = out.width, out.height

    # 1. Exact `#FF00FF` chroma-keying across the entire sprite
    for y in range(h):
        for x in range(w):
            r, g, b, _ = out.get_rgba(x, y)
            if (r, g, b) == CHROMA_MAGENTA_RGB:
                out.set_rgba(x, y, (0, 0, 0, 0))

    if not flood_corners or chroma_tolerance < 0 or w < 3 or h < 3:
        return out

    # 2. Connected-component flood from the 4 corners `(0,0), (w-1,0), (0,h-1), (w-1,h-1)`
    corners = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
    cx, cy = w // 2, h // 2
    max_flood_pixels = int(w * h * 0.65)

    visited_global = set()
    to_clear: List[Tuple[int, int]] = []

    for sx, sy in corners:
        if (sx, sy) in visited_global:
            continue
        sr, sg, sb, sa = out.get_rgba(sx, sy)
        if sa == 0:
            continue

        comp: List[Tuple[int, int]] = []
        seen = {(sx, sy)}
        q: deque[Tuple[int, int]] = deque([(sx, sy)])
        hit_center = False

        while q:
            x, y = q.popleft()
            if x == cx and y == cy:
                hit_center = True
                break
            comp.append((x, y))
            if len(comp) > max_flood_pixels:
                hit_center = True
                break
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < w and 0 <= ny < h and (nx, ny) not in seen:
                    nr, ng, nb, na = out.get_rgba(nx, ny)
                    if na == 0:
                        continue
                    if (
                        abs(nr - sr) <= chroma_tolerance
                        and abs(ng - sg) <= chroma_tolerance
                        and abs(nb - sb) <= chroma_tolerance
                    ):
                        seen.add((nx, ny))
                        q.append((nx, ny))

        visited_global.update(seen)
        if not hit_center:
            to_clear.extend(comp)

    for x, y in to_clear:
        out.set_rgba(x, y, (0, 0, 0, 0))

    return out


def _draw_chevron_indicator(
    header: RgbaImage,
    direction: str,
    ink_rgb: Tuple[int, int, int],
    shadow_rgb: Tuple[int, int, int],
) -> RgbaImage:
    """Stamp a crisp pixel-art `▲` (collapsed) or `▼` (expanded) chevron on the right side."""
    out = header.clone()
    w, h = out.width, out.height
    if w < 28 or h < 9:
        return out

    # 7x4 pixel chevron positioned cleanly inside right titlebar margin
    cx = w - 12
    cy = h // 2
    rows = 4
    for r in range(rows):
        span = r if direction == "up" else (rows - 1 - r)
        py = cy - 2 + r
        if 0 <= py < h:
            for dx in range(-span, span + 1):
                px = cx + dx
                if 0 <= px < w:
                    if py + 1 < h and out.get_rgba(px, py + 1)[3] > 0:
                        out.set_rgba(px, py + 1, (shadow_rgb[0], shadow_rgb[1], shadow_rgb[2], 255))
                    out.set_rgba(px, py, (ink_rgb[0], ink_rgb[1], ink_rgb[2], 255))
    return out


def _synthesize_fallback_header(
    width: int,
    height: int,
    chassis: Dict[str, str],
) -> RgbaImage:
    start_rgb = hex_to_rgb(chassis["titleBarActiveStart"])
    end_rgb = hex_to_rgb(chassis["titleBarActiveEnd"])
    light_rgb = hex_to_rgb(chassis["titleBarStripeLight"])
    dark_rgb = hex_to_rgb(chassis["titleBarStripeDark"])

    img = RgbaImage.blank(width, height, (0, 0, 0, 255))
    for y in range(height):
        for x in range(width):
            t = 1.0 - abs((x / max(1, width - 1)) * 2.0 - 1.0)
            r = int(round(start_rgb[0] + (end_rgb[0] - start_rgb[0]) * t))
            g = int(round(start_rgb[1] + (end_rgb[1] - start_rgb[1]) * t))
            b = int(round(start_rgb[2] + (end_rgb[2] - start_rgb[2]) * t))
            img.set_rgba(x, y, (r, g, b, 255))

    for sy in (4, 7, 10):
        if sy + 1 < height:
            for x in range(8, width - 22):
                img.set_rgba(x, sy, (light_rgb[0], light_rgb[1], light_rgb[2], 255))
                img.set_rgba(x, sy + 1, (dark_rgb[0], dark_rgb[1], dark_rgb[2], 255))
    return img


def _assemble_pledit_expanded_header(pledit_bmp: RgbaImage) -> Optional[RgbaImage]:
    """Assemble a 275x20 active playlist header from Winamp `pledit.bmp` top tiles."""
    left = pledit_bmp.crop(0, 0, 25, 20)
    title = pledit_bmp.crop(26, 0, 100, 20)
    fill = pledit_bmp.crop(127, 0, 25, 20)
    right = pledit_bmp.crop(153, 0, 25, 20)
    if not (left and title and fill and right):
        return None

    out = RgbaImage.blank(275, 20, (0, 0, 0, 255))
    x = 0
    while x < 275:
        out.blit(fill, x, 0)
        x += 25
    out.blit(left, 0, 0)
    out.blit(title, (275 - 100) // 2, 0)
    out.blit(right, 275 - 25, 0)
    return out


def build_consistent_headers(
    titlebar_bmp: Optional[RgbaImage],
    eqmain_bmp: Optional[RgbaImage],
    pledit_bmp: Optional[RgbaImage],
    chassis: Dict[str, str],
) -> Dict[str, RgbaImage]:
    """
    Synthesize all 4 required panel headers (`header_deck`, `header_eq`,
    `header_drawer_collapsed`, `header_drawer_expanded`) so `hasCompletePanelHeaders`
    is always satisfied (`ADR-028`).
    """
    deck = titlebar_bmp.crop(27, 0, 275, 14) if titlebar_bmp is not None else None
    if deck is None:
        deck = _synthesize_fallback_header(275, 14, chassis)

    eq = eqmain_bmp.crop(0, 134, 275, 14) if eqmain_bmp is not None else None
    if eq is None:
        eq = deck.clone()

    collapsed_raw: Optional[RgbaImage] = None
    expanded_raw: Optional[RgbaImage] = None
    if pledit_bmp is not None:
        collapsed_raw = pledit_bmp.crop(0, 42, 275, 14)
        expanded_raw = _assemble_pledit_expanded_header(pledit_bmp)

    ink_rgb = hex_to_rgb(chassis["titleBarText"])
    shadow_rgb = hex_to_rgb(chassis["titleBarStripeDark"])

    collapsed_base = collapsed_raw if collapsed_raw is not None else deck.clone()
    expanded_base = expanded_raw if expanded_raw is not None else deck.clone()

    collapsed = _draw_chevron_indicator(collapsed_base, "up", ink_rgb, shadow_rgb)
    expanded = _draw_chevron_indicator(expanded_base, "down", ink_rgb, shadow_rgb)

    return {
        "header_deck": apply_chroma_and_corner_key(deck, flood_corners=False),
        "header_eq": apply_chroma_and_corner_key(eq, flood_corners=False),
        "header_drawer_collapsed": apply_chroma_and_corner_key(collapsed, flood_corners=False),
        "header_drawer_expanded": apply_chroma_and_corner_key(expanded, flood_corners=False),
    }


# ---------------------------------------------------------------------------
# Archive Inspection, Safety Validation & Conversion Pipeline
# ---------------------------------------------------------------------------


def _validate_and_normalize_zip_path(raw_name: str) -> str:
    norm = raw_name.replace("\\", "/")
    parts = [p for p in norm.split("/") if p != ""]
    if ".." in parts or norm.startswith("/"):
        raise ValueError(f"Unsafe ZIP entry path rejected (zip-slip guard): {raw_name!r}")
    return "/".join(parts)


def slugify_skin_id(raw: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", raw.strip().lower()).strip("-")
    return slug or "augmented-wsz-skin"


def humanize_skin_name(stem: str) -> str:
    cleaned = re.sub(r"[_\-]+", " ", stem).strip()
    return cleaned.title() if cleaned else "Augmented Winamp Skin"


def convert_wsz_bytes(
    input_bytes: bytes,
    *,
    skin_id: str,
    skin_name: str,
    author: str = "Winamp Community (Augmented)",
    extract_sprites: bool = False,
    chroma_tolerance: int = 12,
    corner_radius_dp: float = 0.0,
    bevel_thickness_dp: float = 1.5,
    double_step_bevel: bool = True,
    allow_pil: bool = True,
) -> Tuple[bytes, Dict[str, object]]:
    """
    Convert raw legacy `.wsz` bytes into an Augmented `.wsz` archive in memory,
    returning `(output_zip_bytes, manifest_dict)`.
    """
    original_entries: List[Tuple[str, bytes]] = []
    by_basename: Dict[str, bytes] = {}

    with zipfile.ZipFile(io.BytesIO(input_bytes), "r") as zin:
        for info in zin.infolist():
            norm_path = _validate_and_normalize_zip_path(info.filename)
            if info.is_dir() or not norm_path:
                continue
            payload = zin.read(info.filename)
            original_entries.append((norm_path, payload))
            base = norm_path.rsplit("/", 1)[-1].lower()
            # Keep first occurrence (shallowest if archive ordered top-down)
            if base not in by_basename:
                by_basename[base] = payload

    def _read_text(name: str) -> Optional[str]:
        raw = by_basename.get(name.lower())
        if raw is None:
            return None
        for enc in ("utf-8-sig", "cp1252", "latin-1"):
            try:
                return raw.decode(enc)
            except UnicodeDecodeError:
                continue
        return raw.decode("utf-8", errors="replace")

    def _read_bmp(name: str) -> Optional[RgbaImage]:
        raw = by_basename.get(name.lower())
        if raw is None:
            return None
        try:
            return decode_bmp(raw, allow_pil=allow_pil)
        except Exception:
            return None

    viscolor = parse_viscolor(_read_text("viscolor.txt"))
    playlist = parse_pledit(_read_text("pledit.txt"))

    main_bmp = _read_bmp("main.bmp")
    titlebar_bmp = _read_bmp("titlebar.bmp")
    cbuttons_bmp = _read_bmp("cbuttons.bmp")
    nums_bmp = _read_bmp("nums_ex.bmp") or _read_bmp("numbers.bmp")
    eqmain_bmp = _read_bmp("eqmain.bmp")
    pledit_bmp = _read_bmp("pledit.bmp")

    palette = extract_palettes(
        main_bmp=main_bmp,
        titlebar_bmp=titlebar_bmp,
        cbuttons_bmp=cbuttons_bmp,
        nums_bmp=nums_bmp,
        viscolor=viscolor,
        playlist=playlist,
    )

    extracted_pngs: Dict[str, bytes] = {}
    sprites_manifest: Dict[str, str] = {}

    if extract_sprites:
        bmp_cache: Dict[str, Optional[RgbaImage]] = {
            "main.bmp": main_bmp,
            "titlebar.bmp": titlebar_bmp,
            "cbuttons.bmp": cbuttons_bmp,
            "eqmain.bmp": eqmain_bmp,
            "pledit.bmp": pledit_bmp,
        }

        def _cached_bmp(bname: str) -> Optional[RgbaImage]:
            key = bname.lower()
            if key not in bmp_cache:
                if key == "balance.bmp" and key not in by_basename:
                    bmp_cache[key] = _cached_bmp("volume.bmp")
                else:
                    bmp_cache[key] = _read_bmp(key)
            return bmp_cache[key]

        # 1. All 4 consistent panel headers (`ADR-028`)
        headers = build_consistent_headers(
            titlebar_bmp=titlebar_bmp,
            eqmain_bmp=eqmain_bmp,
            pledit_bmp=pledit_bmp,
            chassis=palette["chassis"],  # type: ignore[arg-type]
        )
        for key, img in headers.items():
            rel_path = f"sprites/{key}.png"
            extracted_pngs[rel_path] = encode_rgba_png(img)
            sprites_manifest[key] = rel_path

        # 2. Standard Winamp 2.x button, VFD, stereo & slider thumb slices
        for sprite_key, bmp_name, (sx, sy, sw, sh), flood_corners in LEGACY_SPRITE_SLICES:
            src_bmp = _cached_bmp(bmp_name)
            if src_bmp is None:
                continue
            cropped = src_bmp.crop(sx, sy, sw, sh)
            if cropped is None:
                continue
            keyed = apply_chroma_and_corner_key(
                cropped,
                chroma_tolerance=chroma_tolerance,
                flood_corners=flood_corners,
            )
            rel_path = f"sprites/{sprite_key}.png"
            extracted_pngs[rel_path] = encode_rgba_png(keyed)
            sprites_manifest[sprite_key] = rel_path

    manifest: Dict[str, object] = {
        "schemaVersion": 1,
        "id": skin_id,
        "name": skin_name,
        "author": author,
        "inheritLegacyBmpSprites": False,
        "shapeStyle": {
            "cornerRadiusDp": float(corner_radius_dp),
            "bevelThicknessDp": float(bevel_thickness_dp),
            "doubleStepBevel": bool(double_step_bevel),
        },
        "palette": palette,
        "sprites": sprites_manifest,
    }

    manifest_bytes = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")

    out_buf = io.BytesIO()
    replaced_lower = {"droidamp.json"} | {p.lower() for p in extracted_pngs}
    with zipfile.ZipFile(out_buf, "w", compression=zipfile.ZIP_DEFLATED) as zout:
        for orig_path, orig_data in original_entries:
            if orig_path.lower() in replaced_lower:
                continue
            if orig_path.rsplit("/", 1)[-1].lower() == "droidamp.json":
                continue
            zout.writestr(orig_path, orig_data)

        zout.writestr("droidamp.json", manifest_bytes)
        for sprite_path in sorted(extracted_pngs):
            zout.writestr(sprite_path, extracted_pngs[sprite_path])

    return out_buf.getvalue(), manifest


def convert_wsz_file(
    input_path: Path,
    output_path: Path,
    *,
    skin_id: Optional[str] = None,
    skin_name: Optional[str] = None,
    author: str = "Winamp Community (Augmented)",
    extract_sprites: bool = False,
    chroma_tolerance: int = 12,
    corner_radius_dp: float = 0.0,
    bevel_thickness_dp: float = 1.5,
    double_step_bevel: bool = True,
    allow_pil: bool = True,
) -> Dict[str, object]:
    stem = input_path.stem
    resolved_id = skin_id or slugify_skin_id(stem)
    resolved_name = skin_name or humanize_skin_name(stem)

    out_bytes, manifest = convert_wsz_bytes(
        input_path.read_bytes(),
        skin_id=resolved_id,
        skin_name=resolved_name,
        author=author,
        extract_sprites=extract_sprites,
        chroma_tolerance=chroma_tolerance,
        corner_radius_dp=corner_radius_dp,
        bevel_thickness_dp=bevel_thickness_dp,
        double_step_bevel=double_step_bevel,
        allow_pil=allow_pil,
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(out_bytes)
    return manifest


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="wsz_augment.py",
        description=(
            "Convert a legacy 1999 Winamp 2.x .wsz archive into an Augmented DroidAmp "
            ".wsz skin with droidamp.json palette/shape metadata and optional RGBA PNG sprites."
        ),
    )
    parser.add_argument("input", type=Path, help="Path to source legacy .wsz archive")
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        required=True,
        help="Destination path for augmented .wsz archive",
    )
    parser.add_argument(
        "--extract-sprites",
        action="store_true",
        help="Slice Winamp 2.x BMP controls/headers into chroma- & corner-keyed sprites/*.png",
    )
    parser.add_argument(
        "--chroma-tolerance",
        type=int,
        default=12,
        help="Per-channel RGB tolerance for outer corner background flood-keying (default: 12)",
    )
    parser.add_argument("--id", dest="skin_id", default=None, help="Override manifest skin ID slug")
    parser.add_argument("--name", dest="skin_name", default=None, help="Override manifest display name")
    parser.add_argument(
        "--author",
        default="Winamp Community (Augmented)",
        help="Manifest author attribution",
    )
    parser.add_argument(
        "--corner-radius",
        type=float,
        default=0.0,
        help="Shape cornerRadiusDp token in droidamp.json (default: 0.0)",
    )
    parser.add_argument(
        "--bevel-thickness",
        type=float,
        default=1.5,
        help="Shape bevelThicknessDp token in droidamp.json (default: 1.5)",
    )
    parser.add_argument(
        "--single-step-bevel",
        action="store_true",
        help="Set shapeStyle.doubleStepBevel = false in droidamp.json",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    manifest = convert_wsz_file(
        input_path=args.input,
        output_path=args.output,
        skin_id=args.skin_id,
        skin_name=args.skin_name,
        author=args.author,
        extract_sprites=args.extract_sprites,
        chroma_tolerance=args.chroma_tolerance,
        corner_radius_dp=args.corner_radius,
        bevel_thickness_dp=args.bevel_thickness,
        double_step_bevel=not args.single_step_bevel,
    )
    sprites_count = len(manifest.get("sprites", {}))  # type: ignore[arg-type]
    print(
        f"Wrote {args.output} (id={manifest['id']!r}, name={manifest['name']!r}, "
        f"sprites={sprites_count})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
