"""Theme F - 공사장 (Construction Site): sprites at native resolution, packed into OUT/f/.

Same grid and footprints as theme A (16-unit tiles, 2:1 iso, desk 20x10, seat at z 7), so the layout does not change.
A desk is a sawhorse workbench (rw) or a brick wall going up (lw); a chair is an upturned bucket (rw) or a cinder block (lw);
people are a crew in hard hats and hi-vis vests, drawn at twice the density in people_hd.py's style (see the people section),
with a real animation per state:
  working  seated, seen from behind, swinging a hammer (sparks) or setting a brick with a trowel (mortar dust), 4 phases
  waiting  standing, an open hand up, waving a red flag, calling, 4 phases
  done     standing, thumbs up, a clipboard of green ticks at the chest, a sparkle, 4 phases
  idle     sitting turned round on the seat, arms crossed, hard hat tipped over the face, boots on the floor (1 frame)
Run from ai-visualization/art:  PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" theme_f.py [mockup]
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFont
from pix import C, dk, lt, mix, E, H, Spr, part, outline, outline_out, box, obox, M, INK
import sprites as SP
import people_hd as PH
from people_hd import Fig, ramp, looks, head_front, head_back, hair_back_layer, hand, cuff, legs_front, flip, LIFT, SIT, WHITE, MOUTH

OUT = SP.OUT
SEG, W_H = SP.SEG, SP.W_H

F = dict(
    dirt=[C('#a3825c'), C('#9e7e58'), C('#a88760'), C('#9a7a55')],
    gravel=[C('#8e8984'), C('#d2ccc0'), C('#6c6763'), C('#a9a29a')],
    track=C('#94714a'),
    plank=[C('#d9ab68'), C('#cc9d5b'), C('#e0b878')], plank_seam=C('#6f4a2a'),
    base_side=C('#6b4a2e'),
    conc=[C('#b5b1a8'), C('#aeaaa1'), C('#bbb7ae')], conc_seam=C('#98948b'), tie=C('#5d5954'),
    wall_cap=C('#cfcbc1'), rebar=C('#9a4f2a'),
    sky=[C('#a9dbf5'), C('#8ccbee'), C('#6db4e4')], cloud=C('#f4fbff'),
    mesh=C('#5f6973'), mesh_hi=C('#a9b3bc'), pipe=C('#7b858f'),
    crane=C('#e2a92c'), city=C('#9fb4c6'),
    wood=C('#c8955a'), wood_lt=C('#ddb17a'), wood_dk=C('#8e6136'),
    brick=[C('#b5523b'), C('#a84a35'), C('#c05f45'), C('#9d4430')], mortar=C('#cfc6b6'),
    steel=C('#9aa4ae'), steel_dk=C('#5c646d'),
    hazard=C('#ffc928'), black=C('#24211f'),
    orange=C('#f2711c'), green=C('#2f9e4f'),
)

def HM(*a):
    """H with its bits mixed: neighbouring inputs give unrelated values (H alone streaks along one screen axis)."""
    x = int(H(*a) * 4294967296.0)
    for _ in range(2):
        x ^= x >> 16
        x = (x * 0x45d9f3b) & 0xffffffff
    x ^= x >> 16
    return x / 4294967296.0


def thick(put, x0, y0, x1, y1, c, w=2):
    n = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
    for i in range(n + 1):
        t = i / n
        x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
        for dx in range(w):
            for dy in range(w):
                put(int(math.floor(x)) + dx, int(math.floor(y)) + dy, c)


STATES = ('working', 'waiting', 'done', 'idle')
PHASES = 4

# ---------------------------------------------------------------- floor: packed dirt and gravel, tyre tracks, a plank walkway


def floor_tile(var):
    s = Spr(70, 40, 33, 2)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if var == 3:  # two boards laid along u
            for k, (d0, d1) in enumerate(((3.5, 7.5), (8.0, 12.0))):
                if d0 <= d < d1:
                    if d < d0 + 0.5 or d >= d1 - 0.5:
                        return F['plank_seam'] if d < d0 + 0.5 else dk(F['plank'][k], .18)
                    tone = F['plank'][int(H('pk', k, int(a // 8)) * 3)]
                    if a % 8 < 0.5:
                        return F['plank_seam']
                    if abs(a % 8 - 1.5) < 0.5 and abs(d - (d0 + d1) / 2) < 1.2:
                        return C('#55504c')  # nail heads
                    g = H('pg', k, int(a * 2), int(d * 2))
                    if g < 0.08:
                        return dk(tone, .12)
                    if d < d0 + 1.0:
                        return lt(tone, .15)
                    return tone
            if d1_shadow(d):
                return dk(F['dirt'][0], .2)
        if var == 1:  # faint tyre tracks along v
            for t0 in (4.0, 11.0):
                if t0 <= a < t0 + 2.0 and int(d * 2) % 3 == 0:
                    return dk(vnoise_dirt(var, a, d), .14)
        if var == 2 and (a - 9) ** 2 + ((d - 6) * 1.2) ** 2 < 5:  # a stone
            return lt(F['gravel'][3], .2) if d < 5.5 and a < 9 else F['gravel'][3]
        px, py = int(math.floor(2 * a - 2 * d)), int(math.floor(a + d))  # screen pixel: speckles stay one pixel
        n = HM('dn', var, px, py)
        c = vnoise_dirt(var, a, d)
        if n < 0.02:
            return F['gravel'][1]
        if n < 0.045 or (n < 0.06 and HM('dn', var, px - 1, py) < 0.02):
            return F['gravel'][2]
        if n < 0.14:
            return dk(c, .08)
        if n > 0.93:
            return lt(c, .07)
        return c
    box(s, 0, 0, 0, SEG, SEG, 0, tex)
    return s.crop()


def vnoise_dirt(var, a, d):
    """Smooth value noise over the tile (wrapping at its edges so neighbours join) picking one of the dirt tones."""
    g = 4.0
    i, j = int(a // g), int(d // g)
    fx, fy = (a % g) / g, (d % g) / g
    n = 4

    def v(x, y):
        return H('vn', x % n, y % n)
    top = v(i, j) * (1 - fx) + v(i + 1, j) * fx
    bot = v(i, j + 1) * (1 - fx) + v(i + 1, j + 1) * fx
    t = top * (1 - fy) + bot * fy
    return F['dirt'][0] if t < 0.42 else F['dirt'][1] if t < 0.62 else F['dirt'][2] if t < 0.8 else F['dirt'][3]


def d1_shadow(d):
    return 12.0 <= d < 12.5


def floor_base(n):
    return SP.floor_base(dict(base_side=F['base_side']), n)


# ---------------------------------------------------------------- walls: poured concrete with rebar; chain-link fence segments


def sky_px(x, y, w, hh, seed):
    """Sky behind the fence at (x along, y up) of a w x hh opening: clouds, a tower crane or a skyline of buildings going up."""
    sky = F['sky']
    t = y / hh
    col = sky[2] if t > 0.66 else sky[1] if t > 0.33 else sky[0]
    cy = hh * 0.72 + (seed % 3) * 2
    cx = w * (0.3 + 0.25 * (seed % 2))
    if ((x - cx) * 1.6) ** 2 + ((y - cy) * 1.4) ** 2 < 7 or ((x - cx - 2) * 1.6) ** 2 + ((y - cy - 1.5) * 1.4) ** 2 < 5:
        col = F['cloud']
    if seed % 2 == 0:  # tower crane: lattice mast, jib, cab, hook
        mx = w * 0.35
        if mx <= x < mx + 1.5 and y < hh * 0.8:
            return F['crane'] if (int(y) + int(x * 2)) % 3 else dk(F['crane'], .35)
        if hh * 0.8 <= y < hh * 0.8 + 2 and 0.5 <= x < w - 0.5:
            return F['crane'] if (int(x * 2) + int(y)) % 3 else dk(F['crane'], .35)
        if mx - 2.5 <= x < mx and hh * 0.8 - 3 <= y < hh * 0.8:
            return C('#5d6b78')  # counterweight
        if mx + 1.5 <= x < mx + 3 and hh * 0.8 - 2 <= y < hh * 0.8:
            return C('#3f4b57')  # cab
        hx = w * 0.8
        if abs(x - hx) < 0.25 and hh * 0.45 <= y < hh * 0.8:
            return C('#3a3f47')
        if hx - 1 <= x < hx + 1 and hh * 0.42 <= y < hh * 0.45 + 0.5:
            return C('#e5484d')
    else:  # buildings under construction
        bh = [10, 18, 14, 22, 8][int(x // 3) % 5] + (seed % 3) * 2
        if y < bh:
            c = F['city']
            if int(y) % 4 == 2 and int(x * 2) % 3 != 0:
                c = dk(c, .18)
            if y >= bh - 2 and int(x * 2) % 2 == 0:
                c = dk(c, .3)  # open floor slab
            return c
    return col


def wall_tex(seed, fence):
    cap = F['wall_cap']

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if sf == 'T':
            if fence:
                return F['pipe'] if d < D_ - 1.0 else lt(F['pipe'], .2)
            return cap if d < D_ - 0.5 else dk(cap, .08)
        inner = (sf == 'L' and d >= D_ - 0.01) or (sf == 'R' and a >= A_ - 0.01)
        s_ = a if sf == 'L' else d
        dim = 0.0 if sf == 'L' else 0.12
        if not inner:
            if fence:
                return dk(F['pipe'], .2)
            return dk(cap, .3) if z < Hh - 1 else cap
        if fence:
            return dk(fence_px(s_, z, Hh, seed), dim)
        return dk(concrete_px(s_, z, Hh, seed), dim)
    return tex


def concrete_px(s_, z, Hh, seed):
    if z < 3:
        return mix(F['conc'][1], F['dirt'][1], .55) if z >= 1 else dk(F['dirt'][1], .2)
    if z >= Hh - 2:
        return lt(F['wall_cap'], .1) if z >= Hh - 1 else F['wall_cap']
    pz = (z - 3) % 12
    if s_ % 8 < 0.5 or pz < 0.8:
        return F['conc_seam']  # formwork panel joints
    if (abs(s_ % 8 - 2) < 0.5 or abs(s_ % 8 - 6) < 0.5) and (abs(pz - 3.5) < 0.5 or abs(pz - 8.5) < 0.5):
        return F['tie']  # form tie holes
    c = F['conc'][int(H('cn', seed, int(s_ // 8), int((z - 3) // 12)) * 3)]
    n = H('cp', seed, int(s_ * 2), int(z))
    if n < 0.05:
        return dk(c, .12)
    if n > 0.97:
        return lt(c, .15)
    if pz >= 11:
        return lt(c, .1)
    if H('st', seed, int(s_ * 2)) < 0.12 and z < 20:
        return dk(c, .07)  # rain streaks
    return c


def fence_px(s_, z, Hh, seed):
    if z < 6:
        return concrete_px(s_ + 3, z + (Hh - 10 if z >= 4 else 0), Hh, seed) if z < 4 else F['conc'][0]
    if s_ < 0.9 or s_ >= 15.1:  # posts
        return lt(F['pipe'], .25) if (s_ < 0.4 or 15.1 <= s_ < 15.5) else F['pipe']
    if z >= Hh - 2 or 30 <= z < 31:  # top and middle rail
        return lt(F['pipe'], .2) if z >= Hh - 1 or z < 30.5 else F['pipe']
    x = s_ * 2
    if (x + z) % 8 < 1.0 or (x - z) % 8 < 1.0:  # chain-link diamonds
        return F['mesh_hi'] if (x + z) % 8 < 0.5 else F['mesh']
    return sky_px(s_ - 0.9, z - 6, 14.2, Hh - 8, seed)


def wall_seg(side, seed, fence=False, end=False):
    s = Spr(110, 130, 55, 90)
    tex = wall_tex(seed, fence)
    if side == 'rw':
        box(s, 0, -3, 0, SEG, 3, W_H, tex, 'TL' + ('R' if end else ''))
    else:
        box(s, -3, 0, 0, 3, SEG, W_H, tex, 'TR' + ('L' if end else ''))
    if not fence:  # rebar left standing out of the pour
        def rebar(t):
            for k, a0 in enumerate((2.0, 6.0, 10.0, 14.0)):
                h = 4 + int(H('rb', seed, k) * 4)
                obox(t, side, a0, -2.0, W_H, 0.5, 0.5, h, M(F['rebar'], F['rebar'], dk(F['rebar'], .3)), 'TFS')
        part(s, rebar, 0)
    else:  # barbed wire on the fence top
        for k in range(8):
            a0 = k * 2 + 0.5
            u, v = (a0, -1.5) if side == 'rw' else (-1.5, a0)
            x, y = s.ax + 2 * u - 2 * v, s.ay + u + v - W_H - 2
            s.put(int(x), int(y), C('#4a5058'))
            s.put(int(x) + 1, int(y) - 1, C('#8d96a0'))
    return s.crop()


def wall_corner():
    return SP.wall_corner(dict(wall_cap=F['wall_cap']))


# ---------------------------------------------------------------- desks: sawhorse workbench (rw), brick wall going up (lw)


def wood_tex(base, along_grain=True, seed=0):
    def tex(role, a, d, z, A_, D_, Hh, sf):
        if sf == 'T':
            if a >= A_ - 0.5 or d >= D_ - 0.5:
                return lt(base, .2)
            if H('wg', seed, int(a * 3 if not along_grain else a // 3), int(d * 2)) < 0.12:
                return dk(base, .1)
            return base
        c = dk(base, .16) if sf == 'L' else dk(base, .32)
        if z >= Hh - 0.6:
            return lt(c, .12)
        return c
    return tex


def workbench(o='rw'):
    s = Spr(140, 110, 70, 50)
    A_, D_ = 20, 10
    leg = M(F['wood_dk'])

    def horses(t):
        for a0 in (2, 15):
            for d0 in (0.5, D_ - 1.5):
                obox(t, o, a0, d0, 0, 1, 1, 9, leg)
                obox(t, o, a0 + 2, d0, 0, 1, 1, 9, leg)
            obox(t, o, a0, 0.3, 9, 3, D_ - 0.6, 1.2, M(F['wood']))  # top beam
            obox(t, o, a0 + 1, 1.5, 4, 1, D_ - 3, 1, leg)  # spreader
    part(s, horses, .4)

    def top(t):
        for k in range(3):  # three planks along the bench
            obox(t, o, 0, k * 3.3 + 0.2, 10.2, A_, 3.1, 1.8, wood_tex(F['plank'][k], True, k))
    part(s, top, .45)

    def items(t):
        # the board being nailed: lighter stock across two planks, nails standing in it
        obox(t, o, 6, 2.5, 12, 8, 4, 1, wood_tex(F['wood_lt'], True, 7))
        obox(t, o, 15, 1, 12, 3, 2, 1.5, M(C('#c9ccd1')))  # box of nails
        obox(t, o, 2, 7.5, 12, 9, 1, 0.8, M(F['hazard']))  # spirit level
        obox(t, o, 16, 6.5, 12, 2, 2, 1.5, M(C('#f2c230')))  # tape measure
    part(s, items, .35)
    # nail heads and the level's bubble
    for (a, d) in ((7.5, 3.5), (9.5, 4.0), (11.5, 3.5), (13.0, 4.5)):
        x, y = SP._proj_local(s, o, a, d, 13)
        s.put(int(x), int(y), C('#e8eaee'))
        s.put(int(x), int(y) - 1, C('#7a7f87'))
    x, y = SP._proj_local(s, o, 6.5, 7.8, 12.8)
    s.put(int(x), int(y), C('#7fd36b'))
    s.put(int(x) + 1, int(y), C('#7fd36b'))
    return s.crop()


def brick_tex(seed, rows_top=None):
    def tex(role, a, d, z, A_, D_, Hh, sf):
        if sf == 'T':
            if d < 0.3 or d >= D_ - 0.3 or a % 4 < 0.5 or abs(d - D_ / 2) < 0.25:
                return F['mortar']
            return lt(F['brick'][int(H('bt', seed, int(a // 4), int(d * 2 // D_)) * 4)], .1)
        s_ = a if sf == 'L' else d
        dim = 0.0 if sf == 'L' else 0.16
        row = int(z // 3)
        if z % 3 < 0.7:
            return dk(F['mortar'], dim)
        off = 2 if row % 2 else 0
        if (s_ + off) % 4 < 0.5:
            return dk(F['mortar'], dim)
        c = F['brick'][int(H('br', seed, row, int((s_ + off) // 4)) * 4)]
        if z % 3 >= 2.4:
            c = lt(c, .12)
        return dk(c, dim)
    return tex


def brick_wall(o='lw'):
    s = Spr(140, 120, 70, 60)
    A_, D_ = 20, 10

    def footing(t):
        obox(t, o, 0, 1, 0, A_, 4.5, 1, M(C('#a39e94')))
    part(s, footing, .4)

    def wall(t):  # courses racked back at the far end: the wall is going up
        steps = [(0, 12, 9), (12, 15, 6), (15, 18, 3)]
        for a0, a1, h in steps:
            obox(t, o, a0, 1.5, 1, a1 - a0, 3.5, h, brick_tex(3))
    part(s, wall, .45)

    def tools(t):
        obox(t, o, 13, 6, 0, 5, 3.5, 3, M(C('#4b4f56')))  # mortar tub
        obox(t, o, 13.4, 6.4, 3, 4.2, 2.7, 0.01, lambda *a: C('#bdb5a6'), 'T')
        obox(t, o, 1, 6, 0, 5, 3, 2, brick_tex(9))  # bricks waiting
        obox(t, o, 1.5, 6.3, 2, 4, 2.4, 2, brick_tex(11))
        for a0 in (0, 19.2):  # profile posts carrying the line for the next course
            obox(t, o, a0, 1.0, 0, 0.8, 0.8, 14, M(F['wood']))
        obox(t, o, 0.4, 1.2, 12.5, 19.2, 0.3, 0.3, M(C('#e5484d')))
    part(s, tools, .35)
    # a dab of fresh mortar on top of the working course
    x, y = SP._proj_local(s, o, 13.5, 3.2, 7)
    for dx in range(-2, 3):
        s.put(int(x) + dx, int(y) - 1, C('#d8d0c0'))
    return s.crop()


def bucket():
    """An upturned paint bucket: wide at the floor, the seat at z 7."""
    s = Spr(20, 20, 10, 16)
    body, body_dk, body_lt = F['orange'], dk(F['orange'], .3), lt(F['orange'], .25)
    for y in range(-8, 1):
        hw = 4.5 + (y + 8) * 0.12
        for x in range(-6, 7):
            xx = x + 0.5
            if abs(xx) > hw:
                continue
            c = body_lt if xx < -hw + 1.6 else body if xx < hw * 0.35 else body_dk
            if -5 <= y <= -4:
                c = C('#f4f1ea') if xx < hw * 0.35 else C('#cfcac0')  # label band
            if y == 0:
                c = dk(body, .45)
            s.put(10 + x, 16 + y, c)
    for y in range(-11, -6):  # top ellipse (the bottom of the bucket)
        for x in range(-6, 7):
            nx, ny = (x + 0.5) / 4.6, (y + 9 + 0.5) / 2.2
            if nx * nx + ny * ny <= 1:
                s.put(10 + x, 16 + y, lt(body, .1) if nx * nx + ny * ny > 0.55 else body)
    outline(s, None, .5)
    return s.crop()


def cinder_block(o='lw'):
    s = Spr(40, 40, 20, 26)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        base = C('#9c9a95')
        if sf == 'T':
            for c0 in (0.8, 3.6):
                if c0 <= a < c0 + 2.2 and 0.8 <= d < D_ - 0.8:
                    return C('#4d4b48')
            return lt(base, .15)
        c = dk(base, .12) if sf == 'L' else dk(base, .3)
        return dk(c, .08) if H('cb', int(a * 2), int(d * 2), int(z)) < 0.15 else c
    part(s, lambda t: obox(t, o, -3.3, -2.2, 0, 6.6, 4.4, 7, tex), .45)
    return s.crop()


# ---------------------------------------------------------------- rugs: plywood deck, blue tarp, OSB


def platform(style, A_=24, D_=16):
    s = Spr(4 * (A_ + D_) + 10, 2 * (A_ + D_) + 10, 2 * D_ + 5, 5)

    def tex(role, a, d, z, AA, DD, Hh, sf):
        e = min(a, d, AA - a, DD - d)
        if style == 0:  # plywood sheet
            base = C('#d8b07a')
            if sf != 'T':
                return dk(base, .3)
            if e < 0.5:
                return dk(base, .35)
            if e < 1.0:
                return lt(base, .12)
            if abs(e - 1.5) < 0.3 and int(a + d) % 3 == 0:
                return C('#6f665e')  # nails
            g = H('ply', int(a // 5), int(d * 2))
            if (a * 0.5 + d * 2 + g * 3) % 4 < 0.5:
                return dk(base, .1)
            return base if g < 0.7 else lt(base, .06)
        if style == 1:  # blue tarp
            base = C('#2f7fd3')
            if sf != 'T':
                return dk(base, .3)
            if e < 0.5:
                return dk(base, .35)
            if e < 1.6 and (a < 2 or a > AA - 2) and (d < 2 or d > DD - 2):
                return C('#c9ced6')  # grommets at the corners
            if e < 1.2:
                return lt(base, .15)
            w = (a * 0.7 - d * 1.3 + 3 * math.sin(a * 0.35)) % 9
            if w < 0.6:
                return lt(base, .2)
            if w < 1.3:
                return dk(base, .12)
            return base
        base = C('#c79b5e')  # OSB: pressed chips
        if sf != 'T':
            return dk(base, .3)
        if e < 0.5:
            return dk(base, .35)
        if e < 1.0:
            return lt(base, .1)
        h = H('osb', int(a * 1.5), int(d * 2))
        return [base, dk(base, .12), lt(base, .12), C('#b5864a'), dk(base, .2)][int(h * 5)]
    box(s, 0, 0, 0, A_, D_, 1, tex)
    return s.crop()


# ---------------------------------------------------------------- name plate: a yellow-black striped site sign


def site_sign(w=40):
    h = 10
    s = Spr(w + 4, h + 8, (w + 4) // 2, h + 7)
    for x in (w // 2 - 6, w // 2 + 6):
        s.rect(x + 1, h, x + 2, h + 6, C('#5b636c'))
        s.rect(x + 1, h, x + 1, h + 6, C('#7d868f'))
        s.rect(x - 1, h + 6, x + 4, h + 6, C('#3e444b'))
    s.rect(1, 0, w + 2, h, F['black'])
    s.rect(2, 1, w + 1, h - 1, F['hazard'])
    s.rect(2, 1, w + 1, 1, lt(F['hazard'], .45))
    s.rect(2, h - 1, w + 1, h - 1, dk(F['hazard'], .15))
    for cap0 in (2, w - 8):  # hazard stripes on the two caps (inside the 12px insets), the middle stays plain for the label
        for y in range(1, h):
            for x in range(cap0, cap0 + 8):
                if (x + y) % 6 < 3:
                    s.put(x, y, F['black'])
        s.rect(cap0 + 8 if cap0 == 2 else cap0 - 1, 1, cap0 + 8 if cap0 == 2 else cap0 - 1, h - 1, F['black'])
    outline_out(s, INK)
    return s


# ---------------------------------------------------------------- wall decor


def site_map(o='rw'):
    s = Spr(70, 70, 35, 50)
    bp, ln = C('#2f63b0'), C('#dbe8fb')

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return M(C('#3b434c'))(role, a, d, z, A_, D_, Hh, sf)
        if a < 0.5 or a >= A_ - 0.5 or z < 1 or z >= Hh - 1:
            return C('#3b434c') if z >= 1 else C('#262b31')
        if z >= Hh - 4:  # title strip
            if int(z) == Hh - 3 and 2 <= a < 11 and int(a * 2) % 3 != 2:
                return C('#2a2a2a')
            return C('#f4f1ea') if a < A_ - 4 else F['hazard']
        x, y = a - 0.5, z - 1
        if (abs(x - 2) < 0.25 or abs(x - 15) < 0.25) and 2 <= y < 13 or (abs(y - 2) < 0.5 or abs(y - 13) < 0.5) and 2 <= x < 15:
            return ln  # site boundary
        if 4 <= x < 9 and 5 <= y < 11:
            return C('#f2c230') if (x < 4.5 or x >= 8.5 or y < 5.5 or y >= 10.5) else C('#4c80c9')  # building footprint
        if 10.5 <= x < 13.5 and 4 <= y < 7:
            return C('#e5484d') if (x - 12) ** 2 + (y - 5.5) ** 2 * 1.5 < 1.6 else C('#4c80c9')  # you are here
        if int(x * 2) % 4 == 0 and int(y) % 3 == 0:
            return lt(bp, .15)
        return bp
    part(s, lambda t: obox(t, o, 0, 0, 22, 18, 1, 20, tex), .4)
    return s.crop()


def safety_sign(o='rw'):
    """The green-cross safety board (안전제일)."""
    s = Spr(30, 30, 15, 20)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return C('#2c6e3c')
        if a < 0.5 or a >= A_ - 0.5 or z < 1 or z >= Hh - 1:
            return F['green']
        cx, cz = A_ / 2, Hh / 2
        if (abs(a - cx) < 0.6 and abs(z - cz) < 3.6) or (abs(z - cz) < 1.0 and abs(a - cx) < 2.1):
            return F['green']
        return C('#fbf8f1')
    obox(s, o, 0, 0, 0, 7, 0.6, 11, tex, 'TF')
    return s.crop()


def danger_sign(o='rw'):
    s = Spr(40, 40, 20, 30)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return C('#2f2a2a')
        if a < 0.5 or a >= A_ - 0.5 or z < 1 or z >= Hh - 1:
            return C('#2f2a2a')
        x, y = a - A_ / 2, z - 2
        if y >= 0 and abs(x) * 2.2 <= 9 - y:  # warning triangle
            if abs(x) * 2.2 > 9 - y - 1.6 or y < 1:
                return F['black']
            if abs(x) < 0.5 and (2 <= y < 3 or 4 <= y < 7.5):
                return F['black']
            return F['hazard']
        return C('#fbf8f1')
    obox(s, o, 0, 0, 0, 7, 0.6, 13, tex, 'TF')
    return s.crop()


# ---------------------------------------------------------------- site furniture


def brick_pallet(o='lw'):
    s = Spr(80, 80, 40, 60)

    def pallet(t):
        def ptex(role, a, d, z, A_, D_, Hh, sf):
            if sf == 'T':
                return F['wood'] if int(d * 2) % 3 else F['wood_dk']
            if 0.6 <= z < 1.4 and int(a) % 5 not in (0, 4):
                return C('#3a2c22')
            return dk(F['wood'], .2 if sf == 'L' else .35)
        obox(t, o, 0, 0, 0, 14, 6, 2, ptex)
    part(s, pallet, .4)
    part(s, lambda t: (obox(t, o, 0.5, 0.5, 2, 6.5, 5, 9, brick_tex(21)),
                       obox(t, o, 7.2, 0.5, 2, 6.3, 5, 6, brick_tex(22))), .45)

    def straps(t):
        for a0 in (3.2, 10):
            h = 9 if a0 < 7 else 6
            obox(t, o, a0, 0.4, 2, 0.5, 5.2, h + 0.2, M(C('#2f5fb0')), 'F')
    part(s, straps, 0)
    return s.crop()


def scaffold(o='lw'):
    s = Spr(90, 130, 45, 100)
    A_, D_, Ht = 14, 6, 44
    tube = M(F['steel'], F['steel'], F['steel_dk'])

    def back(t):
        for a0 in (0, A_ - 1):
            obox(t, o, a0, 0, 0, 0.8, 0.8, Ht, tube)
        for z0 in (12, 26, 40):
            obox(t, o, 0, 0, z0, A_, 0.6, 0.6, tube)
    part(s, back, .35)

    def decks(t):
        for z0 in (22, 40):
            obox(t, o, 0, 0.4, z0, A_, D_ - 0.8, 1, wood_tex(F['wood'], True, z0))
            obox(t, o, 0, D_ - 0.6, z0 + 1, A_, 0.3, 2, M(F['hazard']), 'FS')  # toe board
        obox(t, o, 0.5, 1, 0, 3, 3, 0.5, M(C('#4c5158')))
    part(s, decks, .4)

    def front(t):
        for a0 in (0, A_ - 1):
            obox(t, o, a0, D_ - 0.8, 0, 0.8, 0.8, Ht, tube)
            obox(t, o, a0, 0, 22 - 0.6, 0.8, D_, 0.6, tube)
        for z0 in (12, 31, 48):
            if z0 < Ht:
                obox(t, o, 0, D_ - 0.8, z0, A_, 0.6, 0.6, tube)
        for k in range(12):  # diagonal brace across the front
            obox(t, o, 0.8 + k * 1.0, D_ - 0.6, 1 + k * 1.7, 1.0, 0.4, 1.7, tube, 'F')
    part(s, front, .35)
    return s.crop()


def cement_mixer(o='lw'):
    """A site mixer: an orange drum tipped up on an A-frame stand, a wheel and a push handle (drawn in screen space)."""
    s = Spr(90, 90, 45, 64)
    fx, fy = SP._proj_local(s, o, 8, 4, 0)
    fx, fy = int(fx), int(fy)
    st = Spr(s.w, s.h, s.ax, s.ay)

    def put(x, y, c):
        st.put(int(x), int(y), c)
    steel, steel_lt = C('#4a5059'), C('#7a838d')
    thick(put, fx - 7, fy + 1, fx - 2, fy - 9, steel, 2)
    thick(put, fx + 6, fy + 2, fx + 2, fy - 9, steel, 2)
    thick(put, fx - 5, fy - 3, fx + 5, fy - 3, steel_lt, 1)
    thick(put, fx + 3, fy - 12, fx + 13, fy - 18, steel, 1)  # push handle
    st.rect(fx - 12, fy - 8, fx - 7, fy - 3, C('#6b737d'))  # motor
    st.rect(fx - 12, fy - 8, fx - 7, fy - 8, lt(C('#6b737d'), .3))
    for yy in range(-3, 4):  # wheel
        for xx in range(-2, 3):
            if yy * yy / 10.5 + xx * xx / 5 <= 1:
                put(fx + 8 + xx, fy + yy, C('#2a2a2e') if abs(yy) > 1 or abs(xx) > 0 else C('#9aa1ab'))
    outline(st, None, .45)
    s.paste(st, 0, 0)
    cx, cy = fx, fy - 15
    drum = Spr(s.w, s.h, s.ax, s.ay)
    body, dark, light = F['orange'], dk(F['orange'], .35), lt(F['orange'], .25)
    for y in range(-9, 10):
        for x in range(-10, 11):
            nx, ny = (x + 0.5) / 8.5, (y + 0.5) / 7.5
            if nx * nx + ny * ny > 1:
                continue
            l = -nx * 0.6 - ny * 0.7
            c = light if l > 0.45 else body if l > -0.35 else dark
            if abs((x + 0.5) * 0.5 - (y + 0.5) - 3) < 0.7:
                c = dk(c, .25)  # blade weld seam
            drum.put(cx + x, cy + y, c)
    for y in range(-4, 3):  # the mouth, tipped up and to the left
        for x in range(-4, 5):
            nx, ny = (x + 0.5) / 4.2, (y + 0.5) / 2.8
            if nx * nx + ny * ny <= 1:
                drum.put(cx - 4 + x, cy - 6 + y, C('#3a3330') if nx * nx + ny * ny < 0.55 else C('#c9ced6'))
    outline(drum, None, .5)
    s.paste(drum, 0, 0)
    return s.crop()


def cones():
    s = Spr(40, 40, 20, 30)
    for dx, dy, h in ((-5, -2, 11), (5, -1, 10), (0, 3, 12)):
        cone(s, s.ax + dx, s.ay + dy, h)
    return s.crop()


def cone(s, bx, by, h):
    o, od, w = F['orange'], dk(F['orange'], .3), C('#f4f4f0')
    t = Spr(s.w, s.h, s.ax, s.ay)
    for y in range(-1, 2):  # square base
        for x in range(-5, 6):
            if abs(x) / 5 + abs(y) / 2 <= 1.2:
                t.put(bx + x, by + y, C('#2e2b29') if y >= 0 else C('#4a4642'))
    for k in range(h):
        y = by - 1 - k
        hw = 3.2 - k * 2.8 / h
        for x in range(int(-hw - 0.5), int(hw + 0.5) + 1):
            if abs(x) > hw + 0.3:
                continue
            band = h * 0.35 <= k < h * 0.55
            c = (w if x < hw * 0.3 else C('#c9c9c4')) if band else (lt(o, .2) if x < -hw * 0.3 else o if x < hw * 0.4 else od)
            t.put(bx + x, y, c)
    outline(t, None, .45)
    s.paste(t, 0, 0)


def wheelbarrow(o='lw'):
    s = Spr(60, 50, 30, 34)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        g = F['green']
        if sf == 'T':
            return C('#b9a57e') if (a > 0.6 and d > 0.6 and a < A_ - 0.6 and d < D_ - 0.6) else lt(g, .2)
        return g if sf == 'L' else dk(g, .3)
    part(s, lambda t: (obox(t, o, -4, -2.2, 0, 0.8, 0.8, 3, M(C('#3a3d44'))),
                       obox(t, o, -4, 1.4, 0, 0.8, 0.8, 3, M(C('#3a3d44'))),
                       obox(t, o, -8, -2.2, 4, 7, 0.6, 0.6, M(C('#5b4636'))),
                       obox(t, o, -8, 1.6, 4, 7, 0.6, 0.6, M(C('#5b4636')))), .4)
    part(s, lambda t: obox(t, o, -3, -2.5, 3, 6, 5, 3.5, tex), .45)
    x, y = SP._proj_local(s, o, 0, 0, 6.5)  # a heap of sand
    for yy in range(-2, 1):
        for xx in range(-5 + abs(yy) * 2, 6 - abs(yy) * 2):
            s.put(int(x) + xx, int(y) + yy, C('#d8bf8a') if yy < -1 or xx < 0 else C('#c3a873'))
    wx, wy = SP._proj_local(s, o, 4.2, 0, 2.5)  # wheel
    for yy in range(-3, 4):
        for xx in range(-1, 2):
            if yy * yy / 10 + xx * xx / 2.5 <= 1:
                s.put(int(wx) + xx, int(wy) + yy, C('#2a2a2e') if abs(yy) > 1 or abs(xx) == 1 else C('#9aa1ab'))
    return s.crop()


def worklight():
    s = Spr(40, 70, 20, 56)
    part(s, lambda t: (obox(t, 'rw', -3, -3, 0, 6, 6, 1, M(F['hazard'])),
                       obox(t, 'rw', -0.5, -0.5, 1, 1, 1, 28, M(C('#4b505c'))),
                       obox(t, 'rw', -3.5, -0.4, 28, 7, 0.8, 0.8, M(C('#4b505c')))), .35)

    def head(role, a, d, z, A_, D_, Hh, sf):
        if sf == 'L':
            return E(C('#fff6cf')) if 0.5 <= a < A_ - 0.5 and 0.5 <= z < Hh - 0.5 else F['hazard']
        return F['hazard'] if sf == 'T' else dk(F['hazard'], .35)
    part(s, lambda t: (obox(t, 'rw', -4, -0.9, 29, 3, 1.6, 4, head), obox(t, 'rw', 1, -0.9, 29, 3, 1.6, 4, head)), .3)
    return s.crop()


# ---------------------------------------------------------------- people: a building crew at twice the density (Theme.personDetail)
# Drawn the way people_hd.py draws the office people (its figure, heads, faces, hands and shading are imported, not copied), so
# the crew has their warm shading, faces, hair and proportions; dressed as builders: a hard hat, a hi-vis vest with reflective
# stripes over a work shirt, a belt with a tool pouch, work boots. Every size and anchor is even (a frame covers whole native
# pixels). Frames: crew<v>_<state>_<phase>_f (idle has phase 0 only) and, for a desk along v, crew<v>_working_<phase>_lw_f.

DETAIL = PH.DETAIL

CREW = [
    dict(name='dongsu', skin='light', hair='#4a3022', style='short', shirt='#3f6fb5', pants='#34405e', shoe='#8a5a2e',
         hat='#f5c518', hivis='#ff7a1a', tool='hammer'),
    dict(name='ama', skin='dark', hair='#1f1a20', style='curly', shirt='#6b7280', pants='#3b3f47', shoe='#6a4426',
         hat='#f4f4ee', hivis='#c8f03c', tool='trowel'),
    dict(name='yuna', skin='medium', hair='#2c2028', style='ponytail', shirt='#2f3b55', pants='#5b4b3a', shoe='#7a4a26',
         hat='#f28c28', hivis='#c8f03c', tool='hammer'),
    dict(name='ivan', skin='pale', hair='#c2662e', style='short', shirt='#b8423a', pants='#41506e', shoe='#8a5a2e',
         hat='#f5c518', hivis='#c8f03c', tool='trowel', beard=True),
    dict(name='bongsu', skin='brown', hair='#a8a8ae', style='short', shirt='#e9e6dd', pants='#2e3446', shoe='#5a3a22',
         hat='#f4f4ee', hivis='#ff7a1a', tool='hammer', glasses='#2a2228'),
    dict(name='mei', skin='light', hair='#7a3b22', style='bob', shirt='#3e7d4f', pants='#4a4038', shoe='#6a4426',
         hat='#f28c28', hivis='#ff7a1a', tool='trowel', freckles=True),
    dict(name='leo', skin='tan', hair='#2a2230', style='messy', shirt='#d9a23a', pants='#33374d', shoe='#7a4a26',
         hat='#f5c518', hivis='#ff7a1a', tool='hammer'),
    dict(name='nari', skin='pale', hair='#f0c060', style='long', shirt='#4a7fd0', pants='#43405a', shoe='#8a5a2e',
         hat='#f4f4ee', hivis='#c8f03c', tool='trowel'),
    dict(name='omar', skin='medium', hair='#231c24', style='short', shirt='#5d6470', pants='#2f3240', shoe='#5a3a22',
         hat='#f28c28', hivis='#c8f03c', tool='hammer', beard=True),
    dict(name='suji', skin='tan', hair='#2c2028', style='twintail', shirt='#c0504a', pants='#3a3548', shoe='#7a4a26',
         hat='#f5c518', hivis='#ff7a1a', tool='trowel', glasses='#7a3a2a'),
]
REFLECT = ramp('#e3e9ee')
BELT = C('#4a3220')
BUCKLE = C('#d9c27a')
LEATHER = ramp('#8a5a2e')
WOOD = ramp('#a8743e')
STEEL = ramp('#8d96a0')
FLAG = ramp('#e5302a')
GREEN = C('#2f9e4f')
SPARK = [C('#fff6b0'), C('#ffd23a'), C('#ff9a2e')]
DUST = [C('#efe8d8'), C('#d6ccb8'), C('#bfb39c')]
SEAT_HD = 7 * DETAIL  # the seat's height (OfficeLayout.SEAT_Z) at this density: the floor under a seated person


def hat_front(f, P, cx, cy, tip=0):
    """A hard hat on a front 3/4 head (centre cx, cy): a dome with a ridge and a shine, the peak a little to the left (where the
    face looks). [tip] pulls it down over the face."""
    sk = PH.SKIN[P['skin']]
    R = ramp(P['hat'])
    dome = {p for p in f.mask(('e', (cx - 15, cy - 23 + tip, cx + 14, cy + 3 + tip))) if p[1] - f.lift <= cy - 6 + tip}
    f.paint(dome, R, 'hat', shade='rb')
    f.vl(cx - 1, cy - 21 + tip, cy - 7 + tip, R['hi'])
    f.vl(cx + 1, cy - 21 + tip, cy - 7 + tip, R['sh'])
    f.hl(cx - 9, cx - 6, cy - 17 + tip, WHITE)
    f.px(cx - 10, cy - 16 + tip, R['hi'])
    brim = f.mask(('e', (cx - 19, cy - 9 + tip, cx + 16, cy - 3 + tip)))
    f.paint(brim, R, 'brim', shade='b')
    for (x, y) in brim:  # the brim's shadow on the face and hair
        q = (x, y + 1)
        if q not in brim and q in f.col and f.col[q] in (sk['b'], sk['hi']):
            f.col[q] = sk['sh']


def hat_back(f, P, cx, cy):
    """A hard hat on a head seen from behind: the dome, the ridge and a short brim all round."""
    R = ramp(P['hat'])
    dome = {p for p in f.mask(('e', (cx - 14, cy - 22, cx + 14, cy + 4))) if p[1] - f.lift <= cy - 5}
    f.paint(dome, R, 'hat', shade='rb')
    f.vl(cx - 1, cy - 20, cy - 6, R['hi'])
    f.vl(cx + 1, cy - 20, cy - 6, R['sh'])
    f.hl(cx - 8, cx - 5, cy - 16, WHITE)
    f.paint(f.mask(('e', (cx - 17, cy - 8, cx + 17, cy - 2))), R, 'brim', shade='b')


def boots(f, P):
    """legs_front's shoes as work boots: a dark sole, a lighter toe cap, laces."""
    sole = ramp(P['shoe'])['ln']
    for x in range(-14, 15):
        for y in (0, -1):
            if (x, y) in f.col and (f.grp.get((x, y)) or '').startswith('shoe'):
                f.col[(x, y)] = sole
    for x0 in (-9, 4):
        f.px(x0, -6, C('#e9dcb8')); f.px(x0 + 2, -6, C('#e9dcb8'))


def vest_front(f, P, poly, sleeves):
    """The upper body from the front: a work shirt, a hi-vis vest open in a V at the neck with a reflective band and braces, a
    belt with a buckle and a tool pouch; then [sleeves] (shirt) over it. Standing coordinates (the torso from y -58 to -33)."""
    sk, hair, top, pants, *_ = looks(P)
    hv = ramp(P['hivis'])
    body = f.mask(('p', poly))
    f.paint(body, top, 'shirt')
    vest = {p for p in body if not (p[1] - f.lift < -46 and abs(p[0] + 0.5) < 1 + (-(p[1] - f.lift) - 46) * 0.6)}
    f.paint(vest, hv, 'vest')
    for (x, y) in vest:
        yy = y - f.lift
        if -45 <= yy <= -43 or (yy < -45 and abs(x + 0.5) in (6.5, 7.5)):
            f.col[(x, y)] = REFLECT['sh'] if (yy == -43 or x > 9) else REFLECT['b']
    f.vl(0, -46, -36, hv['dp'])  # zip
    f.hl(-10, -5, -40, hv['sh'])  # pocket
    f.vl(-10, -40, -37, hv['sh'])
    for i in range(3):  # the shirt's collar in the V
        f.px(-2 - i, -57 + i, top['hi']); f.px(1 + i, -57 + i, top['b'])
    for sx in (-1, 1):  # folds under the arms
        for i in range(4):
            f.px(sx * (10 - i // 2), -52 + i * 2, hv['sh'])
    f.hl(-11, 11, -34, BELT); f.hl(-11, 11, -33, PH.wdk(BELT, .4))
    f.hl(-2, 1, -34, BUCKLE); f.px(-2, -33, BUCKLE); f.px(1, -33, BUCKLE)
    f.paint(f.mask(('r', (7, -35, 13, -28))), LEATHER, 'pouch')
    f.hl(7, 13, -35, LEATHER['dp'])
    f.vl(12, -40, -36, WOOD['b'])  # a hammer handle in the pouch
    for m in sleeves:
        f.paint(m, top, 'sleeve')


def open_hand(f, sk, hx, hy, thumb=-1):
    """An open palm facing us, four fingers apart, the thumb out on the [thumb] side (-1 left)."""
    f.paint(f.mask(('e', (hx - 5, hy - 2, hx + 5, hy + 8))), sk, 'hand', hl=False)
    for fx, ln in ((-4, 5), (-1, 7), (2, 7), (5, 5)):
        f.paint(f.mask(('r', (hx + fx, hy - ln, hx + fx + 1, hy))), sk, 'hand', shade='', hl=False)
        f.px(hx + fx + 1, hy - ln + 1, sk['sh'])
    f.paint(f.mask(('l', [(hx + 5 * thumb, hy + 4), (hx + 9 * thumb, hy - 1)], 3)), sk, 'hand', shade='', hl=False)
    f.hl(hx - 3, hx + 3, hy + 6, sk['sh'])


def fist(f, sk, x0, y0, x1, y1, group='fist'):
    f.paint(f.mask(('e', (x0, y0, x1, y1))), sk, group, hl=False)
    for x in range(x0 + 2, x1 - 1, 2):
        f.vl(x, y0 + 2, y0 + 3, sk['sh'])
    f.px(x0 + 1, y0 + 1, sk['hi'])


def done_effects(phase):
    """A sparkle beside the thumb that grows and fades, in another spot every other phase."""
    out = []
    cx, cy = (31, -82) if phase < 2 else (-22, -76)
    r = (3, 2, 3, 1)[phase]
    for d in range(1, r + 1):
        c = SPARK[0] if d < r else SPARK[1]
        for dx, dy in ((d, 0), (-d, 0), (0, d), (0, -d)):
            out.append((cx + dx, cy + dy, c))
    out.append((cx, cy, WHITE))
    return out


def standing_f(P, state, phase):
    """Standing, front 3/4, at the feet's anchor: waving a red flag, or a thumbs up with a ticked clipboard."""
    f = Fig()
    sk, hair, top, *_ = looks(P)
    legs_front(f, P)
    boots(f, P)
    f.lift = LIFT
    hair_back_layer(f, P, 0, -74)
    torso = [(-12, -58), (12, -58), (14, -52), (12, -33), (-12, -33), (-14, -52)]
    shoulders = [f.mask(('e', (-16, -60, -6, -48))), f.mask(('e', (6, -60, 16, -48)))]
    fx = []
    if state == 'waiting':
        sw = (-1, 0, 1, 0)[phase]
        hx = 21 + sw
        sleeves = [f.mask(('l', [(-12, -54), (-19, -62), (-21, -69)], 7)),
                   f.mask(('l', [(12, -54), (19, -64), (hx, -72)], 7))]
        vest_front(f, P, torso, shoulders + sleeves)
        cuff(f, top, -21, -69)
        cuff(f, top, hx, -72)
        open_hand(f, sk, -21, -78, thumb=1)
        # the flag: a pole through the raised fist, the red cloth rippling off its top
        a = math.radians((-16, 0, 16, 0)[phase])
        ux, uy = math.sin(a), -math.cos(a)
        bx, by = hx - ux * 6, -72 - uy * 6
        tx, ty = hx + ux * 36, -78 + uy * 36
        f.paint(f.mask(('l', [(bx, by), (tx, ty)], 2)), WOOD, 'pole', shade='', hl=False)
        f.px(round(tx), round(ty) - 1, BUCKLE)
        nx, ny = math.cos(a), math.sin(a)
        for y in range(int(ty) - 6, int(ty) + 30):
            for x in range(int(tx) - 4, int(tx) + 26):
                dx, dy = x - tx, y - ty
                i = dx * nx + dy * ny
                j = -(dx * ux + dy * uy)
                k = i * 0.5 - phase * math.pi / 2
                j -= math.sin(k) * (0.4 + i * 0.13)
                if 1 <= i < 18 and 1 <= j < 13:
                    w = math.cos(k)
                    c = FLAG['sh'] if w < -0.45 else (FLAG['hi'] if w > 0.6 else FLAG['b'])
                    if j >= 11.5:
                        c = FLAG['dp']
                    f.px(x, y, c, 'flag')
        fist(f, sk, hx - 4, -81, hx + 4, -73)
        f.vl(hx - 5, -79, -76, sk['b'])  # the thumb round the pole
        head_front(f, P, 'calling' if phase in (0, 2) else 'open')
        hat_front(f, P, 0, -74)
    else:
        b = (0, -1, -2, -1)[phase]
        sleeves = [f.mask(('l', [(-12, -54), (-15, -44), (-6, -41)], 7)),
                   f.mask(('l', [(12, -54), (20, -50), (22, -60 + b)], 7))]
        vest_front(f, P, torso, shoulders + sleeves)
        # the clipboard held to the chest, three green ticks
        f.paint(f.mask(('r', (-15, -55, -2, -34))), ramp('#a0703a'), 'board')
        f.paint(f.mask(('r', (-14, -52, -3, -36))), ramp('#fbfaf4'), 'paper', shade='', hl=False)
        f.paint(f.mask(('r', (-10, -57, -6, -52))), STEEL, 'clip', hl=False)
        for ty in (-49, -44, -39):
            f.px(-13, ty, GREEN); f.px(-12, ty + 1, GREEN); f.px(-11, ty, GREEN); f.px(-10, ty - 1, GREEN)
            f.hl(-8, -4, ty, C('#9aa3b5'))
        cuff(f, top, -4, -41)
        hand(f, sk, -4, -46, 1, -40, 'handL', thumb=-1)
        # thumbs up by the shoulder
        cuff(f, top, 22, -60 + b)
        # a sideways fist, fingers curled towards us (creases across), the thumb straight up on the side of the head
        f.paint(f.mask(('e', (17, -67 + b, 28, -58 + b))), sk, 'fist', hl=False)
        for y in (-64, -62, -60):
            f.hl(21, 26, y + b, sk['sh'])
        f.paint(f.mask(('e', (16, -76 + b, 21, -63 + b))), sk, 'thumb', shade='r', hl=False)
        f.px(18, -75 + b, sk['hi']); f.px(17, -73 + b, sk['hi'])
        f.hl(18, 19, -76 + b, mix(sk['b'], WHITE, .4))  # the nail
        head_front(f, P, 'happy')
        hat_front(f, P, 0, -74)
        fx = done_effects(phase)
    return f, fx


def idle_f(P):
    """Turned round on the seat, slumped, arms crossed, the hard hat tipped over the face, boots on the floor."""
    f = Fig()
    sk, hair, top, pants, shoe, *_ = looks(P)
    sr = ramp(P['shoe'])
    f.paint(f.mask(('r', (-12, -6, 12, 1))), pants, 'lap')
    f.paint(f.mask(('p', [(-12, 0), (-3, 0), (-5, SEAT_HD - 5), (-13, SEAT_HD - 5)])), pants, 'legL', shade='r')
    f.paint(f.mask(('p', [(3, 0), (12, 0), (15, SEAT_HD - 5), (6, SEAT_HD - 5)])), pants, 'legR', shade='r')
    f.paint(f.mask(('e', (-16, SEAT_HD - 9, -3, SEAT_HD - 1))), sr, 'shoeL')
    f.paint(f.mask(('e', (4, SEAT_HD - 9, 17, SEAT_HD - 1))), sr, 'shoeR')
    f.hl(-16, -3, SEAT_HD - 2, sr['ln']); f.hl(4, 17, SEAT_HD - 2, sr['ln'])
    f.hl(-13, -8, SEAT_HD - 8, sr['hi']); f.hl(7, 12, SEAT_HD - 8, sr['hi'])
    f.lift = 28  # the standing torso and head, lowered onto the seat
    hair_back_layer(f, P, 2, -72)
    torso = [(-12, -58), (12, -58), (14, -52), (12, -33), (-12, -33), (-14, -52)]
    shoulders = [f.mask(('e', (-16, -60, -6, -48))), f.mask(('e', (6, -60, 16, -48)))]
    vest_front(f, P, torso, shoulders + [f.mask(('e', (-15, -51, 15, -41)))])
    f.hl(-11, 11, -46, top['sh'])
    f.hl(-13, 13, -41, top['dp'])
    fist(f, sk, 10, -50, 16, -44, 'handR')
    fist(f, sk, -16, -47, -10, -41, 'handL')
    head_front(f, P, 'open', hy=-72, hx=2)
    hat_front(f, P, 2, -72, tip=9)
    # a slack, open mouth under the brim
    f.hl(-2, -1, -62, MOUTH); f.hl(-2, -1, -61, C('#5a2228'))
    return f, []


def tool_line(f, x0, y0, ang, ln):
    return x0 + math.cos(ang) * ln, y0 + math.sin(ang) * ln


def working_f(P, phase):
    """Seated, seen from behind, facing up-right at the bench: hammering (sparks) or laying a brick with a trowel (dust)."""
    f = Fig()
    sk, hair, top, pants, *_ = looks(P)
    hv = ramp(P['hivis'])
    hammer = P['tool'] == 'hammer'
    hx, hy = 8, -50 + (1 if phase == 2 else 0)
    f.paint(f.mask(('e', (-6, -10, 18, 2))), pants, 'pants')
    f.lift = SIT
    f.paint(f.mask(('l', [(-2, -30), (-5, -18), (10, -24)], 7)), top, 'sleeveL')
    f.lift = 0
    f.paint(f.mask(('p', [(8, -10), (18, -12), (30, -15), (31, -9), (20, -3), (10, -2)])), pants, 'thigh')
    f.hl(19, 29, -9, pants['dp'])
    f.lift = SIT
    shoulders = f.mask(('e', (-8, -37, 4, -25)), ('e', (8, -37, 20, -25)))
    f.paint(shoulders | f.mask(('p', [(-6, -6), (16, -6), (18, -24), (16, -33), (0, -34), (-7, -24)])), top, 'shirt')
    vest = f.mask(('p', [(-5, -6), (15, -6), (16, -24), (14, -33), (1, -34), (-5, -24)]))
    f.paint(vest, hv, 'vest')
    for (x, y) in vest:
        yy = y - f.lift
        if -20 <= yy <= -18 or (yy < -20 and x in (1, 2, 11, 12)):
            f.col[(x, y)] = REFLECT['sh'] if (yy == -18 or x >= 11) else REFLECT['b']
    f.vl(6, -32, -10, hv['sh'])
    f.hl(-5, 15, -8, BELT); f.hl(-5, 15, -7, PH.wdk(BELT, .4))
    f.paint(f.mask(('r', (-6, -11, -1, -4))), LEATHER, 'pouch')
    hair_back_layer(f, P, hx, hy, back=True)
    head_back(f, P, hx, hy)
    hat_back(f, P, hx, hy)
    f.lift = 0
    fx = []
    if hammer:
        S = (17, -26)
        E, Hd, ang = [((23, -36), (23, -48), math.radians(-150)),
                      ((25, -28), (31, -38), math.radians(-60)),
                      ((22, -18), (30, -22), math.radians(15)),
                      ((24, -24), (31, -31), math.radians(-25))][phase]
        ex, ey = tool_line(f, Hd[0], Hd[1], ang, 12)
        f.paint(f.mask(('l', [Hd, (ex, ey)], 3)), WOOD, 'handle', shade='', hl=False)
        px, py = -math.sin(ang), math.cos(ang)
        f.paint(f.mask(('l', [(ex - px * 5, ey - py * 5), (ex + px * 4, ey + py * 4)], 5)), STEEL, 'head')
        hit = (round(ex + px * 5), round(ey + py * 5))
    else:
        # the other hand sets a brick on the bench
        f.lift = SIT
        f.paint(f.mask(('r', (13, -35, 25, -29))), ramp(F['brick'][0]), 'brick')
        f.hl(13, 25, -32, F['mortar'])
        f.lift = 0
        S = (17, -26)
        E, Hd, ang = [((23, -27), (28, -35), math.radians(-35)),
                      ((24, -24), (30, -30), math.radians(-10)),
                      ((22, -19), (30, -23), math.radians(30)),
                      ((23, -22), (30, -27), math.radians(5))][phase]
        ex, ey = tool_line(f, Hd[0], Hd[1], ang, 4)
        f.paint(f.mask(('l', [Hd, (ex, ey)], 3)), WOOD, 'handle', shade='', hl=False)
        tx, ty = tool_line(f, ex, ey, ang, 9)
        px, py = -math.sin(ang), math.cos(ang)
        f.paint(f.mask(('p', [(ex - px * 4, ey - py * 4), (ex + px * 4, ey + py * 4), (tx, ty)])), STEEL, 'blade')
        hit = (round(tx), round(ty))
    f.paint(f.mask(('l', [S, E, Hd], 7)), top, 'sleeveR')
    cuff(f, top, Hd[0] - 2, Hd[1] + 2)
    fist(f, sk, Hd[0] - 3, Hd[1] - 3, Hd[0] + 4, Hd[1] + 3, 'hand')
    if not hammer:
        f.lift = SIT
        hand(f, sk, 15, -30, 21, -24, 'handL')
        f.lift = 0
    else:
        f.lift = SIT
        hand(f, sk, 15, -29, 21, -23, 'handL')
        f.lift = 0
    # sparks or mortar dust where the tool lands
    if phase == 2:
        hx_, hy_ = hit
        if hammer:
            for i, (dx, dy) in enumerate(((3, -3), (5, -1), (2, -6), (6, -5), (-1, -5), (5, 2), (8, -2), (4, -8), (9, -6))):
                fx.append((hx_ + dx, hy_ + dy, SPARK[i % 3]))
                if i < 4:
                    fx.append((hx_ + dx + 1, hy_ + dy, SPARK[0]))
        else:
            for i, (dx, dy, r) in enumerate(((2, -3, 2), (6, -2, 2), (4, -6, 1), (-1, -4, 1))):
                for ddx in range(-r, r + 1):
                    for ddy in range(-r, r + 1):
                        if ddx * ddx + ddy * ddy <= r * r + 1:
                            fx.append((hx_ + dx + ddx, hy_ + dy + ddy, DUST[(ddx + ddy + i) % 2]))
    elif phase == 3:
        hx_, hy_ = (34, -24) if hammer else (34, -26)
        if hammer:
            for i, (dx, dy) in enumerate(((6, -8), (10, -4), (3, -11), (11, -9))):
                fx.append((hx_ + dx, hy_ + dy, SPARK[1 + i % 2]))
        else:
            for i, (dx, dy, r) in enumerate(((3, -6, 2), (7, -5, 1), (0, -8, 1))):
                for ddx in range(-r, r + 1):
                    for ddy in range(-r, r + 1):
                        if ddx * ddx + ddy * ddy <= r * r:
                            fx.append((hx_ + dx + ddx, hy_ + dy + ddy, DUST[1 + (ddx + ddy) % 2]))
    return f, fx


def finish_fx(f, fx):
    """Fig.finish(), then the effect pixels (sparks, dust, a sparkle) on top without an outline, the frame grown to hold them."""
    sp = f.finish()
    if not fx:
        return sp
    x0, y0 = min([-sp.ax] + [p[0] for p in fx]), min([-sp.ay] + [p[1] for p in fx])
    x1, y1 = max([sp.w - sp.ax - 1] + [p[0] for p in fx]), max([sp.h - sp.ay - 1] + [p[1] for p in fx])
    x0 -= x0 % 2
    y0 -= y0 % 2
    w, h = x1 - x0 + 1, y1 - y0 + 1
    out = Spr(w + w % 2, h + h % 2, -x0, -y0)
    out.img.paste(sp.img, (-sp.ax - x0, -sp.ay - y0))
    out._load()
    for x, y, c in fx:
        out.put(x - x0, y - y0, c)
    return out


def pose_frames(P, state):
    """The phases of one pose, each grown to their common box so every phase has the same (even) size and anchor."""
    if state == 'idle':
        sp = [finish_fx(*idle_f(P))]
    elif state == 'working':
        sp = [finish_fx(*working_f(P, ph)) for ph in range(PHASES)]
    else:
        sp = [finish_fx(*standing_f(P, state, ph)) for ph in range(PHASES)]
    x0 = min(-p.ax for p in sp)
    y0 = min(-p.ay for p in sp)
    x1 = max(p.w - p.ax for p in sp)
    y1 = max(p.h - p.ay for p in sp)
    out = []
    for p in sp:
        c = Spr(x1 - x0, y1 - y0, -x0, -y0)
        c.img.paste(p.img, (-p.ax - x0, -p.ay - y0))
        c._load()
        out.append(c)
    return out


def crew_frames():
    fr = {}
    for vi, P in enumerate(CREW):
        for st in STATES:
            for ph, sp in enumerate(pose_frames(P, st)):
                fr[f'crew{vi}_{st}_{ph}_f'] = sp
                if st == 'working':
                    fr[f'crew{vi}_{st}_{ph}_lw_f'] = flip(sp)
    return fr


def crew_lineup(path, scale=4):
    """Every crew member x state and phase at [scale] x the scene (scale / DETAIL x the frames), on dirt."""
    fr = crew_frames()
    cols = [f'working_{p}' for p in range(4)] + ['working_0_lw', 'idle_0'] + [f'waiting_{p}' for p in range(4)] + \
           [f'done_{p}' for p in range(4)]
    k = scale // DETAIL
    cw, ch = 34 * scale, 56 * scale
    img = Image.new('RGBA', (cw * len(cols) + 20, ch * len(CREW) + 20), (163, 130, 92, 255))
    d = ImageDraw.Draw(img)
    for yy in range(0, img.height, 8 * scale):
        d.line([(0, yy), (img.width, yy)], fill=(150, 118, 82, 255))
    for r in range(len(CREW)):
        for c, name in enumerate(cols):
            sp = fr[f'crew{r}_{name}_f']
            big = sp.img.resize((sp.w * k, sp.h * k), Image.NEAREST)
            x = 10 + c * cw + cw // 2 - sp.ax * k
            y = 10 + r * ch + ch - 10 * scale - sp.ay * k
            img.alpha_composite(big, (x, max(0, y)))
    img.convert('RGB').save(path)
    print('wrote', path)


# ---------------------------------------------------------------- sheet


def build_sheet_f():
    """Theme F's own sheet in OUT/f/: every frame under an _f suffix except the bubbles, which are A's under A's names."""
    fr = {}
    for v in range(4):
        fr[f'floor_dirt_{v}_f'] = floor_tile(v)
    fr['wall_rw_f'] = wall_seg('rw', 1)
    fr['wall_rw_fence_f'] = wall_seg('rw', 2, fence=True)
    fr['wall_lw_f'] = wall_seg('lw', 3)
    fr['wall_lw_fence_f'] = wall_seg('lw', 5, fence=True)
    fr['wall_corner_f'] = wall_corner()
    fr['bench_rw_f'] = workbench('rw')
    fr['brickwall_lw_f'] = brick_wall('lw')
    fr['bucket_f'] = bucket()
    fr['cinder_lw_f'] = cinder_block('lw')
    for i in range(3):
        fr[f'deck_{i}_f'] = platform(i)
    fr['sign_f'] = site_sign(40)
    fr['sitemap_lw_f'] = site_map('lw')
    fr['sitemap_rw_f'] = site_map('rw')
    fr['safety_rw_f'] = safety_sign('rw')
    fr['danger_rw_f'] = danger_sign('rw')
    fr['bricks_lw_f'] = brick_pallet('lw')
    fr['scaffold_lw_f'] = scaffold('lw')
    fr['mixer_lw_f'] = cement_mixer('lw')
    fr['cones_f'] = cones()
    fr['barrow_f'] = wheelbarrow('lw')
    fr['worklight_f'] = worklight()
    fr.update(crew_frames())
    for st in STATES:
        fr[f'bubble_{st}'] = SP.bubble(st)
    out = f'{OUT}/f'
    os.makedirs(out, exist_ok=True)
    SP.pack(fr, out)
    return fr


# ---------------------------------------------------------------- mockup (the scene of scenes.py, theme F frames)


def native(sp):
    """An HD crew frame shrunk to the scene's density, for the native mockup only (the plugin draws them at DETAIL x)."""
    n = Spr(sp.w // DETAIL, sp.h // DETAIL, sp.ax // DETAIL, sp.ay // DETAIL)
    n.img = sp.img.resize((n.w, n.h), Image.NEAREST)
    n._load()
    return n


def mockup(fr):
    import scenes as SC
    NW, NH, S = SC.NW, SC.NH, 3
    items = []

    def add(layer, key, name, u, v, z=0):
        X, Y = SC.proj(u, v, z)
        items.append((layer, key, fr[name] if isinstance(name, str) else name, X, Y))
    add(0, 0, floor_base(96), 0, 0)
    for i in range(6):
        for j in range(6):
            add(0, 1 + i + j, f'floor_dirt_{(i * 31 + j * 17 + (i ^ j)) % 4}_f', i * 16, j * 16)
    add(1, 0, 'wall_corner_f', 0, 0)
    for i in range(6):
        add(1, 1 + i, 'wall_rw_fence_f' if i in (1, 3, 5) else 'wall_rw_f', i * 16, 0)
        add(1, 1 + i, 'wall_lw_fence_f' if i in (2, 4) else 'wall_lw_f', 0, i * 16)
    for d in DECOR:
        add(d[4], d[5], d[0], d[1], d[2], d[3])
    for k, (_, _, (ru, rv, ra, rd, pi)) in enumerate(SC.AREAS):
        cols, rows = max(1, ra // 24), max(1, rd // 16)
        u0, v0 = ru + (ra - cols * 24) // 2, rv + (rd - rows * 16) // 2
        for c in range(cols):
            for r in range(rows):
                add(3, k + (c + r) / 100, f'deck_{pi % 3}_f', u0 + c * 24, v0 + r * 16)
    people = []
    phase = int(os.environ.get('PHASE', '2'))
    for si, (area, di, state, pv, title) in enumerate(SC.SESSIONS):
        o, U, V = SC.DESKS[area][di]
        pv = si % len(CREW)
        add(5, U + V + 15, 'bench_rw_f' if o == 'rw' else 'brickwall_lw_f', U, V)
        cu, cv = (U + 10, V + 12.5) if o == 'rw' else (U + 12.5, V + 10)
        add(5, cu + cv, 'bucket_f' if o == 'rw' else 'cinder_lw_f', cu, cv)
        ph = phase if state != 'idle' else 0
        lw = '_lw' if state == 'working' and o == 'lw' else ''
        name = native(fr[f'crew{pv}_{state}_{ph}{lw}_f'])  # the scene is native: the HD crew at half size
        if state in ('working', 'idle'):
            add(5, cu + cv + 0.1, name, cu, cv, 7)
            X, Y = SC.proj(cu, cv, 7)
        else:
            pu, pvv = (U + 16, V + 22) if o == 'lw' else ((U + 15, V + 19) if di == 1 else (U - 3, V + 16))
            add(5, pu + pvv, name, pu, pvv)
            X, Y = SC.proj(pu, pvv)
        people.append((state, X, Y - name.ay))
    for label, (pu, pvv), _ in SC.AREAS:
        add(5, pu + pvv, 'sign_f', pu, pvv)
    canvas = Spr(NW, NH)
    for layer, key, sp, X, Y in sorted(items, key=lambda it: (it[0], it[1])):
        canvas.place(sp, X, Y)
    for st, X, head in people:
        b = fr[f'bubble_{st}']
        canvas.place(b, X + 3, head - 1 - [0, 1, 2, 1][phase % 4])
    bg = Image.new('RGBA', (NW, NH), (46, 42, 37, 255))
    bg.alpha_composite(canvas.img)
    img = bg.resize((NW * S, NH * S), Image.NEAREST)
    img.convert('RGB').save(f'{OUT}/F-construction.png')
    print('wrote', f'{OUT}/F-construction.png')
    crew_lineup(f'{OUT}/F-crew.png')


# (frame, u, v, z, layer, key): mirrors ThemeF.decor, used by the mockup
DECOR = [
    ('sitemap_lw_f', 0, 16, 0, 2, 0.0),
    ('sitemap_rw_f', 66, 0, 0, 2, 1.0),
    ('safety_rw_f', 39, 0, 40, 2, 2.0),
    ('danger_rw_f', 7, 0, 24, 2, 3.0),
    ('bricks_lw_f', 0, 1, 0, 5, 11.0),
    ('scaffold_lw_f', 0, 80, 0, 5, 90.0),
    ('mixer_lw_f', 0, 48, 0, 5, 60.0),
    ('cones_f', 90, 6, 0, 5, 96.0),
    ('cones_f', 7, 89, 0, 5, 96.0),
    ('barrow_f', 5, 68, 0, 5, 73.0),
    ('worklight_f', 91, 90, 0, 5, 181.0),
]


if __name__ == '__main__':
    frames = build_sheet_f()
    print(len(frames), 'frames (theme F sheet, f/)')
    if 'mockup' in sys.argv[1:]:
        mockup(frames)
