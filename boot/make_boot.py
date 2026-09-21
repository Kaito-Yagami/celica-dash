# -*- coding: utf-8 -*-
"""
The boot splash and boot animation for the QF (Qianfeng) head units, in the form the vendor's
own importer takes - no root, no adb.

    python boot/make_boot.py --name KADE --wordmark "CELICA *T*-SPORT" --line "ZZT231 · 2ZZ-GE" --out setup/payload/boot

writes <out>/QF/{import_auto.cfg, logo.bmp, bootanimation.zip} and <out>/preview.png. Copy the
QF folder to the root of a FAT32 USB stick, plug it into the unit, say yes when it offers to
import; it reboots itself. The firmware's Car Settings app does the writing: logo.bmp to the
logo partition (the very first thing on the screen), bootanimation.zip to /great.

logo.bmp: 1024x600, 8-bit palettised, as the unit's own logo_default.bmp is - "WELCOME, NAME"
over the wordmark. bootanimation.zip: stored, desc.txt "1024 600 20", part0 once (a rev
counter drawing itself, the needle sweeping, the wordmark typing in), part1 looped (a glow
breathing) until Android is up.

Needs Pillow (RUN ME installs it into setup/tools/venv on its own).
"""
import argparse
import io
import math
import os
import zipfile

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
W, H, FPS = 1024, 600, 20
BG = (0, 0, 0)
WHITE = (241, 237, 234)
DIM = (143, 136, 132)
CHROME = (199, 202, 207)


def font(name, size):
    for d in (os.path.join(HERE, "fonts"), os.path.join(ROOT, "android", "app", "src", "main", "res", "font")):
        p = os.path.join(d, name)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def ease(t):
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def runs(wordmark):
    """(text, accented) runs from the asterisks, as the dash reads them."""
    out, buf, acc = [], "", False
    for ch in wordmark:
        if ch == "*":
            if buf:
                out.append((buf, acc))
            buf, acc = "", not acc
        else:
            buf += ch
    if buf:
        out.append((buf, acc))
    return out or [("DASH", False)]


class Maker:
    def __init__(self, name, wordmark, line, accent):
        self.name = name.strip().upper()
        self.runs = runs(wordmark.strip().upper())
        self.line = line.strip()
        self.red = accent
        self.glow = tuple(int(c * 0.5) for c in accent)
        self.big = font("michroma_regular.ttf", 44)
        self.small = font("michroma_regular.ttf", 20)
        self.mono = font("plexmono_regular.ttf", 15)

    def tach(self, draw, cx, cy, r, progress, needle, stroke):
        """A rev counter: the arc up to `progress` (0..1 of 240 degrees), the hot zone as the last sixth, the needle at `needle` (0..1)."""
        box = [cx - r, cy - r, cx + r, cy + r]
        start, sweep = 150, 240
        if progress > 0:
            hot_from = 5 / 6
            grey_end = start + sweep * min(progress, hot_from)
            draw.arc(box, start, grey_end, fill=CHROME, width=stroke)
            if progress > hot_from:
                draw.arc(box, start + sweep * hot_from, start + sweep * progress, fill=self.red, width=stroke)
        if needle is not None:
            a = math.radians(start + sweep * needle)
            draw.line([(cx, cy), (cx + math.cos(a) * r * 0.78, cy + math.sin(a) * r * 0.78)], fill=WHITE, width=max(3, stroke // 2))
            draw.ellipse([cx - 9, cy - 9, cx + 9, cy + 9], fill=WHITE)

    def frame(self, t, loop_t=None, welcome=False):
        im = Image.new("RGB", (W, H), BG)
        cx, cy = W // 2, H // 2 - 60
        r = 150
        if loop_t is None:
            p = min(1.0, t / 1.4)
            # the needle: a sweep up as the arc finishes, a fall back to a little past idle
            n = 0.0 if t < 1.1 else (ease((t - 1.1) / 0.7) * 0.92 if t < 1.8 else 0.92 - ease((t - 1.8) / 0.9) * 0.80)
        else:
            p, n = 1.0, 0.12 + 0.02 * math.sin(loop_t * 2 * math.pi)
        # a soft glow under the hot zone
        g = Image.new("RGB", (W, H), BG)
        gd = ImageDraw.Draw(g)
        if p > 5 / 6:
            self.tach(gd, cx, cy, r, p, None, 34)
            g = g.filter(ImageFilter.GaussianBlur(16))
            k = 0.55 if loop_t is None else 0.4 + 0.25 * (0.5 + 0.5 * math.sin(loop_t * 2 * math.pi))
            g = g.point(lambda v: int(v * k))
            im.paste(g, (0, 0), g.convert("L"))
        d = ImageDraw.Draw(im)
        self.tach(d, cx, cy, r, p, n if (loop_t is not None or t >= 1.1) else None, 14)

        # the wordmark, typing in run by run; the welcome line above it on the splash
        base_y = cy + r + 44
        if loop_t is None:
            reveal = ease((t - 1.6) / 0.9)
        else:
            reveal = 1.0
        widths = [d.textlength(txt, font=self.big) for txt, _ in self.runs]
        total = sum(widths)
        x = cx - total / 2
        chars = sum(len(txt) for txt, _ in self.runs)
        shown = int(round(chars * reveal))
        for (txt, acc), w in zip(self.runs, widths):
            take = max(0, min(len(txt), shown))
            shown -= len(txt)
            if take > 0:
                d.text((x, base_y), txt[:take], font=self.big, fill=self.red if acc else WHITE)
            x += w
        if welcome or loop_t is not None or t > 2.8:
            wl = "WELCOME, " + self.name if self.name else ""
            if wl and welcome:
                ww = d.textlength(wl, font=self.small)
                d.text((cx - ww / 2, base_y - 40), wl, font=self.small, fill=DIM)
        if self.line and (loop_t is not None or t > 3.0):
            lw = d.textlength(self.line, font=self.mono)
            d.text((cx - lw / 2, H - 60), self.line, font=self.mono, fill=(90, 90, 90))
        return im

    def make(self, out):
        qf = os.path.join(out, "QF")
        os.makedirs(qf, exist_ok=True)
        open(os.path.join(qf, "import_auto.cfg"), "w").close()
        # the splash: the finished picture with the welcome line, 8-bit like the unit's own
        logo = self.frame(10.0, welcome=True)
        logo.convert("P", palette=Image.ADAPTIVE, colors=256).save(os.path.join(qf, "logo.bmp"), "BMP")
        # the animation: part0 once, part1 looped until Android is up
        part0 = [self.frame(i / FPS) for i in range(int(4.0 * FPS))]
        part1 = [self.frame(10.0, loop_t=i / 60) for i in range(60)]
        zpath = os.path.join(qf, "bootanimation.zip")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:
            z.writestr("desc.txt", "%d %d %d\np 1 0 part0\np 0 0 part1\n" % (W, H, FPS))
            for i, f in enumerate(part0):
                z.writestr("part0/boot_%03d.png" % i, png(f))
            for i, f in enumerate(part1):
                z.writestr("part1/loop_%03d.png" % i, png(f))
        picks = [part0[k] for k in (6, 16, 24, 30, 36, 44, 56, 70, 79)]
        sheet = Image.new("RGB", (3 * 512, 3 * 300))
        for i, f in enumerate(picks):
            sheet.paste(f.resize((512, 300)), ((i % 3) * 512, (i // 3) * 300))
        sheet.save(os.path.join(out, "preview.png"))
        logo.save(os.path.join(out, "splash-preview.png"))
        print("wrote %s - logo.bmp %d KB, bootanimation.zip %d KB" % (qf, os.path.getsize(os.path.join(qf, "logo.bmp")) // 1024, os.path.getsize(zpath) // 1024))


def png(im):
    b = io.BytesIO()
    im.save(b, "PNG", optimize=True)
    return b.getvalue()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="", help="the name on the splash: WELCOME, NAME")
    ap.add_argument("--wordmark", default="CELICA *T*-SPORT", help="the animated wordmark; *...* is drawn in the accent")
    ap.add_argument("--line", default="", help="a quiet line at the foot, e.g. 'ZZT231 · 2ZZ-GE'")
    ap.add_argument("--accent", default="D8433C", help="hex colour of the accent")
    ap.add_argument("--out", default=os.path.join(HERE, "out"))
    a = ap.parse_args()
    hx = a.accent.strip().lstrip("#")
    accent = tuple(int(hx[i:i + 2], 16) for i in (0, 2, 4)) if len(hx) == 6 else (216, 67, 60)
    Maker(a.name, a.wordmark, a.line, accent).make(a.out)


if __name__ == "__main__":
    main()
