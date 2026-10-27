"""Builds the static fonts the Windows app embeds (WPF cannot use variable-font axes).

Input : android/app/src/main/res/font/geist*.ttf (variable), assets/fonts-src/InstrumentSerif-*.ttf
Output: windows/Send/Fonts/*.ttf
Run   : python make-fonts.py   (needs fonttools)
"""
import os
import shutil

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res", "font")
SRC = os.path.join(ROOT, "assets", "fonts-src")
OUT = os.path.join(ROOT, "windows", "Send", "Fonts")
os.makedirs(OUT, exist_ok=True)


def rename(font, family, style="Regular"):
    name = font["name"]
    full = family if style == "Regular" else f"{family} {style}"
    ps = full.replace(" ", "")
    for rec in list(name.names):
        if rec.nameID in (16, 17, 21, 22, 25):
            name.removeNames(nameID=rec.nameID)
    for nid, val in ((1, family), (2, style), (3, ps), (4, full), (6, ps)):
        name.setName(val, nid, 3, 1, 0x409)
        name.setName(val, nid, 1, 0, 0)


def instance(src, weight, family, out):
    f = TTFont(src)
    f = instantiateVariableFont(f, {"wght": weight}, inplace=False)
    rename(f, family)
    f["OS/2"].usWeightClass = weight
    f.save(os.path.join(OUT, out))


instance(os.path.join(RES, "geist.ttf"), 400, "Send Sans", "SendSans-Regular.ttf")
instance(os.path.join(RES, "geist.ttf"), 500, "Send Sans Medium", "SendSans-Medium.ttf")
instance(os.path.join(RES, "geist_mono.ttf"), 400, "Send Mono", "SendMono-Regular.ttf")
for f in ("InstrumentSerif-Regular.ttf", "InstrumentSerif-Italic.ttf"):
    shutil.copy(os.path.join(SRC, f), os.path.join(OUT, f))

for f in sorted(os.listdir(OUT)):
    t = TTFont(os.path.join(OUT, f))
    print(f, "|", t["name"].getDebugName(1), "|", t["name"].getDebugName(2), "|", os.path.getsize(os.path.join(OUT, f)), "bytes")
