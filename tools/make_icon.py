"""Rasterize the foton favicon (assets/favicon.svg geometry) to icon.png / icon.ico / icon.rgba.

Pure Python, no dependencies. Regenerates the shell icon assets from the exact
frontend favicon geometry. Run:  python3 tools/make_icon.py
"""
import math
import os
import struct
import zlib

GREEN = (0xA8, 0xE6, 0xCF)
DARK = (0x1A, 0x1A, 0x1A)
VIEW = 32.0
SS = 4
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]
WINDOW_SIZE = 64


def inside_rrect(x, y):
    cx = cy = half = 16.0
    rx = 6.0
    qx = max(abs(x - cx) - (half - rx), 0.0)
    qy = max(abs(y - cy) - (half - rx), 0.0)
    return qx * qx + qy * qy <= rx * rx


def inside_circle(x, y):
    return (x - 10.0) ** 2 + (y - 22.0) ** 2 <= 6.5 ** 2


def inside_line(x, y):
    ax, ay, bx, by = 15.0, 17.0, 28.0, 5.0
    dx, dy = bx - ax, by - ay
    t = ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(x - (ax + t * dx), y - (ay + t * dy)) <= 3.5 / 2.0


def sample(x, y):
    if inside_line(x, y) or inside_circle(x, y):
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
    assets = os.path.join(here, os.pardir, "assets")

    entries = [(s, png(s, raster(s))) for s in ICO_SIZES]
    with open(os.path.join(assets, "icon.ico"), "wb") as f:
        f.write(ico(entries))
    with open(os.path.join(assets, "icon.png"), "wb") as f:
        f.write(dict(entries)[256])
    with open(os.path.join(assets, "icon.rgba"), "wb") as f:
        f.write(raster(WINDOW_SIZE))

    print("icon.ico sizes:", ICO_SIZES)
    print("icon.png: 256x256")
    print("icon.rgba:", WINDOW_SIZE, "x", WINDOW_SIZE)


if __name__ == "__main__":
    main()
