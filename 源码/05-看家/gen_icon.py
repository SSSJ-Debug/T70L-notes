#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「看家」桌面图标主体：180x240 满铺，不画圆角、不画投影（交给 mk_icon.py）"""
import math
import struct
import zlib

W, H = 180, 240
px = [[(0, 0, 0, 255) for _ in range(W)] for _ in range(H)]


def mix(c1, c2, t):
    return tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(3))


TOP = (32, 44, 62)
BOT = (16, 22, 31)
ACC = (58, 140, 255)
ACC2 = (28, 92, 210)
WHITE = (238, 245, 253)
DIM = (120, 150, 190)


def cl(v):
    return 0 if v < 0 else (255 if v > 255 else int(v))


def put(x, y, c, a=1.0):
    if not (0 <= x < W and 0 <= y < H):
        return
    r, g, b, _ = px[y][x]
    px[y][x] = (cl(r * (1 - a) + c[0] * a),
                cl(g * (1 - a) + c[1] * a),
                cl(b * (1 - a) + c[2] * a), 255)


# 背景：上浅下深的竖向渐变
for y in range(H):
    c = mix(TOP, BOT, y / float(H - 1))
    for x in range(W):
        px[y][x] = (cl(c[0]), cl(c[1]), cl(c[2]), 255)

# 左上柔光
for y in range(H):
    for x in range(W):
        d = math.hypot(x - 20, y - 10)
        if d < 150:
            t = max(0.0, (150 - d) / 150) ** 2 * 0.16
            put(x, y, (120, 175, 255), t)

# 眼睛：上弧饱满、下弧略平
CX, CY = 90, 110
HALF = 60
for y in range(30, 190):
    for x in range(20, 160):
        u = (x - CX) / HALF
        if abs(u) > 1:
            continue
        top = CY - 43 * math.sqrt(max(0.0, 1 - u * u))
        bot = CY + 37 * math.sqrt(max(0.0, 1 - u * u))
        if top <= y <= bot:
            # 眼白：靠边压暗一点，有体积感
            edge = min(1.0, abs(u))
            put(x, y, mix(WHITE, DIM, edge ** 3 * 0.55), 1.0)

# 虹膜
IR = 29
for y in range(CY - IR - 2, CY + IR + 3):
    for x in range(CX - IR - 2, CX + IR + 3):
        d = math.hypot(x - CX, y - CY)
        if d <= IR:
            t = d / IR
            put(x, y, mix(ACC, ACC2, t ** 0.8), 1.0)

# 瞳孔
for y in range(CY - 12, CY + 13):
    for x in range(CX - 12, CX + 13):
        if math.hypot(x - CX, y - CY) <= 11:
            put(x, y, (12, 16, 24), 1.0)

# 高光
for y in range(CY - 18, CY + 2):
    for x in range(CX - 18, CX + 2):
        d = math.hypot(x - (CX - 9), y - (CY - 9))
        if d <= 7:
            put(x, y, (255, 255, 255), 0.85 * (1 - d / 7) + 0.25)

raw = b''
for y in range(H):
    raw += b'\x00' + b''.join(struct.pack('BBBB', *px[y][x]) for x in range(W))


def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)


png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(raw, 9))
       + chunk(b'IEND', b''))
open('art_180.png', 'wb').write(png)
print("art_180.png", W, "x", H, len(png), "bytes")
