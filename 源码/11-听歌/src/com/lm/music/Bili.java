package com.lm.music;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B 站公开接口的最小封装：搜索视频 → 取 cid → 取纯音频流。
 *
 * 为什么走这条路：
 *   1. 个人开发者拿不到任何正版曲库授权，"搜任意歌曲"只有这条路能覆盖到；
 *   2. 只要音频轨（dash.audio），不解码视频，1G 内存的盒子压力最小；
 *   3. 全部是公开接口、公开内容，不碰大会员/付费内容。
 *
 * 注意：B 站 2023 年起搜索接口要 WBI 签名（w_rid），签名算法见 sign()。
 * 签名不合法会返回 412 风控页，表现是"返回一段 HTML"，这里会当失败处理。
 */
public class Bili {

    static final String TAG = "MUSIC";
    static final String UA = "Mozilla/5.0 (Linux; Android 4.4.4) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/60.0 Safari/537.36";

    /** WBI 混淆表 / cookie / mixinKey 已随 WBI 方案一起删除（见下方说明） */

    public static class Song {
        public String title = "";
        public String author = "";
        public String bvid = "";
        public String cover = "";
        public int seconds = 0;
        public String audioUrl;       // 取到之后缓存，重复播放不用再签一次

        public String time() {
            int m = seconds / 60, s = seconds % 60;
            return m + ":" + (s < 10 ? "0" : "") + s;
        }
    }

    // ───────────────────────── HTTP ─────────────────────────

    /** 统一走 Http（自带 TLS1.2 + 新根证书兼容，4.4 才连得上） */
    static byte[] http(String url) throws Exception {
        return Http.bytes(url, "https://www.bilibili.com/");
    }

    static JSONObject json(String url) throws Exception {
        byte[] d = http(url);
        if (d == null) throw new Exception("网络无响应");
        String s = new String(d, "UTF-8").trim();
        if (!s.startsWith("{")) throw new Exception("接口返回异常（可能被风控）");
        return new JSONObject(s);
    }

    // ───────────────────── 关于 WBI 签名（已废弃，留个记录） ─────────────────────
    // 早期版本走 /x/web-interface/wbi/search/type，要自己算 w_rid。
    // 2026-11-07 实测：这条路在本机被风控 —— 返回
    //   {"data":{"v_voucher":"voucher_xxx"},"message":"OK","code":0}
    // code 是 0、message 是 OK，但 result 里一条都没有，最容易被误判成"搜不到"。
    // 我把设备真实请求的 URL 拿到 Mac 上逐字节验算过：w_rid 与官方算法完全一致，
    // 用同一条 URL 回放照样 v_voucher —— 说明不是签名错，是"这个身份被判成机器人"。
    // 换 /x/web-interface/search/all/v2 后：不需要任何签名、不风控、返回 1000 条。
    // 于是整套 WBI 代码（混淆表 / md5 / 拼串）全部删掉，反而更小更快。

    // ───────────────────────── 业务 ─────────────────────────

    /** 搜视频，按"像不像一首歌"粗排：标题里含关键词、时长 1~15 分钟优先 */
    public static List<Song> search(String keyword, int page) throws Exception {
        // ★ 走 all/v2：不用签名、不风控。
        //   返回结构是「按类型分块」——result: [{result_type:"video", data:[...]}, ...]
        String u = "https://api.bilibili.com/x/web-interface/search/all/v2?keyword="
                + URLEncoder.encode(keyword, "UTF-8") + "&page=" + page;
        JSONObject o = json(u);
        if (o.optInt("code", -1) != 0) throw new Exception("搜索失败：" + o.optString("message"));

        JSONArray blocks = o.getJSONObject("data").optJSONArray("result");
        JSONArray arr = null;
        if (blocks != null) {
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject blk = blocks.optJSONObject(i);
                if (blk != null && "video".equals(blk.optString("result_type"))) {
                    arr = blk.optJSONArray("data");
                    break;
                }
            }
        }
        List<Song> out = new ArrayList<Song>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.optJSONObject(i);
            if (v == null) continue;
            Song s = new Song();
            String t = strip(v.optString("title"));
            String a = v.optString("author");
            // 标题通常是「【4K修复】周杰伦 - 晴天」，拆一下更像首歌
            String[] split = splitTitle(t);
            s.title = split[0];
            s.author = split[1].length() > 0 ? split[1] : a;
            s.bvid = v.optString("bvid");
            String pic = v.optString("pic");
            // 封面一律走 http：hdslb 的 TLS 在 4.4 上不一定谈得拢，
            // 图片是公开资源，走明文反而更稳（配合 Referer 即可）。
            if (pic.startsWith("//")) pic = "http:" + pic;
            else if (pic.startsWith("https://")) pic = "http://" + pic.substring(8);
            s.cover = pic;
            s.seconds = parseDur(v.optString("duration"));
            if (s.bvid.length() == 0) continue;
            out.add(s);
        }
        // 排序：时长 45 秒 ~ 15 分钟的排前面（短视频片段/超长合集靠后）
        Collections.sort(out, new java.util.Comparator<Song>() {
            public int compare(Song a, Song b) {
                return score(a) - score(b);
            }
        });
        return out;
    }

    static int score(Song s) {
        int r = 0;
        if (s.seconds < 45) r += 40;
        if (s.seconds > 900) r += 30;
        if (s.title.contains("合集") || s.title.contains("串烧")) r += 25;
        return r;
    }

    static String strip(String s) {
        return s.replace("<em class=\"keyword\">", "").replace("</em>", "")
                .replace("&quot;", "\"").replace("&amp;", "&");
    }

    /**
     * B 站视频标题拆成「歌名 + 歌手」：
     *   「【4K修复】周杰伦 - 晴天」   → 晴天 / 周杰伦
     *   「周杰伦——《晴天》」          → 晴天 / 周杰伦
     *   「晴天 周杰伦」               → 晴天 周杰伦 / （空，用 UP 主兜底）
     */
    static String[] splitTitle(String t) {
        if (t == null) return new String[]{"", ""};
        String s = t.trim();
        // 剥掉开头的【4K修复】【Hi-Res】(官方) 这类前缀，最多三层
        for (int i = 0; i < 4; i++) {
            String ns = s.replaceFirst("^[\\s【\\[（(][^】\\]）)]{0,18}[】\\]）)]\\s*", "");
            ns = ns.replaceFirst("^[\\s|丨\\-—·]+", "");
            if (ns.equals(s)) break;
            s = ns;
        }
        s = s.replace("《", "").replace("》", "").replace("「", "").replace("」", "").trim();

        String[] out = new String[]{s, ""};
        int i = s.indexOf(" - ");
        int cut = 3;
        if (i < 0) { i = s.indexOf("－"); cut = 1; }
        if (i < 0) { i = s.indexOf("—"); cut = 1; }
        if (i < 0) { i = s.indexOf(" – "); cut = 3; }
        if (i > 0 && i + cut < s.length()) {
            String left = s.substring(0, i).trim();
            String right = s.substring(i + cut).trim();
            if (right.length() > 0 && left.length() > 0 && left.length() <= 24) {
                out[0] = right;
                out[1] = left;
            }
        }
        return out;
    }

    static int parseDur(String d) {
        try {
            String[] p = d.split(":");
            if (p.length == 2) return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600
                    + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
        } catch (Exception ignored) {}
        return 0;
    }

    /** 取最高码率的那条纯音频轨 */
    public static String audioUrl(String bvid) throws Exception {
        JSONObject view = json("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid);
        if (view.optInt("code", -1) != 0) throw new Exception("取视频信息失败");
        JSONObject vd = view.getJSONObject("data");
        long cid = vd.getLong("cid");

        // ★ 重试取流：playurl 每次分配的 CDN 节点是随机的，
        //   实测 mcdn 系列能出数据，upos/cos/hw 这些节点会 403。
        //   一次不行就再要一次，命中能用的节点就停。
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String u = playurlOnce(bvid, cid);
                if (u != null) return u;
            } catch (Throwable e) {
                last = new Exception(String.valueOf(e.getMessage()));
                Log.w(TAG, "取流第 " + (attempt + 1) + " 次失败: " + e);
            }
        }
        throw last == null ? new Exception("取不到能播的音频地址") : last;
    }

    /** 取一次 playurl，把 base + backup 全部探活，返回第一个能出数据的 */
    static String playurlOnce(String bvid, long cid) throws Exception {
        JSONObject pu = json("https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                + "&cid=" + cid + "&fnval=16&fourk=0");
        if (pu.optInt("code", -1) != 0) throw new Exception("取音频地址失败：" + pu.optString("message"));
        JSONObject data = pu.getJSONObject("data");
        if (data.isNull("dash")) throw new Exception("这条没开放音频轨");
        JSONArray au = data.getJSONObject("dash").getJSONArray("audio");
        // 只选 AAC 轨（30280=192k / 30232=132k / 30216=64k）。
        // id=30250 是 Hi-Res（FLAC），4.4 的解码器不支持，必须排除。
        JSONObject best = null;
        for (int i = 0; i < au.length(); i++) {
            JSONObject a = au.getJSONObject(i);
            int id = a.optInt("id", 0);
            if (id == 30250) continue;
            if (best == null || a.optInt("bandwidth") > best.optInt("bandwidth")) best = a;
        }
        if (best == null && au.length() > 0) best = au.getJSONObject(0);
        if (best == null) throw new Exception("这首歌没有音频轨");

        // baseUrl + backupUrl[] 指向不同 CDN 节点，逐个探活
        java.util.ArrayList<String> cand = new java.util.ArrayList<String>();
        cand.add(best.optString("baseUrl"));
        JSONArray bk = best.optJSONArray("backupUrl");
        if (bk != null) {
            for (int i = 0; i < bk.length(); i++) {
                String bu = bk.optString(i);
                if (bu != null && bu.length() > 10) cand.add(bu);
            }
        }
        return pickPlayable(cand);
    }

    /** 依次探活，返回第一个能出数据(200/206)的地址；全挂返回 null 让上层重试 */
    static String pickPlayable(java.util.List<String> urls) {
        for (int i = 0; i < urls.size(); i++) {
            String u = urls.get(i);
            if (u == null || u.length() < 10) continue;
            Net.Resp r = null;
            try {
                r = Net.open(u, "https://www.bilibili.com/", "bytes=0-1023");
                if (r.code == 200 || r.code == 206) {
                    Log.i(TAG, "音频节点 #" + i + " 可用 " + u.split("/")[2]);
                    return u;
                }
                Log.w(TAG, "音频节点 #" + i + " → " + r.code + " " + u.split("/")[2]);
            } catch (Throwable e) {
                Log.w(TAG, "音频节点 #" + i + " 失败 " + e);
            } finally {
                if (r != null) r.close();
            }
        }
        return null;
    }

    /**
     * 兜底：拿整段 MP4（fnval=1）里的音轨。
     *
     * DASH 的纯音频是 fragmented MP4，4.4 的 NuPlayer 一般吃得住，但不是所有片子都行。
     * 万一播不动，退回完整 MP4 至少能出声——代价是 MediaPlayer 得顺带解一遍视频帧，
     * A83T 会有点吃力，所以只在纯音频失败时才走这里。
     */
    public static String mp4Url(String bvid) throws Exception {
        JSONObject view = json("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid);
        if (view.optInt("code", -1) != 0) throw new Exception("取视频信息失败");
        long cid = view.getJSONObject("data").getLong("cid");
        JSONObject pu = json("https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                + "&cid=" + cid + "&fnval=1&fourk=0");
        if (pu.optInt("code", -1) != 0) throw new Exception("取视频地址失败");
        JSONObject d = pu.getJSONObject("data");
        JSONArray durl = d.optJSONArray("durl");
        if (durl == null || durl.length() == 0) throw new Exception("没有可播放地址");
        return durl.getJSONObject(0).getString("url");
    }
}
