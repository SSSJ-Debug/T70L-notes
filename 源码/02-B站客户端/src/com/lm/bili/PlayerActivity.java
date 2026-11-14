package com.lm.bili;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.HashMap;
import java.util.Map;

/**
 * 播放页。
 *
 * 这台设备只有一个物理电源键（被映射成 HOME），所以返回必须自绘按钮。
 * 直链是完整 mp4（非 DASH），交给系统 MediaPlayer 硬解，1G 内存无压力。
 */
public class PlayerActivity extends Activity {

    private static final int HIDE_DELAY = 3500;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private String[] bvids;
    private long[] cids;
    private String[] titles;
    private int index;

    private VideoView video;
    private FrameLayout root;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView titleView;
    private PlayPauseView playBtn;
    private TextView curTime;
    private TextView durTime;
    private TextView loadingView;
    private SeekBar seek;
    private boolean dragging = false;
    private boolean controlsVisible = true;

    private final Runnable hideControls = new Runnable() {
        public void run() {
            setControlsVisible(false);
        }
    };

    private final Runnable tick = new Runnable() {
        public void run() {
            if (video != null && video.isPlaying() && !dragging) {
                int pos = video.getCurrentPosition();
                int dur = video.getDuration();
                if (dur > 0) {
                    seek.setMax(dur);
                    seek.setProgress(pos);
                    curTime.setText(fmt(pos));
                }
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        bvids = getIntent().getStringArrayExtra("bvids");
        cids = getIntent().getLongArrayExtra("cids");
        titles = getIntent().getStringArrayExtra("titles");
        index = getIntent().getIntExtra("index", 0);
        if (bvids == null || cids == null || bvids.length == 0) {
            finish();
            return;
        }

        buildUi();
        play(index);
        ui.postDelayed(tick, 500);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        video = new VideoView(this);
        // VideoView 自己按视频比例算尺寸，但 FrameLayout 默认把子 View 放左上角，
        // 竖屏视频就会贴左边。用 gravity=CENTER 让它居中留黑边。
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.gravity = Gravity.CENTER;
        root.addView(video, vlp);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                loadingView.setVisibility(View.GONE);
                durTime.setText(fmt(mp.getDuration()));
                mp.setLooping(false);
                video.start();
                playBtn.setPlaying(true);
                scheduleHide();
            }
        });

        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) {
                next();
            }
        });

        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                loadingView.setVisibility(View.GONE);
                toastText("播放失败（" + what + "/" + extra + "），跳到下一个");
                ui.postDelayed(new Runnable() {
                    public void run() {
                        next();
                    }
                }, 1200);
                return true;
            }
        });

        // 点击画面切换底部控制条。
        // 注意：VideoView 内部是 SurfaceView，事件不会冒泡到父容器，
        // 必须直接挂在 video 上，否则点击无效（实测 2026-11-05）。
        video.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    setControlsVisible(!controlsVisible);
                }
                return true;
            }
        });

        // ---- 顶部控制条 ----
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable tb = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000});
        topBar.setBackground(tb);
        topBar.setPadding(28, 0, 28, 0);
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 110, Gravity.TOP));

        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextColor(Color.WHITE);
        back.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(26);
        pill.setColor(0x33FFFFFF);
        back.setBackground(pill);
        back.setPadding(26, 0, 26, 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        topBar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 52));

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = 24;
        topBar.addView(titleView, tlp);

        // ---- 底部控制条 ----
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bb = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000});
        bottomBar.setBackground(bb);
        bottomBar.setPadding(36, 0, 36, 0);
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 130, Gravity.BOTTOM));

        playBtn = new PlayPauseView(this);
        playBtn.setPlaying(true);
        playBtn.setContentDescription("btn_play");
        playBtn.setClickable(true);
        playBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (video.isPlaying()) {
                    video.pause();
                    playBtn.setPlaying(false);
                    ui.removeCallbacks(hideControls);
                } else {
                    video.start();
                    playBtn.setPlaying(true);
                    scheduleHide();
                }
            }
        });
        bottomBar.addView(playBtn, new LinearLayout.LayoutParams(96, 96));

        curTime = timeLabel("00:00");
        bottomBar.addView(curTime);

        seek = new SeekBar(this);
        seek.setContentDescription("seek");
        styleSeekBar(seek);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = 16;
        slp.rightMargin = 16;
        bottomBar.addView(seek, slp);

        durTime = timeLabel("00:00");
        bottomBar.addView(durTime);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) curTime.setText(fmt(p));
            }

            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
                ui.removeCallbacks(hideControls);
            }

            public void onStopTrackingTouch(SeekBar sb) {
                video.seekTo(sb.getProgress());
                dragging = false;
                scheduleHide();
            }
        });

        // ---- 加载提示 ----
        loadingView = new TextView(this);
        loadingView.setText("正在取视频地址…");
        loadingView.setTextColor(Color.WHITE);
        loadingView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28);
        loadingView.setGravity(Gravity.CENTER);
        root.addView(loadingView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView timeLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFDDDDDD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(80);
        return t;
    }

    /**
     * 只切换底部控制条。
     * 顶部（返回按钮 + 标题）常驻不隐藏 —— 本机只有一个电源键且被映射成 HOME，
     * 返回入口必须随时可达，否则用户会卡在播放页出不去。
     */
    private void setControlsVisible(boolean show) {
        controlsVisible = show;
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) scheduleHide();
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideControls);
        ui.postDelayed(hideControls, HIDE_DELAY);
    }

    private void toastText(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 播放

    private void play(final int i) {
        if (i < 0 || i >= bvids.length) return;
        index = i;
        loadingView.setVisibility(View.VISIBLE);
        loadingView.setText("正在取视频地址…");
        titleView.setText(titles != null && i < titles.length ? titles[i] : bvids[i]);
        curTime.setText("00:00");
        durTime.setText("00:00");
        seek.setProgress(0);

        final String bvid = bvids[i];
        final long cid = cids[i];

        new Thread(new Runnable() {
            public void run() {
                try {
                    final String url = BiliApi.playUrl(bvid, cid);
                    ui.post(new Runnable() {
                        public void run() {
                            if (i != index) return;
                            loadingView.setText("缓冲中…");
                            setVideoUriWithHeaders(Uri.parse(url));
                            video.requestFocus();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            loadingView.setText("取地址失败：" + e.getMessage());
                            toastText("取地址失败，跳到下一个");
                            ui.postDelayed(new Runnable() {
                                public void run() {
                                    next();
                                }
                            }, 1500);
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Android 4.4 的 VideoView 只公开了 setVideoURI(Uri)，
     * 带请求头的重载是 private。bilivideo 有防盗链，必须带 Referer，
     * 因此反射调用私有重载；失败则降级为无请求头。
     */
    private void setVideoUriWithHeaders(Uri uri) {
        try {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Referer", "https://www.bilibili.com/");
            headers.put("User-Agent", BiliApi.UA);
            java.lang.reflect.Method m = VideoView.class.getDeclaredMethod(
                    "setVideoURI", Uri.class, Map.class);
            m.setAccessible(true);
            m.invoke(video, uri, headers);
        } catch (Throwable t) {
            video.setVideoURI(uri);
        }
    }

    private void next() {
        if (bvids.length <= 1) {
            // 只有一个视频时循环自己
            play(index);
            return;
        }
        int n = index + 1;
        if (n >= bvids.length) n = 0;
        play(n);
    }

    /** 把 4.4 默认的黄色 holo 进度条换成 B 站粉 + 白色圆点滑块 */
    private void styleSeekBar(SeekBar sb) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x40FFFFFF);
        bg.setCornerRadius(5);
        bg.setSize(1000, 8);

        GradientDrawable prog = new GradientDrawable();
        prog.setColor(0xFFFB7299);
        prog.setCornerRadius(5);
        prog.setSize(1000, 8);

        ClipDrawable clip = new ClipDrawable(prog, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{bg, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        sb.setProgressDrawable(ld);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        thumb.setSize(26, 26);
        sb.setThumb(thumb);
        sb.setThumbOffset(0);
        sb.setPadding(0, 0, 0, 0);
    }

    private static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        return String.format("%d:%02d", total / 60, total % 60);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) {
            video.pause();
            playBtn.setPlaying(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (video != null) {
            try {
                video.stopPlayback();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 自绘播放/暂停图标。
     * 实测：Android 4.4 的 DroidSans 没有 U+275A（❚）字形，
     * 用文字画暂停键会显示成空白，只能用 Canvas 画。
     */
    static class PlayPauseView extends android.view.View {
        private boolean playing = false;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tri = new Path();
        private final RectF rf = new RectF();

        public PlayPauseView(android.content.Context c) {
            super(c);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
        }

        public void setPlaying(boolean p) {
            if (playing != p) {
                playing = p;
                invalidate();
            }
        }

        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            if (playing) {
                // 两根圆角竖条 = 暂停键
                float bw = 9f, gap = 11f, bh = 34f;
                rf.set(cx - gap - bw, cy - bh / 2, cx - gap, cy + bh / 2);
                canvas.drawRoundRect(rf, 3, 3, paint);
                rf.set(cx + gap, cy - bh / 2, cx + gap + bw, cy + bh / 2);
                canvas.drawRoundRect(rf, 3, 3, paint);
            } else {
                // 三角形 = 播放键
                tri.reset();
                tri.moveTo(cx - 11, cy - 20);
                tri.lineTo(cx - 11, cy + 20);
                tri.lineTo(cx + 22, cy);
                tri.close();
                canvas.drawPath(tri, paint);
            }
        }
    }
}
