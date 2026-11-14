#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「云台调节」图标的主体画面（180x240 满铺），
再交给 launcher_standard/mk_icon.py 套用原厂圆角+投影模板。

设计：深蓝黑卡片 + 竖直升降滑轨 + 白色滑块 + 上下细箭头。
「滑块」= 云台的俯仰位置调节，和 App 里的方向盘 UI 对应；
不用镜头/眼睛，避免和「看家」撞符号。
只画内容，不画圆角、不画投影 —— 那是模板的活。
"""
import zlib, struct, math

W, H = 180, 240
SS = 3

# ── 配色 ──────────────────────────────────────────────
BG_TOP = (17, 27, 51)
BG_BOT = (7, 11, 22)

RAIL = (26, 39, 62)
FILL_HI = (86, 146, 255)
FILL_LO = (40, 92, 198)
KNOB_HI = (240, 247, 255)
KNOB_LO = (196, 214, 240)
CHEV = (126, 168, 224)

# ── 几何 ──────────────────────────────────────────────
CX = 90.0
RAIL_X0, RAIL_X1 = 82.0, 98.0     # 轨道（宽 16）
RAIL_Y0, RAIL_Y1 = 50.0, 132.0
KNOB_W, KNOB_H = 60.0, 20.0
KNOB_Y = 92.0                    # 滑块中心

CHEV_UP_Y = 30.0
CHEV_DN_Y = 148.0
CHEV_HALF = 14.0
CHEV_H = 9.0
CHEV_W = 5.4


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


def sdf_rrect(x, y, cx, cy, hw, hh, r):
    dx = abs(x - cx) - (hw - r)
    dy = abs(y - cy) - (hh - r)
    return math.hypot(max(dx, 0), max(dy, 0)) + min(max(dx, dy), 0) - r


def sdf_seg(x, y, x1, y1, x2, y2, hw):
    vx, vy = x2 - x1, y2 - y1
    wx, wy = x - x1, y - y1
    L = vx * vx + vy * vy
    t = 0.0 if L == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / L))
    return math.hypot(wx - t * vx, wy - t * vy) - hw


def col_at(x, y):
    col = lerp(BG_TOP, BG_BOT, y / (H - 1.0))
    if y < 40:
        col = over(col, (255, 255, 255), 0.05 * (1 - y / 40.0))

    # 轨道凹槽
    d = sdf_rrect(x, y, CX, (RAIL_Y0 + RAIL_Y1) / 2, (RAIL_X1 - RAIL_X0) / 2,
                  (RAIL_Y1 - RAIL_Y0) / 2, 8.0)
    if d < 1.2:
        col = over(col, RAIL, min(1.0, (1.2 - d) / 1.2))

    # 轨道里的渐变进度（下段亮）
    if RAIL_Y0 - 1 < y < KNOB_Y and RAIL_X0 < x < RAIL_X1:
        t = (KNOB_Y - y) / (KNOB_Y - RAIL_Y0)
        c = lerp(FILL_HI, FILL_LO, t)
        if RAIL_X0 + 4.2 < x < RAIL_X1 - 4.2:
            col = over(col, c, 1.0)

    # 上下箭头
    for (cy, up) in ((CHEV_UP_Y, True), (CHEV_DN_Y, False)):
        s = -1 if up else 1
        d1 = sdf_seg(x, y, CX - CHEV_HALF, cy - s * CHEV_H, CX, cy + s * CHEV_H, CHEV_W)
        d2 = sdf_seg(x, y, CX, cy + s * CHEV_H, CX + CHEV_HALF, cy - s * CHEV_H, CHEV_W)
        dc = min(d1, d2)
        if dc < 1.0:
            col = over(col, CHEV, min(1.0, (1.0 - dc) / 1.0))

    # 滑块阴影
    ds = sdf_rrect(x, y, CX, KNOB_Y + 3.0, KNOB_W / 2, KNOB_H / 2, KNOB_H / 2)
    if ds < 7:
        col = over(col, (0, 0, 0), max(0.0, 0.30 * (1 - ds / 7.0)))

    # 滑块
    dk = sdf_rrect(x, y, CX, KNOB_Y, KNOB_W / 2, KNOB_H / 2, KNOB_H / 2)
    if dk < 1.1:
        a = min(1.0, (1.1 - dk) / 1.1)
        t = max(0.0, min(1.0, (y - (KNOB_Y - KNOB_H / 2)) / KNOB_H))
        col = over(col, lerp(KNOB_HI, KNOB_LO, t), a)

    # 滑块中间的短刻线，增加机械感
    if abs(y - KNOB_Y) < 1.1 and abs(x - CX) < 9:
        col = over(col, (140, 168, 205), 0.9)

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

OUT = '/tmp/tvtest/art_180.png'
open(OUT, 'wb').write(png)
print(OUT, len(png), 'bytes')
