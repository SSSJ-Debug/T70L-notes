package com.lm.files;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 极简图片查看器。
 *
 * 为什么内置：实测本机 `am start -t image/png` 直接返回
 * "unable to resolve Intent"，系统里没有任何图库类应用。
 * 靠外部 Intent 看图不可行，只能自带。
 *
 * 内存约束（1G RAM）：
 *   inJustDecodeBounds 先量尺寸，再按屏幕大小做 2 的幂采样，
 *   切换时显式 recycle 上一张，避免连翻多张后 OOM。
 *
 * 交互（2026-11-05 补）：
 *   本机只有一个电源键，且被系统映射为 HOME，进不了 onBackPressed，
 *   所以返回入口必须做在屏幕上；翻页同时支持 触摸滑动 与 方向键。
 */
public class ImageActivity extends Activity {

    private static final int C_TEXT = 0xFFF2F2F4;

    private ArrayList<String> paths = new ArrayList<String>();
    private int index = 0;
    private ImageView iv;
    private TextView tvLabel;
    private Bitmap bmp;

    // 触摸滑动状态
    private float downX, downY;
    private long downT;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        try {
            ArrayList<String> p = getIntent().getStringArrayListExtra("paths");
            if (p != null) paths = p;
            index = getIntent().getIntExtra("index", 0);
        } catch (Throwable ignored) {
        }
        if (index < 0 || index >= paths.size()) index = 0;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(Color.BLACK);
        root.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶部条：返回按钮 + 文件名/页码 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xE6000000);
        bar.setPadding(px(14), px(12), px(18), px(12));

        TextView btnBack = new TextView(this);
        btnBack.setBackgroundDrawable(makeBtnBg());
        btnBack.setGravity(Gravity.CENTER);
        btnBack.setSingleLine(true);
        btnBack.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22));
        btnBack.setPadding(px(16), px(7), px(18), px(8));
        SpannableString bs = new SpannableString("\u2190  返回");
        bs.setSpan(new ForegroundColorSpan(0xFFFF6A3D), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        bs.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        bs.setSpan(new RelativeSizeSpan(1.18f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        btnBack.setText(bs);
        btnBack.setTextColor(C_TEXT);
        btnBack.setFocusable(false);
        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = px(16);
        bar.addView(btnBack, blp);

        tvLabel = new TextView(this);
        tvLabel.setTextColor(C_TEXT);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(21));
        tvLabel.setSingleLine(true);
        tvLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tvLabel.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(tvLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.gravity = Gravity.TOP;
        root.addView(bar, bl);

        // ---- 底部操作提示（很淡，不挡图） ----
        TextView hint = new TextView(this);
        hint.setText("左右滑动 / 左右键 翻页");
        hint.setTextColor(0x59FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(19));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, px(8), 0, px(10));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.gravity = Gravity.BOTTOM;
        root.addView(hint, hl);

        root.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                return handleTouch(e);
            }
        });

        setContentView(root);

        if (paths.isEmpty()) {
            Toast.makeText(this, "没有可显示的图片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        show(index);
    }

    private int px(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private StateListDrawable makeBtnBg() {
        StateListDrawable sd = new StateListDrawable();
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x3322222A);
        off.setCornerRadius(px(8));
        GradientDrawable press = new GradientDrawable();
        press.setColor(0x66FFFFFF);
        press.setCornerRadius(px(8));
        sd.addState(new int[]{android.R.attr.state_pressed}, press);
        sd.addState(new int[]{}, off);
        return sd;
    }

    /**
     * 手势翻页。阈值按屏宽百分比算（这里 density=160，720~1280 屏都能用），
     * 并要求横向位移明显大于纵向，避免上下轻微抖动被误判成翻页。
     */
    private boolean handleTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downT = System.currentTimeMillis();
                return true;

            case MotionEvent.ACTION_UP:
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                long dt = System.currentTimeMillis() - downT;
                int w = getResources().getDisplayMetrics().widthPixels;
                float th = Math.max(w * 0.06f, 70f);
                if (dt < 1500 && Math.abs(dx) > th && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    if (dx < 0) {
                        if (index + 1 < paths.size()) show(index + 1);
                    } else {
                        if (index > 0) show(index - 1);
                    }
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                return true;

            default:
                return true;
        }
    }

    private Bitmap decode(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int reqW = dm.widthPixels;
        int reqH = dm.heightPixels;

        int s = 1;
        while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) {
            s *= 2;
            if (s > 64) break;
        }

        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(path, o2);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private void show(int i) {
        if (i < 0 || i >= paths.size()) return;
        index = i;

        iv.setImageBitmap(null);
        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        bmp = null;

        String path = paths.get(index);
        bmp = decode(path);
        if (bmp == null) {
            Toast.makeText(this, "这张图打不开", Toast.LENGTH_SHORT).show();
        }
        iv.setImageBitmap(bmp);

        String name = new File(path).getName();
        tvLabel.setText(String.format(Locale.US, "%s    %d / %d", name, index + 1, paths.size()));
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP) {
            if (index > 0) show(index - 1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (index + 1 < paths.size()) show(index + 1);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        iv.setImageBitmap(null);
        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        bmp = null;
        super.onDestroy();
    }
}
