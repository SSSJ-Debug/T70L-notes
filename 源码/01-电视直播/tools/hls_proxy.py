#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
hls_proxy.py —— 多连接预取 HLS 代理（验证用）

背景：上游 IPTV 服务器对「单连接」限速约 0.6~1.0 MB/s，
      而 1080p 流需要 1.08 MB/s，单连接必然周期性卡顿。
      实测 4 连接并行可达 2.14 MB/s —— 所以把「下载」和「播放」
      拆开：后台 4 并发预取分片到内存，播放器只跟本地代理说话，
      拿到的永远是已经下好的数据。

用法：python3 hls_proxy.py <upstream_m3u8> [port]
"""
import sys, time, ssl, threading, queue
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, quote, unquote
from concurrent.futures import ThreadPoolExecutor

UPSTREAM = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765

H = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/90 Mobile Safari/537.36',
}
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

cache = {}          # url -> bytes
order = []          # 缓存顺序，用于淘汰
lock = threading.Lock()
stats = {'downloaded': 0, 'bytes': 0, 'miss': 0, 'hit': 0}


def http_get(url, timeout=30):
    req = urllib.request.Request(url, headers=H)
    r = urllib.request.urlopen(req, timeout=timeout, context=CTX)
    return r.read(), r.geturl()


def fetch_playlist():
    """拉上游 m3u8，返回 (改写后的内容, 分片绝对URL列表)"""
    body, final = http_get(UPSTREAM, 12)
    text = body.decode('utf-8', 'ignore')
    base = final.rsplit('/', 1)[0]
    segs = []
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s:
            continue
        if s.startswith('#'):
            out.append(s)
            continue
        full = s if s.startswith('http') else base + '/' + s
        segs.append(full)
        out.append('/seg?u=' + quote(full, safe=''))
    return '\n'.join(out) + '\n', segs


def prefetch_loop():
    """持续预取当前窗口内的分片，4 并发"""
    while True:
        try:
            _, segs = fetch_playlist()
            with lock:
                todo = [u for u in segs if u not in cache]
            if todo:
                with ThreadPoolExecutor(max_workers=4) as ex:
                    for url, res in zip(todo, ex.map(lambda u: _safe(u), todo)):
                        if res:
                            with lock:
                                cache[url] = res
                                order.append(url)
                                stats['downloaded'] += 1
                                stats['bytes'] += len(res)
                                # 只保留最近 12 个分片
                                while len(order) > 12:
                                    old = order.pop(0)
                                    cache.pop(old, None)
        except Exception as e:
            pass
        time.sleep(1.5)


def _safe(u):
    try:
        d, _ = http_get(u)
        return d
    except Exception:
        return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'

    def log_message(self, *a):
        pass

    def do_GET(self):
        p = urlparse(self.path)
        try:
            if p.path.startswith('/live.m3u8'):
                text, _ = fetch_playlist()
                body = text.encode('utf-8')
                self._send(body, 'application/vnd.apple.mpegurl')
            elif p.path.startswith('/seg'):
                qs = parse_qs(p.query)
                url = unquote(qs.get('u', [''])[0])
                body = None
                for _ in range(60):          # 最多等 30 秒
                    with lock:
                        body = cache.get(url)
                    if body:
                        stats['hit'] += 1
                        break
                    time.sleep(0.5)
                if body is None:
                    stats['miss'] += 1
                    body, _ = http_get(url)
                self._send(body, 'video/mp2t')
            elif p.path.startswith('/stat'):
                s = dict(stats)
                s['cache_items'] = len(cache)
                s['cache_mb'] = round(sum(len(v) for v in cache.values()) / 1048576, 1)
                import json
                self._send(json.dumps(s, ensure_ascii=False).encode(), 'application/json')
            else:
                self._send(b'not found', 'text/plain', 404)
        except Exception as e:
            try:
                self._send(str(e).encode(), 'text/plain', 500)
            except Exception:
                pass

    def _send(self, body, ctype, code=200):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(body)


if __name__ == '__main__':
    threading.Thread(target=prefetch_loop, daemon=True).start()
    print(f'proxy on :{PORT}  upstream={UPSTREAM}', flush=True)
    ThreadingHTTPServer(('0.0.0.0', PORT), Handler).serve_forever()
