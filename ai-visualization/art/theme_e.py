"""Theme E - 주방 (Restaurant Kitchen): sprites at native resolution, packed to OUT/e/, and a mockup.

Same grid as A (16-unit tiles, 2:1 iso, desk 20x10 units, chair centre 12.5 units in front of the desk, seat height 7 px).
A desk is a steel cooking station, the chair a wooden duckboard on the floor (the cook stands on it), and the people are ten
cooks drawn at people_hd.DETAIL x (people_hd's heads and faces, E's jackets, aprons and hats) with four animation phases per state:
  working  back view at the station, tossing a wok over flames (even variants) or chopping with a cleaver (odd variants)
  waiting  front view, an open hand raised high, calling out, ringing a brass hand bell in the other
  done     front view, presenting a plate under a silver cloche, steam rising
  idle     front view, dozing on an upturned crate, a sleep bubble growing
  (working is also mirrored, `_lw`, for stations along v)
Run from ai-visualization/art:  PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" theme_e.py [mockup]
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFont
from pix import C, dk, lt, mix, H, Spr, part, outline, outline_out, box, obox, M, INK
import sprites as SP
import people_hd as PH
from people_hd import Fig, ramp, looks, SKIN, WHITE

OUT = SP.OUT
SEG, W_H = SP.SEG, SP.W_H
SEAT_Z = 7
PHASES = 4
STATES = ('working', 'waiting', 'done', 'idle')

K = dict(
    quarry=[C('#b5523b'), C('#a94b36'), C('#bd5c43')], grout=C('#7d3628'), base_side=C('#5a2a20'),
    tile=C('#f4f5f2'), tile_hi=C('#ffffff'), tile_grout=C('#c9ced2'), band=C('#2f7f74'),
    steel=C('#c3cad2'), steel_hi=C('#e4e8ec'), steel_dk=C('#8b949f'), steel_deep=C('#5d6570'),
    cap=C('#dfe3e7'),
    dining_wall=C('#c98a4b'), dining_dk=C('#8a5530'), dining_floor=C('#6b3f26'),
    cloth=C('#f3ebdc'), lamp=C('#ffd27a'), heat=C('#ff7a3d'),
    wood=C('#b98552'), wood_dk=C('#7d5232'),
    mat=[(C('#3a3e45'), C('#24272c'), C('#4b5058')), (C('#3e4a3d'), C('#283126'), C('#506150'))],
)

# ---------------------------------------------------------------- floor


def floor_tile(var):
    """Red quarry tiles, 8x8 units each (four to a floor tile), dark grout on the low edges."""
    s = Spr(70, 40, 33, 2)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if a % 8 < 0.5 or d % 8 < 0.5:
            return K['grout']
        ti, tj = int(a // 8), int(d // 8)
        tone = K['quarry'][int(H('q', var, ti, tj) * 3)]
        if (a % 8) < 1.0 or (d % 8) < 1.0:
            return lt(tone, .14)
        if (a % 8) >= 7.5 or (d % 8) >= 7.5:
            return dk(tone, .10)
        r = H('sp', var, int(a * 2), int(d * 2))
        if r < 0.04:
            return dk(tone, .12)
        if r > 0.985:
            return lt(tone, .2)
        return tone
    box(s, 0, 0, 0, SEG, SEG, 0, tex)
    return s.crop()


# ---------------------------------------------------------------- walls


def dining_px(s_, z, seed):
    """The dining room seen through a pass window: x in 0..12 along the opening, y 0..28 up from the pass shelf."""
    x, y = s_, z
    # pendant lamp
    lx = 3.5 + (seed % 3) * 2.5
    if abs(x - lx) < 0.3 and y >= 24:
        return C('#3a2a22')
    if abs(x - lx) < 1.6 and 21 <= y < 24:
        return K['lamp'] if y >= 22 else lt(K['lamp'], .3)
    if abs(x - lx) < 2.6 and 16 <= y < 21 and ((x - lx) * 1.2) ** 2 + (y - 21) ** 2 < 9:
        return mix(K['dining_wall'], K['lamp'], .35)
    # tables with white cloths and a diner
    if y < 3:
        return K['dining_floor'] if (int(x * 2) + int(y)) % 5 else dk(K['dining_floor'], .15)
    tx = 2 + (seed * 5) % 6
    if tx <= x < tx + 5 and 3 <= y < 7:
        return K['cloth'] if y >= 6 else dk(K['cloth'], .12) if y >= 4 else dk(K['cloth'], .3)
    dx = tx + 6 if tx < 5 else tx - 2.5
    if dx <= x < dx + 1.5 and 3 <= y < 11:  # diner
        if y >= 9:
            return C('#e6b48c')
        return C('#3f5d96') if seed % 2 else C('#8a3b52')
    # wall: wainscot + warm paint + a framed picture
    if y < 9:
        return K['dining_dk'] if int(x * 2) % 3 else dk(K['dining_dk'], .2)
    if y < 10:
        return lt(K['dining_dk'], .2)
    px = 8 - (seed % 2) * 6
    if px <= x < px + 3 and 12 <= y < 16:
        return C('#2c3a2a') if px + 0.5 <= x < px + 2.5 and 12.5 <= y < 15.5 else C('#e8d5a8')
    return K['dining_wall'] if (int(x * 2) + int(y)) % 7 else lt(K['dining_wall'], .06)


def pass_px(s_, z, seed):
    """Pass-through window on a wall face, or None."""
    x0, x1, z0, z1 = 2.0, 14.0, 16, 46
    if x0 - 1 <= s_ < x1 + 1 and z0 - 3 <= z < z0:  # steel pass shelf
        if z >= z0 - 1:
            return K['steel_hi']
        return K['steel'] if z >= z0 - 2 else K['steel_dk']
    if not (x0 <= s_ < x1 and z0 <= z < z1):
        return None
    x, y = s_ - x0, z - z0
    w, hh = x1 - x0, z1 - z0
    if x < 0.5 or x >= w - 0.5 or y >= hh - 1:
        return K['steel_dk']
    # heat lamp housing along the top with a glowing strip
    if y >= hh - 4:
        return K['steel_deep'] if y >= hh - 3 else K['heat']
    # order tickets clipped under the lamp
    for k, tx in enumerate((1.5, 5.0, 8.5)):
        if (seed + k) % 3 == 2:
            continue
        if tx <= x < tx + 2 and hh - 9 <= y < hh - 4:
            if y >= hh - 5:
                return C('#9aa1ab')
            return C('#d9443a') if int(y) == hh - 6 and x < tx + 1.5 else C('#ffffff') if (int(y) + k) % 2 else C('#e9e6dd')
    # a plate waiting on the pass
    if seed % 2 == 0 and 4 <= x < 8 and y < 3:
        if y < 1:
            return C('#d8d8d8')
        return C('#e8590c') if 5 <= x < 7 and y >= 1.5 else C('#5fa83d') if y >= 1 and (x < 5 or x >= 7) else C('#ffffff')
    c = dining_px(x - 0.5, y, seed)
    glow = max(0.0, (y - (hh - 12)) / 12.0) * 0.25  # warm light from the heat lamp
    if x < 1.0:
        c = dk(c, .3)  # reveal shadow
    return mix(c, K['heat'], glow) if glow > 0 else c


def wall_tex(seed, window=False, deco=None):
    def tex(role, a, d, z, A_, D_, Hh, sf):
        if sf == 'T':
            return K['cap'] if d < D_ - 0.5 else dk(K['cap'], .08)
        inner = (sf == 'L' and d >= D_ - 0.01) or (sf == 'R' and a >= A_ - 0.01)
        s_ = a if sf == 'L' else d
        if not inner:
            return dk(K['cap'], .3) if z < Hh - 1 else K['cap']
        dim = 0.0 if sf == 'L' else 0.12
        if window:
            c = pass_px(s_, z, seed)
            if c is not None:
                return dk(c, dim * 0.5)
        if z < 5:  # steel kick plate
            c = K['steel_hi'] if z >= 4 else K['steel'] if z >= 1 else K['steel_dk']
            if z < 4 and (s_ * 2 + z) % 9 < 0.6:
                c = lt(c, .2)
            return dk(c, dim)
        if z >= Hh - 3:
            return dk(K['cap'] if z < Hh - 1 else lt(K['cap'], .3), dim)
        zz = z - 5
        row = int(zz // 4)
        if zz % 4 < 1:
            return dk(K['tile_grout'], dim)
        off = 2 if row % 2 else 0
        if (s_ + off) % 4 < 0.5:
            return dk(K['tile_grout'], dim)
        c = K['band'] if row in (5, 6) else K['tile']
        if zz % 4 >= 3:
            c = lt(c, .5)
        elif (s_ + off) % 4 < 1.0:
            c = lt(c, .25)
        elif H('t', seed, row, int((s_ + off) // 4)) < 0.15 and c == K['tile']:
            c = dk(c, .035)
        return dk(c, dim + 0.03)
    return tex


def wall_seg(side, seed, window=False, end=False):
    s = Spr(110, 120, 55, 80)
    tex = wall_tex(seed, window)
    if side == 'rw':
        box(s, 0, -3, 0, SEG, 3, W_H, tex, 'TL' + ('R' if end else ''))
    else:
        box(s, -3, 0, 0, 3, SEG, W_H, tex, 'TR' + ('L' if end else ''))
    return s.crop()


def wall_corner():
    s = Spr(30, 90, 15, 76)
    box(s, -3, -3, 0, 3, 3, W_H, lambda *a: K['cap'], 'T')
    return s.crop()


# ---------------------------------------------------------------- station (desk) and duckboard (chair)


def station(o='rw', seed=0):
    """Steel cooking station, 20x10 units: cabinet doors, a gas burner with its flame ring, a cutting board with vegetables,
    a back shelf with sauces."""
    s = Spr(150, 130, 75, 70)
    A_, D_ = 20, 10
    st = K['steel']

    def body(role, a, d, z, AA, DD, Hh, sf):
        if role == 'T':
            return st
        if role != 'F':
            return dk(st, .3) if z >= 1 else K['steel_deep']
        if z < 1.5:
            return K['steel_deep']
        if a < 0.4 or a >= AA - 0.4 or abs(a - AA / 2) < 0.25:
            return dk(st, .25)
        if Hh - 3 <= z < Hh - 2.4 and (2 <= a < AA / 2 - 2 or AA / 2 + 2 <= a < AA - 2):
            return K['steel_hi']  # bar handles
        if z >= Hh - 1.5:
            return lt(st, .15)
        if (a * 2 + z * 0.6) % 11 < 0.7:
            return lt(st, .18)  # brushed sheen
        return dk(st, .06)
    part(s, lambda t: obox(t, o, 0.3, 0.3, 0, A_ - 0.6, D_ - 0.6, 12, body), .4)

    def top(role, a, d, z, AA, DD, Hh, sf):
        if role == 'T':
            if a >= AA - 0.5 or d >= DD - 0.5:
                return K['steel_hi']
            return lt(st, .1)
        return K['steel_hi'] if z >= Hh - 0.5 else st
    part(s, lambda t: obox(t, o, 0, 0, 12, A_, D_, 2, top), .45)
    # back splash + shelf on posts
    part(s, lambda t: obox(t, o, 0, 0, 14, A_, 0.8, 7, M(K['steel'], K['steel'], K['steel_dk'])), .35)
    part(s, lambda t: (obox(t, o, 0.4, 0.2, 21, 1, 1, 8, M(K['steel_dk'])),
                       obox(t, o, A_ - 1.4, 0.2, 21, 1, 1, 8, M(K['steel_dk']))), .35)
    part(s, lambda t: obox(t, o, 0, 0, 29, A_, 3, 1, M(K['steel_hi'], K['steel'], K['steel_dk'])), .4)
    # sauces on the shelf
    bottles = [(1.5, C('#3a2018'), 4), (3.2, C('#d9443a'), 3), (5, C('#f2efe6'), 3), (7, C('#5d8a2f'), 4),
               (13.5, C('#e8a33d'), 3), (15.3, C('#3a2018'), 4), (17.2, C('#d9443a'), 3)]
    part(s, lambda t: [obox(t, o, ba, 0.8, 30, 1.2, 1.2, bh, M(bc)) for ba, bc, bh in bottles], .35)
    # gas burner: cast-iron grate with a blue flame ring
    def burner(role, a, d, z, AA, DD, Hh, sf):
        if role != 'T':
            return C('#2a2c31')
        r = math.hypot((a - AA / 2), (d - DD / 2))
        if 1.0 <= r < 1.6:
            return C('#69c3ff') if (int(a * 2) + int(d * 2)) % 2 else C('#ffb347')
        if r < 1.0:
            return C('#1b1c20')
        if abs(a - AA / 2) < 0.3 or abs(d - DD / 2) < 0.3 or r > 2.6:
            return C('#4a4d55')
        return C('#2a2c31')
    part(s, lambda t: obox(t, o, 11.5, 2.5, 14, 6, 6, 1, burner), .3)
    # small stock pot on the back corner
    part(s, lambda t: (obox(t, o, 15.5, 1, 14, 3.5, 3.5, 4, M(C('#b9c0c8'))),
                       obox(t, o, 15.8, 1.3, 18, 2.9, 2.9, 0.01, lambda *a: C('#c96a2b'), 'T')), .35)
    # cutting board, knife, vegetables
    def board(role, a, d, z, AA, DD, Hh, sf):
        if role != 'T':
            return K['wood_dk']
        if a < 0.4 or d < 0.4 or a >= AA - 0.4 or d >= DD - 0.4:
            return dk(K['wood'], .1)
        return K['wood'] if int(a * 3 + d) % 7 else lt(K['wood'], .1)
    part(s, lambda t: obox(t, o, 1.5, 3.5, 14, 7.5, 5, 0.8, board), .3)
    part(s, lambda t: (obox(t, o, 2.2, 4.3, 14.8, 2, 1.2, 1, M(C('#e8590c'))),
                       obox(t, o, 4.6, 4.4, 14.8, 1, 1, 1, M(C('#5fa83d'))),
                       obox(t, o, 5.8, 4.2, 14.8, 1, 1, 1, M(C('#5fa83d'))),
                       obox(t, o, 3.0, 6.4, 14.8, 1.6, 1.6, 1.2, M(C('#f2efe6'))),
                       obox(t, o, 5.2, 6.6, 14.8, 3.2, 0.7, 0.4, M(C('#dfe5ea'))),
                       obox(t, o, 8.4, 6.6, 14.8, 1, 0.7, 0.6, M(C('#2a2c31')))), .3)
    return s.crop()


def duckboard(o='rw'):
    """Wooden slatted floor grate the cook stands on, centred on the chair point."""
    s = Spr(50, 40, 25, 20)

    def tex(role, a, d, z, AA, DD, Hh, sf):
        if sf != 'T':
            return K['wood_dk']
        if int(a * 2) % 3 == 2:
            return dk(K['wood_dk'], .3)
        return K['wood'] if a % 1.5 > 0.4 else lt(K['wood'], .12)
    part(s, lambda t: obox(t, o, -4, -2.5, 0, 8, 5, 1, tex), .35)
    return s.crop()


def crate(s, u, v, col=C('#a8743f'), n=4):
    """Upturned wooden produce crate (n x n x 7 units) drawn into s at iso offset (u, v) from its anchor."""
    def tex(role, a, d, z, AA, DD, Hh, sf):
        if sf == 'T':
            if a < 0.5 or d < 0.5 or a >= AA - 0.5 or d >= DD - 0.5:
                return lt(col, .2)
            return col if (int(a) + int(d)) % 2 else dk(col, .12)
        sx = a if sf == 'L' else d
        c = col if sf == 'L' else dk(col, .22)
        if 2.5 <= z < 4.5 and 0.5 <= sx < AA - 0.5:
            return dk(col, .6)
        if z >= Hh - 1:
            return lt(c, .15)
        return c
    part(s, lambda t: box(t, u - n / 2, v - n / 2, 0, n, n, SEAT_Z, tex), .4)


# ---------------------------------------------------------------- people (HD: drawn at people_hd.DETAIL x, like A/B/C's)
# The cooks reuse the office people's heads, faces, hair, hands and shading (people_hd.py, imported, unchanged); only the
# clothes (white double-breasted jacket, apron, toque/bandana/cap) and the poses are E's own.

DETAIL = PH.DETAIL
JACKET = '#f6f2ea'


def _cook(**d):
    d.setdefault('shirt', JACKET)
    d['pants'] = '#2c2b33' if d.get('pants', 'check') == 'black' else '#5a5d6a'
    d.setdefault('pants_kind', 'black' if d['pants'] == '#2c2b33' else 'check')
    d.setdefault('shoe', '#2a2228')
    d['top'] = 'chef'
    return d


COOKS = [
    _cook(skin='light', hair='#5c3a25', style='short', hat='toque', apron='#2f4a7a', neck='#d9443a', pants='check'),
    _cook(skin='medium', hair='#2a2028', style='ponytail', hat='bandana', band='#d63c3c', apron='#34343c', pants='black'),
    _cook(skin='dark', hair='#1f1a20', style='curly', hat='toque', apron='#2f7d4f', neck='#f2b33d', pants='check'),
    _cook(skin='pale', hair='#f0c060', style='long', hat='bandana', band='#2f6fd1', apron='#8a5a33', pants='black',
          freckles=True),
    _cook(skin='tan', hair='#b5532a', style='messy', hat='cap', band='#34343c', apron='#b8323a', pants='check', beard=True),
    _cook(skin='pale', hair='#c9c9cf', style='short', hat='tall', apron='#6b2d3c', neck='#2f6fd1', pants='black',
          glasses='#2a2228'),
    _cook(skin='light', hair='#7a4a2c', style='twintail', hat='bandana', band='#f2a93d', apron='#d0574a', pants='check'),
    _cook(skin='brown', hair='#231c24', style='bun', hat='cap', band='#f4f0e6', apron='#3a6f8f', pants='black',
          glasses='#7a3a2a'),
    _cook(skin='medium', hair='#d4683a', style='bob', hat='toque', apron='#5a8f3a', neck='#e85a7a', pants='check'),
    _cook(skin='dark', hair='#2c2028', style='short', hat='bandana', band='#2f9a6a', apron='#34343c', pants='black',
          beard=True),
]

GOLD = ramp('#f2c14e')
STEEL = ramp('#c3cad2')
WOK = ramp('#4a4a54')
WOOD = ramp('#b98552')
CRATE = ramp('#b07a45')
FLAME = [C('#c92a2a'), C('#f76707'), C('#ffa94d'), C('#ffe066'), C('#fff9db')]
STEAM = C('#ffffff', 200)
SPARK = C('#ffe066')


def _check(f, P, m):
    """Houndstooth-ish checks on the pants mask m (check trousers only)."""
    if P['pants_kind'] != 'check':
        return
    d = ramp(P['pants'])['ln']
    for (x, y) in m:
        if (x + y) % 4 == 0 and (x - y) % 4 == 0:
            f.col[(x, y)] = d


def legs(f, P, spread=0):
    """Standing legs (people_hd's, chunky), checked when the cook wears check trousers."""
    f.lift = 0
    PH.legs_front(f, P, spread)
    t = -31 + PH.LIFT
    _check(f, P, f.mask(('r', (-11, t - 5, 11, -8))))


def apron_r(P):
    return ramp(P['apron'])


def chef_front(f, P, poly, sleeves, skirt=-20):
    """A white double-breasted jacket seen from the front with an apron (bib and skirt down to [skirt]), then the sleeves."""
    sk, hair, jk, *_ = looks(P)
    body = f.mask(('p', poly))
    f.paint(body, jk, 'shirt')
    # the jacket's flap: two rows of cloth buttons, the overlap's edge
    f.vl(5, -56, -36, jk['sh'])
    for y in (-55, -50):
        f.px(-6, y, jk['ln']); f.px(8, y, jk['ln'])
    ap = apron_r(P)
    bib = f.mask(('r', (-8, -47, 7, -34)), ('p', [(-12, -36), (11, -36), (13, skirt), (-13, skirt)]))
    f.paint(bib, ap, 'apron')
    f.hl(-4, 3, -42, ap['sh'])  # a pocket on the bib
    f.vl(-4, -42, -38, ap['sh']); f.vl(3, -42, -38, ap['sh'])
    f.hl(-13, 12, -35, ap['dp'])  # waist tie
    for y in range(-31, skirt, 3):  # folds
        f.px(-6, y, ap['sh']); f.px(6, y + 1, ap['sh'])
    for i in range(10):  # neck straps
        f.px(-8 + i // 4, -47 - i, ap['dp']); f.px(7 - i // 4, -47 - i, ap['dp'])
    for m in sleeves:
        f.paint(m, jk, 'sleeve')


def collar(f, P, cx=0, cy=-74):
    """The jacket's stand collar and the neckerchief, drawn after the head so they wrap the neck."""
    sk, hair, jk, *_ = looks(P)
    f.paint(f.mask(('r', (cx - 7, cy + 15, cx + 7, cy + 18))), jk, 'collar', hl=False)
    if P.get('neck'):
        nk = ramp(P['neck'])
        f.paint(f.mask(('p', [(cx - 4, cy + 15), (cx + 4, cy + 15), (cx, cy + 21)])), nk, 'neck2', hl=False)


def hat(f, P, cx, cy, back=False):
    """Toque (puffy white), tall toque, bandana (dotted, knot) or a skull cap with a folded band, over people_hd's hair."""
    kind = P['hat']
    if kind in ('toque', 'tall'):
        W = ramp('#fdfbf6')
        top = cy - (36 if kind == 'tall' else 29)
        puff = f.mask(('e', (cx - 15, top, cx + 15, cy - 8)), ('e', (cx - 18, top + 7, cx - 3, cy - 8)),
                      ('e', (cx + 3, top + 7, cx + 18, cy - 8)))
        f.paint(puff, W, 'hat', shade='rb')
        for x in range(cx - 10, cx + 11, 5):
            f.vl(x, top + 9, cy - 15, W['sh'])
        f.paint(f.mask(('r', (cx - 15, cy - 15, cx + 15, cy - 8))), W, 'hatband', shade='rb')
        f.hl(cx - 14, cx + 14, cy - 15, W['sh'])
    else:
        R = ramp(P['band'])
        cap = f.mask(('e', (cx - 16, cy - 19, cx + 16, cy + 5)))
        cap = {p for p in cap if p[1] - f.lift <= cy - 6 + (abs(p[0] - cx) // 5 if kind == 'bandana' else 0)}
        f.paint(cap, R, 'hat', shade='rb')
        if kind == 'bandana':
            for (x, y) in cap:
                if (x * 3 + (y - f.lift) * 5) % 13 == 0 and (x - cx) ** 2 < 150:
                    f.col[(x, y)] = lt(R['b'], .7)
            if back:
                kx = cx + 1
                f.paint(f.mask(('e', (kx - 3, cy - 8, kx + 3, cy - 3))), R, 'knot')
                f.paint(f.mask(('p', [(kx - 2, cy - 4), (kx + 1, cy - 4), (kx - 4, cy + 7), (kx - 7, cy + 5)]),
                               ('p', [(kx, cy - 4), (kx + 3, cy - 4), (kx + 6, cy + 6), (kx + 3, cy + 7)])), R, 'tails')
            else:
                f.paint(f.mask(('e', (cx + 11, cy - 11, cx + 17, cy - 5))), R, 'knot')
                f.paint(f.mask(('p', [(cx + 15, cy - 9), (cx + 21, cy - 5), (cx + 18, cy - 2)])), R, 'tails')
        else:
            f.paint({p for p in cap if p[1] - f.lift >= cy - 10}, ramp(R['dp']), 'hatband', hl=False)
            f.hl(cx - 4, cx + 2, cy - 17, R['hi'])


def face_sleep(f, P, cx, cy):
    """Closes people_hd's open eyes: skin over them, a downward arc each."""
    sk = SKIN[P['skin']]
    ex, ey = (cx - 8, cx + 2), cy + 1
    for x0 in ex:
        for y in range(ey - 1, ey + 4):
            for x in range(x0, x0 + 3):
                f.px(x, y, sk['b'])
        f.px(x0 - 1, ey + 1, PH.EYE); f.hl(x0, x0 + 2, ey + 2, PH.EYE); f.px(x0 + 3, ey + 1, PH.EYE)
    my = ey + 8
    f.hl(cx - 5, cx, my - 1, sk['b']); f.hl(cx - 4, cx - 1, my, sk['b'])
    f.hl(cx - 3, cx - 2, my, PH.MOUTH); f.hl(cx - 3, cx - 2, my + 1, PH.MOUTH)


TORSO = [(-12, -58), (12, -58), (14, -52), (12, -33), (-12, -33), (-14, -52)]


def shoulders(f):
    return [f.mask(('e', (-16, -60, -6, -48))), f.mask(('e', (6, -60, 16, -48)))]


def flames(f, cx, base, height, phase, w=8):
    """Tongues of fire around cx rising from row base (absolute frame coords)."""
    for dx in range(-w, w + 1):
        hgt = height * (1 - (abs(dx) / (w + 1)) ** 1.5) + (H('fl', phase, dx // 2) - 0.5) * 5
        hgt = int(round(hgt))
        for k in range(max(hgt, 0)):
            t = k / max(hgt, 1)
            core = 1 - abs(dx) / (w + 1)
            lvl = int((1 - t) * 2.2 + core * 2.4)
            f.px(cx + dx, base - k, FLAME[max(0, min(4, lvl))], 'flame')


def back_body(f, P):
    """A standing cook seen from behind: jacket, crossed apron straps, the bow at the back, apron edges, head and hat."""
    sk, hair, jk, *_ = looks(P)
    legs(f, P)
    f.lift = PH.LIFT
    ap = apron_r(P)
    # apron skirt edges peeking at the sides
    f.paint(f.mask(('p', [(-13, -36), (-10, -36), (-11, -20), (-14, -20)]), ('p', [(10, -36), (13, -36), (14, -20), (11, -20)])),
            ap, 'apron')
    torso = f.mask(('p', TORSO))
    f.paint(torso, jk, 'shirt')
    f.vl(0, -56, -37, jk['sh'])  # back seam
    for i in range(19):  # straps crossing on the back
        f.px(-9 + i, -56 + i, ap['b']); f.px(-8 + i, -56 + i, ap['dp'])
        f.px(9 - i, -56 + i, ap['b']); f.px(8 - i, -56 + i, ap['dp'])
    f.paint(f.mask(('r', (-13, -37, 12, -34))), ap, 'tie', hl=False)
    f.paint(f.mask(('e', (-8, -41, -1, -32)), ('e', (0, -41, 7, -32))), ap, 'bow')
    f.paint(f.mask(('e', (-2, -39, 1, -34))), ramp(ap['dp']), 'knot', hl=False)
    f.paint(f.mask(('l', [(-1, -34), (-4, -26)], 3), ('l', [(0, -34), (3, -27)], 3)), ap, 'tails', shade='', hl=False)
    for m in shoulders(f):
        f.paint(m, jk, 'sleeve')
    PH.hair_back_layer(f, P, 2, -74, back=True)
    PH.head_back(f, P, 2, -74)
    hat(f, P, 2, -74, back=True)


def working(P, phase, chop=False):
    """At the station seen from behind (the station is ahead, up-right): tossing a wok over flames, or chopping."""
    f = Fig()
    sk, hair, jk, *_ = looks(P)
    if chop:
        # the board on the station top, a carrot and its slices, drawn first: the cook stands in front of it
        f.lift = 0
        f.paint(f.mask(('r', (12, -46, 42, -41))), WOOD, 'board')
        f.hl(12, 42, -40, WOOD['dp'])
        f.paint(f.mask(('e', (30, -52, 42, -46))), ramp('#e8590c'), 'carrot')
        f.paint(f.mask(('p', [(40, -52), (46, -58), (44, -50)])), ramp('#5fa83d'), 'greens')
        for i, x in enumerate((19, 23, 27)):
            f.paint(f.mask(('e', (x - 1, -49, x + 2, -46))), ramp('#f08a3a' if i % 2 else '#e8590c'), 'slice', hl=False)
    back_body(f, P)
    f.lift = 0
    # left arm hanging, its hand on the station's edge
    f.paint(f.mask(('l', [(-12, -48), (-16, -40), (-15, -32)], 7)), jk, 'sleeveL')
    PH.cuff(f, jk, -15, -33)
    PH.hand(f, sk, -19, -33, -12, -27, 'handL', thumb=1)
    if not chop:
        rim = (-58, -64, -70, -62)[phase]
        flames(f, 30, -38, (16, 11, 20, 13)[phase], phase)
        # the wok: a dark bowl, a steel rim, food inside or in the air
        bowl = f.mask(('e', (19, rim - 6, 41, rim + 9)))
        bowl = {p for p in bowl if p[1] >= rim}
        f.paint(bowl, WOK, 'wok', shade='rb')
        f.paint(f.mask(('e', (19, rim - 2, 41, rim + 2))), ramp('#2a2830'), 'wokin', shade='', hl=False)
        f.hl(20, 40, rim - 2, STEEL['hi']); f.px(19, rim, STEEL['b']); f.px(41, rim, STEEL['b'])
        f.hl(22, 38, rim + 3, WOK['hi'])
        food = [ramp('#5fa83d'), ramp('#e8590c'), ramp('#ffe9a8'), ramp('#c0392b'), ramp('#ffe9a8')]
        arcs = {0: [(-6, -1), (-1, -2), (4, -1), (1, 0)],
                1: [(-7, -6), (0, -9), (5, -5), (3, -12)],
                2: [(-5, -14), (1, -19), (6, -12), (-1, -10), (8, -17)],
                3: [(-5, -6), (2, -4), (6, -8), (0, -10)]}[phase]
        for i, (dx, dy) in enumerate(arcs):
            x, y = 30 + dx, rim + dy
            f.paint(f.mask(('e', (x - 1, y - 1, x + 2, y + 1))), food[i % 5], f'food{i}', hl=False, shade='')
        # the long handle back to the right hand; the arm forward from the shoulder
        hx, hy = 15, rim + 7
        f.paint(f.mask(('l', [(20, rim + 1), (hx + 2, hy - 1)], 3)), ramp('#6b4a2e'), 'handle', shade='', hl=False)
        f.paint(f.mask(('l', [(12, -48), (16, -44), (hx, hy + 2)], 7)), jk, 'sleeveR')
        PH.cuff(f, jk, hx, hy + 2)
        PH.hand(f, sk, hx - 2, hy - 3, hx + 4, hy + 3, 'handR', thumb=-1)
        # sparks flying off
        if phase in (0, 2):
            for i in range(3):
                f.px(26 + int(H('sp', phase, i) * 10), rim + 6 - int(H('sy', phase, i) * 6), SPARK)
    else:
        # the cleaver: raised, falling, striking, rising
        hy = (-74, -64, -52, -64)[phase]
        hx = 20 + (phase == 2)
        f.paint(f.mask(('l', [(12, -48), (16, -50), (hx, hy + 3)], 7)), jk, 'sleeveR')
        PH.cuff(f, jk, hx, hy + 3)
        bx = hx + 4
        if phase == 2:  # blade down on the board
            f.paint(f.mask(('r', (bx, hy + 1, bx + 9, hy + 7))), STEEL, 'blade')
            f.hl(bx, bx + 9, hy + 7, STEEL['hi'])
            for dx, dy, c in ((bx + 12, hy - 2, '#e8590c'), (bx + 15, hy + 2, '#f08a3a'), (bx - 3, hy - 3, '#e8590c')):
                f.paint(f.mask(('e', (dx, dy, dx + 2, dy + 2))), ramp(c), 'chip', hl=False, shade='')
            for x in (bx - 4, bx + 12):
                f.vl(x, hy + 4, hy + 6, SPARK)
        else:  # blade up, its edge down
            f.paint(f.mask(('r', (bx, hy - 5, bx + 9, hy + 2))), STEEL, 'blade')
            f.hl(bx, bx + 9, hy + 2, STEEL['hi'])
            f.paint(f.mask(('e', (bx + 6, hy - 4, bx + 8, hy - 2))), ramp('#5d6570'), 'hole', shade='', hl=False)
            if phase == 1:  # motion lines
                f.vl(bx + 12, hy - 6, hy - 1, STEAM); f.hl(bx + 2, bx + 7, hy - 9, STEAM)
        f.paint(f.mask(('r', (hx + 1, hy - 1, bx, hy + 2))), ramp('#5a3420'), 'grip', shade='', hl=False)
        PH.hand(f, sk, hx - 3, hy - 2, hx + 3, hy + 4, 'handR', thumb=-1)
    sp = f.finish()
    sp.ay -= SEAT_Z * DETAIL  # the layout lifts a seated person's feet point by the seat height; the cook stands on the floor
    return sp


def waiting(P, phase):
    """Front 3/4: an open hand raised high, calling out, ringing a brass hand bell in the other."""
    f = Fig()
    sk, hair, jk, *_ = looks(P)
    legs(f, P)
    f.lift = PH.LIFT
    PH.hair_back_layer(f, P, 0, -74)
    dx = (0, 1, 0, -1)[phase]
    sw = (-2, 0, 2, 0)[phase]
    sleeves = [f.mask(('l', [(-12, -54), (-19, -52), (-24, -58)], 7)),
               f.mask(('l', [(12, -54), (19, -64), (20 + dx, -76)], 7))]
    chef_front(f, P, TORSO, shoulders(f) + sleeves)
    PH.cuff(f, jk, -24, -59)
    PH.cuff(f, jk, 20 + dx, -75)
    # the bell hangs from the left hand by its handle, swinging
    bx, by = -26 + sw, -50
    f.paint(f.mask(('l', [(-26, -60), (bx, by - 4)], 3)), ramp('#6b4a2e'), 'bellh', shade='', hl=False)
    f.paint(f.mask(('e', (bx - 6, by - 6, bx + 6, by + 10))) - f.mask(('r', (bx - 7, by + 5, bx + 7, by + 11))), GOLD, 'bell')
    f.paint(f.mask(('e', (bx - 8, by + 3, bx + 8, by + 6))), GOLD, 'lip')
    f.paint(f.mask(('e', (bx - 2 - sw // 2, by + 6, bx + 1 - sw // 2, by + 9))), ramp('#7a5a1a'), 'clapper', hl=False)
    f.px(bx - 3, by - 3, lt(GOLD['b'], .6)); f.px(bx - 4, by - 1, lt(GOLD['b'], .6))
    PH.hand(f, sk, -29, -64, -23, -58, 'handL', thumb=1)
    # ring marks on the side it swings to
    side = (-1, 0, 1, 0)[phase]
    for s_ in ((side,) if side else (-1, 1)):
        n = 2 if side else 1
        for k in range(n):
            ox = bx + s_ * (10 + k * 4)
            h_ = 3 + k * 2
            for y in range(by - h_ + (phase == 3), by + h_ + 2 + (phase == 3)):
                f.px(ox + s_ * (1 if abs(y - by - 1) > h_ - 2 else 0), y, SPARK, 'ring')
    # the open hand: palm, four fingers apart, the thumb out (people_hd's)
    hx, hy = 20 + dx, -82
    f.paint(f.mask(('e', (hx - 5, hy - 2, hx + 5, hy + 8))), sk, 'hand', hl=False)
    for fx, ln in ((-4, 5), (-1, 7), (2, 7), (5, 5)):
        f.paint(f.mask(('r', (hx + fx, hy - ln, hx + fx + 1, hy))), sk, 'hand', shade='', hl=False)
        f.px(hx + fx + 1, hy - ln + 1, sk['sh'])
    f.paint(f.mask(('l', [(hx - 5, hy + 4), (hx - 9, hy - 1)], 3)), sk, 'hand', shade='', hl=False)
    f.hl(hx - 3, hx + 3, hy + 6, sk['sh'])
    PH.head_front(f, P, 'calling' if phase % 2 == 0 else 'open')
    collar(f, P)
    hat(f, P, 0, -74)
    return f.finish()


def done_pose(P, phase):
    """Front 3/4, eyes closed in a smile, presenting a plate under a silver cloche, steam escaping from under its rim."""
    f = Fig()
    sk, hair, jk, *_ = looks(P)
    legs(f, P)
    f.lift = PH.LIFT
    PH.hair_back_layer(f, P, 0, -74)
    sleeves = [f.mask(('l', [(-12, -54), (-17, -46), (-15, -38)], 7)),
               f.mask(('l', [(12, -54), (17, -46), (15, -38)], 7))]
    chef_front(f, P, TORSO, shoulders(f) + sleeves)
    PH.head_front(f, P, 'happy')
    collar(f, P)
    hat(f, P, 0, -74)
    # the plate, the dome on it, the hands under the plate's rim
    f.paint(f.mask(('e', (-20, -42, 20, -36))), ramp('#fbfaf6'), 'plate')
    f.hl(-17, 17, -37, ramp('#fbfaf6')['sh'])
    dome = {p for p in f.mask(('e', (-15, -56, 15, -24))) if p[1] - f.lift <= -40}
    f.paint(dome, STEEL, 'dome', shade='rb')
    f.hl(-15, 15, -40, STEEL['dp'])
    for i in range(5):
        f.px(-10 + i // 2, -50 + i, STEEL['hi']); f.px(-9 + i // 2, -50 + i, STEEL['hi'])
    f.paint(f.mask(('e', (-3, -59, 3, -54))), STEEL, 'knob')
    PH.cuff(f, jk, -16, -38)
    PH.cuff(f, jk, 16, -38)
    PH.hand(f, sk, -22, -41, -16, -35, 'handL', thumb=1)
    PH.hand(f, sk, 16, -41, 22, -35, 'handR', thumb=-1)
    sp = f.finish()
    # steam curling up from under the rim, past the dome; a sparkle that blinks on the dome
    for side, x0 in ((-1, -21), (1, 21)):
        for k in range(9):
            y = -37 - k * 3 - (phase % 2) * 2 + PH.LIFT
            x = x0 + side * (((k + phase // 2) // 2) % 2) * 2
            sp.put(x + sp.ax, y + sp.ay, STEAM)
            sp.put(x + sp.ax, y - 1 + sp.ay, STEAM)
    if phase in (1, 2):
        arms = ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2)) if phase == 1 else ((0, 0), (1, 0), (0, 1), (1, 1))
        for dx, dy in arms:
            sp.put(9 + dx + sp.ax, -47 + PH.LIFT + dy + sp.ay, C('#fff8c8'))
    return sp


def idle(P, phase):
    """Dozing on an upturned produce crate, hands in the lap, head drooping, hat askew, a bubble at the nose."""
    f = Fig()
    sk, hair, jk, pants, shoe, *_ = looks(P)
    b = (0, 0, 1, 1)[phase]  # breathing
    # the crate: front, right side, slatted top; the floor point is the anchor
    f.paint(f.mask(('p', [(-16, -18), (-8, -24), (22, -24), (14, -18)])), ramp(lt(CRATE['b'], .15)), 'cratetop', shade='')
    f.paint(f.mask(('p', [(14, -18), (22, -24), (22, -6), (14, 0)])), ramp(CRATE['sh']), 'crateside', shade='', hl=False)
    f.paint(f.mask(('r', (-16, -18, 14, 0))), CRATE, 'cratefront', shade='')
    for y in (-12, -6):
        f.hl(-15, 13, y, CRATE['dp'])
    f.vl(-1, -17, 0, CRATE['sh'])
    f.paint(f.mask(('r', (-10, -10, 8, -8))), ramp(CRATE['ln']), 'handhole', shade='', hl=False)
    # legs: thighs on the crate, shins down its front, shoes on the floor
    th = f.mask(('r', (-12, -24, 12, -16)))
    f.paint(th, pants, 'thigh')
    f.paint(f.mask(('r', (-11, -17, -3, -6))), pants, 'shinL', shade='r')
    f.paint(f.mask(('r', (3, -17, 11, -6))), pants, 'shinR', shade='r')
    _check(f, P, f.mask(('r', (-12, -24, 12, -6))))
    f.paint(f.mask(('e', (-13, -8, -1, 1))), shoe, 'shoeL')
    f.paint(f.mask(('e', (1, -8, 13, 1))), shoe, 'shoeR')
    for x in range(-14, 15):
        f.col.pop((x, 1), None)
    # the body, slumped (lowered by the seat and a breath)
    f.lift = 10 + b
    hx, hy = 2, -70
    PH.hair_back_layer(f, P, hx, hy)
    sleeves = [f.mask(('l', [(-12, -54), (-15, -44), (-8, -38)], 7)),
               f.mask(('l', [(12, -54), (15, -44), (8, -38)], 7))]
    chef_front(f, P, TORSO, shoulders(f) + sleeves, skirt=-28)
    PH.hand(f, sk, -9, -42, -2, -35, 'handL', thumb=1)
    PH.hand(f, sk, 1, -42, 8, -35, 'handR', thumb=-1)
    PH.head_front(f, P, 'open', hy=hy, hx=hx)
    face_sleep(f, P, hx, hy)
    collar(f, P, hx - 2, hy - 2)
    hat(f, P, hx + 3, hy + 1)
    # the bubble at the nose, growing then popping
    r = (2, 4, 6, 0)[phase]
    if r:
        bx, by = hx - 3 - r, hy + 8
        f.paint(f.mask(('e', (bx - r, by - r, bx + r, by + r))), ramp('#d4ecff'), 'bubble', shade='rb')
        f.px(bx - r // 2, by - r // 2, WHITE)
    sp = f.finish()
    sp.ay -= SEAT_Z * DETAIL
    return sp


def _align(sprs):
    """The sprites on one canvas each, sharing size and anchor (the union of their boxes, kept even)."""
    x0 = min(-s.ax for s in sprs)
    y0 = min(-s.ay for s in sprs)
    x1 = max(s.w - s.ax for s in sprs)
    y1 = max(s.h - s.ay for s in sprs)
    x0 -= x0 % 2
    y0 -= y0 % 2
    w, h = x1 - x0, y1 - y0
    w += w % 2
    h += h % 2
    out = []
    for s in sprs:
        n = Spr(w, h, -x0, -y0)
        n.img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        n.img.paste(s.img, (-x0 - s.ax, -y0 - s.ay))
        n.em = Image.new('L', (w, h), 0)
        n._load()
        out.append(n)
    return out


def person_frames(P, vi):
    """name -> Spr for the 4 states x 4 phases of cook P (and working mirrored for stations along v, `_lw`); all phases of
    one state share size and anchor."""
    out = {}
    for st in STATES:
        if st == 'working':
            fr = [working(P, ph, chop=(vi % 2 == 1)) for ph in range(PHASES)]
        elif st == 'waiting':
            fr = [waiting(P, ph) for ph in range(PHASES)]
        elif st == 'done':
            fr = [done_pose(P, ph) for ph in range(PHASES)]
        else:
            fr = [idle(P, ph) for ph in range(PHASES)]
        for ph, sp in enumerate(_align(fr)):
            out[f'person{vi}_e{ph}_{st}'] = sp
            if st == 'working':
                out[f'person{vi}_e{ph}_{st}_lw'] = PH.flip(sp)
    return out


# ---------------------------------------------------------------- rug, nameplate, decor


def mat(i, A_=24, D_=16):
    """Interlocking rubber anti-fatigue mat tile with drainage holes."""
    s = Spr(4 * (A_ + D_) + 10, 2 * (A_ + D_) + 10, 2 * D_ + 5, 5)
    field, hole, edge = K['mat'][i]

    def tex(role, a, d, z, AA, DD, Hh, sf):
        if sf != 'T':
            return dk(field, .35)
        e = min(a, d, AA - a, DD - d)
        if e < 0.6:
            return dk(field, .2)
        if e < 1.4:
            return edge
        if (a % 3 < 1.0) and (d % 3 < 1.0):
            return hole
        if (a % 3 < 1.3) and (d % 3 < 1.3):
            return lt(field, .06)
        return field
    box(s, 0, 0, 0, A_, D_, 1, tex)
    return s.crop()


def nameplate(w):
    """Order-ticket rail sign: a steel rail with clips over a white ticket, on two steel legs. Same rows as A's plate."""
    h = 10
    s = Spr(w + 4, h + 8, (w + 4) // 2, h + 7)
    for x in (w // 2 - 6, w // 2 + 6):
        s.rect(x + 1, h, x + 2, h + 6, K['steel_dk'])
        s.rect(x, h + 6, x + 3, h + 6, K['steel_deep'])
    s.rect(1, 0, w + 2, h, K['steel_deep'])
    s.rect(2, 3, w + 1, h - 1, C('#fffdf6'))
    s.rect(2, h - 1, w + 1, h - 1, C('#e9e3d2'))
    s.rect(1, 0, w + 2, 2, K['steel'])
    s.rect(1, 0, w + 2, 0, K['steel_hi'])
    for x in (4, w - 1):  # clips in the caps
        s.rect(x, 1, x + 1, 4, C('#d9443a'))
    s.rect(3, h - 2, 9, h - 2, C('#ece6d6'))
    outline_out(s, INK)
    return s


def tex_front(fn, side=None):
    def f(role, a, d, z, A_, D_, Hh, sf):
        if role == 'F':
            return fn(a, z, A_, Hh)
        return side(role, a, d, z, A_, D_, Hh, sf) if side else dk(fn(a, z, A_, Hh) or K['steel'], .25)
    return f


def fridge(o='lw'):
    """Tall two-door stainless fridge, 14x6x44."""
    s = Spr(80, 100, 40, 80)
    st = K['steel']

    def front(a, z, A_, Hh):
        if z < 2:
            return K['steel_deep']
        if a < 0.4 or a >= A_ - 0.4 or z >= Hh - 1 or abs(a - A_ / 2) < 0.2:
            return dk(st, .25)
        if Hh - 6 <= z < Hh - 1:  # top grille / display
            if 2 <= a < 4 and Hh - 4 <= z < Hh - 2:
                return C('#7ee0a8')
            return K['steel_deep'] if int(z) % 2 else dk(st, .4)
        if (A_ / 2 - 1.4 <= a < A_ / 2 - 0.8 or A_ / 2 + 0.8 <= a < A_ / 2 + 1.4) and 18 <= z < 30:
            return K['steel_hi']
        if (a * 2 + z * 0.5) % 13 < 1:
            return lt(st, .2)
        return st
    part(s, lambda t: obox(t, o, 0, 0, 0, 14, 6, 44, tex_front(front, M(st, st, K['steel_dk']))), .45)
    # a sticky note and a magnet
    part(s, lambda t: obox(t, o, 2, 6, 30, 2, 0.1, 3, lambda *a: C('#ffe066')), 0)
    return s.crop()


def pot_shelf(o='lw'):
    """Wire shelving with stock pots, pans and stacked plates."""
    s = Spr(80, 100, 40, 80)
    wire = K['steel_dk']
    levels = (3, 16, 29, 41)
    for i, z in enumerate(levels):
        part(s, lambda t, z=z: obox(t, o, 0, 0, z, 14, 6, 1, M(K['steel'], K['steel'], wire)), .3)
    part(s, lambda t: [obox(t, o, a, d, 0, 0.8, 0.8, 42, M(K['steel_hi'], K['steel'], wire)) for a in (0, 13.2) for d in (0, 5.2)], .3)
    # contents
    part(s, lambda t: (obox(t, o, 1, 0.8, 4, 5, 4.5, 7, M(C('#c3cad2'))),
                       obox(t, o, 1.2, 1, 11, 4.6, 4.1, 0.6, M(C('#dfe5ea'))),
                       obox(t, o, 7, 1, 4, 5, 4, 5, M(C('#2a2c31'))),
                       obox(t, o, 2, 1.5, 17, 4, 4, 5, M(C('#d5602b'))),
                       obox(t, o, 7.5, 1, 17, 5, 4.5, 4, M(C('#f2efe6'))),
                       obox(t, o, 7.5, 1, 21, 5, 4.5, 1, M(lt(C('#f2efe6'), .2))),
                       obox(t, o, 1, 1, 30, 6, 4, 4, M(C('#3a3a42'))),
                       obox(t, o, 8, 1.5, 30, 4, 3.5, 6, M(C('#b9c0c8'))),
                       obox(t, o, 3, 1, 42, 8, 4, 3, M(C('#e8d5a8')))), .35)
    return s.crop()


def rice_sacks(o='lw'):
    """Sacks of rice (쌀) on a wooden pallet."""
    s = Spr(90, 90, 45, 64)
    part(s, lambda t: obox(t, o, 0, 0, 0, 16, 8, 2, M(K['wood'], K['wood'], K['wood_dk'])), .4)

    def sack(seed):
        def f(role, a, d, z, A_, D_, Hh, sf):
            base = C('#efe8d6')
            if role == 'T':
                return lt(base, .1)
            c = base if sf == 'L' else dk(base, .15)
            if (a + d + z) % 2 < 0.5:
                c = dk(c, .05)
            if role == 'F' and 2 <= z < 4 and 1 <= a < A_ - 1:
                return C('#d9443a')
            if role == 'F' and 4.5 <= z < 5.5 and 2 <= a < A_ - 2:
                return C('#2f4a7a')
            return c
        return f
    part(s, lambda t: (obox(t, o, 0.5, 0.5, 2, 7, 6.5, 7, sack(0)), obox(t, o, 8.3, 0.5, 2, 7, 6.5, 7, sack(1))), .4)
    part(s, lambda t: obox(t, o, 4, 1, 9, 7.5, 6, 6, sack(2)), .4)
    return s.crop()


def extinguisher():
    s = Spr(30, 60, 15, 50)
    red = C('#d62f2f')
    part(s, lambda t: obox(t, 'rw', -2, -2, 0, 4, 4, 14, M(red, red, dk(red, .3))), .4)
    part(s, lambda t: (obox(t, 'rw', -0.6, -0.6, 14, 1.2, 1.2, 2, M(C('#2a2c31'))),
                       obox(t, 'rw', -1.8, -0.4, 16, 3, 0.8, 1, M(C('#3a3c44')))), .3)
    t = Spr(s.w, s.h, s.ax, s.ay)
    for y in range(4):
        t.put(s.ax + 4 + (y // 2), s.ay - 16 + y * 3, C('#1b1c20'))
        t.put(s.ax + 4 + (y // 2), s.ay - 15 + y * 3, C('#1b1c20'))
    s.paste(t, 0, 0)
    for x in range(-2, 3):
        s.put(s.ax + x, s.ay - 9, C('#ffffff'))
    return s.crop()


def bin_(col=C('#3f8f4a')):
    s = Spr(40, 50, 20, 38)
    part(s, lambda t: obox(t, 'rw', -3, -3, 0, 6, 6, 12, M(col, col, dk(col, .3))), .4)
    part(s, lambda t: obox(t, 'rw', -3.4, -3.4, 12, 6.8, 6.8, 1, M(lt(col, .15))), .4)
    return s.crop()


def crate_stack():
    s = Spr(50, 70, 25, 52)
    for i, (col, z) in enumerate(((C('#2f6fd1'), 0), (C('#e8a33d'), 7), (C('#2f8f6a'), 14))):
        def tex(role, a, d, zz, AA, DD, Hh, sf, col=col):
            c = col if sf in ('L', 'T') else dk(col, .22)
            sx = a if sf == 'L' else d
            if sf != 'T' and 2 <= zz < 5 and 0.8 <= sx % 2.5 < 1.9:
                return dk(col, .55)
            if sf == 'T':
                return lt(col, .1)
            return c
        part(s, lambda t, z=z, tex=tex: box(t, -3, -3, z, 6, 6, 7, tex), .4)
    # cabbages on top
    t = Spr(s.w, s.h, s.ax, s.ay)
    SP.foliage(t, s.ax - 2, s.ay - 23, [(-1, 0, 2.2), (2, 0, 2.2)], [C('#4b8a2e'), C('#7cc04f'), C('#b5e07a')])
    outline(t, None, .5)
    s.paste(t, 0, 0)
    return s.crop()


def ladle_rail(o='rw'):
    """Wall rail with hanging ladles, spatulas, a strainer and pans."""
    s = Spr(70, 70, 35, 50)
    part(s, lambda t: obox(t, o, 0, 0, 40, 18, 1, 1, M(K['steel_hi'], K['steel'], K['steel_dk'])), .35)
    tools = [(1, 'ladle'), (3.5, 'spat'), (6, 'ladle'), (8.5, 'pan'), (13, 'strain'), (16, 'ladle')]

    def t_(t):
        for a, kind in tools:
            if kind == 'pan':
                obox(t, o, a, 0.2, 26, 4, 0.5, 14, lambda r, aa, d, z, A_, D_, Hh, sf: (
                    None if ((aa - 2) * 1.0) ** 2 + ((z - 5) * 0.55) ** 2 > 4 and z < 10 else
                    (C('#6b4a2e') if z >= 10 and 1.6 <= aa < 2.4 else None) if z >= 10 else
                    (C('#2c2c33') if ((aa - 2)) ** 2 + ((z - 5) * 0.55) ** 2 > 2.4 else C('#3e3e46'))), 'F')
                continue
            obox(t, o, a, 0.2, 30, 0.5, 0.4, 10, M(K['steel_dk']), 'F')
            if kind == 'ladle':
                obox(t, o, a - 0.6, 0.2, 26, 1.7, 0.4, 4, M(K['steel']), 'F')
            elif kind == 'spat':
                obox(t, o, a - 0.5, 0.2, 25, 1.5, 0.4, 5, M(K['steel_hi']), 'F')
            else:
                obox(t, o, a - 1, 0.2, 25, 2.5, 0.4, 5, lambda r, aa, d, z, A_, D_, Hh, sf: C('#c3cad2') if (int(aa * 2) + int(z)) % 2 else C('#7d8590'), 'F')
    part(s, t_, .35)
    return s.crop()


def menu_board(o='lw'):
    """Order board: a chalkboard with today's dishes and tickets pinned along the top."""
    s = Spr(70, 70, 35, 50)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return K['wood_dk']
        if a < 0.6 or a >= A_ - 0.6 or z < 1 or z >= Hh - 1:
            return K['wood'] if z >= 1 else K['wood_dk']
        for i, tx in enumerate((1.5, 4.5, 7.5, 10.5, 13.5)):
            if tx <= a < tx + 2 and Hh - 6 <= z < Hh - 1:
                return C('#d9443a') if z >= Hh - 2 else C('#ffffff') if int(z) % 2 else C('#e9e6dd')
        line = int((Hh - 7 - z) // 3)
        if 0 <= line < 4 and (Hh - 7 - z) % 3 < 1 and 1.5 <= a < 4 + H('m', line) * 9:
            return C('#f2efe6') if line % 2 == 0 else C('#ffe066')
        return C('#2f3b33')
    part(s, lambda t: obox(t, o, 0, 0, 22, 16, 1, 22, tex), .4)
    return s.crop()


def knife_strip(o='rw'):
    s = Spr(40, 40, 20, 30)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return C('#2a2c31')
        if z >= Hh - 2:
            return C('#2a2c31') if z < Hh - 0.5 else C('#4a4d55')
        k = int(a // 1.4)
        if a % 1.4 < 0.9 and k < 5:
            if z >= Hh - 4:
                return C('#3a2018') if k % 2 else C('#1b1c20')
            if z >= Hh - 4 - (6 + (k % 3) * 2):
                return C('#e4e8ec') if a % 1.4 < 0.45 else C('#aab1bb')
        return None
    obox(s, o, 0, 0, 0, 7, 0.6, 14, tex, 'F')
    return s.crop()


# ---------------------------------------------------------------- sheet


def build_frames():
    f = {}
    for v in range(3):
        f[f'floor_quarry_{v}_e'] = floor_tile(v)
    f['wall_rw_e'] = wall_seg('rw', 1)
    f['wall_rw_window_e'] = wall_seg('rw', 2, window=True)
    f['wall_lw_e'] = wall_seg('lw', 3)
    f['wall_lw_window_e'] = wall_seg('lw', 4, window=True)
    f['wall_corner_e'] = wall_corner()
    f['desk_rw_e'] = station('rw', 1)
    f['desk_lw_e'] = station('lw', 2)
    f['duckboard_rw_e'] = duckboard('rw')
    f['duckboard_lw_e'] = duckboard('lw')
    for i in range(2):
        f[f'mat_{i}_e'] = mat(i)
    f['nameplate_e'] = nameplate(40)
    f['fridge_lw_e'] = fridge('lw')
    f['potshelf_lw_e'] = pot_shelf('lw')
    f['rice_lw_e'] = rice_sacks('lw')
    f['extinguisher_e'] = extinguisher()
    f['bin_e'] = bin_()
    f['crates_e'] = crate_stack()
    f['ladles_rw_e'] = ladle_rail('rw')
    f['orders_lw_e'] = menu_board('lw')
    f['knives_rw_e'] = knife_strip('rw')
    f['clock_rw'] = SP.clock(SP.A, 'rw')
    for vi, P in enumerate(COOKS):
        f.update(person_frames(P, vi))
    for st in STATES:
        f[f'bubble_{st}'] = SP.bubble(st)
    return f


def build_sheet_e():
    frames = build_frames()
    out = f'{OUT}/e'
    os.makedirs(out, exist_ok=True)
    SP.pack(frames, out)
    return frames


# ---------------------------------------------------------------- mockup (mirrors scenes.py and the Kotlin layout)


NW, NH, S = 480, 300, 3
OX, OY = 275, 86
N = 96


def proj(u, v, z=0):
    return OX + 2 * u - 2 * v, OY + u + v - z


SESSIONS = [
    ('main', 0, 'working', 0), ('main', 1, 'waiting', 2),
    ('pay', 0, 'working', 1), ('pay', 1, 'idle', 3),
    ('hot', 0, 'done', 4), ('hot', 1, 'working', 5),
]
DESKS = {'main': [('rw', 14, 8), ('rw', 34, 8)], 'pay': [('lw', 64, 6), ('lw', 64, 29)], 'hot': [('rw', 24, 62), ('rw', 48, 62)]}
AREAS = [('main', (32, 41), (9, 4, 48, 32)), ('feature-pay #15', (87, 57), (61, 3, 24, 48)), ('hotfix-login', (50, 93), (19, 58, 48, 32))]


def mockup(frames, phase=0, path=None):
    items = []

    def add(layer, key, sp, u, v, z=0):
        X, Y = proj(u, v, z)
        items.append((layer, key, sp, X, Y))
    base = SP.floor_base(dict(base_side=K['base_side']), N)
    add(0, 0, base, 0, 0)
    for i in range(6):
        for j in range(6):
            add(0, 1 + i + j, frames[f'floor_quarry_{int(H("tile", i, j) * 3)}_e'], i * 16, j * 16)
    add(1, 0, frames['wall_corner_e'], 0, 0)
    for i in range(6):
        add(1, 1 + i, frames['wall_rw_window_e' if i in (1, 3, 5) else 'wall_rw_e'], i * 16, 0)
        add(1, 1 + i, frames['wall_lw_window_e' if i in (2, 4) else 'wall_lw_e'], 0, i * 16)
    for fr, u, v, z, key in DECOR:
        if fr in ('orders_lw_e', 'ladles_rw_e', 'clock_rw', 'knives_rw_e'):
            add(2, key, frames[fr], u, v, z)
        else:
            add(5, key, frames[fr], u, v, z)
    for k, (_, _, (ru, rv, ra, rd)) in enumerate(AREAS):
        for c in range(ra // 24):
            for r in range(rd // 16):
                add(3, k, frames[f'mat_{k % 2}_e'], ru + c * 24, rv + r * 16)
    people = []
    for si, (area, di, state, pv) in enumerate(SESSIONS):
        o, U, V = DESKS[area][di]
        add(5, U + V + 15, frames[f'desk_{o}_e'], U, V)
        cu, cv = (U + 10, V + 12.5) if o == 'rw' else (U + 12.5, V + 10)
        add(5, cu + cv, frames[f'duckboard_{o}_e'], cu, cv)
        ps = _half(frames[f'person{pv}_e{phase}_{state}' + ('_lw' if state == 'working' and o == 'lw' else '')])
        if state in ('working', 'idle'):
            add(5, cu + cv + 0.1, ps, cu, cv, SEAT_Z)
            X, Y = proj(cu, cv, SEAT_Z)
        else:
            pu, pv_ = (U + 16, V + 22) if o == 'lw' else ((U + 15, V + 19) if di == 1 else (U - 3, V + 16))
            add(5, pu + pv_, ps, pu, pv_)
            X, Y = proj(pu, pv_)
        people.append((state, X, Y - ps.ay))
    plates = []
    for label, (pu, pv_), _ in AREAS:
        sp = frames['nameplate_e']
        add(5, pu + pv_, sp, pu, pv_)
        plates.append((label, *proj(pu, pv_)))
    canvas = Spr(NW, NH)
    for layer, key, sp, X, Y in sorted(items, key=lambda it: (it[0], it[1])):
        canvas.place(sp, X, Y)
    for state, X, head in people:
        canvas.place(frames[f'bubble_{state}'], X + 3, head - 1)
    bg = Image.new('RGBA', (NW, NH), (38, 34, 33, 255))
    bg.alpha_composite(canvas.img)
    img = bg.resize((NW * S, NH * S), Image.NEAREST)
    d = ImageDraw.Draw(img)
    try:
        fnt = ImageFont.truetype(SP.FONT, 13, index=6)
    except OSError:
        fnt = ImageFont.load_default()
    for label, X, Y in plates:
        d.text((X * S - fnt.getlength(label[:8]) / 2, (Y - 13) * S), label[:8], font=fnt, fill=(60, 40, 30))
    path = path or f'{OUT}/E-kitchen.png'
    img.convert('RGB').save(path)
    return path


DECOR = [  # frame, u, v, z, key (kept in step with ThemeE.decor)
    ('orders_lw_e', 0, 16, 22 - 22, 0.0), ('ladles_rw_e', 66, 0, 0, 1.0), ('clock_rw', 39, 0, 40, 2.0), ('knives_rw_e', 7, 0, 26, 3.0),
    ('fridge_lw_e', 0, 1, 0, 11.0), ('potshelf_lw_e', 0, 80, 0, 90.0), ('rice_lw_e', 0, 48, 0, 60.0),
    ('extinguisher_e', 90, 6, 0, 96.0), ('bin_e', 7, 89, 0, 96.0), ('crates_e', 5, 68, 0, 73.0), ('crates_e', 91, 90, 0, 181.0),
]


def _half(sp):
    """An HD person frame shrunk to the scene's density (nearest neighbour), for the native mockup."""
    n = Spr(sp.w // DETAIL, sp.h // DETAIL, sp.ax // DETAIL, sp.ay // DETAIL)
    n.img = sp.img.resize((n.w, n.h), Image.NEAREST)
    n._load()
    return n


def lineup(frames, path=None):
    """Every cook in every state and phase at x4 of the scene (x2 of the HD frames), on the quarry floor."""
    Sx = 2
    cw, ch = 96, 128
    img = Image.new('RGBA', (cw * 16 * Sx, ch * len(COOKS) * Sx), (60, 56, 54, 255))
    for vi in range(len(COOKS)):
        for si, st in enumerate(STATES):
            for ph in range(PHASES):
                sp = frames[f'person{vi}_e{ph}_{st}']
                cell = Spr(cw, ch)
                X, Y = cw // 2, ch - 8
                if st in ('working', 'idle'):
                    Y -= SEAT_Z * DETAIL
                cell.place(sp, X, Y)
                big = cell.img.resize((cw * Sx, ch * Sx), Image.NEAREST)
                img.alpha_composite(big, ((si * 4 + ph) * cw * Sx, vi * ch * Sx))
    path = path or f'{OUT}/E-lineup.png'
    img.convert('RGB').save(path)
    return path


if __name__ == '__main__':
    fr = build_sheet_e()
    print(len(fr), 'frames (theme E sheet, e/)')
    if 'mockup' in sys.argv[1:]:
        for ph in range(PHASES):
            print('wrote', mockup(fr, ph, f'{OUT}/E-kitchen-{ph}.png'))
        print('wrote', lineup(fr))
