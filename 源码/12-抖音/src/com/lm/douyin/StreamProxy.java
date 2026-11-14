package com.lm.douyin;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 本地音频流代理 —— 解决 Android 4.4 媒体栈拉不了 HTTPS 的问题。
 *
 * B 站 DASH 音频轨的 baseUrl 全是 https，而 4.4 的 NuPlayer 没有可用的 TLS，
 * 直接 setDataSource 会报 1/-1004（IO 错误）。
 * 做法：应用内 Java（TLS 可用，搜索接口就是这么通的）把音频拉回来，
 * 用明文 http://127.0.0.1:18777 喂给 MediaPlayer。
 *
 * 支持 Range 透传（进度条可拖动）；每个连接一个线程，用完即关。
 */
public class StreamProxy {

    static final String TAG = "Douyin";
    static final int PORT = 18778;

    static ServerSocket ss;
    static volatile boolean running;

    public static synchronized void start() {
        if (running) return;
        try {
            ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
            running = true;
            Thread t = new Thread(new Runnable() {
                public void run() { loop(); }
            }, "dy-proxy");
            t.setDaemon(true);
            t.start();
            Log.i(TAG, "代理就绪 :" + PORT);
        } catch (Throwable e) {
            Log.w(TAG, "代理启动失败 " + e);
        }
    }

    /** 把 https 音频地址换成给 MediaPlayer 用的本地地址 */
    public static String local(String httpsUrl) throws Exception {
        start();
        if (!running) return httpsUrl;      // 兜底：起不来就直连（失败会被 onError 接住）
        return "http://127.0.0.1:" + PORT + "/a?u="
                + URLEncoder.encode(httpsUrl, "UTF-8");
    }

    /** 各音源对 Referer 的要求不一样，按域名给 */
    static String refererOf(String url) {
        if (url == null) return null;
        // ★ 必须带 bilivideo / mcdn：B 站的音频 CDN 域名是 mcdn.bilivideo.cn，
        //   不含 "bilibili"，之前漏了这两个导致所有音频请求 403 Forbidden。
        if (url.contains("bilibili") || url.contains("hdslb")
                || url.contains("bilivideo") || url.contains("mcdn"))
            return "https://www.bilibili.com/";
        if (url.contains("126.net") || url.contains("music.163")) return "https://music.163.com/";
        if (url.contains("kuwo")) return "https://www.kuwo.cn/";
        return null;
    }

    static void loop() {
        while (running) {
            try {
                final Socket sock = ss.accept();
                Thread h = new Thread(new Runnable() {
                    public void run() { serve(sock); }
                }, "dy-proxy-conn");
                h.setDaemon(true);
                h.start();
            } catch (Throwable e) {
                if (running) Log.w(TAG, "accept " + e);
            }
        }
    }

    /** GET /a?u=<urlencode(https url)>，Range 原样透传 */
    static void serve(Socket sock) {
        HttpURLConnection c = null;
        try {
            sock.setSoTimeout(30000);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(sock.getInputStream()), 8192);

            // ★ 请求行必须单独存。之前的写法是 String line = r.readLine();
            //   紧接着 while ((line = r.readLine()) ...) 把请求行覆盖成了空串，
            //   后面再按 "GET /a?u=xxx HTTP/1.1" 解析必然失败 —— 日志里是
            //   "代理收到异常请求行: "（空），MediaPlayer 拿到空响应就报 -1004。
            String reqLine = r.readLine();
            if (reqLine == null || reqLine.length() == 0) { sock.close(); return; }

            String target = null, range = null, line;
            while ((line = r.readLine()) != null && line.length() > 0) {
                String low = line.toLowerCase();
                if (low.startsWith("range:")) range = line.substring(6).trim();
            }
            int sp1 = reqLine.indexOf(' '), sp2 = reqLine.lastIndexOf(' ');
            if (sp1 < 0 || sp2 <= sp1) {
                Log.w(TAG, "代理收到异常请求行: " + reqLine);
                sock.close();
                return;
            }
            String path = reqLine.substring(sp1 + 1, sp2);
            int uq = path.indexOf("u=");
            if (uq < 0) {
                Log.w(TAG, "代理请求缺参数: " + path);
                sock.close();
                return;
            }
            target = java.net.URLDecoder.decode(path.substring(uq + 2), "UTF-8");
            if (target == null || target.length() < 10) { sock.close(); return; }

            // 上游也走 Net（强制 IPv4）；B 站 CDN 同样有 AAAA 优先的毛病
            Net.Resp up = Net.open(target, refererOf(target), range);
            int code = up.code;
            if (code < 200 || code >= 400) {
                String err = "";
                try {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] bb = new byte[512];
                    int n1, tot = 0;
                    while (tot < 400 && (n1 = up.body.read(bb)) > 0) { bo.write(bb, 0, n1); tot += n1; }
                    err = new String(bo.toByteArray(), "UTF-8");
                } catch (Throwable ignored) {}
                Log.w(TAG, "代理上游 " + code + " " + target);
                Log.w(TAG, "上游返回体: " + err.replace('\n', ' ').substring(0, Math.min(300, err.length())));
                up.close();
                sock.close();
                return;
            }

            String ct = up.contentType;
            String cr = up.contentRange;
            OutputStream os = sock.getOutputStream();
            StringBuilder hd = new StringBuilder();
            hd.append("HTTP/1.0 ").append(code)
              .append(code == 206 ? " Partial Content" : " OK").append("\r\n");
            hd.append("Content-Type: ").append(ct == null ? "video/mp4" : ct).append("\r\n");
            if (up.length >= 0) hd.append("Content-Length: ").append(up.length).append("\r\n");
            if (cr != null) hd.append("Content-Range: ").append(cr).append("\r\n");
            hd.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
            os.write(hd.toString().getBytes("UTF-8"));
            Log.i(TAG, "代理开始 " + code + " " + up.length + "B ct=" + ct + " range=" + range);

            InputStream is = up.body;
            byte[] buf = new byte[16384];
            int n, total = 0;
            while (running && (n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
            }
            os.flush();
            up.close();
            Log.i(TAG, "代理送完 " + (total / 1024) + "KB");
        } catch (Throwable e) {
            // 播放器 seek/断开是正常现象，不打日志
            String m = String.valueOf(e);
            if (!m.contains("Broken pipe") && !m.contains("Socket closed")
                    && !m.contains("Connection reset")) {
                Log.w(TAG, "代理 " + m);
            }
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
            try { sock.close(); } catch (Exception ignored) {}
        }
    }
}
