package com.lm.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.View;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面图 —— 下载 + 内存缓存 + 圆角绘制。
 *
 * 4.4 上图片不能走 ImageView.setImageUrl 那套（没这 API），
 * 自己用 Http 拉字节再 BitmapFactory 解，走的是 Java 层 TLS，稳。
 * 缓存按"张数"限，1GB 内存的机器贪多必被系统杀。
 */
public class CoverLoader {

    static final String TAG = "MUSIC";
    static final int MAX_ITEMS = 16;

    static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(MAX_ITEMS) {
        protected int sizeOf(String k, Bitmap b) { return 1; }
    };

    static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    static final Set<String> LOADING = new HashSet<String>();
    static final Handler UI = new Handler(Looper.getMainLooper());

    public interface Cb {
        void done(Bitmap b);
    }

    /** 各家封面 CDN 都验 Referer，按域名给 */
    static String refOf(String url) {
        if (url == null) return null;
        if (url.contains("hdslb") || url.contains("bilibili")) return "https://www.bilibili.com/";
        if (url.contains("126.net") || url.contains("music.163")) return "https://music.163.com/";
        return null;
    }

    public static Bitmap cached(String url) {
        if (url == null) return null;
        return CACHE.get(url);
    }

    public static void load(final String url, final Cb cb) {
        if (url == null || url.length() < 8) return;
        Bitmap b = CACHE.get(url);
        if (b != null) { cb.done(b); return; }
        synchronized (LOADING) {
            if (LOADING.contains(url)) return;   // 同一个图不重复请求
            LOADING.add(url);
        }
        POOL.execute(new Runnable() {
            public void run() {
                Bitmap bmp = null;
                try {
                    // 封面 CDN 也要 Referer，不然 B 站 hdslb 会 403、网易 126.net 也一样
                    byte[] d = Http.bytes(url, refOf(url));
                    if (d != null && d.length > 100) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inPreferredConfig = Bitmap.Config.RGB_565;   // 封面不用 alpha，省一半内存
                        bmp = BitmapFactory.decodeByteArray(d, 0, d.length, o);
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "封面失败 " + e);
                }
                synchronized (LOADING) { LOADING.remove(url); }
                if (bmp != null) CACHE.put(url, bmp);
                final Bitmap fb = bmp;
                UI.post(new Runnable() {
                    public void run() { cb.done(fb); }
                });
            }
        });
    }

    // ───────────────────────── 封面 View ─────────────────────────

    /** 圆角封面；没图时自己画一张渐变占位（带音符），不会露出尴尬的空白 */
    public static class CoverView extends View {

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private Bitmap bmp;
        private String url;
        private int radius = 12;
        private int seed = 0;

        public CoverView(Context c) { super(c); }

        public void setRadius(int dp) { radius = dp; }

        public void setUrl(final String u) {
            if (u == null ? url == null : u.equals(url)) return;
            url = u;
            bmp = CoverLoader.cached(u);
            seed = Math.abs(u == null ? 0 : u.hashCode());
            invalidate();
            if (bmp == null && u != null) {
                CoverLoader.load(u, new CoverLoader.Cb() {
                    public void done(Bitmap b) {
                        if (b != null && url != null && url.equals(u)) {
                            bmp = b;
                            invalidate();
                        }
                    }
                });
            }
        }

        public void clear() {
            url = null;
            bmp = null;
            invalidate();
        }

        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = radius;
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, r, r, Path.Direction.CW);

            cv.save();
            cv.clipPath(clip);
            if (bmp != null) {
                cv.drawBitmap(bmp, null, rect, p);
            } else {
                drawPlaceholder(cv, w, h);
            }
            cv.restore();
        }

        /** 没封面时按 url 哈希取一组配色，画渐变 + 一个音符 */
        private void drawPlaceholder(Canvas cv, int w, int h) {
            int[][] pal = {
                    {0xFF3A2C6B, 0xFF1B2440},
                    {0xFF5B2A46, 0xFF241A2E},
                    {0xFF1F3A55, 0xFF14202E},
                    {0xFF40321C, 0xFF251D14},
                    {0xFF2C4A3A, 0xFF16241D}
            };
            int[] c = pal[seed % pal.length];
            p.setShader(new LinearGradient(0, 0, w, h, c[0], c[1], Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);

            // 音符
            float cx = w / 2f, cy = h / 2f, s = Math.min(w, h);
            p.setColor(0x55FFFFFF);
            cv.drawCircle(cx - s * 0.13f, cy + s * 0.15f, s * 0.105f, p);
            cv.drawCircle(cx + s * 0.12f, cy + s * 0.08f, s * 0.105f, p);
            p.setStrokeWidth(s * 0.045f);
            p.setStyle(Paint.Style.STROKE);
            cv.drawLine(cx - s * 0.045f, cy + s * 0.15f, cx - s * 0.045f, cy - s * 0.12f, p);
            cv.drawLine(cx + s * 0.205f, cy + s * 0.08f, cx + s * 0.205f, cy - s * 0.19f, p);
            p.setStyle(Paint.Style.FILL);
            Path beam = new Path();
            beam.moveTo(cx - s * 0.045f, cy - s * 0.12f);
            beam.lineTo(cx + s * 0.205f, cy - s * 0.19f);
            beam.lineTo(cx + s * 0.205f, cy - s * 0.115f);
            beam.lineTo(cx - s * 0.045f, cy - s * 0.045f);
            beam.close();
            cv.drawPath(beam, p);
        }
    }
}
