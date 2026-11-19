"""Core helpers for the hi-res iso pixel-art prototype.

Iso convention (native px): u -> (+2,+1), v -> (-2,+1), z -> (0,-1).
A 64x32 floor tile = 16 units per side.
"""
import math
from PIL import Image, ImageDraw


def C(s, a=255):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def mix(a, b, t):
    return tuple(int(round(a[i] * (1 - t) + b[i] * t)) for i in range(3)) + (255,)


WARM = (255, 244, 214, 255)
COOL = (34, 20, 48, 255)
INK = C('#1e1420')


def ramp(base, hi=.42, lo=.30):
    """6 tones: 0 highlight, 1 light, 2 base, 3 shadow, 4 deep, 5 ink. Hue-shifted."""
    b = C(base) if isinstance(base, str) else base
    return [mix(b, WARM, hi), mix(b, WARM, hi * .45), b, mix(b, COOL, lo),
            mix(b, COOL, lo * 1.8), mix(b, COOL, .78)]


def H(*a):
    h = 2166136261
    for x in a:
        for b in str(x).encode() + b'|':
            h = ((h ^ b) * 16777619) & 0xffffffff
    return h / 4294967296.0


class Spr:
    def __init__(s, w, h, ax=0, ay=0):
        s.w, s.h, s.ax, s.ay = w, h, ax, ay
        s.img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        s.p = s.img.load()

    def reload(s):
        s.p = s.img.load()

    def put(s, x, y, c):
        if c is None or not (0 <= x < s.w and 0 <= y < s.h):
            return
        if len(c) > 3 and c[3] < 255:
            d = s.p[x, y]
            a = c[3] / 255.0
            if d[3] == 0:
                s.p[x, y] = tuple(c[:4])
            else:
                s.p[x, y] = (int(d[0] * (1 - a) + c[0] * a), int(d[1] * (1 - a) + c[1] * a),
                             int(d[2] * (1 - a) + c[2] * a), max(d[3], c[3]))
        else:
            s.p[x, y] = tuple(c[:3]) + (255,)

    def get(s, x, y):
        if 0 <= x < s.w and 0 <= y < s.h:
            return s.p[x, y]
        return (0, 0, 0, 0)

    def rect(s, x0, y0, x1, y1, c):  # inclusive
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                s.put(x, y, c)

    def hline(s, x0, x1, y, c):
        for x in range(x0, x1 + 1):
            s.put(x, y, c)

    def vline(s, x, y0, y1, c):
        for y in range(y0, y1 + 1):
            s.put(x, y, c)

    def line(s, x0, y0, x1, y1, c):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(n + 1):
            s.put(int(round(x0 + (x1 - x0) * i / n)), int(round(y0 + (y1 - y0) * i / n)), c)

    def paste(s, o, x, y):
        layer = Image.new('RGBA', (s.w, s.h), (0, 0, 0, 0))
        layer.paste(o.img, (int(x), int(y)))
        s.img.alpha_composite(layer)
        s.reload()

    def place(s, o, X, Y):
        s.paste(o, int(round(X - o.ax)), int(round(Y - o.ay)))

    def flip(s):
        n = Spr(s.w, s.h, s.w - 1 - s.ax, s.ay)
        n.img = s.img.transpose(Image.FLIP_LEFT_RIGHT)
        n.reload()
        return n

    def crop(s, pad=1):
        bb = s.img.getbbox()
        if not bb:
            return s
        x0, y0, x1, y1 = bb[0] - pad, bb[1] - pad, bb[2] + pad, bb[3] + pad
        n = Spr(x1 - x0, y1 - y0, s.ax - x0, s.ay - y0)
        n.img = s.img.crop((x0, y0, x1, y1))
        n.reload()
        return n

    def grid(s, x, y, rows, pal):
        """Hand-placed pixel grid. pal maps char -> color; '.' and ' ' skip."""
        for j, r in enumerate(rows):
            for i, ch in enumerate(r):
                if ch in '. ':
                    continue
                c = pal.get(ch)
                if c is not None:
                    s.put(x + i, y + j, c)


# ---------- shaded shapes ----------

def mask_poly(w, h, polys=(), ells=(), rects=()):
    m = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(m)
    for p in polys:
        d.polygon(p, fill=255)
    for e in ells:
        d.ellipse(e, fill=255)
    for r in rects:
        d.rectangle(r, fill=255)
    return m


def blob(s, m, R, lx=.65, ly=.35, edge=True, cuts=(.14, .42, .74, .92), hi=True, dither=False):
    """Fill mask m on sprite s with ramp R, cylinder-shaded with light from upper-left.
    Right/bottom boundary pixels get the deep tone (part separation)."""
    mp = m.load()
    w, h = m.size
    rows, cols = {}, {}
    for y in range(h):
        for x in range(w):
            if mp[x, y]:
                a = rows.setdefault(y, [x, x]); a[0] = min(a[0], x); a[1] = max(a[1], x)
                b = cols.setdefault(x, [y, y]); b[0] = min(b[0], y); b[1] = max(b[1], y)
    for y, (xl, xr) in rows.items():
        for x in range(xl, xr + 1):
            if not mp[x, y]:
                continue
            yt, yb = cols[x]
            tx = (x - xl + .5) / (xr - xl + 1)
            ty = (y - yt + .5) / (yb - yt + 1)
            v = lx * tx + ly * ty
            if dither and (x + y) % 2:
                v += .05
            if v < cuts[0] and hi and (xr - xl) >= 4:
                t = 0
            elif v < cuts[1]:
                t = 1
            elif v < cuts[2]:
                t = 2
            elif v < cuts[3]:
                t = 3
            else:
                t = 4
            if edge:
                r = x + 1 >= w or not mp[x + 1, y]
                b = y + 1 >= h or not mp[x, y + 1]
                if r or b:
                    t = max(t, 4 if r else 3)
            s.put(x, y, R[t])


def outline(s, ink=INK, t=.25, corners=False):
    """Outer 1px outline, tinted slightly by the neighbour colour."""
    src = s.img.copy().load()
    nb = ((1, 0), (-1, 0), (0, 1), (0, -1)) + (((1, 1), (-1, -1), (1, -1), (-1, 1)) if corners else ())
    for y in range(s.h):
        for x in range(s.w):
            if src[x, y][3] != 0:
                continue
            for dx, dy in nb:
                nx, ny = x + dx, y + dy
                if 0 <= nx < s.w and 0 <= ny < s.h and src[nx, ny][3] > 128:
                    s.p[x, y] = mix(ink, src[nx, ny], t)
                    break


# ---------- iso ----------

def iso(ox, oy, u, v, z=0):
    return (ox + 2 * u - 2 * v, oy + u + v - z)


def box(s, ox, oy, u0, v0, z0, a, b, h, tex, faces='TLR'):
    """Iso box. tex(face, p, q, A, B, edge) -> color.
    T: p=u-u0,q=v-v0 (A=a,B=b); L (front-left, v=v0+b): p=u-u0, q=z-z0 (A=a,B=h);
    R (front-right, u=u0+a): p=v-v0, q=z-z0 (A=b,B=h)."""
    xs, ys = [], []
    for u in (u0, u0 + a):
        for v in (v0, v0 + b):
            for z in (z0, z0 + h):
                xs.append(ox + 2 * u - 2 * v)
                ys.append(oy + u + v - z)
    for py in range(int(math.floor(min(ys))) - 1, int(math.ceil(max(ys))) + 1):
        for px in range(int(math.floor(min(xs))) - 1, int(math.ceil(max(xs))) + 1):
            rx, ry = px + .5 - ox, py + .5 - oy
            c = None
            if 'T' in faces:
                t = ry + z0 + h
                u = rx / 4 + t / 2
                v = t / 2 - rx / 4
                if u0 <= u < u0 + a and v0 <= v < v0 + b:
                    p, q = u - u0, v - v0
                    e = p >= a - .5 or q >= b - .5 or p < .5 or q < .5
                    c = tex('T', p, q, a, b, e)
            if c is None and h > 0 and 'L' in faces:
                v = v0 + b
                u = rx / 2 + v
                z = u + v - ry
                if u0 <= u < u0 + a and z0 <= z < z0 + h:
                    p, q = u - u0, z - z0
                    e = p < .5 or p >= a - .5 or q < 1 or q >= h - 1
                    c = tex('L', p, q, a, h, e)
            if c is None and h > 0 and 'R' in faces:
                u = u0 + a
                v = u - rx / 2
                z = u + v - ry
                if v0 <= v < v0 + b and z0 <= z < z0 + h:
                    p, q = v - v0, z - z0
                    e = p < .5 or p >= b - .5 or q < 1 or q >= h - 1
                    c = tex('R', p, q, b, h, e)
            if c is not None:
                s.put(px, py, c)


def mat(R, top=1, left=2, right=3, rim=0, edge=4):
    """Simple 3-face material from a ramp. Front top edges get a rim highlight."""
    def f(face, p, q, A, B, e):
        if face == 'T':
            if p >= A - .5 or q >= B - .5:
                return R[rim]
            return R[top]
        t = left if face == 'L' else right
        if q >= B - 1:
            return R[max(0, t - 1)]
        if q < 1:
            return R[edge]
        if face == 'L' and p < .5:
            return R[max(0, t - 1)]
        return R[t]
    return f


def iso_ellipse(s, cx, cy, rx, col, ry=None):
    ry = ry if ry is not None else rx / 2
    for y in range(int(cy - ry - 1), int(cy + ry + 2)):
        for x in range(int(cx - rx - 1), int(cx + rx + 2)):
            dx = (x + .5 - cx) / rx
            dy = (y + .5 - cy) / ry
            if dx * dx + dy * dy <= 1:
                s.put(x, y, col(dx, dy) if callable(col) else col)


# ---------- tiny pixel font (3x5 / 5x7 glyphs for bubbles & labels) ----------
GL = {
    '<': ["...X", "..X.", ".X..", "X...", ".X..", "..X.", "...X"],
    '>': ["X...", ".X..", "..X.", "...X", "..X.", ".X..", "X..."],
    '/': ["....X", "...X.", "...X.", "..X..", ".X...", ".X...", "X...."],
    '!': ["XX", "XX", "XX", "XX", "..", "XX", "XX"],
}
