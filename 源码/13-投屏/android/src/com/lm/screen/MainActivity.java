package com.lm.screen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 扩展屏 for T70L —— 把 Mac 的屏幕实时显示到平板上
 * =====================================================
 * 目标设备：全志 A83T / Android 4.4.4 / 1280x800 / 1GB 内存
 *
 * 为什么走这条线：
 *   Android 4.4 没有 MediaProjection，本机 screenrecord 不支持裸 H.264 输出，
 *   屏幕还是 MIPI 直连 LCD（没有 HDMI 口，采集卡也没法用），
 *   所以「平板 → Mac」这条路在系统层面就是死的。
 *   但反方向「Mac → 平板」只需要平板会显示图片，成本低得多，也更接近双屏的用法。
 *
 * 链路：
 *   Mac 端 SCStream 采集 15fps → JPEG → TCP 7200 → 这里解码显示
 *
 * 内存纪律（这台机器只有 1GB）：
 *   ① 全程 RGB_565 解码，单帧 1.8MB，不是 ARGB_8888 的 3.6MB
 *   ② 网络线程只保留「最新一帧」，解码跟不上就丢旧帧，延迟不累积
 *   ③ 旧 Bitmap 延迟一帧回收，既不会踩到正在绘制的那张，也不会让 GC 堆积
 *   ④ 没有 largeHeap
 */
public class MainActivity extends Activity {

    static final String TAG = "Screen";
    static final int PORT = 7200;
    static final String DEFAULT_HOST = "192.168.1.210";

    ImageView view;
    TextView status;
    FrameLayout root;

    final Handler ui = new Handler();

    String host = DEFAULT_HOST;
    volatile boolean running = true;

    // 最新一帧（网络线程写，解码线程取）
    final Object slotLock = new Object();
    byte[] slot;
    boolean slotFresh = false;

    Bitmap shown;
    boolean fullScreen = false;
    int fpsCount = 0;
    long fpsAt = 0;
    volatile String state = "连接中…";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        host = sp.getString("host", DEFAULT_HOST);

        buildUi();

        new Thread(new Runnable() { public void run() { recvLoop(); } }, "recv").start();
        new Thread(new Runnable() { public void run() { decodeLoop(); } }, "decode").start();

        ui.postDelayed(fadeStatus, 4000);
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    // ══════════════════════ UI ══════════════════════

    void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setBackgroundColor(0xFF000000);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextSize(11);
        status.setTextColor(0xCCFFFFFF);
        status.setGravity(Gravity.LEFT);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        status.setText(state);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2);
        slp.gravity = Gravity.TOP | Gravity.LEFT;
        slp.topMargin = dp(8);
        slp.leftMargin = dp(8);
        root.addView(status, slp);

        // 点一下就显示状态条，再点显示设置
        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (status.getVisibility() != View.VISIBLE) {
                    status.setVisibility(View.VISIBLE);
                    ui.removeCallbacks(fadeStatus);
                    ui.postDelayed(fadeStatus, 3000);
                } else {
                    showHostDialog();
                }
            }
        });

        // 长按：在「保持比例（上下留黑）」和「铺满全屏（轻微拉伸）」之间切换
        root.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                fullScreen = !fullScreen;
                getPreferences(MODE_PRIVATE).edit().putBoolean("full", fullScreen).apply();
                applyScaleMode();
                toast(fullScreen ? "铺满全屏（画面轻微拉伸）" : "保持原比例（上下留黑）");
                return true;
            }
        });

        fullScreen = getPreferences(MODE_PRIVATE).getBoolean("full", false);
        applyScaleMode();

        setContentView(root);
    }

    void applyScaleMode() {
        if (view == null) return;
        view.setScaleType(fullScreen
                ? ImageView.ScaleType.FIT_XY      // 1280x720 → 1280x800，纵向拉伸 11%
                : ImageView.ScaleType.FIT_CENTER);// 原比例，上下各留 40px 黑边
    }

    final Runnable fadeStatus = new Runnable() {
        public void run() {
            if (status != null) status.setVisibility(View.GONE);
        }
    };

    void showHostDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(host);
        et.setHint("Mac 的局域网 IP");

        new AlertDialog.Builder(this)
                .setTitle("连接哪台 Mac")
                .setMessage("在 Mac 上运行 mirrord，它启动时会打印自己的地址")
                .setView(et)
                .setPositiveButton("重连", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String h = et.getText().toString().trim();
                        if (h.length() == 0) return;
                        host = h;
                        getPreferences(MODE_PRIVATE).edit().putString("host", h).apply();
                        toast("切换到 " + h);
                        setState("重连中…");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ══════════════════════ 接收 ══════════════════════

    void recvLoop() {
        while (running) {
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(host, PORT), 5000);
                s.setReceiveBufferSize(512 * 1024);
                InputStream raw = s.getInputStream();
                BufferedInputStream in = new BufferedInputStream(raw, 256 * 1024);

                setState("已连接 " + host);
                Log.i(TAG, "已连接 " + host + ":" + PORT);

                while (running) {
                    int b0 = in.read();
                    if (b0 < 0) break;
                    int b1 = in.read(), b2 = in.read(), b3 = in.read();
                    if (b1 < 0 || b2 < 0 || b3 < 0) break;
                    // 小端 4 字节长度（Mac 端发的是 UInt32.littleEndian）
                    int len = (b0 & 0xFF) | ((b1 & 0xFF) << 8)
                            | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
                    if (len <= 0 || len > 8 * 1024 * 1024) {
                        Log.w(TAG, "帧长度异常 " + len + "，断开重连");
                        break;
                    }

                    byte[] buf = new byte[len];
                    int off = 0;
                    while (off < len) {
                        int n = in.read(buf, off, len - off);
                        if (n < 0) throw new java.io.EOFException();
                        off += n;
                    }

                    // 只保留最新一帧：解码跟不上就丢掉旧的，延迟不会越积越大
                    synchronized (slotLock) {
                        slot = buf;
                        slotFresh = true;
                        slotLock.notifyAll();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "连接中断: " + t);
                setState("未连接，3 秒后重试");
            } finally {
                if (s != null) try { s.close(); } catch (Throwable ignored) {}
            }

            if (!running) break;
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            setState("正在连接 " + host + " …");
        }
    }

    // ══════════════════════ 解码 ══════════════════════

    void decodeLoop() {
        final BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inPreferredConfig = Bitmap.Config.RGB_565;   // 单帧 1.8MB，比 ARGB 省一半
        opt.inMutable = true;                            // 允许被 inBitmap 复用

        // 三缓冲轮转：解码时把新帧解到「3 帧前那个 buffer」的像素内存里。
        // 用 3 个而不是 2 个，是因为 show() 是 post 到 UI 线程异步执行的，
        // 留 200ms 余量，保证被复用的那块肯定已经被 ImageView 换下来了。
        // 好处：稳态下不再产生任何新的 Bitmap 分配，内存是一条平线。
        final Bitmap[] pool = new Bitmap[3];
        int bufIdx = 0;

        while (running) {
            byte[] data;
            synchronized (slotLock) {
                while (!slotFresh && running) {
                    try { slotLock.wait(500); } catch (InterruptedException ignored) {}
                }
                if (!running) return;
                data = slot;
                slot = null;
                slotFresh = false;
            }
            if (data == null) continue;

            Bitmap cached = pool[bufIdx];
            opt.inBitmap = cached;

            Bitmap bm;
            try {
                bm = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
            } catch (Throwable t) {
                // 复用冲突时 BitmapFactory 会抛异常，退化成重新分配
                Log.w(TAG, "复用解码失败，回退: " + t);
                opt.inBitmap = null;
                if (pool[bufIdx] != null && !pool[bufIdx].isRecycled()) {
                    try { pool[bufIdx].recycle(); } catch (Throwable ignored) {}
                }
                pool[bufIdx] = null;
                continue;
            }
            if (bm == null) {
                opt.inBitmap = null;
                continue;
            }

            // 复用失败会返回新对象，那块旧的就没用了
            if (cached != null && cached != bm && !cached.isRecycled()) {
                try { cached.recycle(); } catch (Throwable ignored) {}
            }

            pool[bufIdx] = bm;
            bufIdx = (bufIdx + 1) % 3;

            final Bitmap fb = bm;
            ui.post(new Runnable() { public void run() { show(fb); } });
        }
    }

    void show(final Bitmap bm) {
        if (isFinishing()) return;

        // 不再回收旧帧：所有 Bitmap 都由 decodeLoop 的三缓冲池统一管理，
        // 在这里 recycle 会把马上就要被复用的 buffer 干掉。
        shown = bm;
        view.setImageBitmap(bm);

        fpsCount++;
        long now = System.currentTimeMillis();
        if (fpsAt == 0) fpsAt = now;
        if (now - fpsAt >= 1000) {
            final String s = "已连接 " + host + " · " + fpsCount + " fps · "
                    + bm.getWidth() + "x" + bm.getHeight();
            fpsCount = 0;
            fpsAt = now;
            state = s;
            status.setText(s);
        }
    }

    // ══════════════════════ 工具 ══════════════════════

    void setState(final String s) {
        state = s;
        ui.post(new Runnable() {
            public void run() {
                if (status == null) return;
                status.setText(s);
                status.setVisibility(View.VISIBLE);
                ui.removeCallbacks(fadeStatus);
                ui.postDelayed(fadeStatus, 4000);
            }
        });
    }

    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if (code == android.view.KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
