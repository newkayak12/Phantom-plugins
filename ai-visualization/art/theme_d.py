"""Theme D - Restroom Cleaning (화장실 청소): sprites, sheet (OUT/d/) and a mockup.

Imports the shared helpers of pix.py / sprites.py and changes none of them, so the sheets of A, B and C stay byte-identical.
Desks are toilet + sink units, chairs are mop buckets, people are janitors in coveralls and rubber gloves, drawn front-facing
in every state at twice the scene's density with people_hd.py's figures and faces (also imported, also unchanged): WORKING mops the floor (4 frames, the mop swings left and right with suds), WAITING raises an open hand holding a plunger,
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


# ---------------------------------------------------------------- people (people_hd.py's look, dressed as janitors)
# Drawn at people_hd.DETAIL x the scene's density (ThemeD.personDetail), with people_hd's figure, faces, hair and hands; the anchor
# is the boots for every state. Only the clothes (coveralls, rubber boots and gloves, caps and bandanas) and the props are new here.
import people_hd as HD
from people_hd import ramp, SKIN, INKW, LIFT, EYE, MOUTH, WHITE, hand, head_front, hair_back_layer, looks

DETAIL = HD.DETAIL

# janitors: skin, hair (style), coverall (shirt = pants), rubber gloves, boots (shoe), headwear
PEOPLE_D = [
    dict(name='dohyun', skin='light', hair='#5c3a25', style='short', shirt='#3d6fc0', glove='#ffd02e', shoe='#2d3140',
         hat='cap', hat_c='#26407a'),
    dict(name='yuna', skin='pale', hair='#e0a848', style='long', shirt='#2f9a7e', glove='#ff8fb1', shoe='#2a2f2c',
         hat='bandana', hat_c='#e2463f'),
    dict(name='kwame', skin='dark', hair='#1f1a20', style='curly', shirt='#e07a2e', glove='#ffd02e', shoe='#2b2522'),
    dict(name='mira', skin='medium', hair='#2c2028', style='bun', shirt='#5f6f86', glove='#4fb4ff', shoe='#25262c'),
    dict(name='finn', skin='tan', hair='#8a3a2a', style='messy', shirt='#8a4fa8', glove='#ffd02e', shoe='#2a2430',
         glasses='#2a2228'),
    dict(name='sumi', skin='pale', hair='#2a2230', style='ponytail', shirt='#3ba3d9', glove='#7ed957', shoe='#23303a',
         hat='bandana', hat_c='#2f6fd0'),
    dict(name='arjun', skin='brown', hair='#231c24', style='short', shirt='#7a8a3a', glove='#ff9a3a', shoe='#2a2020',
         hat='cap', hat_c='#f5c518', beard=True),
    dict(name='hana', skin='light', hair='#d4683a', style='bob', shirt='#33447a', glove='#ffd02e', shoe='#2a2630',
         hat='bandana', hat_c='#f07aa8', freckles=True),
]
for _P in PEOPLE_D:
    _P['pants'] = _P['shirt']
    _P['top'] = 'coverall'

STATES = ('working', 'waiting', 'done', 'idle')
PHASES = dict(working=4, waiting=2, done=2, idle=2)  # distinct frames per state; the theme maps phase -> phase % n
SEAT_Z = 7  # OfficeLayout.SEAT_Z: the layout lifts working/idle people this high; they still stand, at the toilet

WOOD = ramp('#b77a42')
RUBBER = ramp('#e0393e')
TORSO = [(-12, -58), (12, -58), (14, -52), (12, -33), (-12, -33), (-14, -52)]


def legs(f, P):
    """people_hd's short legs in coverall trousers tucked into rubber boots."""
    sk, hair, top, pants, boot, *_ = looks(P)
    t = -31 + LIFT
    f.paint(f.mask(('r', (-11, t - 5, 11, t + 2))), pants, 'pants')
    f.paint(f.mask(('p', [(-11, t), (-1, t), (-2, -7), (-10, -7)])), pants, 'legL', shade='r')
    f.paint(f.mask(('p', [(1, t), (11, t), (10, -7), (2, -7)])), pants, 'legR', shade='r')
    for x0, g in ((-11, 'bootL'), (2, 'bootR')):
        f.paint(f.mask(('r', (x0, -16, x0 + 9, -6))), boot, g, shade='r')
        f.hl(x0, x0 + 9, -16, boot['hi'])
    f.paint(f.mask(('e', (-13, -10, -1, 1))), boot, 'bootL')
    f.paint(f.mask(('e', (1, -10, 13, 1))), boot, 'bootR')
    for x in range(-14, 15):
        f.col.pop((x, 1), None)
    f.hl(-10, -5, -8, boot['hi']); f.hl(4, 9, -8, boot['hi'])


def coverall(f, P, sleeves):
    """The coverall's upper half (front): zip, a chest pocket with a pen, a name patch, a reflective band, a belt; then sleeves."""
    sk, hair, top, *_ = looks(P)
    body = f.mask(('p', TORSO))
    f.paint(body, top, 'shirt')
    L = f.lift

    def inb(x, y):
        return (x, y + L) in body
    for x in range(-14, 15):
        if inb(x, -41):
            f.px(x, -41, C('#f4f1cf')); f.px(x, -40, C('#c9c8a8'))
    f.hl(-12, 12, -35, top['dp'])
    f.vl(0, -56, -36, top['ln'])
    f.px(0, -54, C('#dfe4ea')); f.px(0, -53, C('#9aa3ae'))
    f.paint(f.mask(('r', (-10, -50, -4, -45))), top, 'pocket', shade='rb', hl=False)
    f.vl(-6, -53, -50, C('#e04848')); f.px(-6, -53, C('#f6f6f2'))
    f.paint(f.mask(('r', (3, -51, 9, -48))), ramp('#f6f6f2'), 'patch', shade='rb', hl=False)
    f.hl(4, 7, -50, C('#7a8494'))
    f.paint(f.mask(('r', (-2, -36, 2, -33))), ramp('#d9b24a'), 'buckle', shade='rb', hl=False)
    for sx in (-1, 1):
        for i in range(5):
            f.px(sx * (10 - i // 2), -52 + i * 2, top['sh'])
    for m in sleeves:
        f.paint(m, top, 'sleeve')
    f.paint(f.mask(('p', [(-8, -59), (-1, -59), (-3, -53)])), ramp(HD.lt(top['b'], .1)), 'collar', hl=False)
    f.paint(f.mask(('p', [(1, -59), (8, -59), (3, -53)])), top, 'collar', hl=False)


def glove(f, P, x, y, group='hand', thumb=-1, cuff=True):
    """A rubber-gloved hand centred near (x, y), with its gauntlet cuff above it."""
    gl = ramp(P['glove'])
    if cuff:
        f.paint(f.mask(('e', (x - 4, y - 7, x + 4, y - 1))), gl, group + 'c', hl=False)
        f.hl(x - 3, x + 3, y - 2, gl['sh'])
    hand(f, gl, x - 3, y - 3, x + 3, y + 3, group, thumb=thumb)


def hat(f, P, cx, cy):
    if not P.get('hat'):
        return
    hc = ramp(P['hat_c'])
    L = f.lift
    if P['hat'] == 'cap':
        crown = {p for p in f.mask(('e', (cx - 15, cy - 19, cx + 15, cy + 3))) if p[1] - L <= cy - 8}
        f.paint(crown, hc, 'hat')
        f.vl(cx - 1, cy - 17, cy - 10, hc['sh'])
        f.paint(f.mask(('e', (cx - 7, cy - 16, cx - 2, cy - 11))), ramp('#fbfbf6'), 'logo', shade='', hl=False)
        f.px(cx - 5, cy - 14, hc['b']); f.px(cx - 4, cy - 13, hc['b'])
        brim = {p for p in f.mask(('e', (cx - 20, cy - 12, cx + 11, cy - 3))) if p[1] - L >= cy - 8}
        f.paint(brim, ramp(hc['sh']), 'brim', shade='b', hl=False)
        f.hl(cx - 16, cx + 6, cy - 8, hc['hi'])
    else:
        band = {p for p in f.mask(('e', (cx - 15, cy - 18, cx + 15, cy + 4))) if p[1] - L <= cy - 7}
        f.paint(band, hc, 'hat')
        for dx, dy in ((-9, -12), (-3, -15), (3, -11), (9, -14), (-6, -8), (6, -8), (-1, -9), (11, -9)):
            if (cx + dx, cy + dy + L) in band:
                f.px(cx + dx, cy + dy, WHITE); f.px(cx + dx + 1, cy + dy, HD.lt(hc['b'], .55))
        f.paint(f.mask(('p', [(cx + 13, cy - 9), (cx + 21, cy - 5), (cx + 18, cy - 1)])), hc, 'tail1')
        f.paint(f.mask(('p', [(cx + 13, cy - 8), (cx + 18, cy + 2), (cx + 14, cy + 3)])), ramp(hc['sh']), 'tail2')
        f.paint(f.mask(('e', (cx + 10, cy - 12, cx + 16, cy - 6))), hc, 'knot')


def head(f, P, expr, hx=0, hy=-74):
    """people_hd's front head and hair, with the headwear; 'sleep' closes the eyes and rounds the mouth."""
    head_front(f, P, 'open' if expr == 'sleep' else expr, hy=hy, hx=hx)
    if expr == 'sleep':
        sk = SKIN[P['skin']]
        ey = hy + 1
        for x0 in (hx - 8, hx + 2):
            for y in range(ey - 1, ey + 4):
                for x in range(x0, x0 + 3):
                    f.px(x, y, sk['b'])
            f.px(x0 - 1, ey + 1, EYE); f.hl(x0, x0 + 2, ey + 2, EYE); f.px(x0 + 3, ey + 1, EYE)
        my = ey + 8
        f.px(hx - 5, my - 1, sk['b']); f.hl(hx - 4, hx - 1, my, sk['b']); f.px(hx, my - 1, sk['b'])
        f.hl(hx - 3, hx - 2, my, MOUTH); f.hl(hx - 3, hx - 2, my + 1, C('#5a2228'))
    hat(f, P, hx, hy)


def disc(fx, x, y, r, fill, rim, shine=None):
    """A bubble (no ink outline) into the effects dict [fx]."""
    k = int(r) + 2
    for yy in range(y - k, y + k + 1):
        for xx in range(x - k, x + k + 1):
            d = math.hypot(xx - x, yy - y)
            if d <= r - 0.4:
                fx[(xx, yy)] = shine if shine and (xx - x) + (yy - y) < -r * .6 else fill
            elif d <= r + 0.6:
                fx[(xx, yy)] = rim


def sparkle(fx, x, y, r):
    """A four-point star with an amber rim, so it shows on white tiles."""
    pts = {(0, 0): 'c'}
    for k in range(1, r + 1):
        w = 1 if k < r - 1 else 0
        for dx, dy in ((k, 0), (-k, 0), (0, k), (0, -k)):
            pts[(dx, dy)] = 'a' if k < r else 't'
            if w:
                for ox, oy in (((0, 1), (0, -1)) if dy == 0 else ((1, 0), (-1, 0))):
                    if k == 1:
                        pts.setdefault((dx + ox, dy + oy), 'a')
    for (dx, dy) in list(pts):
        for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (dx + ox, dy + oy) not in pts:
                fx[(x + dx + ox, y + dy + oy)] = C('#d08a10')
    for (dx, dy), k in pts.items():
        fx[(x + dx, y + dy)] = WHITE if k == 'c' else C('#ffe14d') if k == 'a' else C('#fff6b0')


def mop_head(f, x, y=1):
    """A wet string mop on the floor centred at x: a grey clamp, white strands with blue-grey creases."""
    f.lift = 0
    f.paint(f.mask(('r', (x - 5, y - 13, x + 5, y - 10))), ramp('#8a94a4'), 'clamp')
    strands = {p for p in f.mask(('e', (x - 11, y - 12, x + 11, y + 2))) if p[1] >= y - 10}
    f.paint(strands, ramp('#eef3f6'), 'mop')
    for xx in range(x - 8, x + 9, 3):
        f.vl(xx, y - 8, y, C('#b4c6d4'))
    for xx in range(x - 10, x + 11):
        if (xx, y) in strands:
            f.px(xx, y, C('#9cc9df'))


def handle(f, x0, y0, x1, y1):
    f.lift = 0
    f.paint(f.mask(('l', [(x0, y0), (x1, y1)], 3)), WOOD, 'handle', shade='r', hl=False)


def finish(f, over=None, under=None):
    """people_hd's Fig.finish plus effects without an outline: [over] on top of the figure, [under] only where it is empty."""
    over, under = over or {}, under or {}
    col = dict(f.col)
    for (x, y), c in f.col.items():
        g = f.grp.get((x, y))
        if g is None:
            continue
        o = f.order[(x, y)]
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q in f.col and f.grp.get(q) not in (None, g) and f.order[q] < o:
                col[(x, y)] = HD.LINES.get(c, HD.wdk(c, .55))
                break
    out = dict(col)
    for (x, y) in col:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y + dy) not in col:
                out[(x + dx, y + dy)] = INKW
    for p, c in under.items():
        out.setdefault(p, c)
    out.update(over)
    xs = [p[0] for p in out]
    ys = [p[1] for p in out]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    x0 -= x0 % 2
    y0 -= y0 % 2
    w, h = x1 - x0 + 1, y1 - y0 + 1
    sp = Spr(w + w % 2, h + h % 2, -x0, -y0)
    for (x, y), c in out.items():
        sp.put(x - x0, y - y0, c)
    return sp


def person(P, state, ph):
    """One janitor frame, front 3/4, the anchor at the boots."""
    f = HD.Fig()
    over, under = {}, {}
    legs(f, P)
    sk, hair, top, *_ = looks(P)
    shoulders = lambda: [f.mask(('e', (-16, -60, -6, -48))), f.mask(('e', (6, -60, 16, -48)))]
    if state == 'working':
        # mopping: the mop sweeps left, centre, right, centre; the handle crosses the body
        mx = (-14, -4, 14, 4)[ph]
        side = -1 if mx < 0 else 1
        bob = ph % 2
        tx, ty = -side * 15, -58
        bx, by = mx + side * 2, -10
        mop_head(f, mx)
        f.lift = LIFT
        hair_back_layer(f, P, side * 2, -74 + bob)
        coverall(f, P, shoulders())
        head(f, P, 'open', hx=side * 2, hy=-74 + bob)
        handle(f, tx, ty, bx, by)
        at = lambda t: (round(tx + (bx - tx) * t), round(ty + (by - ty) * t))
        uh, lh = at(.12), at(.5)
        f.paint(f.mask(('l', [(-side * 12, -48), uh], 7)), top, 'sleeveU')
        f.paint(f.mask(('l', [(side * 12, -48), (side * 13, -38), lh], 7)), top, 'sleeveL')
        glove(f, P, uh[0], uh[1], 'handU', thumb=side, cuff=False)
        glove(f, P, lh[0], lh[1], 'handL', thumb=side, cuff=False)
        for x in range(min(0, mx) - 4, max(0, mx) + 5):
            for y in (-1, 0, 1, 2):
                if not (y == 2 and x % 3):
                    under[(x, y)] = C('#62c3e3', 140)
        trail = -side * 9
        for x, y, r in ((mx + trail, -2, 4), (mx + trail // 2, -12 - 2 * bob, 2.5), (mx + trail * 2, 0, 2.5),
                        (mx - trail // 3, -15 + bob, 2)):
            disc(over, x, y, r, C('#e9f7fc'), C('#58b9d6'), WHITE)
    elif state == 'waiting':
        # an open hand raised high, calling; the other hand holds a plunger standing beside the boots
        w = ph % 2
        f.paint(f.mask(('e', (-27, -13, -7, 0))), RUBBER, 'cupback')
        f.lift = LIFT
        hair_back_layer(f, P, 0, -74)
        sleeves = [f.mask(('l', [(-12, -54), (-15, -44), (-16, -38)], 7)),
                   f.mask(('l', [(12, -54), (19, -64), (20 + w, -76)], 7))]
        coverall(f, P, shoulders() + sleeves)
        f.lift = 0
        f.paint(f.mask(('r', (-18, -50, -16, -8))), WOOD, 'handle', shade='r', hl=False)
        f.paint({p for p in f.mask(('e', (-27, -16, -7, 0))) if p[1] <= -4}, RUBBER, 'cup')
        f.paint(f.mask(('r', (-28, -5, -6, -2))), ramp(RUBBER['sh']), 'cuprim', hl=False)
        f.lift = LIFT
        glove(f, P, -16, -36, 'handP', thumb=1)
        gl = ramp(P['glove'])
        hx, hy = 20 + w, -82
        f.paint(f.mask(('e', (hx - 4, hy + 4, hx + 4, hy + 10))), gl, 'gcuff', hl=False)
        f.paint(f.mask(('e', (hx - 5, hy - 2, hx + 5, hy + 7))), gl, 'hand', hl=False)
        for fx_, ln in ((-4, 5), (-1, 7), (2, 7), (5, 5)):
            f.paint(f.mask(('r', (hx + fx_, hy - ln, hx + fx_ + 1, hy))), gl, 'hand', shade='', hl=False)
            f.px(hx + fx_ + 1, hy - ln + 1, gl['sh'])
        f.paint(f.mask(('l', [(hx - 5, hy + 3), (hx - 9, hy - 2)], 3)), gl, 'hand', shade='', hl=False)
        f.hl(hx - 3, hx + 3, hy + 5, gl['sh'])
        head(f, P, 'calling' if w == 0 else 'open')
        for i, (x, y) in enumerate(((31 + w, -78), (33 + w, -72), (30 + w, -66))):
            for k in range(3):
                over[(x + k, y - i + k // 2)] = C('#e5484d')
    elif state == 'done':
        # thumbs-up, the other fist on the hip, among sparkles
        f.lift = LIFT
        hair_back_layer(f, P, 0, -74)
        sleeves = [f.mask(('l', [(-12, -54), (-19, -45), (-14, -38)], 7)),
                   f.mask(('l', [(12, -54), (23, -46), (21, -57)], 7))]
        coverall(f, P, shoulders() + sleeves)
        glove(f, P, -13, -37, 'handH', thumb=1)
        gl = ramp(P['glove'])
        f.paint(f.mask(('e', (16, -60, 26, -53))), gl, 'gcuff', hl=False)
        f.paint(f.mask(('e', (15, -67, 26, -58))), gl, 'fist')
        for y in (-64, -62, -60):
            f.hl(17, 20, y, gl['sh'])
        f.paint(f.mask(('e', (20, -75, 25, -64))), gl, 'thumb', hl=True)
        head(f, P, 'happy')
        big = ph % 2 == 0
        for (x, y), b in (((-24, -82), big), ((31, -84), not big), ((-25, -46), not big), ((28, -34), big)):
            sparkle(over, x, y, 5 if b else 3)
    else:
        # dozing standing up, hands stacked on the mop handle, the cheek against them; a snot bubble swells and shrinks
        b = ph % 2
        mop_head(f, -19)
        handle(f, -20, -60, -20, -10)
        f.lift = LIFT
        hx, hy = -3, -72 + b
        hair_back_layer(f, P, hx, hy)
        sleeves = [f.mask(('l', [(-12, -54), (-17, -48), (-20, -58)], 7)),
                   f.mask(('l', [(12, -54), (10, -46), (-14, -56)], 7))]
        coverall(f, P, shoulders() + sleeves)
        glove(f, P, -21, -64, 'handA', thumb=1, cuff=False)
        glove(f, P, -18, -58, 'handB', thumb=-1, cuff=False)
        head(f, P, 'sleep', hx=hx, hy=hy)
        r = 2.5 + 2 * b
        disc(over, hx - 7 - int(r), hy + LIFT + 8, r, C('#d6f0ff', 230), C('#4aa8d8'), WHITE)
        for i, (x, y) in enumerate(((14, -92), (19, -99))):
            n = 4 + i * 2 + b
            for k in range(n):
                over[(x + k, y)] = C('#6a7f99'); over[(x + k, y + n - 1)] = C('#6a7f99')
                over[(x + n - 1 - k, y + k)] = C('#6a7f99')
    return finish(f, over, under)


def person_frames():
    """Every janitor frame; all phases of one look and state share a size and an anchor (the union of their boxes)."""
    out = {}
    for vi, P in enumerate(PEOPLE_D):
        for st in STATES:
            fr = [person(P, st, ph) for ph in range(PHASES[st])]
            x0 = min(-sp.ax for sp in fr); y0 = min(-sp.ay for sp in fr)
            x1 = max(sp.w - sp.ax for sp in fr); y1 = max(sp.h - sp.ay for sp in fr)
            for ph, sp in enumerate(fr):
                n = Spr(x1 - x0, y1 - y0, -x0, -y0)
                n.img.alpha_composite(sp.img, (-x0 - sp.ax, -y0 - sp.ay))
                n._load()
                out[f'person{vi}_{st}_{ph}_d'] = n
    return out


def native(sp):
    """A person frame at the scene's density (for the mockup)."""
    n = Spr(sp.w // DETAIL, sp.h // DETAIL, sp.ax // DETAIL, sp.ay // DETAIL)
    n.img = sp.img.resize((n.w, n.h), Image.NEAREST)
    n.em = Image.new('L', (n.w, n.h), 0)
    n._load()
    return n


def lineup(path, scale=4):
    """Every janitor x state x phase at [scale] / DETAIL, on the floor tiles."""
    fr = person_frames()
    cols = [(st, ph) for st in STATES for ph in range(PHASES[st])]
    cw, ch = 40 * scale, 56 * scale
    img = Image.new('RGBA', (cw * len(cols) + 20, ch * len(PEOPLE_D) + 20), (236, 243, 243, 255))
    d = ImageDraw.Draw(img)
    for yy in range(0, img.height, 8 * scale):
        d.line([(0, yy), (img.width, yy)], fill=(196, 214, 216, 255))
    for r in range(len(PEOPLE_D)):
        for c, (st, ph) in enumerate(cols):
            sp = fr[f'person{r}_{st}_{ph}_d']
            big = sp.img.resize((sp.w * scale // DETAIL, sp.h * scale // DETAIL), Image.NEAREST)
            x = 10 + c * cw + cw // 2 - sp.ax * scale // DETAIL
            y = 10 + r * ch + ch - 6 * scale - sp.ay * scale // DETAIL
            img.alpha_composite(big, (x, max(0, y)))
    img.convert('RGB').save(path)


# ---------------------------------------------------------------- pets (decor, at the scene's density, shaded like the people)
def cat():
    """An orange tabby sitting front 3/4, tail curled round its paws, eyes shut in a smile; the anchor is under its paws."""
    f = HD.Fig()
    fur, wh = ramp('#f2a65a'), ramp('#fbf4ea')
    f.paint(f.mask(('l', [(4, -1), (8, -1), (9, -5)], 3)), fur, 'tail')
    f.paint(f.mask(('e', (-5, -10, 6, 0))), fur, 'body')
    f.paint(f.mask(('e', (-3, -8, 2, -1))), wh, 'body', shade='', hl=False)
    for x in (-3, 0):
        f.paint(f.mask(('r', (x, -1, x + 1, 0))), wh, 'paw', shade='', hl=False)
    f.vl(4, -8, -6, fur['dp']); f.vl(3, -5, -3, fur['dp'])
    f.px(9, -5, wh['b']); f.px(9, -6, wh['b'])
    f.paint(f.mask(('p', [(-6, -14), (-5, -20), (-2, -16)])), fur, 'ear')
    f.paint(f.mask(('p', [(1, -16), (4, -20), (4, -13)])), fur, 'ear')
    f.px(-5, -17, C('#f2a0a8')); f.px(3, -17, C('#f2a0a8'))
    f.paint(f.mask(('e', (-7, -17, 5, -7))), fur, 'head')
    f.paint(f.mask(('e', (-4, -12, 2, -8))), wh, 'head', shade='', hl=False)
    for x, y in ((-2, -16), (0, -16), (-1, -15), (-6, -12), (4, -12)):
        f.px(x, y, fur['dp'])
    f.px(-4, -12, EYE); f.px(-5, -13, EYE); f.px(-3, -13, EYE)
    f.px(1, -12, EYE); f.px(0, -13, EYE); f.px(2, -13, EYE)
    f.px(-1, -11, C('#e0707a'))
    f.px(-6, -10, mix(fur['b'], C('#ff6f7d'), .4)); f.px(4, -10, mix(fur['b'], C('#ff6f7d'), .4))
    return finish(f)


def dog():
    """A corgi lying on the floor facing left, a blue bandana on, a sponge in its mouth; the anchor is under its chest."""
    f = HD.Fig()
    fur, wh, bd = ramp('#e3994a'), ramp('#fbf4ea'), ramp('#3a7ad8')
    f.paint(f.mask(('e', (8, -9, 13, -4))), wh, 'tail')
    f.paint(f.mask(('e', (-6, -8, 11, 0))), fur, 'body')
    f.paint(f.mask(('e', (-5, -4, 6, 0))), wh, 'body', shade='', hl=False)
    f.paint(f.mask(('e', (5, -3, 11, 0))), fur, 'leg')
    f.paint(f.mask(('e', (-13, -2, -6, 0))), wh, 'paw', hl=False)
    f.paint(f.mask(('p', [(-13, -12), (-13, -19), (-9, -13)])), fur, 'ear')
    f.paint(f.mask(('p', [(-8, -13), (-5, -19), (-4, -11)])), fur, 'ear')
    f.px(-12, -15, C('#f2a0a8')); f.px(-6, -15, C('#f2a0a8'))
    f.paint(f.mask(('e', (-15, -14, -3, -4))), fur, 'head')
    f.paint(f.mask(('e', (-17, -9, -10, -4))), wh, 'head', shade='', hl=False)
    f.vl(-9, -13, -10, wh['b'])
    f.px(-17, -8, EYE); f.px(-16, -8, EYE)
    f.px(-12, -10, EYE); f.px(-7, -10, EYE); f.px(-12, -11, WHITE)
    f.px(-11, -7, mix(fur['b'], C('#ff6f7d'), .45))
    f.paint(f.mask(('p', [(-6, -6), (-1, -6), (-3, -2)])), bd, 'bandana', hl=False)
    f.px(-4, -5, WHITE)
    f.paint(f.mask(('r', (-21, -7, -16, -4))), ramp('#ffd84a'), 'sponge')
    f.hl(-21, -16, -7, C('#3fae5a'))
    return finish(f)


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
    d['cat_d'] = cat()
    d['dog_d'] = dog()
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
                sp = native(fr[f'person{var}_{state}_{phase % n}_d'])
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
    ('dog_d', 92, 18, 0, 5, 110.0),
    ('cat_d', 80, 93, 0, 5, 173.0),
]


if __name__ == '__main__':
    import sys
    fr = build_sheet_d()
    print(len(fr), 'frames (theme D sheet, d/)')
    lineup('/tmp/viz-port-d-lineup.png' if 'lineup' in sys.argv[1:] else f'{OUT}/D-lineup.png')
    for ph in range(4 if 'all' in sys.argv[1:] else 1):
        print('wrote', mockup(fr, ph, f'{OUT}/D-restroom-{ph}.png' if ph else None))
