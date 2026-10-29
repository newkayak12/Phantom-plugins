"""Theme D - Restroom Cleaning (화장실 청소): sprites, sheet (OUT/d/) and a mockup.

Imports the shared helpers of pix.py / sprites.py and changes none of them, so the sheets of A, B and C stay byte-identical.
Desks are toilet + sink units, chairs are mop buckets, people are janitors in coveralls and rubber gloves, drawn front-facing
in every state: WORKING mops the floor (4 frames, the mop swings left and right with suds), WAITING holds a plunger up high,
DONE gives a thumbs-up among sparkles, IDLE dozes leaning on the mop (2 frames, a snot bubble swells and shrinks).

Run from ai-visualization/art:  PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" theme_d.py   (writes OUT/d/ and OUT/D-restroom.png)
"""
import math
import os
from PIL import Image, ImageDraw, ImageFont
from pix import C, dk, lt, mix, H, Spr, part, outline, outline_out, box, obox, M, INK
import sprites as SP

OUT = SP.OUT
SEG, W_H = SP.SEG, SP.W_H

D = dict(
    key='D',
    tile=C('#f3f7f7'), tile_alt=C('#e6eff0'), tile_teal=C('#8fd3cb'), grout=C('#b4c5c8'),
    base_side=C('#7d9aa0'),
    wall_lo=[C('#47ada3'), C('#3fa59b'), C('#4fb5ab')], wall_lo_grout=C('#2f857d'),
    wall_hi=[C('#f4f8f8'), C('#eaf1f2')], wall_hi_grout=C('#c9d8da'),
    band=C('#22766f'), baseboard=C('#1f6862'), trim=C('#ffffff'), wall_cap=C('#ffffff'),
    chrome=C('#c9d3da'), chrome_dk=C('#8f9ca8'),
    glass=[C('#d9eef4'), C('#c4e1ea'), C('#abd0dc')],
    china=C('#fbfcfd'), china_l=C('#dfe7ec'), china_r=C('#c2cdd6'),
    water=C('#6cc6d8'), water_dk=C('#3fa4bd'),
    rugs=[(C('#6f8196'), C('#4b5a6c'), C('#36414f')),
          (C('#39a596'), C('#23786c'), C('#185a51')),
          (C('#c8553d'), C('#9c3a27'), C('#6e271a'))],
    plate=C('#ffd23a'), plate_frame=C('#2b2b2b'), post=C('#3a3a3a'),
    pot=C('#f2f4f6'), leaf=[C('#2a5f36'), C('#43914a'), C('#76c35f')],
)

# janitors: coverall, gloves, boots, skin, hair, headwear
PEOPLE_D = [
    dict(uni=C('#3d63b0'), glove=C('#ffd02e'), boot=C('#2d3140'), skin=C('#f1c39b'), hair=C('#5b3a26'), hat='cap', hat_c=C('#2c4a8a'), style='short'),
    dict(uni=C('#2f9a7e'), glove=C('#ff8fb1'), boot=C('#2a2f2c'), skin=C('#f7d6b8'), hair=C('#e8a93d'), hat='bandana', hat_c=C('#e2463f'), style='long'),
    dict(uni=C('#e07a2e'), glove=C('#ffd02e'), boot=C('#2b2522'), skin=C('#8d5a3b'), hair=C('#1e1a22'), hat=None, hat_c=None, style='short'),
    dict(uni=C('#5f6f86'), glove=C('#4fb4ff'), boot=C('#25262c'), skin=C('#c88d63'), hair=C('#2a2636'), hat=None, hat_c=None, style='bun'),
    dict(uni=C('#8a4fa8'), glove=C('#ffd02e'), boot=C('#2a2430'), skin=C('#e0ad85'), hair=C('#7a2f1e'), hat='cap', hat_c=C('#f2f2f2'), style='short'),
    dict(uni=C('#3ba3d9'), glove=C('#7ed957'), boot=C('#23303a'), skin=C('#f3cfae'), hair=C('#b9bcc4'), hat='bandana', hat_c=C('#2f6fd0'), style='short'),
]
STATES = ('working', 'waiting', 'done', 'idle')
PHASES = dict(working=4, waiting=2, done=2, idle=2)  # distinct frames per state; the theme maps phase -> phase % n
SEAT_Z = 7  # OfficeLayout.SEAT_Z: the layout lifts working/idle people this high (onto the chair seat)
DROP = 0  # how far below their anchor working/idle janitors' boots are; 0: they stand at the toilet, SEAT_Z up the screen


# ---------------------------------------------------------------- floor + walls
def floor_tile(var=0):
    """16-unit tile of 4-unit bathroom mosaic: white tiles, a teal one on a diagonal, grey grout."""
    s = Spr(70, 40, 33, 2)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if a % 4 < 0.5 or d % 4 < 0.5:
            return D['grout']
        i, j = int(a // 4), int(d // 4)
        if (i + j + var) % 4 == 0 and (i - j) % 2 == 0:
            tone = D['tile_teal']
        else:
            tone = D['tile'] if (i + j) % 2 == 0 else D['tile_alt']
        if (a % 4) < 1.0 or (d % 4) < 1.0:
            return lt(tone, .35)
        if (a % 4) > 3.0 and (d % 4) > 3.0:
            return dk(tone, .04)
        return tone
    box(s, 0, 0, 0, SEG, SEG, 0, tex)
    return s.crop()


def mirror_px(s_, z):
    """A mirror (in a window segment) at along s_, height z, or None."""
    x0, x1, z0, z1 = 3.0, 13.0, 22, 50
    if x0 - 0.5 <= s_ < x1 + 0.5 and z0 - 2 <= z < z0:  # glass shelf
        return D['glass'][0] if z >= z0 - 1 else D['chrome_dk']
    if not (x0 <= s_ < x1 and z0 <= z < z1):
        return None
    x, y, w, hh = s_ - x0, z - z0, x1 - x0, z1 - z0
    if x < 0.5 or x >= w - 0.5 or y < 1 or y >= hh - 1:
        return D['chrome'] if y >= 1 else D['chrome_dk']
    if x < 1.0 or y >= hh - 2:
        return lt(D['chrome'], .4)
    t = y / hh
    col = D['glass'][0] if t > 0.66 else D['glass'][1] if t > 0.33 else D['glass'][2]
    g = (x * 2 + y) % 15
    if g < 1.4 or 3.0 <= g < 3.6:
        col = lt(col, .7)
    return col


def wall_tex(seg_seed, mirror=False):
    def tex(role, a, d, z, A_, D_, Hh, sf):
        if sf == 'T':
            return D['wall_cap'] if d < D_ - 0.5 else dk(D['wall_cap'], .08)
        inner = (sf == 'L' and d >= D_ - 0.01) or (sf == 'R' and a >= A_ - 0.01)
        s_ = a if sf == 'L' else d
        if not inner:
            return dk(D['wall_cap'], .3) if z < Hh - 1 else D['wall_cap']
        dim = 0.0 if sf == 'L' else 0.1
        if mirror:
            c = mirror_px(s_, z)
            if c is not None:
                return dk(c, dim * 0.5)
        if z < 4:
            return dk(lt(D['baseboard'], .2) if z >= 3 else D['baseboard'], dim)
        if z >= Hh - 3:
            return dk(D['trim'] if z < Hh - 1 else lt(D['band'], .5), dim + 0.02)
        if 24 <= z < 27:
            return dk(lt(D['band'], .2) if z >= 26 else D['band'], dim)
        lo = z < 24
        zz = z - (4 if lo else 27)
        row = int(zz // 5)
        off = 0
        if zz % 5 < 0.6 or (s_ + off) % 4 < 0.35:
            return dk(D['wall_lo_grout'] if lo else D['wall_hi_grout'], dim)
        tones = D['wall_lo'] if lo else D['wall_hi']
        c = tones[int(H('wt', seg_seed, row, int(s_ // 4)) * len(tones))]
        if zz % 5 >= 4.0:
            c = lt(c, .18)
        return dk(c, dim + (0.02 if not lo else 0))
    return tex


def wall_seg(side, seed, mirror=False):
    s = Spr(110, 120, 55, 80)
    tex = wall_tex(seed, mirror)
    if side == 'rw':
        box(s, 0, -3, 0, SEG, 3, W_H, tex, 'TL')
    else:
        box(s, -3, 0, 0, 3, SEG, W_H, tex, 'TR')
    return s.crop()


# ---------------------------------------------------------------- toilet + sink unit ("desk")
def china(top=None):
    return M(top or D['china'], D['china_l'], D['china_r'])


def oval(t, o, ca, cd, ra, rd, z0, z1, top, side=None):
    """A rounded solid: ovals stacked from z0 to z1 (each lower one shows as the side wall, shaded by facing), [top] on top.
    top(x, y) gets the point's position in the oval (-1..1 each) and returns a color or None."""
    def ring(z):
        def tex(role, a, d, zz, A_, D_, Hh, sf):
            x, y = (a - ra) / ra, (d - rd) / rd
            if x * x + y * y > 1:
                return None
            if z == z1:
                return top(x, y)
            c = side or D['china_l']
            return c if y > x * 0.7 else dk(c, .14) if y > -0.3 else lt(c, .05)
        return tex
    for z in range(z0, z1 + 1):
        obox(t, o, ca - ra, cd - rd, z, 2 * ra, 2 * rd, 0, ring(z), 'T')


def desk(o='rw', seed=0):
    """20x10 units like A's desk: a pedestal sink (along 1..8) and a toilet with its seat and lid up (along 11..19), plus a
    toilet brush in its holder. Along 'a' grows to the right on screen for rw and to the left for lw, so the toilet is always
    on the open side of the janitor who stands in front of the unit's middle."""
    s = Spr(140, 110, 70, 50)
    cm = M(D['chrome'], D['chrome'], D['chrome_dk'])
    seat = C('#7cc9c0')
    # --- sink: pedestal, oval basin, faucet, soap
    part(s, lambda t: oval(t, o, 5, 5, 1.3, 1.3, 0, 9, lambda x, y: D['china']), .35)

    def basin_top(x, y):
        r = x * x + y * y
        if r > 0.62:
            return D['china'] if r < 0.85 or y + x < 0 else lt(D['china'], .2)
        if r > 0.42:
            return D['china_r']
        return D['water'] if x - y < 0.5 else lt(D['water'], .4)
    part(s, lambda t: oval(t, o, 5, 5.2, 3.6, 3, 9, 12, basin_top), .38)
    part(s, lambda t: (obox(t, o, 4.5, 1.6, 12, 1, 1, 3, cm), obox(t, o, 4.5, 1.6, 14.6, 1, 2.2, 0.8, cm)), .3)
    part(s, lambda t: (obox(t, o, 7.2, 1.8, 11, 1.4, 1.4, 4, M(C('#ff8fb1'))), obox(t, o, 7.6, 2.2, 15, 0.6, 0.6, 1.2, M(C('#f4f4f4')))), .35)
    # --- toilet: tank, seat lid up, oval bowl with seat ring and water, brush
    part(s, lambda t: oval(t, o, 15, 7, 1.6, 1.4, 0, 4, lambda x, y: D['china']), .35)
    part(s, lambda t: obox(t, o, 12.5, 0.6, 5, 5, 2.6, 11, china()), .4)
    part(s, lambda t: (obox(t, o, 12.2, 0.4, 16, 5.6, 3, 1, china()), obox(t, o, 12.9, 1.0, 13, 0.6, 0.3, 0.8, cm)), .35)

    def bowl_top(x, y):
        r = x * x + y * y
        if r > 0.6:
            return seat if y - x < 0.6 else dk(seat, .15)
        if r > 0.45:
            return D['china_r']
        return D['water'] if x - y < 0.4 else lt(D['water'], .4)
    part(s, lambda t: oval(t, o, 15, 6.4, 3, 3.4, 4, 8, bowl_top), .4)

    def lid(role, a, d, z, A_, D_, Hh, sf):
        if role == 'F':
            e = ((a - A_ / 2) / (A_ / 2)) ** 2 + (max(0, z - Hh * 0.45) / (Hh * 0.6)) ** 2
            if e > 1:
                return None
            return lt(seat, .2) if e < 0.45 else seat
        if role == 'T':
            return None
        return dk(seat, .2)
    part(s, lambda t: obox(t, o, 12.4, 2.9, 8, 5.2, 0.6, 9, lid), .4)
    # brush in its holder
    part(s, lambda t: (obox(t, o, 18.6, 1.2, 0, 1.6, 1.6, 4, M(C('#5f6c7d'))),
                       obox(t, o, 19.1, 1.7, 4, 0.6, 0.6, 6, M(C('#f2f4f6'))),
                       obox(t, o, 18.9, 1.5, 10, 1, 1, 1, M(C('#2fb3a7')))), .35)
    return s.crop()


def bucket(o='rw'):
    """The "chair": a yellow mop bucket on wheels with a wringer, set beside the spot (left of it for rw, right for lw) so the
    janitor standing on the spot keeps it in view."""
    s = Spr(70, 50, 35, 30)
    du, dv = (-4.5, 4) if o == 'rw' else (4, -4.5)
    yel = C('#f5c518')
    part(s, lambda t: [obox(t, 'rw', du + x, dv + y, 0, 1, 1, 1, M(C('#2b2b30'))) for x, y in ((-2.6, -2.2), (1.6, -2.2), (-2.6, 1.2), (1.6, 1.2))], .2)

    def body(role, a, d, z, A_, D_, Hh, sf):
        if role == 'T':
            if 0.6 <= a < A_ - 0.6 and 0.6 <= d < D_ - 0.6:
                return C('#7fb3bf') if H('bw', int(a * 2), int(d * 2)) > 0.12 else C('#e9f6f8')
            return lt(yel, .25)
        c = yel if sf == 'L' else dk(yel, .22)
        if 4 <= z < 5:
            return dk(c, .2)
        return lt(c, .15) if z >= Hh - 1 else c
    part(s, lambda t: obox(t, 'rw', du - 3, dv - 2.5, 1, 6, 5, 7, body), .45)
    part(s, lambda t: (obox(t, 'rw', du + 1, dv - 2.7, 7, 2.4, 5.4, 3, M(C('#4a5361'))),
                       obox(t, 'rw', du + 2.6, dv - 0.6, 10, 0.8, 0.8, 7, M(C('#7a8494')))), .35)
    return s.crop()


# ---------------------------------------------------------------- wall decor / furniture
def urinals(o='rw'):
    s = Spr(90, 90, 45, 60)
    for a0 in (2.0, 10.0):
        def u_tex(role, a, d, z, A_, D_, Hh, sf):
            if role == 'F':
                if 0.8 <= a < A_ - 0.8 and 2 <= z < Hh - 3:
                    return C('#d3e1e7') if z > 4 else C('#bccdd5')
                if 1.5 <= a < A_ - 1.5 and 1 <= z < 2:
                    return D['chrome_dk']
            if role == 'T' and 0.8 <= a < A_ - 0.8 and d >= 0.8:
                return D['china_l']
            return china()(role, a, d, z, A_, D_, Hh, sf)
        part(s, lambda t, a0=a0: obox(t, o, a0, 0, 8, 4.5, 3, 14, u_tex), .4)
        part(s, lambda t, a0=a0: (obox(t, o, a0 + 1.8, 0.3, 22, 0.9, 0.9, 7, M(D['chrome'], D['chrome'], D['chrome_dk'])),
                                  obox(t, o, a0 + 1.5, 0, 28.5, 1.5, 1.4, 1.2, M(D['chrome'], D['chrome'], D['chrome_dk']))), .3)
    part(s, lambda t: obox(t, o, 7.7, 0, 5, 0.7, 6, 26, M(C('#9fcfc9'), C('#8cc2bb'), C('#6fa7a0'))), .4)
    return s.crop()


def hand_dryer(o='rw'):
    s = Spr(30, 30, 15, 20)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role == 'F' and z >= Hh - 2 and 1 <= a < 2:
            return C('#3fd0ff')
        if role == 'F' and z < 1:
            return C('#3a3f4a')
        return M(C('#eef1f4'), C('#d5dbe2'), C('#aeb7c2'))(role, a, d, z, A_, D_, Hh, sf)
    part(s, lambda t: obox(t, o, 0, 0, 0, 5, 3, 6, tex), .4)
    part(s, lambda t: obox(t, o, 2, 1, -2, 1.2, 1.2, 2, M(C('#9aa3ae'))), .35)
    return s.crop()


def towel_dispenser(o='rw'):
    s = Spr(30, 40, 15, 28)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role == 'F':
            if 1.5 <= a < A_ - 1.5 and 4 <= z < 7:
                return C('#9ab8c8') if z < 5.5 else C('#c8dbe5')
            if z < 1:
                return C('#4a505e')
        return M(C('#f4f6f8'), C('#dfe4e9'), C('#b9c2cc'))(role, a, d, z, A_, D_, Hh, sf)
    part(s, lambda t: obox(t, o, 0, 0, 0, 6, 2.5, 9, tex), .4)
    part(s, lambda t: obox(t, o, 1.5, 1.2, -3, 3, 0.4, 3, M(C('#ffffff'), C('#f1efe8'), C('#d9d5ca'))), .3)
    return s.crop()


def supply_shelf(o='lw', seed=0):
    """Wire shelf (A's bookshelf footprint): toilet rolls, spray bottles, folded towels."""
    s = Spr(80, 90, 40, 70)
    steel = C('#aeb8c4')
    bottles = [C('#2f86de'), C('#38b26a'), C('#f08a3a'), C('#d94f8a'), C('#f5c518')]

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return M(steel)(role, a, d, z, A_, D_, Hh, sf)
        if a < 0.6 or a >= A_ - 0.6:
            return steel
        for sz in (0, 13, 26, 39):
            if sz <= z < sz + 1.2:
                return lt(steel, .2) if z >= sz + 0.6 else steel
        k = int(z // 13)
        zin = z - k * 13 - 1.2
        x = a - 0.6
        kind = (k + seed) % 3
        if kind == 0:  # toilet rolls: 2.4 wide, two high
            ri = int(x // 2.4)
            fx = x % 2.4
            if zin < 9.5 and fx < 2.2:
                c = C('#ffffff') if fx < 1.4 else C('#dfe3e8')
                if 1.8 <= zin % 4.8 < 2.6 and 0.7 <= fx < 1.4:
                    c = C('#9aa3ae')
                return c if zin % 4.8 < 4.4 else C('#c3c9d1')
        elif kind == 1:  # spray bottles
            bi = int(x // 2.1)
            fx = x % 2.1
            if fx < 1.6:
                col = bottles[int(H('bt', seed, k, bi) * len(bottles))]
                if zin < 6:
                    return lt(col, .3) if fx < 0.5 else col
                if zin < 8 and 0.4 <= fx < 1.2:
                    return C('#f2f4f6')
                if 8 <= zin < 9 and fx < 1.4:
                    return C('#f2f4f6')
        else:  # folded towels
            if zin < 8:
                band = int(zin // 2)
                c = [C('#ffffff'), C('#bfe7e2'), C('#ffffff'), C('#8fd3cb')][band % 4]
                return dk(c, .08) if zin % 2 < 0.5 else c
        return dk(steel, .55)
    part(s, lambda t: obox(t, o, 0, 0, 0, 14, 6, 40, tex), .45)
    part(s, lambda t: obox(t, o, 3, 1, 40, 4, 4, 4, M(C('#f5c518'))), .4)
    part(s, lambda t: obox(t, o, 9, 1.5, 40, 3, 3, 6, M(C('#2f86de'))), .4)
    return s.crop()


def vanity(o='lw'):
    """Sink counter (A's coffee station footprint) with two basins, faucets and soap pumps."""
    s = Spr(90, 90, 45, 64)
    cab = C('#2f9a90')

    def ctex(role, a, d, z, A_, D_, Hh, sf):
        if role == 'T':
            for ca in (4, 12):
                if (a - ca) ** 2 / 6.0 + (d - 4.6) ** 2 / 4.0 < 1:
                    return D['water'] if (a - ca) ** 2 / 2.0 + (d - 4.6) ** 2 / 1.2 < 1 else D['china_l']
            return lt(C('#f4f6f8'), .3) if (a >= A_ - 0.5 or d >= D_ - 0.5) else C('#eef2f4')
        if role != 'F':
            return M(cab)(role, a, d, z, A_, D_, Hh, sf)
        if z >= Hh - 1.5:
            return C('#f4f6f8') if z >= Hh - 0.8 else C('#dfe5ea')
        if z < 1.5:
            return dk(cab, .35)
        if abs(a - A_ / 2) < 0.25 or a < 0.5 or a >= A_ - 0.5:
            return dk(cab, .25)
        if 8 <= z < 9 and (A_ / 2 - 1.5 <= a < A_ / 2 - 0.75 or A_ / 2 + 0.75 <= a < A_ / 2 + 1.5):
            return D['chrome']
        return cab
    part(s, lambda t: obox(t, o, 0, 0, 0, 16, 8, 13, ctex), .45)
    cm = M(D['chrome'], D['chrome'], D['chrome_dk'])
    for ca in (4, 12):
        part(s, lambda t, ca=ca: (obox(t, o, ca - 0.5, 0.6, 13, 1, 1, 3.5, cm), obox(t, o, ca - 0.5, 0.6, 16, 1, 2.4, 0.8, cm)), .3)
    for ca, col in ((8, C('#ff8fb1')), (15, C('#7fd0ff'))):
        part(s, lambda t, ca=ca, col=col: (obox(t, o, ca - 0.7, 0.8, 13, 1.4, 1.4, 4, M(col)),
                                           obox(t, o, ca - 0.3, 1.2, 17, 0.6, 0.6, 1.2, M(C('#f4f4f4')))), .35)
    return s.crop()


def wet_floor_sign():
    """Yellow A-frame 'wet floor' sign with a black slip pictogram."""
    s = Spr(40, 50, 20, 40)
    yel = C('#ffd200')

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role == 'T':
            return lt(yel, .3)
        half = (A_ / 2) * (1 - z / (Hh * 1.9))
        if abs(a - A_ / 2) > half and role == 'F':
            return None
        c = yel if sf == 'L' else dk(yel, .22)
        if role == 'F':
            if z < 1 or z >= Hh - 1.2 or abs(a - A_ / 2) > half - 0.5:
                return dk(yel, .3)
            # pictogram: a falling figure in a triangle
            x, y = a - A_ / 2, z - 3
            if 0 <= y < 8 and abs(x) * 2 < 8 - y:
                if abs(x) * 2 >= 6.6 - y:
                    return C('#1b1b1b')
                if (x + 0.3) ** 2 + (y - 5.3) ** 2 < 0.5 or (abs(x - y * 0.3 + 0.6) < 0.4 and 1 <= y < 4.5):
                    return C('#1b1b1b')
        return c
    part(s, lambda t: obox(t, 'rw', -3, -1.5, 0, 6, 3, 14, tex), .45)
    part(s, lambda t: obox(t, 'rw', -0.5, -1.7, 14, 1, 3.4, 1, M(C('#2b2b2b'))), .3)
    return s.crop()


def bin_():
    s = Spr(30, 40, 15, 28)
    st = C('#c3ccd6')

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role == 'T':
            return C('#9aa6b2')
        c = st if sf == 'L' else dk(st, .25)
        if role == 'F' or sf == 'L':
            g = a if sf == 'L' else d
            if g < 1:
                c = lt(c, .35)
        return dk(c, .15) if z >= Hh - 1.5 else c
    part(s, lambda t: obox(t, 'rw', -2.5, -2.5, 0, 5, 5, 9, tex), .45)
    part(s, lambda t: obox(t, 'rw', -0.6, 2.5, 0, 1.2, 1.4, 1, M(C('#4a5361'))), .3)
    return s.crop()


def cart():
    """Janitor's cart: grey body, yellow trash bag, bottles on top, a broom standing in it."""
    s = Spr(60, 70, 30, 50)
    part(s, lambda t: [obox(t, 'rw', x, y, 0, 1, 1, 1.2, M(C('#2b2b30'))) for x, y in ((-4, -2.5), (3, -2.5), (-4, 1.5), (3, 1.5))], .2)
    part(s, lambda t: obox(t, 'rw', -4.5, -2.8, 1, 9, 5.6, 2, M(C('#5f6c7d'))), .4)
    part(s, lambda t: obox(t, 'rw', -4, -2.4, 3, 4, 4.8, 9, M(C('#f5c518'), C('#e0b10f'), C('#b88e05'))), .4)
    part(s, lambda t: obox(t, 'rw', 0.2, -2.6, 3, 4.2, 5.2, 9, M(C('#6f7d90'))), .4)
    part(s, lambda t: (obox(t, 'rw', 1, -1.6, 12, 1.4, 1.4, 4, M(C('#2f86de'))),
                       obox(t, 'rw', 2.8, 0.2, 12, 1.4, 1.4, 4, M(C('#38b26a'))),
                       obox(t, 'rw', 1.3, -1.3, 16, 0.8, 0.8, 1, M(C('#f2f4f6'))),
                       obox(t, 'rw', 3.1, 0.5, 16, 0.8, 0.8, 1, M(C('#f2f4f6')))), .35)
    part(s, lambda t: (obox(t, 'rw', -2.2, -0.6, 12, 0.7, 0.7, 20, M(C('#b0753f'))),
                       obox(t, 'rw', -3.4, -1.8, 9, 3, 3, 3, M(C('#c8c3b8')))), .35)
    return s.crop()


def rug(i, A_=24, D_=16):
    """A rubber drain mat: raised rim, a field of drain holes."""
    s = Spr(4 * (A_ + D_) + 10, 2 * (A_ + D_) + 10, 2 * D_ + 5, 5)
    field, rim, hole = D['rugs'][i]

    def tex(role, a, d, z, AA, DD, Hh, sf):
        if sf != 'T':
            return dk(rim, .2)
        e = min(a, d, AA - a, DD - d)
        if e < 0.6:
            return dk(rim, .15)
        if e < 1.6:
            return lt(rim, .12) if (a < 1.6 or d < 1.6) else rim
        if e < 2.2:
            return field
        if (a - 2.2) % 2.5 < 1.0 and (d - 2.2) % 2.5 < 1.0:
            return hole
        if (a - 2.2) % 2.5 < 1.5 and (d - 2.2) % 2.5 < 1.5 and ((a - 2.2) % 2.5 >= 1.0 or (d - 2.2) % 2.5 >= 1.0):
            return lt(field, .2)
        return field
    box(s, 0, 0, 0, A_, D_, 1, tex)
    return s.crop()


def nameplate(w):
    """A's sign board geometry (so NameplateSlice(12, 12, 12) holds) as a yellow cleaning sign with hazard-striped caps."""
    h = 10
    s = Spr(w + 4, h + 8, (w + 4) // 2, h + 7)
    fr, pl = D['plate_frame'], D['plate']
    for x in (w // 2 - 6, w // 2 + 6):
        s.rect(x + 1, h, x + 2, h + 6, D['post'])
        s.rect(x, h + 6, x + 3, h + 6, dk(D['post'], .3))
    s.rect(1, 0, w + 2, h, fr)
    s.rect(2, 1, w + 1, h - 1, pl)
    for x0 in (2, w - 7):  # hazard caps, 8 px each
        for y in range(1, h):
            for x in range(x0, x0 + 8):
                if (x + y) % 6 < 3:
                    s.put(x, y, C('#2b2b2b'))
        s.rect(x0 + 8 if x0 == 2 else x0 - 1, 1, x0 + 8 if x0 == 2 else x0 - 1, h - 1, dk(pl, .25))
    s.rect(11, 1, w - 8, 1, lt(pl, .5))
    s.rect(11, h - 1, w - 8, h - 1, dk(pl, .12))
    outline_out(s, INK)
    return s


# ---------------------------------------------------------------- people
EYE = C('#2a2238')
FW, FH, CX, FY = 48, 60, 24, 52  # person canvas, body centre column, bottom row of the boots


class Fig:
    """Layers of one janitor frame, drawn relative to (CX, FY): x right, y up negative."""

    def __init__(s):
        s.layers = []
        s.new()

    def new(s):
        s.cur = Spr(FW, FH)
        s.layers.append(s.cur)
        return s

    def R(s, x0, y0, x1, y1, c):
        s.cur.rect(CX + min(x0, x1), FY + min(y0, y1), CX + max(x0, x1), FY + max(y0, y1), c)

    def P(s, x, y, c):
        s.cur.put(CX + x, FY + y, c)

    def line(s, x0, y0, x1, y1, c, w=1):
        n = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
        for i in range(n + 1):
            t = i / n
            x, y = round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t)
            s.R(x, y, x + w - 1, y, c)

    def flatten(s):
        """Composite the layers; each layer gets a dark ring where it lies over the layers below, so arms read over the body."""
        out = Spr(FW, FH)
        for L in s.layers:
            src = L.img.load()
            base = out.img.load()
            for y in range(FH):
                for x in range(FW):
                    if src[x, y][3] != 0 or base[x, y][3] == 0:
                        continue
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        nx, ny = x + dx, y + dy
                        if 0 <= nx < FW and 0 <= ny < FH and src[nx, ny][3] > 0:
                            base[x, y] = dk(base[x, y], .45)
                            break
            out.img.alpha_composite(L.img)
            out._load()
        return out


def body(f, P, lean=0, arms='down'):
    """Boots, legs, coverall torso (shifted [lean] px), the arms that hang (per [arms])."""
    u, ud, uh = P['uni'], dk(P['uni'], .25), lt(P['uni'], .2)
    b = P['boot']
    f.R(-3, -6, -1, -2, u); f.R(-1, -6, -1, -2, ud)
    f.R(1, -6, 3, -2, u); f.R(3, -6, 3, -2, ud)
    f.R(-4, -1, -1, 0, b); f.R(1, -1, 4, 0, b)
    f.R(-3, -1, -2, -1, lt(b, .3)); f.R(2, -1, 3, -1, lt(b, .3))
    L = lean
    f.R(-4 + L, -15, 4 + L, -7, u); f.R(4 + L, -15, 4 + L, -7, ud); f.R(-4 + L, -15, 4 + L, -15, uh)
    f.R(-4 + L, -7, 4 + L, -7, dk(u, .45)); f.P(0 + L, -7, C('#e8c25a'))  # belt + buckle
    f.R(0 + L, -14, 0 + L, -8, dk(u, .14))  # zipper
    f.R(-3 + L, -12, -2 + L, -11, ud); f.R(-3 + L, -12, -2 + L, -12, dk(u, .35))  # chest pocket
    f.R(2 + L, -12, 3 + L, -12, C('#f4f4f4'))  # name patch
    f.R(-4 + L, -10, 4 + L, -10, lt(u, .45))  # reflective stripe
    f.R(-1 + L, -15, 1 + L, -15, P['skin']); f.P(L, -14, P['skin'])  # collar opening
    f.P(-2 + L, -15, lt(u, .5)); f.P(2 + L, -15, lt(u, .5))
    if 'L' in arms:
        f.R(-6 + L, -15, -5 + L, -9, u); f.R(-5 + L, -14, -5 + L, -9, ud); f.R(-6 + L, -8, -5 + L, -7, P['glove'])
    if 'R' in arms:
        f.R(5 + L, -15, 6 + L, -9, u); f.R(6 + L, -14, 6 + L, -9, ud); f.R(5 + L, -8, 6 + L, -7, P['glove'])


def head(f, P, expr, dx=0, dy=0):
    """A's front head (11 rows) moved by (dx, dy), with headwear and an expression."""
    h_, hd, hh = P['hair'], dk(P['hair'], .3), lt(P['hair'], .3)
    sk, sd = P['skin'], dk(P['skin'], .18)
    blush = mix(sk, C('#ff7a8a'), .35)

    def R(x0, y0, x1, y1, c):
        f.R(x0 - 9 + dx, y0 - 28 + dy, x1 - 9 + dx, y1 - 28 + dy, c)

    def Pt(x, y, c):
        f.P(x - 9 + dx, y - 28 + dy, c)
    R(8, 12, 10, 12, sd)
    R(6, 2, 12, 2, h_); R(5, 3, 13, 3, h_); R(4, 4, 14, 5, h_)
    R(5, 6, 13, 10, sk); R(6, 11, 12, 11, sk); R(13, 6, 13, 11, sd); R(12, 11, 12, 11, sd)
    R(4, 6, 4, 8, h_); R(14, 6, 14, 8, hd); Pt(4, 9, sk); Pt(14, 9, sd)
    R(5, 6, 6, 6, h_); R(9, 6, 10, 6, h_); R(12, 6, 13, 6, hd); Pt(5, 7, h_)
    R(7, 3, 9, 3, hh); Pt(6, 4, hh); R(12, 4, 14, 5, hd)
    if P['style'] == 'long':
        R(3, 5, 4, 14, h_); R(14, 5, 15, 14, hd); R(3, 14, 4, 15, hd)
    if P['style'] == 'bun':
        R(7, 0, 11, 1, h_); Pt(8, 0, hh); R(10, 1, 11, 1, hd)
    hat, hc = P['hat'], P['hat_c']
    if hat == 'cap':
        R(6, 1, 12, 1, hc); R(5, 2, 13, 4, hc); R(4, 5, 14, 5, dk(hc, .35)); R(3, 5, 4, 5, dk(hc, .35))
        R(7, 2, 9, 2, lt(hc, .3)); Pt(9, 3, C('#f5c518') if hc != C('#f5c518') else C('#2b2b2b'))
    elif hat == 'bandana':
        R(6, 2, 12, 2, hc); R(5, 3, 13, 5, hc); Pt(7, 3, lt(hc, .6)); Pt(11, 4, lt(hc, .6)); Pt(9, 5, lt(hc, .6))
        R(14, 4, 15, 5, dk(hc, .2)); Pt(16, 6, dk(hc, .2))
    if expr == 'happy':
        for ex in (6, 10):
            Pt(ex, 9, EYE); Pt(ex + 1, 8, EYE); Pt(ex + 2, 9, EYE)
        R(8, 11, 10, 11, dk(sk, .5)); Pt(7, 10, dk(sk, .35)); Pt(11, 10, dk(sk, .35))
    elif expr == 'open':
        R(7, 8, 7, 9, EYE); R(11, 8, 11, 9, EYE); Pt(7, 8, C('#ffffff')); Pt(11, 8, C('#ffffff'))
        R(6, 7, 7, 7, hd) if hat else None; R(11, 7, 12, 7, hd) if hat else None
        R(9, 10, 9, 11, C('#7a2a2a'))
    elif expr == 'sleep':
        R(6, 9, 7, 9, EYE); R(10, 9, 11, 9, EYE)
        Pt(9, 11, dk(sk, .4))
    else:  # focused
        R(7, 8, 7, 9, EYE); R(11, 8, 11, 9, EYE)
        R(8, 11, 9, 11, dk(sk, .45))
    Pt(6, 10, blush); Pt(12, 10, blush)


def mop_head(f, x, y=0):
    """Mop head on the floor centred at x: clamp, then wet white strands."""
    f.R(x - 1, y - 3, x + 1, y - 3, C('#4a5361'))
    for i, xx in enumerate(range(x - 4, x + 5)):
        bottom = y + (1 if (i * 7 + x) % 3 == 0 else 0)
        f.R(xx, y - 2, xx, bottom, C('#f4f6f8') if i % 2 == 0 else C('#c9d6de'))
    f.R(x - 3, y - 2, x + 3, y - 2, C('#ffffff'))


def suds(sp, pts):
    """Soap bubbles (no ink outline): white with a light blue rim."""
    for x, y, r in pts:
        cx, cy = CX + x, FY + y
        k = int(r) + 1
        for yy in range(cy - k, cy + k + 1):
            for xx in range(cx - k, cx + k + 1):
                d = math.hypot(xx - cx, yy - cy)
                if d <= r - 0.3:
                    sp.put(xx, yy, C('#ffffff') if (xx - cx) + (yy - cy) < 0 else C('#e3f4fb'))
                elif d <= r + 0.7:
                    sp.put(xx, yy, C('#58b9d6'))


def streak(sp, x0, x1):
    """A wet streak on the floor from x0 to x1 (translucent)."""
    for x in range(min(x0, x1) - 2, max(x0, x1) + 3):
        for y in (-1, 0, 1, 2):
            if y == 2 and (x % 3):
                continue
            sp.put(CX + x, FY + y, C('#62c3e3', 150))


def sparkle(sp, x, y, big):
    """A four-point star: white core, yellow arms, an amber rim so it shows on white tiles."""
    cx, cy = CX + x, FY + y
    r = 3 if big else 2
    pts = {(0, 0): 'c'}
    for k in range(1, r + 1):
        for dx, dy in ((k, 0), (-k, 0), (0, k), (0, -k)):
            pts[(dx, dy)] = 'a' if k < r else 't'
    if big:
        for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
            pts[(dx, dy)] = 'a'
    for (dx, dy), k in pts.items():
        for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (dx + ox, dy + oy) not in pts:
                sp.put(cx + dx + ox, cy + dy + oy, C('#d08a10'))
    for (dx, dy), k in pts.items():
        sp.put(cx + dx, cy + dy, C('#ffffff') if k == 'c' else C('#ffe14d') if k == 'a' else C('#fff6b0'))


def person(P, state, ph):
    f = Fig()
    fx = []  # effects drawn after the ink outline: (fn, args)
    under = []
    if state == 'working':
        mx = (-12, -3, 12, 3)[ph]
        side = -1 if mx < 0 else 1
        lean = side if abs(mx) > 6 else 0
        body(f, P, lean, arms='')
        head(f, P, 'focused', dx=lean)
        f.new()
        tx, ty = (-side * 6, -20) if abs(mx) > 6 else (-side * 6, -21)
        f.line(tx, ty, mx, -2, C('#b0753f'))
        f.new()
        uh = (tx + (mx - tx) * 0.12, ty + (-2 - ty) * 0.12)
        lh = (tx + (mx - tx) * 0.45, ty + (-2 - ty) * 0.45)
        u = P['uni']
        # arm to the upper hand from the shoulder on the handle's side, the other to the lower hand
        sh_u, sh_l = (side * -5 + lean, -14), (side * 5 + lean, -14)
        f.line(sh_u[0], sh_u[1], round(uh[0]), round(uh[1]) + 1, u, 2)
        f.line(sh_l[0], sh_l[1], round(lh[0]), round(lh[1]) + 1, u, 2)
        f.new()
        for hx, hy in (uh, lh):
            f.R(round(hx) - 1, round(hy) - 1, round(hx) + 1, round(hy) + 1, P['glove'])
            f.P(round(hx) - 1, round(hy) - 1, lt(P['glove'], .4))
        f.new()
        mop_head(f, mx)
        under.append((streak, (0, mx)))
        trail = -side * (6 if abs(mx) > 6 else 3)
        fx.append((suds, ([(mx + trail, -1, 2.5), (mx + trail // 2, -6 - ph % 2, 1.5),
                           (mx + trail * 2, 0, 1.5)],)))
    elif state == 'waiting':
        w = ph % 2
        body(f, P, 0, arms='L')
        head(f, P, 'open')
        f.new()
        u, ud = P['uni'], dk(P['uni'], .25)
        f.R(5, -15, 6, -14, u); f.R(6, -17, 7, -16, u)
        f.R(7, -27, 8, -18, u); f.R(8, -27, 8, -18, ud)
        f.R(7 + w, -29, 8 + w, -28, u)
        f.new()
        g = P['glove']
        f.R(6 + w, -32, 9 + w, -30, g); f.R(6 + w, -32, 9 + w, -32, lt(g, .4)); f.P(9 + w, -31, dk(g, .2))
        f.new()
        f.R(7 + 2 * w, -38, 8 + 2 * w, -33, C('#b0753f')); f.R(8 + 2 * w, -38, 8 + 2 * w, -33, C('#8a5a2e'))
        f.new()
        rx = 7 + 3 * w
        red, rd, rh = C('#e0393e'), C('#a5252a'), C('#ff8a80')
        f.R(rx - 1, -44, rx + 2, -44, red); f.R(rx - 3, -43, rx + 4, -42, red); f.R(rx - 4, -41, rx + 5, -40, red)
        f.R(rx - 4, -39, rx + 5, -39, rd); f.R(rx - 2, -43, rx - 1, -42, rh); f.P(rx, -44, rh)
        f.R(rx + 3, -41, rx + 5, -40, rd)
        fx.append((lambda sp, w=w: [sp.put(CX + x, FY + y, C('#7fd0ff')) for x, y in ((-7 - w, -24), (-7 - w, -23), (-8 - w, -22))], ()))
    elif state == 'done':
        body(f, P, 0, arms='L')
        head(f, P, 'happy')
        f.new()
        u, ud = P['uni'], dk(P['uni'], .25)
        f.R(5, -15, 9, -14, u); f.R(5, -14, 9, -14, ud)
        f.R(8, -20, 9, -16, u); f.R(9, -20, 9, -16, ud)
        f.new()
        g, gd = P['glove'], dk(P['glove'], .25)
        f.R(7, -23, 10, -21, g); f.R(7, -21, 10, -21, gd); f.P(10, -22, gd)
        f.R(8, -27, 9, -24, g); f.R(8, -27, 8, -26, lt(g, .5))
        big = ph % 2 == 0
        fx.append((sparkle, (-11, -26, big)))
        fx.append((sparkle, (15, -31, not big)))
        fx.append((sparkle, (-12, -9, not big)))
        fx.append((sparkle, (14, -6, big)))
    else:  # idle: leaning on the mop, dozing
        b = ph % 2
        body(f, P, -1, arms='R')
        f.new()
        f.line(-9, -22, -9, -3, C('#b0753f'))
        f.new()
        mop_head(f, -9)
        f.new()
        u = P['uni']
        f.R(-6, -15, -5, -13, u); f.R(-7, -18, -6, -16, u)
        f.new()
        g = P['glove']
        f.R(-11, -24, -7, -20, g); f.R(-11, -22, -7, -22, dk(g, .25)); f.R(-11, -24, -8, -24, lt(g, .4))
        f.new()
        head(f, P, 'sleep', dx=-2, dy=1 + b)
        r = 1 + b
        fx.append((lambda sp, r=r, b=b: [sp.put(xx, yy, C('#bfe8ff', 220) if math.hypot(xx - (CX - 3 + r), yy - (FY - 17 + b)) < r - 0.2
                                          else C('#4aa8d8')) for yy in range(FY - 17 + b - r - 1, FY - 17 + b + r + 2)
                                          for xx in range(CX - 3, CX - 3 + 2 * r + 2)
                                          if math.hypot(xx - (CX - 3 + r), yy - (FY - 17 + b)) < r + 0.6], ()))
    s = f.flatten()
    outline_out(s, INK)
    for fn, args in fx:
        fn(s, *args)
    if under:
        floor_ = Spr(FW, FH)
        for fn, args in under:
            fn(floor_, *args)
        floor_.img.alpha_composite(s.img)
        floor_.em = s.em
        floor_._load()
        s = floor_
    s.ax, s.ay = CX, FY + 1 - (DROP if state in ('working', 'idle') else 0)
    return s


def person_frames():
    """Every janitor frame; all phases of one look and state share a size and an anchor (cropped to their union)."""
    out = {}
    for vi, P in enumerate(PEOPLE_D):
        for st in STATES:
            fr = [person(P, st, ph) for ph in range(PHASES[st])]
            bb = None
            for sp in fr:
                b = sp.img.getbbox()
                bb = b if bb is None else (min(bb[0], b[0]), min(bb[1], b[1]), max(bb[2], b[2]), max(bb[3], b[3]))
            for ph, sp in enumerate(fr):
                n = Spr(bb[2] - bb[0], bb[3] - bb[1], sp.ax - bb[0], sp.ay - bb[1])
                n.img = sp.img.crop(bb)
                n.em = sp.em.crop(bb)
                n._load()
                out[f'person{vi}_{st}_{ph}_d'] = n
    return out


# ---------------------------------------------------------------- sheet
def build_all():
    d = {}
    for v in range(2):
        d[f'floor_tile_{v}_d'] = floor_tile(v)
    d['wall_rw_d'] = wall_seg('rw', 1)
    d['wall_rw_window_d'] = wall_seg('rw', 2, mirror=True)
    d['wall_lw_d'] = wall_seg('lw', 3)
    d['wall_lw_window_d'] = wall_seg('lw', 4, mirror=True)
    d['wall_corner_d'] = SP.wall_corner(D)
    d['desk_rw_d'] = desk('rw', 1)
    d['desk_lw_d'] = desk('lw', 2)
    d['chair_rw_d'] = bucket('rw')
    d['chair_lw_d'] = bucket('lw')
    for i in range(3):
        d[f'rug_{i}_d'] = rug(i)
    d['nameplate_d'] = nameplate(40)
    d['urinals_rw_d'] = urinals('rw')
    d['urinals_lw_d'] = urinals('lw')
    d['dryer_rw_d'] = hand_dryer('rw')
    d['towel_rw_d'] = towel_dispenser('rw')
    d['supply_lw_d'] = supply_shelf('lw', 0)
    d['vanity_lw_d'] = vanity('lw')
    d['wetsign_d'] = wet_floor_sign()
    d['bin_d'] = bin_()
    d['cart_d'] = cart()
    d['plant_big_d'] = SP.plant_big(SP.B)
    d.update(person_frames())
    for st in STATES:
        d[f'bubble_{st}'] = SP.bubble(st)
    return d


def build_sheet_d():
    frames = build_all()
    out = f'{OUT}/d'
    os.makedirs(out, exist_ok=True)
    SP.pack(frames, out)
    return frames


# ---------------------------------------------------------------- mockup (the Kotlin layout's spots for 3 areas)
NW, NH, S = 480, 300, 3
OX, OY = 275, 86


def proj(u, v, z=0):
    return OX + 2 * u - 2 * v, OY + u + v - z


def mockup(fr, phase=0, path=None):
    items = []

    def add(layer, key, sp, u, v, z=0):
        X, Y = proj(u, v, z)
        items.append((layer, key, sp, X, Y))
    add(0, 0, SP.floor_base(D, 96), 0, 0)
    for i in range(6):
        for j in range(6):
            add(0, 1 + i + j, fr[f'floor_tile_{(i + j) % 2}_d'], i * 16, j * 16)
    add(1, 0, fr['wall_corner_d'], 0, 0)
    for i in range(6):
        add(1, 1 + i, fr['wall_rw_window_d' if i in WIN_RW else 'wall_rw_d'], i * 16, 0)
        add(1, 1 + i, fr['wall_lw_window_d' if i in WIN_LW else 'wall_lw_d'], 0, i * 16)
    for name, u, v, z, layer, key in DECOR:
        add(layer, key, fr[name], u, v, z)
    areas = [  # rug (u, v, w, d, style), plate (u, v), seats [(o, u, v)]
        ((9, 4, 48, 32, 0), (32, 41), [('rw', 14, 8), ('rw', 34, 8)]),
        ((61, 3, 32, 48, 1), (87, 57), [('lw', 64, 6), ('lw', 64, 29)]),
        ((19, 58, 48, 32, 2), (50, 93), [('rw', 24, 62), ('rw', 46, 62)]),
    ]
    sessions = [(0, 0, 'working', 0), (0, 1, 'waiting', 1), (1, 0, 'idle', 2), (1, 1, 'working', 3), (2, 0, 'done', 4), (2, 1, 'working', 5)]
    for ru, rv, rw_, rd, st in (a[0] for a in areas):
        for c in range(rw_ // 24):
            for k in range(rd // 16):
                add(3, c + k, fr[f'rug_{st}_d'], ru + c * 24, rv + k * 16)
    people = []
    for ai, (_, (pu, pv), seats) in enumerate(areas):
        add(5, pu + pv, fr['nameplate_d'], pu, pv)
        for si, (o, u, v) in enumerate(seats):
            add(5, u + v + 15, fr[f'desk_{o}_d'], u, v)
            cu, cv = (u + 10, v + 12.5) if o == 'rw' else (u + 12.5, v + 10)
            add(5, cu + cv, fr[f'chair_{o}_d'], cu, cv)
            for (a2, s2, state, var) in sessions:
                if (a2, s2) != (ai, si):
                    continue
                n = PHASES[state]
                sp = fr[f'person{var}_{state}_{phase % n}_d']
                if state in ('working', 'idle'):
                    add(5, cu + cv + 0.1, sp, cu, cv, SEAT_Z)
                    X, Y = proj(cu, cv, SEAT_Z)
                else:
                    su, sv = (u + 16, v + 22) if o == 'lw' else ((u + 15, v + 19) if si % 2 == 1 else (u - 3, v + 16))
                    add(5, su + sv, sp, su, sv)
                    X, Y = proj(su, sv)
                people.append((state, X, Y - sp.ay))
    canvas = Spr(NW, NH)
    for layer, key, sp, X, Y in sorted(items, key=lambda it: (it[0], it[1])):
        canvas.place(sp, X, Y)
    for state, X, head_y in people:
        canvas.place(fr[f'bubble_{state}'], X + 3, head_y - 1)
    bg = Image.new('RGBA', (NW, NH), (227, 238, 239, 255))
    bg.alpha_composite(canvas.img)
    img = bg.resize((NW * S, NH * S), Image.NEAREST)
    path = path or f'{OUT}/D-restroom.png'
    img.convert('RGB').save(path)
    return path


# kept in step with ThemeD.kt
WIN_RW = (1, 3)
WIN_LW = (3,)
DECOR = [  # frame, u, v, z, layer, key
    ('urinals_lw_d', 0, 16, 0, 2, 0.0),
    ('urinals_rw_d', 66, 0, 0, 2, 1.0),
    ('dryer_rw_d', 39, 0, 24, 2, 2.0),
    ('towel_rw_d', 7, 0, 22, 2, 3.0),
    ('dryer_rw_d', 88, 0, 24, 2, 4.0),
    ('supply_lw_d', 0, 1, 0, 5, 11.0),
    ('supply_lw_d', 0, 80, 0, 5, 90.0),
    ('vanity_lw_d', 0, 48, 0, 5, 60.0),
    ('wetsign_d', 90, 6, 0, 5, 96.0),
    ('plant_big_d', 7, 89, 0, 5, 96.0),
    ('bin_d', 5, 68, 0, 5, 73.0),
    ('cart_d', 91, 90, 0, 5, 181.0),
]


if __name__ == '__main__':
    import sys
    fr = build_sheet_d()
    print(len(fr), 'frames (theme D sheet, d/)')
    for ph in range(4 if 'all' in sys.argv[1:] else 1):
        print('wrote', mockup(fr, ph, f'{OUT}/D-restroom-{ph}.png' if ph else None))
