package com.lm.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * B 站接口层（零第三方依赖）。
 *
 * 关键坑（均实测踩过）：
 *  1. Android 4.4 的 HttpsURLConnection 默认协商 TLSv1，B 站要求 TLSv1.2+，
 *     必须显式建立 TLSv1.2 的 SSLContext，否则 handshake 直接失败。
 *  2. 4.4 内置 CA 库是 2013 年的，用 trust-all 规避根证书轮换问题。
 *  3. 不带 buvid3 Cookie 直接请求排行榜会返回 code=-352（风控拦截）。
 *     必须先打 /x/frontend/finger/spi 取得 b_3/b_4 作为设备指纹。
 *  4. UA 用老手机 UA 更容易被风控，改用桌面 Chrome UA。
 */
public class BiliApi {

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final String ORIGIN = "https://www.bilibili.com";

    /** 一条视频的展示信息 */
    public static class Video {
        public String bvid = "";
        public long cid = 0;
        public String title = "";
        public String pic = "";
        public String author = "";
        public long play = 0;
        public long duration = 0;
        public String desc = "";

        public String playText() {
            if (play >= 100000000L) return String.format("%.1f亿", play / 100000000.0);
            if (play >= 10000L) return String.format("%.1f万", play / 10000.0);
            return String.valueOf(play);
        }

        public String durText() {
            long m = duration / 60;
            long s = duration % 60;
            return String.format("%d:%02d", m, s);
        }
    }

    /** 分区表：tab 名称 + 排行榜 rid（全部实测 code=0，2026-11-05） */
    public static final String[] TAB_NAMES = {
            "全站", "动画", "音乐", "游戏", "影视", "科技", "鬼畜", "舞蹈"
    };
    public static final int[] TAB_RIDS = {
            0, 1, 3, 4, 181, 188, 119, 129
    };

    private static boolean sTlsReady = false;
    private static String sCookie = null;

    private static synchronized void initTls() {
        if (sTlsReady) return;
        try {
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) { return true; }
            });
            sTlsReady = true;
        } catch (Throwable ignored) {
        }
    }

    /** 取 buvid3/buvid4 设备指纹，拿不到就让请求裸奔（可能 -352） */
    private static synchronized String ensureCookie() {
        if (sCookie != null && sCookie.length() > 0) return sCookie;
        try {
            String body = httpGetRaw("https://api.bilibili.com/x/frontend/finger/spi", null);
            JSONObject root = new JSONObject(body);
            JSONObject d = root.optJSONObject("data");
            if (d != null) {
                String b3 = d.optString("b_3");
                String b4 = d.optString("b_4");
                if (b3.length() > 0) {
                    sCookie = "buvid3=" + b3 + "; buvid4=" + b4;
                }
            }
        } catch (Throwable ignored) {
        }
        return sCookie;
    }

    private static String httpGetRaw(String url, String cookie) throws Exception {
        initTls();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(18000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("Origin", ORIGIN);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (cookie != null && cookie.length() > 0) {
                conn.setRequestProperty("Cookie", cookie);
            }
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new Exception("HTTP " + code + " 无响应体");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String s = new String(bos.toByteArray(), "UTF-8");
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code + ": " + trim(s));
            return s;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 统一入口：遇到 -352（风控）或 -400（偶发请求异常）就丢掉旧指纹重取一次 */
    private static JSONObject getJson(String url) throws Exception {
        String cookie = ensureCookie();
        JSONObject root = new JSONObject(httpGetRaw(url, cookie));
        int code = root.optInt("code", 0);
        if (code == -352 || code == -400) {
            synchronized (BiliApi.class) {
                sCookie = null;
            }
            root = new JSONObject(httpGetRaw(url, ensureCookie()));
        }
        return root;
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    /**
     * 拉取分区排行榜。该接口无需登录、无需 wbi 签名，返回 100 条。
     */
    public static List<Video> ranking(int rid) throws Exception {
        String url = "https://api.bilibili.com/x/web-interface/ranking/v2?rid=" + rid + "&type=all";
        JSONObject root = getJson(url);
        int code = root.optInt("code", -1);
        if (code != 0) {
            String msg = root.optString("message");
            if (code == -352) msg = "被B站风控拦截，请点重新加载";
            throw new Exception("接口返回 code=" + code + " " + msg);
        }
        JSONObject data = root.optJSONObject("data");
        JSONArray list = (data == null) ? null : data.optJSONArray("list");
        List<Video> out = new ArrayList<Video>();
        if (list == null) return out;

        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            Video v = new Video();
            v.bvid = o.optString("bvid");
            v.cid = o.optLong("cid");
            v.title = o.optString("title");
            v.pic = o.optString("pic");
            if (v.pic.startsWith("http://")) v.pic = "https://" + v.pic.substring(7);
            JSONObject owner = o.optJSONObject("owner");
            v.author = (owner == null) ? "" : owner.optString("name");
            JSONObject stat = o.optJSONObject("stat");
            v.play = (stat == null) ? 0 : stat.optLong("view");
            v.duration = o.optLong("duration");
            v.desc = o.optString("desc");
            if (v.bvid.length() > 0) out.add(v);
        }
        return out;
    }

    /**
     * 取可直接播放的 mp4 直链。
     *
     * platform=html5 + qn=16 + fnval=0 时，B 站返回 durl 数组，
     * 也就是一个完整 mp4（非 DASH 分段），系统 MediaPlayer 可直接播放，
     * 不需要任何解封装逻辑。未登录最高 360P，1280x800 屏足够。
     */
    public static String playUrl(String bvid, long cid) throws Exception {
        String url = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                + "&cid=" + cid
                + "&qn=16&fnval=0&fnver=0&fourk=0&platform=html5&high_quality=1";
        JSONObject root = getJson(url);
        int code = root.optInt("code", -1);
        if (code != 0) {
            throw new Exception("取播放地址失败 code=" + code + " " + root.optString("message"));
        }
        JSONObject data = root.optJSONObject("data");
        JSONArray durl = (data == null) ? null : data.optJSONArray("durl");
        if (durl == null || durl.length() == 0) throw new Exception("该视频没有可播放的直链");
        JSONObject first = durl.optJSONObject(0);
        String u = (first == null) ? "" : first.optString("url");
        if (u == null || u.length() == 0) throw new Exception("直链为空");
        return u;
    }
}
