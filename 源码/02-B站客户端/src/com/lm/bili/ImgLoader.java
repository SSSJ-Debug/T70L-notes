package com.lm.bili;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 极简封面加载器：内存 LruCache + 3 线程池 + tag 防错位。
 * 不引 OkHttp / Glide，dex 保持最小。
 */
public class ImgLoader {

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                protected int sizeOf(String key, Bitmap b) {
                    return b.getByteCount();
                }
            };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "img");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            return t;
        }
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void load(final String url, final ImageView iv, final int targetW) {
        if (url == null || url.length() == 0) return;
        iv.setTag(url);

        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        iv.setImageDrawable(null);

        POOL.execute(new Runnable() {
            public void run() {
                final Bitmap bmp = fetch(url, targetW);
                if (bmp == null) return;
                CACHE.put(url, bmp);
                MAIN.post(new Runnable() {
                    public void run() {
                        if (url.equals(iv.getTag())) iv.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    private static Bitmap fetch(String url, int targetW) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", BiliApi.UA);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (conn.getResponseCode() != 200) return null;

            // 先读尺寸，按目标宽度降采样，避免 1G 内存被大图打爆
            byte[] raw = readAll(conn.getInputStream());
            if (raw == null) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            int sample = 1;
            if (targetW > 0 && o.outWidth > targetW * 2) sample = 2;
            if (targetW > 0 && o.outWidth > targetW * 4) sample = 4;

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, o2);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
