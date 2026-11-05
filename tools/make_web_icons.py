#!/usr/bin/env python3
# Copyright (C) 2026 the xrnrule authors
#
# This file is part of Loop Habit Tracker (the xrnloops fork).
#
# Loop Habit Tracker is free software: you can redistribute it and/or modify it under the terms
# of the GNU General Public License as published by the Free Software Foundation, either version
# 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY
# WARRANTY; see the GNU General Public License for more details: <http://www.gnu.org/licenses/>.
#
# Writes web/icons.js: the Font Awesome 4 glyphs the phone draws its days with, and the phone's
# own vector icons, as SVG path data. Needs fontTools (`pip install fonttools`).
#
#   python3 tools/make_web_icons.py .
import json
import re
import sys
import xml.etree.ElementTree as ET

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

repo = sys.argv[1]
font = TTFont(f"{repo}/uhabits-android/src/main/assets/fontawesome-webfont.ttf")
cmap, glyphs = font.getBestCmap(), font.getGlyphSet()
ascent = font["hhea"].ascent
UPM = font["head"].unitsPerEm

marks = {}
for name, cp in [("check", 0xF00C), ("times", 0xF00D), ("minus", 0xF068), ("question", 0xF128)]:
    g = glyphs[cmap[cp]]
    pen = SVGPathPen(glyphs, lambda v: "%g" % round(v))
    flip = (1, 0, 0, -1, 0, ascent)
    g.draw(TransformPen(pen, flip))
    bounds = BoundsPen(glyphs)
    g.draw(TransformPen(bounds, flip))
    x0, y0, x1, y1 = (round(v) for v in bounds.bounds)
    marks[name] = {"d": pen.getCommands(), "box": [x0, y0, x1 - x0, y1 - y0]}

ANDROID = "{http://schemas.android.com/apk/res/android}"
icons = {}
for name in ["lock", "lock_open", "note", "dialpad", "cloud_off", "open_in_new"]:
    root = ET.parse(f"{repo}/uhabits-android/src/main/res/drawable/xrn_ic_{name}.xml").getroot()
    paths = [p.get(ANDROID + "pathData") for p in root.iter("path")]
    icons[name.replace("_", "")] = " ".join(paths)

out = f"""/*
 * Copyright (C) 2026 the xrnrule authors
 *
 * This file is part of Loop Habit Tracker (the xrnloops fork). GPLv3 or later; see loop.js.
 */

// The shapes the phone draws (XRNLOOPS.md, "The web app"). Made by tools/make_web_icons.py from
// the app's own files, so a day here looks like a day there:
// - MARKS: Font Awesome 4's check, times, minus and question glyphs (Dave Gandy, SIL OFL 1.1),
//   from uhabits-android/src/main/assets/fontawesome-webfont.ttf, which Loop draws its days with.
//   Units are the font's ({UPM} per em); `box` is the glyph's ink: x, y, width, height.
// - ICONS: res/drawable/xrn_ic_*.xml, Material icons on a 24-unit grid (Apache 2.0).

export const UNITS_PER_EM = {UPM};

export const MARKS = {json.dumps(marks, indent=2)};

export const ICONS = {json.dumps(icons, indent=2)};
"""
open(f"{repo}/web/icons.js", "w").write(out)
print({k: v["box"] for k, v in marks.items()}, list(icons))
