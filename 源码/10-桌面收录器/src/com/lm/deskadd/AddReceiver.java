package com.lm.deskadd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 触发器：装包 / 卸包 / 开机时叫醒 SyncService 去同步桌面。
 *
 * 注意 Android 3.1+ 对「STOPPED 状态」的应用不派发静态广播，
 * 所以这个应用装完必须先启动过一次（或开过一次机），之后才会稳定收到 PACKAGE_ADDED。
 * 为了兜底，开机广播里也会全量同步一次。
 */
public class AddReceiver extends BroadcastReceiver {

    static final String TAG = "DeskAdd";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;

        if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                || Intent.ACTION_PACKAGE_REMOVED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "com.lm.deskadd.SYNC".equals(action)) {
            Log.i(TAG, "收到 " + action + "，开始同步");
            try {
                Intent svc = new Intent(ctx, SyncService.class);
                ctx.startService(svc);
            } catch (Throwable t) {
                Log.w(TAG, "启动同步服务失败: " + t);
            }
        }
    }
}
