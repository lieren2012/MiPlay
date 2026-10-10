#!/usr/bin/env python3
"""Read a VirtualBox VM screen without a GUI.

VirtualBox can dump the guest framebuffer with `VBoxManage controlvm <vm>
screenshotpng`. This tool takes that dump and turns it into something usable in
a terminal: basic statistics (is it black? is it an Android UI?) and, for text
consoles, an ASCII rendering of the lit pixels.

No third-party packages: the PNG is decoded with zlib from the standard library.
"""

from __future__ import annotations

import argparse
import struct
import subprocess
import sys
import zlib
from pathlib import Path

VBOXMANAGE = r"C:\Program Files\Oracle\VirtualBox\VBoxManage.exe"


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def decode_png(data: bytes) -> tuple[int, int, list[list[int]]]:
    """Decodes an 8-bit RGB/RGBA/grayscale PNG into a luminance matrix."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos = 8
    width = height = None
    bit_depth = color_type = None
    idat = bytearray()
    while pos + 8 <= len(data):
        length, chunk_type = struct.unpack(">I4s", data[pos : pos + 8])
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type = struct.unpack(">IIBB", body[:10])
        elif chunk_type == b"IDAT":
            idat += body
        elif chunk_type == b"IEND":
            break
    if bit_depth != 8 or color_type not in (0, 2, 6):
        raise ValueError(f"unsupported PNG: depth={bit_depth} color={color_type}")

    channels = {0: 1, 2: 3, 6: 4}[color_type]
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    rows: list[list[int]] = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset : offset + stride])
        offset += stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                up_left = previous[i - channels] if i >= channels else 0
                line[i] = (line[i] + _paeth(left, previous[i], up_left)) & 0xFF
        elif filter_type != 0:
            raise ValueError(f"unknown PNG filter {filter_type}")
        previous = line
        if channels == 1:
            rows.append(list(line))
        else:
            rows.append(
                [
                    (line[i] * 299 + line[i + 1] * 587 + line[i + 2] * 114) // 1000
                    for i in range(0, stride, channels)
                ]
            )
    return width, height, rows


def stats(width: int, height: int, rows: list[list[int]]) -> str:
    flat = [value for row in rows for value in row]
    lit = sum(1 for value in flat if value > 40)
    distinct = len(set(flat))
    bright = sum(flat) / len(flat)
    return (
        f"size {width}x{height}  distinct_gray={distinct}  mean={bright:.1f}  "
        f"lit(>40)={lit} ({100 * lit / len(flat):.2f}%)"
    )


def ascii_text(width: int, height: int, rows: list[list[int]], margin: int = 2) -> str:
    xs = [x for y in range(height) for x in range(width) if rows[y][x] > 40]
    ys = [y for y in range(height) for x in range(width) if rows[y][x] > 40]
    if not xs:
        return "(screen is uniformly dark)"
    x0, x1 = max(0, min(xs) - margin), min(width - 1, max(xs) + margin)
    y0, y1 = max(0, min(ys) - margin), min(height - 1, max(ys) + margin)
    if (x1 - x0) > 240 or (y1 - y0) > 120:
        return (
            f"(text too wide to render: bbox {x1 - x0}x{y1 - y0}; this looks like a "
            "graphical screen, use --stats only)"
        )
    out = []
    for y in range(y0, y1 + 1):
        out.append("".join("#" if rows[y][x] > 40 else "." for x in range(x0, x1 + 1)))
    return "\n".join(out)


def ascii_block(width: int, height: int, rows: list[list[int]], cols: int = 100) -> str:
    ramp = " .:-=+*#%@"
    step_x = max(1, width // cols)
    step_y = step_x * 2
    out = []
    for y in range(0, height - step_y + 1, step_y):
        line = []
        for x in range(0, width - step_x + 1, step_x):
            total = 0
            for dy in range(step_y):
                for dx in range(step_x):
                    total += rows[y + dy][x + dx]
            mean = total // (step_x * step_y)
            line.append(ramp[min(len(ramp) - 1, mean * len(ramp) // 256)])
        out.append("".join(line))
    return "\n".join(out)


def selftest() -> int:
    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(
            ">I", zlib.crc32(kind + body) & 0xFFFFFFFF
        )

    # 2x2 grayscale with filter 0: all four pixels lit
    raw = bytes([0, 255, 255, 0, 255, 255])
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 0, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )
    width, height, rows = decode_png(png)
    assert (width, height) == (2, 2), (width, height)
    assert rows[0] == [255, 255], rows
    assert rows[1] == [255, 255], rows
    assert "2x2" in stats(width, height, rows)
    assert ascii_text(width, height, rows).count("#") == 4
    assert "uniformly dark" in ascii_text(1, 1, [[0]])
    print("selftest ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vm", default="AndroidX86-DiPlay")
    parser.add_argument("--out", type=Path, default=Path("reports/vm-screen.png"))
    parser.add_argument("--full", action="store_true", help="decompress a JPEG instead of PNG")
    parser.add_argument("--stats", action="store_true", help="statistics only")
    parser.add_argument("--block", action="store_true", help="downsampled ascii for graphics screens")
    parser.add_argument("--json", type=Path, help="also write the ascii rendering to a file")
    parser.add_argument("--no-capture", action="store_true", help="decode an existing --out file")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    if not args.no_capture:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        result = subprocess.run(
            [
                VBOXMANAGE, "controlvm", args.vm, "screenshotpng",
                str(args.out.resolve()),
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            print(f"VBoxManage failed: {result.stderr.strip() or result.stdout.strip()}", file=sys.stderr)
            return 1

    width, height, rows = decode_png(args.out.read_bytes())
    print(stats(width, height, rows))
    if not args.stats:
        rendered = (
            ascii_block(width, height, rows, cols=120)
            if args.block
            else ascii_text(width, height, rows)
        )
        print(rendered)
        if args.json:
            args.json.write_text(rendered + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
