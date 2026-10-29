"""Theme F - 공사장 (Construction Site): sprites at native resolution, packed into OUT/f/.

Same grid and footprints as theme A (16-unit tiles, 2:1 iso, desk 20x10, seat at z 7), so the layout does not change.
A desk is a sawhorse workbench (rw) or a brick wall going up (lw); a chair is an upturned bucket (rw) or a cinder block (lw);
people are a crew in hard hats and hi-vis vests with a real animation per state:
  working  seated, seen from behind, swinging a hammer (sparks) or tapping a brick down with a trowel (mortar dust), 4 phases
  waiting  standing, an arm straight up waving a red flag, mouth open, 4 phases
  done     standing, thumbs up, a clipboard of green ticks in the other hand, 4 phases (the thumb bobs, a sparkle)
  idle     sitting turned round on the seat, arms crossed, hard hat tipped over the face, legs out (1 frame)
Run from ai-visualization/art:  PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" theme_f.py [mockup]
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFont
from pix import C, dk, lt, mix, E, H, Spr, part, outline, outline_out, box, obox, M, INK
import sprites as SP

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


# ---------------------------------------------------------------- people

CREW = [
    dict(hat=C('#f5c518'), vest=C('#ff7a1a'), shirt=C('#3f6fb5'), skin=C('#f1c39b'), hair=C('#5b3a26'), pants=C('#34405e'),
         boot=C('#7a4a26'), style='short', tool='hammer'),
    dict(hat=C('#f4f4ee'), vest=C('#c8f03c'), shirt=C('#6b7280'), skin=C('#8d5a3b'), hair=C('#1f1a1c'), pants=C('#3b3f47'),
         boot=C('#5a3a22'), style='short', tool='trowel'),
    dict(hat=C('#f28c28'), vest=C('#c8f03c'), shirt=C('#2f3b55'), skin=C('#d9a273'), hair=C('#26201f'), pants=C('#5b4b3a'),
         boot=C('#3a2a22'), style='long', tool='hammer'),
    dict(hat=C('#f5c518'), vest=C('#c8f03c'), shirt=C('#b8423a'), skin=C('#f6d2b5'), hair=C('#c2662e'), pants=C('#41506e'),
         boot=C('#7a4a26'), style='beard', tool='trowel'),
    dict(hat=C('#f4f4ee'), vest=C('#ff7a1a'), shirt=C('#e9e6dd'), skin=C('#5e3a28'), hair=C('#a8a8ae'), pants=C('#2e3446'),
         boot=C('#4a3426'), style='short', tool='hammer'),
    dict(hat=C('#f28c28'), vest=C('#ff7a1a'), shirt=C('#3e7d4f'), skin=C('#e8b48c'), hair=C('#7a3b22'), pants=C('#4a4038'),
         boot=C('#5a3a22'), style='long', tool='trowel'),
]
EYE = C('#2a2238')
REFLECT = C('#e3e9ee')
SPARK = [C('#fff6b0'), C('#ffd23a'), C('#ff9a2e')]
DUST = [C('#e8e0cf'), C('#cfc5b2')]

CW, CH, AX, AY = 48, 60, 24, 50  # person canvas: anchor (feet / seat point) at (AX, AY)


def thick(put, x0, y0, x1, y1, c, w=2):
    n = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
    for i in range(n + 1):
        t = i / n
        x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
        for dx in range(w):
            for dy in range(w):
                put(int(math.floor(x)) + dx, int(math.floor(y)) + dy, c)


def person(P, pose, phase=0):
    s = Spr(CW, CH, AX, AY)
    hat, hat_dk, hat_lt = P['hat'], dk(P['hat'], .25), lt(P['hat'], .45)
    vs, vd, vl = P['vest'], dk(P['vest'], .22), lt(P['vest'], .2)
    sh, shd = P['shirt'], dk(P['shirt'], .25)
    sk, sd = P['skin'], dk(P['skin'], .18)
    hr, hrd = P['hair'], dk(P['hair'], .3)
    pa, pad = P['pants'], dk(P['pants'], .3)
    bt = P['boot']
    seated = pose in ('working', 'idle')
    ox = AX - 9
    oy = AY - 29 + (7 if seated else 0)

    def R(x0, y0, x1, y1, c):
        s.rect(x0 + ox, y0 + oy, x1 + ox, y1 + oy, c)

    def Pt(x, y, c):
        s.put(int(x) + ox, int(y) + oy, c)

    def front_head(dy=0, tipped=False):
        if not tipped:
            R(5, 6 + dy, 13, 10 + dy, sk); R(6, 11 + dy, 12, 11 + dy, sk); R(13, 6 + dy, 13, 11 + dy, sd)
            R(4, 6 + dy, 4, 8 + dy, hr); R(14, 6 + dy, 14, 8 + dy, hrd)
            R(5, 6 + dy, 13, 6 + dy, dk(sk, .28))  # shade of the brim
            if P['style'] == 'long':
                R(3, 6 + dy, 4, 13 + dy, hr); R(14, 6 + dy, 15, 13 + dy, hrd)
            if P['style'] == 'beard':
                R(5, 9 + dy, 13, 11 + dy, hr); R(6, 12 + dy, 12, 12 + dy, hrd)
        # the hard hat: dome, ridge, brim
        R(6, 1 + dy, 12, 1 + dy, hat); R(5, 2 + dy, 13, 4 + dy, hat); R(3, 5 + dy, 15, 5 + dy, hat)
        R(12, 2 + dy, 13, 4 + dy, hat_dk); R(3, 5 + dy, 15, 5 + dy, hat); R(13, 5 + dy, 15, 5 + dy, hat_dk)
        R(9, 1 + dy, 9, 4 + dy, lt(hat, .2)); R(6, 2 + dy, 7, 3 + dy, hat_lt)

    def legs_standing():
        R(6, 22, 8, 25, pa); R(8, 22, 8, 25, pad); R(10, 22, 12, 25, pa); R(12, 22, 12, 25, pad)
        R(5, 26, 8, 28, bt); R(10, 26, 13, 28, bt); R(5, 28, 8, 28, dk(bt, .4)); R(10, 28, 13, 28, dk(bt, .4))
        R(6, 26, 7, 26, lt(bt, .3)); R(11, 26, 12, 26, lt(bt, .3))

    def vest_front(y0=13, y1=20):
        R(5, y0, 13, y1, vs); R(13, y0, 13, y1, vd); R(5, y0, 13, y0, vl)
        R(8, y0, 10, y0, sh); Pt(9, y0 + 1, sh)  # collar
        R(6, y0, 6, y0 + 4, REFLECT); R(12, y0, 12, y0 + 4, dk(REFLECT, .12))  # reflective braces
        R(5, y0 + 5, 13, y0 + 5, REFLECT); R(13, y0 + 5, 13, y0 + 5, dk(REFLECT, .15))
        R(9, y0 + 2, 9, y1, vd)  # zip

    def belt(y=21):
        R(5, y, 13, y, C('#4a3220')); Pt(9, y, C('#d9c27a'))

    if pose in ('waiting', 'done'):
        legs_standing()
        belt()
        vest_front()
        R(12, 20, 13, 23, C('#8a5a2e'))  # tool pouch
        # left arm (viewer's left) hangs or holds the clipboard
        if pose == 'waiting':
            R(3, 13, 4, 19, sh); R(4, 14, 4, 19, shd); R(3, 20, 4, 20, sk)
        else:
            R(3, 13, 4, 16, sh); R(4, 14, 4, 16, shd)
            # clipboard of ticks held at the chest
            R(1, 14, 7, 22, C('#8a5a2e')); R(2, 15, 6, 21, C('#fbfaf6')); R(3, 14, 5, 14, C('#c9ced6'))
            for k, yy in enumerate((16, 18, 20)):
                Pt(2, yy, F['green']); Pt(3, yy + 1, F['green']); Pt(4, yy, F['green'])
                R(5, yy, 6, yy, C('#9aa3b5')) if k < 2 else None
            R(2, 17, 3, 18, sk) if False else None
            R(7, 17, 8, 18, sk)  # fingers round the board edge
        front_head()
        if pose == 'waiting':
            # shouting: brows up, mouth open
            R(7, 8, 7, 9, EYE); R(11, 8, 11, 9, EYE); Pt(7, 8, C('#ffffff')); Pt(11, 8, C('#ffffff'))
            R(6, 7, 7, 7, hrd); R(11, 7, 12, 7, hrd)
            R(8, 10, 10, 11, C('#7a2230')); Pt(9, 10, C('#c0505a'))
            # right arm straight up, the fist above the hat, and the flag
            sw = (-1, 0, 1, 0)[phase % 4]
            R(14, 12, 15, 14, sh); R(15, 6, 16, 11, sh); R(16, 6, 16, 11, shd)
            R(15 + (sw > 0), 2, 16 + (sw > 0), 5, sk); R(16 + (sw > 0), 3, 16 + (sw > 0), 5, sd)
            ang = math.radians((-22, 0, 22, 0)[phase % 4])
            hx, hy = 16.0 + (sw > 0), 6.0
            L = 15
            tx, ty = hx + math.sin(ang) * L, hy - math.cos(ang) * L
            n = 40
            for i in range(n + 1):
                t = i / n
                Pt(round(hx + (tx - hx) * t), round(hy + (ty - hy) * t), C('#6b5a48'))
            Pt(round(tx), round(ty) - 1, C('#d9c27a'))  # finial
            cloth = C('#e5302a')
            for i in range(9):  # the red cloth, rippling away from the pole
                wave = math.sin(i * 0.8 - phase * math.pi / 2) * (0.6 + i * 0.12)
                for j in range(6):
                    px = tx + i - math.sin(ang) * j * 0.0 + math.sin(ang) * j
                    py = ty + j * math.cos(ang) + wave + i * math.sin(ang) * 0.3
                    c = cloth
                    dw = math.cos(i * 0.8 - phase * math.pi / 2)
                    if dw < -0.35:
                        c = dk(cloth, .28)
                    elif dw > 0.55:
                        c = lt(cloth, .2)
                    Pt(round(px + 1), round(py), c)
        else:
            # done: happy eyes, a grin, thumbs up by the shoulder
            for ex in (6, 10):
                Pt(ex, 9, EYE); Pt(ex + 1, 8, EYE); Pt(ex + 2, 9, EYE)
            R(8, 11, 10, 11, dk(sk, .5)); Pt(7, 10, dk(sk, .35)); Pt(11, 10, dk(sk, .35))
            b = (0, -1, -1, 0)[phase % 4]
            R(14, 13, 15, 15, sh); R(15, 9 + b, 16, 14, sh); R(16, 10 + b, 16, 14, shd)
            R(14, 6 + b, 17, 9 + b, sk); R(17, 7 + b, 17, 9 + b, sd); R(14, 7 + b, 14, 8 + b, sd)
            R(15, 2 + b, 16, 5 + b, sk); R(16, 2 + b, 16, 5 + b, sd)  # the thumb
            if phase % 2 == 0:  # sparkle
                cx, cy = 20, 1 + (phase // 2)
                for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
                    Pt(cx + dx, cy + dy, C('#fff3a6') if (dx, dy) == (0, 0) else C('#ffd23a'))
    elif pose == 'working':
        # seated, seen from behind at the bench
        R(5, 13, 13, 21, vs); R(12, 13, 13, 21, vd); R(5, 13, 13, 13, vl)
        R(6, 13, 6, 18, REFLECT); R(12, 13, 12, 18, dk(REFLECT, .12)); R(5, 18, 13, 18, REFLECT)
        R(5, 21, 13, 21, C('#4a3220'))
        R(3, 14, 4, 17, sh); R(3, 14, 3, 17, lt(sh, .15))  # left arm reaching forward
        # back of the head under the hat
        R(5, 7, 13, 10, hr); R(5, 10, 13, 10, hrd); R(7, 11, 11, 12, sd); Pt(4, 8, sk); Pt(14, 8, sd)
        if P['style'] == 'long':
            R(8, 10, 10, 15, hr); R(10, 10, 10, 15, hrd)
        R(6, 1, 12, 1, hat); R(5, 2, 13, 5, hat); R(3, 6, 15, 6, hat); R(12, 2, 13, 5, hat_dk); R(13, 6, 15, 6, hat_dk)
        R(9, 1, 9, 5, lt(hat, .2)); R(6, 2, 7, 4, hat_lt)
        # swinging arm: shoulder -> elbow -> hand per phase, then the tool
        sx, sy = 14, 14
        poses = [((17, 9), (17, 3)), ((18, 11), (21, 8)), ((17, 13), (19, 15)), ((18, 11), (20, 10))]
        (ex, ey), (hx, hy) = poses[phase % 4]

        def put(x, y, c):
            Pt(x, y, c)
        if P['tool'] == 'hammer':
            # handle from the hand away from the shoulder, head across its end
            ang = [-1.9, -0.3, 0.9, -0.6][phase % 4]
            dx, dy = math.cos(ang), math.sin(ang)
            ex2, ey2 = hx + dx * 6, hy + dy * 6
            thick(put, hx, hy, ex2, ey2, C('#9a6a3c'), 1)
            px, py = -dy, dx
            thick(put, ex2 - px * 2, ey2 - py * 2, ex2 + px * 2, ey2 + py * 2, C('#5c646d'), 2)
            Pt(ex2 + px * 2, ey2 + py * 2, C('#c9d0d8'))
        else:
            # trowel: a steel blade below the fist; the other hand holds a brick
            ang = [-1.6, -0.6, 0.6, -0.2][phase % 4]
            dx, dy = math.cos(ang), math.sin(ang)
            ex2, ey2 = hx + dx * 3, hy + dy * 3
            thick(put, hx, hy, ex2, ey2, C('#9a6a3c'), 1)
            for k in range(4):
                for w in range(-k, k + 1):
                    Pt(ex2 + dx * k - dy * w * 0.6, ey2 + dy * k + dx * w * 0.6, C('#c9d0d8') if w < 0 else C('#8d96a0'))
            R(0, 12, 3, 13, F['brick'][0]); R(0, 13, 3, 13, dk(F['brick'][0], .25)); R(2, 14, 3, 14, sk)
        thick(put, sx, sy, ex, ey, sh, 2)
        thick(put, ex, ey, hx, hy, sh, 2)
        R(hx - 0, hy - 0, hx + 1, hy + 1, sk)
        if phase % 4 == 2:  # impact: sparks (hammer) or a puff of mortar (trowel)
            cx, cy = 20, 16
            pts = ((2, -2), (3, 0), (1, -4), (4, -3), (-1, -3), (3, 2), (5, -1)) if P['tool'] == 'hammer' else \
                ((1, -1), (2, -2), (3, -1), (2, 0), (0, -2), (4, -2))
            for i, (dx, dy) in enumerate(pts):
                Pt(cx + dx, cy + dy, (SPARK if P['tool'] == 'hammer' else DUST)[i % (3 if P['tool'] == 'hammer' else 2)])
        if phase % 4 == 3:  # dying sparks / settling dust
            cx, cy = 21, 15
            for i, (dx, dy) in enumerate(((3, -3), (5, -1), (2, 1))):
                Pt(cx + dx, cy + dy, (SPARK[2] if P['tool'] == 'hammer' else DUST[1]))
    else:
        # idle: turned round on the seat, slumped back, knees splayed, arms crossed, head lolled, hat tipped over the face
        def put(x, y, c):
            Pt(x, y, c)
        thick(put, 6, 20, 2, 23, pa, 3); thick(put, 2, 23, 1, 26, pa, 3)
        thick(put, 10, 20, 14, 23, pa, 3); thick(put, 15, 23, 16, 26, pa, 3)
        Pt(3, 23, pad); Pt(16, 23, pad)  # knees
        R(-1, 27, 3, 28, bt); R(15, 27, 19, 28, bt); R(-1, 28, 3, 28, dk(bt, .4)); R(15, 28, 19, 28, dk(bt, .4))
        R(0, 27, 1, 27, lt(bt, .3)); R(16, 27, 17, 27, lt(bt, .3))
        vest_front(15, 21)
        R(5, 21, 13, 21, C('#4a3220'))
        R(3, 15, 4, 19, sh); R(14, 15, 15, 19, shd)  # upper arms
        R(4, 17, 14, 19, sh); R(4, 19, 14, 19, shd); R(5, 17, 13, 17, lt(sh, .12))  # crossed forearms
        Pt(14, 17, sk); Pt(4, 18, sk)
        # head lolled to one side, only the chin under the brim
        R(5, 11, 11, 13, sk); R(11, 11, 11, 13, sd); R(6, 14, 10, 14, sd)
        if P['style'] == 'beard':
            R(5, 12, 11, 14, hr)
        if P['style'] == 'long':
            R(3, 10, 4, 15, hr); R(12, 10, 13, 15, hrd)
        R(7, 13, 8, 13, dk(sk, .55))  # mouth, slack
        R(5, 4, 10, 4, hat); R(4, 5, 11, 8, hat); R(10, 5, 11, 8, hat_dk); R(7, 4, 7, 8, lt(hat, .2)); R(5, 5, 6, 6, hat_lt)
        thick(put, 1, 10, 14, 7, hat, 1); thick(put, 1, 11, 14, 8, hat_dk, 1)  # tilted brim
        R(3, 9, 12, 9, hat); Pt(2, 10, hat); Pt(13, 8, hat)
        R(5, 10, 11, 10, dk(sk, .45))
    outline_out(s, INK)
    return s


def person_frames(P, pose):
    """The phases of one pose, cropped to their common box so every phase has the same size and anchor."""
    n = PHASES if pose != 'idle' else 1
    sp = [person(P, pose, ph) for ph in range(n)]
    x0 = min(p.img.getbbox()[0] for p in sp)
    y0 = min(p.img.getbbox()[1] for p in sp)
    x1 = max(p.img.getbbox()[2] for p in sp)
    y1 = max(p.img.getbbox()[3] for p in sp)
    out = []
    for p in sp:
        c = Spr(x1 - x0, y1 - y0, p.ax - x0, p.ay - y0)
        c.img = p.img.crop((x0, y0, x1, y1))
        c.em = p.em.crop((x0, y0, x1, y1))
        c._load()
        out.append(c)
    return out


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
    for vi, P in enumerate(CREW):
        for st in STATES:
            for ph, sp in enumerate(person_frames(P, st)):
                fr[f'crew{vi}_{st}_{ph}_f'] = sp
    for st in STATES:
        fr[f'bubble_{st}'] = SP.bubble(st)
    out = f'{OUT}/f'
    os.makedirs(out, exist_ok=True)
    SP.pack(fr, out)
    return fr


# ---------------------------------------------------------------- mockup (the scene of scenes.py, theme F frames)


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
        pv = si % 6
        add(5, U + V + 15, 'bench_rw_f' if o == 'rw' else 'brickwall_lw_f', U, V)
        cu, cv = (U + 10, V + 12.5) if o == 'rw' else (U + 12.5, V + 10)
        add(5, cu + cv, 'bucket_f' if o == 'rw' else 'cinder_lw_f', cu, cv)
        ph = phase if state != 'idle' else 0
        name = f'crew{pv}_{state}_{ph}_f'
        if state in ('working', 'idle'):
            add(5, cu + cv + 0.1, name, cu, cv, 7)
            X, Y = SC.proj(cu, cv, 7)
        else:
            pu, pvv = (U + 16, V + 22) if o == 'lw' else ((U + 15, V + 19) if di == 1 else (U - 3, V + 16))
            add(5, pu + pvv, name, pu, pvv)
            X, Y = SC.proj(pu, pvv)
        people.append((state, X, Y - fr[name].ay))
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
    # every person frame at x6 for a close look
    rows = []
    for vi in range(len(CREW)):
        row = []
        for st in STATES:
            for ph in range(PHASES if st != 'idle' else 1):
                row.append(fr[f'crew{vi}_{st}_{ph}_f'])
        rows.append(row)
    cw = max(f.w for r in rows for f in r) + 4
    chh = max(f.h for r in rows for f in r) + 4
    sheet = Image.new('RGBA', (cw * max(len(r) for r in rows), chh * len(rows)), (120, 104, 84, 255))
    for ri, r in enumerate(rows):
        for ci, f in enumerate(r):
            sheet.alpha_composite(f.img, (ci * cw + 2, ri * chh + chh - 2 - f.h))
    sheet = sheet.resize((sheet.width * 6, sheet.height * 6), Image.NEAREST)
    sheet.convert('RGB').save(f'{OUT}/F-crew.png')
    print('wrote', f'{OUT}/F-crew.png')


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
