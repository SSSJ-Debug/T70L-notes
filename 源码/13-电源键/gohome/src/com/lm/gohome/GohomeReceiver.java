package com.lm.gohome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.List;

/**
 * 电源键「回桌面」执行器。
 *
 * 背景（解析 /system/framework/services.jar 得到）：
 *   PowerManagerService.goToSleepFromNative()
 *       if (!isHome) { sendOrderedBroadcast("com.nemo.action.GOHOME"); isHome = true; }
 *       else         { 真息屏 }
 *   也就是说，系统只负责"喊一声 GOHOME"，真正把桌面拉到前台是接收者的事。
 *   原厂那个接收者是 vulture.app.home —— 它同时会抢麦克风，所以我们要把它拿掉，
 *   由这个几十行的接收器接管。
 *
 * 收到广播后做两件事（互为兜底）：
 *   ① 发标准 HOME Intent（ACTION_MAIN + CATEGORY_HOME），交给系统解析；
 *   ② 解析失败就直接点名 com.xylink.launcher 的 LauncherActivity。
 */
public class GohomeReceiver extends BroadcastReceiver {
    static final String TAG = "GohomeRx";
    static final String LAUNCHER_PKG = "com.xylink.launcher";
    static final String LAUNCHER_CLS = "com.xylink.launcher.app.LauncherActivity";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        Log.i(TAG, "收到 " + a + " → 回桌面");

        if (homeByResolve(ctx)) return;
        homeByClass(ctx);
    }

    /** 标准 HOME 解析：系统首选桌面是谁就叫谁 */
    private boolean homeByResolve(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
            ResolveInfo ri = ctx.getPackageManager()
                    .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null) {
                Log.w(TAG, "HOME 解析不到，走点名方式");
                return false;
            }
            ctx.startActivity(i);
            Log.i(TAG, "已发 HOME intent → " + ri.activityInfo.packageName);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "HOME intent 失败: " + e);
            return false;
        }
    }

    /** 兜底：直接点名本机桌面 */
    private void homeByClass(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setClassName(LAUNCHER_PKG, LAUNCHER_CLS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
            Log.i(TAG, "点名拉起 " + LAUNCHER_PKG);
        } catch (Throwable e) {
            Log.e(TAG, "点名拉起也失败: " + e);
        }
    }
}
