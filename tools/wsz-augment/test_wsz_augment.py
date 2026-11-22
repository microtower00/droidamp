#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 DroidAmp Contributors

"""Unit test suite for `wsz_augment.py` and `generate_showcase_skins.py`."""

from __future__ import annotations

import io
import json
from pathlib import Path
import struct
import sys
import tempfile
from typing import Dict, List, Tuple
import unittest
import zipfile

_THIS_DIR = Path(__file__).resolve().parent
if str(_THIS_DIR) not in sys.path:
    sys.path.insert(0, str(_THIS_DIR))

from generate_showcase_skins import (  # noqa: E402
    build_amber_hifi_rack_wsz,
    generate_showcase_skins,
)
from wsz_augment import (  # noqa: E402
    CHROMA_MAGENTA_RGB,
    HAS_PIL,
    RgbaImage,
    convert_wsz_bytes,
    decode_bmp,
    decode_bmp_stdlib,
    decode_rgba_png_stdlib,
    encode_bmp_24,
    hex_to_rgb,
    main as wsz_augment_cli_main,
    parse_pledit,
    parse_viscolor,
)

if HAS_PIL:
    from PIL import Image as PilImage  # type: ignore


EXPECTED_CHASSIS_KEYS = {
    "windowBackground",
    "panelSurface",
    "panelSurfaceAlt",
    "titleBarActiveStart",
    "titleBarActiveEnd",
    "titleBarStripeLight",
    "titleBarStripeDark",
    "titleBarText",
    "divider",
    "bottomLip",
    "labelText",
    "labelMuted",
}

EXPECTED_BEVEL_KEYS = {
    "highlightOuter",
    "highlightInner",
    "shadowInner",
    "shadowOuter",
    "wellBorderDark",
    "wellBorderLight",
    "buttonFace",
    "buttonFacePressed",
    "buttonText",
    "buttonTextActive",
    "sliderThumbTop",
    "sliderThumbFace",
    "sliderThumbGroove",
}

EXPECTED_VFD_KEYS = {
    "wellBackground",
    "phosphorPrimary",
    "phosphorBright",
    "phosphorDim",
    "segmentUnlit",
    "ledOn",
    "ledOff",
    "ledAmber",
    "eqCurveStroke",
    "eqCurveFill",
    "eqCurveZeroLine",
}

EXPECTED_PLAYLIST_KEYS = {
    "normalText",
    "currentText",
    "normalBackground",
    "selectedBackground",
    "selectedText",
    "indexNumberText",
    "folderAccentText",
    "breadcrumbBackground",
    "breadcrumbText",
}

EXPECTED_SHOWCASE_SPRITES = {
    "header_deck",
    "header_eq",
    "header_drawer_collapsed",
    "header_drawer_expanded",
    "transport_prev_normal",
    "transport_prev_pressed",
    "transport_play_normal",
    "transport_play_pressed",
    "transport_pause_normal",
    "transport_pause_pressed",
    "transport_stop_normal",
    "transport_stop_pressed",
    "transport_next_normal",
    "transport_next_pressed",
    "transport_eject_normal",
    "transport_eject_pressed",
    "shuffle_off",
    "shuffle_on",
    "repeat_off",
    "repeat_on",
    "eq_btn_off",
    "eq_btn_on",
    "queue_btn_off",
    "queue_btn_on",
    "eq_on_off",
    "eq_on_on",
    "eq_auto_off",
    "eq_auto_on",
    "eq_presets_normal",
    "eq_presets_pressed",
    "mobile_tab_queue_off",
    "mobile_tab_queue_on",
    "mobile_tab_library_off",
    "mobile_tab_library_on",
    "mobile_rem_normal",
    "mobile_rem_pressed",
    "mobile_misc_normal",
    "mobile_misc_pressed",
    "mobile_list_normal",
    "mobile_list_pressed",
    "mobile_row_play_normal",
    "mobile_row_play_pressed",
    "mobile_row_enqueue_normal",
    "mobile_row_enqueue_pressed",
    "mobile_settings_gear_normal",
    "mobile_settings_gear_pressed",
}


def _wcag_rel_luminance(rgb: Tuple[int, int, int]) -> float:
    def lin(c: int) -> float:
        s = c / 255.0
        return s / 12.92 if s <= 0.04045 else ((s + 0.055) / 1.055) ** 2.4

    return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])


def _wcag_contrast(fg_hex: str, bg_rgb: Tuple[int, int, int]) -> float:
    fg_rgb = hex_to_rgb(fg_hex)
    l1 = _wcag_rel_luminance(fg_rgb)
    l2 = _wcag_rel_luminance(bg_rgb)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def _make_beveled_button_tile(
    w: int,
    h: int,
    corner_rgb: Tuple[int, int, int],
    corner_jitter: int,
    border_rgb: Tuple[int, int, int],
    face_rgb: Tuple[int, int, int],
    use_magenta_tl: bool = False,
) -> RgbaImage:
    """Create a synthetic Winamp button tile with baked corner chassis pixels + bevel border."""
    tile = RgbaImage.blank(w, h, (face_rgb[0], face_rgb[1], face_rgb[2], 255))
    # Paint a bevel ring inset from chamfered corners
    chamfer = 3
    for y in range(h):
        for x in range(w):
            dist = min(x, w - 1 - x) + min(y, h - 1 - y)
            if dist < chamfer:
                if use_magenta_tl and x < chamfer and y < chamfer:
                    tile.set_rgba(x, y, (*CHROMA_MAGENTA_RGB, 255))
                else:
                    jitter = corner_jitter if ((x + y) % 2 == 0) else -corner_jitter
                    cr = max(0, min(255, corner_rgb[0] + jitter))
                    cg = max(0, min(255, corner_rgb[1] + jitter))
                    cb = max(0, min(255, corner_rgb[2] + jitter))
                    tile.set_rgba(x, y, (cr, cg, cb, 255))
            elif dist == chamfer or x == chamfer or x == w - 1 - chamfer or y == 0 or y == h - 1:
                tile.set_rgba(x, y, (*border_rgb, 255))
    return tile


def _build_synthetic_legacy_wsz(nested_folder: str = "MyRetroSkin/") -> bytes:
    """Create a realistic legacy 1999 `.wsz` archive with Winamp 2.x bitmaps and text files."""
    main_img = RgbaImage.blank(275, 116, (0x30, 0x38, 0x48, 255))
    titlebar_img = RgbaImage.blank(344, 87, (0x1E, 0x3F, 0x74, 255))

    # cbuttons.bmp (136x36): 6 normal (row 0) + 6 pressed (row 1) buttons with baked corners + #FF00FF
    cbuttons_img = RgbaImage.blank(136, 36, (0x30, 0x38, 0x48, 255))
    specs = [
        (0, 0, 23, 18),
        (23, 0, 23, 18),
        (46, 0, 23, 18),
        (69, 0, 23, 18),
        (92, 0, 22, 18),
        (114, 0, 22, 16),
        (0, 18, 23, 18),
        (23, 18, 23, 18),
        (46, 18, 23, 18),
        (69, 18, 23, 18),
        (92, 18, 22, 18),
        (114, 16, 22, 16),
    ]
    for sx, sy, sw, sh in specs:
        btn = _make_beveled_button_tile(
            sw,
            sh,
            corner_rgb=(0x30, 0x38, 0x48),
            corner_jitter=4,
            border_rgb=(0x88, 0x98, 0xB4),
            face_rgb=(0x44, 0x52, 0x68),
            use_magenta_tl=(sx == 23 and sy == 0),  # Play normal uses #FF00FF in top-left corner
        )
        cbuttons_img.blit(btn, sx, sy)

    shufrep_img = RgbaImage.blank(92, 85, (0x30, 0x38, 0x48, 255))
    for sx, sy, sw, sh in ((0, 0, 28, 15), (28, 0, 47, 15), (0, 61, 23, 12), (23, 61, 23, 12)):
        tile = _make_beveled_button_tile(
            sw,
            sh,
            corner_rgb=(0x30, 0x38, 0x48),
            corner_jitter=3,
            border_rgb=(0x90, 0xA0, 0xBC),
            face_rgb=(0x46, 0x54, 0x6C),
        )
        shufrep_img.blit(tile, sx, sy)

    nums_img = RgbaImage.blank(108, 13, (0x06, 0x0A, 0x0E, 255))
    eqmain_img = RgbaImage.blank(275, 315, (0x28, 0x32, 0x42, 255))
    pledit_img = RgbaImage.blank(280, 186, (0x22, 0x2C, 0x3C, 255))

    viscolor_txt = "\n".join(
        [
            "// Classic test viscolor with leading zeros & comments",
            "006,010,014 // 0 background",
            "018,032,044 ; 1 grid",
            *[f"{240 - i * 8},{80 + i * 9},032 // spec {i}" for i in range(16)],
            "060,240,255 // 18 osc 1",
            "048,210,230",
            "036,180,200",
            "024,140,160",
            "016,100,120",
            "255,255,255 // 23 peak",
        ]
    )
    pledit_txt = (
        "[Text]\r\n"
        "Normal=#40F0FF ; cyan track text\r\n"
        "Current=#FFFFFF\r\n"
        "NormalBG=#060A0E\r\n"
        "SelectedBG=#183C74\r\n"
        "mbBG=#0C1624\r\n"
        "mbFG=#98EEFF\r\n"
    )

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(f"{nested_folder}MAIN.BMP", encode_bmp_24(main_img))
        zf.writestr(f"{nested_folder}TitleBar.bmp", encode_bmp_24(titlebar_img))
        zf.writestr(f"{nested_folder}CBUTTONS.BMP", encode_bmp_24(cbuttons_img))
        zf.writestr(f"{nested_folder}SHUFREP.BMP", encode_bmp_24(shufrep_img))
        zf.writestr(f"{nested_folder}NUMS_EX.BMP", encode_bmp_24(nums_img))
        zf.writestr(f"{nested_folder}EQMAIN.BMP", encode_bmp_24(eqmain_img))
        zf.writestr(f"{nested_folder}PLEDIT.BMP", encode_bmp_24(pledit_img))
        zf.writestr(f"{nested_folder}VISCOLOR.TXT", viscolor_txt.encode("utf-8"))
        zf.writestr(f"{nested_folder}PLEDIT.TXT", pledit_txt.encode("utf-8"))
    return buf.getvalue()


class WszAugmentTests(unittest.TestCase):
    def test_viscolor_and_pledit_parsers(self) -> None:
        vis = parse_viscolor("005, 008, 012 // comment\n255,000,128 ; peak\n")
        self.assertEqual(len(vis), 24)
        self.assertEqual(vis[0], "#05080C")
        self.assertEqual(vis[1], "#FF0080")

        pl = parse_pledit("[Text]\nNormal=#12AB34\nCurrent=FFEEAA\nNormalBG=#010203\nSelectedBG=#112233\n")
        self.assertEqual(set(pl.keys()), EXPECTED_PLAYLIST_KEYS)
        self.assertEqual(pl["normalText"], "#12AB34")
        self.assertEqual(pl["currentText"], "#FFEEAA")
        self.assertEqual(pl["normalBackground"], "#010203")
        self.assertEqual(pl["selectedBackground"], "#112233")

    def test_stdlib_bmp_8_24_32_and_pil_rle_fallback(self) -> None:
        # 24-bit round-trip
        src = RgbaImage.blank(5, 3, (12, 34, 56, 255))
        src.set_rgba(4, 2, (250, 120, 10, 255))
        dec24 = decode_bmp_stdlib(encode_bmp_24(src))
        self.assertEqual((dec24.width, dec24.height), (5, 3))
        self.assertEqual(dec24.get_rgba(0, 0), (12, 34, 56, 255))
        self.assertEqual(dec24.get_rgba(4, 2), (250, 120, 10, 255))

        # 8-bit uncompressed paletted BMP
        pal_header = struct.pack("<2sIHHI", b"BM", 54 + 8 + 8, 0, 0, 54 + 8)
        pal_dib = struct.pack("<IiiHHIIiiII", 40, 2, 2, 1, 8, 0, 8, 2835, 2835, 2, 0)
        palette = struct.pack("<BBBBBBBB", 200, 100, 50, 0, 10, 20, 30, 0)  # BGR0 x 2
        rows = bytes([0, 1, 0, 0, 1, 0, 0, 0])  # bottom-up 2x2 padded to 4-byte stride
        dec8 = decode_bmp_stdlib(pal_header + pal_dib + palette + rows)
        self.assertEqual(dec8.get_rgba(0, 0), (30, 20, 10, 255))  # top-left = bmp row 1 col 0 -> idx 1
        self.assertEqual(dec8.get_rgba(0, 1), (50, 100, 200, 255))  # bottom-left = bmp row 0 col 0 -> idx 0

        # 32-bit BGRX BMP (all-zero X byte treated as opaque)
        hdr32 = struct.pack("<2sIHHI", b"BM", 54 + 4, 0, 0, 54)
        dib32 = struct.pack("<IiiHHIIiiII", 40, 1, 1, 1, 32, 0, 4, 2835, 2835, 0, 0)
        px32 = struct.pack("<BBBB", 90, 80, 70, 0)
        dec32 = decode_bmp_stdlib(hdr32 + dib32 + px32)
        self.assertEqual(dec32.get_rgba(0, 0), (70, 80, 90, 255))

        if HAS_PIL:
            # Verify RLE-compressed or 4-bit BMP delegates to Pillow cleanly
            im = PilImage.new("P", (6, 4))
            im.putpalette([i % 256 for i in range(768)])
            buf = io.BytesIO()
            # 1-bit BMP is unsupported by stdlib decoder (bpp=1) -> triggers PIL fallback
            im.convert("1").save(buf, format="BMP")
            dec_pil = decode_bmp(buf.getvalue(), allow_pil=True)
            self.assertEqual((dec_pil.width, dec_pil.height), (6, 4))

    def test_legacy_wsz_to_droidamp_json_conversion(self) -> None:
        legacy_bytes = _build_synthetic_legacy_wsz(nested_folder="NestedSkin/")
        out_bytes, manifest = convert_wsz_bytes(
            legacy_bytes,
            skin_id="nested-retro",
            skin_name="Nested Retro",
            author="Unit Test",
            extract_sprites=False,
            corner_radius_dp=2.5,
            allow_pil=False,
        )

        self.assertEqual(manifest["schemaVersion"], 1)
        self.assertEqual(manifest["id"], "nested-retro")
        self.assertEqual(manifest["name"], "Nested Retro")
        self.assertEqual(manifest["author"], "Unit Test")
        self.assertFalse(manifest["inheritLegacyBmpSprites"])
        self.assertEqual(manifest["shapeStyle"]["cornerRadiusDp"], 2.5)  # type: ignore[index]

        pal = manifest["palette"]  # type: ignore[index]
        self.assertEqual(set(pal["chassis"].keys()), EXPECTED_CHASSIS_KEYS)  # type: ignore[index]
        self.assertEqual(set(pal["bevel"].keys()), EXPECTED_BEVEL_KEYS)  # type: ignore[index]
        self.assertEqual(set(pal["vfd"].keys()), EXPECTED_VFD_KEYS)  # type: ignore[index]
        self.assertEqual(set(pal["playlist"].keys()), EXPECTED_PLAYLIST_KEYS)  # type: ignore[index]
        self.assertEqual(len(pal["viscolor"]), 24)  # type: ignore[index]

        self.assertEqual(pal["chassis"]["panelSurface"], "#303848")  # type: ignore[index]
        self.assertEqual(pal["vfd"]["wellBackground"], "#060A0E")  # type: ignore[index]
        self.assertEqual(pal["playlist"]["normalText"], "#40F0FF")  # type: ignore[index]

        with zipfile.ZipFile(io.BytesIO(out_bytes), "r") as zf:
            names = set(zf.namelist())
            self.assertIn("droidamp.json", names)
            self.assertIn("NestedSkin/MAIN.BMP", names)
            parsed_json = json.loads(zf.read("droidamp.json").decode("utf-8"))
            self.assertEqual(parsed_json["id"], "nested-retro")

    def test_extract_sprites_chroma_and_corner_flood_alpha(self) -> None:
        legacy_bytes = _build_synthetic_legacy_wsz()
        with tempfile.TemporaryDirectory() as tmp:
            in_path = Path(tmp) / "legacy_test.wsz"
            out_path = Path(tmp) / "augmented_test.wsz"
            in_path.write_bytes(legacy_bytes)

            rc = wsz_augment_cli_main(
                [
                    str(in_path),
                    "-o",
                    str(out_path),
                    "--extract-sprites",
                    "--chroma-tolerance",
                    "12",
                    "--id",
                    "aug-cli-test",
                    "--name",
                    "Augmented CLI Test",
                    "--corner-radius",
                    "3.0",
                ]
            )
            self.assertEqual(rc, 0)
            self.assertTrue(out_path.is_file())

            with zipfile.ZipFile(out_path, "r") as zf:
                names = set(zf.namelist())
                # All 4 required consistent headers must exist (`ADR-028`)
                for hdr in (
                    "sprites/header_deck.png",
                    "sprites/header_eq.png",
                    "sprites/header_drawer_collapsed.png",
                    "sprites/header_drawer_expanded.png",
                ):
                    self.assertIn(hdr, names)

                self.assertNotEqual(
                    zf.read("sprites/header_drawer_collapsed.png"),
                    zf.read("sprites/header_drawer_expanded.png"),
                )

                # Verify transport_play_normal has BOTH #FF00FF top-left chroma key AND flood-keyed corners
                play_png = decode_rgba_png_stdlib(zf.read("sprites/transport_play_normal.png"))
                w, h = play_png.width, play_png.height
                self.assertEqual((w, h), (23, 18))
                for cx, cy in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
                    self.assertEqual(
                        play_png.get_rgba(cx, cy)[3],
                        0,
                        f"Expected alpha==0 at corner ({cx}, {cy})",
                    )
                self.assertEqual(
                    play_png.get_rgba(w // 2, h // 2)[3],
                    255,
                    "Expected opaque alpha==255 at button center",
                )

    def test_zip_slip_rejection(self) -> None:
        for malicious_entry in ("../evil.bmp", "skin/../../etc/passwd", "/absolute/main.bmp"):
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w") as zf:
                zf.writestr(malicious_entry, b"bad")
            with self.assertRaises(ValueError):
                convert_wsz_bytes(
                    buf.getvalue(),
                    skin_id="bad",
                    skin_name="Bad",
                )

    def test_generate_showcase_skins_completeness_alpha_and_wcag(self) -> None:
        # Determinism check
        self.assertEqual(build_amber_hifi_rack_wsz(), build_amber_hifi_rack_wsz())

        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp) / "skins"
            written = generate_showcase_skins(out_dir)
            self.assertEqual([p.name for p in written], ["amber_hifi_rack.wsz", "cyber_ice_99.wsz"])

            expected_meta = {
                "amber_hifi_rack.wsz": ("amber-hifi-rack", "Amber Hi-Fi Rack", 2.0, True),
                "cyber_ice_99.wsz": ("cyber-ice-99", "Cyber Ice '99", 4.0, False),
            }

            for wsz_path in written:
                exp_id, exp_name, exp_radius, exp_double = expected_meta[wsz_path.name]
                with zipfile.ZipFile(wsz_path, "r") as zf:
                    names = set(zf.namelist())
                    self.assertIn("droidamp.json", names)
                    self.assertIn("viscolor.txt", names)
                    self.assertIn("pledit.txt", names)
                    self.assertIn("main.bmp", names)

                    # Verify legacy 275x116 main.bmp & 24-line viscolor.txt
                    main_bmp = decode_bmp_stdlib(zf.read("main.bmp"))
                    self.assertEqual((main_bmp.width, main_bmp.height), (275, 116))
                    self.assertEqual(len(parse_viscolor(zf.read("viscolor.txt").decode("utf-8"))), 24)

                    manifest = json.loads(zf.read("droidamp.json").decode("utf-8"))
                    self.assertEqual(manifest["schemaVersion"], 1)
                    self.assertEqual(manifest["id"], exp_id)
                    self.assertEqual(manifest["name"], exp_name)
                    self.assertEqual(manifest["author"], "DroidAmp Studio")
                    self.assertEqual(manifest["shapeStyle"]["cornerRadiusDp"], exp_radius)
                    self.assertEqual(manifest["shapeStyle"]["doubleStepBevel"], exp_double)

                    pal = manifest["palette"]
                    chassis = pal["chassis"]
                    bevel = pal["bevel"]
                    vfd = pal["vfd"]
                    playlist = pal["playlist"]
                    self.assertEqual(set(chassis.keys()), EXPECTED_CHASSIS_KEYS)
                    self.assertEqual(set(bevel.keys()), EXPECTED_BEVEL_KEYS)
                    self.assertEqual(set(vfd.keys()), EXPECTED_VFD_KEYS)
                    self.assertEqual(set(playlist.keys()), EXPECTED_PLAYLIST_KEYS)
                    self.assertEqual(len(pal["viscolor"]), 24)

                    # Verify WCAG 2.1 contrast ratios match `WszContrastGuard.kt` with zero adjustments
                    t_start = hex_to_rgb(chassis["titleBarActiveStart"])
                    t_end = hex_to_rgb(chassis["titleBarActiveEnd"])
                    t_avg = ((t_start[0] + t_end[0]) // 2, (t_start[1] + t_end[1]) // 2, (t_start[2] + t_end[2]) // 2)
                    self.assertGreaterEqual(_wcag_contrast(chassis["titleBarText"], t_avg), 3.5)
                    self.assertGreaterEqual(_wcag_contrast(chassis["labelText"], hex_to_rgb(chassis["panelSurface"])), 3.5)
                    self.assertGreaterEqual(_wcag_contrast(bevel["buttonText"], hex_to_rgb(bevel["buttonFace"])), 3.8)
                    self.assertGreaterEqual(_wcag_contrast(bevel["buttonTextActive"], hex_to_rgb(bevel["buttonFace"])), 3.8)
                    self.assertGreaterEqual(_wcag_contrast(vfd["phosphorPrimary"], hex_to_rgb(vfd["wellBackground"])), 4.5)
                    self.assertGreaterEqual(_wcag_contrast(vfd["phosphorBright"], hex_to_rgb(vfd["wellBackground"])), 4.5)
                    self.assertGreaterEqual(_wcag_contrast(playlist["normalText"], hex_to_rgb(playlist["normalBackground"])), 4.5)
                    self.assertGreaterEqual(_wcag_contrast(playlist["currentText"], hex_to_rgb(playlist["normalBackground"])), 4.5)
                    self.assertGreaterEqual(_wcag_contrast(playlist["selectedText"], hex_to_rgb(playlist["selectedBackground"])), 4.5)
                    self.assertGreaterEqual(
                        _wcag_contrast(playlist["breadcrumbText"], hex_to_rgb(playlist["breadcrumbBackground"])),
                        3.8,
                    )

                    # Verify all 46 canonical RGBA PNG sprites exist and have alpha==0 outer corners
                    sprites_map: Dict[str, str] = manifest["sprites"]
                    self.assertEqual(set(sprites_map.keys()), EXPECTED_SHOWCASE_SPRITES)

                    for key, rel_path in sprites_map.items():
                        self.assertIn(rel_path, names, f"Missing sprite {rel_path} in {wsz_path.name}")
                        raw_png = zf.read(rel_path)
                        img = decode_rgba_png_stdlib(raw_png)
                        if HAS_PIL:
                            with PilImage.open(io.BytesIO(raw_png)) as pil_im:
                                self.assertEqual(pil_im.mode, "RGBA")
                                self.assertEqual(pil_im.size, (img.width, img.height))

                        w, h = img.width, img.height
                        for cx, cy in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
                            self.assertEqual(
                                img.get_rgba(cx, cy)[3],
                                0,
                                f"{wsz_path.name}:{key} corner ({cx},{cy}) must have alpha==0",
                            )
                        self.assertEqual(
                            img.get_rgba(w // 2, h // 2)[3],
                            255,
                            f"{wsz_path.name}:{key} center must be opaque",
                        )

                    # Collapsed vs expanded drawer headers must be visually distinct (`▲` vs `▼` + accent)
                    self.assertNotEqual(
                        zf.read("sprites/header_drawer_collapsed.png"),
                        zf.read("sprites/header_drawer_expanded.png"),
                    )


if __name__ == "__main__":
    unittest.main()
