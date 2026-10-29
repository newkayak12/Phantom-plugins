"""Phantom AI Visualization - sprite definitions (native resolution) + sprite sheet / atlas."""
import json
import math
import os
from PIL import Image, ImageDraw, ImageFont
from pix import C, dk, lt, mix, E, H, Spr, part, outline, outline_out, box, obox, M, INK

OUT = os.environ.get('PHANTOM_AIVIZ_OUT', '/tmp/phantom-ai-viz')
FONT = os.environ.get('PHANTOM_AIVIZ_FONT', '/System/Library/Fonts/AppleSDGothicNeo.ttc')

# ---------------------------------------------------------------- themes
A = dict(
    key='A', floor='wood', wall='brick', night=False,
    wood=[C('#c4864c'), C('#d29558'), C('#b57943')], wood_seam=C('#7a4628'), wood_grain=C('#a56b3a'),
    base_side=C('#5b3523'),
    brick=[C('#b2513a'), C('#a44833'), C('#be5d44'), C('#9c4330')], mortar=C('#d9bca2'),
    wall_cap=C('#efe1c8'), baseboard=C('#5e3a24'), trim=C('#e8d5b5'),
    win_frame=C('#f4ecda'), sill=C('#f8f1e3'),
    sky=[C('#a9dbf5'), C('#86c6ec'), C('#64aee2')], cloud=C('#f4fbff'),
    desk_top=C('#9a6338'), desk_side=C('#7a4a2a'), desk_leg=C('#4a2c1c'),
    chair=C('#3f5f88'), chair_dk=C('#2c4262'), chair_base=C('#2d2f38'),
    mon=C('#2b2f3a'), mon_bezel=C('#3b4250'), scr_bg=C('#16202c'), scr_bar=C('#2c3b4f'),
    rugs=[(C('#a3443d'), C('#e1b265'), C('#6f2a27')),
          (C('#3f7657'), C('#e6c98a'), C('#284d3a')),
          (C('#3f5d96'), C('#e9d7a6'), C('#283d66'))],
    shelf=C('#7c4b2b'), counter=C('#8f5a34'), counter_top=C('#ece0c8'),
    pot=C('#c8693a'), leaf=[C('#2a5f36'), C('#43914a'), C('#76c35f')],
    plate=C('#f6e7c4'), plate_frame=C('#6e4226'), post=C('#4a2c1c'),
    wb_frame=C('#b9c1cb'), wb=C('#f6f8fa'),
)
B = dict(A)
B.update(
    key='B', floor='tile', wall='plaster',
    tile=[C('#dfe3e8'), C('#d3d8de')], grout=C('#b9c0c9'),
    base_side=C('#8c97a5'),
    plaster=C('#f5f6f8'), plaster_dk=C('#e3e7ec'), wall_cap=C('#ffffff'), baseboard=C('#139a8e'),
    trim=C('#1fb5a8'), win_frame=C('#3c4654'), sill=C('#e9edf1'),
    desk_top=C('#f7f8fa'), desk_side=C('#d6dce3'), desk_leg=C('#8b96a4'),
    chair=C('#17a597'), chair_dk=C('#0f7e74'), chair_base=C('#4a5361'),
    rugs=[(C('#bfe7e2'), C('#1fb5a8'), C('#8fd3cb')),
          (C('#d9dee5'), C('#5f6c7d'), C('#c3cad3')),
          (C('#c9ece7'), C('#139a8e'), C('#a4dcd5'))],
    shelf=C('#eef1f4'), counter=C('#f2f4f7'), counter_top=C('#1fb5a8'),
    pot=C('#f2f4f6'), plate=C('#ffffff'), plate_frame=C('#139a8e'), post=C('#5f6c7d'),
    glass=C('#7fd6cc'),
)
Cn = dict(A)
Cn.update(key='C', night=True, sky=[C('#1b2347'), C('#141b3a'), C('#0e1430')], cloud=C('#2a3563'))
THEMES = {'A': A, 'B': B, 'C': Cn}

PEOPLE = [
    dict(hair=C('#5b3a26'), skin=C('#f1c39b'), shirt=C('#3b7dd8'), pants=C('#2e3446'), shoe=C('#3a2a22'), style='short'),
    dict(hair=C('#e8a93d'), skin=C('#f7d6b8'), shirt=C('#3fae62'), pants=C('#4a3a30'), shoe=C('#2a2a30'), style='long'),
    dict(hair=C('#2a2636'), skin=C('#c88d63'), shirt=C('#e0574f'), pants=C('#34405e'), shoe=C('#22202a'), style='bun'),
]
STATE_COL = dict(working=C('#4f8cff'), waiting=C('#e5484d'), done=C('#2fb36b'), idle=C('#9aa3b5'))

W_H = 60        # wall height px
SEG = 16        # wall segment / floor tile length in iso units


# ---------------------------------------------------------------- floor
def floor_tile(T, var=0):
    s = Spr(70, 40, 33, 2)
    if T['floor'] == 'wood':
        def tex(role, a, d, z, A_, D_, Hh, sf):
            pl = int(d // 4)
            fd = d % 4
            off = int(H('off', var, pl) * 12) + 2
            if fd < 0.5:
                return T['wood_seam']
            seg = int((a + off) // 12)
            if (a + off) % 12 < 0.5:
                return T['wood_seam']
            tone = T['wood'][int(H('t', var, pl, seg) * 3)]
            if fd < 1.0:
                return lt(tone, .14)
            g = H('g', var, pl, int(a * 2))
            if g < 0.07:
                return T['wood_grain']
            if g > 0.97:
                return lt(tone, .08)
            return tone
    else:
        def tex(role, a, d, z, A_, D_, Hh, sf):
            if a % 8 < 0.5 or d % 8 < 0.5:
                return T['grout']
            tone = T['tile'][(int(a // 8) + int(d // 8) + var) % 2]
            if H('sp', var, int(a * 2), int(d * 2)) < 0.03:
                return dk(tone, .06)
            if (a % 8) < 1.0 or (d % 8) < 1.0:
                return lt(tone, .25)
            return tone
    box(s, 0, 0, 0, SEG, SEG, 0, tex)
    return s.crop()


def floor_base(T, n):
    s = Spr(4 * n + 30, 2 * n + 30, 2 * n + 14, 6)
    box(s, -3, -3, -7, n + 3, n + 3, 7, M(T['base_side'], T['base_side'], dk(T['base_side'], .25), edge=False), 'LR')
    return s.crop()


# ---------------------------------------------------------------- walls
def window_px(T, s_, z, seg_seed, lw):
    """Return color if (s_, z) on a wall face hits the window, else None."""
    if T['wall'] == 'plaster':
        x0, x1, z0, z1 = 2.0, 14.0, 12, 52
    else:
        x0, x1, z0, z1 = 3.0, 13.0, 16, 48
    if x0 - 0.5 <= s_ < x1 + 0.5 and z0 - 2 <= z < z0:
        return T['sill'] if z >= z0 - 1 else dk(T['sill'], .25)
    if not (x0 <= s_ < x1 and z0 <= z < z1):
        return None
    x = s_ - x0
    y = z - z0
    w = x1 - x0
    hh = z1 - z0
    fr = T['win_frame']
    if x < 0.5 or x >= w - 0.5 or y < 1 or y >= hh - 1:
        return fr
    if abs(x - w / 2) < 0.25 or (T['wall'] != 'plaster' and int(y) == int(hh * 0.62)):
        return fr
    if x < 1.0 or y >= hh - 2:
        return dk(fr, .3)  # inner reveal shadow
    sky = T['sky']
    t = y / hh
    col = sky[2] if t > 0.66 else sky[1] if t > 0.33 else sky[0]
    if T['night']:
        if H('star', seg_seed, int(x * 2), int(y)) < 0.025:
            col = C('#fff6c8')
        if seg_seed % 3 == 1:  # moon
            mx, my = w * 0.7, hh * 0.72
            if ((x - mx) * 2) ** 2 + (y - my) ** 2 < 10:
                col = C('#fff1b0')
        # distant city skyline glow
        bh = 4 + int(H('city', seg_seed, int(x)) * 6)
        if y < bh:
            col = C('#0b0f22')
            if H('lit', seg_seed, int(x * 2), int(y)) < 0.18:
                col = C('#ffd27a')
        return E(col)
    # day: clouds + glare
    cy = hh * 0.7 + (seg_seed % 3) * 2
    cx = w * (0.3 + 0.2 * (seg_seed % 2))
    if ((x - cx) * 1.6) ** 2 + ((y - cy) * 1.4) ** 2 < 7 or ((x - cx - 2) * 1.6) ** 2 + ((y - cy - 1.5) * 1.4) ** 2 < 5:
        col = T['cloud']
    if (x * 2 + y) % 13 < 1.3:
        col = lt(col, .35)
    return E(col) if False else col


def wall_tex(T, seg_seed, window=False, deco=None):
    def tex(role, a, d, z, A_, D_, Hh, sf):
        if sf == 'T':
            return lt(T['wall_cap'], .0) if d < D_ - 0.5 else dk(T['wall_cap'], .08)
        inner = (sf == 'L' and d >= D_ - 0.01) or (sf == 'R' and a >= A_ - 0.01)
        s_ = a if sf == 'L' else d
        if not inner:  # wall end cap
            return dk(T['wall_cap'], .3) if z < Hh - 1 else T['wall_cap']
        dim = 0.0 if sf == 'L' else 0.12
        if window:
            c = window_px(T, s_, z, seg_seed, sf == 'R')
            if c is not None:
                return c if len(c) > 4 else dk(c, dim * 0.5)
        if deco:
            c = deco(s_, z)
            if c is not None:
                return c
        if z < 4:
            c = T['baseboard']
            return dk(lt(c, .2) if z >= 3 else c, dim)
        if z >= Hh - 3:
            c = T['trim'] if z < Hh - 1 else lt(T['trim'], .3)
            return dk(c, dim)
        if T['wall'] == 'brick':
            zz = z - 4
            row = int(zz // 4)
            if zz % 4 < 1:
                return dk(T['mortar'], dim)
            off = 4 if row % 2 else 0
            if (s_ + off) % 8 < 0.5:
                return dk(T['mortar'], dim)
            bi = int((s_ + off) // 8)
            c = T['brick'][int(H('b', seg_seed, row, bi) * 4)]
            if zz % 4 >= 3:
                c = lt(c, .12)
            elif H('bs', seg_seed, row, int(s_ * 2)) < 0.06:
                c = dk(c, .12)
            return dk(c, dim)
        # plaster
        c = T['plaster']
        if 22 <= z < 23:
            c = T['trim']
        elif s_ % 16 < 0.5:
            c = T['plaster_dk']
        return dk(c, dim + 0.03)
    return tex


def wall_seg(T, side, seed, window=False, end=False, deco=None):
    s = Spr(110, 120, 55, 80)
    tex = wall_tex(T, seed, window, deco)
    if side == 'rw':
        box(s, 0, -3, 0, SEG, 3, W_H, tex, 'TL' + ('R' if end else ''))
    else:
        box(s, -3, 0, 0, 3, SEG, W_H, tex, 'TR' + ('L' if end else ''))
    return s.crop()


def wall_corner(T):
    s = Spr(30, 90, 15, 76)
    box(s, -3, -3, 0, 3, 3, W_H, lambda *a: T['wall_cap'], 'T')
    return s.crop()


# ---------------------------------------------------------------- desk + items
def screen_tex(T, seed):
    cols = [C('#7ee787'), C('#79c0ff'), C('#ffa657'), C('#d2a8ff'), C('#ff7b72'), C('#e6edf3')]
    lines = []
    for i in range(7):
        ind = int(H('ind', seed, i) * 3) * 1.0
        ln = 2 + int(H('ln', seed, i) * 8)
        lines.append((ind, ln, cols[int(H('c', seed, i) * len(cols))], H('c2', seed, i)))
    base = M(T['mon'])

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return base(role, a, d, z, A_, D_, Hh, sf)
        if a < 0.6 or a >= A_ - 0.6 or z < 1 or z >= Hh - 1:
            return T['mon_bezel'] if z >= 1 else dk(T['mon'], .2)
        r = int(Hh - 2 - z)
        x = a - 0.6
        if r == 0:
            if x < 1.5:
                return E(C('#ff5f57')) if x < 0.5 else E(C('#febc2e')) if x < 1.0 else E(C('#28c840'))
            return E(T['scr_bar'])
        if x < 0.75:  # gutter
            return E(C('#4a5a70')) if r % 3 == 2 else E(T['scr_bg'])
        if r % 3 == 2:
            ind, ln, col, c2 = lines[(r // 3) % len(lines)]
            xx = x - 0.75
            if ind <= xx < ind + ln:
                if c2 > 0.4 and xx > ind + 2:
                    return E(cols[(int(c2 * 7) + 1) % len(cols)]) if xx < ind + ln * 0.75 else E(C('#e6edf3'))
                return E(col)
        return E(T['scr_bg'])
    return tex


def desk(T, o='rw', seed=0, items=True, plant=False):
    s = Spr(140, 110, 70, 50)
    A_, D_ = 20, 10
    leg = M(T['desk_leg'])

    def legs(t):
        for a0, d0 in ((0.5, 0.5), (A_ - 2.5, 0.5), (0.5, D_ - 2.5), (A_ - 2.5, D_ - 2.5)):
            obox(t, o, a0, d0, 0, 2, 2, 10, leg)
        obox(t, o, 1, 0.5, 4, A_ - 2, 1, 6, M(T['desk_side']))  # modesty panel
    part(s, legs, .4)
    part(s, lambda t: obox(t, o, 0, 0, 10, A_, D_, 2, M(T['desk_top'], T['desk_side'], dk(T['desk_side'], .2))), .45)
    if not items:
        return s.crop()
    # papers
    def papers(t):
        obox(t, o, 1, 1.5, 12, 4, 4, 0.01, lambda *a: C('#f4f1ea') if a[1] < 3.5 else C('#dcd6cb'), 'T')
        obox(t, o, 1.6, 2.2, 12.3, 3, 3.5, 0.01, lambda *a: C('#ffffff') if int(a[2] * 2) % 2 == 0 else C('#c9d3e0'), 'T')
    part(s, papers, 0)
    # monitor stand + panel
    part(s, lambda t: (obox(t, o, 8, 1, 12, 4, 3, 1, M(C('#3a3f4b'))),
                       obox(t, o, 9.5, 1.3, 13, 1, 1, 4, M(C('#4a505e')))), .4)
    part(s, lambda t: obox(t, o, 3, 0.6, 16, 14, 1.4, 16, screen_tex(T, seed)), .35)
    # keyboard + mouse
    def kb(t):
        def ktex(role, a, d, z, A_, D_, Hh, sf):
            if sf != 'T':
                return C('#3c414d') if sf == 'L' else C('#2c3039')
            if a < 0.4 or a >= A_ - 0.4 or d < 0.4 or d >= D_ - 0.4:
                return C('#555b68')
            return C('#e9ecf1') if (int(a * 2) + int(d * 2)) % 2 == 0 else C('#b8bec9')
        obox(t, o, 5, 5.5, 12, 10, 3, 1, ktex)
        obox(t, o, 16.5, 6, 12, 1.5, 2, 1, M(C('#e6e8ec')))
    part(s, kb, .3)
    # mug
    part(s, lambda t: (obox(t, o, 1.5, 6.5, 12, 2, 2, 4, M(C('#f2efe8'))),
                       obox(t, o, 1.9, 6.9, 15.9, 1.2, 1.2, 0.2, lambda *a: C('#5a3420'), 'T')), .35)
    if plant:
        def pl(t):
            obox(t, o, 16, 1, 12, 3, 3, 4, M(T['pot']))
        part(s, pl, .4)
        cx, cy = _proj_local(s, o, 17.5, 2.5, 18)
        blob = Spr(s.w, s.h, s.ax, s.ay)
        foliage(blob, cx, cy, [(-2, 0, 2.6), (2, 0, 2.6), (0, -2, 2.8), (0, 1, 2.4)], T['leaf'])
        outline(blob, None, .5)
        s.paste(blob, 0, 0)
    return s.crop()


def _proj_local(s, o, a, d, z):
    u, v = (a, d) if o == 'rw' else (d, a)
    return s.ax + 2 * u - 2 * v, s.ay + u + v - z


def chair(T, o='rw', which='all'):
    s = Spr(50, 50, 25, 30)
    cm = M(T['chair'], T['chair'], T['chair_dk'])
    if which in ('all', 'seat'):
        part(s, lambda t: (obox(t, o, -2.5, -2.5, 0, 5, 5, 1, M(T['chair_base'])),
                           obox(t, o, -0.5, -0.5, 1, 1, 1, 5, M(dk(T['chair_base'], -.0)))), .3)
        part(s, lambda t: obox(t, o, -3.5, -3, 6, 7, 6, 2, cm), .45)
    if which in ('all', 'back'):
        part(s, lambda t: (obox(t, o, -0.5, 3, 7, 1, 1, 3, M(T['chair_base'])),
                           obox(t, o, -3.5, 3.2, 9, 7, 1.6, 9, cm)), .45)
    return s.crop()


# ---------------------------------------------------------------- plants / foliage
def foliage(s, cx, cy, blobs, pal):
    dark, mid, hi = pal
    for dx, dy, r in sorted(blobs, key=lambda b: b[1]):
        bx, by = cx + dx, cy + dy
        for y in range(int(by - r) - 1, int(by + r) + 2):
            for x in range(int(bx - r) - 1, int(bx + r) + 2):
                nx, ny = (x + 0.5 - bx) / r, (y + 0.5 - by) / (r * 0.9)
                if nx * nx + ny * ny > 1:
                    continue
                l = -nx * 0.55 - ny * 0.85
                c = hi if l > 0.5 else mid if l > -0.25 else dark
                if H('lf', x, y) < 0.08:
                    c = lt(c, .25) if c is not dark else mid
                s.put(x, y, c)


def plant_big(T, seed=0):
    s = Spr(50, 70, 25, 52)
    part(s, lambda t: (obox(t, 'rw', -3, -3, 0, 6, 6, 7, M(T['pot'])),
                       obox(t, 'rw', -3.6, -3.6, 7, 7.2, 7.2, 2, M(lt(T['pot'], .12))),
                       obox(t, 'rw', -2.8, -2.8, 9, 5.6, 5.6, 0.01, lambda *a: C('#4a3022'), 'T')), .45)
    cx, cy = s.ax, s.ay - 14
    t = Spr(s.w, s.h, s.ax, s.ay)
    # stems
    for i, (sx, h_) in enumerate(((-1, 9), (1, 12), (0, 15))):
        for y in range(h_):
            t.put(cx + sx + (y // 5) * (1 if sx >= 0 else -1), cy + 2 - y, C('#3d6b2c'))
    blobs = [(-5, -6, 4.2), (5, -7, 4.2), (0, -12, 4.6), (-3, -17, 3.8), (4, -15, 3.6), (0, -3, 3.8), (-7, -1, 3), (7, -2, 3)]
    if seed:
        blobs = [(b[0] * (1 if seed % 2 else -1), b[1], b[2]) for b in blobs]
    foliage(t, cx, cy, blobs, T['leaf'])
    outline(t, None, .55)
    s.paste(t, 0, 0)
    return s.crop()


# ---------------------------------------------------------------- wall decor / furniture
def whiteboard(T, o='rw'):
    s = Spr(70, 70, 35, 50)
    notes = [(2, 12, 2, 4, C('#ffd75e')), (5, 13, 2, 4, C('#ff9ab8')), (8, 12, 2, 4, C('#8fd0ff')),
             (2, 6, 2, 4, C('#a6e08a')), (11, 13, 2, 4, C('#ffd75e')), (14, 11, 2, 4, C('#ff9ab8')),
             (11, 6, 2, 4, C('#8fd0ff')), (14, 5, 2, 4, C('#ffd75e'))]

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return M(T['wb_frame'])(role, a, d, z, A_, D_, Hh, sf)
        if a < 0.5 or a >= A_ - 0.5 or z < 1 or z >= Hh - 1:
            return T['wb_frame'] if z >= 1 else dk(T['wb_frame'], .3)
        for (n0, nz, nw, nh, nc) in notes:
            if n0 <= a < n0 + nw and nz <= z < nz + nh:
                return dk(nc, .18) if z < nz + 1 else (lt(nc, .2) if z >= nz + nh - 1 else nc)
        # columns divider + headings (kanban)
        if abs(a - 10) < 0.25 and 2 <= z < Hh - 2:
            return C('#c8ced6')
        if int(z) == Hh - 3 and (1.5 <= a < 4.5 or 11.5 <= a < 15):
            return C('#3b6fd6') if a < 10 else C('#d6453b')
        if int(z) == 3 and 5 <= a < 9:
            return C('#3aa65a')
        return T['wb']
    part(s, lambda t: obox(t, o, 0, 0, 22, 18, 1, 20, tex), .4)
    part(s, lambda t: (obox(t, o, 1, 1, 22, 16, 1.5, 1, M(T['wb_frame'])),
                       obox(t, o, 4, 1.3, 23, 2, 0.6, 0.5, M(C('#d6453b'))),
                       obox(t, o, 7, 1.3, 23, 2, 0.6, 0.5, M(C('#3b6fd6')))), .4)
    return s.crop()


def bookshelf(T, o='lw', seed=0):
    s = Spr(80, 90, 40, 70)
    bcols = [C('#c0392b'), C('#2e86de'), C('#f39c12'), C('#27ae60'), C('#8e44ad'), C('#e8e2d0'), C('#16a085'), C('#d35400')]
    wood = T['shelf']

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return M(wood)(role, a, d, z, A_, D_, Hh, sf)
        if a < 0.6 or a >= A_ - 0.6 or z >= Hh - 1.5:
            return lt(wood, .1) if z >= Hh - 1 else wood
        if z < 2:
            return dk(wood, .25)
        for sz in (14, 27):
            if sz <= z < sz + 1.5:
                return lt(wood, .15) if z >= sz + 1 else wood
        k = 0 if z < 14 else 1 if z < 27 else 2
        zb = (2, 15.5, 28.5)[k]
        zin = z - zb
        bi = int((a - 0.6) // 1.0)
        if H('empty', seed, k, bi) < 0.12:
            return dk(wood, .55)
        hb = 7 + int(H('h', seed, k, bi) * 4)
        if zin < hb:
            c = bcols[int(H('c', seed, k, bi) * len(bcols))]
            if hb - 3 <= zin < hb - 2:
                c = lt(c, .45)
            if (a - 0.6) % 1.0 < 0.5:
                c = lt(c, .12)
            else:
                c = dk(c, .1)
            return c
        return dk(wood, .55)
    part(s, lambda t: obox(t, o, 0, 0, 0, 14, 6, 40, tex), .45)
    # top deco: small plant + box
    part(s, lambda t: obox(t, o, 9, 1.5, 40, 3, 3, 3, M(T['pot'])), .4)
    cx, cy = _proj_local(s, o, 10.5, 3, 45)
    t = Spr(s.w, s.h, s.ax, s.ay)
    foliage(t, cx, cy, [(-2, 1, 2.4), (2, 1, 2.4), (0, -1, 2.6), (-3, 3, 1.8)], T['leaf'])
    outline(t, None, .5)
    s.paste(t, 0, 0)
    part(s, lambda t: obox(t, o, 2, 1, 40, 5, 4, 3, M(C('#c9a46a'))), .4)
    return s.crop()


def coffee_station(T, o='lw'):
    s = Spr(90, 90, 45, 64)
    cnt = T['counter']

    def ctex(role, a, d, z, A_, D_, Hh, sf):
        if role == 'T':
            return lt(T['counter_top'], .2) if (a >= A_ - 0.5 or d >= D_ - 0.5) else T['counter_top']
        if role != 'F':
            return M(cnt)(role, a, d, z, A_, D_, Hh, sf)
        if z >= Hh - 1:
            return lt(cnt, .15)
        if z < 1.5:
            return dk(cnt, .35)
        if abs(a - A_ / 2) < 0.25 or a < 0.5 or a >= A_ - 0.5:
            return dk(cnt, .25)
        if 9 <= z < 10 and (A_ / 2 - 1.5 <= a < A_ / 2 - 0.75 or A_ / 2 + 0.75 <= a < A_ / 2 + 1.5):
            return C('#d9d9d9')
        return cnt
    part(s, lambda t: obox(t, o, 0, 0, 0, 16, 8, 14, ctex), .45)
    body = C('#383a42')

    def mtex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            if role == 'T':
                return C('#b9bfc8')
            return dk(body, .2)
        if z >= Hh - 4:
            if 3.5 <= z - (Hh - 4) < 2.5 + 2:
                pass
            if int(z) == Hh - 3 and 1 <= a < 1.5:
                return E(C('#ff4d4d'))
            if int(z) == Hh - 3 and 2 <= a < 2.5:
                return E(C('#47e07a'))
            return C('#c9ced6') if z < Hh - 1 else C('#e6e9ee')
        if 1 <= a < A_ - 1 and 2 <= z < 8:
            if 2 <= a < 3.6 and 3 <= z < 6:
                return C('#f4f1ea') if a < 3.1 else C('#d6d1c6')
            return C('#1a1b20')
        if 1 <= z < 2 and 1 <= a < A_ - 1:
            return C('#9aa1ab')
        return body
    part(s, lambda t: obox(t, o, 2, 1.5, 14, 6, 5, 15, mtex), .4)
    # cup stack + jar
    part(s, lambda t: (obox(t, o, 10, 2, 14, 2, 2, 5, M(C('#f4f1ea'))),
                       obox(t, o, 13, 2, 14, 2, 2, 4, M(C('#a46a3c'))),
                       obox(t, o, 13, 2, 18, 2, 2, 1, M(C('#d0d4da')))), .35)
    return s.crop()


def rug(T, i, A_=40, D_=28):
    s = Spr(4 * (A_ + D_) + 10, 2 * (A_ + D_) + 10, 2 * D_ + 5, 5)
    field, border, deep = T['rugs'][i]

    def tex(role, a, d, z, AA, DD, Hh, sf):
        if sf != 'T':
            return dk(field, .3)
        e = min(a, d, AA - a, DD - d)
        if e < 0.6:
            return deep
        if e < 2.6:
            return border if e > 1.1 else lt(border, .15)
        if e < 3.4:
            return deep
        if e < 4:
            return field
        cx, cy = abs(a - AA / 2), abs(d - DD / 2)
        m = (cx + cy) % 8
        if m < 0.7:
            return lt(border, .1)
        if (int(a) + int(d)) % 6 == 0 and e > 5 and H('rd', int(a), int(d)) < 0.5:
            return dk(field, .12)
        return field
    box(s, 0, 0, 0, A_, D_, 1, tex)
    return s.crop()


def lamp(T):
    s = Spr(30, 60, 15, 50)
    part(s, lambda t: (obox(t, 'rw', -2.5, -2.5, 0, 5, 5, 1, M(C('#3a3d46'))),
                       obox(t, 'rw', -0.5, -0.5, 1, 1, 1, 30, M(C('#4b505c')))), .35)

    def shade(role, a, d, z, A_, D_, Hh, sf):
        base = C('#f3d9a4')
        if sf == 'T':
            return C('#d9b77a')
        if z < 1:
            return E(C('#ffe9a8'))
        return base if sf == 'L' else dk(base, .2)
    part(s, lambda t: obox(t, 'rw', -3.5, -3.5, 30, 7, 7, 8, shade), .3)
    return s.crop()


def clock(T, o='rw'):
    s = Spr(30, 30, 15, 20)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return C('#3a3d46')
        dx, dz = (a - A_ / 2) * 2, z - Hh / 2
        r = math.hypot(dx, dz)
        if r > Hh / 2:
            return None
        if r > Hh / 2 - 1.2:
            return C('#3a3d46')
        if (abs(dx) < 0.6 and 0 <= dz < 3.5) or (abs(dz) < 0.6 and 0 <= dx < 2.6):
            return C('#2a1d2a')
        return C('#fbf8f1')
    obox(s, o, 0, 0, 0, 5, 0.6, 10, tex, 'F')
    return s.crop()


def poster(T, o='rw', kind=0):
    s = Spr(40, 40, 20, 30)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return C('#2f2a2a')
        if a < 0.5 or a >= A_ - 0.5 or z < 1 or z >= Hh - 1:
            return C('#2f2a2a')
        if kind == 0:  # framed pixel landscape
            x, y = a - 0.5, z - 1
            if a < 1.0 or a >= A_ - 1.0 or z < 2 or z >= Hh - 2:
                return C('#f4efe4')
            mh = 8 - abs(x - 3.0) * 2.6
            mh2 = 6 - abs(x - 5.2) * 2.0
            if y < 2.5:
                return C('#5aa04a') if y >= 1.5 else C('#3f7a3a')
            if y < mh:
                return C('#f6f6f6') if y >= mh - 1.6 and mh > 6 else C('#6c6a9a')
            if y < mh2:
                return C('#8784b8')
            if (x - 1.6) ** 2 * 4 + (y - 9.2) ** 2 < 2.6:
                return C('#ffd45e')
            return C('#9fd6f0') if y < 8 else C('#7cc2ea')
        # bar chart
        x, y = a - 0.5, z - 1
        if y < 1:
            return C('#e8e2d0')
        bars = [5, 9, 7, 12, 10]
        bi = int(x // 1.4)
        if bi < len(bars) and x % 1.4 < 1 and y < bars[bi]:
            return C('#1fb5a8') if bi % 2 else C('#f0a43a')
        return C('#fbf8f1')
    obox(s, o, 0, 0, 0, 7, 0.6, 14, tex, 'TF')
    return s.crop()


def glass(T, o='rw', L=16):
    s = Spr(4 * L + 20, 2 * L + 50, 2 * L + 10, 40)
    al = C('#c3cbd4')

    def tex(role, a, d, z, A_, D_, Hh, sf):
        if role != 'F':
            return al if role == 'T' else dk(al, .2)
        if a < 0.5 or a >= A_ - 0.5 or z >= Hh - 1.2:
            return lt(al, .1)
        if z < 2:
            return dk(al, .25)
        if 9 <= z < 13:
            return C('#e9f7f5', 215) if not (10.5 <= z < 11.5) else C('#1fb5a8')
        if (a * 2 + z) % 18 < 1.4 or (a * 2 + z) % 18 > 16.6 and (a * 2 + z) % 18 < 17.2:
            return C('#ffffff', 150)
        return C('#8ad9d0', 70)
    obox(s, o, 0, 0, 0, L, 1, 28, tex)
    return s.crop()


def nameplate(T, w):
    """Flat standing sign; text is drawn later at final resolution."""
    h = 10
    s = Spr(w + 4, h + 8, (w + 4) // 2, h + 7)
    fr, pl = T['plate_frame'], T['plate']
    for x in (w // 2 - 6, w // 2 + 6):
        s.rect(x + 1, h, x + 2, h + 6, T['post'])
        s.rect(x, h + 6, x + 3, h + 6, dk(T['post'], .3))
    s.rect(1, 0, w + 2, h, fr)
    s.rect(2, 1, w + 1, h - 1, pl)
    s.rect(2, 1, w + 1, 1, lt(pl, .5))
    s.rect(2, h - 1, w + 1, h - 1, dk(pl, .12))
    s.put(3, 2, dk(fr, .1))
    s.put(w, 2, dk(fr, .1))
    outline_out(s, INK)
    return s


# ---------------------------------------------------------------- people
EYE = C('#2a2238')


def person(P, pose):
    s = Spr(24, 34, 11, 31)
    h_, hd, hh = P['hair'], dk(P['hair'], .3), lt(P['hair'], .3)
    sk, sd = P['skin'], dk(P['skin'], .18)
    t_, td, th = P['shirt'], dk(P['shirt'], .25), lt(P['shirt'], .2)
    p_, pd = P['pants'], dk(P['pants'], .3)
    k_ = P['shoe']
    blush = mix(sk, C('#ff7a8a'), .35)
    ox, oy = 2, 2
    if pose in ('working', 'idle'):
        oy = 9  # seated: shorter sprite, bottom row = seat

    def R(x0, y0, x1, y1, c):
        s.rect(x0 + ox, y0 + oy, x1 + ox, y1 + oy, c)

    def Pt(x, y, c):
        s.put(x + ox, y + oy, c)

    if pose in ('waiting', 'done'):
        # legs + shoes
        R(6, 22, 8, 26, p_); R(8, 22, 8, 26, pd)
        R(10, 22, 12, 26, p_); R(12, 22, 12, 26, pd)
        R(5, 27, 8, 28, k_); R(10, 27, 13, 28, k_)
        R(6, 27, 7, 27, lt(k_, .3)); R(11, 27, 12, 27, lt(k_, .3))
        # torso
        R(5, 13, 13, 20, t_); R(13, 13, 13, 20, td); R(5, 13, 13, 13, th)
        R(5, 21, 13, 21, pd); Pt(9, 21, C('#d9c27a'))
        R(8, 13, 10, 13, sk); Pt(9, 14, sk)
        R(6, 16, 7, 17, td); Pt(11, 16, lt(t_, .5))
        # arms
        R(3, 13, 4, 19, t_); R(4, 14, 4, 19, td); R(3, 20, 4, 20, sk)
        if pose == 'waiting':
            R(14, 12, 15, 13, t_); R(15, 8, 16, 12, t_); R(16, 9, 16, 12, td)
            R(15, 4, 16, 7, sk); R(16, 4, 16, 7, sd)
            R(14, 1, 17, 3, sk); Pt(14, 0, sk); Pt(16, 0, sk); Pt(17, 3, sd)
        else:
            R(14, 13, 15, 19, t_); R(14, 14, 14, 19, td); R(14, 20, 15, 20, sk)
            # coffee mug held in front (left hand)
            R(4, 17, 7, 17, sk)
            R(5, 15, 8, 19, C('#f4f1ea')); R(8, 15, 8, 19, C('#d6d1c6'))
            R(5, 15, 8, 15, C('#ffffff')); R(6, 16, 7, 16, C('#6b3b20'))
            Pt(9, 17, C('#f4f1ea')); Pt(9, 18, C('#d6d1c6'))
            R(5, 17, 6, 18, sk)
        # head
        R(6, 2, 12, 2, h_); R(5, 3, 13, 3, h_); R(4, 4, 14, 5, h_)
        R(5, 6, 13, 10, sk); R(6, 11, 12, 11, sk); R(13, 6, 13, 11, sd); R(12, 11, 12, 11, sd)
        R(4, 6, 4, 8, h_); R(14, 6, 14, 8, hd); Pt(4, 9, sk); Pt(14, 9, sd)
        R(5, 6, 6, 6, h_); R(9, 6, 10, 6, h_); R(12, 6, 13, 6, hd); Pt(5, 7, h_)
        R(7, 3, 9, 3, hh); Pt(6, 4, hh); R(12, 4, 14, 5, hd)
        R(8, 12, 10, 12, sd)
        if pose == 'done':
            for ex in (6, 10):
                Pt(ex, 9, EYE); Pt(ex + 1, 8, EYE); Pt(ex + 2, 9, EYE)
            R(8, 11, 10, 11, dk(sk, .45)); Pt(7, 10, dk(sk, .3)); Pt(11, 10, dk(sk, .3))
        else:
            R(7, 8, 7, 9, EYE); R(11, 8, 11, 9, EYE); Pt(7, 8, C('#ffffff'))
            Pt(11, 8, C('#ffffff')); Pt(9, 11, dk(sk, .55)); Pt(9, 10, dk(sk, .25))
            R(6, 7, 7, 7, hd); R(11, 7, 12, 7, hd)  # raised brows
        Pt(6, 10, blush); Pt(12, 10, blush)
        if P['style'] == 'long':
            R(3, 5, 4, 14, h_); R(14, 5, 15, 14, hd); R(3, 14, 4, 15, hd)
        if P['style'] == 'bun':
            R(7, 0, 11, 1, h_); Pt(8, 0, hh); R(10, 1, 11, 1, hd)
    else:
        sleep = pose == 'idle'
        # torso (back view)
        R(5, 14, 13, 21, t_); R(12, 14, 13, 21, td); R(5, 14, 13, 14, th); R(7, 14, 11, 14, td)
        R(9, 16, 9, 20, dk(t_, .1))
        if sleep:
            R(2, 11, 5, 15, t_); R(13, 11, 16, 15, t_); R(2, 15, 5, 15, td); R(13, 15, 16, 15, td)
            R(3, 10, 5, 10, sk); R(13, 10, 15, 10, sk)
            dy = 4
        else:
            R(3, 15, 4, 19, t_); R(14, 15, 15, 19, td); R(3, 15, 4, 15, th)
            dy = 0
        if not sleep:
            R(8, 12, 10, 13, sd)
        R(6, 2 + dy, 12, 2 + dy, h_); R(5, 3 + dy, 13, 3 + dy, h_); R(4, 4 + dy, 14, 11 + dy, h_)
        R(5, 12 + dy, 13, 12 + dy, hd); R(4, 9 + dy, 14, 11 + dy, hd); R(12, 4 + dy, 14, 8 + dy, hd)
        R(7, 3 + dy, 10, 3 + dy, hh); R(6, 4 + dy, 7, 4 + dy, hh); R(8, 6 + dy, 9, 6 + dy, hh)
        Pt(3, 8 + dy, sk); Pt(3, 9 + dy, sd); Pt(15, 8 + dy, sk); Pt(15, 9 + dy, sd)
        if P['style'] == 'long':
            R(5, 12 + dy, 13, 17, h_); R(5, 17, 13, 17, hd); R(11, 12 + dy, 13, 17, hd)
        if P['style'] == 'bun':
            R(7, 0 + dy, 11, 1 + dy, h_); Pt(8, dy, hh); R(10, 1 + dy, 11, 1 + dy, hd)
    outline_out(s, INK)
    s = s.crop()
    return s


# ---------------------------------------------------------------- bubbles
ICONS = {
    'waiting': (C('#e5484d'), C('#ffffff'), [
        "..##..",
        "..##..",
        "..##..",
        "..##..",
        "..##..",
        "......",
        "..##..",
        "..##.."]),
    'done': (C('#2fb36b'), C('#ffffff'), [
        "........##",
        ".......##.",
        "##....##..",
        ".##..##...",
        "..####....",
        "...##....."]),
    'idle': (C('#f2f4ff'), C('#4b6fd6'), [
        "......#####",
        ".........#.",
        "........#..",
        "###....#...",
        "..#...#####",
        ".#.........",
        "###........"]),
    'working': (C('#f2f4ff'), C('#7a4fd6'), [
        "..#....#..#..",
        ".#....#....#.",
        "#.....#.....#",
        ".#...#.....#.",
        "..#..#....#.."]),
}


def bubble(state):
    fill, ink, g = ICONS[state]
    iw, ih = len(g[0]), len(g)
    bw, bh = iw + 6, ih + 5
    s = Spr(bw + 2, bh + 5, 3, bh + 4)
    hi = lt(fill, .45)
    for y in range(bh):
        for x in range(bw):
            if (x in (0, bw - 1)) and (y in (0, bh - 1)):
                continue
            s.put(x + 1, y + 1, fill)
    s.rect(2, 2, bw - 2, 2, hi)
    s.rect(2, bh - 1, bw - 1, bh - 1, dk(fill, .15))
    for i in range(3):  # tail
        s.rect(3, bh + 1 + i, 3 + 2 - i, bh + 1 + i, fill)
    for y, row in enumerate(g):
        for x, ch in enumerate(row):
            if ch == '#':
                s.put(x + 4, y + 3, ink)
    outline_out(s, INK)
    return s.crop()


# ---------------------------------------------------------------- sheet
def build_all(T):
    """Return dict name -> Spr for a theme."""
    d = {}
    for v in range(3):
        d[f'floor_{T["floor"]}_{v}'] = floor_tile(T, v)
    d['wall_rw'] = wall_seg(T, 'rw', 1)
    d['wall_rw_window'] = wall_seg(T, 'rw', 2, window=True)
    d['wall_lw'] = wall_seg(T, 'lw', 3)
    d['wall_lw_window'] = wall_seg(T, 'lw', 4, window=True)
    d['wall_corner'] = wall_corner(T)
    d['desk_rw'] = desk(T, 'rw', 1)
    d['desk_lw'] = desk(T, 'lw', 2, plant=True)
    d['chair_rw'] = chair(T, 'rw')
    d['chair_lw'] = chair(T, 'lw')
    d['chair_seat_rw'] = chair(T, 'rw', 'seat')
    d['chair_back_rw'] = chair(T, 'rw', 'back')
    d['plant_big'] = plant_big(T)
    d['whiteboard_rw'] = whiteboard(T, 'rw')
    d['whiteboard_lw'] = whiteboard(T, 'lw')
    d['coffee_lw'] = coffee_station(T, 'lw')
    d['bookshelf_lw'] = bookshelf(T, 'lw', 1)
    d['bookshelf_rw'] = bookshelf(T, 'rw', 2)
    for i in range(3):
        d[f'rug_{i}'] = rug(T, i, 24, 16)
    d['lamp'] = lamp(T)
    d['clock_rw'] = clock(T, 'rw')
    d['poster_rw'] = poster(T, 'rw', 0)
    d['nameplate'] = nameplate(T, 40)
    return d


def build_sheet():
    frames = {}
    frames.update(build_all(A))
    b = build_all(B)
    for k in ('floor_tile_0', 'floor_tile_1', 'wall_rw', 'wall_rw_window', 'wall_lw_window', 'desk_rw', 'chair_rw', 'rug_0', 'nameplate'):
        frames[k + ('' if k.startswith('floor_tile') else '_b')] = b[k]
    frames['glass_rw_b'] = glass(B, 'rw')
    frames['glass_lw_b'] = glass(B, 'lw')
    for vi, P in enumerate(PEOPLE):
        for st in ('working', 'waiting', 'done', 'idle'):
            frames[f'person{vi}_{st}'] = person(P, st)
    for st in ('working', 'waiting', 'done', 'idle'):
        frames[f'bubble_{st}'] = bubble(st)
    pack(frames, OUT)
    preview(frames)
    return frames


def pack(frames, out_dir):
    """Shelf-pack [frames] into out_dir/sprites.png and its atlas out_dir/sprites.json."""
    names = sorted(frames, key=lambda n: -frames[n].h)
    SW, pad = 512, 2
    x = y = rowh = 0
    pos = {}
    for n in names:
        f = frames[n]
        if x + f.w + pad > SW:
            x, y, rowh = 0, y + rowh + pad, 0
        pos[n] = (x, y)
        x += f.w + pad
        rowh = max(rowh, f.h)
    sheet = Image.new('RGBA', (SW, y + rowh), (0, 0, 0, 0))
    atlas = {'meta': {'image': 'sprites.png', 'size': [SW, y + rowh], 'scale': 1,
                      'iso': '2:1, 1 unit=(2,1)px, floor tile 64x32=16 units',
                      'anchor': 'pixel in frame that maps to the iso origin / feet point'},
             'frames': {}}
    for n in sorted(frames):
        f = frames[n]
        px, py = pos[n]
        sheet.alpha_composite(f.img, (px, py))
        atlas['frames'][n] = {'x': px, 'y': py, 'w': f.w, 'h': f.h, 'anchor': [f.ax, f.ay]}
    sheet.save(f'{out_dir}/sprites.png')
    with open(f'{out_dir}/sprites.json', 'w') as fp:
        json.dump(atlas, fp, indent=1)


def build_sheet_b():
    """Theme B's own sheet in OUT/b/: its _b art (B floor tiles keep their floor_tile_<n> names), the generic decor and the
    people and bubbles of A re-exported unchanged, so the theme is self-contained."""
    b = build_all(B)
    frames = {}
    for v in range(2):
        frames[f'floor_tile_{v}'] = b[f'floor_tile_{v}']
    for k in ('wall_rw', 'wall_rw_window', 'wall_lw', 'wall_lw_window', 'wall_corner', 'desk_rw', 'desk_lw',
              'chair_seat_rw', 'chair_back_rw', 'chair_lw', 'rug_0', 'rug_1', 'rug_2', 'nameplate',
              'plant_big', 'bookshelf_lw', 'coffee_lw'):
        frames[k + '_b'] = b[k]
    frames['glass_rw_b'] = glass(B, 'rw')
    frames['glass_lw_b'] = glass(B, 'lw')
    for k in ('whiteboard_rw', 'whiteboard_lw', 'clock_rw', 'poster_rw', 'lamp'):  # same pixels in every theme
        frames[k] = b[k]
    for vi, P in enumerate(PEOPLE):
        for st in ('working', 'waiting', 'done', 'idle'):
            frames[f'person{vi}_{st}'] = person(P, st)
    for st in ('working', 'waiting', 'done', 'idle'):
        frames[f'bubble_{st}'] = bubble(st)
    out = f'{OUT}/b'
    os.makedirs(out, exist_ok=True)
    pack(frames, out)
    return frames


def preview(frames):
    S = 4
    font = ImageFont.truetype(FONT, 13, index=4)
    names = sorted(frames, key=lambda n: (n.split('_')[0], n))
    PW = 1800
    cells = []
    x = y = rowh = 0
    for n in names:
        f = frames[n]
        cw = max(f.w * S, int(font.getlength(n)) + 8) + 16
        ch = f.h * S + 26
        if x + cw > PW:
            x, y, rowh = 0, y + rowh + 10, 0
        cells.append((n, x, y, cw))
        x += cw
        rowh = max(rowh, ch)
    img = Image.new('RGBA', (PW, y + rowh + 20), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    for yy in range(0, img.height, 16):
        for xx in range(0, PW, 16):
            c = (58, 60, 68) if (xx // 16 + yy // 16) % 2 else (46, 48, 55)
            d.rectangle([xx, yy, xx + 15, yy + 15], fill=c)
    for n, cx, cy, cw in cells:
        f = frames[n]
        big = f.img.resize((f.w * S, f.h * S), Image.NEAREST)
        ix = cx + (cw - f.w * S) // 2
        img.alpha_composite(big, (ix, cy + 4))
        # anchor marker
        ax, ay = ix + f.ax * S + S // 2, cy + 4 + f.ay * S + S // 2
        d.line([ax - 3, ay, ax + 3, ay], fill=(255, 80, 200))
        d.line([ax, ay - 3, ax, ay + 3], fill=(255, 80, 200))
        tw = font.getlength(n)
        d.rounded_rectangle([cx + cw / 2 - tw / 2 - 4, cy + f.h * S + 6, cx + cw / 2 + tw / 2 + 4, cy + f.h * S + 23],
                            4, fill=(20, 20, 26))
        d.text((cx + cw / 2 - tw / 2, cy + f.h * S + 7), n, font=font, fill=(235, 235, 240))
    img.convert('RGB').save(f'{OUT}/sprites-preview.png')


if __name__ == '__main__':
    fr = build_sheet()
    print(len(fr), 'frames (theme A sheet)')
    fb = build_sheet_b()
    print(len(fb), 'frames (theme B sheet, b/)')
