package com.lm.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 听歌 —— 搜歌即听。
 *
 * 布局（横屏 1280×800）：
 *   ┌───────────────┬──────────────────────────────┐
 *   │ 搜索框         │   [封面]  歌名                │
 *   │ 历史标签       │           歌手 / 专辑 / 音源   │
 *   │ ─────────     │                              │
 *   │ 结果列表       │   歌词（当前行高亮）           │
 *   │               │   进度条                      │
 *   │               │   ⏮  ▶  ⏭   随机 循环   音量   │
 *   └───────────────┴──────────────────────────────┘
 *
 * 语音点歌沿用：am start -a com.lm.music.PLAY -e q "晴天 周杰伦"
 */
public class MainActivity extends Activity {

    static final String TAG = "MUSIC";

    /**
     * ★ 必须比任何网络请求都早设置。
     * T70L 没有 IPv6 路由，而 Java 的 DNS 默认优先返回 AAAA，
     * 导致 music-api.gdstudio.xyz 直接 ENETUNREACH（网易源因此整源失效）。
     * 放在静态块里 = 类一加载就生效，早于 onCreate 里的任何线程。
     */
    static {
        try { System.setProperty("java.net.preferIPv4Stack", "true"); } catch (Throwable ignored) {}
    }

    static final int BG = 0xFF0A0E15;
    static final int PANEL = 0xFF0E131C;
    static final int CARD = 0xFF161D29;
    static final int CARD2 = 0xFF1B2331;
    static final int LINE = 0xFF212A38;
    static final int TEXT = 0xFFEDF2FA;
    static final int DIM = 0xFF7E8A9C;
    static final int DIM2 = 0xFF4C5666;
    static final int ACCENT = 0xFF7C5CFF;
    static final int ACCENT2 = 0xFF3F7BEA;

    static final int MODE_ORDER = 0, MODE_ONE = 1, MODE_SHUFFLE = 2;

    /** 当前大封面显示的 url，用来判断兜底解析后要不要换图 */
    String coverUrlShown = "";

    // 左栏
    EditText input;
    ListView list;
    SongAdapter adapter;
    LinearLayout chips;
    HorizontalScrollView chipScroll;

    // 右栏
    CoverLoader.CoverView cover;
    TextView tvTitle, tvArtist, tvMeta, tvStatus, tvNow, tvAll, tvMode;
    Lrc.LyricView lyricView;
    ProgressView progress;
    VolumeBar vbar;
    TextView vtxt;
    IconBtn btnPlay;

    // 数据
    final List<Song> songs = new ArrayList<Song>();
    int current = -1;
    int playToken = 0;
    int mode = MODE_ORDER;
    MediaPlayer mp;
    boolean prepared = false, wantPlay = false, dragging = false;
    long durationMs = 0;
    Lrc lrc;
    PowerManager.WakeLock wake;
    final Handler ui = new Handler();
    boolean searching = false;
    String lastQuery = "";
    SharedPreferences sp;
    final Random rnd = new Random();

    // ───────────────────────── 生命周期 ─────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        U = Math.max(dm.widthPixels, dm.heightPixels) / 1280f;   // 长边对齐 1280 设计稿
        Log.i(TAG, "屏幕 " + dm.widthPixels + "x" + dm.heightPixels + " density=" + dm.density
                + " → 缩放 " + U);
        sp = getSharedPreferences("music", MODE_PRIVATE);
        buildUi();
        syncVolume();
        refreshChips();
        StreamProxy.start();
        // 不再预热 Bili.init()：改用免签的 all/v2 后不需要 buvid/WBI 密钥，
        // 而且带着旧 buvid 反而会被风控（见 Bili 顶部说明）。
        status("搜歌名或歌手，也可以对小智说「放 晴天」");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        handleIntent(it);
    }

    void handleIntent(Intent it) {
        if (it == null) return;
        String q = it.getStringExtra("q");
        if (q == null || q.trim().length() == 0) q = it.getStringExtra("keyword");
        String act = it.getAction();
        if (act != null && act.equals("com.lm.music.PLAY") && q != null && q.trim().length() > 0) {
            input.setText(q);
            input.clearFocus();
            doSearch(q, true);
        }
    }

    @Override
    protected void onDestroy() {
        release();
        super.onDestroy();
    }

    // ───────────────────────── UI ─────────────────────────

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        root.addView(buildLeft(), weight(33));
        root.addView(buildRight(), weight(67));
        setContentView(root);
    }

    LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, w);
        return p;
    }

    View buildLeft() {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackgroundColor(PANEL);
        left.setPadding(u(18), u(16), u(18), 0);

        // 标题行
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = new TextView(this);
        logo.setText("听歌");
        logo.setTextColor(TEXT);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(19));
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(logo);
        TextView sub = new TextView(this);
        sub.setText("  全 网 搜 索");
        sub.setTextColor(DIM2);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(11));
        head.addView(sub);
        left.addView(head, new LinearLayout.LayoutParams(-1, u(30)));

        // 搜索行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("歌名 / 歌手");
        input.setHintTextColor(DIM2);
        input.setTextColor(TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(15));
        input.setBackground(round(CARD, 12, 0));
        input.setPadding(u(14), 0, u(14), 0);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                doSearch(input.getText().toString(), false);
                return true;
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, u(46), 1f));

        TextView go = new TextView(this);
        go.setText("搜索");
        go.setTextColor(0xFFFFFFFF);
        go.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(14));
        go.setGravity(Gravity.CENTER);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setBackground(round(ACCENT, 12, 0));
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doSearch(input.getText().toString(), false); }
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(u(72), u(46));
        gp.leftMargin = u(10);
        row.addView(go, gp);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, u(46));
        rp.topMargin = u(12);
        left.addView(row, rp);

        // 历史标签
        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chips, new LinearLayout.LayoutParams(-2, u(36)));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, u(36));
        cp.topMargin = u(10);
        left.addView(chipScroll, cp);

        // 列表
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setBackgroundColor(PANEL);
        list.setCacheColorHint(0);
        list.setVerticalScrollBarEnabled(false);
        adapter = new SongAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { play(pos); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = u(8);
        left.addView(list, lp);

        return left;
    }

    View buildRight() {
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackgroundColor(BG);
        right.setPadding(u(30), u(22), u(30), u(18));

        // ── 顶部：封面 + 信息
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        cover = new CoverLoader.CoverView(this);
        cover.setRadius(u(14));
        top.addView(cover, new LinearLayout.LayoutParams(u(200), u(200)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(u(24), 0, 0, 0);

        tvTitle = new TextView(this);
        tvTitle.setText("还没在放歌");
        tvTitle.setTextColor(TEXT);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(27));
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setMaxLines(2);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(tvTitle);

        tvArtist = new TextView(this);
        tvArtist.setText("选一首开始");
        tvArtist.setTextColor(0xFFAFBBD0);
        tvArtist.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(15));
        tvArtist.setSingleLine(true);
        tvArtist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = u(6);
        info.addView(tvArtist, ap);

        tvMeta = new TextView(this);
        tvMeta.setText("");
        tvMeta.setTextColor(DIM);
        tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12));
        tvMeta.setSingleLine(true);
        LinearLayout.LayoutParams mp2 = new LinearLayout.LayoutParams(-1, -2);
        mp2.topMargin = u(4);
        info.addView(tvMeta, mp2);

        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        right.addView(top, new LinearLayout.LayoutParams(-1, u(210)));

        // ── 歌词
        lyricView = new Lrc.LyricView(this);
        lyricView.setScale(U);
        LinearLayout.LayoutParams lvp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lvp.topMargin = u(8);
        right.addView(lyricView, lvp);

        // ── 进度
        progress = new ProgressView(this);
        LinearLayout.LayoutParams pvp = new LinearLayout.LayoutParams(-1, u(26));
        pvp.topMargin = u(4);
        right.addView(progress, pvp);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        tvNow = timeText("0:00");
        tvAll = timeText("0:00");
        tvAll.setGravity(Gravity.RIGHT);
        timeRow.addView(tvNow, new LinearLayout.LayoutParams(0, -2, 1f));
        timeRow.addView(tvAll, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(-1, -2);
        trp.topMargin = u(2);
        right.addView(timeRow, trp);

        // ── 控制
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER_VERTICAL);

        btnPlay = new IconBtn(this, IconBtn.PLAY);
        View prev = new IconBtn(this, IconBtn.PREV);
        View next = new IconBtn(this, IconBtn.NEXT);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(u(66), u(66));
        bp.leftMargin = u(14);
        bp.rightMargin = u(14);
        ctl.addView(prev, new LinearLayout.LayoutParams(u(50), u(50)));
        ctl.addView(btnPlay, bp);
        ctl.addView(next, new LinearLayout.LayoutParams(u(50), u(50)));

        tvMode = new TextView(this);
        tvMode.setTextColor(DIM);
        tvMode.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12));
        tvMode.setGravity(Gravity.CENTER);
        tvMode.setBackground(round(CARD, 10, 0));
        tvMode.setText("顺序");
        tvMode.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cycleMode(); }
        });
        LinearLayout.LayoutParams mp3 = new LinearLayout.LayoutParams(u(62), u(34));
        mp3.leftMargin = u(16);
        ctl.addView(tvMode, mp3);

        View sp1 = new View(this);
        ctl.addView(sp1, new LinearLayout.LayoutParams(0, 1, 1f));

        vbar = new VolumeBar(this);
        ctl.addView(vbar, new LinearLayout.LayoutParams(u(150), u(30)));

        vtxt = new TextView(this);
        vtxt.setTextColor(DIM);
        vtxt.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(11));
        vtxt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vtp = new LinearLayout.LayoutParams(u(44), -2);
        vtp.leftMargin = u(6);
        ctl.addView(vtxt, vtp);

        LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(-1, u(72));
        cp2.topMargin = u(6);
        right.addView(ctl, cp2);

        tvStatus = new TextView(this);
        tvStatus.setTextColor(DIM2);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(11));
        tvStatus.setSingleLine(true);
        tvStatus.setEllipsize(TextUtils.TruncateAt.END);
        right.addView(tvStatus, new LinearLayout.LayoutParams(-1, -2));

        return right;
    }

    TextView timeText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(11));
        return t;
    }

    void status(String s) {
        if (tvStatus != null) tvStatus.setText(s);
    }

    // ───────────────────────── 搜索 ─────────────────────────

    void doSearch(final String q, final boolean autoPlay) {
        if (q == null) return;
        final String kw = q.trim();
        if (kw.length() == 0) return;
        if (searching) return;
        searching = true;
        lastQuery = kw;
        status("正在全网搜「" + kw + "」…");
        hideIme();
        saveHistory(kw);
        refreshChips();

        new Thread(new Runnable() {
            public void run() {
                try {
                    final List<Song> r = Sources.search(kw);
                    ui.post(new Runnable() {
                        public void run() {
                            searching = false;
                            songs.clear();
                            songs.addAll(r);
                            adapter.notifyDataSetChanged();
                            if (r.isEmpty()) {
                                status("没搜到，换个说法试试");
                                return;
                            }
                            int ne = 0, bl = 0;
                            for (int i = 0; i < r.size(); i++) {
                                if (Song.SRC_NETEASE.equals(r.get(i).source)) ne++;
                                else bl++;
                            }
                            status("共 " + r.size() + " 首（网易 " + ne + " / B站 " + bl
                                    + "）· 点一首开始听");
                            if (autoPlay) play(0);
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        public void run() {
                            searching = false;
                            status("搜索失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    void hideIme() {
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    // ── 搜索历史

    void saveHistory(String kw) {
        String all = sp.getString("history", "");
        List<String> ls = new ArrayList<String>();
        String[] parts = all.split("\\|");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if (s.length() > 0 && !s.equals(kw)) ls.add(s);
            if (ls.size() >= 9) break;
        }
        ls.add(0, kw);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size() && i < 10; i++) {
            if (i > 0) sb.append('|');
            sb.append(ls.get(i).replace("|", " "));
        }
        sp.edit().putString("history", sb.toString()).commit();
    }

    void refreshChips() {
        if (chips == null) return;
        chips.removeAllViews();
        String all = sp.getString("history", "");
        String[] parts = all.split("\\|");
        int n = 0;
        for (int i = 0; i < parts.length && n < 8; i++) {
            final String s = parts[i].trim();
            if (s.length() == 0) continue;
            TextView t = new TextView(this);
            t.setText(s);
            t.setTextColor(0xFF9AA6B8);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12));
            t.setGravity(Gravity.CENTER);
            t.setBackground(round(CARD, 14, 0));
            t.setPadding(u(12), 0, u(12), 0);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    input.setText(s);
                    doSearch(s, false);
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, u(30));
            p.rightMargin = u(8);
            chips.addView(t, p);
            n++;
        }
        chipScroll.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    // ───────────────────────── 播放 ─────────────────────────

    void play(final int idx) {
        if (idx < 0 || idx >= songs.size()) return;
        current = idx;
        final Song s = songs.get(idx);
        playToken++;
        final int token = playToken;

        adapter.notifyDataSetChanged();
        showNow(s);
        progress.setProgress(0f);
        tvNow.setText("0:00");
        tvAll.setText(s.durationSec > 0 ? fmt(s.durationSec * 1000L) : "0:00");
        status("正在取音频…");
        lrc = null;
        lyricView.setLrc(null);
        lyricView.setIdle("歌词加载中…");

        release();
        prepared = false;
        wantPlay = true;
        btnPlay.setKind(IconBtn.PAUSE);
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 400);

        new Thread(new Runnable() {
            public void run() {
                try {
                    final String url = Sources.resolve(s);
                    final String local = StreamProxy.local(url);
                    ui.post(new Runnable() {
                        public void run() {
                            if (token != playToken) return;
                            // 兜底解析可能补上了封面（网易→B站），这里补刷一次
                            if (s.coverUrl != null && s.coverUrl.length() > 8
                                    && !s.coverUrl.equals(coverUrlShown)) {
                                coverUrlShown = s.coverUrl;
                                cover.setUrl(s.coverUrl);
                            }
                            startUrl(local);
                        }
                    });
                    // 歌词单独拉，别拖慢开播
                    if (Song.SRC_NETEASE.equals(s.source)) {
                        final String ly = Sources.lyric(s);
                        ui.post(new Runnable() {
                            public void run() {
                                if (token != playToken) return;
                                applyLyric(ly);
                            }
                        });
                    } else {
                        // B 站没有歌词，得主动把"歌词加载中…"收掉，
                        // 否则界面会一直挂在那句话上，像是卡住了
                        ui.post(new Runnable() {
                            public void run() {
                                if (token != playToken) return;
                                applyLyric(null);
                            }
                        });
                    }
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        public void run() {
                            if (token != playToken) return;
                            status("放不了：" + e.getMessage());
                            btnPlay.setKind(IconBtn.PLAY);
                            wantPlay = false;
                            lyricView.setIdle("这首没放出来，换一首");
                        }
                    });
                }
            }
        }).start();
    }

    void applyLyric(String raw) {
        Lrc l = Lrc.parse(raw);
        if (l.ok()) {
            lrc = l;
            lyricView.setLrc(l);
        } else {
            lyricView.setIdle("这首歌没有歌词");
        }
    }

    void showNow(Song s) {
        coverUrlShown = s.coverUrl == null ? "" : s.coverUrl;
        cover.setUrl(s.coverUrl);
        tvTitle.setText(s.title);
        String a = s.artist == null || s.artist.length() == 0 ? "未知歌手" : s.artist;
        tvArtist.setText(a);
        StringBuilder m = new StringBuilder();
        if (s.album != null && s.album.length() > 0 && !s.album.equals("null")) m.append(s.album);
        if (s.bitrate > 0) {
            if (m.length() > 0) m.append("  ·  ");
            m.append(s.bitrate).append("kbps");
        }
        if (m.length() > 0) m.append("  ·  ");
        m.append(s.source);
        tvMeta.setText(m.toString());
    }

    void startUrl(String url) {
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            java.util.Map<String, String> h = new java.util.HashMap<String, String>();
            h.put("User-Agent", Http.UA);
            mp.setDataSource(this, android.net.Uri.parse(url), h);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer p) {
                    prepared = true;
                    durationMs = p.getDuration();
                    if (durationMs > 0) tvAll.setText(fmt(durationMs));
                    status("正在播放");
                    if (wantPlay) p.start();
                    acquireWake();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer p) {
                    onSongEnd();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer p, int w, int e) {
                    status("播放出错(" + w + "/" + e + ")，换一首试试");
                    btnPlay.setKind(IconBtn.PLAY);
                    wantPlay = false;
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Throwable e) {
            status("打不开音频：" + e.getMessage());
            btnPlay.setKind(IconBtn.PLAY);
            wantPlay = false;
        }
    }

    void onSongEnd() {
        if (songs.isEmpty()) return;
        if (mode == MODE_ONE) {
            play(current);
        } else if (mode == MODE_SHUFFLE) {
            if (songs.size() == 1) { play(0); return; }
            int n = current;
            for (int i = 0; i < 8 && n == current; i++) n = rnd.nextInt(songs.size());
            play(n);
        } else {
            if (current + 1 < songs.size()) play(current + 1);
            else {
                status("一张列表听完了，再搜点新的？");
                btnPlay.setKind(IconBtn.PLAY);
                wantPlay = false;
            }
        }
    }

    void toggle() {
        if (mp == null || !prepared) return;
        try {
            if (mp.isPlaying()) {
                mp.pause();
                wantPlay = false;
                btnPlay.setKind(IconBtn.PLAY);
                releaseWake();
            } else {
                mp.start();
                wantPlay = true;
                btnPlay.setKind(IconBtn.PAUSE);
                acquireWake();
            }
        } catch (Throwable ignored) {}
    }

    void cycleMode() {
        mode = (mode + 1) % 3;
        tvMode.setText(mode == MODE_ORDER ? "顺序" : (mode == MODE_ONE ? "单曲" : "随机"));
        tvMode.setTextColor(mode == MODE_ORDER ? DIM : 0xFFB9A6FF);
    }

    void release() {
        releaseWake();
        if (mp != null) {
            try { if (mp.isPlaying()) mp.stop(); } catch (Throwable ignored) {}
            try { mp.reset(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
        prepared = false;
    }

    void acquireWake() {
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "music");
            wake.setReferenceCounted(false);
        }
        try { if (!wake.isHeld()) wake.acquire(); } catch (Throwable ignored) {}
    }

    void releaseWake() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable ignored) {}
    }

    final Runnable tick = new Runnable() {
        public void run() {
            if (mp != null && prepared) {
                try {
                    int pos = mp.getCurrentPosition();
                    if (!dragging) {
                        tvNow.setText(fmt(pos));
                        if (durationMs > 0) progress.setProgress(pos / (float) durationMs);
                    }
                    if (lrc != null) lyricView.setTime(pos);
                } catch (Throwable ignored) {}
            }
            ui.postDelayed(this, 350);
        }
    };

    static String fmt(long ms) {
        if (ms < 0) ms = 0;
        int s = (int) (ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // ───────────────────────── 音量 ─────────────────────────

    void syncVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (vbar != null) { vbar.setMax(max); vbar.setProgress(cur); }
            if (vtxt != null && max > 0) vtxt.setText((cur * 100 / max) + "%");
        } catch (Throwable ignored) {}
    }

    void setVolume(int v) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (v < 0) v = 0;
            if (v > max) v = max;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            if (vtxt != null && max > 0) vtxt.setText((v * 100 / max) + "%");
        } catch (Throwable ignored) {}
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /**
     * 全屏等比缩放单位。
     *
     * 整套界面是按 1280×800 像素画的，问题在于不知道这台盒子跑起来
     * density 到底是 1.0 还是 1.5 —— 一旦不是 1.0，用 dp/sp 会把界面撑爆或缩没。
     * 所以这里统一按"屏宽 / 1280"缩放：设计稿多少像素，实际就按比例多少像素。
     * 字号也走这个比例（COMPLEX_UNIT_PX），不交给 sp。
     */
    float U = 1f;

    int u(float v) {
        return Math.round(v * U);
    }

    GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(u(radiusDp));
        if (strokeColor != 0) g.setStroke(u(1), strokeColor);
        return g;
    }

    // ───────────────────────── 列表 ─────────────────────────

    class SongAdapter extends BaseAdapter {
        public int getCount() { return songs.size(); }
        public Object getItem(int i) { return songs.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(u(10), u(7), u(10), u(7));

                h = new Holder();
                h.cover = new CoverLoader.CoverView(MainActivity.this);
                h.cover.setRadius(u(9));
                row.addView(h.cover, new LinearLayout.LayoutParams(u(50), u(50)));

                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(u(11), 0, 0, 0);
                h.title = new TextView(MainActivity.this);
                h.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(15));
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(h.title);
                h.sub = new TextView(MainActivity.this);
                h.sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(11));
                h.sub.setSingleLine(true);
                h.sub.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
                sp.topMargin = u(3);
                col.addView(h.sub, sp);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

                h.badge = new TextView(MainActivity.this);
                h.badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(9));
                h.badge.setGravity(Gravity.CENTER);
                h.badge.setPadding(u(6), u(2), u(6), u(2));
                row.addView(h.badge);

                row.setTag(h);
                cv = row;
            } else {
                h = (Holder) cv.getTag();
            }

            Song s = songs.get(pos);
            boolean act = pos == current;
            cv.setBackgroundColor(act ? 0xFF1D2130 : 0x00000000);
            h.title.setText(s.title);
            h.title.setTextColor(act ? 0xFFB9A6FF : TEXT);
            h.title.setTypeface(act ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            StringBuilder sb = new StringBuilder();
            sb.append(s.artist == null || s.artist.length() == 0 ? "未知歌手" : s.artist);
            if (s.durationSec > 0) sb.append("  ·  ").append(s.time());
            if (s.bitrate > 0) sb.append("  ·  ").append(s.bitrate + "k");
            h.sub.setText(sb.toString());
            h.sub.setTextColor(act ? 0xFF9B87E6 : DIM);
            h.cover.setUrl(s.coverUrl);

            h.badge.setText(s.source);
            h.badge.setTextColor(badgeText(s.source));
            h.badge.setBackground(round(badgeBg(s.source), 6, 0));
            return cv;
        }
    }

    static int badgeBg(String src) {
        if (Song.SRC_KUWO.equals(src)) return 0xFF23283A;
        if (Song.SRC_NETEASE.equals(src)) return 0xFF2A1F2E;
        return 0xFF1E2A24;
    }

    static int badgeText(String src) {
        if (Song.SRC_KUWO.equals(src)) return 0xFF9BB7FF;
        if (Song.SRC_NETEASE.equals(src)) return 0xFFE2A0C8;
        return 0xFF8FD9AE;
    }

    static class Holder {
        CoverLoader.CoverView cover;
        TextView title, sub, badge;
    }

    // ───────────────────────── 进度条 ─────────────────────────

    class ProgressView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float ratio = 0f;
        private boolean down = false;

        ProgressView(Context c) { super(c); }

        void setProgress(float r) {
            if (down) return;
            ratio = Math.max(0f, Math.min(1f, r));
            invalidate();
        }

        public boolean onTouchEvent(android.view.MotionEvent e) {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                down = true;
                dragging = true;
                pick(e.getX());
            } else if (a == android.view.MotionEvent.ACTION_MOVE) {
                pick(e.getX());
            } else if (a == android.view.MotionEvent.ACTION_UP
                    || a == android.view.MotionEvent.ACTION_CANCEL) {
                pick(e.getX());
                if (mp != null && prepared && durationMs > 0) {
                    try { mp.seekTo((int) (ratio * durationMs)); } catch (Throwable ignored) {}
                }
                down = false;
                dragging = false;
            }
            return true;
        }

        private void pick(float x) {
            ratio = Math.max(0f, Math.min(1f, x / Math.max(1, getWidth())));
            if (durationMs > 0) tvNow.setText(fmt((long) (ratio * durationMs)));
            invalidate();
        }

        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight(), bar = u(4), top = (h - bar) / 2, r = bar / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF232B3A);
            cv.drawRoundRect(new RectF(0, top, w, top + bar), r, r, p);
            float fw = w * ratio;
            if (fw > 0) {
                p.setShader(new LinearGradient(0, 0, Math.max(fw, 1), 0, ACCENT, ACCENT2, Shader.TileMode.CLAMP));
                cv.drawRoundRect(new RectF(0, top, fw, top + bar), r, r, p);
                p.setShader(null);
                p.setColor(0xFFFFFFFF);
                float cx = Math.max(u(7), Math.min(w - u(7), fw));
                cv.drawCircle(cx, h / 2f, down ? u(8) : u(6), p);
                p.setColor(0x337C5CFF);
                cv.drawCircle(cx, h / 2f, down ? u(14) : u(11), p);
            }
        }
    }

    // ───────────────────────── 音量条 ─────────────────────────

    class VolumeBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int max = 15, value = 7;

        VolumeBar(Context c) { super(c); }

        void setMax(int m) { if (m > 0) max = m; }

        void setProgress(int v) {
            value = Math.max(0, Math.min(max, v));
            invalidate();
        }

        private void pick(float x) {
            int v = Math.round(x / Math.max(1, getWidth()) * max);
            v = Math.max(0, Math.min(max, v));
            if (v != value) { value = v; setVolume(v); invalidate(); }
        }

        public boolean onTouchEvent(android.view.MotionEvent e) {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_MOVE
                    || a == android.view.MotionEvent.ACTION_UP) {
                pick(e.getX());
            }
            return true;
        }

        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight(), bar = u(5), top = (h - bar) / 2, r = bar / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF232B3A);
            cv.drawRoundRect(new RectF(0, top, w, top + bar), r, r, p);
            int fw = (int) (w * (value / (float) Math.max(1, max)));
            if (fw > 0) {
                p.setColor(0xFF4E6FA8);
                cv.drawRoundRect(new RectF(0, top, fw, top + bar), r, r, p);
            }
            p.setColor(0xFFD7E2F5);
            float cx = Math.max(u(5), Math.min(w - u(5), fw));
            cv.drawCircle(cx, h / 2f, u(5), p);
        }
    }

    // ───────────────────────── 按钮 ─────────────────────────

    class IconBtn extends View {
        static final int PLAY = 0, PAUSE = 1, PREV = 2, NEXT = 3;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int kind;
        private boolean pressed;

        IconBtn(Context c, int kind) {
            super(c);
            this.kind = kind;
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (IconBtn.this.kind == PLAY || IconBtn.this.kind == PAUSE) toggle();
                    else if (IconBtn.this.kind == NEXT) {
                        if (songs.isEmpty()) return;
                        if (mode == MODE_SHUFFLE && songs.size() > 1) {
                            int n = current;
                            for (int i = 0; i < 8 && n == current; i++) n = rnd.nextInt(songs.size());
                            play(n);
                        } else {
                            play(Math.min(songs.size() - 1, Math.max(0, current + 1)));
                        }
                    } else {
                        if (songs.isEmpty()) return;
                        if (current > 0) play(current - 1);
                        else status("已经是第一首了");
                    }
                }
            });
        }

        void setKind(int k) {
            if (kind == k) return;
            kind = k;
            invalidate();
        }

        public boolean onTouchEvent(android.view.MotionEvent e) {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) { pressed = true; invalidate(); }
            else if (a == android.view.MotionEvent.ACTION_UP
                    || a == android.view.MotionEvent.ACTION_CANCEL) { pressed = false; invalidate(); }
            return super.onTouchEvent(e);
        }

        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            boolean main = kind == PLAY || kind == PAUSE;
            float rad = Math.min(w, h) / 2f;

            p.setStyle(Paint.Style.FILL);
            if (main) {
                p.setShader(new LinearGradient(cx - rad, cy - rad, cx + rad, cy + rad,
                        pressed ? 0xFF6A4AE6 : ACCENT, pressed ? 0xFF3568CC : ACCENT2,
                        Shader.TileMode.CLAMP));
                cv.drawCircle(cx, cy, rad, p);
                p.setShader(null);
            } else {
                p.setColor(pressed ? 0xFF27314a : 0x00000000);
                cv.drawCircle(cx, cy, rad, p);
            }

            p.setColor(main ? 0xFFFFFFFF : 0xFFC6D2E4);
            float s = rad * 0.9f;
            Path path = new Path();
            if (kind == PLAY) {
                path.moveTo(cx - s * 0.30f, cy - s * 0.44f);
                path.lineTo(cx + s * 0.46f, cy);
                path.lineTo(cx - s * 0.30f, cy + s * 0.44f);
                path.close();
                cv.drawPath(path, p);
            } else if (kind == PAUSE) {
                cv.drawRoundRect(new RectF(cx - s * 0.36f, cy - s * 0.42f,
                        cx - s * 0.12f, cy + s * 0.42f), s * 0.06f, s * 0.06f, p);
                cv.drawRoundRect(new RectF(cx + s * 0.12f, cy - s * 0.42f,
                        cx + s * 0.36f, cy + s * 0.42f), s * 0.06f, s * 0.06f, p);
            } else if (kind == NEXT) {
                path.moveTo(cx - s * 0.42f, cy - s * 0.40f);
                path.lineTo(cx + s * 0.10f, cy);
                path.lineTo(cx - s * 0.42f, cy + s * 0.40f);
                path.close();
                cv.drawPath(path, p);
                cv.drawRoundRect(new RectF(cx + s * 0.18f, cy - s * 0.40f,
                        cx + s * 0.30f, cy + s * 0.40f), s * 0.05f, s * 0.05f, p);
            } else {
                path.moveTo(cx + s * 0.42f, cy - s * 0.40f);
                path.lineTo(cx - s * 0.10f, cy);
                path.lineTo(cx + s * 0.42f, cy + s * 0.40f);
                path.close();
                cv.drawPath(path, p);
                cv.drawRoundRect(new RectF(cx - s * 0.30f, cy - s * 0.40f,
                        cx - s * 0.18f, cy + s * 0.40f), s * 0.05f, s * 0.05f, p);
            }
        }
    }
}
