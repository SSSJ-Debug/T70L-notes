package com.lm.gohome;

import android.app.Activity;
import android.os.Bundle;

/**
 * 空壳 Activity —— 唯一作用是把 App 从"已安装未启动"状态里拉出来。
 *
 * Android 会记住"哪个包从来没被启动过"（stopped state），
 * 处于这个状态的包，manifest 里静态注册的广播接收器一条都收不到。
 * 所以装完必须启动一次，之后就再也用不到这个 Activity 了。
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        finish();
    }
}
