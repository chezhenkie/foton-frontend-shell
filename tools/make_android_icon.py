"""Regenerate the Android launcher vector drawables from the icon geometry.

Mirrors tools/make_icon.py constants so both targets trace the same SVG
(foton-app-icon.svg = assets/favicon.svg): solid mint background, four
square-cornered marker-hatched blocks. Run:  python tools/make_android_icon.py
"""
import math
import os

BLOCKS = [
    (98.0, 52.0, 120.0, 122.0, 45.0),
    (308.0, 88.0, 80.0, 72.0, 45.0),
    (90.0, 214.0, 258.0, 208.0, 18.0),
    (392.0, 208.0, 62.0, 36.0, 45.0),
]
LINE_PERIOD = 16.0
LINE_PHASE = 8.0
DARK = "#1A1A1A"
MINT = "#A8E6CF"
OUTLINE_SW = 6
HATCH_SW = 7

FOREGROUND = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="512"
    android:viewportHeight="512">
    <group
        android:scaleX="0.61111"
        android:scaleY="0.61111"
        android:translateX="99.5556"
        android:translateY="99.5556">
        <path
            android:pathData="{outline}"
            android:strokeColor="{dark}"
            android:strokeWidth="{outline_sw}"/>
{hatch_paths}    </group>
</vector>
"""

HATCH_PATH = """        <path
            android:pathData="{path}"
            android:strokeColor="{dark}"
            android:strokeWidth="{sw}"/>
"""

BACKGROUND = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="512"
    android:viewportHeight="512">
    <path
        android:fillColor="{mint}"
        android:pathData="M0,0 H512 V512 H0 Z"/>
</vector>
"""


def outline_path(b):
    x0, y0, w, h = b[0], b[1], b[2], b[3]
    return "M%g,%g H%g V%g H%g Z" % (x0, y0, x0 + w, y0 + h, x0)


def hatch_segments(b):
    x0, y0, w, h, deg = b
    a = math.radians(deg)
    nx, ny = math.cos(a), math.sin(a)
    dx, dy = -ny, nx
    corners = [nx * X + ny * Y for X in (x0, x0 + w) for Y in (y0, y0 + h)]
    out = []
    k0 = int(math.floor((min(corners) - LINE_PHASE) / LINE_PERIOD))
    k1 = int(math.ceil((max(corners) - LINE_PHASE) / LINE_PERIOD))
    for k in range(k0, k1 + 1):
        c = LINE_PHASE + LINE_PERIOD * k
        px, py = nx * c, ny * c
        t0, t1 = -1e18, 1e18
        for p, d, lo, hi in ((px, dx, x0, x0 + w), (py, dy, y0, y0 + h)):
            if abs(d) < 1e-12:
                if p < lo - 1e-9 or p > hi + 1e-9:
                    t0, t1 = 1.0, 0.0
            else:
                ta, tb = (lo - p) / d, (hi - p) / d
                if ta > tb:
                    ta, tb = tb, ta
                t0 = max(t0, ta)
                t1 = min(t1, tb)
        if t1 - t0 > 1e-6:
            out.append("M%.2f,%.2f L%.2f,%.2f"
                       % (px + dx * t0, py + dy * t0, px + dx * t1, py + dy * t1))
    return out


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, os.pardir))
    drawable = os.path.join(root, "android", "app", "src", "main", "res", "drawable")

    outline = " ".join(outline_path(b) for b in BLOCKS)
    angles = sorted({b[4] for b in BLOCKS}, reverse=True)
    paths = []
    for ang in angles:
        segs = []
        for b in BLOCKS:
            if b[4] == ang:
                segs.extend(hatch_segments(b))
        paths.append(HATCH_PATH.format(path=" ".join(segs), dark=DARK, sw=HATCH_SW))

    with open(os.path.join(drawable, "ic_launcher_foreground.xml"), "w",
              encoding="utf-8", newline="\n") as f:
        f.write(FOREGROUND.format(outline=outline, dark=DARK,
                                  outline_sw=OUTLINE_SW, hatch_paths="".join(paths)))
    with open(os.path.join(drawable, "ic_launcher_background.xml"), "w",
              encoding="utf-8", newline="\n") as f:
        f.write(BACKGROUND.format(mint=MINT))
    print("wrote ic_launcher_foreground.xml, ic_launcher_background.xml")


if __name__ == "__main__":
    main()
