"""Furniture & props. Each function returns an outlined Spr whose anchor (ax, ay)
is the screen position of the prop's local iso origin (u=0, v=0, z=0)."""
import math
from core import Spr, C, mix, ramp, box, mat, H, outline, iso_ellipse, mask_poly, blob, INK
import people

WOOD = ramp('#b9773f', .40, .30)
WOOD2 = ramp('#a2643a', .38, .30)
BEIGE = ramp('#d9d0b6', .45, .26)
BEIGE2 = ramp('#c8bea2', .42, .28)
GRAY = ramp('#6c7480', .40, .32)
STEEL = ramp('#a8b0b8', .45, .30)
TERRA = ramp('#c06a3e', .40, .30)
LEAF = ramp('#4f9a3a', .45, .32)
LEAF2 = ramp('#3e8a4e', .45, .32)


def canvas(eu, ev, ez, m=4):
    w = 2 * (eu + ev) + 2 * m
    h = eu + ev + ez + 2 * m
    return Spr(int(w), int(h), int(2 * ev + m), int(ez + m))


def fin(s, ol=True):
    if ol:
        outline(s)
    return s.crop(1)


def woodtex(R, along='u', seed=0, rim=True):
    def f(face, p, q, A, B, e):
        if face == 'T':
            if rim and (p >= A - .5 or q >= B - .5):
                return R[0]
            a, b = (p, q) if along == 'u' else (q, p)
            g = math.sin(b * 2.3 + math.sin(a * .28 + seed) * 1.4 + seed)
            n = H('k', seed, int(a / 3), int(b * 2))
            if g > .82:
                return R[2]
            if n > .93:
                return R[2]
            return R[1]
        t = 2 if face == 'L' else 3
        if q >= B - 1:
            return R[t - 1]
        if q < 1:
            return R[4]
        return R[t]
    return f


# ------------------------------------------------------------------ desk
def desk(a=30, b=14, top_z=26, drawers='L', along='u'):
    """Wooden desk; drawers pedestal at u in [0,9] with drawer fronts on the visible L face."""
    s = canvas(a, b, top_z)
    X, Y = s.ax, s.ay
    R = WOOD
    th = 3
    # back modesty panel
    box(s, X, Y, 1, 1, 6, a - 2, 1, top_z - th - 6, mat(WOOD2, 1, 3, 3))
    # right leg panel
    box(s, X, Y, a - 3, 1, 0, 2, b - 2, top_z - th, mat(R, 1, 2, 3))
    # drawer pedestal
    def ped(face, p, q, A, B, e):
        if face == 'L':
            # 3 drawers
            if p < .6 or p >= A - .6 or q < 1:
                return R[4] if q < 1 else R[3]
            rows = [(2, 7.5), (8.5, 14), (15, 20.5)]
            for (z0, z1) in rows:
                if z0 <= q < z1:
                    if q < z0 + 1 or p < 1.2 or p >= A - 1.2:
                        return R[3]
                    if q >= z1 - 1:
                        return R[1]
                    mid = (z0 + z1) / 2
                    if abs(p - A / 2) < 1.6 and abs(q - mid) < .9:
                        return STEEL[1] if q > mid else STEEL[3]
                    return R[2]
            return R[3]
        if face == 'R':
            return R[3] if q >= 1 else R[4]
        return R[1]
    box(s, X, Y, 1, 1, 0, 9, b - 2, top_z - th, ped)
    # top slab
    box(s, X, Y, 0, 0, top_z - th, a, b, th, woodtex(R, 'u', seed=a))
    return fin(s)


def counter(a=14, b=30, h=26):
    """Coffee counter running along v, front (R face) has cabinet doors."""
    s = canvas(a, b, h)
    X, Y = s.ax, s.ay
    R = WOOD

    def body(face, p, q, A, B, e):
        if face == 'R':
            if q < 1:
                return R[4]
            if q < 2.5:
                return R[4]
            cell = 7.0
            k = p % cell
            if k < .6 or q >= B - 1:
                return R[4] if k < .6 else R[3]
            if k > cell - 1.2:
                return R[3]
            if q < 4:
                return R[3]
            if abs(k - (cell - 2.4)) < .6 and abs(q - (B - 6)) < 1.5:
                return STEEL[1]
            return R[2]
        if face == 'L':
            return R[2] if q >= 1 else R[4]
        return R[1]
    box(s, X, Y, 0, 0, 0, a, b, h - 3, body)
    box(s, X, Y, -1, -1, h - 3, a + 1, b + 1, 3, woodtex(R, 'v', seed=3))
    return fin(s)


# ------------------------------------------------------------------ CRT monitor
CODE_COLS = [C('#7cf27a'), C('#7cf27a'), C('#b8ff9a'), C('#5ad0e8'), C('#f2d45a')]


def crt(screen='green', back=False, seed=0):
    """CRT facing down-left (screen on L face). back=True: screen faces away (we see the rear)."""
    s = canvas(13, 13, 26)
    X, Y = s.ax, s.ay
    B = BEIGE
    # stand
    box(s, X, Y, 3, 4, 0, 7, 6, 2, mat(BEIGE2, 1, 2, 3))
    if back:
        box(s, X, Y, 0, 0, 2, 13, 6, 23, mat(B, 1, 2, 3))
        def rear(face, p, q, A, Bh, e):
            if face == 'L':
                if 2 < p < A - 2 and q > 4 and int(q) % 3 == 0 and 0.4 < (p % 1.5):
                    return B[4]
                return B[2] if q < Bh - 1 else B[1]
            if face == 'R':
                return B[3] if q < Bh - 1 else B[2]
            return B[1]
        box(s, X, Y, 2, 6, 5, 9, 4, 16, rear)
        # cable
        for i in range(6):
            s.put(X + 2 * 5 - 2 * 12 + i // 2, Y + 5 + 12 - 6 + i, C('#2a2a30'))
        return fin(s)
    # rear bulge (visible from above / right)
    box(s, X, Y, 2.5, 2, 6, 8, 4, 15, mat(B, 1, 2, 3))
    rows = []
    for i in range(12):
        ind = int(H('ind', seed, i) * 4) * .8
        ln = 2 + H('ln', seed, i) * 6
        if H('blank', seed, i) < .15:
            ln = 0
        col = CODE_COLS[int(H('c', seed, i) * len(CODE_COLS))]
        rows.append((ind, ln, col))
    blue = screen == 'blue'
    bg = C('#16301e') if not blue else C('#1c3c9a')
    bg2 = C('#0e2014') if not blue else C('#16307c')

    def body(face, p, q, A, Bh, e):
        if face == 'L':
            # bezel
            if q < 1:
                return B[4]
            if q >= Bh - 1:
                return B[0]
            if p < .6:
                return B[1]
            if p >= A - .6:
                return B[3]
            sx0, sx1, sz0, sz1 = 1.5, A - 1.5, 4.5, Bh - 2.0
            if sx0 <= p < sx1 and sz0 <= q < sz1:
                # screen
                if p < sx0 + .5 or q >= sz1 - 1:
                    return C('#08100a') if not blue else C('#0c1850')
                if p >= sx1 - .5 or q < sz0 + 1:
                    return mix(B[3], C('#000000'), .2)
                # glare top-left
                if (p - sx0) + (sz1 - q) * .5 < 2.0:
                    return mix(bg, C('#ffffff'), .25)
                r = int((sz1 - 1.5 - q) / 2)
                if (sz1 - 1.5 - q) % 2 < 1 and 0 <= r < len(rows):
                    ind, ln, col = rows[r]
                    if blue:
                        col = C('#e8f0ff') if r % 3 else C('#f2e45a')
                    x = p - sx0 - .8
                    if ind <= x < ind + ln and x < sx1 - sx0 - 1.2:
                        if H('gap', seed, r, int(x)) < .12:
                            return bg
                        return col
                return bg
            if sx0 - .8 <= p < sx1 + .8 and sz0 - 1 <= q < sz1 + 1:
                return B[3]
            # power LED + button under the screen
            if q < 3.5 and q >= 2:
                if A - 3.2 < p < A - 2.4:
                    return C('#5aff6a')
                if 1.5 < p < 4.5:
                    return B[3]
            return B[2]
        if face == 'R':
            if q >= Bh - 1:
                return B[1]
            if q < 1:
                return B[4]
            if p > A - 3 and int(q) % 3 == 0 and q > 6:
                return B[4]
            return B[3]
        if p >= A - .5 or q >= Bh - .5:
            return B[0]
        return B[1]
    box(s, X, Y, 0, 6, 2, 13, 6, 23, body)
    return fin(s)


def keyboard(a=11, b=4):
    s = canvas(a, b, 3)
    X, Y = s.ax, s.ay
    K = BEIGE

    def f(face, p, q, A, Bh, e):
        if face == 'T':
            if p < .6 or q < .6 or p >= A - .6 or q >= Bh - .6:
                return K[2]
            r = int((q - .6) / .85)
            if (q - .6) % .85 < .3:
                return K[3]
            if r < 3 and ((p + r * .5) % 1.4) < .45:
                return K[2]
            if r == 3 and not (3 < p < A - 3) and (p % 1.4) < .45:
                return K[2]
            return K[0] if (int((p + r * .5) / 1.4) + r) % 6 else C('#9aa6b0')
        return K[3] if face == 'L' else K[4]
    box(s, X, Y, 0, 0, 0, a, b, 2, f)
    return fin(s)


def mousepad(blue=True):
    s = canvas(6, 6, 3)
    X, Y = s.ax, s.ay
    P = ramp('#2c3a5a' if blue else '#3a2c3a', .3, .3)
    box(s, X, Y, 0, 0, 0, 4.5, 4.5, .6, mat(P, 2, 3, 4, rim=1))
    M = BEIGE
    cx, cy = X, Y + 3
    s.grid(int(cx) - 2, int(cy) - 2, [".00.", "0112", "1223", "1223", ".33."], {'0': M[0], '1': M[1], '2': M[2], '3': M[4]})
    s.put(int(cx) - 1, int(cy) - 1, M[3])
    s.line(int(cx) + 1, int(cy) - 3, int(cx) + 4, int(cy) - 5, C('#3a3a40'))
    return fin(s)


def tower():
    s = canvas(6, 12, 28)
    X, Y = s.ax, s.ay
    B = BEIGE

    def f(face, p, q, A, Bh, e):
        if face == 'L':   # front panel (narrow, facing down-left)
            if q < 1:
                return B[4]
            if q >= Bh - 1:
                return B[0]
            if p < .6:
                return B[1]
            if p >= A - .6:
                return B[3]
            for z0 in (Bh - 6, Bh - 10):
                if z0 <= q < z0 + 3 and 1 <= p < A - 1:
                    if q < z0 + 1:
                        return B[4]
                    if z0 + 1.5 <= q < z0 + 2 and 1.5 < p < A - 1.5:
                        return C('#2a2a30')
                    return B[1]
            if Bh - 14 <= q < Bh - 13 and 1.5 < p < A - 1.5:
                return C('#2a2a30')
            if 6 <= q < 8:
                if 1.5 < p < 2.5:
                    return C('#5aff6a')
                if 3 < p < 4.5:
                    return B[4]
            if q < 5 and int(q) % 2 == 0 and 1 < p < A - 1:
                return B[3]
            return B[2]
        if face == 'R':
            if q < 1:
                return B[4]
            if q >= Bh - 1:
                return B[2]
            return B[3]
        if p >= A - .5 or q >= Bh - .5:
            return B[0]
        return B[1]
    box(s, X, Y, 0, 0, 0, 5, 11, 26, f)
    return fin(s)


# ------------------------------------------------------------------ chair
CHAIR = ramp('#5e6878', .40, .32)
CHAIR_B = ramp('#4e8ad0', .40, .32)


def chair_base(R=CHAIR):
    s = Spr(36, 30, 18, 24)
    cx, cy = 18, 24
    M = ramp('#3a3c44', .35, .3)
    # star base
    for k in range(5):
        a = k * 2 * math.pi / 5 + .3
        ex, ey = cx + math.cos(a) * 10, cy + math.sin(a) * 5
        s.line(cx, cy - 1, int(ex), int(ey) - 1, M[2])
        s.line(cx, cy, int(ex), int(ey), M[3])
        s.rect(int(ex) - 1, int(ey), int(ex), int(ey) + 1, M[4])
        s.put(int(ex) - 1, int(ey), M[1])
    # column
    s.rect(cx - 1, cy - 14, cx + 1, cy - 1, M[2])
    s.vline(cx - 1, cy - 14, cy - 1, M[1])
    s.vline(cx + 1, cy - 14, cy - 1, M[3])
    # seat: thickness then top cushion
    iso_ellipse(s, cx, cy - 15, 10, R[3], 5)
    iso_ellipse(s, cx, cy - 17, 10, lambda dx, dy: R[0] if dx + dy < -1.0 else (R[1] if dx + dy < -.2 else (R[2] if dx + dy < .7 else R[3])), 5)
    outline(s)
    return s.crop(1)


def chair_back(R=CHAIR, facing='front'):
    """Backrest panel, lies in the u-z plane. facing='front': we see the cushion side
    (person faces away from us); 'rear': the shell back."""
    s = Spr(30, 34, 15, 30)
    X, Y = 15, 30
    M = ramp('#3a3c44', .35, .3)
    # support stem
    s.rect(X - 1, Y - 12, X + 1, Y - 2, M[3])
    s.vline(X - 1, Y - 12, Y - 2, M[2])

    def f(face, p, q, A, Bh, e):
        corner = (p < 1.5 or p > A - 1.5) and (q < 2 or q > Bh - 2)
        if corner:
            return None
        if face == 'L':
            if facing == 'front':
                if q < 1.5 or p < .8 or p > A - .8 or q > Bh - 1.5:
                    return R[3]
                if abs(p - A / 2) < .4:
                    return R[3]
                t = (p / A) * .6 + (1 - q / Bh) * .4
                return R[1] if t < .35 else (R[2] if t < .7 else R[3])
            t = (p / A) * .7 + (1 - q / Bh) * .3
            if q > Bh - 1.5:
                return R[1]
            return R[2] if t < .55 else R[3]
        if face == 'R':
            return R[4]
        return R[1]
    box(s, X, Y, -6, -1, 12, 12, 2, 16, f)
    outline(s)
    return s.crop(1)


# ------------------------------------------------------------------ plants
def leaf(s, x0, y0, ang, ln, wd, R, droop=0.0):
    """Pointed leaf from (x0,y0) at angle ang (rad, 0=right, -pi/2=up)."""
    pts_l, pts_r = [], []
    n = max(4, int(ln))
    for i in range(n + 1):
        t = i / n
        a = ang + droop * t * t
        cx = x0 + math.cos(ang) * ln * t + math.cos(a) * 0
        cy = y0 + math.sin(ang) * ln * t + droop * ln * t * t * .6
        w = wd * math.sin(math.pi * min(1, t * 1.15)) * (1 - t * .3)
        nx, ny = -math.sin(ang), math.cos(ang)
        pts_l.append((cx + nx * w, cy + ny * w, cx, cy))
        pts_r.append((cx - nx * w, cy - ny * w))
    poly1 = [(p[2], p[3]) for p in pts_l] + [(p[0], p[1]) for p in reversed(pts_l)]
    poly2 = [(p[2], p[3]) for p in pts_l] + [p for p in reversed(pts_r)]
    m1 = mask_poly(s.w, s.h, polys=[poly1])
    m2 = mask_poly(s.w, s.h, polys=[poly2])
    # which half faces up -> lighter
    up_first = math.cos(ang) < 0
    blob(s, m2 if up_first else m1, R, lx=.3, ly=.7, cuts=(.12, .5, .85, .97), edge=False)
    blob(s, m1 if up_first else m2, [R[1], R[2], R[3], R[3], R[4], R[5]], lx=.3, ly=.7, edge=False)
    for (_, _, cx, cy) in pts_l[1:-2]:
        s.put(int(cx), int(cy), R[3])


def pot(s, cx, cy, r, h, R=TERRA):
    # body
    for y in range(cy - h, cy + 1):
        k = (y - (cy - h)) / max(1, h)
        rr = r - k * 1.5
        for x in range(int(cx - rr), int(cx + rr) + 1):
            t = (x - (cx - rr)) / (2 * rr)
            s.put(x, y, R[1] if t < .25 else (R[2] if t < .65 else (R[3] if t < .9 else R[4])))
    iso_ellipse(s, cx, cy, r - 1.5, lambda dx, dy: R[3] if dy > 0 else None, (r - 1.5) / 2)
    # rim
    iso_ellipse(s, cx, cy - h, r + 1, R[0], (r + 1) / 2)
    iso_ellipse(s, cx, cy - h + 1, r - .5, C('#4a3020'), (r - .5) / 2)
    s.hline(int(cx - r), int(cx + r), cy - h + 3, R[3])


def plant(big=True, seed=1):
    if big:
        s = Spr(56, 64, 28, 58)
        cx, cy = 28, 58
        pot(s, cx, cy, 8, 11)
        base = (cx, cy - 13)
        leaves = [(-2.6, 22, 5, .4), (-.6, 22, 5, .5), (-1.9, 26, 5, .2), (-1.2, 27, 5, .25),
                  (-2.9, 16, 4, .6), (-.25, 17, 4, .6), (-1.55, 21, 4, .1), (-2.3, 18, 4, .3), (-.9, 19, 4, .3)]
        for i, (a, l, w, d) in enumerate(leaves):
            leaf(s, base[0], base[1], a, l, w, LEAF if i % 2 else LEAF2, droop=d)
    else:
        s = Spr(28, 30, 14, 26)
        cx, cy = 14, 26
        pot(s, cx, cy, 5, 7, ramp('#e8e4dc'))
        base = (cx, cy - 8)
        for i, (a, l, w, d) in enumerate([(-2.5, 9, 3, .3), (-.6, 9, 3, .3), (-1.6, 11, 3, 0), (-2.0, 8, 2.5, .1),
                                          (-1.1, 8, 2.5, .1)]):
            leaf(s, base[0], base[1], a, l, w, LEAF if i % 2 else LEAF2, droop=d)
    outline(s)
    return s.crop(1)


def cactus():
    s = Spr(18, 22, 9, 19)
    pot(s, 9, 19, 4, 5, ramp('#c86a4a'))
    G = ramp('#5aa848', .4, .32)
    blob(s, mask_poly(18, 22, ells=[(6, 4, 11, 15)]), G, lx=.7, ly=.3)
    blob(s, mask_poly(18, 22, ells=[(2, 7, 6, 12)]), G, lx=.7, ly=.3)
    blob(s, mask_poly(18, 22, ells=[(11, 6, 15, 11)]), G, lx=.7, ly=.3)
    for (x, y) in [(8, 6), (9, 10), (7, 12), (4, 9), (13, 8)]:
        s.put(x, y, G[0])
    s.put(9, 3, C('#f070a0')); s.put(8, 3, C('#f8a0c0'))
    outline(s)
    return s.crop(1)


# ------------------------------------------------------------------ lamp
def lamp(color='#3ab0b0'):
    R = ramp(color, .45, .32)
    M = ramp('#3a3c44', .35, .3)
    s = Spr(30, 34, 8, 30)
    iso_ellipse(s, 8, 29, 5, lambda dx, dy: M[1] if dy < -.2 else M[3], 2.5)
    iso_ellipse(s, 8, 28, 4, R[2], 2)
    s.line(8, 28, 7, 16, M[2]); s.line(9, 28, 8, 16, M[3])
    s.line(7, 16, 16, 9, M[2]); s.line(8, 16, 17, 10, M[3])
    s.rect(6, 15, 8, 17, R[3])
    # shade (cone pointing down-right)
    m = mask_poly(30, 34, polys=[[(14, 7), (19, 5), (25, 13), (22, 17), (14, 18)]])
    blob(s, m, R, lx=.6, ly=.4)
    s.put(17, 7, R[0]); s.put(16, 8, R[0]); s.put(18, 7, R[0])
    # bulb glow
    for (x, y) in [(17, 17), (18, 17), (19, 17), (20, 16), (21, 16)]:
        s.put(x, y, C('#fff4b0'))
    outline(s)
    return s.crop(1)


def lamp_glow(s, x, y, r=14):
    for yy in range(int(y - r / 2), int(y + r / 2) + 1):
        for xx in range(int(x - r), int(x + r) + 1):
            d = ((xx - x) / r) ** 2 + ((yy - y) / (r / 2)) ** 2
            if d < 1:
                s.put(xx, yy, (255, 236, 160, int(50 * (1 - d))))


# ------------------------------------------------------------------ coffee corner
def coffee_machine():
    s = canvas(11, 10, 30)
    X, Y = s.ax, s.ay
    S = STEEL
    D = ramp('#3a3e46', .35, .3)

    def f(face, p, q, A, Bh, e):
        if face == 'R':      # front faces down-right
            if q < 1:
                return D[4]
            if q >= Bh - 1:
                return S[0]
            if p < .6:
                return S[1]
            if p >= A - .6:
                return S[3]
            # recess for cup
            if 2 <= p < A - 2 and 3 <= q < 12:
                if q < 4:
                    return D[3]
                if abs(p - A / 2) < .7 and q > 9:
                    return S[2]
                return D[3] if q > 4 else D[4]
            # panel with display & buttons
            if 1.5 <= p < A - 1.5 and 14 <= q < 20:
                if 2.5 <= p < 5.5 and 16 <= q < 19:
                    return C('#5ad0e8')
                if 7 <= p < 8 and int(q) % 2 == 0:
                    return C('#e85a4a') if q < 17 else C('#5aff6a')
                return D[2]
            return S[2]
        if face == 'L':
            if q >= Bh - 1:
                return S[1]
            return S[3] if q > 1 else D[4]
        if p >= A - .5 or q >= Bh - .5:
            return S[0]
        return S[1]
    box(s, X, Y, 0, 0, 0, 9, 9, 22, f)
    # bean hopper on top
    def hop(face, p, q, A, Bh, e):
        if face == 'T':
            return D[1]
        if q < 1:
            return D[4]
        if q > Bh - 1.5:
            return D[2]
        g = (int(p * 2) + int(q)) % 3
        return [C('#6a3a1e'), C('#5a3018'), C('#7e4a26')][g]
    box(s, X, Y, 2, 2, 22, 5, 5, 7, hop)
    m = mug_spr(steam=True)
    s.place(m, X + 2 * 9 - 2 * 4.5 + 1, Y + 9 + 4.5 - 3)
    outline(s)
    return s.crop(1)


def mug_spr(steam=True, col=None):
    s = Spr(10, 14, 4, 12)
    people.mug(s, 1, 5, col, steam)
    return s


def grinder():
    s = canvas(5, 5, 20)
    X, Y = s.ax, s.ay
    D = ramp('#3a3e46', .35, .3)
    box(s, X, Y, 0, 0, 0, 5, 5, 9, mat(D, 1, 2, 3))
    def g(face, p, q, A, Bh, e):
        if face == 'T':
            return C('#a8d0e0')
        return [C('#6a3a1e'), C('#5a3018'), C('#80502a')][(int(p * 2) + int(q)) % 3] if q < Bh - 2 else C('#c8e4f0')
    box(s, X, Y, .5, .5, 9, 4, 4, 9, g)
    outline(s)
    return s.crop(1)


def stool():
    s = Spr(24, 30, 12, 27)
    R = WOOD
    M = ramp('#5a3a24', .4, .3)
    cx, cy = 12, 27
    for (dx, dy) in [(-6, -1), (6, -1), (-3, 1), (3, 1)]:
        s.line(cx + dx // 2, cy - 15, cx + dx, cy + dy, M[2 if dx < 0 else 3])
    # foot ring
    for k in range(40):
        a = k / 40 * 2 * math.pi
        x, y = cx + math.cos(a) * 5, cy - 7 + math.sin(a) * 2.5
        if math.sin(a) > -.2:
            s.put(int(x), int(y), M[1])
    iso_ellipse(s, cx, cy - 16, 8, R[3], 4)
    iso_ellipse(s, cx, cy - 18, 8, lambda dx, dy: R[0] if dx + dy < -1 else (R[1] if dx + dy < .3 else R[2]), 4)
    outline(s)
    return s.crop(1)


def bookshelf(a=18, b=6, h=46):
    s = canvas(a, b, h)
    X, Y = s.ax, s.ay
    R = WOOD2
    BOOKS = [C(c) for c in ('#c84a3a', '#3a6ab0', '#e0b040', '#4a9a5a', '#8a4ab0', '#e8e0d0', '#3a3a48', '#d87a3a')]

    def f(face, p, q, A, Bh, e):
        if face == 'L':
            if p < 1 or p >= A - 1 or q < 1.5 or q >= Bh - 1.5:
                return R[2] if p < 1 else R[3]
            shelf = int((q - 1.5) / 11)
            k = (q - 1.5) % 11
            if k < 1.2:
                return R[1]
            # books
            bi = int((p - 1) / 1.1)
            hgt = 6 + H('bh', shelf, bi) * 3.5
            if H('gap', shelf, bi) < .12:
                return R[4]
            if k - 1.2 < hgt:
                c = BOOKS[int(H('bc', shelf, bi) * len(BOOKS))]
                if (p - 1) % 1.1 < .35:
                    return mix(c, C('#1a1020'), .45)
                if abs((k - 1.2) - hgt + 2) < .5:
                    return mix(c, C('#fff0c0'), .45)
                return c
            return R[4]
        if face == 'R':
            return R[3] if q > 1 else R[4]
        return R[1] if not (p >= A - .5 or q >= Bh - .5) else R[0]
    box(s, X, Y, 0, 0, 0, a, b, h, f)
    return fin(s)


def pencup():
    s = Spr(10, 16, 5, 14)
    P = ramp('#4a6aa8', .4, .3)
    for (x, c) in [(3, C('#e84a4a')), (4, C('#f0d040')), (5, C('#3a8ae0')), (6, C('#40b060'))]:
        s.vline(x, 2 + (x % 2), 8, c)
    s.rect(2, 7, 7, 13, P[2]); s.vline(2, 7, 13, P[1]); s.vline(7, 7, 13, P[3]); s.hline(2, 7, 7, P[0])
    outline(s)
    return s.crop(1)


def sticky(col='#f8e060'):
    s = Spr(10, 8, 5, 4)
    R = ramp(col, .4, .3)
    # flat iso square on desk
    box(s, 5, 2, 0, 0, 0, 2.5, 2.5, .5, mat(R, 1, 2, 3, rim=0))
    s.put(5, 3, R[3]); s.put(4, 3, R[3])
    return s.crop(0)


def books_stack():
    s = canvas(7, 5, 8)
    X, Y = s.ax, s.ay
    z = 0
    for (c, hh, du) in [('#3a6ab0', 2, 0), ('#c84a3a', 2, .5), ('#e8e0d0', 1.5, -.3)]:
        R = ramp(c)
        box(s, X, Y, du, 0, z, 6, 4, hh, mat(R, 1, 2, 3, rim=0))
        z += hh
    return fin(s)


def cabinet(a=10, b=8, h=30):
    s = canvas(a, b, h)
    X, Y = s.ax, s.ay
    R = ramp('#8a96a2', .4, .3)

    def f(face, p, q, A, Bh, e):
        if face == 'L':
            if q < 1:
                return R[4]
            k = q % 9.5
            if k < .8:
                return R[4]
            if abs(p - A / 2) < 1.2 and abs(k - 6.5) < .6:
                return R[0]
            return R[2] if p > .6 else R[1]
        if face == 'R':
            return R[3]
        return R[1]
    box(s, X, Y, 0, 0, 0, a, b, h, f)
    return fin(s)


# ------------------------------------------------------------------ bubbles
BUB = C('#fbfaf4')


def bubble(kind):
    """Speech bubble with pixel icons. Anchor = tail tip."""
    w, h = {'code': (34, 20), 'warn': (20, 20), 'done': (22, 20), 'zzz': (24, 20), 'lambda': (30, 20),
            'idea': (22, 22), 'map': (26, 22)}[kind]
    s = Spr(w + 4, h + 8)
    x0, y0 = 2, 1
    # body with rounded corners
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            cx = min(x - x0, x0 + w - 1 - x)
            cy = min(y - y0, y0 + h - 1 - y)
            if cx + cy < 2:
                continue
            s.put(x, y, BUB if cy > 1 or y < y0 + h - 2 else C('#ddd8cc'))
    s.hline(x0 + 2, x0 + w - 3, y0 + h - 1, C('#d4cec0'))
    # tail
    tx = x0 + 5
    for i in range(5):
        s.hline(tx, tx + 4 - i, y0 + h + i, BUB)
    INKC = C('#26202c')
    pal = {'X': INKC}
    if kind == 'code':
        s.grid(x0 + 4, y0 + 5, [
            "...X....X...X.......X..",
            "..XX...XX...XX......X..",
            ".XX....X.....XX.....X..",
            "XX....XX......XX....X..",
            ".XX...X......XX........",
            "..XX.XX.....XX......X..",
            "...X.X......X.......X..",
        ], pal)
    elif kind == 'warn':
        R = C('#e83a3a')
        s.grid(x0 + 7, y0 + 3, ["XX", "XX", "XX", "XX", "XX", "XX", "XX", "..", "..", "XX", "XX"], {'X': R})
    elif kind == 'done':
        G = C('#2eaa4a'); G2 = C('#1e7a34')
        s.grid(x0 + 4, y0 + 4, [
            "...........XX",
            "..........XXG",
            ".........XXG.",
            "XX......XXG..",
            "GXX....XXG...",
            ".GXX..XXG....",
            "..GXXXXG.....",
            "...GXXG......",
            "....GG.......",
        ], {'X': G, 'G': G2})
    elif kind == 'zzz':
        B = C('#3a6ad8')
        s.grid(x0 + 3, y0 + 3, [
            "...........XXXXX",
            "..............X.",
            ".............X..",
            "......XXXX..X...",
            "........X..XXXXX",
            ".......X........",
            "XXX...XXXX......",
            "..X.............",
            ".X..............",
            "XXX.............",
        ], {'X': B})
    elif kind == 'lambda':
        s.grid(x0 + 4, y0 + 5, [
            "XX..............",
            "..X.........X...",
            "..X..........X..",
            "..XX....XXXXXXX.",
            ".X.X.........X..",
            ".X..X.......X...",
            "X...XX..........",
        ], pal)
    elif kind == 'idea':
        Y_ = C('#f8d030'); Y2 = C('#d8a010'); Wt = C('#fff8c0'); G = C('#8a8a90')
        s.grid(x0 + 5, y0 + 2, [
            "...YYYY...",
            "..YWWYYY..",
            ".YWYYYYYO.",
            ".YWYYYYYO.",
            ".YYYYYYOO.",
            "..YYYYOO..",
            "...YYOO...",
            "...GGGG...",
            "...gggg...",
            "...GGGG...",
            "....gg....",
        ], {'Y': Y_, 'W': Wt, 'O': Y2, 'G': G, 'g': C('#5a5a60')})
        for (x, y) in [(x0 + 2, y0 + 4), (x0 + 3, y0 + 2), (x0 + 16, y0 + 4), (x0 + 15, y0 + 2)]:
            s.put(x, y, Y_)
    elif kind == 'map':
        s.grid(x0 + 3, y0 + 3, [
            "gggGGggggggGGgggg.",
            "ggGGgggbbbgGGgggg.",
            "gGGggggbbggGgRRgg.",
            "gGgggggggggGgRRgg.",
            "GGggyyyyyyyGGgggg.",
            "Gggyggggggyggggg..",
            "ggygggbbggygggg...",
            "gygggbbbggyggg....",
            "gyggggggggggg.....",
        ], {'g': C('#8ac86a'), 'G': C('#5a9a4a'), 'b': C('#5ab0e0'), 'y': C('#e8d070'), 'R': C('#e84a3a')})
    outline(s, C('#1e1a24'), t=0)
    s.ax, s.ay = tx + 1, y0 + h + 5
    return s.crop(0)


# ------------------------------------------------------------------ CRT (oblique, reference-style)
def crt2(screen='green', back=False, seed=0):
    """CRT drawn with a near-frontal bezel (like the reference) and depth receding up-right.
    Anchor = bottom-centre of the stand."""
    B = BEIGE
    W_, H_ = 26, 22          # front face
    D = 9                    # depth (px to the right), rises D//2
    s = Spr(W_ + D + 8, H_ + D // 2 + 10)
    x0, y0 = 2, D // 2 + 2
    dd = D // 2

    def para(xa, ya, w, h, dx, dy, col_fn):
        # parallelogram: start (xa,ya) width w along x, sheared by (dx,dy) per h rows
        pass

    # rear bulge (behind, offset up-right)
    bx0, by0 = x0 + 5 + D - 2, y0 + 3 - dd + 1
    bw, bh = W_ - 10, H_ - 7
    # top face of main box
    for i in range(dd + 1):
        for x in range(x0 + 2 * i, x0 + W_ + 2 * i):
            s.put(x, y0 - i, B[0] if i == 0 or x < x0 + 2 * i + 1 else B[1])
    # bulge top & side
    for i in range(dd + 4):
        for x in range(bx0 + 2 * i - 4, bx0 + bw + 2 * i - 4):
            s.put(x, by0 - i + 2, B[1] if i < dd + 3 else B[0])
    # right side of main box
    for i in range(D):
        yy = y0 - i // 2
        for y in range(yy, yy + H_):
            c = B[3]
            if i > D - 3 and (y - yy) % 3 == 1 and 3 < y - yy < H_ - 4:
                c = B[4]
            if y == yy + H_ - 1:
                c = B[4]
            s.put(x0 + W_ + i, y, c)
    # bulge right side
    for i in range(6):
        yy = by0 - (i + D) // 2 + 2
        for y in range(yy, yy + bh - 2):
            s.put(x0 + W_ + D - 1 + i, y + 2, B[3] if i < 5 else B[4])
    if back:
        # rear panel: we see the back of the case (front face slot shows the rear)
        for y in range(y0, y0 + H_):
            for x in range(x0, x0 + W_):
                t = 2
                if y == y0:
                    t = 1
                if x == x0:
                    t = 1
                if x == x0 + W_ - 1 or y == y0 + H_ - 1:
                    t = 3
                s.put(x, y, B[t])
        # protruding bulge seen from behind
        for y in range(y0 + 3, y0 + H_ - 3):
            for x in range(x0 + 5, x0 + W_ - 5):
                t = 1 if y < y0 + 5 else 2
                if x >= x0 + W_ - 7:
                    t = 3
                if (y - y0) % 3 == 0 and x0 + 7 < x < x0 + W_ - 8 and y > y0 + 6:
                    t = 4
                s.put(x, y, B[t])
        s.hline(x0 + 5, x0 + W_ - 6, y0 + H_ - 3, B[4])
        # cable
        s.vline(x0 + W_ // 2, y0 + H_ - 2, y0 + H_ + 4, C('#2a2a30'))
    else:
        rows = []
        for i in range(8):
            ind = int(H('ind', seed, i) * 4) * 2 if i else 0
            if i and H('ind2', seed, i) < .3:
                ind = rows[-1][0] if rows else 0
            toks, x = [], ind
            n = 1 + int(H('nt', seed, i) * 3)
            for k in range(n):
                ln = 2 + int(H('tl', seed, i, k) * 5)
                toks.append((x, x + ln, k))
                x += ln + 1
            rows.append((ind, toks))
        blue = screen == 'blue'
        bg = C('#0f2a18') if not blue else C('#1f3fa8')
        sx0, sy0, sx1, sy1 = x0 + 3, y0 + 3, x0 + W_ - 4, y0 + H_ - 6
        for y in range(y0, y0 + H_):
            for x in range(x0, x0 + W_):
                t = 2
                if y == y0 or x == x0:
                    t = 0
                elif y == y0 + 1 or x == x0 + 1:
                    t = 1
                if x == x0 + W_ - 1 or y == y0 + H_ - 1:
                    t = 3
                s.put(x, y, B[t])
        # recessed screen frame
        s.rect(sx0 - 1, sy0 - 1, sx1 + 1, sy1 + 1, B[3])
        s.hline(sx0 - 1, sx1 + 1, sy1 + 1, B[1]); s.vline(sx1 + 1, sy0 - 1, sy1 + 1, B[1])
        for y in range(sy0, sy1 + 1):
            for x in range(sx0, sx1 + 1):
                cx = min(x - sx0, sx1 - x); cy = min(y - sy0, sy1 - y)
                if cx + cy == 0:
                    s.put(x, y, B[4]); continue
                c = bg
                if y == sy0 or x == sx0:
                    c = mix(bg, C('#000000'), .35)
                r, k = divmod(y - sy0 - 2, 2)
                if k == 0 and 0 <= r < len(rows):
                    ind, toks = rows[r]
                    xx = x - sx0 - 2
                    for (a, b, kk) in toks:
                        if a <= xx < b and x < sx1 - 1:
                            if blue:
                                c = C('#f0f4ff') if kk else C('#f8e070')
                            else:
                                c = [C('#8cff7a'), C('#c8ffb0'), C('#5ae0c8')][kk % 3] if (r + kk) % 4 else C('#f2e45a')
                # glare
                if (x - sx0) + (y - sy0) in (3, 4) and y - sy0 < 4:
                    c = mix(c, C('#ffffff'), .35)
                s.put(x, y, c)
        # blinking cursor
        r = len(rows)
        cy = sy0 + 2 + 2 * min(r, (sy1 - sy0 - 3) // 2)
        if cy < sy1:
            s.rect(sx0 + 2, cy - 1, sx0 + 3, cy, C('#c8ffb0') if not blue else C('#ffffff'))
        # chin: LED, buttons, badge
        cy0 = y0 + H_ - 4
        s.put(x0 + W_ - 5, cy0 + 1, C('#4aff5a')); s.put(x0 + W_ - 4, cy0 + 1, C('#2ab03a'))
        s.hline(x0 + 3, x0 + 6, cy0 + 1, B[3])
        s.hline(x0 + 10, x0 + 14, cy0 + 1, B[4])
    # stand
    s.rect(x0 + 8, y0 + H_, x0 + W_ - 8, y0 + H_ + 1, BEIGE2[3])
    s.rect(x0 + 5, y0 + H_ + 2, x0 + W_ - 5, y0 + H_ + 3, BEIGE2[2])
    s.hline(x0 + 5, x0 + W_ - 5, y0 + H_ + 2, BEIGE2[1])
    outline(s)
    s.ax, s.ay = x0 + W_ // 2, y0 + H_ + 3
    return s.crop(1)
