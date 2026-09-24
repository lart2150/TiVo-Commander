"""Draw the remote control artwork from scratch.

The remote screens lay invisible buttons over a picture of a remote, so every
button here sits under the hit box remote.xml and now_showing_remote.xml give
it: move one and it has to move in the layout too.  The look follows the rest
of the app's icons -- dark outline, vertical gradient body, a gloss across the
top, soft drop shadow -- and everything is drawn at S times size and scaled
down, which is what softens the edges.

    python files/remote_art.py app/src/main/res/drawable

needs Pillow, and a bold sans font (Arial on Windows, DejaVu elsewhere).
"""
import math
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

S = 4
W, H = 320, 895

INK = (255, 255, 255, 255)
INK_DIM = (132, 135, 138, 255)

EDGE = (8, 8, 9)
KEY = ((96, 99, 103), (40, 42, 44))
KEY_DIM = ((62, 64, 66), (42, 43, 45))
RING = ((84, 87, 91), (34, 36, 38))
BODY = ((66, 68, 72), (22, 23, 25))
BLUE = ((150, 214, 247), (23, 84, 137), (12, 44, 74))
RED = ((236, 96, 90), (150, 18, 22), (62, 8, 10))
GREEN = ((160, 226, 104), (44, 128, 32), (18, 56, 12))
AMBER = ((255, 222, 96), (204, 146, 8), (84, 58, 0))
YELLOW = ((255, 226, 90), (206, 160, 10), (86, 64, 0))


def font(size):
    for name in ("arialbd.ttf", "C:/Windows/Fonts/arialbd.ttf",
                 "DejaVuSans-Bold.ttf",
                 "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, int(size * S))
        except OSError:
            pass
    raise SystemExit("no bold sans font found")


class Canvas:
    """An RGBA image at S times size, taking coordinates at 1x."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.img = Image.new("RGBA", (w * S, h * S), (0, 0, 0, 0))

    def mask(self):
        return Image.new("L", self.img.size, 0)

    def gradient(self, box, top, bottom):
        """A vertical gradient spanning just box, so each part shades alone."""
        y0, y1 = int(box[1] * S), int(box[3] * S)
        col = Image.new("RGBA", (1, max(1, y1 - y0)))
        px = col.load()
        for y in range(col.height):
            t = y / max(1, col.height - 1)
            px[0, y] = tuple(
                int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        full = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        full.paste(col.resize((self.img.width, col.height)), (0, y0))
        return full

    def paint(self, mask, top, bottom, box):
        g = self.gradient(box, top, bottom)
        self.img.paste(g, (0, 0), mask)

    def flat(self, mask, rgba):
        self.img.paste(Image.new("RGBA", self.img.size, rgba), (0, 0), mask)

    def shadow(self, mask, dy=2.0, blur=2.5, alpha=150):
        m = mask.filter(ImageFilter.GaussianBlur(blur * S))
        m = ImageChops.offset(m, 0, int(dy * S))
        m = m.point(lambda v: v * alpha // 255)
        self.flat(m, (0, 0, 0, 255))

    def gloss(self, mask, box, strength=70, soft=0):
        """The highlight across the top half of a shape.

        soft blurs its lower edge, which a tall shape needs: a hard arc across
        the middle of the whole remote reads as a seam.
        """
        x0, y0, x1, y1 = box
        g = self.mask()
        ImageDraw.Draw(g).ellipse(
            [(x0 - (x1 - x0) * 0.2) * S, (y0 - (y1 - y0) * 0.55) * S,
             (x1 + (x1 - x0) * 0.2) * S, (y0 + (y1 - y0) * 0.52) * S],
            fill=strength)
        if soft:
            g = g.filter(ImageFilter.GaussianBlur(soft * S))
        self.flat(ImageChops.multiply(g, mask), (255, 255, 255, 255))

    def shape(self, draw_fn, colors, edge=EDGE, rim=1.6, shine=70, drop=True,
              soft=0):
        """Shadow, dark edge, gradient body and gloss for one shape.

        draw_fn(draw, inset) draws the shape into a mask, shrunk by inset.
        """
        outer = self.mask()
        draw_fn(ImageDraw.Draw(outer), 0)
        inner = self.mask()
        draw_fn(ImageDraw.Draw(inner), rim)
        box = [v / S for v in outer.getbbox()]
        if drop:
            self.shadow(outer)
        self.flat(outer, edge + (255,))
        self.paint(inner, colors[0], colors[1], box)
        if shine:
            self.gloss(inner, box, shine, soft)
        return box

    def draw(self):
        return ImageDraw.Draw(self.img)

    def text(self, xy, s, size, fill=INK, spacing=0):
        d = self.draw()
        f = font(size)
        d.multiline_text((xy[0] * S, xy[1] * S), s, font=f, fill=fill,
                         anchor="mm", align="center", spacing=spacing * S)

    def poly(self, pts, fill=INK):
        self.draw().polygon([(x * S, y * S) for x, y in pts], fill=fill)

    def finish(self, w=None, h=None):
        return self.img.resize((w or self.w, h or self.h), Image.LANCZOS)


# ---------------------------------------------------------------- shapes

def ellipse(cx, cy, rx, ry):
    def fn(d, inset):
        d.ellipse([(cx - rx + inset) * S, (cy - ry + inset) * S,
                   (cx + rx - inset) * S, (cy + ry - inset) * S], fill=255)
    return fn


def capsule(p1, p2, r):
    def fn(d, inset):
        rr = r - inset
        d.line([p1[0] * S, p1[1] * S, p2[0] * S, p2[1] * S], fill=255,
               width=int(rr * 2 * S))
        for x, y in (p1, p2):
            d.ellipse([(x - rr) * S, (y - rr) * S, (x + rr) * S, (y + rr) * S],
                      fill=255)
    return fn


def rounded(box, radius):
    def fn(d, inset):
        d.rounded_rectangle(
            [(box[0] + inset) * S, (box[1] + inset) * S,
             (box[2] - inset) * S, (box[3] - inset) * S],
            radius=(radius - inset) * S, fill=255)
    return fn


# ---------------------------------------------------------------- glyphs

def tri(c, cx, cy, size, direction, fill=INK):
    """A triangle pointing up, down, left or right, centred on cx, cy."""
    h = size * 0.9
    pts = {
        "up": [(cx, cy - h / 2), (cx + size / 2, cy + h / 2), (cx - size / 2, cy + h / 2)],
        "down": [(cx, cy + h / 2), (cx + size / 2, cy - h / 2), (cx - size / 2, cy - h / 2)],
        "left": [(cx - h / 2, cy), (cx + h / 2, cy - size / 2), (cx + h / 2, cy + size / 2)],
        "right": [(cx + h / 2, cy), (cx - h / 2, cy - size / 2), (cx - h / 2, cy + size / 2)],
    }[direction]
    c.poly(pts, fill)


def bar(c, x0, y0, x1, y1, fill=INK):
    c.draw().rectangle([x0 * S, y0 * S, x1 * S, y1 * S], fill=fill)


def house(c, cx, cy, s, fill=INK):
    c.poly([(cx, cy - s * 0.62), (cx + s * 0.62, cy - s * 0.02),
            (cx + s * 0.42, cy - s * 0.02), (cx + s * 0.42, cy + s * 0.52),
            (cx + s * 0.12, cy + s * 0.52), (cx + s * 0.12, cy + s * 0.16),
            (cx - s * 0.12, cy + s * 0.16), (cx - s * 0.12, cy + s * 0.52),
            (cx - s * 0.42, cy + s * 0.52), (cx - s * 0.42, cy - s * 0.02),
            (cx - s * 0.62, cy - s * 0.02)], fill)


def thumb(c, cx, cy, s, up, fill=INK):
    """A plain hand with the thumb out, drawn upright then flipped for down."""
    layer = Canvas(c.w, c.h)
    d = layer.draw()
    k = lambda v: v * S
    # Side view of a fist: cuff at the left, palm, four stacked fingers to the
    # right, and the thumb standing up from the palm's top corner.
    d.rectangle([k(cx - s * 0.62), k(cy - s * 0.10), k(cx - s * 0.44),
                 k(cy + s * 0.58)], fill=fill)
    d.rounded_rectangle([k(cx - s * 0.40), k(cy - s * 0.14), k(cx + s * 0.06),
                         k(cy + s * 0.58)], radius=k(s * 0.08), fill=fill)
    for i in range(4):
        y0 = cy - s * 0.14 + i * s * 0.185
        d.rounded_rectangle([k(cx - s * 0.10), k(y0), k(cx + s * 0.50),
                             k(y0 + s * 0.155)], radius=k(s * 0.075), fill=fill)
    d.rounded_rectangle([k(cx - s * 0.40), k(cy - s * 0.70), k(cx - s * 0.10),
                         k(cy - s * 0.02)], radius=k(s * 0.15), fill=fill)
    if not up:
        box = tuple(int(v) for v in (k(cx - s), k(cy - s), k(cx + s), k(cy + s)))
        piece = layer.img.crop(box).transpose(Image.FLIP_TOP_BOTTOM)
        layer.img = Image.new("RGBA", layer.img.size, (0, 0, 0, 0))
        layer.img.paste(piece, box[:2])
    c.img = Image.alpha_composite(c.img, layer.img)


def replay_arrow(c, cx, cy, r, width, fill=INK):
    d = c.draw()
    d.arc([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S],
          start=-60, end=210, fill=fill, width=int(width * S))
    # Arrowhead at the arc's start, pointing on round the circle.
    a = math.radians(-60)
    ex, ey = cx + r * math.cos(a), cy + r * math.sin(a)
    hs = width * 1.9
    c.poly([(ex - hs * 0.9, ey - hs * 0.2), (ex + hs * 0.8, ey - hs * 0.7),
            (ex + hs * 0.35, ey + hs * 1.0)], fill)


def advance_arrow(c, cx, cy, s, fill=INK):
    bar(c, cx - s * 0.55, cy - s * 0.09, cx + s * 0.05, cy + s * 0.09, fill)
    tri(c, cx + s * 0.18, cy, s * 0.62, "right", fill)
    bar(c, cx + s * 0.48, cy - s * 0.34, cx + s * 0.64, cy + s * 0.34, fill)


# --------------------------------------------------------------- buttons

def key(c, cx, cy, rx, ry=None, colors=KEY, edge=EDGE, label=None, size=11,
        ink=INK):
    ry = ry or rx
    c.shape(ellipse(cx, cy, rx, ry), colors, edge)
    if label:
        c.text((cx, cy), label, size, ink, spacing=1)


def colored(c, cx, cy, r, tone, label=None, size=12):
    top, bottom, edge = tone
    key(c, cx, cy, r, colors=(top, bottom), edge=edge, label=label, size=size)


def home_key(c, cx, cy, r):
    colored(c, cx, cy, r, BLUE)
    house(c, cx, cy + 1, r * 0.95)


def replay_key(c, cx, cy, r):
    key(c, cx, cy, r)
    replay_arrow(c, cx, cy, r * 0.5, r * 0.19)


def advance_key(c, cx, cy, r):
    key(c, cx, cy, r)
    advance_arrow(c, cx, cy, r * 1.05)


def dpad(c, cx, cy):
    c.shape(ellipse(cx, cy, 74, 74), RING)
    key(c, cx, cy, 31, label="Select", size=12)
    for dx, dy, way in ((0, -57, "up"), (0, 57, "down"),
                        (-54, 0, "left"), (54, 0, "right")):
        tri(c, cx + dx, cy + dy, 17, way)


def playback(c, cx, cy):
    """The transport ring; now_showing_remote crops this out on its own."""
    c.shape(ellipse(cx, cy, 84, 82), RING)
    key(c, cx, cy, 33, colors=AMBER[:2], edge=AMBER[2])
    ink = (40, 30, 0, 255)
    bar(c, cx - 9, cy - 12, cx - 3, cy + 12, ink)
    bar(c, cx + 3, cy - 12, cx + 9, cy + 12, ink)
    tri(c, cx + 1, cy - 65, 17, "right")
    tri(c, cx - 60, cy, 15, "left")
    tri(c, cx - 72, cy, 15, "left")
    tri(c, cx + 60, cy, 15, "right")
    tri(c, cx + 72, cy, 15, "right")
    bar(c, cx - 8, cy + 55, cx - 4, cy + 71)
    tri(c, cx + 5, cy + 63, 15, "right")


def rocker(c, p1, p2, label, dim=False):
    colors, ink = (KEY_DIM, INK_DIM) if dim else (KEY, INK)
    c.shape(capsule(p1, p2, 22), colors)
    mx, my = (p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2
    c.text((mx, my), label, 13, ink)
    tri(c, p1[0], p1[1] - 2, 13, "up", ink)
    tri(c, p2[0], p2[1] + 2, 13, "down", ink)


# ---------------------------------------------------------------- remote

def remote():
    c = Canvas(W, H)
    body = rounded((14, 6, W - 14, H - 6), 64)
    c.shape(body, BODY, rim=2.5, shine=30, soft=60)
    # A faint lighter line just inside the edge, so the body reads as rounded.
    rim = c.mask()
    rounded((17, 9, W - 17, H - 9), 61)(ImageDraw.Draw(rim), 0)
    rounded((18.5, 10.5, W - 18.5, H - 10.5), 59.5)(ImageDraw.Draw(rim), 0)
    inner = c.mask()
    rounded((18.5, 10.5, W - 18.5, H - 10.5), 59.5)(ImageDraw.Draw(inner), 0)
    c.flat(ImageChops.subtract(rim, inner).point(lambda v: v * 40 // 255),
           (255, 255, 255, 255))

    # Top row.  TV power and volume are drawn but have no hit box: the DVR
    # cannot send them, so they are dimmed as on the original.
    home_key(c, 159, 79, 27)
    key(c, 94, 86, 20, colors=KEY_DIM, label="TV\nPwr", size=8.5, ink=INK_DIM)
    key(c, 226, 86, 20, label="Live\nTV", size=8.5)
    key(c, 70.5, 134, 19, label="Zoom", size=9)
    key(c, 249.5, 132, 19, label="Info", size=10)
    key(c, 56.5, 184, 19, label="Back", size=9)
    key(c, 262.5, 184, 19, label="Guide", size=9)

    dpad(c, 159.5, 207)
    rocker(c, (259.5, 236), (228.5, 295), "Ch")
    rocker(c, (60.5, 236), (91.5, 295), "Vol", dim=True)

    colored(c, 159.5, 331.5, 18, RED, "Rec", 10)
    colored(c, 80.5, 360.5, 21, RED)
    thumb(c, 80.5, 361, 19, up=False)
    colored(c, 237.5, 360.5, 21, GREEN)
    thumb(c, 237.5, 360, 19, up=True)

    playback(c, 161.5, 453)

    replay_key(c, 76.5, 532.5, 18)
    advance_key(c, 242.5, 532.5, 18)

    for (label, tone), x in zip((("A", YELLOW), ("B", BLUE), ("C", RED),
                                 ("D", GREEN)), COLOR_X):
        colored(c, x, COLOR_Y, 17, tone, label, 14)

    for row, labels in enumerate(PAD):
        for col, label in enumerate(labels):
            key(c, PAD_X[col], PAD_Y[row], 27, 16, label=label,
                size=16 if len(label) == 1 else 10)
    return c


# The colour buttons in one row and the number pad as a grid.  remote.xml's
# hit boxes for these are centred on the same points: 43x39 for the colours,
# 60x40 for the pad.
COLOR_Y = 592
COLOR_X = (78.5, 132.5, 186.5, 240.5)
PAD = (("1", "2", "3"), ("4", "5", "6"), ("7", "8", "9"),
       ("Clear", "0", "Enter"))
PAD_X = (90, 160, 230)
PAD_Y = (642, 688, 734, 780)


def play_controls():
    """now_showing_remote's 180x170 crop: the ring alone, pause at 89,83.5."""
    c = Canvas(180, 170)
    playback(c, 89, 84)
    return c.finish()


def lone_key(w, h, draw_fn):
    c = Canvas(w, h)
    draw_fn(c, w / 2, h / 2 - 1, min(w, h) / 2 - 4)
    return c.finish()


def icon(art, angle, w, h):
    """The whole remote shrunk to an icon, turned as the old icons were."""
    turned = art.rotate(angle, resample=Image.BICUBIC, expand=True)
    turned = turned.crop(turned.getbbox())
    scale = min(w / turned.width, h / turned.height)
    small = turned.resize((max(1, round(turned.width * scale)),
                           max(1, round(turned.height * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.alpha_composite(small, ((w - small.width) // 2, (h - small.height) // 2))
    return out


def main():
    out = sys.argv[1]
    full = remote()
    big = full.img
    full.finish().save(os.path.join(out, "remote_full.png"))
    play_controls().save(os.path.join(out, "remote_play_controls.png"))
    lone_key(68, 68, replay_key).save(os.path.join(out, "remote_button_replay.png"))
    lone_key(69, 68, advance_key).save(os.path.join(out, "remote_button_advance.png"))
    icon(big, -35, 32, 41).save(os.path.join(out, "icon_remote.png"))
    icon(big, -90, 60, 32).save(os.path.join(out, "icon_remote_32h.png"))


if __name__ == "__main__":
    main()
