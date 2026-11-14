package com.lm.xiaozhi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机/软重启后自动拉起小智（init 不重跑时也能兜住） */
public class BootReceiver extends BroadcastReceiver {
    static final String TAG = "XzBoot";

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        String a = intent.getAction();
        Log.i(TAG, "收到 " + a);
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(8000); } catch (Throwable ignored) {}
                try {
                    ctx.startService(new Intent(ctx, XzService.class));
                    Log.i(TAG, "已拉起 XzService");
                } catch (Throwable e) {
                    Log.e(TAG, "拉起失败", e);
                }
            }
        }).start();
    }
}
