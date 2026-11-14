package com.lm.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词：解析 + 一行一行往上滚的显示。
 *
 * 只做"看得清"这一件事：当前行高亮加粗，上下各一行淡出。
 * 不做卡拉OK逐字染色——那需要给每行再解析 <mm:ss.xx> 标签，收益不值这个复杂度。
 */
public class Lrc {

    static final Pattern TAG = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    public long[] times = new long[0];
    public String[] lines = new String[0];
    public String plain = "";

    public static Lrc parse(String raw) {
        Lrc l = new Lrc();
        if (raw == null || raw.trim().length() == 0) return l;
        List<Long> ts = new ArrayList<Long>();
        List<String> ls = new ArrayList<String>();
        String[] rows = raw.split("\n");
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].trim();
            if (row.length() == 0) continue;
            Matcher m = TAG.matcher(row);
            if (!m.find()) continue;
            int min = Integer.parseInt(m.group(1));
            int sec = Integer.parseInt(m.group(2));
            String f = m.group(3);
            int ms = 0;
            if (f != null) {
                if (f.length() == 1) ms = Integer.parseInt(f) * 100;
                else if (f.length() == 2) ms = Integer.parseInt(f) * 10;
                else ms = Integer.parseInt(f);
            }
            String text = row.substring(m.end()).trim();
            // 有些 LRC 一行带多个时间戳，逐个展开
            while (true) {
                ts.add(min * 60000L + sec * 1000L + ms);
                ls.add(text);
                if (!m.find()) break;
                min = Integer.parseInt(m.group(1));
                sec = Integer.parseInt(m.group(2));
                f = m.group(3);
                ms = 0;
                if (f != null) {
                    if (f.length() == 1) ms = Integer.parseInt(f) * 100;
                    else if (f.length() == 2) ms = Integer.parseInt(f) * 10;
                    else ms = Integer.parseInt(f);
                }
            }
        }
        int n = ts.size();
        l.times = new long[n];
        l.lines = new String[n];
        for (int i = 0; i < n; i++) {
            l.times[i] = ts.get(i);
            l.lines[i] = ls.get(i);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.lines[i]);
        }
        l.plain = sb.toString();
        return l;
    }

    public boolean ok() { return times.length > 0; }

    /** 当前时间落在第几行，没有返回 -1 */
    public int indexAt(long ms) {
        if (times.length == 0) return -1;
        int lo = 0, hi = times.length - 1, r = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= ms) { r = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return r;
    }

    // ───────────────────────── 视图 ─────────────────────────

    public static class LyricView extends View {

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Lrc lrc;
        private int cur = -1;
        private String idle = "听点什么呢？搜一首试试";
        private float scale = 1f;

        public LyricView(Context c) {
            super(c);
            p.setTextAlign(Paint.Align.CENTER);
        }

        /** 由 Activity 传入全屏缩放比（设计稿 1280 宽） */
        public void setScale(float s) {
            scale = s > 0 ? s : 1f;
            invalidate();
        }

        public void setIdle(String s) {
            idle = s;
            invalidate();
        }

        public void setLrc(Lrc l) {
            lrc = l;
            cur = -1;
            invalidate();
        }

        public void setTime(long ms) {
            if (lrc == null) return;
            int i = lrc.indexAt(ms);
            if (i != cur) {
                cur = i;
                invalidate();
            }
        }

        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f;
            Paint.FontMetrics fm;

            if (lrc == null || !lrc.ok()) {
                p.setTextSize(15 * scale);
                p.setTypeface(Typeface.DEFAULT);
                p.setColor(0xFF5A6474);
                p.setTextAlign(Paint.Align.CENTER);
                cv.drawText(idle, cx, h / 2f, p);
                return;
            }

            float sp = 17 * scale;
            float midY = h / 2f;

            drawLine(cv, cur, cx, midY, sp, true);
            if (cur > 0) drawLine(cv, cur - 1, cx, midY - sp * 2.6f, sp * 0.9f, false);
            if (cur + 1 < lrc.lines.length) drawLine(cv, cur + 1, cx, midY + sp * 2.6f, sp * 0.9f, false);
            if (cur < 0) {
                drawLine(cv, 0, cx, midY, sp, true);
                if (lrc.lines.length > 1) drawLine(cv, 1, cx, midY + sp * 2.6f, sp * 0.9f, false);
            }
        }

        private void drawLine(Canvas cv, int idx, float cx, float y, float size, boolean active) {
            if (idx < 0 || idx >= lrc.lines.length) return;
            String t = lrc.lines[idx];
            if (t == null || t.length() == 0) return;
            p.setTextSize(size);
            p.setTextAlign(Paint.Align.CENTER);
            if (active) {
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setColor(0xFFF2F6FF);
            } else {
                p.setTypeface(Typeface.DEFAULT);
                p.setColor(0xFF5A6474);
            }
            Paint.FontMetrics fm = p.getFontMetrics();
            float base = y - (fm.ascent + fm.descent) / 2f;
            cv.drawText(t, cx, base, p);
        }
    }
}
