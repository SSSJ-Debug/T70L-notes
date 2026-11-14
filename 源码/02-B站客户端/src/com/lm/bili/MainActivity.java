package com.lm.bili;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 哔哩哔哩（极简客户端）主界面。
 *
 * 为什么不是浏览器：本机 Android 4.4 / Chromium 33，B 站 2025 前端用到
 * `?.` `??`（Chrome 80+）语法，解析阶段即失败，页面脚本一行都不执行。
 * 任何"套壳浏览器"（Via 等）内核都是系统 WebView，换壳无用。
 * 因此改走官方开放接口 + 系统 MediaPlayer，完全绕开网页渲染。
 */
public class MainActivity extends Activity {

    private static final int PINK = 0xFFFB7299;
    private static final int BG = 0xFFF4F4F4;
    private static final int CARD_W = 400;
    private static final int COVER_H = 250;
    private static final int GAP = 16;
    private static final int PAD = 24;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<BiliApi.Video> videos = new ArrayList<BiliApi.Video>();

    private int currentTab = 0;
    private ListView listView;
    private VideoAdapter adapter;
    private LinearLayout tabBar;
    private TextView[] tabViews;
    private FrameLayout content;
    private LinearLayout stateBox;
    private TextView stateText;
    private TextView stateAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        applyImmersive();
        buildUi();
        switchTab(0);
    }

    /** 隐掉底部 48px 系统导航栏，1280x800 全部给内容用 */
    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        // ---- 顶部粉色栏 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable topBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFB7299, 0xFFFC8CAC});
        top.setBackground(topBg);
        top.setPadding(PAD, 0, PAD, 0);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 72));

        TextView logo = new TextView(this);
        logo.setText("哔哩哔哩");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(logo, new LinearLayout.LayoutParams(150,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 分区 tab ----
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tabBar, new LinearLayout.LayoutParams(0, 72, 1f));

        tabViews = new TextView[BiliApi.TAB_NAMES.length];
        for (int i = 0; i < BiliApi.TAB_NAMES.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(BiliApi.TAB_NAMES[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(0xCCFFFFFF);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    switchTab(idx);
                }
            });
            tabViews[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 72);
            lp.setMargins(14, 0, 14, 0);
            tabBar.addView(t, lp);
        }

        TextView refresh = new TextView(this);
        refresh.setText("刷新");
        refresh.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24);
        refresh.setTextColor(Color.WHITE);
        refresh.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(24);
        pill.setColor(0x33FFFFFF);
        pill.setStroke(2, 0x66FFFFFF);
        refresh.setBackground(pill);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchTab(currentTab);
            }
        });
        top.addView(refresh, new LinearLayout.LayoutParams(96, 48));

        // ---- 内容区 ----
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setPadding(PAD, GAP, PAD, GAP);
        listView.setClipToPadding(false);
        listView.setCacheColorHint(0);
        listView.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new VideoAdapter();
        listView.setAdapter(adapter);
        content.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 状态层（加载 / 报错）----
        stateBox = new LinearLayout(this);
        stateBox.setOrientation(LinearLayout.VERTICAL);
        stateBox.setGravity(Gravity.CENTER);
        stateBox.setBackgroundColor(BG);
        stateBox.setVisibility(View.GONE);

        stateText = new TextView(this);
        stateText.setTextColor(0xFF666666);
        stateText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        stateText.setGravity(Gravity.CENTER);
        stateBox.addView(stateText);

        stateAction = new TextView(this);
        stateAction.setText("重新加载");
        stateAction.setTextColor(Color.WHITE);
        stateAction.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        stateAction.setGravity(Gravity.CENTER);
        GradientDrawable btn = new GradientDrawable();
        btn.setCornerRadius(28);
        btn.setColor(PINK);
        stateAction.setBackground(btn);
        stateAction.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchTab(currentTab);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(240, 56);
        blp.topMargin = 28;
        stateBox.addView(stateAction, blp);

        content.addView(stateBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void switchTab(int idx) {
        if (idx < 0 || idx >= tabViews.length) return;
        currentTab = idx;
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = (i == idx);
            tabViews[i].setTextColor(on ? Color.WHITE : 0xAAFFFFFF);
            tabViews[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        load();
    }

    private void showState(String msg, boolean showButton) {
        stateText.setText(msg);
        stateAction.setVisibility(showButton ? View.VISIBLE : View.GONE);
        stateBox.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
    }

    private void hideState() {
        stateBox.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- 数据

    private void load() {
        showState("正在加载…", false);
        final int rid = BiliApi.TAB_RIDS[currentTab];
        new Thread(new Runnable() {
            public void run() {
                try {
                    final List<BiliApi.Video> got = BiliApi.ranking(rid);
                    ui.post(new Runnable() {
                        public void run() {
                            if (rid != BiliApi.TAB_RIDS[currentTab]) return; // tab 已切换
                            videos.clear();
                            videos.addAll(got);
                            adapter.notifyDataSetChanged();
                            listView.setSelection(0);
                            if (videos.isEmpty()) {
                                showState("这个分区暂时没有内容", true);
                            } else {
                                hideState();
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            showState("加载失败：" + e.getMessage(), true);
                        }
                    });
                }
            }
        }).start();
    }

    private void openPlayer(int index) {
        String[] bvids = new String[videos.size()];
        long[] cids = new long[videos.size()];
        String[] titles = new String[videos.size()];
        for (int i = 0; i < videos.size(); i++) {
            bvids[i] = videos.get(i).bvid;
            cids[i] = videos.get(i).cid;
            titles[i] = videos.get(i).title;
        }
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("bvids", bvids);
        it.putExtra("cids", cids);
        it.putExtra("titles", titles);
        it.putExtra("index", index);
        startActivity(it);
    }

    // ---------------------------------------------------------------- 列表

    private class VideoAdapter extends BaseAdapter {
        public int getCount() {
            return (videos.size() + 2) / 3;
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            Object[] slots;
            if (convertView != null) {
                row = (LinearLayout) convertView;
                slots = (Object[]) row.getTag();
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                slots = new Object[3];
                for (int c = 0; c < 3; c++) {
                    View card = buildCard();
                    slots[c] = card;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            CARD_W, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (c > 0) lp.leftMargin = GAP;
                    row.addView(card, lp);
                }
                row.setTag(slots);
            }

            for (int c = 0; c < 3; c++) {
                int pos = position * 3 + c;
                View card = (View) slots[c];
                if (pos >= videos.size()) {
                    card.setVisibility(View.INVISIBLE);
                } else {
                    card.setVisibility(View.VISIBLE);
                    bindCard(card, videos.get(pos), pos);
                }
            }
            return row;
        }
    }

    private View buildCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        RoundImageView cover = new RoundImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFFE8E8E8);
        cover.setLayerType(View.LAYER_TYPE_SOFTWARE, null); // clipPath 在 4.4 硬件层不稳定
        card.addView(cover, new LinearLayout.LayoutParams(
                CARD_W, COVER_H));

        TextView title = new TextView(this);
        title.setTextColor(0xFF222222);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
        title.setMaxLines(2);
        title.setLineSpacing(4, 1f);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                CARD_W, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = 10;
        card.addView(title, tlp);

        TextView meta = new TextView(this);
        meta.setTextColor(0xFF999999);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                CARD_W, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = 6;
        card.addView(meta, mlp);

        return card;
    }

    private void bindCard(View card, final BiliApi.Video v, final int pos) {
        LinearLayout ll = (LinearLayout) card;
        RoundImageView cover = (RoundImageView) ll.getChildAt(0);
        TextView title = (TextView) ll.getChildAt(1);
        TextView meta = (TextView) ll.getChildAt(2);

        title.setText(v.title);
        meta.setText(v.author + "  ·  " + v.playText() + "  ·  " + v.durText());
        ImgLoader.load(v.pic, cover, CARD_W);

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                openPlayer(pos);
            }
        });
    }

    /** 圆角封面（clipPath 实现，软件层绘制） */
    static class RoundImageView extends ImageView {
        private final Path path = new Path();
        private final RectF rect = new RectF();

        public RoundImageView(android.content.Context c) {
            super(c);
        }

        protected void onDraw(Canvas canvas) {
            rect.set(0, 0, getWidth(), getHeight());
            path.reset();
            path.addRoundRect(rect, 14, 14, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(path);
            super.onDraw(canvas);
            canvas.restore();
        }
    }
}
