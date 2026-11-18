"""Office people of themes A, B and C as cute pixel art (replaces people_hd.py for A/B/C; D, E and F still use people_hd).

Drawn at the frame's own resolution (people_hd.DETAIL = 2 x the scene's density), every shape supersampled then thresholded so
edges stay clean single pixels, each part shaded from the upper left, a darker line where parts overlap and a dark outline tinted
by the color it borders. Proportions (cosy chibi: the head about 2/5 of the height, big dark eyes with a white
glint, rosy cheeks, full hair with strands and a shine, a knit top, jeans, sneakers).

Frame names and anchors as people_hd.py: person<v>_working_<0..3>[_lw], person<v>_idle[_lw], person<v>_waiting_<0|1>,
person<v>_done_<0|1>; standing frames are anchored at the feet, seated ones at the seat.
"""
import math
from PIL import Image, ImageDraw
from pix import C, mix, lt, Spr

DETAIL = 2
OUT = C('#2a1a20')            # outline
_SH = (92, 40, 70, 255)
_DP = (38, 18, 32, 255)


def wdk(c, t):
    return mix(c, _SH, t) if t < .5 else mix(mix(c, _SH, .5), _DP, (t - .5) * 2)


def ramp(c):
    c = C(c) if isinstance(c, str) else c
    return dict(hi=lt(c, .3), b=c, sh=wdk(c, .25), dp=wdk(c, .48), ln=wdk(c, .72))


SKIN = {k: ramp(v) for k, v in dict(pale='#fde3cc', light='#f7d0aa', medium='#e3ab7e', tan='#c98d5e',
                                     brown='#a26a4a', dark='#734834').items()}
for _r in SKIN.values():
    _r['blush'] = mix(_r['b'], C('#ff6f86'), .42)
EYE = C('#2b1a1e')
IRIS = C('#7a4630')
WHITE = C('#ffffff')
MOUTH = C('#8a3036')
TONGUE = C('#ec8794')

PEOPLE = [
    dict(skin='light', hair='#6b3f24', style='wavy', top='sweater', shirt='#e8a8a0', pants='#5878b0', shoe='#f2efe8'),
    dict(skin='light', hair='#4a2c1c', style='tousled', top='sweater', shirt='#7c9cd8', pants='#cdb894', shoe='#f2efe8'),
    dict(skin='pale', hair='#f0c45a', style='ponytail', top='hoodie', shirt='#3fae9c', pants='#40445e', shoe='#f2efe8',
         iris='#4a7ab0'),
    dict(skin='tan', hair='#2a2026', style='short', top='cardigan', shirt='#6a8a4a', inner='#f4eee0', pants='#3a3e52',
         shoe='#5a4436', glasses='#2a2026'),
    dict(skin='dark', hair='#1e1a1e', style='curly', top='sweater', shirt='#e0a838', pants='#4a5c80', shoe='#f2efe8'),
    dict(skin='medium', hair='#2c2024', style='bun', top='cardigan', shirt='#c85a50', inner='#f8eedc', pants='#3c3a50',
         shoe='#f2efe8', glasses='#7a3a2a'),
    dict(skin='pale', hair='#c8603a', style='bob', top='sweater', shirt='#f2e2c4', pants='#5a6ea0', shoe='#f2efe8',
         freckles=True, iris='#4a8a50'),
    dict(skin='brown', hair='#231c22', style='short', top='hoodie', shirt='#8a6ab8', pants='#2e3242', shoe='#f2efe8',
         beard=True),
    dict(skin='light', hair='#8c5a3a', style='wavy', top='hoodie', shirt='#a8c8e8', pants='#4c5a7c', shoe='#f2efe8'),
    dict(skin='medium', hair='#3a2620', style='tousled', top='cardigan', shirt='#3c5070', inner='#e8e4dc', pants='#b8a888',
         shoe='#4a3a30', glasses='#2a2026'),
]
VARIANTS = len(PEOPLE)


def looks(P):
    hair = ramp(P['hair'])
    hair['shine'] = lt(C(P['hair']), .55)
    return (SKIN[P['skin']], hair, ramp(P['shirt']), ramp(P['pants']), ramp(P['shoe']),
            ramp(P['inner']) if 'inner' in P else None)


# ---------------------------------------------------------------- the canvas
SS = 4      # supersampling of shapes
PAD = 72    # half the mask canvas, in frame pixels


class Fig:
    """Frame pixels keyed (x, y), the anchor at (0, 0), y down; [dx], [dy] move everything drawn after."""

    def __init__(s):
        s.col, s.grp, s.ord = {}, {}, {}
        s.n = 0
        s.dx = s.dy = 0

    def mask(s, *shapes):
        """Pixels covered by the shapes (e ellipse box, r box, p polygon, l polyline with width and round ends), half covered."""
        n = PAD * 2 * SS
        im = Image.new('L', (n, n), 0)
        d = ImageDraw.Draw(im)

        def P(x, y):
            return ((x + s.dx + PAD) * SS, (y + s.dy + PAD) * SS)

        for sh in shapes:
            k, a = sh[0], sh[1]
            if k in ('e', 'r'):
                x0, y0, x1, y1 = a
                b = [P(x0, y0), P(x1 + 1, y1 + 1)]
                (d.ellipse if k == 'e' else d.rectangle)(b, fill=255)
            elif k == 'p':
                d.polygon([P(x + .5, y + .5) for x, y in a], fill=255)
            elif k == 'l':
                pts, wd = [P(x + .5, y + .5) for x, y in a], sh[2] * SS
                d.line(pts, fill=255, width=int(wd), joint='curve')
                for (x, y) in pts:
                    d.ellipse([x - wd / 2, y - wd / 2, x + wd / 2, y + wd / 2], fill=255)
        im = im.resize((PAD * 2, PAD * 2), Image.BOX)
        px = im.load()
        return {(x - PAD, y - PAD) for y in range(PAD * 2) for x in range(PAD * 2) if px[x, y] >= 120}

    def paint(s, m, R, group, shade=True, hl=True, line=True):
        """Base, a two-step shadow on the lower right, a highlight on the upper left (light from the upper left)."""
        s.n += 1
        for (x, y) in m:
            c = R['b']
            if shade and ((x + 1, y) not in m or (x, y + 1) not in m):
                c = R['dp']
            elif shade and ((x + 2, y + 1) not in m or (x + 1, y + 2) not in m):
                c = R['sh']
            elif hl and ((x - 1, y - 1) not in m or (x - 2, y) not in m) and (x + 2, y + 2) in m:
                c = R['hi']
            s.col[(x, y)] = c
            s.grp[(x, y)] = group if line else None
            s.ord[(x, y)] = s.n
        return m

    def px(s, x, y, c, group=None):
        p = (x + s.dx, y + s.dy)
        s.col[p] = c
        s.grp[p] = group if group is not None else s.grp.get(p)
        s.ord.setdefault(p, s.n)

    def finish(s):
        """A darker line where a part lies over one drawn before it, then an outline tinted by what it borders, into a Spr."""
        col = dict(s.col)
        for (x, y), c in s.col.items():
            g = s.grp.get((x, y))
            if g is None:
                continue
            for q in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if q in s.col and s.grp.get(q) not in (None, g) and s.ord[q] < s.ord[(x, y)]:
                    col[(x, y)] = wdk(c, .62)
                    break
        out = dict(col)
        for (x, y), c in col.items():
            for q in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if q not in col and q not in out:
                    out[q] = mix(OUT, c, .2)
        xs = [p[0] for p in out]
        ys = [p[1] for p in out]
        x0, y0 = min(xs), min(ys)
        x0 -= x0 % 2
        y0 -= y0 % 2
        w, h = max(xs) - x0 + 1, max(ys) - y0 + 1
        sp = Spr(w + w % 2, h + h % 2, -x0, -y0)
        for (x, y), c in out.items():
            sp.put(x - x0, y - y0, c)
        return sp


def flip(sp):
    n = Spr(sp.w, sp.h, sp.w - sp.ax, sp.ay)
    n.img = sp.img.transpose(Image.FLIP_LEFT_RIGHT)
    n.em = sp.em.transpose(Image.FLIP_LEFT_RIGHT)
    n._load()
    return n


def strands(f, m, hair, cx, cy, wave=False, step=5):
    """Darker strand lines down the hair, waving in long hair."""
    for (x, y) in m:
        dx, dy = x - f.dx - cx, y - f.dy - cy
        w = int(round(math.sin(dy / 5.0) * 1.5)) if wave else 0
        if (dx + w + 100) % step == 0 and f.col.get((x, y)) in (hair['b'], hair['hi']):
            f.col[(x, y)] = hair['sh']


def shine(f, m, hair, cx, cy, rx, ry, a0=-150, a1=-115):
    """A broken arc of light on the upper left of the hair."""
    for i in range(a0, a1, 3):
        a = math.radians(i)
        if (i // 3) % 5 == 4:
            continue
        for t in (0, 1):
            x = int(round(cx + math.cos(a) * (rx + t)))
            y = int(round(cy + math.sin(a) * (ry + t)))
            if (x + f.dx, y + f.dy) in m:
                f.px(x, y, hair['shine'])


# ---------------------------------------------------------------- head, front
HX, HY = 0, -58     # face centre when standing
FRX, FRY = 16, 15   # face radii


def hair_behind(f, P, cx, cy):
    """Hair falling behind the shoulders (wavy, bob's back, the ponytail): drawn before the body."""
    sk, hair, *_ = looks(P)
    st = P['style']
    if st == 'wavy':
        m = f.mask(('e', (cx - 20, cy - 20, cx + 20, cy + 14)), ('r', (cx - 20, cy - 4, cx + 20, cy + 26)))
        m = {p for p in m if p[1] - f.dy < cy + 27 - (0 if (p[0] - f.dx - cx) % 8 < 4 else 2)}
        f.paint(m, hair, 'hairback')
        strands(f, m, hair, cx, cy, wave=True)
    elif st == 'bob':
        f.paint(f.mask(('e', (cx - 19, cy - 19, cx + 19, cy + 12)), ('r', (cx - 19, cy - 2, cx + 19, cy + 10))), hair, 'hairback')
    elif st == 'ponytail':
        m = f.mask(('l', [(cx + 14, cy - 14), (cx + 22, cy - 4), (cx + 21, cy + 10), (cx + 17, cy + 18)], 8))
        f.paint(m, hair, 'hairback')
        strands(f, m, hair, cx + 18, cy, wave=True, step=4)


EYE_MAP = ['.KKK.', 'KKKKK', 'KWWIK', 'KWIIK', 'KIIIK', 'KIILK', 'KILLK', '.KKK.']


def face(f, P, cx, cy, expr='open'):
    """The face ([expr]: open, calling, happy, sleep, down), ears and features; the hair goes over it after."""
    sk, hair, *_ = looks(P)
    m = f.mask(('e', (cx - FRX, cy - FRY, cx + FRX, cy + FRY)), ('e', (cx - FRX + 2, cy - 2, cx + FRX - 2, cy + FRY + 1)))
    f.paint(m, sk, 'face', hl=False)
    for sx in (-1, 1):
        ex0 = cx + sx * (FRX + 1)
        f.paint(f.mask(('e', (ex0 - 2, cy - 1, ex0 + 2, cy + 5))), sk, 'ear', hl=False)
        f.px(ex0, cy + 2, sk['sh'])
    ey = cy + 1
    ex = (cx - 10, cx + 5)
    iris = C(P['iris']) if 'iris' in P else IRIS
    if expr in ('happy', 'sleep', 'down'):
        arc = [(0, 2), (1, 1), (2, 0), (3, 1), (4, 2)] if expr == 'happy' else [(0, 1), (1, 2), (2, 2), (3, 2), (4, 1)]
        for x0 in ex:
            for ax, ay in arc:
                f.px(x0 + ax, ey + 1 + ay, EYE)
            if expr != 'happy':
                f.px(x0 - 1 if x0 < cx else x0 + 5, ey + 1, EYE)  # lashes
    else:
        cols = dict(K=EYE, I=iris, W=WHITE, L=lt(iris, .55))
        for x0 in ex:
            for r, row in enumerate(EYE_MAP):
                for c, ch in enumerate(row):
                    if ch != '.':
                        f.px(x0 + c, ey - 3 + r, cols[ch])
            f.px(x0 - 1 if x0 < cx else x0 + 5, ey - 2, EYE)  # an upper lash out at the side
    for i, x0 in enumerate(ex):
        lift = -1 if expr == 'calling' else 0
        for c in range(5):
            f.px(x0 + c, ey - 6 + lift + (1 if (c == 0 and i == 0) or (c == 4 and i == 1) else 0), hair['dp'])
    if not P.get('beard'):
        for x in (cx - 13, cx + 9):
            for c in range(4):
                f.px(x + c, ey + 6, sk['blush'])
    if P.get('freckles'):
        for x, y in ((cx - 12, ey + 4), (cx - 10, ey + 6), (cx + 10, ey + 6), (cx + 12, ey + 4)):
            f.px(x, y, sk['dp'])
    f.px(cx - 1, ey + 6, sk['sh'])  # nose
    my = ey + 9
    if expr == 'calling':
        for x in range(cx - 3, cx + 2):
            f.px(x, my - 1, MOUTH)
        for x in range(cx - 3, cx + 2):
            f.px(x, my, TONGUE if cx - 2 <= x <= cx else MOUTH)
        f.px(cx - 2, my + 1, MOUTH); f.px(cx - 1, my + 1, MOUTH); f.px(cx, my + 1, MOUTH)
    elif expr in ('sleep', 'down'):
        f.px(cx - 2, my, MOUTH); f.px(cx - 1, my, MOUTH)
    elif expr == 'happy':
        for x in range(cx - 4, cx + 3):
            f.px(x, my - 1, MOUTH)
        f.px(cx - 3, my, MOUTH); f.px(cx - 2, my, TONGUE); f.px(cx - 1, my, TONGUE); f.px(cx, my, TONGUE); f.px(cx + 1, my, MOUTH)
        f.px(cx - 2, my + 1, MOUTH); f.px(cx - 1, my + 1, MOUTH); f.px(cx, my + 1, MOUTH)
    else:
        f.px(cx - 3, my - 1, MOUTH); f.px(cx - 2, my, MOUTH); f.px(cx - 1, my, MOUTH); f.px(cx, my, MOUTH); f.px(cx + 1, my - 1, MOUTH)
    if P.get('beard'):
        for (x, y) in f.mask(('e', (cx - 15, cy + 1, cx + 15, cy + FRY + 3))):
            dx, dy = x - f.dx - cx, y - f.dy - cy
            if (x, y) in m and dy >= 6 and not (-4 <= dx <= 2 and my - 2 - cy <= dy <= my + 2 - cy):
                f.col[(x, y)] = hair['b'] if (x * 3 + y) % 4 else hair['sh']
    if P.get('glasses'):
        g = C(P['glasses'])
        for x0 in ex:
            for x in range(x0, x0 + 5):
                f.px(x, ey - 5, g); f.px(x, ey + 6, g)
            for y in range(ey - 3, ey + 5):
                f.px(x0 - 2, y, g); f.px(x0 + 6, y, g)
            f.px(x0 - 1, ey - 4, g); f.px(x0 + 5, ey - 4, g); f.px(x0 - 1, ey + 5, g); f.px(x0 + 5, ey + 5, g)
        for x in range(cx - 4, cx + 5):
            f.px(x, ey - 1, g)


SIDE = {'wavy': 20, 'bob': 10, 'ponytail': 2, 'bun': 2, 'curly': 0, 'short': -4, 'tousled': -2}


def hair_front(f, P, cx, cy):
    """The hair over the head: a cap down to a pointed fringe that shows the brows, side locks by style, strands, a shine."""
    sk, hair, *_ = looks(P)
    st = P['style']
    cap = f.mask(('e', (cx - FRX - 3, cy - FRY - 5, cx + FRX + 3, cy + FRY - 3)))
    m = set()
    for (x, y) in cap:
        dx, dy = x - f.dx - cx, y - f.dy - cy
        k = (dx + 40) % 7
        edge = -8 + (3 - abs(k - 3))  # locks ending in points
        if abs(dx) >= FRX - 2:
            edge = SIDE[st]
        elif abs(dx) >= FRX - 5:
            edge = max(edge, -3)
        if dy <= edge:
            m.add((x, y))
    if st in ('wavy', 'bob'):
        lo = SIDE[st]
        for sx in (-1, 1):
            m |= f.mask(('l', [(cx + sx * (FRX - 1), cy - 8), (cx + sx * (FRX + 2), cy + 2), (cx + sx * (FRX + 1), cy + lo)], 6))
    if st == 'curly':
        for ox, oy in ((-15, -9), (-9, -17), (0, -19), (9, -17), (15, -9), (-17, 0), (17, 0), (-6, -11), (6, -11), (-18, 7),
                       (18, 7)):
            m |= f.mask(('e', (cx + ox - 5, cy + oy - 5, cx + ox + 5, cy + oy + 5)))
        m = {p for p in m if p[1] - f.dy - cy <= -7 or abs(p[0] - f.dx - cx) >= FRX - 1}
    if st == 'tousled':
        for ox, h in ((-12, 5), (-6, 7), (1, 7), (8, 6), (14, 4)):
            b = cy - FRY - 2
            m |= f.mask(('p', [(cx + ox - 4, b + 2), (cx + ox + (1 if ox < 0 else 3), b - h), (cx + ox + 4, b + 2)]))
    if st == 'short':
        m = {p for p in m if not (abs(p[0] - f.dx - cx) >= FRX - 1 and p[1] - f.dy - cy > -2)}
    f.paint(m, hair, 'hair')
    strands(f, m, hair, cx, cy, wave=st in ('wavy', 'curly'))
    for (x, y) in m:  # the fringe's shadow on the forehead
        q = (x, y + 1)
        if q not in m and f.grp.get(q) == 'face':
            f.col[q] = sk['sh']
    shine(f, m, hair, cx - 2, cy - 6, FRX - 4, FRY - 3)
    if st == 'bun':
        bm = f.paint(f.mask(('e', (cx - 8, cy - FRY - 15, cx + 8, cy - FRY + 1))), hair, 'bun')
        strands(f, bm, hair, cx, cy, step=4)
        f.px(cx - 4, cy - FRY - 11, hair['shine']); f.px(cx - 3, cy - FRY - 12, hair['shine'])
    if st == 'ponytail':
        f.paint(f.mask(('e', (cx + 11, cy - FRY - 3, cx + 17, cy - FRY + 3))), ramp('#e85a7a'), 'tie')


# ---------------------------------------------------------------- body, front
def knit(f, P, m, R):
    """Ribs on a sweater."""
    if P['top'] == 'sweater':
        for (x, y) in m:
            if (x - f.dx) % 3 == 0 and f.col.get((x, y)) == R['b']:
                f.col[(x, y)] = mix(R['b'], R['sh'], .5)


def torso_front(f, P, arms, top_y=-42, hem_y=-20):
    """The upper body from the front: neck, torso, [arms] (sleeve masks drawn over it), collar and hem."""
    sk, hair, top, pants, shoe, inner = looks(P)
    f.paint(f.mask(('r', (-3, top_y - 3, 2, top_y + 1))), sk, 'neck', hl=False)
    body = f.mask(('p', [(-9, top_y), (9, top_y), (13, top_y + 4), (13, hem_y), (-13, hem_y), (-13, top_y + 4)]),
                  ('e', (-14, top_y, -6, top_y + 8)), ('e', (6, top_y, 14, top_y + 8)))
    f.paint(body, top, 'top')
    knit(f, P, body, top)
    kind = P['top']
    if kind == 'cardigan':
        f.paint({p for p in body if -3 <= p[0] - f.dx <= 2}, inner, 'inner', shade=False, hl=False)
        for y in range(top_y + 6, hem_y, 5):
            f.px(-5, y, top['ln'])
    if kind == 'hoodie':
        f.paint(f.mask(('e', (-8, top_y - 3, 8, top_y + 4))), top, 'hood')
        for sx in (-3, 2):
            for y in range(top_y + 3, top_y + 10):
                f.px(sx, y, WHITE)
        pk = f.mask(('r', (-7, hem_y - 8, 6, hem_y - 2)))
        for (x, y) in pk:
            if (x - 1, y) not in pk or (x + 1, y) not in pk or (x, y - 1) not in pk:
                f.col[(x, y)] = top['sh']
    for x in range(-13, 14):
        f.px(x, hem_y, top['dp'] if x % 2 else top['sh'])
        f.px(x, hem_y - 1, top['sh'] if x % 2 else top['b'])
    if kind != 'hoodie':
        for x in range(-4, 4):
            f.px(x, top_y, top['dp'] if x % 2 else top['sh'])
    for m in arms:
        f.paint(m, top, 'sleeve')
        knit(f, P, m, top)


def hand(f, P, x, y, r=3, group='hand'):
    sk, *_ = looks(P)
    f.paint(f.mask(('e', (x - r, y - r, x + r - 1, y + r - 1))), sk, group, hl=False)


def legs_front(f, P):
    sk, hair, top, pants, shoe, inner = looks(P)
    f.paint(f.mask(('p', [(-12, -22), (12, -22), (11, -6), (1, -6), (0, -14), (-1, -6), (-11, -6)])), pants, 'pants')
    for y in range(-14, -6):
        f.px(0, y, pants['ln'])
    f.px(-6, -20, pants['hi']); f.px(5, -20, pants['hi'])
    for sx in (-1, 1):
        x0 = -12 if sx < 0 else 1
        f.paint(f.mask(('e', (x0, -8, x0 + 11, 0)), ('r', (x0, -4, x0 + 11, 0))), shoe, 'shoe', hl=False)
        for x in range(x0 + 1, x0 + 11):
            f.px(x, -1, wdk(shoe['b'], .35))
        f.px(x0 + 3, -6, lt(shoe['b'], .5)); f.px(x0 + 4, -6, lt(shoe['b'], .5)); f.px(x0 + 5, -7, lt(shoe['b'], .5))


def standing(P, state, phase):
    f = Fig()
    sk, hair, top, *_ = looks(P)
    hair_behind(f, P, HX, HY)
    legs_front(f, P)
    w = phase % 2
    if state == 'waiting':
        armL = f.mask(('l', [(-12, -38), (-15, -30), (-15, -26)], 7))
        armR = f.mask(('l', [(11, -38), (17, -46), (20 + w, -58)], 7))
        torso_front(f, P, [armL, armR])
        hand(f, P, -15, -23)
        face(f, P, HX, HY, 'calling' if w == 0 else 'open')
        hair_front(f, P, HX, HY)
        # the raised open hand over the hair: a cuff, the palm, four fingers and a thumb
        hx, hy = 21 + w, -64
        f.n += 1
        for x in range(hx - 3, hx + 4):
            f.px(x, hy + 3, top['dp'], 'cuff')
        f.paint(f.mask(('e', (hx - 4, hy - 4, hx + 3, hy + 3))), sk, 'palm', hl=False)
        for i, fx in enumerate((-3, -1, 1, 3)):
            for fy in range(3 + (1 if i in (1, 2) else 0)):
                f.px(hx + fx - (1 if fx > 0 else 0), hy - 4 - fy, sk['b'], 'palm')
        f.px(hx + 4, hy - 1, sk['b'], 'palm'); f.px(hx + 5, hy - 2, sk['b'], 'palm')
        for fx in (-2, 0):
            f.px(hx + fx, hy - 5, sk['sh'])
    else:  # done: a mug held in both hands at the chest, eyes closed in a smile
        armL = f.mask(('l', [(-12, -38), (-14, -30), (-6, -27)], 7))
        armR = f.mask(('l', [(11, -38), (13, -30), (6, -27)], 7))
        torso_front(f, P, [armL, armR])
        mug = ramp('#f8f4ec')
        f.paint(f.mask(('r', (-4, -36, 4, -25))), mug, 'mug')
        f.paint(f.mask(('l', [(5, -34), (8, -32), (5, -28)], 2)), mug, 'mugh', shade=False, hl=False)
        for x in range(-3, 4):
            f.px(x, -36, C('#6b3a22'))
            f.px(x, -31, C('#e86a6a')); f.px(x, -30, C('#e86a6a'))
        hand(f, P, -6, -28); hand(f, P, 6, -28)
        face(f, P, HX, HY, 'happy')
        hair_front(f, P, HX, HY)
        steam = C('#fffaf0', 200)
        for i in range(6):
            f.px(-1 + ((i // 2 + phase) % 2) * 2, -39 - i, steam)
    return f.finish()


# ---------------------------------------------------------------- seated, from behind (facing up-right)
BX, BY = 4, -44     # the back of the head at the desk
BRX, BRY = 15, 15


def head_back(f, P, cx, cy):
    """The back of the head turned a little right: the right ear, a sliver of cheek and the nape, the rest hair."""
    sk, hair, *_ = looks(P)
    st = P['style']
    rx, ry = BRX, BRY
    f.paint(f.mask(('e', (cx - rx + 2, cy - ry, cx + rx, cy + ry))), sk, 'face', hl=False)
    f.paint(f.mask(('r', (cx - 3, cy + ry - 4, cx + 5, cy + ry + 3))), sk, 'nape', hl=False)
    ex, ey = cx + rx - 1, cy + 1
    f.paint(f.mask(('e', (ex - 2, ey - 3, ex + 2, ey + 4))), sk, 'ear', hl=False)
    f.px(ex, ey, sk['dp']); f.px(ex, ey + 1, sk['sh'])
    low = {'wavy': 22, 'bob': 10, 'ponytail': 4, 'bun': 4, 'curly': 6, 'short': 4, 'tousled': 6}[st]
    m = f.mask(('e', (cx - rx - 2, cy - ry - 4, cx + rx + 1, cy + ry - 2)),
               ('p', [(cx - rx - 2, cy), (cx + rx - 4, cy), (cx + rx - 6, cy + low), (cx - rx, cy + low)]))
    m = {p for p in m if not ((p[0] - f.dx - cx) > rx - 6 and -3 <= (p[1] - f.dy - cy) <= ry)}  # the ear and cheek stay clear
    if st == 'curly':
        for ox, oy in ((-15, -9), (-8, -17), (2, -18), (11, -12), (-17, 1), (-14, 10), (0, -6), (8, -2)):
            m |= f.mask(('e', (cx + ox - 5, cy + oy - 5, cx + ox + 5, cy + oy + 5)))
    if st == 'tousled':
        for ox, h in ((-10, 5), (-3, 7), (5, 6), (11, 4)):
            b = cy - ry - 3
            m |= f.mask(('p', [(cx + ox - 4, b + 3), (cx + ox + 2, b - h), (cx + ox + 4, b + 3)]))
    if st == 'wavy':
        m = {p for p in m if p[1] - f.dy < cy + low + 1 - (0 if (p[0] - f.dx - cx) % 8 < 4 else 2)}
    f.paint(m, hair, 'hair')
    strands(f, m, hair, cx, cy, wave=st in ('wavy', 'curly'))
    shine(f, m, hair, cx - 1, cy - 4, rx - 4, ry - 4)
    if P.get('glasses'):
        g = C(P['glasses'])
        for x in range(ex - 4, ex):
            f.px(x, ey - 2, g)
    if st == 'bun':
        bm = f.paint(f.mask(('e', (cx - 8, cy - ry - 12, cx + 8, cy - ry + 3))), hair, 'bun')
        strands(f, bm, hair, cx, cy, step=4)
        f.px(cx - 4, cy - ry - 8, hair['shine']); f.px(cx - 3, cy - ry - 9, hair['shine'])
    if st == 'ponytail':
        f.paint(f.mask(('e', (cx - 4, cy + 2, cx + 3, cy + 7))), ramp('#e85a7a'), 'tie')
        tm = f.paint(f.mask(('l', [(cx, cy + 6), (cx - 2, cy + 14), (cx, cy + 22)], 7)), hair, 'tail')
        strands(f, tm, hair, cx, cy, wave=True, step=4)


def seated(P, state, phase):
    """At a desk seen from behind, facing up-right; the anchor is the seat."""
    f = Fig()
    sk, hair, top, pants, shoe, inner = looks(P)
    if state == 'idle':
        # bent forward, the head down on the arms folded on the desk
        f.paint(f.mask(('e', (-10, -8, 16, 0))), pants, 'pants')
        body = f.paint(f.mask(('p', [(-10, -4), (16, -4), (26, -22), (22, -30), (4, -30), (-10, -18)])), top, 'top')
        knit(f, P, body, top)
        sl = f.paint(f.mask(('e', (12, -36, 38, -22))), top, 'sleeve')
        knit(f, P, sl, top)
        hand(f, P, 35, -26)
        head_back(f, P, 18, -36)
        return f.finish()
    bob = 1 if phase in (1, 3) else 0
    f.paint(f.mask(('e', (-10, -8, 18, 0))), pants, 'pants')
    f.paint(f.mask(('p', [(8, -8), (18, -10), (27, -14), (27, -8), (20, -2), (10, -2)])), pants, 'thigh')
    rh = (0, -2, 0, -1)[phase]
    torso = f.paint(f.mask(('p', [(-10, -4), (18, -4), (20, -22), (16, -29), (-4, -29), (-12, -22)]),
                           ('e', (-13, -30, 1, -16)), ('e', (8, -30, 22, -16))), top, 'top')
    knit(f, P, torso, top)
    for x in range(-9, 19):
        f.px(x, -5, top['dp'] if x % 2 else top['sh'])
    for y in range(-24, -8, 2):  # the fold along the spine
        f.px(3, y, top['sh'])
    if P['top'] == 'hoodie':
        hm = f.paint(f.mask(('e', (-4, -36, 16, -24))), top, 'hood')
        for (x, y) in hm:
            if (x, y + 2) not in hm and (x, y + 1) in hm:
                f.col[(x, y)] = top['dp']
    f.dy = bob
    head_back(f, P, BX, BY)
    f.dy = 0
    # the right arm forward to the keyboard
    f.paint(f.mask(('l', [(16, -26), (23, -15), (30, -19 + rh)], 7)), top, 'sleeveR')
    hand(f, P, 33, -21 + rh, group='handR')
    return f.finish()


FRONT_DY = 12  # the front seated upper body is the standing one moved down onto the seat


def seated_front(P, state, phase):
    """Behind a desk facing us (before the flip, looking down-left); the anchor is the seat; the desk hides the legs."""
    f = Fig()
    sk, hair, top, *_ = looks(P)
    if state == 'idle':
        f.dy = FRONT_DY + 4
        hair_behind(f, P, HX, HY)
        torso_front(f, P, [], hem_y=-12)
        f.dy = FRONT_DY + 10
        face(f, P, HX, HY, 'sleep')
        hair_front(f, P, HX, HY)
        f.dy = FRONT_DY
        arms = f.paint(f.mask(('e', (-20, -32, 20, -20))), top, 'sleeve')
        knit(f, P, arms, top)
        for x in range(-12, 13):
            f.px(x, -26, top['sh'])
        return f.finish()
    bob = 1 if phase in (1, 3) else 0
    f.dy = FRONT_DY + bob
    hair_behind(f, P, HX, HY)
    lh = (0, -2, 0, 1)[phase]
    rh = (1, 0, -2, 0)[phase]
    arms = [f.mask(('l', [(-12, -38), (-15, -28), (-9, -21 + lh)], 7)), f.mask(('l', [(11, -38), (14, -28), (8, -21 + rh)], 7))]
    torso_front(f, P, arms, hem_y=-12)
    hand(f, P, -8, -19 + lh, group='handL')
    hand(f, P, 7, -19 + rh, group='handR')
    face(f, P, HX, HY, 'down' if phase in (1, 2) else 'open')
    hair_front(f, P, HX, HY)
    return f.finish()


def frames_for(P, vi, sfx='', tf=lambda s: s, front=None):
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
    for vi, P in enumerate(PEOPLE):
        out.update(frames_for(P, vi, sfx, tf, front))
    return out


def lineup(path, scale=4):
    """Every look x pose at [scale] / 2 x the frame size, on a warm floor."""
    cols = [('working', 0, False), ('working', 1, False), ('working', 0, True), ('idle', 0, False), ('idle', 0, True),
            ('waiting', 0, None), ('waiting', 1, None), ('done', 0, None)]
    cw, ch = 96 * scale // 2, 110 * scale // 2
    img = Image.new('RGBA', (cw * len(cols) + 20, ch * len(PEOPLE) + 20), (226, 196, 160, 255))
    for r, P in enumerate(PEOPLE):
        for c, (st, ph, fr) in enumerate(cols):
            if fr is None:
                sp = standing(P, st, ph)
            elif fr:
                sp = flip(seated_front(P, st, ph))
            else:
                sp = seated(P, st, ph)
            big = sp.img.resize((sp.w * scale // 2, sp.h * scale // 2), Image.NEAREST)
            x = 10 + c * cw + cw // 2 - sp.ax * scale // 2
            y = 10 + r * ch + ch - 6 * scale - sp.ay * scale // 2
            img.alpha_composite(big, (x, max(0, y)))
    img.convert('RGB').save(path)


if __name__ == '__main__':
    import sys
    lineup(sys.argv[1] if len(sys.argv) > 1 else '/tmp/viz-chibi-lineup.png')
