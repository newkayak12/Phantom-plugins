"""Theme E - 주방 (Restaurant Kitchen): sprites at native resolution, packed to OUT/e/, and a mockup.

Same grid as A (16-unit tiles, 2:1 iso, desk 20x10 units, chair centre 12.5 units in front of the desk, seat height 7 px).
A desk is a steel cooking station, the chair a wooden duckboard on the floor (the cook stands on it), and the people are cooks
with four animation phases per state:
  working  back view at the station, tossing a wok over flames (even variants) or chopping with a cleaver (odd variants)
  waiting  front view, ringing a brass hand bell held high and calling out
  done     front view, presenting a plate under a silver cloche, steam rising
  idle     front view, dozing on an upturned crate, a sleep bubble growing
Run from ai-visualization/art:  PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" theme_e.py [mockup]
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFont
from pix import C, dk, lt, mix, H, Spr, part, outline, outline_out, box, obox, M, INK
import sprites as SP

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


# ---------------------------------------------------------------- people


EYE = C('#2a2238')
JK, JK_S, JK_D = C('#f7f7f4'), C('#d9dde2'), C('#aab1bb')
HAT, HAT_S = C('#ffffff'), C('#dde1e6')

COOKS = [
    dict(skin=C('#f1c39b'), hair=C('#5b3a26'), hat='toque', apron=C('#2f4a7a'), neck=C('#d9443a'), pants='check', shoe=C('#2a2a30')),
    dict(skin=C('#d9a273'), hair=C('#1f1b24'), hat='bandana', band=C('#d63c3c'), apron=C('#2b2b30'), neck=None, pants='black',
         shoe=C('#3a2a22')),
    dict(skin=C('#8d5a3b'), hair=C('#1a1416'), hat='toque', apron=C('#2f7d4f'), neck=C('#f2b33d'), pants='check', shoe=C('#22202a')),
    dict(skin=C('#f7d6b8'), hair=C('#e0a845'), hat='bandana', band=C('#2f6fd1'), apron=C('#8a5a33'), neck=None, pants='black',
         shoe=C('#2a2a30'), pony=True),
    dict(skin=C('#e2b089'), hair=C('#b5532a'), hat='cap', band=C('#2a2b31'), apron=C('#b8323a'), neck=None, pants='check',
         shoe=C('#22202a'), beard=True),
    dict(skin=C('#f3cfb3'), hair=C('#c9c9cf'), hat='tall', apron=C('#6b2d3c'), neck=C('#2f6fd1'), pants='black', shoe=C('#2a2a30'),
         mustache=True),
]


class Fig:
    """Draws a cook in local coordinates: body x 3..15, head top y 2, shoes bottom y 28 (A's person grid)."""
    OX, OY = 12, 16

    def __init__(s, P):
        s.s = Spr(48, 56, 9 + Fig.OX, 29 + Fig.OY)  # anchor: feet on the floor
        s.P = P
        sk = P['skin']
        s.sk, s.sd = sk, dk(sk, .18)
        s.h, s.hd, s.hh = P['hair'], dk(P['hair'], .3), lt(P['hair'], .3)
        s.blush = mix(sk, C('#ff7a8a'), .35)

    def R(s, x0, y0, x1, y1, c):
        s.s.rect(x0 + Fig.OX, y0 + Fig.OY, x1 + Fig.OX, y1 + Fig.OY, c)

    def P_(s, x, y, c):
        s.s.put(x + Fig.OX, y + Fig.OY, c)

    def line(s, x0, y0, x1, y1, c, w=1):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(n + 1):
            x = round(x0 + (x1 - x0) * i / n)
            y = round(y0 + (y1 - y0) * i / n)
            s.R(x, y, x + w - 1, y + w - 1, c)

    # ---- parts
    def legs(s, y0=21, sit=False):
        P = s.P
        pc = C('#2b2b30')
        for x0 in (6, 10):
            for y in range(y0, 27):
                for x in range(x0, x0 + 3):
                    c = pc
                    if P['pants'] == 'check':
                        c = C('#d6d8dc') if (x + y) % 2 == 0 else C('#3a3c44')
                    s.P_(x, y, c)
        k = P['shoe']
        s.R(5, 27, 8, 28, k); s.R(10, 27, 13, 28, k)
        s.R(6, 27, 7, 27, lt(k, .3)); s.R(11, 27, 12, 27, lt(k, .3))

    def jacket_front(s, y1=21):
        s.R(5, 13, 13, y1, JK); s.R(13, 13, 13, y1, JK_S); s.R(5, 13, 13, 13, C('#ffffff'))
        s.R(10, 14, 10, y1, JK_S)  # double-breasted overlap
        for y in (15, 17, 19):
            if y <= y1:
                s.P_(7, y, C('#5d6570')); s.P_(11, y, C('#5d6570'))
        neck = s.P['neck']
        s.R(8, 12, 10, 12, s.sd)
        if neck:
            s.R(7, 13, 11, 13, neck); s.R(8, 14, 10, 14, neck); s.P_(9, 15, dk(neck, .2))

    def apron_front(s, y0=19, y1=24):
        a = s.P['apron']
        s.R(5, y0, 13, y1, a); s.R(5, y0, 13, y0, dk(a, .3)); s.R(13, y0 + 1, 13, y1, dk(a, .2))
        s.R(7, y0 + 2, 11, y0 + 2, lt(a, .18))

    def jacket_back(s):
        s.R(5, 13, 13, 20, JK); s.R(12, 13, 13, 20, JK_S); s.R(5, 13, 13, 13, C('#ffffff'))
        s.R(9, 15, 9, 19, JK_S)  # centre seam
        a = s.P['apron']
        s.R(5, 19, 13, 20, a); s.R(5, 20, 13, 20, dk(a, .25))
        s.R(4, 20, 4, 24, a); s.R(14, 20, 14, 24, dk(a, .2))  # apron wrapping the sides
        s.R(8, 18, 10, 20, lt(a, .2)); s.P_(9, 19, dk(a, .3))  # bow
        s.P_(8, 21, a); s.P_(7, 22, a); s.P_(10, 21, a); s.P_(11, 22, a)  # tails

    def face(s, expr='open', dy=0, dx=0):
        sk, sd = s.sk, s.sd
        R = lambda x0, y0, x1, y1, c: s.R(x0 + dx, y0 + dy, x1 + dx, y1 + dy, c)
        P_ = lambda x, y, c: s.P_(x + dx, y + dy, c)
        R(5, 5, 13, 10, sk); R(6, 11, 12, 11, sk); R(13, 5, 13, 10, sd); P_(12, 11, sd)
        R(4, 6, 4, 8, sk); R(14, 6, 14, 8, sd)  # ears
        h, hd = s.h, s.hd
        R(4, 4, 4, 5, h); R(14, 4, 14, 5, hd); R(5, 5, 6, 5, h); R(12, 5, 13, 5, hd)  # sideburns / fringe edge
        if expr == 'sleep':
            R(6, 8, 7, 8, EYE); R(11, 8, 12, 8, EYE)
            P_(9, 10, dk(sk, .45))
        elif expr == 'happy':
            for ex in (6, 10):
                P_(ex, 8, EYE); P_(ex + 1, 7, EYE); P_(ex + 2, 8, EYE)
            R(8, 10, 10, 10, dk(sk, .5)); P_(7, 9, dk(sk, .3)); P_(11, 9, dk(sk, .3))
        elif expr == 'shout':
            R(7, 7, 7, 8, EYE); R(11, 7, 11, 8, EYE); P_(7, 7, C('#ffffff')); P_(11, 7, C('#ffffff'))
            R(6, 6, 7, 6, s.hd); R(11, 6, 12, 6, s.hd)
            R(8, 9, 10, 11, C('#5a1f28')); P_(9, 11, C('#e0606a'))
        elif expr == 'shout2':
            R(7, 7, 7, 8, EYE); R(11, 7, 11, 8, EYE); P_(7, 7, C('#ffffff')); P_(11, 7, C('#ffffff'))
            R(6, 6, 7, 6, s.hd); R(11, 6, 12, 6, s.hd)
            R(8, 10, 10, 10, C('#5a1f28'))
        else:
            R(7, 7, 7, 8, EYE); R(11, 7, 11, 8, EYE); P_(9, 10, dk(sk, .4))
        P_(6, 9, s.blush); P_(12, 9, s.blush)
        if s.P.get('mustache'):
            R(7, 9, 11, 9, s.h); P_(6, 10, s.h); P_(12, 10, s.h)
        if s.P.get('beard'):
            R(5, 10, 13, 11, s.h); R(6, 12, 12, 12, s.hd); R(8, 10, 10, 10, dk(sk, .4)) if expr in ('shout', 'happy') else None

    def hat(s, back=False, dy=0, dx=0, tilt=0):
        P = s.P
        R = lambda x0, y0, x1, y1, c: s.R(x0 + dx, y0 + dy, x1 + dx, y1 + dy, c)
        P_ = lambda x, y, c: s.P_(x + dx, y + dy, c)
        kind = P['hat']
        if kind in ('toque', 'tall'):
            top = -4 if kind == 'toque' else -7
            R(5, 2, 13, 4, HAT); R(5, 4, 13, 4, HAT_S); R(13, 2, 13, 4, HAT_S)
            for y in range(top, 2):
                t = tilt * (1 - y) // 6 if tilt else 0
                x0, x1 = (5, 13) if y == top else (4, 14)
                R(x0 + t, y, x1 + t, y, HAT)
                for px in (7, 10):
                    if y > top:
                        P_(px + t, y, HAT_S)
                P_(x1 + t, y, HAT_S)
            P_(5 + (tilt * (1 - top) // 6 if tilt else 0), top, lt(HAT_S, .5))
        elif kind == 'bandana':
            b = P['band']
            R(5, 1, 13, 1, b); R(4, 2, 14, 4, b); R(4, 4, 14, 4, dk(b, .2))
            for x in range(5, 14, 3):
                P_(x, 3, lt(b, .5))
            if back:
                R(8, 4, 10, 5, dk(b, .15)); P_(8, 6, b); P_(10, 6, b); P_(7, 7, b); P_(11, 7, dk(b, .2))
            else:
                P_(15, 3, b); P_(16, 4, dk(b, .2))
        else:  # skull cap
            b = P['band']
            R(5, 1, 13, 1, b); R(4, 2, 14, 4, b); R(4, 4, 14, 4, lt(b, .25)); R(6, 1, 8, 1, lt(b, .25))

    def head_back(s, dy=0):
        h, hd, hh = s.h, s.hd, s.hh
        s.R(4, 4 + dy, 14, 11 + dy, h); s.R(12, 4 + dy, 14, 10 + dy, hd); s.R(5, 12 + dy, 13, 12 + dy, hd)
        s.R(6, 5 + dy, 8, 5 + dy, hh)
        s.P_(3, 7 + dy, s.sk); s.P_(3, 8 + dy, s.sd); s.P_(15, 7 + dy, s.sk); s.P_(15, 8 + dy, s.sd)
        s.R(7, 12 + dy, 11, 12 + dy, s.sd)  # nape
        if s.P.get('pony'):
            s.R(8, 11 + dy, 10, 16 + dy, h); s.R(10, 12 + dy, 10, 16 + dy, hd); s.P_(9, 17 + dy, hd)

    def done(s, crop=True):
        outline_out(s.s, INK)
        return s.s


def _union_crop(sprs):
    bb = None
    for sp in sprs:
        b = sp.img.getbbox()
        if b:
            bb = b if bb is None else (min(bb[0], b[0]), min(bb[1], b[1]), max(bb[2], b[2]), max(bb[3], b[3]))
    out = []
    for sp in sprs:
        x0, y0, x1, y1 = bb
        n = Spr(x1 - x0, y1 - y0, sp.ax - x0, sp.ay - y0)
        n.img = sp.img.crop(bb)
        n.em = sp.em.crop(bb)
        n._load()
        out.append(n)
    return out


FLAME = [C('#c92a2a'), C('#f76707'), C('#ffa94d'), C('#ffe066'), C('#fff9db')]


def flames(f, cx, base, height, phase, w=5):
    """A tongue of fire per column around cx, rising from row base by up to height."""
    for dx in range(-w, w + 1):
        hgt = height * (1 - (abs(dx) / (w + 1)) ** 1.6) + (H('fl', phase, dx) - 0.5) * 3
        hgt = int(round(hgt))
        for k in range(max(hgt, 0)):
            t = k / max(hgt, 1)
            core = 1 - abs(dx) / (w + 1)
            lvl = int((1 - t) * 2.2 + core * 2.4)
            f.P_(cx + dx, base - k, FLAME[max(0, min(4, lvl))])


def working(P, phase, chop=False):
    f = Fig(P)
    f.legs(21)
    f.jacket_back()
    f.head_back()
    f.hat(back=True)
    if not chop:
        # wok over the burner on the right, tossed through four phases
        rim = (8, 5, 3, 6)[phase]
        tilt = (0, 1, 0, 0)[phase]
        flames(f, 23, rim + 9, (9, 6, 11, 7)[phase], phase)
        wx0, wx1 = 18, 28
        f.R(wx0, rim, wx1, rim, C('#8a8f99'))
        f.R(wx0 + 1, rim + 1, wx1 - 1, rim + 1, C('#3a3a42'))
        f.R(wx0 + 2, rim + 2, wx1 - 2, rim + 2, C('#2c2c33'))
        f.R(wx0 + 4, rim + 3, wx1 - 4, rim + 3, C('#24242a'))
        f.R(wx0 + 2, rim + 1, wx0 + 4, rim + 1, C('#55565f'))
        f.P_(wx1 + 1, rim, C('#3a3a42'))  # helper handle
        if tilt:
            f.P_(wx1, rim - 1, C('#8a8f99'))
        # food in the wok / in the air
        food = [C('#5fa83d'), C('#e8590c'), C('#ffe9a8'), C('#c0392b'), C('#ffe9a8')]
        arcs = {0: [(-2, -1), (0, -1), (2, -1), (1, -1)],
                1: [(-3, -3), (0, -4), (2, -2), (3, -5)],
                2: [(-2, -7), (0, -9), (2, -6), (4, -8), (-4, -5)],
                3: [(-2, -3), (1, -2), (3, -4), (0, -5)]}[phase]
        for i, (dx, dy) in enumerate(arcs):
            f.P_(23 + dx, rim + dy, food[i % len(food)])
            if phase == 2 and i < 2:
                f.P_(23 + dx + 1, rim + dy, food[(i + 2) % len(food)])
        # long handle into the right hand; the arm reaches up from the shoulder
        hx, hy = 16, rim + 2
        f.line(wx0 - 1, rim, hx + 1, hy - 1, C('#6b4a2e'))
        f.line(14, 14, hx, hy + 1, JK, 2)
        f.R(hx, hy, hx + 1, hy + 1, f.sk)
        # left arm forward (bent, at the station)
        f.R(3, 14, 4, 18, JK); f.R(4, 15, 4, 18, JK_S); f.R(3, 19, 4, 19, f.sk)
    else:
        # cleaver chopping on the right: raised, falling, striking, rising
        # board with a carrot and greens at the right, at station height
        f.R(17, 16, 29, 17, K['wood']); f.R(17, 16, 29, 16, lt(K['wood'], .2)); f.R(17, 18, 29, 18, K['wood_dk'])
        f.R(24, 14, 28, 15, C('#e8590c')); f.P_(28, 14, lt(C('#e8590c'), .3)); f.R(29, 13, 29, 14, C('#5fa83d'))
        for i in range(3):
            f.P_(19 + i * 2, 15, C('#e8590c') if i % 2 else C('#f4a25b'))
        hy = (4, 8, 12, 8)[phase]  # hand height
        bx = 19 + (phase == 2)  # blade's left edge
        f.line(14, 14, 16, hy + 1, JK, 2)
        f.R(16, hy, 17, hy + 1, f.sk)
        f.R(17, hy, 18, hy, C('#5a3420'))  # handle
        blade, bhi, bdk = C('#c9d0d8'), C('#f4f7fa'), C('#7d8590')
        if phase == 2:  # blade down, striking the board
            f.R(bx, hy - 1, bx + 3, hy + 3, blade); f.R(bx, hy - 1, bx + 3, hy - 1, bdk); f.R(bx + 3, hy, bx + 3, hy + 3, bhi)
            f.R(bx + 5, hy - 2, bx + 5, hy - 1, C('#ffe066')); f.R(bx - 2, hy - 2, bx - 2, hy - 1, C('#ffe066'))
            for dx, dy, c in ((bx + 6, hy - 4, C('#e8590c')), (bx + 8, hy - 2, C('#f4a25b')), (bx - 1, hy - 4, C('#e8590c'))):
                f.P_(dx, dy, c)
        else:  # blade up, cutting edge down
            f.R(bx, hy - 4, bx + 4, hy, blade); f.R(bx, hy - 4, bx + 4, hy - 4, bdk); f.R(bx, hy, bx + 4, hy, bhi)
            f.P_(bx + 3, hy - 3, C('#5d6570'))  # the cleaver's hole
            if phase == 1:  # motion lines on the way down
                f.R(bx + 6, hy - 5, bx + 6, hy - 3, C('#ffffff', 170)); f.R(bx + 1, hy - 6, bx + 3, hy - 6, C('#ffffff', 170))
        f.R(3, 14, 4, 18, JK); f.R(4, 15, 4, 18, JK_S); f.R(3, 19, 4, 19, f.sk)
    sp = f.done()
    sp.ay -= SEAT_Z  # the layout lifts a seated person's feet point by the seat height
    return sp


def waiting(P, phase):
    f = Fig(P)
    f.legs(25)
    f.jacket_front()
    f.apron_front()
    sw = (-1, 0, 1, 0)[phase]
    # raised left arm with the hand bell
    f.R(3, 13, 4, 14, JK)
    f.line(3, 13, 2, 5, JK, 2)
    f.R(1, 3, 3, 4, f.sk)
    bx = 2 + sw
    f.R(bx, 1, bx + 1, 2, C('#6b4a2e'))  # handle
    gold, gd = C('#f2c14e'), C('#b7862a')
    f.R(bx - 1, -2, bx + 2, 0, gold); f.R(bx, -3, bx + 1, -3, gold); f.R(bx - 2, -1, bx + 3, 0, gold)
    f.R(bx - 2, 0, bx + 3, 0, gd); f.P_(bx - 1, -2, lt(gold, .5)); f.P_(bx + sw + 0, 1, C('#7a5a1a'))
    if phase in (0, 2):  # ring marks
        side = -1 if phase == 0 else 1
        ox = bx - 4 if side < 0 else bx + 5
        f.R(ox, -3, ox, -1, C('#ffe066')); f.P_(ox + side, -4, C('#ffe066')); f.P_(ox + side, 0, C('#ffe066'))
        ox2 = bx + 5 if side < 0 else bx - 4
        f.P_(ox2, -2, C('#ffe066'))
    else:  # between swings: a short shake mark on each side, higher on the way back
        f.P_(bx - 3, -2 - phase // 3, C('#ffe066')); f.P_(bx + 4, -1 - phase // 3, C('#ffe066'))
    # right hand cupped at the mouth
    f.R(14, 13, 15, 16, JK); f.R(15, 14, 15, 16, JK_S)
    f.line(15, 16, 14, 12, JK, 2)
    f.face('shout' if phase % 2 == 0 else 'shout2')
    f.R(13, 9, 14, 11, f.sk); f.P_(14, 11, f.sd)
    f.hat()
    return f.done()


def done_pose(P, phase):
    f = Fig(P)
    f.legs(25)
    f.jacket_front()
    f.apron_front()
    f.face('happy')
    f.hat()
    # both hands hold a plate in front, a silver cloche on it
    f.R(3, 13, 4, 18, JK); f.R(14, 13, 15, 18, JK_S)
    f.R(1, 18, 17, 18, C('#ffffff')); f.R(2, 19, 16, 19, C('#c9ced6'))
    f.R(2, 18, 3, 19, f.sk); f.R(15, 18, 16, 19, f.sk)
    sil, sh, sdk = C('#cfd6de'), C('#ffffff'), C('#8b949f')
    for y, (x0, x1) in zip(range(12, 18), ((7, 11), (5, 13), (4, 14), (3, 15), (3, 15), (3, 15))):
        f.R(x0, y, x1, y, sil)
        f.R(x1 - 1, y, x1, y, sdk)
        f.P_(x0, y, lt(sil, .4))
    f.R(4, 17, 14, 17, dk(sil, .18))
    f.R(8, 10, 10, 11, sdk); f.R(8, 10, 9, 10, sil)  # knob
    f.R(5, 14, 5, 15, sh); f.R(6, 13, 7, 13, sh)  # highlight
    sp = f.done()
    # steam escaping from under the rim and curling up past the shoulders; a sparkle that blinks on the dome
    st = C('#ffffff', 215)
    for side, x0 in ((-1, 1), (1, 17)):
        for k in range(5):
            y = 15 - k * 2 - (phase % 2)
            x = x0 + side * ((k + phase // 2) % 2)
            sp.put(x + Fig.OX, y + Fig.OY, st)
            if k < 3:
                sp.put(x + Fig.OX, y - 1 + Fig.OY, st)
    if phase in (1, 2):
        arms = ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)) if phase == 1 else ((0, 0),)
        for dx, dy in arms:
            sp.put(15 + dx + Fig.OX, 11 + dy + Fig.OY, C('#fff4a8'))
    return sp


def idle(P, phase):
    f = Fig(P)
    # crate first (at the floor point), then the cook sitting on it
    crate(f.s, 0, 0)
    b = (0, 0, 1, 1)[phase]  # breathing
    # shins down to the floor, thighs on the crate
    k = P['shoe']
    pc = C('#2b2b30') if P['pants'] == 'black' else C('#55575f')
    f.R(5, 23, 7, 26, pc); f.R(11, 23, 13, 26, pc)
    f.R(4, 27, 7, 28, k); f.R(11, 27, 14, 28, k)
    f.R(5, 20, 13, 22, pc); f.R(9, 21, 9, 22, dk(pc, .3))
    a = P['apron']
    f.R(5, 19, 13, 22, a); f.R(5, 19, 13, 19, dk(a, .3))
    # torso slumped
    f.R(5, 12 + b, 13, 19, JK); f.R(13, 12 + b, 13, 19, JK_S)
    neck = P['neck']
    if neck:
        f.R(7, 12 + b, 11, 12 + b, neck)
    # arms crossed
    f.R(4, 15 + b, 14, 17 + b, JK_S); f.R(4, 15 + b, 14, 15 + b, JK); f.R(3, 13 + b, 4, 17 + b, JK); f.R(14, 13 + b, 15, 17 + b, JK_S)
    f.R(5, 16 + b, 6, 16 + b, f.sk); f.R(12, 16 + b, 13, 16 + b, f.sk)
    # head drooping to the side
    hd = 3 + b
    f.face('sleep', dy=hd, dx=1)
    f.hat(dy=hd, dx=1, tilt=3)
    # sleep bubble from the nose
    r = (1, 2, 3, 0)[phase]
    if r:
        cx, cy = 13 + 1, 11 + hd
        for yy in range(-r, r + 1):
            for xx in range(-r, r + 1):
                if xx * xx + yy * yy <= r * r + 1:
                    f.P_(cx + xx + r, cy + yy, C('#cfe8ff', 220))
        f.P_(cx + r - 1, cy - r + 1, C('#ffffff'))
    sp = f.done()
    sp.ay -= SEAT_Z
    return sp


def person_frames(P, vi):
    """name -> Spr for the 4 states x 4 phases of cook P; all phases of one state share size and anchor."""
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
        for ph, sp in enumerate(_union_crop(fr)):
            out[f'person{vi}_e{ph}_{st}'] = sp
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
        ps = frames[f'person{pv}_e{phase}_{state}']
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


def lineup(frames, path=None):
    """Every cook in every state and phase at x4, on the quarry floor: the legibility check."""
    Sx = 4
    cw, ch = 40, 52
    img = Image.new('RGBA', (cw * 16 * Sx, ch * 6 * Sx), (60, 56, 54, 255))
    for vi in range(6):
        for si, st in enumerate(STATES):
            for ph in range(PHASES):
                sp = frames[f'person{vi}_e{ph}_{st}']
                cell = Spr(cw, ch)
                X, Y = cw // 2, ch - 8
                if st in ('working', 'idle'):
                    Y -= SEAT_Z // 2
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
