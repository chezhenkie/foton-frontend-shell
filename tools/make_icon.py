"""Rasterize the foton app icon (assets/favicon.svg geometry) to icon.png / icon.ico / icon.rgba.

Pure Python, no dependencies. Regenerates the shell icon assets from the exact
frontend icon geometry: mint rounded-square background with a see-through hole,
four square-cornered marker-hatched blocks. Run:  python3 tools/make_icon.py
"""
import math
import os
import struct
import zlib

GREEN = (0xA8, 0xE6, 0xCF)
DARK = (0x1A, 0x1A, 0x1A)
VIEW = 512.0
SS = 4
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]
WINDOW_SIZE = 64

BG_RX = 32.0
HOLE = (402.0, 329.0, 60.0, 60.0)
# x, y, w, h, hatch angle in degrees from vertical ("/" direction)
BLOCKS = [
    (78.0, 52.0, 120.0, 122.0, 45.0),
    (288.0, 88.0, 80.0, 72.0, 45.0),
    (70.0, 194.0, 258.0, 208.0, 18.0),
    (372.0, 208.0, 62.0, 36.0, 45.0),
]
LINE_PERIOD = 16.0
LINE_PHASE = 8.0
LINE_HALF = 3.5
STROKE_HALF = 3.0


def inside_rrect(x, y):
    half = VIEW / 2.0
    qx = max(abs(x - half) - (half - BG_RX), 0.0)
    qy = max(abs(y - half) - (half - BG_RX), 0.0)
    return qx * qx + qy * qy <= BG_RX * BG_RX


def inside_hole(x, y):
    hx, hy, hw, hh = HOLE
    return hx <= x < hx + hw and hy <= y < hy + hh


def inside_block(b, x, y):
    return b[0] <= x < b[0] + b[2] and b[1] <= y < b[1] + b[3]


def inside_hatch(b, x, y):
    a = math.radians(b[4])
    px = math.cos(a) * x + math.sin(a) * y
    m = (px - LINE_PHASE) % LINE_PERIOD
    return min(m, LINE_PERIOD - m) <= LINE_HALF


def inside_outline(b, x, y):
    bx, by, bw, bh = b[0], b[1], b[2], b[3]
    s = STROKE_HALF
    inside = lambda l, t, r, o: l <= x < r and t <= y < o
    return (inside(bx - s, by - s, bx + bw + s, by + bh + s)
            and not inside(bx + s, by + s, bx + bw - s, by + bh - s))


def sample(x, y):
    if inside_hole(x, y):
        return 0.0, 0.0, 0.0, 0.0
    for b in BLOCKS:
        if inside_outline(b, x, y) or (inside_block(b, x, y) and inside_hatch(b, x, y)):
            return DARK[0], DARK[1], DARK[2], 1.0
    if inside_rrect(x, y):
        return GREEN[0], GREEN[1], GREEN[2], 1.0
    return 0.0, 0.0, 0.0, 0.0


def raster(size):
    scale = VIEW / size
    n = SS * SS
    out = bytearray(size * size * 4)
    for j in range(size):
        for i in range(size):
            sr = sg = sb = sa = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    r, g, b, a = sample((i + (sx + 0.5) / SS) * scale, (j + (sy + 0.5) / SS) * scale)
                    sr += r * a
                    sg += g * a
                    sb += b * a
                    sa += a
            alpha = sa / n
            if alpha > 0:
                r = round(sr / n / alpha)
                g = round(sg / n / alpha)
                b = round(sb / n / alpha)
            else:
                r = g = b = 0
            o = (j * size + i) * 4
            out[o] = r
            out[o + 1] = g
            out[o + 2] = b
            out[o + 3] = round(alpha * 255)
    return bytes(out)


def png(size, rgba):
    raw = bytearray()
    for j in range(size):
        raw.append(0)
        raw += rgba[j * size * 4:(j + 1) * size * 4]

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def ico(entries):
    header = struct.pack("<HHH", 0, 1, len(entries))
    table = b""
    data = b""
    offset = 6 + 16 * len(entries)
    for size, blob in entries:
        dim = 0 if size >= 256 else size
        table += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(blob), offset)
        offset += len(blob)
        data += blob
    return header + table + data


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, os.pardir))
    assets = os.path.join(root, "assets")

    # src/main.rs decodes icon.rgba with a const ICON_SIZE and asserts the byte
    # count at compile time. Regenerating at a different size here would fail the
    # next build, so refuse instead of writing a file the crate cannot use.
    with open(os.path.join(root, "src", "main.rs"), "r", encoding="utf-8") as f:
        rust = f.read()
    if "const ICON_SIZE" not in rust:
        raise SystemExit("src/main.rs has no ICON_SIZE const; add it before generating")
    rust_size = int(rust.split("const ICON_SIZE: u32 = ")[1].split(";")[0].strip())
    if rust_size != WINDOW_SIZE:
        raise SystemExit(
            "size mismatch: tools/make_icon.py WINDOW_SIZE=%d, src/main.rs ICON_SIZE=%d"
            % (WINDOW_SIZE, rust_size))

    entries = [(s, png(s, raster(s))) for s in ICO_SIZES]
    with open(os.path.join(assets, "icon.ico"), "wb") as f:
        f.write(ico(entries))
    with open(os.path.join(assets, "icon.png"), "wb") as f:
        f.write(dict(entries)[256])
    with open(os.path.join(assets, "icon.rgba"), "wb") as f:
        f.write(raster(WINDOW_SIZE))

    print("icon.ico sizes:", ICO_SIZES)
    print("icon.png: 256x256")
    print("icon.rgba:", WINDOW_SIZE, "x", WINDOW_SIZE, "(matches ICON_SIZE)")


if __name__ == "__main__":
    main()
