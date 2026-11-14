package com.lm.music;

/**
 * 一首歌。所有音源统一成这个结构，UI 只认它。
 */
public class Song {

    public static final String SRC_KUWO = "酷我";
    public static final String SRC_NETEASE = "网易";
    public static final String SRC_BILI = "B站";

    public String title = "";
    public String artist = "";
    public String album = "";
    public String source = "";
    public String id = "";          // 各源自家的 id（酷我 MUSIC_xxx / 网易数字 / B站 bvid）
    public String coverUrl = "";
    public int durationSec = 0;
    public int bitrate = 0;         // 0=未知

    /** 解析出来的直链，缓存住，重复播不用再请求 */
    public String audioUrl;
    /** 解析失败时的错误（给 UI 提示用） */
    public String error;

    public String time() {
        int m = durationSec / 60, s = durationSec % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    /** 是否是"干净的原曲"——用来把 KTV/伴奏/翻唱排后面 */
    public int rankScore(String keyword) {
        int r = 0;
        String t = title == null ? "" : title;
        String a = artist == null ? "" : artist;
        if (keyword != null && keyword.length() > 0) {
            if (t.equals(keyword)) r -= 40;                 // 标题就是歌名
            else if (t.startsWith(keyword)) r -= 25;
            else if (t.contains(keyword)) r -= 12;
            if (a.contains(keyword)) r -= 10;
        }
        if (t.contains("伴奏") || t.contains("KTV") || t.contains("ktv")) r += 60;
        if (t.contains("翻唱") || t.contains("cover") || t.contains("COVER")) r += 35;
        if (t.contains("DJ") || t.contains("dj版") || t.contains("remix")) r += 20;
        if (t.contains("铃声") || t.contains("片段") || t.contains("试听")) r += 55;
        if (t.contains("钢琴") || t.contains("吉他") || t.contains("纯音乐")) r += 30;
        if (t.contains("合集") || t.contains("串烧") || t.contains("连播")) r += 45;
        if (durationSec > 0 && durationSec < 45) r += 50;
        if (durationSec > 600) r += 40;
        if (bitrate >= 320) r -= 6;                          // 同分时高码率优先
        return r;
    }

    /** 去重用：把标题里的括号内容、空格、符号都抹掉 */
    public String key() {
        String t = title == null ? "" : title;
        int i = t.indexOf('(');
        int j = t.indexOf('（');
        int k = (i < 0 ? j : (j < 0 ? i : Math.min(i, j)));
        if (k > 1) t = t.substring(0, k);
        t = t.replaceAll("[\\s\\-—_·,，.。!！?？'\"“”‘’]", "").toLowerCase();
        String a = artist == null ? "" : artist.split("[/,&]")[0].trim();
        return t + "|" + a;
    }
}
