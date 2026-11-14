package com.nemo.weather;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * 天气小组件（T70L 专用）
 *
 * 数据源：t.weather.itboy.net（HTTP，无需 key，中文，15 天预报）
 * 用 HTTP 不用 HTTPS 的原因：设备是 Android 4.4，只支持老版本 TLS。
 *
 * 两个关键设计：
 * 1) 先渲染上次成功的数据（内存/SP 缓存），再去联网刷新 —— 开机瞬间网络没起来
 *    也不会显示空白的 "--°"。
 * 2) 联网失败用 AlarmManager 定时重试（最多 5 次），避免开机太早取不到数据就永远不更新。
 */
public class WeatherWidget extends AppWidgetProvider {

    private static final String TAG = "NemoWx";
    public static final String ACTION_REFRESH = "com.nemo.weather.REFRESH";
    private static final String ACTION_RETRY = "com.nemo.weather.RETRY";

    static final String PREFS = "wx_prefs";
    static final String K_CODE = "city_code";
    static final String K_NAME = "city_name";
    static final String K_TEMP = "last_temp";
    static final String K_COND = "last_cond";
    static final String K_CITY = "last_city";
    static final String K_RANGE = "last_range";
    static final String K_AQI = "last_aqi";
    static final String K_TRIES = "retry_count";

    static final String DEF_CODE = "101020100";   // 上海
    static final String DEF_NAME = "上海市";

    private static volatile boolean running = false;

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        renderFromCache(context);          // 先把缓存画上去
        refreshAsync(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            // 开机时桌面还没建好组件视图，回调未注册，刷了也白刷。
            // 所以延迟两次补刷（20s / 60s），此时桌面已完成布局。
            scheduleAt(context, ACTION_REFRESH, 20000L, 1);
            scheduleAt(context, ACTION_REFRESH, 60000L, 2);
            return;
        }
        if (ACTION_REFRESH.equals(a) || ACTION_RETRY.equals(a)) {
            renderFromCache(context);
            refreshAsync(context);
        } else {
            super.onReceive(context, intent);
        }
    }

    private static void scheduleAt(Context ctx, String action, long delayMs, int reqCode) {
        Intent i = new Intent(ctx, WeatherWidget.class).setAction(action);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, pi);
    }

    /** 用 SharedPreferences 里上一次成功的数据渲染（秒出，不联网） */
    private static void renderFromCache(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        push(ctx,
                sp.getString(K_TEMP, "--°"),
                sp.getString(K_COND, "--"),
                sp.getString(K_CITY, sp.getString(K_NAME, DEF_NAME)),
                sp.getString(K_RANGE, ""),
                sp.getString(K_AQI, ""));
    }

    static void refreshAsync(final Context ctx) {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    doRefresh(ctx);
                } catch (Throwable e) {
                    Log.w(TAG, "refresh failed: " + e);
                } finally {
                    running = false;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void doRefresh(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String code = sp.getString(K_CODE, DEF_CODE);
        String name = sp.getString(K_NAME, DEF_NAME);

        try {
            JSONObject root = new JSONObject(httpGet(
                    "http://t.weather.itboy.net/api/weather/city/" + code));

            String city = name;
            JSONObject cityInfo = root.optJSONObject("cityInfo");
            if (cityInfo != null) {
                String c = cityInfo.optString("city", "");
                if (c.length() > 0) city = c;
            }

            String temp = "--°", cond = "--", range = "", aqiLine = "";
            JSONObject data = root.optJSONObject("data");
            if (data == null) throw new RuntimeException("no data");

            String wendu = data.optString("wendu", "");
            if (wendu.length() > 0) temp = trimNum(wendu) + "°";

            String quality = data.optString("quality", "");
            JSONArray fc = data.optJSONArray("forecast");
            if (fc != null && fc.length() > 0) {
                JSONObject d0 = fc.optJSONObject(0);
                if (d0 != null) {
                    cond = d0.optString("type", "--");
                    range = strip(d0.optString("low", "")) + " / " + strip(d0.optString("high", ""));
                    String a = d0.optString("aqi", "");
                    aqiLine = "空气" + quality + (a.length() > 0 ? " " + a : "");
                }
            } else if (quality.length() > 0) {
                aqiLine = "空气" + quality;
            }

            // 成功：落盘 + 渲染 + 清零重试计数
            sp.edit()
                    .putString(K_TEMP, temp)
                    .putString(K_COND, cond)
                    .putString(K_CITY, city)
                    .putString(K_RANGE, range)
                    .putString(K_AQI, aqiLine)
                    .putInt(K_TRIES, 0)
                    .commit();
            push(ctx, temp, cond, city, range, aqiLine);

        } catch (Throwable e) {
            Log.w(TAG, "fetch error: " + e);
            // 失败：保留缓存内容，安排一次重试
            scheduleRetry(ctx);
        }
    }

    private static void scheduleRetry(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int tries = sp.getInt(K_TRIES, 0);
        if (tries >= 5) {
            Log.w(TAG, "give up after " + tries);
            return;
        }
        sp.edit().putInt(K_TRIES, tries + 1).commit();

        Intent i = new Intent(ctx, WeatherWidget.class).setAction(ACTION_RETRY);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 100 + tries, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        // 30s / 60s / 120s / 240s / 480s 退避
        long delay = 30000L << Math.min(tries, 4);
        am.set(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + delay, pi);
        Log.i(TAG, "retry #" + (tries + 1) + " in " + (delay / 1000) + "s");
    }

    /** 真正把数据刷到所有小组件实例上 */
    private static void push(Context ctx, String temp, String cond,
                             String city, String range, String aqi) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.wx_temp, temp);
        rv.setTextViewText(R.id.wx_cond, cond);
        rv.setTextViewText(R.id.wx_city, city);
        rv.setTextViewText(R.id.wx_range, range);
        rv.setTextViewText(R.id.wx_aqi, aqi);

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        rv.setOnClickPendingIntent(R.id.wx_root, pi);

        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        ComponentName me = new ComponentName(ctx, WeatherWidget.class);
        int[] ids = mgr.getAppWidgetIds(me);
        if (ids != null && ids.length > 0) {
            mgr.updateAppWidget(ids, rv);
        }
    }

    private static String trimNum(String s) {
        try {
            return String.valueOf((int) Math.round(Double.parseDouble(s.trim())));
        } catch (Throwable e) {
            return s;
        }
    }

    private static String strip(String s) {
        return s.replace("高温", "").replace("低温", "")
                .replace("℃", "°").replace(" ", "");
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.4.4)");
        int code = c.getResponseCode();
        if (code != 200) throw new RuntimeException("HTTP " + code);

        InputStream in = c.getInputStream();
        String enc = c.getContentEncoding();
        if (enc != null && enc.toLowerCase().contains("gzip")) {
            in = new GZIPInputStream(in);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(bo.toByteArray(), "UTF-8");
    }
}
