package com.nemo.tool;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

import java.lang.reflect.Method;

/**
 * 以 root 身份替桌面(hostId=1024)绑定一个 AppWidget。
 *
 * 背景：com.xylink.launcher 装在 /data/app，缺 BIND_APPWIDGET 这个
 * signature|privileged 权限，所以它自己调 allocateAppWidgetId()/bindAppWidgetId()
 * 会被拒。但 ActivityManager.checkComponentPermission 对 uid 0(root)
 * 直接返回 GRANTED，所以用 root 跑这段代码就能替它完成绑定。
 *
 * 用法：
 *   CLASSPATH=/data/local/tmp/binder.jar app_process /system/bin \
 *       com.nemo.tool.WidgetBinder com.nemo.weather com.nemo.weather.WeatherWidget
 */
public class WidgetBinder {

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                System.out.println("USAGE: WidgetBinder <pkg> <ProviderClass> [hostId]");
                return;
            }
            String pkg = args[0];
            String cls = args[1];
            int hostId = args.length > 2 ? Integer.parseInt(args[2]) : 1024;
            // 第 4 个参数：指定要绑定的已存在 appWidgetId（桌面自己分配的那个）
            int forceId = args.length > 3 ? Integer.parseInt(args[3]) : -1;

            Class<?> at = Class.forName("android.app.ActivityThread");
            android.os.Looper.prepareMainLooper();
            Object thread = at.getMethod("systemMain").invoke(null);
            Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
            System.out.println("CTX=" + ctx);

            ComponentName cn = new ComponentName(pkg, cls);

            AppWidgetHost host = new AppWidgetHost(ctx, hostId);
            int id;
            if (forceId >= 0) {
                id = forceId;
                System.out.println("USING_EXISTING=" + id);
            } else {
                id = host.allocateAppWidgetId();
                System.out.println("ALLOCATED=" + id);
            }

            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            Method bind = AppWidgetManager.class.getMethod(
                    "bindAppWidgetId", int.class, ComponentName.class);
            Object ok = bind.invoke(mgr, id, cn);
            System.out.println("BIND_RESULT=" + ok + " ID=" + id);

            // 确认一下绑定生效
            int[] ids = mgr.getAppWidgetIds(cn);
            StringBuilder sb = new StringBuilder("BOUND_IDS=");
            if (ids != null) {
                for (int i : ids) sb.append(i).append(",");
            }
            System.out.println(sb);
        } catch (Throwable t) {
            System.out.println("FAIL: " + t);
            t.printStackTrace();
        }
    }
}
