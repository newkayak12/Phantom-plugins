"""Compose the 3 isometric office mockups from the sprites (native 480x300 -> x3 = 1440x900)."""
import math
from PIL import Image, ImageDraw, ImageFont
import sprites as SP
from pix import C, dk, lt, Spr, H, obox

OUT = SP.OUT
NW, NH, S = 480, 300, 3
OX, OY = 275, 86
N = 96  # room size in iso units (12x12 cells, 6x6 floor tiles)


def proj(u, v, z=0):
    return OX + 2 * u - 2 * v, OY + u + v - z


def font(sz, w=6):
    return ImageFont.truetype(SP.FONT, sz, index=w)


# session list: (area, desk index, state, people variant, title)
SESSIONS = [
    ('main', 0, 'working', 0, 'claude · rate limit 리팩터'),
    ('main', 1, 'waiting', 2, 'codex · 결제 API 확인?'),
    ('pay', 0, 'working', 1, 'claude · webhook 서명'),
    ('pay', 1, 'idle', 2, 'codex · 테스트 대기'),
    ('hot', 0, 'done', 1, 'claude · 로그인 수정 완료'),
    ('hot', 1, 'working', 0, 'codex · 세션 토큰'),
]

DESKS = {  # area -> list of (orientation, U, V)
    'main': [('rw', 14, 8), ('rw', 34, 8)],
    'pay': [('lw', 64, 6), ('lw', 64, 29)],
    'hot': [('rw', 24, 62), ('rw', 48, 62)],
}
AREAS = [  # label, plate pos (u,v), rug (u,v,A,D,palette)
    ('main', (32, 41), (9, 4, 46, 34, 0)),
    ('feature-pay #15', (87, 57), (61, 3, 32, 50, 1)),
    ('hotfix-login', (50, 93), (19, 58, 56, 32, 2)),
]


def sun_patch(o, s0):
    """Dithered light patch cast on the floor by a window starting at along=s0."""
    sp = Spr(NW, NH, OX, OY)

    def tex(role, a, d, z, A_, D_, Hh, sf):
        lo = s0 + 3 + d * 0.7
        if not (lo <= a < lo + 10) or d < 0.5:
            return None
        return C('#fff2c8', 70) if d > 1.5 else C('#fff2c8', 40)
    obox(sp, o, s0, 0, 0.01, 36, 22, 0, tex, 'T')
    return sp


def build_scene(T):
    spr = SP.build_all(T)
    items = []  # (layer, key, sprite, X, Y)
    lights = []
    people = []

    def add(layer, key, sp, u, v, z=0):
        X, Y = proj(u, v, z)
        items.append((layer, key, sp, X, Y))

    # floor slab + tiles
    add(0, 0, SP.floor_base(T, N), 0, 0)
    for i in range(N // 16):
        for j in range(N // 16):
            var = int(H('tile', i, j) * 3) if T['floor'] == 'wood' else (i + j) % 2
            add(0, 1 + i + j, spr[f'floor_{T["floor"]}_{var}'], i * 16, j * 16)
    # walls
    add(1, 0, spr['wall_corner'], 0, 0)
    for i in range(6):
        win = i in (1, 3, 5)
        add(1, 1 + i, SP.wall_seg(T, 'rw', 10 + i, window=win, end=(i == 5)), i * 16, 0)
    for j in range(6):
        win = j in (2, 4)
        add(1, 1 + j, SP.wall_seg(T, 'lw', 20 + j, window=win, end=(j == 5)), 0, j * 16)
    # wall decor
    add(2, 0, spr['whiteboard_lw'], 0, 16)
    add(2, 1, spr['whiteboard_rw'], 66, 0)
    add(2, 2, spr['clock_rw'], 39, 0, 40)
    add(2, 3, spr['poster_rw'], 7, 0, 24)
    add(2, 4, SP.poster(T, 'rw', 1), 86, 0, 24) if False else None
    # rugs
    for k, (_, _, (ru, rv, ra, rd, pi)) in enumerate(AREAS):
        add(3, k, SP.rug(T, pi, ra, rd), ru, rv)

    if not T['night']:
        for i in (1, 3, 5):
            add(4, i, sun_patch('rw', i * 16), 0, 0)
        for j in (2, 4):
            add(4, j, sun_patch('lw', j * 16), 0, 0)

    # furniture (depth sorted by footprint centre u+v)
    def obj(sp, u, v, cu, cv, z=0):
        add(5, cu + cv, sp, u, v, z)

    obj(spr['bookshelf_lw'], 0, 1, 3, 8)
    obj(SP.bookshelf(T, 'lw', 7), 0, 80, 3, 87)
    obj(spr['coffee_lw'], 0, 48, 4, 56)
    obj(spr['plant_big'], 90, 6, 90, 6)
    obj(SP.plant_big(T, 1), 7, 89, 7, 89)
    obj(SP.plant_big(T, 2), 5, 68, 5, 68)
    obj(spr['lamp'], 91, 90, 91, 90)
    lights.append((proj(91, 90, 31), 85, (1.0, 0.72, 0.42), 1.0))
    lights.append((proj(6, 54, 24), 34, (1.0, 0.45, 0.4), 0.5))

    if T['key'] == 'B':
        g_lw, g_rw = SP.glass(T, 'lw'), SP.glass(T, 'rw')
        for k in range(3):
            obj(g_lw, 57, k * 16, 57.5, k * 16 + 8)
        for k in range(5):
            obj(g_rw, 16 + k * 16, 54, 24 + k * 16, 54.5)

    # desks, chairs, people
    for si, (area, di, state, pv, title) in enumerate(SESSIONS):
        o, U, V = DESKS[area][di]
        dsp = SP.desk(T, o, seed=si * 3 + 1, plant=(si % 3 == 1))
        if o == 'rw':
            obj(dsp, U, V, U + 10, V + 5)
            cu, cv = U + 10, V + 12.5
            mon = proj(U + 10, V + 2, 24)
        else:
            obj(dsp, U, V, U + 5, V + 10)
            cu, cv = U + 12.5, V + 10
            mon = proj(U + 2, V + 10, 24)
        lights.append((mon, 72, (0.55, 0.8, 1.15), 1.3))
        base = cu + cv
        seat = SP.chair(T, o, 'seat')
        back = SP.chair(T, o, 'back')
        psp = SP.person(SP.PEOPLE[pv], state)
        add(5, base, seat, cu, cv)
        add(5, base + 0.2, back, cu, cv)
        if state in ('working', 'idle'):
            X, Y = proj(cu, cv, 7)
            items.append((5, base + 0.1, psp, X, Y))
            head = Y - psp.ay
        else:
            if o == 'rw':
                pu, pv_ = (U + 15, V + 19) if di == 1 else (U - 3, V + 16)
            else:
                pu, pv_ = U + 16, V + 22
            X, Y = proj(pu, pv_)
            items.append((5, pu + pv_, psp, X, Y))
            head = Y - psp.ay
        people.append(dict(state=state, title=title, x=X, y=Y, head=head))

    # name plates (sprite sized to the label)
    plates = []
    fp = font(14, 6)
    for label, (pu, pv_), _ in AREAS:
        w = int(math.ceil(fp.getlength(label) / S)) + 6
        sp = SP.nameplate(T, w)
        if T['night']:
            for yy in range(1, 11):
                for xx in range(sp.w):
                    if sp.p[xx, yy][3]:
                        sp.e[xx, yy] = 255
            lights.append((proj(pu, pv_, 8), 30, (1.0, 0.85, 0.6), 0.6))
        X, Y = proj(pu, pv_)
        items.append((5, pu + pv_, sp, X, Y))
        plates.append((label, X - sp.ax, Y - sp.ay, sp.w, sp))

    canvas = Spr(NW, NH)
    for layer, key, sp, X, Y in sorted(items, key=lambda it: (it[0], it[1])):
        canvas.place(sp, X, Y)
    return canvas, lights, people, plates


BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def night_pass(canvas, lights, ambient=(0.30, 0.32, 0.50)):
    src = canvas.img.load()
    em = canvas.em.load()
    out = Image.new('RGBA', canvas.img.size)
    op = out.load()
    for y in range(NH):
        for x in range(NW):
            r, g, b, a = src[x, y]
            if a == 0:
                op[x, y] = (0, 0, 0, 0)
                continue
            if em[x, y]:
                op[x, y] = (r, g, b, a)
                continue
            lr, lg, lb = ambient
            th = (BAYER[y % 4][x % 4] + 0.5) / 16.0
            for (lx, ly), rad, col, st in lights:
                dx, dy = x - lx, (y - ly) * 1.8
                d = math.sqrt(dx * dx + dy * dy) / rad
                if d >= 1:
                    continue
                f = (1 - d) ** 1.6 * st * 4
                q = int(f) + (1 if (f - int(f)) > th else 0)  # ordered-dither quantization
                q = q / 4.0
                lr += col[0] * q * 0.55
                lg += col[1] * q * 0.55
                lb += col[2] * q * 0.55
            op[x, y] = (min(255, int(r * lr)), min(255, int(g * lg)), min(255, int(b * lb)), a)
    canvas.img = out
    canvas._load()


UI = {
    'A': dict(bg=(43, 34, 31), bg2=(52, 42, 38), head=(30, 24, 22), side=(36, 29, 27), text=(244, 234, 216),
              dim=(176, 158, 140), acc=(240, 164, 58), acc_t=(40, 26, 12), line=(74, 60, 54), pill=(28, 22, 20, 225),
              pill_t=(246, 238, 224), plate_t=(92, 54, 30)),
    'B': dict(bg=(233, 238, 242), bg2=(222, 229, 234), head=(255, 255, 255), side=(250, 251, 252), text=(22, 33, 50),
              dim=(100, 114, 135), acc=(20, 184, 166), acc_t=(255, 255, 255), line=(222, 228, 236),
              pill=(255, 255, 255, 240), pill_t=(22, 33, 50), plate_t=(14, 116, 108)),
    'C': dict(bg=(12, 15, 30), bg2=(18, 22, 42), head=(10, 12, 24), side=(15, 18, 34), text=(226, 232, 255),
              dim=(136, 146, 186), acc=(124, 140, 255), acc_t=(255, 255, 255), line=(36, 44, 76),
              pill=(14, 16, 32, 230), pill_t=(230, 236, 255), plate_t=(92, 54, 30)),
}
NAMES = {
    'A': ('Warm Wood', '원목 바닥 · 벽돌 벽 · 아늑한 사무실'),
    'B': ('Modern Bright', '밝은 타일 · 흰 벽 · 유리 파티션 · 틸 포인트'),
    'C': ('Night Shift', '같은 레이아웃 · 밤 · 모니터가 조명'),
}
STATE_LABEL = [('working', '작업 중', 'working'), ('waiting', '응답 필요', 'waiting for you'),
               ('done', '완료', 'done'), ('idle', '대기 · 잠듦', 'idle')]


def background(T, ui):
    bg = Spr(NW, NH)
    for y in range(NH):
        for x in range(NW):
            c = ui['bg']
            if T['key'] == 'C' and H('sky', x, y) < 0.004:
                c = (90, 100, 150)
            elif (x % 8 == 0 and y % 8 == 0):
                c = ui['bg2']
            bg.p[x, y] = c + (255,)
    return bg


def render(key):
    T = SP.THEMES[key]
    ui = UI[key]
    canvas, lights, people, plates = build_scene(T)
    if T['night']:
        night_pass(canvas, lights)
    for p in people:  # speech bubbles: UI layer, never darkened by the night pass
        b = SP.bubble(p['state'])
        canvas.place(b, p['x'] + 3, p['head'] - 1)
    bg = background(T, ui)
    bg.img.alpha_composite(canvas.img)
    img = bg.img.resize((NW * S, NH * S), Image.NEAREST)

    ov = Image.new('RGBA', img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    # ---- name plates text (crisp, final resolution)
    fp = font(14, 6)
    for label, px, py, pw, sp in plates:
        cx = (px + pw / 2) * S
        cy = (py + 1 + 5.5) * S
        tw = fp.getlength(label)
        d.text((cx - tw / 2, cy - 9), label, font=fp, fill=ui['plate_t'])
    # ---- session pills
    fs = font(12, 4)
    for p in people:
        t = p['title']
        tw = fs.getlength(t)
        cx, cy = p['x'] * S, p['y'] * S + 10
        if p['state'] in ('working', 'idle'):
            cy += 2
        x0, x1 = cx - tw / 2 - 16, cx + tw / 2 + 8
        d.rounded_rectangle([x0, cy, x1, cy + 20], 10, fill=ui['pill'], outline=SP.STATE_COL[p['state']][:3] + (255,), width=1)
        d.ellipse([x0 + 6, cy + 6, x0 + 14, cy + 14], fill=SP.STATE_COL[p['state']][:3])
        d.text((x0 + 17, cy + 2), t, font=fs, fill=ui['pill_t'])
    # ---- header
    d.rectangle([0, 0, NW * S, 40], fill=ui['head'] + (255,))
    d.line([0, 40, NW * S, 40], fill=ui['line'])
    d.text((18, 9), 'Phantom AI Visualization', font=font(17, 6), fill=ui['text'])
    d.text((250, 12), '1F colo-backend  ·  세션 6개  ·  worktree 3개', font=font(14, 2), fill=ui['dim'])
    sx = NW * S - 170
    d.rounded_rectangle([sx, 8, sx + 152, 32], 7, fill=ui['side'] + (255,), outline=ui['line'])
    d.rounded_rectangle([sx + 76, 10, sx + 150, 30], 6, fill=ui['acc'] + (255,))
    f13 = font(13, 4)
    d.text((sx + 22, 12), '목록', font=f13, fill=ui['dim'])
    d.text((sx + 98, 12), '오피스', font=f13, fill=ui['acc_t'])
    # ---- sidebar floor picker
    d.rectangle([0, 41, 200, NH * S], fill=ui['side'] + (246,))
    d.line([200, 41, 200, NH * S], fill=ui['line'])
    d.text((18, 56), 'FLOORS · 프로젝트', font=font(11, 6), fill=ui['dim'])
    floors = [('3F', 'Phantom-plugins', [('working', 2)]),
              ('2F', 'Phantom-IDEA', [('waiting', 1), ('idle', 1)]),
              ('1F', 'colo-backend', [('working', 3), ('waiting', 1), ('done', 1), ('idle', 1)])]
    for i, (fl, name, counts) in enumerate(floors):
        y0 = 78 + i * 58
        sel = fl == '1F'
        d.rounded_rectangle([12, y0, 188, y0 + 50], 9, fill=(ui['acc'] + (255,)) if sel else (ui['bg2'] + (255,)),
                            outline=None if sel else ui['line'])
        chip = (tuple(int(c * 0.7 + 255 * 0.3) for c in ui['acc']) + (255,)) if sel else ui['line'] + (255,)
        d.rounded_rectangle([20, y0 + 9, 52, y0 + 41], 7, fill=chip)
        tc = ui['acc_t'] if sel else ui['text']
        f = font(15, 6)
        d.text((36 - f.getlength(fl) / 2, y0 + 15), fl, font=f, fill=tc)
        d.text((60, y0 + 7), name, font=font(14, 6), fill=tc)
        x = 60
        for st, n in counts:
            col = SP.STATE_COL[st][:3]
            d.ellipse([x, y0 + 33, x + 8, y0 + 41], fill=col, outline=(255, 255, 255) if sel else None)
            d.text((x + 11, y0 + 29), str(n), font=font(12, 4), fill=tc)
            x += 26
    # ---- legend (pixel bubbles at integer x2 nearest-neighbour)
    ly = 640
    d.text((18, ly), 'STATES · 세션 상태', font=font(11, 6), fill=ui['dim'])
    for i, (st, ko, en) in enumerate(STATE_LABEL):
        y = ly + 22 + i * 52
        d.rounded_rectangle([12, y, 188, y + 46], 9, fill=ui['bg2'] + (255,), outline=ui['line'])
        b = SP.bubble(st)
        bi = b.img.resize((b.w * 2, b.h * 2), Image.NEAREST)
        ov.alpha_composite(bi, (22, y + 23 - bi.height // 2))
        d.text((66, y + 5), ko, font=font(14, 6), fill=ui['text'])
        d.text((66, y + 24), en, font=font(12, 2), fill=ui['dim'])
    # ---- corner badge
    name, sub = NAMES[key]
    bx0, by0, bx1, by1 = NW * S - 430, NH * S - 118, NW * S - 18, NH * S - 18
    d.rounded_rectangle([bx0 + 4, by0 + 6, bx1 + 4, by1 + 6], 16, fill=(0, 0, 0, 90))
    d.rounded_rectangle([bx0, by0, bx1, by1], 16, fill=ui['head'] + (250,), outline=ui['acc'], width=3)
    d.rounded_rectangle([bx0 + 14, by0 + 14, bx0 + 86, by0 + 86], 12, fill=ui['acc'] + (255,))
    fL = font(56, 14)
    d.text((bx0 + 50 - fL.getlength(key) / 2, by0 + 14), key, font=fL, fill=ui['acc_t'])
    d.text((bx0 + 104, by0 + 16), name, font=font(30, 14), fill=ui['text'])
    d.text((bx0 + 104, by0 + 58), sub, font=font(14, 2), fill=ui['dim'])

    img.alpha_composite(ov)
    path = f'{OUT}/{key}-' + {'A': 'warm-wood', 'B': 'modern-bright', 'C': 'night'}[key] + '.png'
    img.convert('RGB').save(path)
    print('wrote', path)


if __name__ == '__main__':
    import sys
    SP.build_sheet()
    for k in (sys.argv[1:] or ['A', 'B', 'C']):
        render(k)
