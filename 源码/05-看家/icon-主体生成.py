#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「看家」图标的主体画面（180x240 满铺），
再交给 launcher_standard/mk_icon.py 套用原厂圆角+投影模板。

设计：深青黑卡片 + 极简盾牌 + 内嵌对勾。
「盾」是安防的通用语言，「对勾」表示持续值守中；不用眼睛，避免廉价感。
只画内容，不画圆角、不画投影 —— 那是模板的活。
"""
import zlib, struct, math

W, H = 180, 240
SS = 3                      # 每像素 3x3 超采样

# ── 配色 ──────────────────────────────────────────────
BG_TOP = (19, 34, 36)
BG_BOT = (7, 12, 14)

FILL_TOP = (20, 44, 42)
FILL_BOT = (10, 26, 24)

EDGE_HI = (108, 238, 208)
EDGE_LO = (42, 152, 127)

TICK_HI = (132, 248, 217)
TICK_LO = (62, 196, 166)

# ── 盾牌几何 ──────────────────────────────────────────
CX = 90.0
TOP = 26.0
BOT = 166.0
Y1 = 120.0                  # 肩线：以上为直边，以下收口
HW = 52.0                   # 最大半宽
R = 10.0                    # 顶部圆角
SW = 2.8                    # 描边宽度


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t)


def over(dst, src, a):
    if a <= 0:
        return dst
    if a >= 1:
        return src
    return (dst[0] * (1 - a) + src[0] * a,
            dst[1] * (1 - a) + src[1] * a,
            dst[2] * (1 - a) + src[2] * a)


def shield_hw(y):
    """该行的半宽；返回 <=0 表示不在盾牌内"""
    if y < TOP or y > BOT:
        return -1.0
    if y < Y1:
        if y < TOP + R:
            dy = TOP + R - y
            return HW - R + math.sqrt(max(0.0, R * R - dy * dy))
        return HW
    t = (y - Y1) / (BOT - Y1)
    return HW * math.pow(max(0.0, 1.0 - t * t), 0.5)


def shield_dist(x, y):
    """到盾牌边界的距离（正=内部）。近似，够做描边用"""
    hw = shield_hw(y)
    if hw <= 0:
        return -1.0
    dh = hw - abs(x - CX)
    dv = min(y - TOP, BOT - y)
    return min(dh, dv)


def seg_dist(x, y, x1, y1, x2, y2):
    vx, vy = x2 - x1, y2 - y1
    wx, wy = x - x1, y - y1
    L = vx * vx + vy * vy
    t = 0.0 if L == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / L))
    return math.hypot(wx - t * vx, wy - t * vy)


# 对勾三点
T1 = (63.0, 84.0)
T2 = (82.0, 111.0)
T3 = (118.0, 62.0)
TW = 6.8                    # 勾的半线宽


def tick_dist(x, y):
    return min(seg_dist(x, y, T1[0], T1[1], T2[0], T2[1]),
               seg_dist(x, y, T2[0], T2[1], T3[0], T3[1]))


def col_at(x, y):
    # 背景
    col = lerp(BG_TOP, BG_BOT, y / (H - 1.0))
    if y < 34:
        col = over(col, (255, 255, 255), 0.055 * (1 - y / 34.0))

    d = shield_dist(x, y)
    if d <= 0:
        return col

    # 盾牌本体
    tt = max(0.0, min(1.0, (y - TOP) / (BOT - TOP)))
    col = lerp(FILL_TOP, FILL_BOT, tt)

    # 玻璃斜光：左上往右下的一道极淡提亮
    g = (x * 0.55 + y * 0.45) / (W * 0.55 + H * 0.45)
    col = over(col, (140, 220, 205), max(0.0, 0.10 * (1.0 - g * 1.7)))

    # 描边
    if d < SW:
        e = lerp(EDGE_HI, EDGE_LO, tt)
        a = min(1.0, (SW - d) / 1.35)
        col = over(col, e, a * 0.95)

    # 对勾 + 外发光
    td = tick_dist(x, y)
    if td < TW + 7:
        halo = max(0.0, 1.0 - (td - TW) / 7.0) if td > TW else 1.0
        col = over(col, TICK_HI, 0.16 * halo * (1 if td > TW else 0.35))
    if td < TW:
        a = min(1.0, (TW - td) / 1.3)
        c = lerp(TICK_HI, TICK_LO, max(0.0, min(1.0, (y - 78) / 42.0)))
        col = over(col, c, a)
    return col


px = bytearray(W * H * 4)
n = SS * SS
for y in range(H):
    for x in range(W):
        ar = ag = ab = 0.0
        for sy in range(SS):
            for sx in range(SS):
                c = col_at(x + (sx + 0.5) / SS, y + (sy + 0.5) / SS)
                ar += c[0]; ag += c[1]; ab += c[2]
        i = (y * W + x) * 4
        px[i] = min(255, int(ar / n + 0.5))
        px[i + 1] = min(255, int(ag / n + 0.5))
        px[i + 2] = min(255, int(ab / n + 0.5))
        px[i + 3] = 255

raw = b''
for y in range(H):
    raw += b'\x00' + bytes(px[y * W * 4:(y + 1) * W * 4])


def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)


png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(raw, 9))
       + chunk(b'IEND', b''))

OUT = '/tmp/guard_art_180.png'
open(OUT, 'wb').write(png)
print(OUT, len(png), 'bytes')
