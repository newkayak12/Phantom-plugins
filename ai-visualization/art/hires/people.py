"""People: hand-placed faces + procedurally-shaded bodies at native resolution.

Front view = front-3/4 facing down-left (mirror for down-right).
Back view  = back-3/4 facing up-right (mirror for up-left).
"""
import math
from core import Spr, C, mix, ramp, blob, mask_poly, outline, INK, H

SKINS = {
    'pale': ramp('#f2c8a4', .45, .22),
    'light': ramp('#e8b48c', .42, .25),
    'tan': ramp('#c98e62', .38, .28),
    'brown': ramp('#9a6444', .32, .30),
    'dark': ramp('#6e4430', .30, .32),
}

W, HGT = 44, 82          # standing canvas
HX, HY = 15, 4           # head origin in canvas


# ---------------------------------------------------------------- head
def head_mask(w, h, ox, oy):
    return mask_poly(w, h, ells=[(ox, oy, ox + 13, oy + 14)],
                     polys=[[(ox + 1, oy + 8), (ox + 12, oy + 8), (ox + 12, oy + 12), (ox + 9, oy + 16),
                             (ox + 7, oy + 17), (ox + 4, oy + 17), (ox + 1, oy + 13)]])


def draw_face(s, ox, oy, cfg, expr='smile'):
    S = cfg['skin']
    m = head_mask(s.w, s.h, ox, oy)
    mp = m.load()
    # base skin with soft light from the left, shadow on the right/back of head and under the jaw
    for y in range(oy, oy + 18):
        for x in range(ox - 1, ox + 15):
            if not (0 <= x < s.w and 0 <= y < s.h) or not mp[x, y]:
                continue
            rx = x - ox
            t = 2
            if rx <= 1 and 3 < y - oy < 14:
                t = 1
            if rx >= 10:
                t = 3
            if rx >= 12:
                t = 4
            if y - oy >= 15 and rx >= 6:
                t = 3
            s.put(x, y, S[t])
    # cheek highlight
    s.put(ox + 2, oy + 12, S[1]); s.put(ox + 1, oy + 11, S[1])
    # ear (right side, the back of the 3/4 head)
    s.grid(ox + 10, oy + 7, [
        "..344.",
        ".3234.",
        ".3324.",
        ".3434.",
        "..44..",
    ], {'2': S[2], '3': S[3], '4': S[4]})
    # jaw / neck shadow line under chin
    for x in range(ox + 5, ox + 11):
        s.put(x, oy + 17, S[4] if x > ox + 7 else S[3])
    brow = cfg['hair'][4]
    eye = cfg.get('iris', C('#3a4a8a'))
    lash = C('#1c1218')
    W_ = C('#ffffff')
    gl = cfg.get('glasses')
    if gl:
        F = cfg.get('frame', C('#3a2c34'))
        lens = mix(S[1], C('#eaf4ff'), .55)
        for (a, b) in ((ox - 1, ox + 3), (ox + 5, ox + 10)):
            for y in range(oy + 8, oy + 11):
                for x in range(a + 1, b):
                    s.put(x, y, lens)
    if expr == 'sleep':
        s.hline(ox + 1, ox + 2, oy + 10, lash); s.put(ox + 3, oy + 9, S[3])
        s.hline(ox + 6, ox + 8, oy + 10, lash); s.put(ox + 9, oy + 9, S[3])
    elif gl:
        s.vline(ox + 2, oy + 9, oy + 10, lash); s.put(ox + 1, oy + 9, W_)
        s.vline(ox + 7, oy + 9, oy + 10, lash); s.put(ox + 8, oy + 9, eye); s.put(ox + 8, oy + 10, lash)
        s.put(ox + 6, oy + 9, W_)
    else:
        s.hline(ox + 1, ox + 2, oy + 6, brow)
        s.hline(ox + 6, ox + 9, oy + 6, brow)
        s.put(ox + 9, oy + 7, brow)
        s.grid(ox + 1, oy + 8, ["LL", "WI", "IL"], {'L': lash, 'W': W_, 'I': eye})
        s.grid(ox + 6, oy + 8, ["LLLL", "WIL.", "IIL."], {'L': lash, 'W': W_, 'I': eye})
    # nose: tip points left, shadow underneath
    s.put(ox + 4, oy + 11, S[1])
    s.put(ox + 4, oy + 12, S[3])
    s.put(ox + 5, oy + 12, S[3])
    # blush (soft, under the eyes)
    blush = mix(S[2], C('#ff7a7a'), .18)
    s.put(ox + 1, oy + 12, blush); s.put(ox + 7, oy + 12, blush); s.put(ox + 8, oy + 12, blush)
    # mouth
    M = mix(S[5], C('#8a2a2a'), .5); Ml = C('#c25a50')
    if expr == 'smile':
        s.hline(ox + 3, ox + 5, oy + 15, M); s.put(ox + 6, oy + 14, M); s.put(ox + 2, oy + 14, S[3])
    elif expr == 'open':
        s.hline(ox + 3, ox + 6, oy + 14, M); s.hline(ox + 4, ox + 5, oy + 15, Ml); s.put(ox + 3, oy + 15, M)
        s.put(ox + 6, oy + 15, M)
    elif expr == 'sleep':
        s.put(ox + 4, oy + 15, M); s.put(ox + 5, oy + 15, S[4])
    else:
        s.hline(ox + 3, ox + 5, oy + 15, M)
    s.put(ox + 4, oy + 16, S[3])
    if gl:
        for (a, b) in ((ox - 1, ox + 3), (ox + 5, ox + 10)):
            s.hline(a + 1, b - 1, oy + 7, F); s.hline(a + 1, b - 1, oy + 11, F)
            s.vline(a, oy + 8, oy + 10, F); s.vline(b, oy + 8, oy + 10, F)
            s.put(a + 1, oy + 8, (255, 255, 255, 255))
        s.put(ox + 4, oy + 9, F)
        s.hline(ox + 11, ox + 12, oy + 9, F)  # temple arm to ear
        s.hline(ox + 1, ox + 2, oy + 6, brow); s.hline(ox + 6, ox + 9, oy + 6, brow)


def draw_head_back(s, ox, oy, cfg):
    S = cfg['skin']
    m = mask_poly(s.w, s.h, ells=[(ox, oy, ox + 13, oy + 14)])
    blob(s, m, S, lx=.7, ly=.3)
    # ears on both sides
    s.grid(ox - 1, oy + 8, ["33", "23", "34"], {'2': S[2], '3': S[3], '4': S[4]})
    s.grid(ox + 12, oy + 8, ["34", "34", "44"], {'3': S[3], '4': S[4]})


# ---------------------------------------------------------------- hair
def _hair_shade(s, m, R, ox, oy, hl=True, strands=True):
    blob(s, m, R, lx=.55, ly=.45, cuts=(.0, .30, .66, .9), hi=False)
    mp = m.load()
    if hl:  # glossy arc highlight upper-left
        for y in range(oy - 6, oy + 10):
            for x in range(ox - 3, ox + 16):
                if 0 <= x < s.w and 0 <= y < s.h and mp[x, y]:
                    dx, dy = (x - (ox + 7)) / 8.0, (y - (oy + 7)) / 8.0
                    d = math.hypot(dx, dy)
                    if .74 < d < .9 and dx < .25 and dy < -.25:
                        s.put(x, y, R[0])
                    elif .66 < d <= .74 and dx < 0 and dy < -.35:
                        s.put(x, y, R[1])
    if strands:
        for x in range(ox - 2, ox + 16):
            if H('st', x) < .35:
                for y in range(oy - 4, oy + 24):
                    if 0 <= x < s.w and 0 <= y < s.h and mp[x, y] and H('sy', x, y // 3) < .5:
                        c = s.get(x, y)
                        if c == R[2] or c == R[1]:
                            s.put(x, y, R[3] if c == R[2] else R[2])


def hair_front(s, ox, oy, cfg, back_layer=False):
    st = cfg['style']
    R = cfg['hair']
    w, h = s.w, s.h
    if back_layer:
        # parts of hair that sit behind the body
        if st == 'long':
            m = mask_poly(w, h, polys=[[(ox + 6, oy + 2), (ox + 15, oy + 3), (ox + 17, oy + 16),
                                        (ox + 16, oy + 26), (ox + 9, oy + 25), (ox + 8, oy + 12)]])
            _hair_shade(s, m, R, ox, oy, hl=False)
        if st == 'ponytail':
            m = mask_poly(w, h, ells=[(ox + 11, oy - 3, ox + 19, oy + 6)],
                          polys=[[(ox + 15, oy + 2), (ox + 20, oy + 3), (ox + 21, oy + 12),
                                  (ox + 19, oy + 20), (ox + 17, oy + 23), (ox + 17, oy + 14), (ox + 14, oy + 6)]])
            _hair_shade(s, m, R, ox + 6, oy + 2, hl=True)
            tie = C('#e8507a')
            s.put(ox + 14, oy + 2, tie); s.put(ox + 15, oy + 3, tie); s.put(ox + 15, oy + 2, tie)
        if st == 'bun':
            pass
        return
    # fringe bottom (y offset per x from ox-1 .. ox+14)
    fr = {
        'short':    [6, 5, 5, 4, 5, 4, 4, 5, 4, 5, 5, 7, 8, 9, 9, 9],
        'spiky':    [5, 5, 4, 4, 5, 4, 4, 5, 5, 5, 6, 7, 8, 9, 9, 9],
        'ponytail': [9, 8, 5, 4, 4, 4, 3, 4, 4, 5, 5, 7, 8, 9, 10, 10],
        'long':     [13, 10, 6, 5, 4, 4, 4, 4, 5, 5, 6, 9, 12, 14, 16, 16],
        'curly':    [7, 6, 5, 5, 5, 5, 4, 5, 5, 5, 6, 8, 9, 10, 10, 10],
        'bun':      [7, 6, 5, 5, 4, 4, 4, 4, 4, 5, 5, 8, 9, 10, 10, 10],
        'buzz':     [4, 4, 3, 3, 3, 3, 3, 3, 3, 3, 4, 5, 6, 7, 7, 7],
        'side':     [6, 5, 4, 3, 3, 4, 4, 5, 5, 5, 6, 8, 9, 10, 10, 10],
    }[st]
    m = mask_poly(w, h, ells=[(ox - 1, oy - 3, ox + 14, oy + 11)])
    mp = m.load()
    # cut fringe
    for x in range(ox - 2, ox + 16):
        i = min(max(x - (ox - 1), 0), len(fr) - 1)
        for y in range(oy + fr[i], oy + 30):
            if 0 <= x < w and 0 <= y < h:
                mp[x, y] = 0
    # sideburn / back of head on the right side (behind ear)
    if st not in ('buzz',):
        for y in range(oy + 4, oy + 9):
            for x in range(ox + 12, ox + 15):
                if 0 <= x < w and 0 <= y < h:
                    mp[x, y] = 255
    if st == 'long':
        for (x, y0, y1) in [(ox - 1, oy + 3, oy + 15), (ox, oy + 5, oy + 13)]:
            for y in range(y0, y1):
                mp[x, y] = 255
    if st == 'ponytail':
        for y in range(oy + 4, oy + 13):
            mp[ox - 1, y] = 255
        for y in range(oy + 4, oy + 9):
            mp[ox, y] = 255
    if st == 'spiky':
        d = __import__('PIL.ImageDraw', fromlist=['Draw']).Draw(m)
        for (a, b) in [(ox + 0, oy - 3), (ox + 4, oy - 5), (ox + 8, oy - 5), (ox + 12, oy - 2)]:
            d.polygon([(a - 2, b + 4), (a, b - 1), (a + 3, b + 3)], fill=255)
    if st == 'bun':
        d = __import__('PIL.ImageDraw', fromlist=['Draw']).Draw(m)
        d.ellipse((ox + 5, oy - 8, ox + 12, oy - 1), fill=255)
    if st == 'curly':
        _curly(s, m, R, ox, oy)
    else:
        _hair_shade(s, m, R, ox, oy)
    if st == 'bun':
        s.hline(ox + 6, ox + 10, oy - 1, R[4])


def _curly(s, m, R, ox, oy, back=False):
    mp = m.load()
    pts = [(ox + i * 3 - 1 + (j % 2) + int(H('cx', i, j) * 2), oy - 2 + j * 3 + int(H('cy', i, j) * 2))
           for j in range(5) for i in range(6)]
    pts += [(ox - 2, oy + 6), (ox + 14, oy + 6), (ox + 15, oy + 3)]
    for (cx, cy) in pts:
        inside = any(0 <= cx + dx < s.w and 0 <= cy + dy < s.h and mp[cx + dx, cy + dy]
                     for dx in (-1, 0, 1) for dy in (-1, 0, 1))
        if not inside:
            continue
        r = 2.4
        for y in range(int(cy - 3), int(cy + 4)):
            for x in range(int(cx - 3), int(cx + 4)):
                dx, dy = x - cx, y - cy
                if dx * dx + dy * dy <= r * r and (y <= oy + 5 or x > ox + 10 or x < ox or back):
                    t = 2
                    if dx + dy <= -2:
                        t = 1
                    if dx <= -1 and dy <= -1 and dx * dx + dy * dy <= 2.5:
                        t = 0 if y < oy + 3 else 1
                    if dx + dy >= 2:
                        t = 3
                    if dx * dx + dy * dy > 4.6 and dx + dy > 0:
                        t = 4
                    s.put(x, y, R[t])


def hair_back(s, ox, oy, cfg):
    """Back-3/4 view: hair covers most of the head."""
    st = cfg['style']
    R = cfg['hair']
    w, h = s.w, s.h
    bottom = {'short': 13, 'spiky': 13, 'ponytail': 13, 'long': 26, 'curly': 14,
              'bun': 13, 'buzz': 12, 'side': 13}[st]
    polys = []
    ells = [(ox - 1, oy - 3, ox + 14, oy + 12)]
    if bottom > 14:
        polys.append([(ox - 1, oy + 6), (ox + 14, oy + 6), (ox + 15, oy + bottom - 2), (ox + 12, oy + bottom),
                      (ox + 1, oy + bottom), (ox - 2, oy + bottom - 2)])
    else:
        polys.append([(ox + 0, oy + 6), (ox + 13, oy + 6), (ox + 12, oy + bottom), (ox + 1, oy + bottom)])
    m = mask_poly(w, h, polys=polys, ells=ells)
    if st == 'spiky':
        d = __import__('PIL.ImageDraw', fromlist=['Draw']).Draw(m)
        for (a, b) in [(ox + 1, oy - 3), (ox + 5, oy - 5), (ox + 9, oy - 5), (ox + 13, oy - 2)]:
            d.polygon([(a - 2, b + 4), (a, b - 1), (a + 3, b + 3)], fill=255)
    if st == 'bun':
        d = __import__('PIL.ImageDraw', fromlist=['Draw']).Draw(m)
        d.ellipse((ox + 3, oy - 7, ox + 10, oy), fill=255)
    if st == 'ponytail':
        d = __import__('PIL.ImageDraw', fromlist=['Draw']).Draw(m)
        d.polygon([(ox + 4, oy + 4), (ox + 9, oy + 4), (ox + 10, oy + 14), (ox + 8, oy + 22), (ox + 6, oy + 22),
                   (ox + 4, oy + 14)], fill=255)
    if st == 'curly':
        _curly(s, m, R, ox, oy, back=True)
        mp = m.load()
        for y in range(oy + 6, oy + bottom + 1):
            for x in range(ox, ox + 14):
                if mp[x, y] and s.get(x, y)[3] == 0:
                    s.put(x, y, R[2])
        _curly_bottom(s, R, ox, oy, bottom)
    else:
        _hair_shade(s, m, R, ox, oy - 2)
    if st == 'ponytail':
        tie = C('#e8507a')
        s.hline(ox + 5, ox + 8, oy + 7, tie)


def _curly_bottom(s, R, ox, oy, bottom):
    for i in range(5):
        cx, cy = ox + 1 + i * 3, oy + bottom - 1
        for (dx, dy, t) in [(0, 0, 2), (-1, 0, 1), (1, 0, 3), (0, 1, 3), (0, -1, 1)]:
            s.put(cx + dx, cy + dy, R[t])


# ---------------------------------------------------------------- clothing helpers
def shirt_details(s, cfg, x0, x1, y0, y1, cx):
    """Collar/placket/vest/lanyard on a front torso spanning x0..x1, y0..y1. cx = sternum x."""
    sh = cfg['shirt']
    kind = cfg.get('top', 'shirt')
    if cfg.get('vest'):
        V = cfg['vest']
        lm = mask_poly(s.w, s.h, polys=[[(x0, y0 + 1), (cx - 3, y0), (cx - 1, y0 + 9), (cx - 1, y1), (x0 + 1, y1)]])
        blob(s, lm, V, lx=.8, ly=.2)
        rm = mask_poly(s.w, s.h, polys=[[(cx + 2, y0), (x1, y0 + 1), (x1 - 1, y1), (cx + 1, y1), (cx + 1, y0 + 9)]])
        blob(s, rm, V, lx=.8, ly=.2)
        # vest pockets + zip
        s.hline(x0 + 2, x0 + 5, y0 + 14, V[4]); s.hline(cx + 3, cx + 6, y0 + 14, V[4])
        s.vline(cx - 1, y0 + 10, y1, V[4])
    if kind == 'shirt':
        CL = cfg.get('collar', ramp('#f4f1ea'))
        s.grid(cx - 4, y0 - 1, [
            "1111..222",
            ".1112.22.",
            "..12..2..",
            "...2.....",
        ], {'1': CL[1], '2': CL[3]})
        if not cfg.get('vest'):
            for y in range(y0 + 3, y1):
                s.put(cx, y, sh[3])
                if (y - y0) % 5 == 2:
                    s.put(cx, y, sh[1])
            # chest pocket
            s.hline(x0 + 3, x0 + 6, y0 + 6, sh[3]); s.vline(x0 + 6, y0 + 6, y0 + 9, sh[3])
    elif kind == 'tee':
        S = cfg['skin']
        s.grid(cx - 3, y0 - 1, ["2333.", ".333.", "..4.."], {'2': S[2], '3': S[3], '4': sh[4]})
        s.hline(cx - 4, cx + 2, y0 + 1, sh[3])
    elif kind == 'sweater':
        CL = cfg.get('collar', ramp('#f4f1ea'))
        s.grid(cx - 3, y0 - 1, ["1111.", "11122", ".222."], {'1': CL[1], '2': CL[3]})
        for y in range(y0 + 4, y1, 3):
            s.put(x0 + 3, y, sh[3]); s.put(x1 - 3, y, sh[3])
    if cfg.get('lanyard'):
        L = cfg['lanyard']
        for i in range(8):
            s.put(cx - 3 + i // 3, y0 + i, L)
            s.put(cx + 3 - i // 3, y0 + i, L)
        bx, by = cx - 2, y0 + 8
        s.grid(bx, by, [
            ".LLL.",
            "WWWWW",
            "WPPWW",
            "WPPWg",
            "WWWWW",
            "ggggg",
        ], {'L': L, 'W': C('#f8f8f0'), 'P': C('#7aa0d8'), 'g': C('#b8b4a8')})


# ---------------------------------------------------------------- bodies
def _arm(s, pts, R):
    blob(s, mask_poly(s.w, s.h, polys=[pts]), R, lx=.75, ly=.25)


def _hand(s, x, y, S, fist=True):
    s.grid(x, y, ["122.", "1223", "2234", ".33."] if fist else ["1.2.", "1223", "2233", "2234", ".33."],
           {'1': S[1], '2': S[2], '3': S[3], '4': S[4]})


def standing(cfg, pose='idle'):
    """pose: idle | wave (raised hand) | mug | walk"""
    s = Spr(W, HGT)
    S = cfg['skin']
    sh, pa, shoe = cfg['shirt'], cfg['pants'], cfg['shoes']
    hx, hy = HX, HY
    # hair behind body
    hair_front(s, hx, hy, cfg, back_layer=True)
    short = cfg.get('top') == 'tee'
    # far arm (screen right)
    if pose == 'wave':
        _arm(s, [(27, 24), (31, 23), (33, 12), (32, 6), (29, 6), (29, 13)], sh)
        if short:
            _arm(s, [(29, 14), (32, 13), (32, 6), (29, 6)], S)
        s.grid(28, 0, [
            ".1.1.",
            "11212",
            "12223",
            "12233",
            ".2334",
            ".233.",
        ], {'1': S[1], '2': S[2], '3': S[3], '4': S[4]})
    elif pose == 'walk':
        _arm(s, [(27, 24), (31, 25), (33, 38), (29, 39), (27, 30)], sh)
        if short:
            _arm(s, [(29, 31), (32, 31), (33, 38), (29, 39)], S)
        _hand(s, 29, 38, S)
    else:
        _arm(s, [(27, 24), (31, 25), (32, 40), (28, 40), (27, 30)], sh)
        if short:
            _arm(s, [(28, 31), (31, 31), (32, 40), (28, 40)], S)
        _hand(s, 28, 40, S)
    # legs
    if pose == 'walk':
        leg_n = [(14, 46), (21, 46), (17, 57), (13, 66), (8, 66), (11, 56)]
        leg_f = [(21, 46), (28, 46), (28, 55), (30, 64), (25, 65), (22, 55)]
        shoe_n = (5, 65, 14, 69)
        shoe_f = (24, 63, 32, 67)
    else:
        leg_n = [(14, 46), (21, 46), (20, 66), (14, 66)]
        leg_f = [(22, 46), (28, 46), (28, 65), (22, 65)]
        shoe_n = (12, 65, 21, 69)
        shoe_f = (21, 64, 29, 68)
    blob(s, mask_poly(W, HGT, polys=[leg_f]), pa, lx=.8, ly=.2)
    blob(s, mask_poly(W, HGT, polys=[leg_n]), pa, lx=.8, ly=.2)
    for (a, b, c, d) in (shoe_f, shoe_n):
        m = mask_poly(W, HGT, ells=[(a, b, c, d + 1)], rects=[(a + 3, b, c, d)])
        blob(s, m, shoe, lx=.6, ly=.4)
        s.hline(a + 1, c - 2, b + 1, shoe[1])
    # hips
    blob(s, mask_poly(W, HGT, rects=[(14, 42, 28, 48)]), pa, lx=.8, ly=.2, edge=False)
    s.hline(14, 21, 48, pa[3]); s.vline(21, 46, 52, pa[4])
    _torso_front(s, cfg, 13, 29, 22, 44, 21)
    # near arm (screen left)
    if pose == 'mug':
        _arm(s, [(11, 24), (16, 24), (16, 36), (12, 37), (11, 30)], sh)
        _arm(s, [(10, 33), (16, 33), (17, 37), (11, 38)], sh if not short else S)
        mug(s, 5, 28)
        _hand(s, 9, 33, S)
    else:
        sw = 2 if pose == 'walk' else 0
        _arm(s, [(11, 24), (16, 24), (15, 31), (14 - sw, 41), (9 - sw, 41), (10, 30)], sh)
        if short:
            _arm(s, [(10, 31), (15, 31), (14 - sw, 41), (9 - sw, 41)], S)
        _hand(s, 9 - sw, 40, S)
    # neck + head
    blob(s, mask_poly(W, HGT, rects=[(hx + 4, hy + 15, hx + 8, hy + 19)]), S, lx=.3, ly=.7)
    s.hline(hx + 4, hx + 8, hy + 18, S[4])
    draw_face(s, hx, hy, cfg, cfg.get('expr', 'smile'))
    hair_front(s, hx, hy, cfg)
    if cfg.get('belt', True):
        s.hline(14, 28, 44, pa[4]); s.put(20, 44, C('#d8c070')); s.put(21, 44, C('#a89040'))
    outline(s)
    s.ax, s.ay = 21, 68
    return s


def _torso_front(s, cfg, x0, x1, y0, y1, cx):
    sh = cfg['shirt']
    m = mask_poly(s.w, s.h, polys=[[(x0 + 2, y0), (x1 - 2, y0), (x1, y0 + 3), (x1, y0 + 8), (x1 - 1, y1),
                                     (x0 + 1, y1), (x0, y0 + 8), (x0, y0 + 3)]])
    blob(s, m, sh, lx=.8, ly=.2, cuts=(.08, .38, .72, .93))
    # fold shading under chest / near arm side
    s.hline(x0 + 3, x0 + 6, y0 + 12, sh[3])
    shirt_details(s, cfg, x0, x1, y0, y1, cx)


def mug(s, x, y, col=None, steam=True):
    col = col or ramp('#f0ece4')
    s.grid(x, y, [
        "......",
        ".2222.",
        "133334",
        "1223..",
        "1223.4",
        "1223.4",
        "12234.",
        ".344..",
    ], {'1': col[1], '2': col[2], '3': col[3], '4': col[4]})
    s.put(x + 2, y + 2, C('#5a3420')); s.put(x + 3, y + 2, C('#4a2a18'))
    if steam:
        st = (255, 255, 255, 150)
        for (dx, dy) in [(2, -1), (3, -2), (2, -3), (3, -4), (4, -5)]:
            s.put(x + dx, y + dy, st)


def seated_front(cfg, typing=True, sleep=False):
    """Seated, facing down-left, behind a desk. Returns (body, fore). Anchor = floor under seat."""
    w, h = 44, 64
    s = Spr(w, h)
    f = Spr(w, h)
    S = cfg['skin']
    sh, pa = cfg['shirt'], cfg['pants']
    hx, hy = 15, 4
    if sleep:
        # slumped forward: shoulders low, arms folded on the desk, crown of the head on the arms
        blob(s, mask_poly(w, h, rects=[(13, 42, 29, 50)]), pa, lx=.7, ly=.3)
        m = mask_poly(w, h, polys=[[(12, 30), (30, 29), (32, 34), (31, 46), (13, 46), (11, 34)]])
        blob(s, m, sh, lx=.6, ly=.4)
        if cfg.get('vest'):
            blob(s, mask_poly(w, h, polys=[[(13, 31), (30, 30), (31, 46), (14, 46)]]), cfg['vest'], lx=.6, ly=.4)
        t = Spr(w, h)
        draw_head_back(t, hx - 1, 22, cfg)
        hair_back(t, hx - 1, 22, cfg)
        outline(t)
        f.paste(t, 0, 0)
        blob(f, mask_poly(w, h, ells=[(5, 35, 19, 43), (23, 34, 37, 42)], rects=[(11, 36, 30, 42)]), sh, lx=.35, ly=.65)
        f.hline(12, 30, 38, sh[1])
        _hand(f, 17, 37, S); _hand(f, 22, 37, S)
    if not sleep:
        hair_front(s, hx, hy, cfg, back_layer=True)
        # far upper arm
        _arm(s, [(27, 24), (31, 25), (32, 36), (28, 37), (27, 30)], sh)
        blob(s, mask_poly(w, h, rects=[(13, 42, 29, 50)]), pa, lx=.7, ly=.3)
        _torso_front(s, cfg, 13, 29, 22, 44, 21)
        _arm(s, [(11, 24), (16, 24), (15, 30), (13, 37), (9, 37), (10, 30)], sh)
    if not sleep:
        blob(s, mask_poly(w, h, rects=[(hx + 4, hy + 15, hx + 8, hy + 19)]), S, lx=.3, ly=.7)
        draw_face(s, hx, hy, cfg, cfg.get('expr', 'smile'))
        hair_front(s, hx, hy, cfg)
        # forearms reaching down-left to keyboard
        short = cfg.get('top') == 'tee'
        fa = S if short else sh
        _arm(f, [(9, 35), (14, 35), (10, 43), (5, 44), (4, 41)], fa)
        _arm(f, [(27, 34), (32, 35), (24, 45), (19, 45), (19, 42)], fa)
        if not short:
            cu = cfg.get('collar', ramp('#f4f1ea'))
            f.put(5, 42, cu[2]); f.put(19, 43, cu[2])
        _hand(f, 1, 42, S); _hand(f, 16, 43, S)
    outline(s)
    outline(f)
    for x in (s, f):
        x.ax, x.ay = 21, 62
    return s, f


def seated_back(cfg, sleep=False):
    """Seated, facing up-right (back-3/4). Anchor = floor under seat."""
    w, h = 44, 64
    s = Spr(w, h)
    S = cfg['skin']
    sh, pa, shoe = cfg['shirt'], cfg['pants'], cfg['shoes']
    hx, hy = 16, 3
    # legs going forward (up-right), mostly hidden
    blob(s, mask_poly(w, h, polys=[[(14, 42), (30, 40), (34, 46), (18, 50)]]), pa, lx=.5, ly=.5)
    # far arm forward up-right: elbow out to the right
    _arm(s, [(26, 23), (31, 24), (35, 31), (34, 35), (30, 33), (27, 28)], sh)
    # torso back
    m = mask_poly(w, h, polys=[[(15, 21), (27, 21), (30, 24), (30, 32), (29, 44), (14, 44), (12, 32), (12, 24)]])
    blob(s, m, sh, lx=.75, ly=.25, cuts=(.1, .42, .74, .93))
    # shoulder blade fold + center seam
    s.line(17, 28, 19, 33, sh[3]); s.line(25, 28, 24, 33, sh[3])
    s.vline(21, 34, 43, sh[3])
    if cfg.get('vest'):
        V = cfg['vest']
        blob(s, mask_poly(w, h, polys=[[(14, 23), (28, 23), (29, 32), (28, 44), (15, 44), (13, 32)]]), V, lx=.75, ly=.25)
    # collar at the nape
    CL = cfg.get('collar', ramp('#f4f1ea'))
    if cfg.get('top', 'shirt') in ('shirt',):
        s.hline(16, 25, 21, CL[2]); s.hline(17, 24, 22, CL[3])
    else:
        s.hline(17, 24, 21, sh[4])
    # near arm (screen left): upper arm down, forearm forward
    _arm(s, [(11, 24), (16, 24), (16, 34), (13, 38), (10, 37), (10, 30)], sh)
    if cfg.get('top') == 'tee':
        _arm(s, [(10, 31), (16, 31), (16, 34), (13, 38), (10, 37)], S)
    # neck + head
    blob(s, mask_poly(w, h, rects=[(hx + 3, hy + 14, hx + 9, hy + 19)]), S, lx=.6, ly=.4)
    if sleep:
        # slumped forward onto the desk: head low, both forearms up on the desk around it
        hy += 9
        _arm(s, [(26, 22), (33, 18), (36, 22), (30, 28)], sh)
        _arm(s, [(10, 24), (13, 19), (19, 18), (17, 24)], sh)
    draw_head_back(s, hx, hy, cfg)
    hair_back(s, hx, hy, cfg)
    outline(s)
    s.ax, s.ay = 21, 62
    return s


# ---------------------------------------------------------------- cast
CAST = {
    'mina': dict(skin=SKINS['pale'], hair=ramp('#3f8fd6', .5, .3), style='ponytail', shirt=ramp('#f2f0ea', .3, .2),
                 top='shirt', collar=ramp('#ffffff', .2, .2), pants=ramp('#3a3f5a'), shoes=ramp('#3a2a2a'),
                 lanyard=C('#3a7ad8'), iris=C('#2a6ab0')),
    'jun': dict(skin=SKINS['light'], hair=ramp('#7a5030', .45, .3), style='spiky', shirt=ramp('#6a7480', .35, .3),
                top='shirt', collar=ramp('#5a6470'), pants=ramp('#3a3440'), shoes=ramp('#2a2228'),
                glasses=True, lanyard=C('#2a5ac0'), iris=C('#3a2a20')),
    'tae': dict(skin=SKINS['tan'], hair=ramp('#2c2430', .4, .3), style='short', shirt=ramp('#b8403a', .35, .3),
                top='shirt', collar=ramp('#a83a34'), pants=ramp('#3a4a6a'), shoes=ramp('#2a2228'), iris=C('#2a1a14')),
    'leo': dict(skin=SKINS['tan'], hair=ramp('#2a2026', .4, .3), style='side', shirt=ramp('#6aa83a', .35, .3),
                top='tee', pants=ramp('#5a5e6a'), shoes=ramp('#3a3030'), lanyard=C('#d84040'), iris=C('#2a1a14'),
                expr='open'),
    'sam': dict(skin=SKINS['dark'], hair=ramp('#1e1a20', .3, .3), style='buzz', shirt=ramp('#d8d2c0', .3, .25),
                top='shirt', collar=ramp('#e8e2d0'), vest=ramp('#3e4650', .3, .3), pants=ramp('#2e2a30'),
                shoes=ramp('#2a1a14'), lanyard=C('#e0a020'), iris=C('#1a1010'), expr='smile'),
    'hana': dict(skin=SKINS['light'], hair=ramp('#5a3424', .45, .3), style='long', shirt=ramp('#f0eee8', .3, .2),
                 top='shirt', collar=ramp('#ffffff', .2, .2), pants=ramp('#4a3a48'), shoes=ramp('#2a1a1a'),
                 lanyard=C('#3a7ad8'), iris=C('#4a2a18'), expr='smile'),
    'ivy': dict(skin=SKINS['brown'], hair=ramp('#2a1c18', .4, .3), style='curly', shirt=ramp('#7a5aa8', .35, .3),
                top='sweater', collar=ramp('#efe8dc'), pants=ramp('#30303a'), shoes=ramp('#2a1a1a'),
                glasses=True, frame=C('#5a2a3a'), iris=C('#2a1a14')),
    'ken': dict(skin=SKINS['light'], hair=ramp('#1e1c26', .4, .3), style='bun', shirt=ramp('#3a6aa8', .35, .3),
                top='shirt', collar=ramp('#2e5a94'), pants=ramp('#5a4a3a'), shoes=ramp('#2a1a14'),
                lanyard=C('#40b0a0'), iris=C('#2a1a14')),
    'yuna': dict(skin=SKINS['pale'], hair=ramp('#24202c', .4, .3), style='long', shirt=ramp('#e08aa0', .35, .3),
                 top='sweater', collar=ramp('#f4f1ea'), pants=ramp('#3a3448'), shoes=ramp('#2a1a1a'), iris=C('#2a1a14')),
    'dev': dict(skin=SKINS['light'], hair=ramp('#a0482a', .45, .3), style='curly', shirt=ramp('#2a9a9a', .35, .3),
                top='tee', pants=ramp('#3a3a48'), shoes=ramp('#e8e4dc'), iris=C('#3a6a2a'), lanyard=C('#f0c030')),
    'sora': dict(skin=SKINS['tan'], hair=ramp('#9a9aa4', .5, .3), style='short', shirt=ramp('#e09040', .35, .3),
                 top='shirt', collar=ramp('#d08030'), pants=ramp('#4a4038'), shoes=ramp('#2a1a14'), glasses=True,
                 iris=C('#2a1a14'), lanyard=C('#3a7ad8')),
    'nora': dict(skin=SKINS['pale'], hair=ramp('#e0b050', .5, .3), style='bun', shirt=ramp('#5a9a6a', .35, .3),
                 top='sweater', collar=ramp('#f4f1ea'), pants=ramp('#3a3440'), shoes=ramp('#2a1a1a'), iris=C('#3a6a9a'),
                 lanyard=C('#e05a5a')),
    'omar': dict(skin=SKINS['brown'], hair=ramp('#1e1a20', .35, .3), style='spiky', shirt=ramp('#e8e2d2', .3, .25),
                 top='shirt', collar=ramp('#f4efe2'), vest=ramp('#7a5a3a', .35, .3), pants=ramp('#3a3a48'),
                 shoes=ramp('#2a1a14'), iris=C('#2a1a14'), lanyard=C('#3a7ad8')),
}
