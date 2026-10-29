"""sprites-preview.png (every sprite x3 on a checkerboard, labelled) + compare.png."""
from PIL import Image, ImageDraw, ImageFont
import people as P
import props as Q
import scene as S
from core import Spr

K = 3


def checker(w, h, a=(206, 200, 192), b=(186, 180, 172), n=6):
    im = Image.new('RGBA', (w, h), a + (255,))
    d = ImageDraw.Draw(im)
    for y in range(0, h, n):
        for x in range(0, w, n):
            if (x // n + y // n) % 2:
                d.rectangle((x, y, x + n - 1, y + n - 1), fill=b + (255,))
    return im


def tile(seed):
    s = Spr(66, 34)
    ox, oy = 33, 1
    for y in range(34):
        for x in range(66):
            rx, ry = x + .5 - ox, y + .5 - oy
            u = (rx / 2 + ry) / 2 + seed * 37
            v = (ry - rx / 2) / 2 + seed * 13
            uu, vv = u - seed * 37, v - seed * 13
            if 0 <= uu < 16 and 0 <= vv < 16:
                s.p[x, y] = S.plank_color(u, v)
    return s.crop(0)


def wall(window):
    s = Spr(70, 128)
    ox, oy = 2, 100
    win = [('R', 4, 26, 30, 70)] if window else []
    for y in range(128):
        for x in range(70):
            uu = (x + .5 - ox) / 2
            z = uu - (y + .5 - oy)
            if 0 <= uu < 32 and 0 <= z < S.WALLH:
                s.p[x, y] = S.wall_color(uu, z, 'R', win)
    return s.crop(0)


def items():
    L = []
    c = P.CAST
    poses = [('mina', 'idle'), ('jun', 'idle'), ('sam', 'wave'), ('hana', 'mug'), ('leo', 'walk'), ('ivy', 'idle'),
             ('ken', 'mug'), ('yuna', 'idle'), ('dev', 'walk'), ('sora', 'wave'), ('nora', 'mug'), ('omar', 'idle'),
             ('tae', 'idle')]
    for n, p in poses:
        L.append(('%s standing:%s' % (n, {'idle': 'idle', 'wave': 'waiting', 'mug': 'done', 'walk': 'walk'}[p]),
                  P.standing(c[n], p)))
    for n in ('mina', 'jun', 'ivy', 'sora', 'omar', 'yuna'):
        b, f = P.seated_front(c[n]); b.paste(f, 0, 0)
        L.append(('%s seated-front:working' % n, b))
    b, f = P.seated_front(c['ken'], sleep=True); b.paste(f, 0, 0)
    L.append(('ken seated-front:idle(asleep)', b))
    for n in ('tae', 'dev', 'hana', 'ivy'):
        L.append(('%s seated-back:working' % n, P.seated_back(c[n])))
    L.append(('ken seated-back:idle(asleep)', P.seated_back(c['ken'], sleep=True)))
    L += [('CRT green', Q.crt2('green', seed=3)), ('CRT blue', Q.crt2('blue', seed=5)), ('CRT rear', Q.crt2(back=True)),
          ('tower PC', Q.tower()), ('keyboard', Q.keyboard(12, 4)), ('mousepad+mouse', Q.mousepad()),
          ('desk w/ drawers', Q.desk()), ('office chair seat', Q.chair_base()),
          ('chair back (front)', Q.chair_back(Q.CHAIR, 'front')), ('chair back (rear)', Q.chair_back(Q.CHAIR, 'rear')),
          ('plant big', Q.plant(True)), ('plant small', Q.plant(False)), ('cactus', Q.cactus()),
          ('lamp teal', Q.lamp('#3ab0b0')), ('lamp purple', Q.lamp('#a04ac8')), ('coffee machine', Q.coffee_machine()),
          ('grinder', Q.grinder()), ('mug', Q.mug_spr()), ('stool', Q.stool()), ('bookshelf', Q.bookshelf()),
          ('cabinet', Q.cabinet()), ('counter', Q.counter(14, 30, 26)), ('pen cup', Q.pencup()),
          ('sticky note', Q.sticky()), ('books', Q.books_stack()),
          ('floor tile a', tile(1)), ('floor tile b', tile(2)), ('floor tile c', tile(3)), ('floor tile d', tile(4)),
          ('brick wall', wall(False)), ('brick wall + window', wall(True))]
    for k in ('code', 'warn', 'done', 'zzz', 'lambda', 'idea', 'map'):
        L.append(('bubble ' + k, Q.bubble(k)))
    return L


def sheet():
    L = items()
    font = ImageFont.load_default()
    cells = []
    for name, s in L:
        w, h = s.w * K, s.h * K
        cw, ch = max(w + 12, 150), h + 34
        im = checker(cw, ch)
        im.alpha_composite(s.img.resize((w, h), Image.NEAREST), ((cw - w) // 2, 6))
        d = ImageDraw.Draw(im)
        d.text((4, ch - 22), name, fill=(30, 24, 30, 255), font=font)
        d.text((4, ch - 11), '%dx%d' % (s.w, s.h), fill=(90, 80, 90, 255), font=font)
        cells.append(im)
    W = 1800
    rows, row, x = [], [], 0
    for c in cells:
        if x + c.width > W and row:
            rows.append(row); row, x = [], 0
        row.append(c); x += c.width + 6
    rows.append(row)
    H = sum(max(c.height for c in r) + 6 for r in rows)
    out = Image.new('RGBA', (W, H), (60, 52, 58, 255))
    y = 0
    for r in rows:
        x = 0
        for c in r:
            out.alpha_composite(c, (x, y)); x += c.width + 6
        y += max(c.height for c in r) + 6
    out.save('/tmp/phantom-ai-hires/sprites-preview.png')
    print('sheet', out.size)


def compare():
    ref = Image.open('/tmp/phantom-ai-viz/reference.jpg').convert('RGBA')
    # trim the white strip under the reference art
    ref = ref.crop((0, 0, ref.width, 594))
    sc = Image.open('/tmp/phantom-ai-hires/scene.png').convert('RGBA')
    h = 900
    r2 = ref.resize((round(ref.width * h / ref.height), h), Image.LANCZOS)
    out = Image.new('RGBA', (r2.width + sc.width + 20, h), (30, 26, 30, 255))
    out.paste(r2, (0, 0)); out.paste(sc, (r2.width + 20, 0))
    out.save('/tmp/phantom-ai-hires/compare.png')
    print('compare', out.size)


if __name__ == '__main__':
    sheet()
    compare()
