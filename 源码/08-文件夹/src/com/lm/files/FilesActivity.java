package com.lm.files;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 文件夹 —— 纯本地文件浏览器（替代 vulture.app.home 的 MyFullScreenWebActivity）
 *
 * 设计前提（2026-11-05 静态分析 协议分析）：
 *   原实现 = base_webview_activity 布局 + WebView.loadUrl(云端地址)。
 *   零本地逻辑，服务器一下线就永远转圈。本实现只做本地目录枚举，不联网、无 WebView。
 *
 * 内存预算：1G RAM 设备，无图片解码、无缩略图、无第三方库，ListView 复用 convertView。
 */
public class FilesActivity extends Activity {

    private static final int C_BG        = 0xFF0E0E12;
    private static final int C_BAR       = 0xFF16161C;
    private static final int C_LINE      = 0xFF22222A;
    private static final int C_TEXT      = 0xFFF2F2F4;
    private static final int C_SUB       = 0xFF8A8A96;
    private static final int C_ACCENT    = 0xFFFF6A3D;
    private static final int C_SEL       = 0xFF2A2A36;
    private static final int C_BLOCK_DIR = 0xFFFF6A3D;
    private static final int C_BLOCK_FIL = 0xFF3C3C48;
    private static final int C_BTN       = 0xFF22222A;
    private static final int C_BTN_ON    = 0xFF34343F;

    private final List<File> items = new ArrayList<File>();
    private File root;
    private File cur;
    private FileAdapter adapter;
    private TextView tvPath;
    private TextView tvInfo;
    private TextView tvEmpty;
    private TextView btnBack;
    private ListView list;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        root = Environment.getExternalStorageDirectory();
        if (root == null || !root.exists()) root = new File("/sdcard");
        cur = root;
        buildUi();
        load(cur);
    }

    // ---------------- UI ----------------

    private int px(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(C_BG);

        // 顶部路径栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(C_BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(20), px(12), px(20), px(12));

        // 「返回上一级」按钮：本机只有一个电源键，且被系统映射成 HOME，
        // 进到子目录后没法用物理键回退，所以必须给一个屏幕上的入口。
        btnBack = new TextView(this);
        btnBack.setBackgroundDrawable(makeBackBg());
        btnBack.setGravity(Gravity.CENTER);
        btnBack.setSingleLine(true);
        btnBack.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22));
        btnBack.setPadding(px(16), px(7), px(18), px(8));
        SpannableString bs = new SpannableString("\u2190  上一级");
        bs.setSpan(new ForegroundColorSpan(C_ACCENT), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        bs.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        bs.setSpan(new RelativeSizeSpan(1.18f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        btnBack.setText(bs);
        btnBack.setTextColor(C_TEXT);
        btnBack.setFocusable(false);          // 不抢方向键焦点，列表导航照旧
        btnBack.setVisibility(View.INVISIBLE);
        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                goUp();
            }
        });
        btnBack.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (cur == null || !cur.equals(root)) {
                    load(root);
                    Toast.makeText(FilesActivity.this, "回到内部存储", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = px(18);
        bar.addView(btnBack, blp);

        tvPath = new TextView(this);
        tvPath.setTextColor(C_TEXT);
        tvPath.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(26));
        tvPath.setSingleLine(true);
        tvPath.setEllipsize(TextUtils.TruncateAt.START);
        bar.addView(tvPath, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvInfo = new TextView(this);
        tvInfo.setTextColor(C_SUB);
        tvInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(20));
        tvInfo.setSingleLine(true);
        tvInfo.setGravity(Gravity.RIGHT);
        bar.addView(tvInfo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rootView.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(C_LINE);
        rootView.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // 列表 + 空状态
        FrameLayout wrap = new FrameLayout(this);
        list = new ListView(this);
        list.setBackgroundColor(C_BG);
        list.setDivider(new ColorDrawable(C_LINE));
        list.setDividerHeight(1);
        list.setSelector(makeSelector());
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setFastScrollEnabled(false);
        adapter = new FileAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < items.size()) open(items.get(position));
            }
        });
        wrap.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tvEmpty = new TextView(this);
        tvEmpty.setTextColor(C_SUB);
        tvEmpty.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24));
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setText("这个文件夹是空的");
        tvEmpty.setVisibility(View.GONE);
        wrap.addView(tvEmpty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootView.addView(wrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部提示
        TextView tip = new TextView(this);
        tip.setTextColor(C_SUB);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(19));
        tip.setGravity(Gravity.CENTER);
        tip.setBackgroundColor(C_BAR);
        tip.setPadding(0, px(10), 0, px(10));
        tip.setText("上下键选择 · 确认打开 · 左上角返回上一级（长按直达内部存储）");
        rootView.addView(tip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(rootView);
    }

    /** 返回按钮的常态/按下态背景。 */
    private StateListDrawable makeBackBg() {
        StateListDrawable sd = new StateListDrawable();
        GradientDrawable off = new GradientDrawable();
        off.setColor(C_BTN);
        off.setCornerRadius(px(8));
        GradientDrawable press = new GradientDrawable();
        press.setColor(C_BTN_ON);
        press.setCornerRadius(px(8));
        sd.addState(new int[]{android.R.attr.state_pressed}, press);
        sd.addState(new int[]{}, off);
        return sd;
    }

    private StateListDrawable makeSelector() {
        StateListDrawable sd = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(C_SEL);
        on.setCornerRadius(px(6));
        sd.addState(new int[]{android.R.attr.state_pressed}, on);
        sd.addState(new int[]{android.R.attr.state_focused}, on);
        sd.addState(new int[]{android.R.attr.state_selected}, on);
        sd.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return sd;
    }

    // ---------------- 数据 ----------------

    private void load(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        cur = dir;

        List<File> found = new ArrayList<File>();
        File[] all = dir.listFiles();
        if (all != null) {
            for (File f : all) {
                String n = f.getName();
                if (n != null && n.startsWith(".")) continue;   // 跳过隐藏项
                found.add(f);
            }
        }
        Collections.sort(found, new Comparator<File>() {
            public int compare(File a, File b) {
                boolean da = a.isDirectory();
                boolean db = b.isDirectory();
                if (da != db) return da ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        items.clear();
        items.addAll(found);
        adapter.notifyDataSetChanged();
        if (list != null) list.setSelection(0);

        tvPath.setText(prettyPath(dir));
        tvInfo.setText(freeSpace() + "  ·  " + items.size() + " 项");
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);

        // 根目录不显示返回（那时"上一级"等于退出，避免误触），用 INVISIBLE 保持路径文字不跳动
        if (btnBack != null) {
            boolean atRoot = root != null && dir.getAbsolutePath().equals(root.getAbsolutePath());
            btnBack.setVisibility(atRoot ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private String prettyPath(File dir) {
        String p = dir.getAbsolutePath();
        String r = root.getAbsolutePath();
        if (p.equals(r)) return "内部存储";
        if (p.startsWith(r)) return "内部存储" + p.substring(r.length());
        return p;
    }

    private String freeSpace() {
        try {
            StatFs sf = new StatFs(root.getAbsolutePath());
            long free = (long) sf.getAvailableBlocks() * (long) sf.getBlockSize();
            return "剩余 " + size(free);
        } catch (Throwable t) {
            return "";
        }
    }

    private String size(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.US, "%.1f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024.0);
    }

    private static boolean isImage(String ext) {
        return "png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext)
                || "bmp".equals(ext) || "gif".equals(ext) || "webp".equals(ext);
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    private void open(File f) {
        if (f.isDirectory()) {
            load(f);
            return;
        }
        String ext = extOf(f.getName());

        // 图片走内置查看器：本机实测没有任何应用能 resolve image/png
        if (isImage(ext)) {
            ArrayList<String> pics = new ArrayList<String>();
            int idx = 0;
            for (File x : items) {
                if (!x.isFile()) continue;
                if (!isImage(extOf(x.getName()))) continue;
                if (x.equals(f)) idx = pics.size();
                pics.add(x.getAbsolutePath());
            }
            if (pics.isEmpty()) {
                Toast.makeText(this, "没有可显示的图片", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, ImageActivity.class);
            i.putStringArrayListExtra("paths", pics);
            i.putExtra("index", idx);
            startActivity(i);
            return;
        }

        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if ("apk".equals(ext)) mime = "application/vnd.android.package-archive";
        if (mime == null) {
            // 刻意不做通配类型兜底：那会把「修改接入点」「固定拨号列表」这类
            // 声明了通配接收的组件全列进选择框，选了必崩（本机实测）。
            Toast.makeText(this, "不知道用什么打开这个文件", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(f), mime);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "系统里没有能打开这种文件的应用", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "打不开：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void goUp() {
        if (cur == null) { finish(); return; }
        if (cur.getAbsolutePath().equals(root.getAbsolutePath())) { finish(); return; }
        File parent = cur.getParentFile();
        if (parent == null || !parent.exists()) { finish(); return; }
        load(parent);
    }

    @Override
    public void onBackPressed() {
        goUp();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            goUp();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---------------- 适配器 ----------------

    private class FileAdapter extends BaseAdapter {
        public int getCount() { return items.size(); }
        public Object getItem(int p) { return items.get(p); }
        public long getItemId(int p) { return p; }

        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView name;
            TextView sub;
            View block;

            if (convertView == null) {
                row = new LinearLayout(FilesActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(px(20), px(12), px(20), px(12));

                block = new View(FilesActivity.this);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(38), px(38));
                row.addView(block, blp);

                LinearLayout col = new LinearLayout(FilesActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.leftMargin = px(18);
                row.addView(col, clp);

                name = new TextView(FilesActivity.this);
                name.setTextColor(C_TEXT);
                name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(27));
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                col.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                sub = new TextView(FilesActivity.this);
                sub.setTextColor(C_SUB);
                sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(19));
                sub.setSingleLine(true);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.topMargin = px(3);
                col.addView(sub, slp);

                row.setTag(new Object[]{block, name, sub});
                convertView = row;
            } else {
                Object[] tags = (Object[]) convertView.getTag();
                block = (View) tags[0];
                name = (TextView) tags[1];
                sub = (TextView) tags[2];
            }

            File f = items.get(position);
            boolean dir = f.isDirectory();

            GradientDrawable g = new GradientDrawable();
            g.setColor(dir ? C_BLOCK_DIR : C_BLOCK_FIL);
            g.setCornerRadius(px(7));
            block.setBackgroundDrawable(g);

            name.setText(f.getName());
            if (dir) {
                int n = 0;
                File[] kids = f.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        String kn = k.getName();
                        if (kn == null || kn.startsWith(".")) continue;
                        n++;
                    }
                }
                sub.setText("文件夹 · " + n + " 项");
            } else {
                sub.setText(size(f.length()) + " · " + fmt.format(new Date(f.lastModified())));
            }
            return convertView;
        }
    }
}
