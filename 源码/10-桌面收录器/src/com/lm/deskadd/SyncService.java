package com.lm.deskadd;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * 后台同步服务：装完应用 / 开机时跑一次扫描，把缺的图标补进桌面。
 *
 * 为什么要有独立线程：PackageManager 枚举 + 图标转 PNG 可能要几百毫秒，
 * 放在广播接收器里会 ANR（接收器只有 10 秒）。
 */
public class SyncService extends Service {

    static final String TAG = "DeskAdd";
    private volatile boolean busy = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (busy) return START_NOT_STICKY;
        busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 装完包之后系统还在收尾，等 2 秒再扫，避免扫到半装的包
                    sleep(2000);
                    int n = AutoAdd.sync(SyncService.this);
                    Log.i(TAG, "同步完成，新收录 " + n + " 个");
                    if (n > 0) refreshLauncher();
                } catch (Throwable t) {
                    Log.w(TAG, "同步异常: " + t);
                } finally {
                    busy = false;
                    stopSelf();
                }
            }
        }, "deskadd-sync").start();
        return START_NOT_STICKY;
    }

    /**
     * 让桌面重读数据库。
     *
     * LauncherProvider.insert 不调用 notifyChange，所以插完条目界面不会自己更新。
     * 实测这个 nemo 广播能让 LauncherActivity 重新走一遍加载（它就是 vulture 用来
     * 把真桌面顶起来的那个广播），不发它的话得等下次桌面重建才看得到新图标。
     */
    private void refreshLauncher() {
        try {
            sendBroadcast(new Intent("nemo.intent.update.RESTART"));
            Log.i(TAG, "已请求桌面刷新");
        } catch (Throwable t) {
            Log.w(TAG, "刷新请求失败: " + t);
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
