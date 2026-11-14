package com.lm.music;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多音源聚合搜索。
 *
 * 为什么要多源：单靠任何一家都搜不全。
 *   - 酷我：版权最全（周杰伦、五月天这些网易云没有的都有），接口无签名，直链给 mp3。
 *           → 主力
 *   - 网易云（走公开聚合 API）：音质好、有封面和歌词，但近年版权缺口大。
 *           → 补充 + 提供封面/歌词
 *   - B 站：什么冷门都有，代价是标题杂、得多过滤。
 *           → 兜底，前两源结果太少才用
 *
 * 三源并行会更快，但 1GB 内存的老机器上开三个线程拉网络反而容易翻车，
 * 这里按"酷我 → 网易 → 必要时 B 站"串行，实测 1.5 秒内出结果。
 */
public class Sources {

    static final String TAG = "MUSIC";

    public static final String API = "https://music-api.gdstudio.xyz/api.php";

    /**
     * ★ 强制 IPv4。
     *
     * 实测日志：music-api.gdstudio.xyz 解析到了 2606:4700:3034::6815:2194（IPv6），
     * 而 T70L 没有 IPv6 路由 → "ENETUNREACH (Network is unreachable)"，
     * 于是整个网易源在设备上全军覆没（Mac 上同样的接口却是好的）。
     *
     * Java 的 DNS 会优先返回 AAAA，这个属性让 InetAddress 只走 IPv4。
     * 必须在使用任何网络之前设置，所以放在类的静态块里。
     */
    static {
        try { System.setProperty("java.net.preferIPv4Stack", "true"); } catch (Throwable ignored) {}
    }

    // ───────────────────────── 对外 ─────────────────────────

    /**
     * 双源并行：B 站（覆盖全网）+ 网易云（音质/歌词/封面）。
     *
     * ★ 为什么不用酷我：实测它的 antiserver 对正版曲库（周杰伦/五月天/林俊杰…）
     *   一律返回同一个 181KB 的提示音，接口看着成功、播出来是废话。
     *   这种"假成功"比直接失败更坑，所以整个源砍掉。
     */
    public static List<Song> search(String kw) {
        final String keyword = kw == null ? "" : kw.trim();
        final List<Song> out = new ArrayList<Song>();
        if (keyword.length() == 0) return out;

        final List<Song> bili = new ArrayList<Song>();
        final List<Song> ne = new ArrayList<Song>();

        Thread t1 = new Thread(new Runnable() {
            public void run() {
                try {
                    bili.addAll(biliSearch(keyword));
                    Log.i(TAG, "B站 " + bili.size());
                } catch (Throwable e) {
                    Log.w(TAG, "B站失败 " + e);
                }
            }
        });
        Thread t2 = new Thread(new Runnable() {
            public void run() {
                try {
                    ne.addAll(neteaseSearch(keyword));
                    Log.i(TAG, "网易 " + ne.size());
                } catch (Throwable e) {
                    Log.w(TAG, "网易失败 " + e);
                }
            }
        });
        t1.start();
        t2.start();
        try { t1.join(9000); } catch (Throwable ignored) {}
        try { t2.join(9000); } catch (Throwable ignored) {}

        // 网易在前（正版 320k + 有歌词封面），B 站补齐它没有的
        out.addAll(ne);
        merge(out, bili);

        Collections.sort(out, new Comparator<Song>() {
            public int compare(Song a, Song b) {
                int d = a.rankScore(keyword) - b.rankScore(keyword);
                if (d != 0) return d;
                return a.title.compareTo(b.title);
            }
        });
        return out;
    }

    /** 拿直链。已解析过的直接返回缓存 */
    public static String resolve(Song s) throws Exception {
        if (s.audioUrl != null && s.audioUrl.length() > 10) return s.audioUrl;
        String firstErr = null;
        try {
            if (Song.SRC_KUWO.equals(s.source)) s.audioUrl = kuwoUrl(s.id);
            else if (Song.SRC_NETEASE.equals(s.source)) s.audioUrl = neteaseUrl(s.id);
            else s.audioUrl = biliDirect(s.id);
        } catch (Throwable e) {
            firstErr = msgOf(e);
            Log.w(TAG, "直链失败 " + s.source + " 《" + s.title + "》: " + firstErr);
        }

        // ★ 跨源兜底：网易的 VIP / 无版权曲目拿不到直链（接口给 null），
        //   这时拿「歌名 + 歌手」去 B 站再找一遍 —— B 站几乎什么都有。
        //   没有这一步，用户点一首 VIP 歌就是"点了没反应"，比搜不到更难受。
        if ((s.audioUrl == null || s.audioUrl.length() < 10) && !Song.SRC_BILI.equals(s.source)) {
            Log.i(TAG, "转 B 站兜底: " + s.title + " " + s.artist);
            String u = biliFallback(s);
            if (u != null && u.length() > 10) {
                s.audioUrl = u;
                Log.i(TAG, "B站兜底成功");
            }
        }

        if (s.audioUrl == null || s.audioUrl.length() < 10) {
            throw new Exception(firstErr == null ? "这首没有可播的音频" : firstErr);
        }
        return s.audioUrl;
    }

    static String msgOf(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.length() == 0) ? e.toString() : m;
    }

    /** B 站：先试纯音频轨（省流量），不行退完整 MP4 */
    static String biliDirect(String bvid) throws Exception {
        try {
            String u = Bili.audioUrl(bvid);
            if (u != null && u.length() > 10) return u;
        } catch (Throwable e) {
            Log.w(TAG, "纯音频失败，退完整 MP4: " + e);
        }
        return Bili.mp4Url(bvid);
    }

    /** 拿另一个源的歌名去 B 站找一条能播的 */
    static String biliFallback(Song s) {
        try {
            String q = clean(s.title);
            String ar = clean(s.artist);
            int cut = ar.indexOf('/');
            if (cut > 0) ar = ar.substring(0, cut);
            if (ar.length() > 0) q = q + " " + ar;
            List<Bili.Song> bs = Bili.search(q.trim(), 1);
            int n = Math.min(3, bs.size());
            for (int i = 0; i < n; i++) {
                try {
                    String u = biliDirect(bs.get(i).bvid);
                    if (u != null && u.length() > 10) {
                        // 顺手把 B 站这条的封面借过来：
                        // 网易接口只返回 picId 不返回 picUrl，自己拼要复刻它的加密算法；
                        // 反正这首最后就是从 B 站播的，用它的封面反而更对。
                        if ((s.coverUrl == null || s.coverUrl.length() < 8)
                                && bs.get(i).cover != null && bs.get(i).cover.length() > 8) {
                            s.coverUrl = bs.get(i).cover;
                        }
                        return u;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            Log.w(TAG, "B站兜底失败 " + e);
        }
        return null;
    }

    static void merge(List<Song> base, List<Song> more) {
        Set<String> have = new HashSet<String>();
        for (int i = 0; i < base.size(); i++) have.add(base.get(i).key());
        for (int i = 0; i < more.size(); i++) {
            Song s = more.get(i);
            if (have.add(s.key())) base.add(s);
        }
    }

    // ───────────────────────── 酷我 ─────────────────────────

    public static List<Song> kuwoSearch(String kw) throws Exception {
        String u = "https://search.kuwo.cn/r.s?all=" + URLEncoder.encode(kw, "UTF-8")
                + "&ft=music&itemset=web_2013&client=kt&pn=0&rn=30"
                + "&rformat=json&encoding=utf8&vipver=1&show_copyright_off=1&mobi=1";
        String raw = Http.get(u, "https://www.kuwo.cn/");
        return parseKuwo(raw);
    }

    /**
     * 酷我返回的是"单引号 JSON"（Python dict 风格），标准解析器一律不认。
     * 先整体换成双引号试标准解析；万一内容里真有单引号导致失败，
     * 退回到逐条正则提取——两条路都能活。
     */
    static List<Song> parseKuwo(String raw) {
        List<Song> out = new ArrayList<Song>();
        if (raw == null || raw.length() < 20) return out;
        String s = raw.replace('\'', '"').replace("&nbsp;", " ").replace("\\/", "/");
        try {
            JSONObject o = new JSONObject(s);
            JSONArray arr = o.optJSONArray("abslist");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject v = arr.optJSONObject(i);
                    if (v == null) continue;
                    Song sg = fromKuwo(v.optString("NAME"), v.optString("ARTIST"),
                            v.optString("ALBUM"), v.optString("DURATION"),
                            v.optString("MUSICRID"), v.optString("FORMATS"));
                    if (sg != null) out.add(sg);
                }
            }
            return out;
        } catch (Throwable ignored) {
            // 走正则回退
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{[^{}]*\"MUSICRID\"[^{}]*\\}").matcher(s);
        while (m.find()) {
            String one = m.group();
            Song sg = fromKuwo(field(one, "NAME"), field(one, "ARTIST"), field(one, "ALBUM"),
                    field(one, "DURATION"), field(one, "MUSICRID"), field(one, "FORMATS"));
            if (sg != null) out.add(sg);
        }
        return out;
    }

    static String field(String obj, String k) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + k + "\":\"([^\"]*)\"").matcher(obj);
        return m.find() ? m.group(1) : "";
    }

    static Song fromKuwo(String name, String artist, String album, String dur,
                         String rid, String formats) {
        if (name == null || name.length() == 0) return null;
        if (rid == null || rid.length() < 5) return null;
        Song s = new Song();
        s.title = clean(name);
        s.artist = clean(artist);
        s.album = clean(album);
        s.source = Song.SRC_KUWO;
        s.id = rid;
        s.durationSec = parseInt(dur, 0);
        if (formats != null && formats.contains("MP3H")) s.bitrate = 320;
        else if (formats != null && formats.contains("MP3128")) s.bitrate = 128;
        if (s.title.length() == 0) return null;
        return s;
    }

    public static String kuwoUrl(String rid) throws Exception {
        String u = "https://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3"
                + "&response=url&rid=" + URLEncoder.encode(rid, "UTF-8");
        String r = Http.get(u, "https://www.kuwo.cn/");
        if (r == null) return null;
        r = r.trim();
        if (!r.startsWith("http")) {
            // 部分区域节点会返回空串或错误文本
            throw new Exception("酷我没给地址（可能无版权）");
        }
        return r;
    }

    // ───────────────────────── 网易云（聚合 API） ─────────────────────────

    public static List<Song> neteaseSearch(String kw) throws Exception {
        // ① 先走网易官方老接口（**明文 http**）。
        //    这是本机唯一稳的一条：4.4 的 TLS 连 music-api.gdstudio.xyz 会
        //    SSL handshake failure，而 http://music.163.com/api/search/get 直接返回 JSON。
        try {
            List<Song> a = neteaseSearchDirect(kw);
            if (a.size() > 0) return a;
        } catch (Throwable e) {
            Log.w(TAG, "官方接口失败，转聚合API: " + e);
        }
        // ② 兜底才用聚合 API
        return neteaseSearchGd(kw);
    }

    static List<Song> neteaseSearchGd(String kw) throws Exception {
        String u = API + "?types=search&source=netease&name="
                + URLEncoder.encode(kw, "UTF-8") + "&count=30&pages=1";
        String raw = Http.get(u, null);
        List<Song> out = new ArrayList<Song>();
        if (raw == null || !raw.startsWith("[")) return out;
        JSONArray arr = new JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.optJSONObject(i);
            if (v == null) continue;
            Song s = new Song();
            s.title = clean(v.optString("name"));
            s.id = v.optString("id");
            s.album = clean(v.optString("album"));
            s.source = Song.SRC_NETEASE;
            StringBuilder ar = new StringBuilder();
            JSONArray as = v.optJSONArray("artist");
            if (as != null) {
                for (int j = 0; j < as.length(); j++) {
                    if (ar.length() > 0) ar.append('/');
                    ar.append(as.optString(j));
                }
            }
            s.artist = ar.toString();
            String pic = v.optString("pic_id");
            if (pic != null && pic.length() > 3) {
                s.coverUrl = API + "?types=pic&source=netease&id=" + pic + "&size=300";
            }
            if (s.id.length() == 0 || s.title.length() == 0) continue;
            out.add(s);
        }
        return out;
    }

    /** 网易官方 API（music.163.com/api/search/get），返回结构完全不同，单独解析 */
    static List<Song> neteaseSearchDirect(String kw) throws Exception {
        // ★ 故意用 http：4.4 连 music.163.com:443 会 EOFException，
        //   而这个接口在 80 端口上是明文直出的，机器上实测 200 + 完整 JSON。
        String u = "http://music.163.com/api/search/get?s=" + URLEncoder.encode(kw, "UTF-8")
                + "&type=1&limit=30&offset=0";
        String raw = Http.get(u, "http://music.163.com/");
        List<Song> out = new ArrayList<Song>();
        if (raw == null || raw.indexOf('{') != 0) return out;
        JSONObject root = new JSONObject(raw);
        JSONObject res = root.optJSONObject("result");
        if (res == null) return out;
        JSONArray arr = res.optJSONArray("songs");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.optJSONObject(i);
            if (v == null) continue;
            String name = v.optString("name");
            long id = v.optLong("id", 0);
            if (name.length() == 0 || id == 0) continue;
            Song s = new Song();
            s.title = clean(name);
            s.id = String.valueOf(id);
            s.source = Song.SRC_NETEASE;
            JSONObject al = v.optJSONObject("album");
            if (al != null) {
                s.album = clean(al.optString("name"));
                String pic = al.optString("picUrl");
                if (pic != null && pic.startsWith("http")) s.coverUrl = pic;
            }
            JSONArray ars = v.optJSONArray("artists");
            StringBuilder ar = new StringBuilder();
            if (ars != null) {
                for (int j = 0; j < ars.length(); j++) {
                    JSONObject a1 = ars.optJSONObject(j);
                    if (a1 == null) continue;
                    if (ar.length() > 0) ar.append('/');
                    ar.append(a1.optString("name"));
                }
            }
            s.artist = ar.toString();
            s.durationSec = (int) (v.optLong("duration", 0) / 1000);
            if (s.title.length() == 0) continue;
            out.add(s);
        }
        Log.i(TAG, "网易官方接口 " + out.size() + " 条");
        return out;
    }

    /** 聚合 API 的 TLS 在本机是断的，失败一次就别再试，免得每次点歌都白等 */
    static volatile boolean gdDead = false;

    public static String neteaseUrl(String id) throws Exception {
        if (gdDead) throw new Exception("网易直链通道不可用（转 B 站）");
        String u = API + "?types=url&source=netease&id="
                + URLEncoder.encode(id, "UTF-8") + "&br=320";
        String raw;
        try {
            raw = Http.get(u, null);
        } catch (Throwable e) {
            gdDead = true;
            throw e;
        }
        if (raw == null || !raw.startsWith("{")) return null;
        JSONObject o = new JSONObject(raw);
        String url = o.optString("url");
        if (url == null || url.length() < 10 || url.equals("null")) {
            throw new Exception("网易云没给地址（VIP/无版权）");
        }
        return url;
    }

    /** 歌词（LRC 原文），没有返回 null */
    public static String lyric(Song s) {
        if (s == null || !Song.SRC_NETEASE.equals(s.source)) return null;
        // ① 官方接口，明文 http（4.4 的 TLS 连自家 HTTPS 也会 EOF，别试）
        try {
            String u = "http://music.163.com/api/song/lyric?id="
                    + URLEncoder.encode(s.id, "UTF-8") + "&lv=-1&kv=-1&tv=-1";
            String raw = Http.get(u, "http://music.163.com/");
            JSONObject o = new JSONObject(raw);
            JSONObject lrcObj = o.optJSONObject("lrc");
            if (lrcObj != null) {
                String l = lrcObj.optString("lyric");
                if (l != null && l.length() > 20) return l;
            }
        } catch (Throwable e) {
            Log.w(TAG, "歌词(官方)失败 " + e);
        }
        // ② 聚合 API 兜底
        if (!gdDead) {
            try {
                String u = API + "?types=lyric&source=netease&id="
                        + URLEncoder.encode(s.id, "UTF-8");
                String raw = Http.get(u, null);
                JSONObject o = new JSONObject(raw);
                String l = o.optString("lyric");
                if (l != null && l.length() > 20) return l;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ───────────────────────── B 站兜底 ─────────────────────────

    public static List<Song> biliSearch(String kw) throws Exception {
        List<Bili.Song> bs = Bili.search(kw, 1);
        List<Song> out = new ArrayList<Song>();
        for (int i = 0; i < bs.size(); i++) {
            Bili.Song b = bs.get(i);
            Song s = new Song();
            s.title = clean(b.title);
            s.artist = b.author;
            s.source = Song.SRC_BILI;
            s.id = b.bvid;
            s.durationSec = b.seconds;
            out.add(s);
        }
        return out;
    }

    // ───────────────────────── 小工具 ─────────────────────────

    static String clean(String s) {
        if (s == null) return "";
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("<em class=\"keyword\">", "").replace("</em>", "")
                .trim();
    }

    static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }
}
