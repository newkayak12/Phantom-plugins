"""Office people at twice the scene's pixel density (themes A, B and C).

Every frame here is drawn at DETAIL x the native resolution: the renderer draws it 1:1 into the room composed at DETAIL x (see
Theme.personDetail), so one native pixel holds 2x2 of these. Anchors, widths and heights are even, so a frame covers whole native
pixels and the layout can work in native units (frame size and anchor divided by DETAIL).

Looks: chunky, big heads (about a third of the height), warm palette, a warm dark outline outside the silhouette, darker lines of
each material between parts, two or three tones per material lit from the upper left.

Poses (frame names, `<sfx>` is '' for A/B and '_c' for C; ThemeA/B/C name them through OfficePeople.kt):
  person<v>_working_<phase>[_lw]         seated at the desk seen from behind, typing; 4 phases; facing up-right, `_lw` up-left
  person<v>_idle[_lw]                    asleep, head down on the folded arms
  person<v>_waiting_<0|1>                standing, front 3/4, an open hand raised high, calling
  person<v>_done_<0|1>                   standing, front 3/4, smiling eyes closed, holding a steaming mug
"""
from PIL import Image, ImageDraw
from pix import C, mix, lt, Spr

DETAIL = 2

INKW = C('#2c1b22')          # warm outline
_SHADE = (88, 38, 62, 255)   # warm purple shadow target
_DEEP = (40, 20, 34, 255)


def wdk(c, t):
    return mix(c, _SHADE, t) if t < .5 else mix(mix(c, _SHADE, .5), _DEEP, (t - .5) * 2)


def ramp(c):
    c = C(c) if isinstance(c, str) else c
    return dict(hi=lt(c, .24), b=c, sh=wdk(c, .26), dp=wdk(c, .42), ln=wdk(c, .62))


SKIN = {k: ramp(v) for k, v in dict(pale='#f8d9bf', light='#f1c39b', medium='#dca274', tan='#c58556',
                                     brown='#9a6242', dark='#6c4330').items()}
for _k, _r in SKIN.items():
    _r['blush'] = mix(_r['b'], C('#ff6f7d'), .38)

EYE = C('#2a1d2a')
WHITE = C('#ffffff')
MOUTH = C('#8c3a3e')

# ---------------------------------------------------------------- the cast (>= 8 looks)
PEOPLE_HD = [
    dict(name='minjun', skin='light', hair='#5c3a25', style='short', top='hoodie', shirt='#4a7fd0', pants='#33374d',
         shoe='#3a2a24', lanyard='#d04848'),
    dict(name='sora', skin='light', hair='#7a4a2c', style='long', top='cardigan', shirt='#9a80c8', inner='#f4efe6',
         pants='#4b4060', shoe='#4a2e2a'),
    dict(name='aoi', skin='pale', hair='#4aa0e6', style='twintail', top='vest', shirt='#f2f2f6', vest='#34477a',
         pants='#2e3448', shoe='#2a2630', lanyard='#3a7ad8', iris='#2a5fb0'),
    dict(name='jae', skin='tan', hair='#2a2230', style='short', top='hoodie', shirt='#4f9a52', pants='#3a3a44',
         shoe='#2a2228', glasses='#2a2228'),
    dict(name='kofi', skin='dark', hair='#1f1a20', style='curly', top='sweater', shirt='#d9a23a', pants='#3c4258',
         shoe='#2a2024', lanyard='#2f9a6a'),
    dict(name='mina', skin='medium', hair='#2c2028', style='bun', top='cardigan', shirt='#d0574a', inner='#f6ead8',
         pants='#3a3548', shoe='#3a2420', glasses='#7a3a2a'),
    dict(name='lena', skin='pale', hair='#f0c060', style='ponytail', top='hoodie', shirt='#2fa6a0', pants='#43405a',
         shoe='#3a2a2a'),
    dict(name='raj', skin='brown', hair='#231c24', style='short', top='vest', shirt='#bcd6ee', vest='#5d6470',
         pants='#2f3240', shoe='#2a2020', beard=True, lanyard='#e0a030'),
    dict(name='hana', skin='light', hair='#d4683a', style='bob', top='cardigan', shirt='#e8903e', inner='#fbf4e6',
         pants='#3e3a52', shoe='#4a2e24', freckles=True),
    dict(name='theo', skin='medium', hair='#8a3a2a', style='messy', top='flannel', shirt='#b0463e', pants='#2f3a52',
         shoe='#2a2024', glasses='#2a2228', lanyard='#4a7fd0'),
]

STATES = ('working', 'waiting', 'done', 'idle')


# ---------------------------------------------------------------- a figure: parts as masks, shaded, then outlined
class Fig:
    """A frame being drawn, in local coordinates with the anchor at (0, 0) (the feet, or the seat for a seated pose)."""
    W, H, OX, OY = 96, 120, 48, 108

    def __init__(s):
        s.col = {}
        s.grp = {}
        s.order = {}
        s.n = 0
        s.lift = 0  # added to every y drawn: moves a part down

    def mask(s, *shapes):
        im = Image.new('L', (s.W, s.H), 0)
        d = ImageDraw.Draw(im)
        oy0 = s.OY
        s.OY += s.lift
        try:
            return s._mask(im, d, shapes, oy0)
        finally:
            s.OY = oy0

    def _mask(s, im, d, shapes, oy0):
        for sh in shapes:
            kind, a = sh[0], sh[1:]
            if kind == 'e':
                x0, y0, x1, y1 = a[0]
                d.ellipse([x0 + s.OX, y0 + s.OY, x1 + s.OX, y1 + s.OY], fill=255)
            elif kind == 'r':
                x0, y0, x1, y1 = a[0]
                d.rectangle([x0 + s.OX, y0 + s.OY, x1 + s.OX, y1 + s.OY], fill=255)
            elif kind == 'p':
                d.polygon([(x + s.OX, y + s.OY) for x, y in a[0]], fill=255)
            elif kind == 'l':
                pts, wd = a
                d.line([(x + s.OX, y + s.OY) for x, y in pts], fill=255, width=wd)
                for x, y in pts:
                    r = (wd - 1) / 2
                    d.ellipse([x + s.OX - r, y + s.OY - r, x + s.OX + r, y + s.OY + r], fill=255)
        px = im.load()
        return {(x - s.OX, y - oy0) for y in range(s.H) for x in range(s.W) if px[x, y]}

    def paint(s, m, R, group, shade='rb', hl=True, line=True):
        """Fill mask m with ramp R: base, a shadow band on the right/bottom, a 1px highlight on the upper left."""
        s.n += 1
        for (x, y) in m:
            c = R['b']
            if shade and (('r' in shade and ((x + 1, y) not in m or (x + 2, y) not in m)) or
                          ('b' in shade and (x, y + 1) not in m) or ('l' in shade and (x - 2, y) not in m)):
                c = R['sh']
            elif hl and ((x - 1, y) not in m or (x, y - 1) not in m) and (x + 1, y + 1) in m:
                c = R['hi']
            s.col[(x, y)] = c
            s.grp[(x, y)] = group if line else None
            s.order[(x, y)] = s.n

    def px(s, x, y, c, group=None):
        if c is None:
            return
        y += s.lift
        s.col[(x, y)] = c
        if group is not None:
            s.grp[(x, y)] = group
        s.order.setdefault((x, y), s.n)

    def hl(s, x0, x1, y, c):
        for x in range(x0, x1 + 1):
            s.px(x, y, c)

    def vl(s, x, y0, y1, c):
        for y in range(y0, y1 + 1):
            s.px(x, y, c)

    def fill(s, m, c):
        for p in m:
            if p in s.col:
                s.col[p] = c

    def finish(s, lines=True):
        """Lines of each material where it meets a different part behind it, the warm outline outside, then a Spr."""
        col = dict(s.col)
        if lines:
            for (x, y), c in s.col.items():
                g = s.grp.get((x, y))
                if g is None:
                    continue
                o = s.order[(x, y)]
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    q = (x + dx, y + dy)
                    if q in s.col and s.grp.get(q) not in (None, g) and s.order[q] < o:
                        col[(x, y)] = LINES.get(c, wdk(c, .55))
                        break
        out = dict(col)
        for (x, y) in col:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                q = (x + dx, y + dy)
                if q not in col:
                    out[q] = INKW
        xs = [p[0] for p in out]
        ys = [p[1] for p in out]
        x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
        # even size and even anchor: the frame covers whole native pixels
        if (0 - x0) % 2:
            x0 -= 1
        if (0 - y0) % 2:
            y0 -= 1
        w, h = x1 - x0 + 1, y1 - y0 + 1
        w += w % 2
        h += h % 2
        sp = Spr(w, h, -x0, -y0)
        for (x, y), c in out.items():
            sp.put(x - x0, y - y0, c)
        return sp


LINES = {}


def flip(sp):
    """[sp] mirrored left-right about its anchor (anchor kept even)."""
    n = Spr(sp.w, sp.h, sp.w - sp.ax, sp.ay)
    n.img = sp.img.transpose(Image.FLIP_LEFT_RIGHT)
    n.em = sp.em.transpose(Image.FLIP_LEFT_RIGHT)
    n._load()
    return n


# ---------------------------------------------------------------- clothes
def looks(P):
    sk = SKIN[P['skin']]
    hair = ramp(P['hair'])
    hair['hi2'] = lt(C(P['hair']), .5)
    top = ramp(P['shirt'])
    pants = ramp(P['pants'])
    shoe = ramp(P['shoe'])
    vest = ramp(P['vest']) if 'vest' in P else None
    inner = ramp(P['inner']) if 'inner' in P else None
    return sk, hair, top, pants, shoe, vest, inner


def torso_front(f, P, poly, sleeves):
    """The upper body seen from the front: [poly] the torso, [sleeves] the arm masks (painted after the torso)."""
    sk, hair, top, pants, shoe, vest, inner = looks(P)
    body = f.mask(('p', poly))
    kind = P['top']
    if kind == 'vest':
        f.paint(body, top, 'shirt')
        v = f.mask(('p', poly))
        v = {p for p in v if abs(p[0] + 0.5) > 2 + max(0, (p[1] - f.lift + 52)) * 0.35 or p[1] - f.lift > -44}
        f.paint(v, vest, 'vest')
        # collar
        for i in range(4):
            f.px(-2 - i, -57 + i, top['hi']); f.px(1 + i, -57 + i, top['b'])
        for y in range(-50, -36, 4):
            f.px(0, y, vest['dp'])
    elif kind == 'cardigan':
        f.paint(body, top, 'shirt')
        panel = {p for p in body if -4 <= p[0] <= 3 and p[1] - f.lift < -36}
        f.paint(panel, inner, 'inner', shade='', hl=False)
        for y in range(-52, -37, 5):
            f.px(-5, y, top['dp'])
        f.vl(-5, -56, -36, top['sh'])
        f.vl(4, -56, -36, top['dp'])
    elif kind == 'hoodie':
        f.paint(body, top, 'shirt')
        # strings and a kangaroo pocket
        f.vl(-3, -56, -49, WHITE); f.vl(2, -56, -50, WHITE)
        f.hl(-7, 6, -42, top['sh'])
        f.vl(-8, -41, -36, top['sh']); f.vl(7, -41, -36, top['sh'])
    elif kind == 'flannel':
        f.paint(body, top, 'shirt')
        for (x, y) in body:
            if (x + 40) % 5 == 0 or (y - f.lift + 60) % 5 == 0:
                f.col[(x, y)] = top['dp'] if ((x + 40) % 5 == 0 and (y - f.lift + 60) % 5 == 0) else top['sh']
        f.vl(0, -56, -36, top['ln'])
    else:  # sweater
        f.paint(body, top, 'shirt')
        f.hl(-11, 11, -37, top['sh'])
        for x in range(-4, 4):
            f.px(x, -57, top['hi'])
    # folds under the arms and at the waist
    for sx in (-1, 1):
        for i in range(5):
            f.px(sx * (10 - i // 2), -52 + i * 2, (vest or top)['sh'])
    f.hl(-9, 8, -34, (vest or top)['dp'])
    for m in sleeves:
        f.paint(m, top, 'sleeve')
    if kind in ('hoodie', 'sweater'):
        for x in range(-5, 6):
            f.px(x, -58 + (1 if abs(x) < 3 else 0), top['dp'] if kind == 'hoodie' else top['hi'])
    if P.get('lanyard'):
        ly = C(P['lanyard'])
        for i in range(8):
            f.px(-5 + i // 3, -57 + i, ly); f.px(4 - i // 3, -57 + i, ly)
        f.paint(f.mask(('r', (-3, -50, 2, -43))), ramp('#f6f6f2'), 'badge', shade='rb', hl=False)
        f.hl(-2, 1, -49, ly)
        f.hl(-2, 0, -46, C('#9aa0aa'))


LIFT = 6  # the standing body sits this much lower than drawn: short, chunky legs


def legs_front(f, P, spread=0):
    sk, hair, top, pants, shoe, vest, inner = looks(P)
    t = -31 + LIFT
    f.paint(f.mask(('r', (-11, t - 5, 11, t + 2))), pants, 'pants')
    f.paint(f.mask(('p', [(-11, t), (-1, t), (-2, -7), (-10, -7)])), pants, 'legL', shade='r')
    f.paint(f.mask(('p', [(1, t), (11, t), (10 + spread, -7), (2 + spread, -7)])), pants, 'legR', shade='r')
    f.paint(f.mask(('e', (-13, -10, -1, 1)), ), shoe, 'shoeL')
    f.paint(f.mask(('e', (1 + spread, -10, 13 + spread, 1))), shoe, 'shoeR')
    for x in range(-14, 15):
        f.col.pop((x, 1), None)
    f.hl(-10, -5, -8, shoe['hi']); f.hl(4 + spread, 9 + spread, -8, shoe['hi'])


# ---------------------------------------------------------------- heads
HEAD = (-13, -87, 13, -61)  # front head box (local), centre (0, -74)


def hair_back_layer(f, P, cx, cy, back=False):
    """Hair that hangs behind the body (long, twin tails, ponytail): drawn before the torso."""
    sk, hair, *_ = looks(P)
    st = P['style']
    if st == 'long':
        m = f.mask(('e', (cx - 16, cy - 14, cx + 16, cy + 12)), ('r', (cx - 16, cy - 2, cx + 16, cy + 22)))
        m = {p for p in m if p[1] - f.lift < cy + 22 - abs(p[0] - cx) // 4}
        f.paint(m, hair, 'hairback', shade='rb')
        for x in range(cx - 14, cx + 15, 4):
            f.vl(x, cy + 8, cy + 19, hair['sh'])
    elif st == 'twintail':
        for sx in (-1, 1):
            if back and sx < 0:
                pass
            m = f.mask(('e', (cx + sx * 15 - 5, cy - 6, cx + sx * 15 + 5, cy + 22)))
            f.paint(m, hair, 'hairback', shade='rb')
            f.vl(cx + sx * 15, cy + 2, cy + 16, hair['sh'])
    elif st == 'ponytail' and not back:
        m = f.mask(('e', (cx + 8, cy - 8, cx + 18, cy + 18)))
        f.paint(m, hair, 'hairback', shade='rb')


def head_front(f, P, expr='open', hy=-74, hx=0):
    """A front 3/4 head looking a little to the left: face, ear on the right, hair by style. [expr]: open, calling, happy."""
    sk, hair, *_ = looks(P)
    cx, cy = hx, hy
    f.paint(f.mask(('r', (cx - 3, cy + 11, cx + 3, cy + 17))), sk, 'neck', shade='', hl=False)
    f.hl(cx - 3, cx + 3, cy + 12, sk['sh']); f.hl(cx - 3, cx + 3, cy + 13, sk['sh'])
    face = f.mask(('e', (cx - 13, cy - 13, cx + 13, cy + 12)), ('p', [(cx - 12, cy + 2), (cx + 12, cy + 2), (cx + 7, cy + 13), (cx - 7, cy + 13)]))
    f.paint(face, sk, 'face', shade='r', hl=False)
    # ear
    f.paint(f.mask(('e', (cx + 10, cy - 3, cx + 15, cy + 5))), sk, 'face', hl=False)
    f.px(cx + 12, cy, sk['sh']); f.px(cx + 12, cy + 1, sk['sh']); f.px(cx + 13, cy + 2, sk['dp'])
    # chin shadow
    f.hl(cx - 5, cx + 5, cy + 13, sk['sh'])
    # eyes (looking a little left)
    ex = (cx - 8, cx + 2)
    ey = cy + 1
    if expr == 'happy':
        for x0 in ex:
            f.px(x0, ey + 1, EYE); f.px(x0 + 1, ey, EYE); f.px(x0 + 2, ey, EYE); f.px(x0 + 3, ey + 1, EYE)
    else:
        iris = C(P.get('iris', '#3a2a2e'))
        for x0 in ex:
            f.vl(x0, ey - 1, ey + 3, EYE); f.vl(x0 + 1, ey - 1, ey + 3, EYE); f.vl(x0 + 2, ey, ey + 2, EYE)
            f.px(x0 + 1, ey + 2, iris); f.px(x0 + 1, ey + 3, iris)
            f.px(x0, ey - 1, WHITE); f.px(x0 + 1, ey, WHITE)
    # brows
    by = ey - 4 if expr != 'calling' else ey - 5
    for x0 in ex:
        f.hl(x0 - 1, x0 + 2, by, hair['dp'])
        if expr == 'calling':
            f.px(x0 + (2 if x0 == ex[0] else -1), by + 1, hair['dp'])
    # nose, blush, mouth
    f.px(cx - 3, ey + 5, sk['sh'])
    blush = sk['blush']
    if not P.get('beard'):
        f.hl(cx - 11, cx - 9, ey + 6, blush); f.hl(cx + 5, cx + 7, ey + 6, blush)
    if P.get('freckles'):
        for x, y in ((cx - 10, ey + 5), (cx - 8, ey + 6), (cx + 6, ey + 5), (cx + 8, ey + 6)):
            f.px(x, y, sk['dp'])
    my = ey + 8
    if expr == 'calling':
        f.hl(cx - 4, cx - 1, my, MOUTH); f.hl(cx - 4, cx - 1, my + 1, C('#5a2228')); f.hl(cx - 3, cx - 2, my + 2, C('#e07a80'))
        f.px(cx - 5, my, MOUTH); f.px(cx, my, MOUTH)
    elif expr == 'happy':
        f.hl(cx - 5, cx, my, MOUTH); f.hl(cx - 4, cx - 1, my + 1, C('#5a2228')); f.hl(cx - 3, cx - 2, my + 2, C('#e07a80'))
    else:
        f.px(cx - 5, my - 1, MOUTH); f.hl(cx - 4, cx - 1, my, MOUTH); f.px(cx, my - 1, MOUTH)
    if P.get('beard'):
        bm = f.mask(('e', (cx - 12, cy + 2, cx + 11, cy + 15)))
        bm = {p for p in bm if p in face and p[1] - f.lift >= cy + 6 and not (cx - 6 <= p[0] <= cx + 1 and cy + 8 <= p[1] - f.lift <= cy + 10)}
        for p in bm:
            f.col[p] = hair['b'] if (p[0] + p[1]) % 3 else hair['sh']
    if P.get('glasses'):
        g = C(P['glasses'])
        lens = mix(sk['b'], C('#e8f2ff'), .35)
        for x0 in ex:
            for x in range(x0 - 2, x0 + 5):
                f.px(x, ey - 2, g); f.px(x, ey + 4, g)
            f.vl(x0 - 2, ey - 2, ey + 4, g); f.vl(x0 + 4, ey - 2, ey + 4, g)
            f.px(x0 + 3, ey - 1, lens)
        f.hl(ex[0] + 5, ex[1] - 3, ey, g)
        f.hl(ex[1] + 5, cx + 11, ey, g)
    hair_front(f, P, cx, cy)


def hair_front(f, P, cx, cy):
    sk, hair, *_ = looks(P)
    st = P['style']
    cap = f.mask(('e', (cx - 15, cy - 17, cx + 15, cy + 9)))
    # fringe: down to the brows, a parting a little right of centre, side locks before the ear
    fr = set()
    for (x, y) in cap:
        dx = x - cx
        edge = cy - 6 + (abs((dx + 40) % 6 - 3) if -11 <= dx <= 9 else 0)
        if dx < -11:
            edge = cy + 6 if st not in ('short', 'messy') else cy + 2
        if dx > 9:
            edge = cy - 3
        if st in ('long', 'bob', 'twintail') and (dx < -11 or dx > 10):
            edge = cy + 9
        if y - f.lift <= edge:
            fr.add((x, y))
    if st == 'curly':
        cm = f.mask(*[('e', (cx + ox - 6, cy + oy - 6, cx + ox + 6, cy + oy + 6))
                      for ox, oy in ((-11, -8), (-6, -14), (1, -16), (8, -13), (12, -6), (-13, -1), (13, 0), (-3, -10), (5, -8))])
        fr = {p for p in cm if p[1] - f.lift <= cy - 3 or abs(p[0] - cx) > 11}
    if st == 'messy':
        fr |= f.mask(('p', [(cx - 8, cy - 16), (cx - 4, cy - 21), (cx - 2, cy - 15)]),
                     ('p', [(cx + 2, cy - 16), (cx + 7, cy - 20), (cx + 8, cy - 14)]))
    if st == 'bob':
        fr |= {p for p in f.mask(('r', (cx - 15, cy - 4, cx + 15, cy + 10))) if abs(p[0] - cx) >= 11}
    f.paint(fr, hair, 'hair', shade='rb')
    # the fringe's shadow on the forehead
    for (x, y) in fr:
        q = (x, y + 1)
        if q not in fr and f.col.get(q) == sk['b']:
            f.col[q] = sk['sh']
    for x in (cx - 6, cx - 1, cx + 4):
        f.px(x, cy - 9, hair['sh']); f.px(x, cy - 8, hair['sh'])
    # shine
    for i, x in enumerate(range(cx + 3, cx + 7)):
        f.px(x, cy - 12 + (1 if i == 3 else 0), hair['hi'])
    for i, x in enumerate(range(cx - 9, cx - 2)):
        f.px(x, cy - 13 + (1 if i in (0, 6) else 0), hair['hi2'])
    f.px(cx - 10, cy - 11, hair['hi'])
    if st == 'curly':
        for ox, oy in ((-11, -8), (-6, -14), (1, -16), (8, -13), (12, -6), (-3, -10), (5, -8)):
            f.px(cx + ox - 2, cy + oy - 2, hair['hi']); f.px(cx + ox + 2, cy + oy + 3, hair['sh'])
    if st == 'bun':
        f.paint(f.mask(('e', (cx - 7, cy - 25, cx + 7, cy - 12))), hair, 'bun')
        f.hl(cx - 3, cx + 1, cy - 23, hair['hi2'])
        f.hl(cx - 6, cx + 6, cy - 14, hair['dp'])
    if st == 'ponytail':
        f.paint(f.mask(('e', (cx + 9, cy - 16, cx + 17, cy - 6))), hair, 'tie')
        f.px(cx + 11, cy - 12, C('#e85a7a')); f.px(cx + 12, cy - 11, C('#e85a7a'))
    if st == 'twintail':
        for sx in (-1, 1):
            f.paint(f.mask(('e', (cx + sx * 14 - 3, cy - 12, cx + sx * 14 + 3, cy - 6))), ramp('#f4d04a'), 'tie')


def head_back(f, P, cx, cy, side=1):
    """A head seen from behind, turned a little to the right (we see the right ear and cheek); [side] 1."""
    sk, hair, *_ = looks(P)
    st = P['style']
    f.paint(f.mask(('r', (cx - 4, cy + 9, cx + 4, cy + 15))), sk, 'neck', shade='r', hl=False)
    head = f.mask(('e', (cx - 13, cy - 13, cx + 13, cy + 12)))
    f.paint(head, sk, 'face', shade='', hl=False)
    # cheek and ear on the right
    f.paint(f.mask(('e', (cx + 9, cy - 3, cx + 15, cy + 6))), sk, 'face', hl=False)
    f.px(cx + 12, cy, sk['sh']); f.px(cx + 12, cy + 1, sk['sh']); f.px(cx + 11, cy + 3, sk['dp'])
    if P.get('glasses'):
        g = C(P['glasses'])
        f.hl(cx + 7, cx + 13, cy - 1, g)
    covered = f.mask(('e', (cx - 15, cy - 16, cx + 14, cy + 11)))
    if st in ('short', 'messy', 'curly'):
        covered = {p for p in covered if not (p[0] > cx + 9 and -3 < p[1] - f.lift - cy < 6) and
                   not (abs(p[0] - cx) <= 4 and p[1] - f.lift > cy + 8)}
    elif st == 'bun':
        covered = {p for p in covered if not (p[0] > cx + 9 and -3 < p[1] - f.lift - cy < 6) and
                   not (abs(p[0] - cx) <= 5 and p[1] - f.lift > cy + 6)}
    else:
        covered = {p for p in covered if not (p[0] > cx + 10 and -3 < p[1] - f.lift - cy < 7)}
    if st == 'curly':
        covered |= f.mask(*[('e', (cx + ox - 6, cy + oy - 6, cx + ox + 6, cy + oy + 6))
                            for ox, oy in ((-11, -6), (-5, -13), (3, -14), (10, -9), (-10, 3), (0, -4), (8, 0))])
        covered = {p for p in covered if not (p[0] > cx + 9 and p[1] - f.lift > cy - 3)}
    if st == 'bob':
        covered |= {p for p in f.mask(('r', (cx - 15, cy, cx + 10, cy + 12)))}
    if st == 'long':
        covered |= f.mask(('r', (cx - 15, cy, cx + 12, cy + 26)))
        covered = {p for p in covered if p[1] - f.lift < cy + 26 - abs(p[0] - cx) // 4}
    f.paint(covered, hair, 'hair', shade='rb')
    # strands, a shadow where the hair meets the neck, the cheek's edge
    for i, x in enumerate(range(cx - 10, cx + 9, 3)):
        f.vl(x, cy - 4 + (i % 2) * 2, cy + 4 + (i % 2), hair['sh'])
        f.px(x + 1, cy - 6 + (i % 2) * 2, hair['hi'])
    for (x, y) in covered:
        if (x, y + 1) not in covered and f.col.get((x, y + 1)) == sk['b']:
            f.col[(x, y + 1)] = sk['dp']
    f.px(cx + 9, cy + f.lift + 5, sk['sh']); f.px(cx + 10, cy + f.lift + 6, sk['sh'])
    for i, x in enumerate(range(cx - 7, cx + 1)):
        f.px(x, cy - 11 + (1 if i in (0, 7) else 0), hair['hi2'])
    if st == 'curly':
        for ox, oy in ((-11, -6), (-5, -13), (3, -14), (10, -9), (-10, 3), (0, -4)):
            f.px(cx + ox - 2, cy + oy - 2, hair['hi']); f.px(cx + ox + 2, cy + oy + 3, hair['sh'])
    if st == 'messy':
        f.paint(f.mask(('p', [(cx - 6, cy - 13), (cx - 2, cy - 19), (cx, cy - 13)]),
                       ('p', [(cx + 3, cy - 13), (cx + 8, cy - 17), (cx + 9, cy - 11)])), hair, 'hair')
    if st == 'bun':
        f.paint(f.mask(('e', (cx - 6, cy - 22, cx + 7, cy - 10))), hair, 'bun')
        f.hl(cx - 2, cx + 2, cy - 20, hair['hi2'])
        f.hl(cx - 5, cx + 6, cy - 12, hair['dp'])
    if st == 'ponytail':
        f.paint(f.mask(('e', (cx - 4, cy - 2, cx + 4, cy + 4))), ramp('#e85a7a'), 'tie')
        m = f.mask(('e', (cx - 5, cy + 2, cx + 5, cy + 24)))
        f.paint(m, hair, 'tail', shade='rb')
        f.vl(cx, cy + 6, cy + 20, hair['sh'])
    if st == 'twintail':
        for sx in (-1, 1):
            f.paint(f.mask(('e', (cx + sx * 12 - 3, cy - 8, cx + sx * 12 + 3, cy - 2))), ramp('#f4d04a'), 'tie')
            f.paint(f.mask(('e', (cx + sx * 14 - 5, cy - 4, cx + sx * 14 + 5, cy + 22))), hair, 'tail', shade='rb')
            f.vl(cx + sx * 14, cy + 2, cy + 18, hair['sh'])


# ---------------------------------------------------------------- poses
def hand(f, sk, x0, y0, x1, y1, group='hand', thumb=-1):
    """A small hand: a rounded palm, finger creases at the bottom, a thumb on the left (-1) or right (1) side."""
    f.paint(f.mask(('e', (x0, y0, x1, y1))), sk, group, hl=False)
    tx = x0 - 1 if thumb < 0 else x1 + 1
    f.paint(f.mask(('r', (min(tx, tx - thumb), y0 + 2, max(tx, tx - thumb), y0 + 3))), sk, group, shade='', hl=False)
    for x in range(x0 + 2, x1 - 1, 2):
        f.px(x, y1 - 1, sk['sh'])
    f.px(x0 + 1, y0 + 1, sk['hi'])


def cuff(f, top, x, y):
    """A sleeve's cuff around the wrist at (x, y)."""
    f.paint(f.mask(('e', (x - 3, y - 2, x + 3, y + 2))), ramp(top['dp']), 'cuff', hl=False)


def standing(P, state, phase):
    f = Fig()
    sk, hair, top, *_ = looks(P)
    legs_front(f, P)
    f.lift = LIFT
    hair_back_layer(f, P, 0, -74)
    torso = [(-12, -58), (12, -58), (14, -52), (12, -33), (-12, -33), (-14, -52)]
    shoulders = [f.mask(('e', (-16, -60, -6, -48))), f.mask(('e', (6, -60, 16, -48)))]
    w = phase % 2
    if state == 'waiting':
        # the right arm (on our left? no: the person's left, our right) raised high, palm open to us
        dx = w
        sleeves = [f.mask(('l', [(-12, -54), (-15, -44), (-15, -37)], 7)),
                   f.mask(('l', [(12, -54), (19, -64), (20 + dx, -76)], 7))]
        torso_front(f, P, torso, shoulders + sleeves)
        cuff(f, top, -15, -38)
        cuff(f, top, 20 + dx, -75)
        hand(f, sk, -19, -38, -12, -31, thumb=1)
        # open hand: palm, four fingers apart, a thumb out to the left
        hx, hy = 20 + dx, -82
        f.paint(f.mask(('e', (hx - 5, hy - 2, hx + 5, hy + 8))), sk, 'hand', hl=False)
        for i, (fx, ln) in enumerate(((-4, 5), (-1, 7), (2, 7), (5, 5))):
            f.paint(f.mask(('r', (hx + fx, hy - ln, hx + fx + 1, hy))), sk, 'hand', shade='', hl=False)
            f.px(hx + fx + 1, hy - ln + 1, sk['sh'])
        f.paint(f.mask(('l', [(hx - 5, hy + 4), (hx - 9, hy - 1)], 3)), sk, 'hand', shade='', hl=False)
        f.hl(hx - 3, hx + 3, hy + 6, sk['sh'])
        f.px(hx, hy + 3, sk['sh']); f.px(hx + 1, hy + 4, sk['sh'])
        head_front(f, P, 'calling' if w == 0 else 'open')
    elif state == 'done':
        sleeves = [f.mask(('l', [(-12, -54), (-16, -44), (-8, -42)], 7)),
                   f.mask(('l', [(12, -54), (15, -44), (6, -42)], 7))]
        torso_front(f, P, torso, shoulders + sleeves)
        # the mug, held in both hands at the chest
        mug = ramp('#f6f2ea')
        f.paint(f.mask(('r', (-6, -52, 4, -39))), mug, 'mug')
        f.paint(f.mask(('e', (3, -50, 9, -42))) - f.mask(('e', (5, -48, 7, -44))), mug, 'mug', hl=False)
        f.hl(-5, 3, -51, C('#6b3a22')); f.hl(-4, 2, -50, C('#8a5030'))
        f.hl(-6, 4, -46, C(P.get('lanyard', '#e0574f')))
        cuff(f, top, -10, -43)
        cuff(f, top, 8, -43)
        hand(f, sk, -11, -48, -5, -41, 'handL', thumb=1)
        hand(f, sk, 2, -48, 8, -41, 'handR', thumb=-1)
        head_front(f, P, 'happy')
        # steam beside the head
        st = C('#fffaf0', 220)
        for i in range(6):
            f.px(-8 + ((i + phase) // 2 % 2), -53 - i, st)
            f.px(-3 + ((i + phase + 1) // 2 % 2), -54 - i, st)
    sp = f.finish()
    return sp


SIT = 5  # the seated torso and head sit this much lower than drawn


def seated(P, state, phase):
    """Seated at a desk seen from behind, facing up-right; the anchor is the seat."""
    f = Fig()
    sk, hair, top, pants, *_ = looks(P)
    vest = looks(P)[5]
    if state == 'idle':
        bx, hx, hy = 6, 16, -36
        # torso bent forward over the desk
        f.paint(f.mask(('e', (-8, -10, 16, 2))), pants, 'pants')
        torso = f.mask(('p', [(-8, -6), (16, -6), (26, -26), (24, -32), (4, -34), (-6, -22)]))
        f.paint(torso, vest if vest else top, 'shirt')
        hair_back_layer(f, P, hx, hy, back=True)
        # folded arms as a pillow
        f.paint(f.mask(('e', (6, -34, 34, -22))), top, 'sleeve')
        f.hl(10, 30, -26, top['sh'])
        hand(f, sk, 28, -31, 34, -25)
        head_back(f, P, hx, hy - 2)
        # the head is resting: the bottom of the face hidden by the arms
        f.paint(f.mask(('e', (6, -29, 32, -21))), ramp(top['sh']), 'sleeve2')
        hand(f, sk, 29, -28, 35, -22, 'hand2')
        f.hl(10, 28, -21, top['sh'])
        sp = f.finish()
        return sp
    bob = 1 if phase in (1, 3) else 0
    hx, hy = 8, -50 + bob
    # hips on the seat
    f.paint(f.mask(('e', (-6, -10, 18, 2))), pants, 'pants')
    # left arm: a sleeve behind the body, its hand on the keyboard peeking past the shoulder
    lh = (0, -1, 1, -1)[phase]
    f.lift = SIT  # a short back: the head as high as the monitor's top
    f.paint(f.mask(('l', [(-2, -30), (-5, -18), (10, -24)], 7)), top, 'sleeveL')
    # the right thigh, forward under the desk, and a shoe tip by the chair
    f.lift = 0
    f.paint(f.mask(('p', [(8, -10), (18, -12), (30, -15), (31, -9), (20, -3), (10, -2)])), pants, 'thigh')
    f.hl(19, 29, -9, pants['dp'])
    f.lift = SIT
    torso = f.mask(('p', [(-6, -6), (16, -6), (18, -24), (16, -33), (0, -34), (-7, -24)]),
                   ('e', (-8, -37, 4, -25)), ('e', (8, -37, 20, -25)))
    f.paint(torso, vest if vest else top, 'shirt')
    sh = (vest or top)
    f.vl(6, -30, -10, sh['sh'])  # spine
    for i in range(4):
        f.px(-2 + i, -24 + i, sh['sh']); f.px(13 - i, -24 + i, sh['sh'])  # shoulder blades
    f.hl(-5, 15, -8, sh['dp'])
    if P['top'] == 'hoodie':
        f.paint(f.mask(('e', (-1, -42, 17, -31))), top, 'hood')
        f.hl(2, 14, -33, top['sh'])
    if P['top'] == 'cardigan':
        f.vl(5, -34, -8, top['sh'])
    if P['top'] == 'flannel':
        for (x, y) in torso:
            if (x + 40) % 5 == 0 or (y + 60) % 5 == 0:
                f.col[(x, y)] = top['sh']
    hair_back_layer(f, P, hx, hy, back=True)
    head_back(f, P, hx, hy)
    # right arm: down from the shoulder, the forearm forward to the keyboard
    rh = (0, -2, 0, -1)[phase]
    rx = (0, 0, 1, 0)[phase]
    f.lift = 0
    arm = f.mask(('l', [(17, -31 + SIT), (21, -16), (24 + rx, -21 + rh)], 7))
    f.paint(arm, top, 'sleeveR')
    f.px(22, -17, top['dp']); f.px(21, -18, top['dp'])  # elbow
    f.paint(f.mask(('e', (21 + rx, -24 + rh, 26 + rx, -18 + rh))), ramp(top['dp']), 'cuff')
    hand(f, sk, 24 + rx, -26 + rh, 30 + rx, -20 + rh, thumb=-1)
    f.px(28 + rx, -25 + rh, sk['sh'])
    # the other hand, on the keyboard beyond the shoulder
    hand(f, sk, 15, -29 + lh, 21, -23 + lh, 'handL')
    sp = f.finish()
    return sp


FRONT_LIFT = 29  # the front seated upper body is the standing one moved down onto the seat


def seated_front(P, state, phase):
    """Seated behind a desk facing us (front 3/4, looking down-left before the flip); the anchor is the seat. The legs are left out:
    the desk drawn over the frame hides them. Working: the head bobs and the hands, low on the keyboard, tap in turn; idle: asleep,
    the head sunk onto the arms folded on the desk."""
    f = Fig()
    sk, hair, top, *_ = looks(P)
    torso = [(-12, -58), (12, -58), (14, -52), (12, -26), (-12, -26), (-14, -52)]
    shoulders = [f.mask(('e', (-16, -60, -6, -48))), f.mask(('e', (6, -60, 16, -48)))]
    if state == 'idle':
        f.lift = FRONT_LIFT + 4
        hair_back_layer(f, P, 0, -74)
        torso_front(f, P, torso, shoulders)
        f.lift = FRONT_LIFT
        # the arms folded on the desk in front, the head resting on them
        f.paint(f.mask(('e', (-20, -40, 20, -28))), top, 'sleeve')
        f.hl(-16, 16, -31, top['sh'])
        hand(f, sk, -20, -38, -13, -31, 'handL', thumb=1)
        hand(f, sk, 13, -38, 20, -31, 'handR', thumb=-1)
        f.lift = FRONT_LIFT + 10
        head_front(f, P, 'happy')
        f.lift = FRONT_LIFT
        f.paint(f.mask(('e', (-18, -36, 18, -27))), ramp(top['sh']), 'sleeve2')
        return f.finish()
    bob = 1 if phase in (1, 3) else 0
    f.lift = FRONT_LIFT + bob
    hair_back_layer(f, P, 0, -74)
    lh = (0, -2, 0, 1)[phase]
    rh = (1, 0, -2, 0)[phase]
    sleeves = [f.mask(('l', [(-12, -54), (-16, -42), (-10, -33 + lh)], 7)),
               f.mask(('l', [(12, -54), (16, -42), (10, -33 + rh)], 7))]
    torso_front(f, P, torso, shoulders + sleeves)
    cuff(f, top, -9, -33 + lh)
    cuff(f, top, 9, -33 + rh)
    hand(f, sk, -10, -33 + lh, -3, -27 + lh, 'handL', thumb=1)
    hand(f, sk, 3, -33 + rh, 10, -27 + rh, 'handR', thumb=-1)
    head_front(f, P, 'open')
    return f.finish()


def frames_for(P, vi, sfx='', tf=lambda s: s, front=None):
    """Every frame of look [P] as [vi], names as in the module doc; [tf] transforms each sprite (C's night())."""
    out = {}
    for ph in range(4):
        sp = seated(P, 'working', ph)
        out[f'person{vi}_working_{ph}{sfx}'] = tf(sp)
        out[f'person{vi}_working_{ph}_lw{sfx}'] = tf(front(flip(seated_front(P, 'working', ph))) if front else flip(sp))
    idle = seated(P, 'idle', 0)
    out[f'person{vi}_idle{sfx}'] = tf(idle)
    out[f'person{vi}_idle_lw{sfx}'] = tf(front(flip(seated_front(P, 'idle', 0))) if front else flip(idle))
    for st in ('waiting', 'done'):
        for ph in range(2):
            out[f'person{vi}_{st}_{ph}{sfx}'] = tf(standing(P, st, ph))
    return out


def all_frames(sfx='', tf=lambda s: s, front=None):
    out = {}
    for vi, P in enumerate(PEOPLE_HD):
        out.update(frames_for(P, vi, sfx, tf, front))
    return out


# ---------------------------------------------------------------- lineup
def lineup(path, scale=4):
    """Every look x state (working phases, idle, waiting, done) at [scale] x DETAIL, on a warm floor."""
    import sprites as SP
    fr = all_frames()
    cols = ['working_0', 'working_1', 'working_2', 'working_3', 'working_0_lw', 'idle', 'idle_lw', 'waiting_0', 'waiting_1',
            'done_0', 'done_1']
    cw, ch = 46 * scale, 50 * scale
    img = Image.new('RGBA', (cw * len(cols) + 20, ch * len(PEOPLE_HD) + 20), (196, 134, 76, 255))
    d = ImageDraw.Draw(img)
    for yy in range(0, img.height, 8 * scale):
        d.line([(0, yy), (img.width, yy)], fill=(170, 112, 62, 255))
    for r, P in enumerate(PEOPLE_HD):
        for c, k in enumerate(cols):
            sp = fr[f'person{r}_{k}']
            big = sp.img.resize((sp.w * scale // 2, sp.h * scale // 2), Image.NEAREST) if scale % 2 == 0 else sp.img
            x = 10 + c * cw + cw // 2 - sp.ax * scale // 2
            y = 10 + r * ch + ch - 6 * scale - sp.ay * scale // 2
            img.alpha_composite(big, (x, max(0, y)))
    img.convert('RGB').save(path)


def desk_preview(path, scale=6):
    """People at a real desk and chair of theme A (native art doubled), as the layout places them."""
    import sprites as SP
    T = SP.A
    W, Hh = 260, 120
    img = Image.new('RGBA', (W * 2, Hh * 2), (196, 134, 76, 255))
    desk_rw, desk_lw = SP.desk(T, 'rw', 1), SP.desk(T, 'lw', 2, plant=True)
    seat_rw, back_rw, chair_lw = SP.chair(T, 'rw', 'seat'), SP.chair(T, 'rw', 'back'), SP.chair(T, 'lw')
    fr = all_frames()

    def put_native(sp, X, Y):
        big = sp.img.resize((sp.w * 2, sp.h * 2), Image.NEAREST)
        img.alpha_composite(big, (int(2 * (X - sp.ax)), int(2 * (Y - sp.ay))))

    def put_hd(sp, X, Y):
        img.alpha_composite(sp.img, (int(2 * X - sp.ax), int(2 * Y - sp.ay)))

    for i, (o, name) in enumerate((('rw', 'working_0'), ('rw', 'idle'), ('lw', 'working_1_lw'), ('lw', 'idle_lw'))):
        X0, Y0 = 40 + i * 60, 40
        u, v = (0, 0)
        if o == 'rw':
            put_native(desk_rw, X0, Y0)
            cu, cv = 10, 12.5
        else:
            put_native(desk_lw, X0, Y0)
            cu, cv = 12.5, 10
        cx, cy = X0 + round(2 * cu - 2 * cv), Y0 + round(cu + cv)
        put_native(seat_rw if o == 'rw' else chair_lw, cx, cy)
        put_hd(fr[f'person{(i * 3) % len(PEOPLE_HD)}_{name}'], cx, cy - 7)
        if o == 'rw':
            put_native(back_rw, cx, cy)
    for i, name in enumerate(('waiting_0', 'done_0')):
        put_hd(fr[f'person{i + 1}_{name}'], 200 + i * 28, 110)
    img = img.resize((img.width * scale // 2, img.height * scale // 2), Image.NEAREST)
    img.convert('RGB').save(path)


if __name__ == '__main__':
    import sys
    lineup(sys.argv[1] if len(sys.argv) > 1 else '/tmp/viz-chars-lineup.png')
    desk_preview('/tmp/viz-chars-desk.png')
