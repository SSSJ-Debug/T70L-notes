package com.lm.music;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.SSLSocketFactory;

/**
 * 强制 IPv4 的极简 HTTP/1.1 客户端。
 *
 * ── 为什么非要自己写一个 ─────────────────────────────────────────
 * T70L 只有 IPv4 路由，但 Java 的 DNS 会把 AAAA 排在前面：
 *
 *   failed to connect to music-api.gdstudio.xyz/2606:4700:3034::6815:2194
 *   after 7000ms: connect failed: ENETUNREACH (Network is unreachable)
 *
 * 于是「网易云」整个音源在设备上全灭（同样的接口在 Mac 上是好的）。
 * 试过 System.setProperty("java.net.preferIPv4Stack","true")：在 4.4 的
 * libcore 上**完全不生效**，日志里依旧连 IPv6。
 *
 * 而 HttpURLConnection 没有给我们插手 TCP 连接的机会 —— 它自己 new Socket，
 * 我们只能通过 SSLSocketFactory 影响 TLS 那一层，影响不到连接哪一地址。
 *
 * 所以这里直接自己干：
 *   1. getAllByName 后挑 Inet4Address（ping 实测 A 记录都在）
 *   2. 连这个 IPv4
 *   3. 用 Http.factory() 在其上叠 TLS，**hostname 仍传域名** → SNI 正确
 *   4. 手写请求/响应，支持 Range / gzip / chunked / 跳转
 *
 * 体积代价约 8KB dex，换来的是整台机器的网络可用性 —— 值。
 */
public class Net {

    static final String TAG = "MUSIC";

    /** 一次响应（body 可能是 chunked/gzip 包装过的流，用完必须 close） */
    public static class Resp {
        public int code;
        public String contentType;
        public String contentRange;
        public String location;
        public long length = -1;
        public InputStream body;
        Socket sock;

        public void close() {
            try { if (body != null) body.close(); } catch (Throwable ignored) {}
            try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        }
    }

    /** 挑一个 IPv4；实在没有才退回第一条 */
    static InetAddress pickV4(String host) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (int i = 0; i < all.length; i++) {
            if (all[i] instanceof Inet4Address) return all[i];
        }
        if (all.length > 0) {
            android.util.Log.w(TAG, "只有 IPv6 地址，硬着头皮连: " + host);
            return all[0];
        }
        throw new IOException("DNS 解析不到: " + host);
    }

    /** 自动跳转（最多 5 跳） */
    public static Resp open(String url, String referer, String range) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 5; hop++) {
            Resp r = once(cur, referer, range);
            boolean redirect = r.code >= 300 && r.code < 400;
            if (redirect && r.location != null && r.location.length() > 0) {
                String next = abs(cur, r.location);
                android.util.Log.i(TAG, "跳转 " + r.code + " " + shortUrl(cur) + " → " + shortUrl(next));
                r.close();
                cur = next;
                continue;
            }
            return r;
        }
        throw new IOException("重定向过多");
    }

    static String abs(String base, String loc) throws Exception {
        if (loc.startsWith("http://") || loc.startsWith("https://")) return loc;
        java.net.URL b = new java.net.URL(base);
        if (loc.startsWith("//")) return b.getProtocol() + ":" + loc;
        if (loc.startsWith("/")) return b.getProtocol() + "://" + b.getAuthority() + loc;
        String p = b.getPath();
        int i = p.lastIndexOf('/');
        return b.getProtocol() + "://" + b.getAuthority()
                + (i >= 0 ? p.substring(0, i + 1) : "/") + loc;
    }

    static Resp once(String urlStr, String referer, String range) throws Exception {
        java.net.URL u = new java.net.URL(urlStr);
        boolean tls = "https".equalsIgnoreCase(u.getProtocol());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);

        InetAddress ip = pickV4(host);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(ip, port), 7000);
        raw.setSoTimeout(15000);
        raw.setTcpNoDelay(true);

        Socket s = raw;
        if (tls) {
            SSLSocketFactory f = Http.factory();
            if (f == null) throw new IOException("TLS 初始化失败");
            // host 传域名（不是 IP）→ SNI 与证书校验都用域名
            s = f.createSocket(raw, host, port, true);
        }

        String path = u.getFile();
        if (path == null || path.length() == 0) path = "/";

        StringBuilder sb = new StringBuilder(320);
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        sb.append("User-Agent: ").append(Http.UA).append("\r\n");
        sb.append("Accept: */*\r\n");
        sb.append("Accept-Language: zh-CN,zh;q=0.9\r\n");
        // ★ 不要 gzip。4.4 的 GZIPInputStream 叠在自写 chunked 解码之上时，
        //   一旦边界算错就报 EOFException，而且很难看出来。JSON 只有几十 KB，
        //   走 identity 反而更稳、更快；音频本身就是二进制不吃 gzip。
        sb.append("Accept-Encoding: identity\r\n");
        if (referer != null && referer.length() > 0)
            sb.append("Referer: ").append(referer).append("\r\n");
        if (Http.cookie != null && Http.cookie.length() > 0)
            sb.append("Cookie: ").append(Http.cookie).append("\r\n");
        if (range != null && range.length() > 0)
            sb.append("Range: ").append(range).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        s.getOutputStream().write(sb.toString().getBytes("UTF-8"));
        s.getOutputStream().flush();

        BufferedInputStream in = new BufferedInputStream(s.getInputStream(), 16384);
        Resp r = new Resp();
        r.sock = s;

        String status = readLine(in);
        if (status == null) throw new IOException("空响应: " + host);
        // "HTTP/1.1 200 OK" → 取第二段，只取到下一个空格（"200"），不能整段转数字
        int sp = status.indexOf(' ');
        if (sp > 0) {
            String rest = status.substring(sp + 1).trim();
            int sp2 = rest.indexOf(' ');
            String codeStr = sp2 > 0 ? rest.substring(0, sp2) : rest;
            r.code = (int) num(codeStr, 0);
        }
        if (r.code == 0) throw new IOException("响应头异常: " + status);

        boolean chunked = false, gzip = false;
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String k = line.substring(0, c).trim().toLowerCase(Locale.US);
            String v = line.substring(c + 1).trim();
            if (k.equals("content-length")) r.length = num(v, -1);
            else if (k.equals("content-type")) r.contentType = v;
            else if (k.equals("content-range")) r.contentRange = v;
            else if (k.equals("location")) r.location = v;
            else if (k.equals("transfer-encoding") && v.toLowerCase(Locale.US).contains("chunked")) chunked = true;
            else if (k.equals("content-encoding") && v.toLowerCase(Locale.US).contains("gzip")) gzip = true;
        }

        InputStream body = in;
        if (chunked) body = new Chunked(body);
        if (gzip) {
            try { body = new GZIPInputStream(body); } catch (Throwable ignored) {}
        }
        r.body = body;
        android.util.Log.i(TAG, "net " + shortUrl(urlStr) + " → " + r.code
                + " len=" + r.length + (chunked ? " chunked" : "") + (gzip ? " gzip" : ""));
        return r;
    }

    static String shortUrl(String u) {
        try {
            java.net.URL x = new java.net.URL(u);
            String p = x.getPath();
            if (p != null && p.length() > 26) p = p.substring(0, 26) + "…";
            return x.getHost() + p;
        } catch (Throwable e) {
            return u;
        }
    }

    // ───────────────────────── 便捷入口 ─────────────────────────

    public static byte[] bytes(String url, String referer) throws Exception {
        Resp r = open(url, referer, null);
        try {
            if (r.code < 200 || r.code >= 300) throw new IOException("HTTP " + r.code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(32768);
            byte[] buf = new byte[8192];
            int n;
            while ((n = r.body.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            r.close();
        }
    }

    public static String get(String url, String referer) throws Exception {
        byte[] b = bytes(url, referer);
        return b == null ? null : new String(b, "UTF-8").trim();
    }

    // ───────────────────────── 底层小工具 ─────────────────────────

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    static long num(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Throwable e) { return def; }
    }

    /** chunked 的长度行是 16 进制，不能走 num() */
    static long numHex(String s) {
        try { return Long.parseLong(s.trim(), 16); } catch (Throwable e) { return -1; }
    }

    /** chunked 传输解码：长度行是 16 进制，可能带 ";ext" */
    static class Chunked extends InputStream {
        final InputStream in;
        long left;
        boolean eof;
        boolean first = true;

        Chunked(InputStream i) { in = i; }

        boolean nextChunk() throws IOException {
            // ★ 每块数据后面还跟着一个 CRLF，必须先吃掉再读长度行。
            //   漏了这一步，下一次 readLine 读到的是那个空行，被当成长度 0，
            //   流就提前结束（表现为 gzip 被截断 → EOFException）。
            if (!first) readLine(in);
            first = false;
            String l = readLine(in);
            if (l == null) { eof = true; return false; }
            l = l.trim();
            int sc = l.indexOf(';');
            if (sc >= 0) l = l.substring(0, sc).trim();
            if (l.length() == 0) { eof = true; return false; }
            // ★ 长度是 16 进制！之前误用 Long.parseLong(s) 按 10 进制解析，
            //   "1a2b" 直接抛异常被吞成 0 → 当成长度 0 → 流立刻结束 → body 空。
            //   网易走的是 Content-Length 所以没事，B 站一直是 chunked，全废在这。
            left = numHex(l);
            if (left <= 0) { eof = true; return false; }
            return true;
        }

        public int read() throws IOException {
            if (eof) return -1;
            if (left == 0 && !nextChunk()) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (left == 0 && !nextChunk()) return -1;
            int ask = (int) Math.min(len, left);
            int n = in.read(b, off, ask);
            if (n > 0) left -= n;
            return n;
        }
    }
}
