"""Compose the close-up office scene + previews."""
import math, sys
from PIL import Image, ImageDraw, ImageFont
from core import Spr, C, mix, ramp, H, box, outline, iso_ellipse
import props as Q
import people as P

NW, NH = 800, 450
OX, OY = 400, -64
WALLH = 92

PLANKS = [ramp('#b87842', .38, .30), ramp('#ac6c3a', .38, .30), ramp('#c08250', .36, .30), ramp('#a46638', .38, .30)]
BRICK = [ramp('#a0503a', .35, .30), ramp('#94462f', .35, .30), ramp('#aa5a40', .35, .30), ramp('#8a4430', .35, .30)]
MORTAR = C('#b8a088')


def plank_color(u, v, W=6.0):
    pi = math.floor(v / W)
    k = (v / W - pi) * W                      # 0..W across plank
    L = 22 + H('L', pi) * 26
    off = H('o', pi) * L
    seg = math.floor((u + off) / L)
    e = (u + off) - seg * L
    R = PLANKS[int(H('c', pi, seg) * len(PLANKS))]
    if k < .5:
        return R[4]
    if e < .5:
        return R[4]
    if k < 1.0 or e < 1.0:
        return R[1]
    if k > W - .5:
        return R[3]
    for j, (k0, amp, f) in enumerate(((1.9, .5, .07), (3.9, .6, .05))):
        kp = k0 + amp * math.sin(u * f + pi * 1.7 + j)
        if abs(k - kp) < .26 and H('g', pi, j, int((u + off) / 8)) < .6:
            return R[3]
    if abs(k - 3.0 - .4 * math.sin(u * .09 + pi)) < .26 and H('g2', pi, int(u / 5)) < .25:
        return R[1]
    return R[2]


def wall_color(p, q, wall, windows):
    for (w, a0, a1, z0, z1) in windows:
        if w == wall and a0 - 1 <= p < a1 + 1 and z0 - 2 <= q < z1 + 2:
            F = ramp('#e8e2d4', .4, .25)
            if p < a0 or p >= a1 or q < z0 or q >= z1:
                return F[1] if q >= z1 or p < a0 else F[3]
            mid = (a0 + a1) / 2
            if abs(p - mid) < .5 or abs(q - (z0 + z1) / 2) < 1:
                return F[2]
            # sky with a cloud and city silhouette
            t = (q - z0) / (z1 - z0)
            sky = mix(C('#8ac8f0'), C('#d8f0ff'), 1 - t)
            if q < z0 + 8 + 4 * math.sin(p * .9) * (H('b', int(p)) > .5):
                return C('#7a98b0') if H('wn', int(p * 2), int(q / 2)) > .25 else C('#f0e08a')
            if (p - mid - 3) ** 2 / 9 + (q - z1 + 7) ** 2 / 4 < 1:
                return C('#ffffff')
            return sky
    if q < 2:
        return C('#5a3424')
    if q < 4:
        return C('#7a4a30')
    bh = 5.0
    row = math.floor((q - 4) / bh)
    k = (q - 4) - row * bh
    bl = 6.0
    off = (row % 2) * bl / 2
    col = math.floor((p + off) / bl)
    e = (p + off) - col * bl
    if k < 1:
        return MORTAR
    if e < .5:
        return mix(MORTAR, C('#000000'), .1)
    R = BRICK[int(H('br', row, col, wall) * len(BRICK))]
    if k >= bh - 1:
        return R[1]
    if e > bl - .8:
        return R[3]
    if H('bn', int(p * 2), int(q), wall) > .93:
        return R[3]
    return R[2]


def background(windows):
    s = Spr(NW, NH)
    for y in range(NH):
        for x in range(NW):
            rx, ry = x + .5 - OX, y + .5 - OY
            u = (rx / 2 + ry) / 2
            v = (ry - rx / 2) / 2
            if u >= 0 and v >= 0:
                c = plank_color(u, v)
            else:
                c = None
                if rx < 0:     # left wall plane u=0
                    vv = -rx / 2
                    z = vv - ry
                    if 0 <= z < WALLH:
                        c = wall_color(vv, z, 'L', windows)
                        c = mix(c, C('#2a1a20'), .18)
                    elif z >= WALLH:
                        c = C('#e6dccb') if z < WALLH + 3 else C('#3a2a2a')
                else:
                    uu = rx / 2
                    z = uu - ry
                    if 0 <= z < WALLH:
                        c = wall_color(uu, z, 'R', windows)
                    elif z >= WALLH:
                        c = C('#f0e6d6') if z < WALLH + 3 else C('#3a2a2a')
                if c is None:
                    c = C('#3a2a2a')
            s.p[x, y] = c
    # corner seam + floor/wall shadow line
    for y in range(NH):
        for x in range(NW):
            rx, ry = x + .5 - OX, y + .5 - OY
            u = (rx / 2 + ry) / 2
            v = (ry - rx / 2) / 2
            if (0 <= u < 3 and v >= 0) or (0 <= v < 3 and u >= 0):
                d = min(u if u >= 0 else 9, v if v >= 0 else 9)
                s.put(x, y, (30, 16, 20, int(110 * (1 - d / 3))))
    return s


class Stage:
    def __init__(s, w, h, ox, oy):
        s.s = Spr(w, h)
        s.ox, s.oy = ox, oy
        s.items = []

    def scr(s, u, v, z=0):
        return s.ox + 2 * (u - v), s.oy + (u + v) - z

    def add(s, spr, u, v, z=0, key=None):
        s.items.append(((u + v) if key is None else key, len(s.items), spr, u, v, z))

    def shadow(s, u, v, r, a=70):
        x, y = s.scr(u, v)
        iso_ellipse(s.s, x, y, r, (24, 12, 20, a), r / 2)

    def render(s):
        for (_, _, spr, u, v, z) in sorted(s.items, key=lambda t: (t[0], t[1])):
            x, y = s.scr(u, v, z)
            s.s.place(spr, x, y)
        return s.s


# ------------------------------------------------------------------ pods
def pod(seats, n=2, deco=None):
    """Back-to-back desk pod along u. seats: list of (far, near) per desk, each None or
    (cfg_name, state) with state in work|sleep. Returns composite Spr anchored at pod origin."""
    A = 30
    U, V = n * A, 28
    st = Stage(2 * (U + 60) + 40, U + 60 + 110, 2 * 44 + 20, 100)
    deco = deco or {}
    lay = []    # (order, sprite, u, v, z)
    TOP = 26
    heads = {}
    for i, (far, near) in enumerate(seats):
        u0 = i * A
        # --- far seat (person faces +v / down-left, front view), at the right end of the desk
        fu = u0 + 20
        if far:
            cfg = P.CAST[far[0]]
            sl = far[1] == 'sleep'
            lay.append((0, Q.chair_back(Q.CHAIR, 'front'), fu + 2, -11, 0))
            lay.append((1, Q.chair_base(Q.CHAIR), fu + 2, -9, 0))
            body, fore = P.seated_front(cfg, sleep=sl)
            lay.append((2, body, fu, -5, 6))
            lay.append((6, fore, fu, -5, 6))
            heads[far[0]] = (2 * (fu + 5), fu - 5 - 6 - 56 + (14 if sl else 0))
        else:
            lay.append((0, Q.chair_back(Q.CHAIR, 'front'), fu + 2, -11, 0))
            lay.append((1, Q.chair_base(Q.CHAIR), fu + 2, -9, 0))
        lay.append((3, Q.desk(A, 14, TOP), u0, 0, 0))
        lay.append((5, Q.keyboard(12, 4), u0 + 15, 1.5, TOP))
        lay.append((7, Q.crt2(back=True, seed=i), u0 + 7, 6, TOP))
        for (k, spr, du, dv) in deco.get(('far', i), []):
            lay.append((4 + k, spr, u0 + du, dv, TOP))
        # --- near desk
        lay.append((8, Q.desk(A, 14, TOP), u0, 14, 0))
        lay.append((9, Q.crt2('blue' if i == 1 else 'green', seed=i * 7 + 3 + len(seats)), u0 + 8, 17, TOP))
        lay.append((10, Q.keyboard(12, 4), u0 + 4, 22, TOP))
        lay.append((10, Q.mousepad(i % 2 == 0), u0 + 17, 22, TOP))
        for (k, spr, du, dv) in deco.get(i, []):
            lay.append((9 + k, spr, u0 + du, dv, TOP))
        if near:
            cfg = P.CAST[near[0]]
            nu = u0 + 13
            sl = near[1] == 'sleep'
            lay.append((11, Q.chair_base(Q.CHAIR), nu + 1, 36, 0))
            lay.append((12, P.seated_back(cfg, sleep=sl), nu + 1, 33, 4))
            lay.append((13, Q.chair_back(Q.CHAIR, 'rear'), nu + 1, 39, 0))
            heads[near[0]] = (2 * (nu + 1 - 33), nu + 1 + 33 - 4 - 60 + (8 if sl else 0))
        else:
            nu = u0 + 13
            lay.append((11, Q.chair_base(Q.CHAIR), nu + 3, 38, 0))
            lay.append((13, Q.chair_back(Q.CHAIR, 'rear'), nu + 3, 41, 0))
    for (o, spr, u, v, z) in sorted(lay, key=lambda t: (t[0], t[2] + t[3])):
        x, y = st.scr(u, v, z)
        st.s.place(spr, x, y)
    st.s.ax, st.s.ay = st.ox, st.oy
    return st.s.crop(0), heads



def tower_pc():
    return Q.tower()


def compose():
    windows = [('R', 40, 72, 34, 74), ('R', 150, 182, 34, 74), ('L', 30, 62, 34, 74), ('L', 170, 202, 34, 74)]
    bg = background(windows)
    st = Stage(NW, NH, OX, OY)
    st.s = bg
    # ---- along the walls
    st.add(Q.bookshelf(20, 6, 46), 90, 1, 0)
    st.add(Q.cabinet(10, 8, 30), 112, 1, 0)
    st.add(Q.cabinet(10, 8, 30), 123, 1, 0)
    st.add(Q.plant(True), 140, 8, 0)
    st.add(Q.plant(True), 8, 8, 0)
    st.add(Q.bookshelf(20, 6, 46).flip(), 1, 70, 0, key=1 + 70 + 10)
    # ---- coffee corner along the left wall (u=0)
    st.add(Q.counter(14, 46, 26), 2, 100)
    st.add(Q.coffee_machine(), 4, 104, 26, key=2 + 104 + 14)
    st.add(Q.grinder(), 5, 120, 26, key=5 + 120 + 14)
    for i, dv in enumerate((128, 132, 136)):
        st.add(Q.mug_spr(steam=False), 9, dv, 26, key=9 + dv + 15)
    st.add(Q.plant(True), 6, 156, 0)
    for dv in (108, 122, 136):
        st.shadow(26, dv, 7)
        st.add(Q.stool(), 26, dv, 0)
    # ---- pods (placed by desired screen centre)
    def at(sx, sy, flipped=False):
        d = (sx - OX) / 2 + (15 if flipped else -15)
        t = sy - OY - 30
        return (t + d) / 2, (t - d) / 2
    D = lambda: [(2, Q.lamp('#3ab0b0'), 27, 15), (2, Q.plant(False), 23, 15), (3, Q.mug_spr(), 26, 23)]
    deco1 = {0: [(2, Q.lamp('#3ab0b0'), 27, 15), (2, Q.pencup(), 22, 16), (3, Q.mug_spr(), 26, 23), (3, Q.sticky(), 1, 25)],
             1: [(2, Q.plant(False), 24, 15), (2, Q.lamp('#a04ac8'), 28, 18), (3, Q.books_stack(), 23, 22)],
             ('far', 0): [(1, Q.mug_spr(False), 2, 3)], ('far', 1): [(1, Q.cactus(), 2, 3)]}
    p1, h1 = pod([(('mina', 'work'), ('tae', 'work')), (('jun', 'work'), None)], deco=deco1)
    u, v = at(430, 210); st.add(p1, u, v, key=u + v + 30); A1 = st.scr(u, v)
    deco2 = {0: [(2, Q.lamp('#a04ac8'), 27, 15), (3, Q.books_stack(), 22, 22), (2, Q.pencup(), 24, 15)],
             1: [(2, Q.cactus(), 26, 16), (3, Q.mug_spr(), 25, 23)], ('far', 0): [(1, Q.plant(False), 2, 3)]}
    p2, h2 = pod([(('ivy', 'work'), ('ken', 'sleep')), (('yuna', 'work'), None)], deco=deco2)
    p2 = p2.flip(); h2 = {k: (-x, y) for k, (x, y) in h2.items()}
    u, v = at(700, 200, True); st.add(p2, u, v, key=u + v + 30); A2 = st.scr(u, v)
    deco3 = {0: [(2, Q.lamp('#3ab0b0'), 27, 15), (3, Q.mug_spr(), 25, 23)],
             1: [(2, Q.pencup(), 26, 17), (2, Q.plant(False), 22, 15)], ('far', 1): [(1, Q.mug_spr(False), 2, 3)]}
    p3, h3 = pod([(('sora', 'work'), ('dev', 'work')), (('omar', 'work'), None)], deco=deco3)
    u, v = at(400, 395); st.add(p3, u, v, key=u + v + 30); A3 = st.scr(u, v)
    deco4 = {0: [(2, Q.lamp('#a04ac8'), 27, 15)], 1: [(2, Q.cactus(), 26, 17)]}
    p4 = pod([(None, None), (None, None)], deco=deco4)[0].flip()
    u, v = at(720, 440, True); st.add(p4, u, v, key=u + v + 30)
    deco5 = {0: [(2, Q.plant(False), 24, 15), (3, Q.mug_spr(), 26, 23)], 1: [(2, Q.lamp('#3ab0b0'), 27, 15)]}
    p5, h5 = pod([(('nora', 'work'), None), (None, None)], deco=deco5)
    u, v = at(120, 330); st.add(p5, u, v, key=u + v + 30); A5 = st.scr(u, v)
    for (tu, tv) in ((150, 200), (236, 262)):
        pass
    # ---- standing people
    walk = P.standing(P.CAST['leo'], 'walk')
    st.shadow(240, 140, 8); st.add(walk, 240, 140)
    wave = P.standing(P.CAST['sam'], 'wave')
    st.shadow(120, 182, 8); st.add(wave, 120, 182)
    mugp = P.standing(P.CAST['hana'], 'mug')
    st.shadow(44, 150, 8); st.add(mugp, 44, 150)
    st.add(Q.plant(True), 186, 196, 0)
    st.add(Q.plant(True), 330, 210, 0)
    deco6 = {0: [(2, Q.lamp('#3ab0b0'), 27, 15), (3, Q.mug_spr(), 25, 23)], 1: [(2, Q.plant(False), 24, 15)]}
    p6 = pod([(None, None), (None, None)], deco=deco6)[0].flip()
    u, v = at(590, 95, True); st.add(p6, u, v, key=u + v + 30)
    for (tu, tv) in ((148, 120), (166, 262)):
        st.add(Q.tower(), tu, tv, 0)
    st.add(Q.plant(True), 60, 228, 0)
    out = st.render()
    # ---- bubbles (always on top)
    def bub(kind, u, v, z):
        b = Q.bubble(kind)
        x, y = st.scr(u, v, z)
        out.place(b, x, y)
    def hb(kind, A, hd, name, dx=4, dy=-2):
        b = Q.bubble(kind)
        out.place(b, A[0] + hd[name][0] + dx, A[1] + hd[name][1] + dy)
    bub('warn', 120, 182, 80)
    bub('done', 44, 150, 78)
    hb('code', A1, h1, 'mina')
    hb('lambda', A1, h1, 'tae', -2)
    hb('zzz', A2, h2, 'ken', 2, 4)
    hb('code', A2, h2, 'yuna')
    hb('idea', A3, h3, 'sora')
    bub('map', 240, 140, 80)
    return out


if __name__ == '__main__':
    img = compose().img
    img.save('/tmp/phantom-ai-hires/_work/native.png')
    big = img.resize((NW * 2, NH * 2), Image.NEAREST)
    big.save('/tmp/phantom-ai-hires/scene.png')
    print('ok', big.size)
