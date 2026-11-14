package com.xylink.timewidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.Locale;

/**
 * 每秒刷新时钟 widget。
 * 原版 TextClock 只随 ACTION_TIME_TICK 每分钟刷新，显示不了秒；
 * 这里改为 Service + Handler 每秒 push RemoteViews。
 * 兜底链：START_STICKY + 每分钟 setExact 闹钟 + launcher_guard 外部守护。
 */
public class TimeService extends Service {

    private static final Handler HANDLER = new Handler();

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            try {
                push();
            } catch (Throwable t) {
                // 任何异常都不允许中断循环
            }
            HANDLER.postDelayed(this, 1000);
        }
    };

    static void start(Context c) {
        try {
            c.startService(new Intent(c, TimeService.class));
        } catch (Throwable ignored) {
        }
    }

    static void stop(Context c) {
        HANDLER.removeCallbacksAndMessages(null);
        c.stopService(new Intent(c, TimeService.class));
    }

    static void scheduleNextMinute(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent it = new Intent(TimeWidget.ACTION_TICK);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0, it, PendingIntent.FLAG_UPDATE_CURRENT);
            long next = ((System.currentTimeMillis() / 60000L) + 1) * 60000L + 500L;
            am.setExact(AlarmManager.RTC, next, pi);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        HANDLER.removeCallbacks(mTick);
        HANDLER.post(mTick);
        scheduleNextMinute(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        HANDLER.removeCallbacks(mTick);
        super.onDestroy();
    }

    private void push() {
        Calendar c = Calendar.getInstance();
        // 24 小时制（17:05 / 21:30），不再显示上午/下午
        String hhmm = String.format(Locale.US, "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        String ss = ":" + String.format(Locale.US, "%02d", c.get(Calendar.SECOND));
        String date = String.format(Locale.CHINA, "%02d月%02d日 %s",
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                weekday(c.get(Calendar.DAY_OF_WEEK)));
        String lunar = Lunar.text(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH));

        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.new_app_widget);
        rv.setTextViewText(R.id.tv_time, hhmm);
        rv.setTextViewText(R.id.tv_sec, ss);
        rv.setTextViewText(R.id.tv_date, date);
        rv.setTextViewText(R.id.tv_lunar, lunar);
        AppWidgetManager.getInstance(this).updateAppWidget(
                new ComponentName(this, TimeWidget.class), rv);
    }

    private static String weekday(int dow) {
        switch (dow) {
            case Calendar.SUNDAY: return "周日";
            case Calendar.MONDAY: return "周一";
            case Calendar.TUESDAY: return "周二";
            case Calendar.WEDNESDAY: return "周三";
            case Calendar.THURSDAY: return "周四";
            case Calendar.FRIDAY: return "周五";
            default: return "周六";
        }
    }
}
