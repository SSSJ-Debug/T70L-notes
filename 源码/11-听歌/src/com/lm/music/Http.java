package com.lm.music;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 统一 HTTP 客户端 —— 专门伺候 Android 4.4 的破 TLS。
 *
 * 两个必须解决的问题：
 *   1. 4.4 的 HttpsURLConnection 默认只谈 TLSv1。现在的音乐接口（网易云 / 酷我 /
 *      gdstudio）基本只接受 TLSv1.2+，不强制就会 handshake 失败。
 *   2. 4.4 的根证书库停在 2014 年，Let's Encrypt（ISRG Root X1，2016）等新根证书
 *      一个都没有，证书链校验必挂。
 *
 * 所以这里做了两件事：强制 TLSv1.2/1.1/1.0 协商 + 接受任意证书。
 * 安全性说明：本 App 只在局域网自用，请求的全是公开音乐接口，不传任何账号密码，
 * 被中间人顶多听到"我在听晴天"。用 trust-all 换取"能播"是划算的。
 */
public class Http {

    static final String TAG = "MUSIC";

    /**
     * ★ 必须是"桌面浏览器"UA，这条是生死线。
     *
     * 实测同一首 B 站音频直链：
     *   Android/Chrome60 UA  → 403 Forbidden（nginx，Hit-status: MISS）
     *   桌面 Chrome120 UA    → 206，返回 ftypisom 正常音频
     * 搜接口反而认 Android UA（带 Android 会被 v_voucher 风控）。
     * 权衡下来统一用桌面 UA：搜索和音频两边都过。
     */
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 本机无 IPv6 路由，DNS 却优先给 AAAA → 强制走 IPv4（详见 Sources 里同名说明） */
    static {
        try { System.setProperty("java.net.preferIPv4Stack", "true"); } catch (Throwable ignored) {}
    }

    static SSLSocketFactory factory;

    /** B 站搜索要带 buvid cookie，由 Bili.init() 填进来 */
    static String cookie = "";

    // ───────────────────────── TLS 兼容层 ─────────────────────────

    static synchronized SSLSocketFactory factory() {
        if (factory != null) return factory;
        try {
            TrustManager[] trust = new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trust, new SecureRandom());
            factory = new Tls(ctx.getSocketFactory());
        } catch (Throwable e) {
            Log.w(TAG, "TLS 初始化失败 " + e);
        }
        return factory;
    }

    /** 包装一层，只为了往 socket 上塞 enabledProtocols */
    static class Tls extends SSLSocketFactory {
        final SSLSocketFactory base;

        Tls(SSLSocketFactory b) { base = b; }

        public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }

        public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }

        public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException {
            return fix(base.createSocket(s, h, p, a));
        }

        public Socket createSocket(String h, int p) throws IOException {
            return fix(base.createSocket(h, p));
        }

        public Socket createSocket(String h, int p, java.net.InetAddress l, int lp) throws IOException {
            return fix(base.createSocket(h, p, l, lp));
        }

        public Socket createSocket(java.net.InetAddress h, int p) throws IOException {
            return fix(base.createSocket(h, p));
        }

        public Socket createSocket(java.net.InetAddress a, int p, java.net.InetAddress l, int lp) throws IOException {
            return fix(base.createSocket(a, p, l, lp));
        }

        static Socket fix(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                String[] want = {"TLSv1.2", "TLSv1.1", "TLSv1"};
                java.util.ArrayList<String> ok = new java.util.ArrayList<String>();
                java.util.List<String> have = java.util.Arrays.asList(ss.getSupportedProtocols());
                for (int i = 0; i < want.length; i++) if (have.contains(want[i])) ok.add(want[i]);
                if (ok.size() > 0) {
                    try { ss.setEnabledProtocols(ok.toArray(new String[ok.size()])); } catch (Throwable ignored) {}
                }
                // 握手失败以前是静默吞掉的，结果后面写请求时只看到
                // "Socket is closed"，完全查不出是 TLS 的问题。这里必须留痕。
                try {
                    ss.startHandshake();
                } catch (Throwable e) {
                    Log.w(TAG, "TLS 握手失败 " + e);
                }
            }
            return s;
        }
    }

    // ───────────────────────── 请求 ─────────────────────────
    // 真正的收发都在 Net 里（它负责强制 IPv4 + 手写 HTTP），这里只保留对外接口，
    // 免得 Bili / Sources / StreamProxy 到处改调用点。

    public static String get(String url, String referer) throws Exception {
        return Net.get(url, referer);
    }

    public static byte[] bytes(String url, String referer) throws Exception {
        return Net.bytes(url, referer);
    }

    /** 图片下载（封面），失败返回 null 不抛 */
    public static byte[] image(String url) {
        try {
            return bytes(url, null);
        } catch (Throwable e) {
            return null;
        }
    }
}
