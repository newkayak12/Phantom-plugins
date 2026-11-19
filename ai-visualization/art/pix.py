"""Core pixel-art helpers: colors, sprite canvas, 2:1 isometric box rasterizer, outlines.

Iso convention (native pixels):  1 iso unit along u -> (+2, +1) px, along v -> (-2, +1) px, z -> (0, -1) px.
A 32x16 floor cell = 8 units, a 64x32 floor tile = 16 units.
"""
import math
from PIL import Image


def C(s, a=255):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


INK = C('#2a1d2a')
_DARK = (24, 16, 34)


def mix(a, b, t):
    return tuple(int(round(a[i] * (1 - t) + b[i] * t)) for i in range(3)) + (a[3] if len(a) > 3 else 255,)


def dk(c, t):  # darken toward a cool purple-black (hue-shifted shading)
    return mix(c, _DARK + (255,), t)


def lt(c, t):  # lighten toward warm white
    return mix(c, (255, 250, 236, 255), t)


def E(c):  # emissive marker: 5th element
    return (c[0], c[1], c[2], 255, 1)


def H(*a):
    """Deterministic hash -> [0,1)."""
    h = 2166136261
    for x in a:
        for b in str(x).encode() + b'|':
            h = ((h ^ b) * 16777619) & 0xffffffff
    return h / 4294967296.0


class Spr:
    def __init__(s, w, h, ax=0, ay=0):
        s.w, s.h, s.ax, s.ay = w, h, ax, ay
        s.img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        s.em = Image.new('L', (w, h), 0)
        s._load()

    def _load(s):
        s.p = s.img.load()
        s.e = s.em.load()

    def put(s, x, y, c):
        if c is None or not (0 <= x < s.w and 0 <= y < s.h):
            return
        em = len(c) > 4 and c[4]
        if c[3] < 255:
            d = s.p[x, y]
            a = c[3] / 255.0
            if d[3] == 0:
                s.p[x, y] = c[:4]
            else:
                s.p[x, y] = (int(d[0] * (1 - a) + c[0] * a), int(d[1] * (1 - a) + c[1] * a),
                             int(d[2] * (1 - a) + c[2] * a), max(d[3], c[3]))
        else:
            s.p[x, y] = c[:4]
        s.e[x, y] = 255 if em else 0

    def rect(s, x0, y0, x1, y1, c):  # inclusive
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                s.put(x, y, c)

    def paste(s, o, x, y):
        """Composite sprite o with its top-left at (x,y)."""
        layer = Image.new('RGBA', (s.w, s.h), (0, 0, 0, 0))
        layer.paste(o.img, (x, y))
        el = Image.new('L', (s.w, s.h), 0)
        el.paste(o.em, (x, y))
        mask = layer.getchannel('A').point(lambda a: 255 if a > 160 else 0)
        s.img.alpha_composite(layer)
        s.em = Image.composite(el, s.em, mask)
        s._load()

    def place(s, o, X, Y):
        """Place sprite o so its anchor lands at (X,Y)."""
        s.paste(o, int(X - o.ax), int(Y - o.ay))

    def crop(s, pad=0):
        bb = s.img.getbbox()
        if not bb:
            return s
        x0, y0, x1, y1 = bb[0] - pad, bb[1] - pad, bb[2] + pad, bb[3] + pad
        n = Spr(x1 - x0, y1 - y0, s.ax - x0, s.ay - y0)
        n.img = s.img.crop((x0, y0, x1, y1))
        n.em = s.em.crop((x0, y0, x1, y1))
        n._load()
        return n


def outline(s, col=None, f=0.55):
    """Selective inside outline: silhouette-edge pixels are darkened (keeps geometry)."""
    src = s.img.copy().load()
    for y in range(s.h):
        for x in range(s.w):
            c = src[x, y]
            if c[3] < 200:
                continue
            edge = False
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if not (0 <= nx < s.w and 0 <= ny < s.h) or src[nx, ny][3] == 0:
                    edge = True
                    break
            if edge:
                s.p[x, y] = col if col else dk(c, f)
                s.e[x, y] = 0


def outline_out(s, col=INK):
    """Outside 1px outline (for characters / bubbles)."""
    src = s.img.copy().load()
    for y in range(s.h):
        for x in range(s.w):
            if src[x, y][3] != 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < s.w and 0 <= ny < s.h and src[nx, ny][3] > 0:
                    s.p[x, y] = col
                    break


def part(s, fn, ol=0.5, col=None):
    """Draw fn into its own layer, outline it, composite onto s (gives per-part outlines)."""
    t = Spr(s.w, s.h, s.ax, s.ay)
    fn(t)
    if ol:
        outline(t, col, ol)
    s.paste(t, 0, 0)


def box(s, u0, v0, z0, a, b, h, tex, faces='TLR'):
    """Rasterize an iso box. tex(role, a, d, z, A, D, H, screen_face) -> color|None."""
    ox, oy = s.ax, s.ay
    xs, ys = [], []
    for u in (u0, u0 + a):
        for v in (v0, v0 + b):
            for z in (z0, z0 + h):
                xs.append(ox + 2 * u - 2 * v)
                ys.append(oy + u + v - z)
    for py in range(int(math.floor(min(ys))) - 1, int(math.ceil(max(ys))) + 1):
        for px in range(int(math.floor(min(xs))) - 1, int(math.ceil(max(xs))) + 1):
            rx, ry = px + 0.5 - ox, py + 0.5 - oy
            c = None
            if 'T' in faces:
                t = ry + z0 + h
                u = rx / 4 + t / 2
                v = t / 2 - rx / 4
                if u0 <= u < u0 + a and v0 <= v < v0 + b:
                    c = tex('T', u - u0, v - v0, h, a, b, h, 'T')
            if c is None and h > 0 and 'L' in faces:
                v = v0 + b
                u = rx / 2 + v
                z = u + v - ry
                if u0 <= u < u0 + a and z0 <= z < z0 + h:
                    c = tex('L', u - u0, b, z - z0, a, b, h, 'L')
            if c is None and h > 0 and 'R' in faces:
                u = u0 + a
                v = u - rx / 2
                z = u + v - ry
                if v0 <= v < v0 + b and z0 <= z < z0 + h:
                    c = tex('R', a, v - v0, z - z0, a, b, h, 'R')
            if c is not None:
                s.put(px, py, c)


def obox(s, o, a0, d0, z0, A, D, h, tex, faces='TFS'):
    """Oriented box. o='rw' (along u, front face = L) or 'lw' (along v, front face = R).
    tex receives role in T/F/S with (along, depth) coordinates."""
    if o == 'rw':
        fm = {'T': 'T', 'F': 'L', 'S': 'R'}
        rm = {'T': 'T', 'L': 'F', 'R': 'S'}
        box(s, a0, d0, z0, A, D, h,
            lambda f, uu, vv, zz, aa, bb, hh, sf: tex(rm[f], uu, vv, zz, aa, bb, hh, sf),
            ''.join(fm[c] for c in faces))
    else:
        fm = {'T': 'T', 'F': 'R', 'S': 'L'}
        rm = {'T': 'T', 'R': 'F', 'L': 'S'}
        box(s, d0, a0, z0, D, A, h,
            lambda f, uu, vv, zz, aa, bb, hh, sf: tex(rm[f], vv, uu, zz, bb, aa, hh, sf),
            ''.join(fm[c] for c in faces))


def M(top, lc=None, rc=None, edge=True):
    """Plain 3-tone material: top, left-facing, right-facing (light from upper-left)."""
    lc = lc or dk(top, .16)
    rc = rc or dk(top, .34)

    def f(role, a, d, z, A, D, Hh, sf):
        if sf == 'T':
            if edge and (a >= A - 0.5 or d >= D - 0.5):
                return lt(top, .25)
            return top
        c = lc if sf == 'L' else rc
        if Hh > 2 and z >= Hh - 1:
            return lt(c, .12)
        return c
    return f
