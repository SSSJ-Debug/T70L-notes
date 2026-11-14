package com.lm.deskadd;

import android.app.Activity;
import android.os.Bundle;

/**
 * 没有任何界面，只做两件事：
 *   1. 让这个应用「被启动过一次」——Android 3.1+ 里只有启动过的应用才收得到 PACKAGE_ADDED 静态广播
 *   2. 立刻跑一次全量同步，然后自己退掉
 *
 * 它自己不会被收录到桌面（AutoAdd 里排除了本包）。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            startService(new android.content.Intent(this, SyncService.class));
        } catch (Throwable ignored) {
        }
        finish();
    }
}
