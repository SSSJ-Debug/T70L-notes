package com.lm.douyin;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 抖音 for T70L —— 原生轻量短视频流
 * =====================================
 * 目标设备：全志 A83T / Android 4.4.4 / 1280x800 / 1GB 内存
 *
 * 为什么是原生的：
 *   Chrome33 内核跑不了 2026 年的抖音网页版（缺 nonce CSP、缺现代 JS 语法），
 *   且本机没有可升级的独立 WebView 包（内核编在 framework 里）。
 *   抖音 App 接口又有 a_bogus 签名。所以「刷抖音的样子」用原生实现，
 *   内容源用 B 站热门流（无签名、质量够、720P MP4 可直接解）。
 *
 * 交互（对齐抖音）：
 *   上滑 = 下一条，下滑 = 上一条，单击 = 暂停/继续，播完自动下一条。
 *   右侧：作者头像 / 点赞数 / 弹幕数 / 分享。底部：@作者 + 标题。
 *
 * 内存纪律：
 *   没有 largeHeap；同一时刻只有一个 MediaPlayer；切条必 release；
 *   列表只保留 60 条；进程退出不做任何残留。
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    static final String TAG = "Douyin";

    static final int PAGE_KEEP = 60;        // 列表最多保留条数

    // ── 数据 ──
    final List<Feed.Video> list = new ArrayList<Feed.Video>();
    int idx = 0;
    int page = 0;
    boolean loadingPage = false;
    boolean endOfFeed = false;

    // ── 播放 ──
    MediaPlayer mp;
    SurfaceView sv;
    SurfaceHolder sh;
    boolean surfaceReady = false;
    Feed.Video pending;
    boolean paused = false;
    boolean preparing = false;

    // ── UI ──
    FrameLayout root;
    LinearLayout infoBox;
    TextView tvAuthor, tvTitle, tvLike, tvDanmu, tvShare, tvHint, tvIndex, tvStat;
    TextView btnLike;
    View loadingBox;
    TextView loadingText;

    // ── 音量（T70L 没有实体音量键，必须 App 内自己给入口）──
    AudioManager am;
    int volMax = 15;            // 系统媒体音量档位上限
    LinearLayout volBox;        // 中央浮层
    View volFill;               // 浮层里的进度条（纯显示，不接触摸）
    int volBarW = 240;          // 进度条满宽（px），buildUi 时按 dp 换算
    TextView volPct, tvVol;
    boolean volMode = false;    // 本次触摸是否进入「调音量」模式
    float downVolV = 0f;        // 按下瞬间的屏幕 Y，用于换算音量
    int volAtDown = 0;          // 按下瞬间的音量基数

    // ── 手势 ──
    float downY, downX;
    long downT;

    // ── 主线程 ──
    final Handler ui = new Handler();

    // ══════════════════════ 生命周期 ══════════════════════

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        buildUi();

        StreamProxy.start();     // 本地代理先起来，后面 resolve 才能拿到地址
        loadPage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mp != null && paused) {
            try { mp.start(); paused = false; } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onPause() {
        if (mp != null && mp.isPlaying()) {
            try { mp.pause(); paused = true; } catch (Throwable ignored) {}
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    // ══════════════════════ UI ══════════════════════

    void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        sv = new SurfaceView(this);
        sh = sv.getHolder();
        sh.addCallback(this);
        sh.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(-1, -1);
        vlp.gravity = Gravity.CENTER;
        root.addView(sv, vlp);

        // 顶部渐变提示（上滑切换）
        tvHint = new TextView(this);
        tvHint.setText("上滑看下一条 · 左侧上下滑调音量 · 单击暂停");
        tvHint.setTextSize(12);
        tvHint.setTextColor(0x99FFFFFF);
        tvHint.setGravity(Gravity.CENTER);
        tvHint.setPadding(0, dp(10), 0, dp(10));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-1, -2);
        hlp.gravity = Gravity.TOP;
        root.addView(tvHint, hlp);

        // 顶部右侧序号
        tvIndex = new TextView(this);
        tvIndex.setTextSize(11);
        tvIndex.setTextColor(0x88FFFFFF);
        tvIndex.setPadding(dp(8), dp(4), dp(8), dp(4));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, -2);
        ilp.gravity = Gravity.TOP | Gravity.RIGHT;
        ilp.topMargin = dp(6);
        ilp.rightMargin = dp(8);
        root.addView(tvIndex, ilp);

        // ── 左上角：音量胶囊（点一下就能拖，不用再跑去系统设置）──
        tvVol = new TextView(this);
        tvVol.setTextSize(11);
        tvVol.setTextColor(0xEEFFFFFF);
        tvVol.setGravity(Gravity.CENTER);
        tvVol.setPadding(dp(10), dp(5), dp(10), dp(5));
        tvVol.setBackground(roundRect(0x44000000, dp(14), 0x44FFFFFF));
        tvVol.setShadowLayer(3f, 0f, 1f, 0xFF000000);
        tvVol.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showVol(true);
                ui.removeCallbacks(hideVol);
                ui.postDelayed(hideVol, 4000);
            }
        });
        FrameLayout.LayoutParams vlp2 = new FrameLayout.LayoutParams(-2, -2);
        vlp2.gravity = Gravity.TOP | Gravity.LEFT;
        vlp2.topMargin = dp(6);
        vlp2.leftMargin = dp(10);
        root.addView(tvVol, vlp2);

        // ── 右侧操作栏 ──
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);

        // 作者头像（首字圆）
        TextView avatar = new TextView(this);
        avatar.setText("·");
        avatar.setTextSize(13);
        avatar.setTextColor(Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable ag = circle(0xFFFE2C55, dp(26), 0x66FFFFFF);
        avatar.setBackground(ag);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(40), dp(40));
        right.addView(avatar, alp);

        btnLike = iconBtn("♥", "#FFF0F3", "点赞");
        right.addView(btnLike, iconLp());
        tvLike = subLabel("0");
        right.addView(tvLike, subLp());

        TextView btnDm = iconBtn("弹", "#EAF6FF", "弹幕");
        right.addView(btnDm, iconLp());
        tvDanmu = subLabel("0");
        right.addView(tvDanmu, subLp());

        TextView btnShare = iconBtn("↗", "#EFFFFFFF", "分享");
        right.addView(btnShare, iconLp());
        tvShare = subLabel("分享");
        right.addView(tvShare, subLp());

        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2);
        rlp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        rlp.rightMargin = dp(14);
        root.addView(right, rlp);

        // ── 底部信息 ──
        infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setPadding(dp(16), dp(10), dp(90), dp(14));

        tvAuthor = new TextView(this);
        tvAuthor.setTextSize(14);
        tvAuthor.setTextColor(0xFFFFFFFF);
        tvAuthor.setText("@");
        // 阴影：视频背景有亮有暗，纯白字压在浅色画面上会看不清
        tvAuthor.setShadowLayer(4f, 0f, 1f, 0xFF000000);
        infoBox.addView(tvAuthor);

        tvTitle = new TextView(this);
        tvTitle.setTextSize(13);
        tvTitle.setTextColor(0xEEFFFFFF);
        tvTitle.setMaxLines(2);
        tvTitle.setLineSpacing(dp(2), 1f);
        tvTitle.setShadowLayer(4f, 0f, 1f, 0xFF000000);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(4);
        infoBox.addView(tvTitle, tlp);

        tvStat = new TextView(this);
        tvStat.setTextSize(11);
        tvStat.setTextColor(0xCCFFFFFF);
        tvStat.setText("");
        tvStat.setShadowLayer(4f, 0f, 1f, 0xFF000000);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(-1, -2);
        slp2.topMargin = dp(5);
        infoBox.addView(tvStat, slp2);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2);
        blp.gravity = Gravity.BOTTOM;
        root.addView(infoBox, blp);

        // ── 加载中 ──
        loadingBox = new FrameLayout(this);
        ((FrameLayout) loadingBox).setBackgroundColor(0x66000000);
        loadingText = new TextView(this);
        loadingText.setText("加载中…");
        loadingText.setTextSize(14);
        loadingText.setTextColor(0xCCFFFFFF);
        loadingText.setGravity(Gravity.CENTER);
        ((FrameLayout) loadingBox).addView(loadingText, new FrameLayout.LayoutParams(-1, -1));
        loadingBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(140), dp(60));
        llp.gravity = Gravity.CENTER;
        root.addView(loadingBox, llp);

        // ── 音量浮层（中央，左侧上下滑或点胶囊时出现）──
        // 注意：这里的进度条是「纯显示」的 View，不是 SeekBar。
        // 用 SeekBar 会抢走点击——用户想点屏幕暂停，结果点到条上改了音量。
        // 所有交互统一走左侧滑动，浮层只负责把当前值显示出来。
        volBox = new LinearLayout(this);
        volBox.setOrientation(LinearLayout.VERTICAL);
        volBox.setPadding(dp(20), dp(14), dp(20), dp(16));
        volBox.setBackground(roundRect(0xEE000000, dp(14), 0x66FFFFFF));
        volBox.setVisibility(View.GONE);
        volBox.setClickable(false);
        volBox.setFocusable(false);

        volPct = new TextView(this);
        volPct.setTextSize(13);
        volPct.setTextColor(0xFFFFFFFF);
        volPct.setGravity(Gravity.CENTER);
        volPct.setText("媒体音量 60%");
        volPct.setShadowLayer(3f, 0f, 1f, 0xFF000000);
        volBox.addView(volPct, new LinearLayout.LayoutParams(-1, -2));

        volBarW = dp(230);
        FrameLayout track = new FrameLayout(this);
        View trackBg = new View(this);
        trackBg.setBackground(roundRect(0x40FFFFFF, dp(4), 0));
        track.addView(trackBg, new FrameLayout.LayoutParams(-1, -1));
        volFill = new View(this);
        volFill.setBackground(roundRect(0xFFFF2C55, dp(4), 0));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(0, -1);
        track.addView(volFill, flp);

        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(volBarW, dp(8));
        tlp2.topMargin = dp(12);
        volBox.addView(track, tlp2);

        FrameLayout.LayoutParams vblp = new FrameLayout.LayoutParams(-2, -2);
        vblp.gravity = Gravity.CENTER;
        root.addView(volBox, vblp);

        setContentView(root);

        initAudio();

        // 顶部提示 8 秒后自动消失，别一直挡着画面
        ui.postDelayed(hideHint, 8000);
    }

    GradientDrawable circle(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    GradientDrawable roundRect(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        g.setColor(color);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    TextView iconBtn(String text, String colorStr, final String label) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setBackground(circle(0x33000000, dp(24), 0x44FFFFFF));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toast(label + "（原版功能，这里仅示意）");
            }
        });
        return t;
    }

    LinearLayout.LayoutParams iconLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(46), dp(46));
        p.topMargin = dp(16);
        return p;
    }

    LinearLayout.LayoutParams subLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(4);
        return p;
    }

    TextView subLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(0xEEFFFFFF);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ══════════════════════ 数据 ══════════════════════

    void loadPage() {
        if (loadingPage || endOfFeed) return;
        loadingPage = true;
        final int want = page + 1;

        new Thread(new Runnable() {
            public void run() {
                List<Feed.Video> got = null;
                String err = null;
                try {
                    got = Feed.hot(want);
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }
                final List<Feed.Video> fg = got;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        loadingPage = false;
                        if (fe != null) {
                            toast("获取列表失败：" + fe);
                            if (list.isEmpty()) showLoading("网络不通，点屏幕重试");
                            return;
                        }
                        if (fg == null || fg.isEmpty()) {
                            endOfFeed = true;
                            return;
                        }
                        page = want;
                        list.addAll(fg);
                        // 控制内存：超出上限就丢弃前面的
                        while (list.size() > PAGE_KEEP) {
                            list.remove(0);
                            idx = Math.max(0, idx - 1);
                        }
                        if (idx == 0 && mp == null && pending == null) play(0);
                    }
                });
            }
        }, "feed-load").start();
    }

    // ══════════════════════ 播放 ══════════════════════

    void play(final int i) {
        if (i < 0 || i >= list.size()) return;
        idx = i;
        final Feed.Video v = list.get(i);

        showInfo(v);
        releasePlayer();
        showLoading("加载中…");

        pending = v;
        if (surfaceReady) startResolve(v);

        // 预取后面两条的播放地址，切换时不用再等网络往返
        prefetch(i + 1);
        prefetch(i + 2);

        // 快到末尾就预取下一页
        if (i >= list.size() - 3) loadPage();
    }

    /** 后台把播放地址提前解析好（只解析，不下载视频） */
    void prefetch(final int i) {
        if (i < 0 || i >= list.size()) return;
        new Thread(new Runnable() {
            public void run() {
                Feed.Video v = list.get(i);
                if (v != null && !v.resolved) Feed.resolve(v);
            }
        }, "prefetch-" + i).start();
    }

    void startResolve(final Feed.Video v) {
        preparing = true;
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = Feed.resolve(v);
                ui.post(new Runnable() {
                    public void run() {
                        preparing = false;
                        if (pending != v) return;
                        if (!ok) {
                            showLoading("这条拿不到地址，1 秒后跳到下一条");
                            ui.postDelayed(new Runnable() {
                                public void run() { next(); }
                            }, 1000);
                            return;
                        }
                        startPlayer(v);
                    }
                });
            }
        }, "feed-resolve").start();
    }

    void startPlayer(Feed.Video v) {
        releasePlayer();
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if (sh != null && surfaceReady) mp.setDisplay(sh);

            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) {
                    hideLoading();
                    paused = false;
                    try {
                        fitVideo(m.getVideoWidth(), m.getVideoHeight());
                        m.start();
                    } catch (Throwable t) {
                        Log.w(TAG, "start fail " + t);
                    }
                }
            });
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    fitVideo(w, h);
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer m) {
                    // 播完自动下一条，和抖音一致
                    ui.post(new Runnable() { public void run() { next(); } });
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.w(TAG, "player error " + what + "/" + extra
                            + " 当前=" + (idx < list.size() ? list.get(idx).bvid : "?"));
                    showLoading("这条播不了（" + what + "），跳到下一条");
                    ui.postDelayed(new Runnable() {
                        public void run() { next(); }
                    }, 1200);
                    return true;
                }
            });

            mp.setDataSource(v.local);
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "startPlayer fail " + t);
            toast("播放器启动失败：" + t);
        }
    }

    void releasePlayer() {
        if (mp != null) {
            try { mp.reset(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
        paused = false;
    }

    /** 按视频比例铺满屏幕（横屏视频铺满，竖屏视频高度铺满、左右留黑） */
    void fitVideo(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        int sw = root.getWidth();
        int shh = root.getHeight();
        if (sw <= 0 || shh <= 0) return;

        float vr = (float) vw / (float) vh;
        float sr = (float) sw / (float) shh;

        int w, h;
        if (vr > sr) {          // 视频比屏幕更宽 → 按宽度铺满
            w = sw;
            h = (int) (sw / vr);
        } else {                // 视频更高 → 按高度铺满
            h = shh;
            w = (int) (shh * vr);
        }
        ViewGroup.LayoutParams lp = sv.getLayoutParams();
        lp.width = w;
        lp.height = h;
        sv.setLayoutParams(lp);
    }

    // ══════════════════════ 信息展示 ══════════════════════

    void showInfo(Feed.Video v) {
        tvAuthor.setText("@" + (v.author.length() > 0 ? v.author : "B站"));
        tvTitle.setText(v.title != null ? v.title : "");
        tvLike.setText(Feed.cnt(v.like));
        tvDanmu.setText(Feed.cnt(v.danmaku));
        StringBuilder sb = new StringBuilder();
        sb.append(Feed.cnt(v.play)).append(" 播放");
        if (v.duration > 0) sb.append(" · ").append(Feed.dur(v.duration));
        if (v.w > 0) sb.append(" · ").append(v.w).append("x").append(v.h);
        tvStat.setText(sb.toString());
        tvIndex.setText((idx + 1) + "/" + list.size());
        infoBox.setVisibility(View.VISIBLE);
    }

    void showLoading(String s) {
        loadingText.setText(s);
        loadingBox.setVisibility(View.VISIBLE);
    }

    void hideLoading() {
        loadingBox.setVisibility(View.GONE);
    }

    void next() {
        if (idx + 1 < list.size()) play(idx + 1);
        else { loadPage(); toast("正在加载更多…"); }
    }

    void prev() {
        if (idx > 0) play(idx - 1);
        else toast("已经是第一条");
    }

    void togglePlay() {
        if (mp == null) return;
        try {
            if (mp.isPlaying()) {
                mp.pause();
                paused = true;
                showHint("已暂停");
            } else {
                mp.start();
                paused = false;
                hideLoading();
            }
        } catch (Throwable ignored) {}
    }

    void showHint(String s) {
        tvHint.setText(s);
        tvHint.setVisibility(View.VISIBLE);
        ui.removeCallbacks(restoreHint);
        ui.removeCallbacks(hideHint);
        ui.postDelayed(restoreHint, 1500);
    }

    final Runnable restoreHint = new Runnable() {
        public void run() {
            tvHint.setText("上滑看下一条 · 左侧上下滑调音量 · 单击暂停");
            ui.postDelayed(hideHint, 2500);
        }
    };

    final Runnable hideHint = new Runnable() {
        public void run() { tvHint.setVisibility(View.GONE); }
    };

    // ══════════════════════ 音量 ══════════════════════
    //
    // T70L 是块平板，没有实体音量键，进系统设置翻音量非常麻烦；而短视频
    // 原始响度差异又极大（B 站有的源很炸），所以必须把这个入口搬到播放页里。
    // 两个入口共用一份状态：
    //   ① 屏幕左侧 1/4 竖直滑动 —— 手指不用离开画面，最接近抖音手感
    //   ② 左上角音量胶囊 —— 点开出现滑块，可以精确拖到某一档
    // 调过的值会记住，下次打开抖音直接恢复，不用每次重调。

    void initAudio() {
        try {
            am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            volMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (volMax <= 0) volMax = 15;

            int saved = getPreferences(MODE_PRIVATE).getInt("vol", -1);
            if (saved >= 0 && saved <= volMax) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0);
                Log.i(TAG, "恢复上次音量 " + saved + "/" + volMax);
            }
            refreshVolLabel();
        } catch (Throwable t) {
            Log.w(TAG, "initAudio fail " + t);
        }
    }

    int curVol() {
        try { return am == null ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC); }
        catch (Throwable t) { return 0; }
    }

    int pctOf(int v) {
        return volMax > 0 ? Math.round(v * 100f / volMax) : 0;
    }

    /** 真正改音量 + 刷新所有 UI，remember=true 时持久化 */
    void applyVol(int v, boolean remember) {
        if (v < 0) v = 0;
        if (v > volMax) v = volMax;
        try {
            if (am != null) am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        } catch (Throwable t) {
            Log.w(TAG, "setStreamVolume fail " + t);
        }
        refreshVolLabel();
        Log.d(TAG, "音量 -> " + v + "/" + volMax + " (" + pctOf(v) + "%)");        if (remember) {
            try {
                getPreferences(MODE_PRIVATE).edit().putInt("vol", v).apply();
            } catch (Throwable ignored) {}
        }
    }

    void refreshVolLabel() {
        int v = curVol();
        int pct = pctOf(v);
        volPct.setText(v == 0 ? "已静音" : ("媒体音量 " + pct + "%"));
        tvVol.setText("音量 " + pct + "%");
        if (volFill != null) {
            int w = (int) (volBarW * pct / 100f);
            ViewGroup.LayoutParams lp = volFill.getLayoutParams();
            if (lp.width != w) {
                lp.width = w;
                volFill.setLayoutParams(lp);
            }
        }
    }

    /** 显示浮层；longStay=true 用于点击胶囊（留久一点方便看清） */
    void showVol(boolean longStay) {
        if (am == null) return;
        refreshVolLabel();
        volBox.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideVol);
        ui.postDelayed(hideVol, longStay ? 3500 : 1600);
    }

    final Runnable hideVol = new Runnable() {
        public void run() {
            if (volBox != null) volBox.setVisibility(View.GONE);
        }
    };

    // ══════════════════════ 手势 ══════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = root != null && root.getWidth() > 0 ? root.getWidth() : 1280;
        int h = root != null && root.getHeight() > 0 ? root.getHeight() : 800;

        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                downX = e.getX();
                downT = System.currentTimeMillis();
                // 屏幕左侧 1/4 起手 = 调音量；其余区域照旧是切条
                volMode = am != null && e.getX() < w * 0.25f;
                downVolV = e.getY();
                volAtDown = curVol();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!volMode) return true;
                float dyv = e.getY() - downVolV;
                if (Math.abs(dyv) < dp(5)) return true;      // 手指抖动不响应
                // 竖直滑满一屏 ≈ 走完全量程。屏只有 800px 高、系统只有 15 档，
                // 再灵敏就滑一下就到底了，粗调靠手指、精调靠点开的滑块
                int target = volAtDown + (int) (-dyv * volMax / (float) h);
                if (target < 0) target = 0;
                if (target > volMax) target = volMax;
                if (target != curVol()) {
                    applyVol(target, true);
                    showVol(false);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (volMode) {
                    volMode = false;
                    ui.removeCallbacks(hideVol);
                    ui.postDelayed(hideVol, 1200);
                    return true;                              // 不当成切条
                }
                float dy = e.getY() - downY;
                float dx = e.getX() - downX;
                long dt = System.currentTimeMillis() - downT;

                if (Math.abs(dy) > dp(55) && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                    if (dy < 0) {
                        showHint("↓ 下一条");
                        next();
                    } else {
                        showHint("↑ 上一条");
                        prev();
                    }
                } else if (Math.abs(dy) < dp(14) && Math.abs(dx) < dp(14) && dt < 400) {
                    togglePlay();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                volMode = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    // ══════════════════════ Surface ══════════════════════

    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        sh = h;
        if (pending != null && !preparing) {
            startResolve(pending);
        }
    }

    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        sh = h;
    }

    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        releasePlayer();
    }

    // ══════════════════════ 工具 ══════════════════════

    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if (code == android.view.KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) { next(); return true; }
        if (code == android.view.KeyEvent.KEYCODE_DPAD_UP) { prev(); return true; }
        // 遥控器 / USB 键盘上的音量键也接住，统一走 App 内音量
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            applyVol(curVol() + 1, true); showVol(false); return true;
        }
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            applyVol(curVol() - 1, true); showVol(false); return true;
        }
        if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || code == android.view.KeyEvent.KEYCODE_ENTER) { togglePlay(); return true; }
        return super.onKeyDown(code, e);
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
