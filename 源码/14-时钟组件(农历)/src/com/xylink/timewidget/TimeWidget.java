package com.xylink.timewidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** 时钟 widget 入口：负责拉起/停止每秒刷新服务，并做每分钟心跳兜底。 */
public class TimeWidget extends AppWidgetProvider {

    public static final String ACTION_TICK = "com.xylink.timewidget.ACTION_TICK";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        TimeService.start(context);
    }

    @Override
    public void onEnabled(Context context) {
        TimeService.start(context);
    }

    @Override
    public void onDisabled(Context context) {
        TimeService.stop(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        // 只要有别的实例还在就不停；简单起见先停，onUpdate 会再拉起
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] remain = mgr.getAppWidgetIds(new ComponentName(context, TimeWidget.class));
        if (remain == null || remain.length == 0) {
            TimeService.stop(context);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String a = intent.getAction();
        if (ACTION_TICK.equals(a)) {
            // 心跳：服务被系统回收后由分钟闹钟拉回
            TimeService.start(context);
            TimeService.scheduleNextMinute(context);
        }
    }
}
