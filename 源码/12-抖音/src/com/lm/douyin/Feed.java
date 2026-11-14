package com.lm.douyin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * B 站数据源 —— 只做两件事：拿热门列表、拿可播的 MP4 直链。
 *
 * 为什么用 B 站而不是抖音：
 *   抖音网页版要现代 JS 内核（本机是 Chrome33，跑不了），
 *   App 接口全部要 a_bogus/Argus 签名，纯 Java 复现不现实。
 *   B 站接口不需要签名，且热门流质量够高，做「刷」的体验完全够用。
 *
 * 注意：热门列表接口本身就带 cid，所以不必再请求 view 接口，省一次往返。
 */
public class Feed {

    static final String TAG = "Douyin";
    static final String REF = "https://www.bilibili.com/";

    public static class Video {
        public String bvid = "";
        public String cid = "";
        public String title = "";
        public String desc = "";
        public String author = "";
        public String cover = "";
        public int duration;            // 秒
        public long play;               // 播放量
        public long like;               // 点赞
        public long danmaku;            // 弹幕
        public int w, h;                // 原始分辨率
        public String mp4 = "";         // 上游 https 直链
        public String local = "";       // 本地代理地址（给 MediaPlayer）
        public boolean resolved;
    }

    static String get(String url) throws Exception {
        Net.Resp r = Net.open(url, REF, null);
        if (r.code < 200 || r.code >= 300) throw new Exception("HTTP " + r.code);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = r.body.read(b)) > 0) bo.write(b, 0, n);
        r.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    /** 热门列表，每页 20 条；page 从 1 开始 */
    public static List<Video> hot(int page) throws Exception {
        String u = "https://api.bilibili.com/x/web-interface/popular?ps=20&pn=" + page;
        JSONObject d = new JSONObject(get(u));
        if (d.optInt("code") != 0) throw new Exception("接口 code=" + d.optInt("code"));
        JSONArray arr = d.getJSONObject("data").getJSONArray("list");

        List<Video> out = new ArrayList<Video>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Video v = new Video();
            v.bvid = o.optString("bvid");
            v.cid = o.optString("cid");
            v.title = o.optString("title");
            v.desc = o.optString("desc");
            JSONObject ow = o.optJSONObject("owner");
            if (ow != null) v.author = ow.optString("name");
            v.cover = o.optString("pic");
            if (v.cover.startsWith("//")) v.cover = "http:" + v.cover;
            v.duration = o.optInt("duration");
            JSONObject st = o.optJSONObject("stat");
            if (st != null) {
                v.play = st.optLong("view");
                v.like = st.optLong("like");
                v.danmaku = st.optLong("danmaku");
            }
            JSONObject dim = o.optJSONObject("dimension");
            if (dim != null) {
                v.w = dim.optInt("width");
                v.h = dim.optInt("height");
            }
            if (v.bvid.length() > 0 && v.cid.length() > 0) out.add(v);
        }
        return out;
    }

    /**
     * 拿 MP4 直链并转成本地代理地址。
     * fnval=1 走「整段 MP4」而不是 DASH 分轨 —— 4.4 的播放器解不了 DASH。
     * qn=64 是未登录能达到的最高画质（720P）。
     */
    public static boolean resolve(Video v) {
        if (v == null) return false;
        if (v.resolved && v.local.length() > 0) return true;
        try {
            String u = "https://api.bilibili.com/x/player/playurl?bvid=" + v.bvid
                    + "&cid=" + v.cid + "&fnval=1&qn=64&fourk=1";
            JSONObject d = new JSONObject(get(u));
            if (d.optInt("code") != 0) {
                android.util.Log.w(TAG, "playurl code=" + d.optInt("code") + " " + v.bvid);
                return false;
            }
            JSONObject data = d.optJSONObject("data");
            if (data == null) return false;
            JSONArray du = data.optJSONArray("durl");
            if (du == null || du.length() == 0) return false;

            JSONObject first = du.getJSONObject(0);
            String url = first.optString("url");
            if (url == null || url.length() < 12) return false;

            v.mp4 = url;
            v.local = StreamProxy.local(url);
            v.resolved = true;
            android.util.Log.i(TAG, "resolve OK " + v.bvid
                    + " q=" + data.optInt("quality")
                    + " size=" + (first.optLong("size") / 1024) + "KB");
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "resolve fail " + v.bvid + " " + t);
            return false;
        }
    }

    /** 播放量格式化：12.3万 / 1.2亿 */
    public static String cnt(long n) {
        if (n >= 100000000L) return trim(n / 10000000L / 10.0) + "亿";
        if (n >= 10000L) return trim(n / 1000L / 10.0) + "万";
        return String.valueOf(n);
    }

    static String trim(double d) {
        String s = String.valueOf(Math.round(d * 10) / 10.0);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    /** 秒 → m:ss */
    public static String dur(int sec) {
        if (sec <= 0) return "";
        int m = sec / 60, s = sec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }
}
