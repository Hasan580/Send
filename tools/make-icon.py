"""Draws the Send mark (white arrow on black, hairline ring) and writes windows/Send/Assets/send.ico.

Needs Pillow. The ICO holds PNG frames at 16..256 px, which Windows 7+ reads.
"""
import io
import os
import struct

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "windows", "Send", "Assets")
os.makedirs(OUT, exist_ok=True)


def draw(size):
    s = size * 4  # supersample
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = int(s * 0.225)
    d.rounded_rectangle([0, 0, s - 1, s - 1], r, fill=(0, 0, 0, 255))
    d.rounded_rectangle([s * 0.012, s * 0.012, s - 1 - s * 0.012, s - 1 - s * 0.012], r, outline=(255, 255, 255, 46), width=max(2, s // 128))
    w = int(s * 0.07)
    ink = (252, 253, 255, 255)

    def pt(x, y):
        return (x * s / 108.0, y * s / 108.0)

    def stroke(points):
        for a, b in zip(points, points[1:]):
            d.line([pt(*a), pt(*b)], fill=ink, width=w)
        for p in points:
            x, y = pt(*p)
            d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=ink)

    stroke([(39, 69), (69, 39)])
    stroke([(46, 39), (69, 39), (69, 62)])
    return img.resize((size, size), Image.LANCZOS)


sizes = [16, 24, 32, 48, 64, 128, 256]
frames = []
for sz in sizes:
    buf = io.BytesIO()
    draw(sz).save(buf, "PNG")
    frames.append(buf.getvalue())

header = struct.pack("<HHH", 0, 1, len(sizes))
offset = 6 + 16 * len(sizes)
entries = b""
for sz, data in zip(sizes, frames):
    entries += struct.pack("<BBBBHHII", 0 if sz >= 256 else sz, 0 if sz >= 256 else sz, 0, 0, 1, 32, len(data), offset)
    offset += len(data)
with open(os.path.join(OUT, "send.ico"), "wb") as f:
    f.write(header + entries + b"".join(frames))
draw(256).save(os.path.join(OUT, "send.png"))
print("wrote", os.path.join(OUT, "send.ico"), os.path.getsize(os.path.join(OUT, "send.ico")), "bytes")
