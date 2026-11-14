package com.lm.xiaozhi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 唤醒词学习页。
 *
 * ⚠ 这里【不】自己开 AudioRecord。
 *   安卓 4.4 全局只允许一个录音客户端，而且这台 T70L 的 HAL 有个毛病：
 *   AudioRecord 一旦 stop 再 open，输入轨道常常拿不回来（read 永久返回 0），
 *   只能重启进程。之前"答完一句就聋了"就是这个坑。
 *   所以训练**借用服务里那条长命录音流**：本页只通过静态字段读进度、发指令，
 *   麦克风始终由 XzService 的一条流握着，从头到尾不开第二遍。
 */
public class KwsActivity extends Activity {

    private TextView num, hint, msg, lvlTxt;
    private LinearLayout barTrack;
    private View barFill;
    private Button again, done;

    private final Handler h = new Handler();
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(MainActivity.BG);
        page.setPadding(60, 30, 60, 26);

        // ── 顶栏 ──
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("学习唤醒词");
        title.setTextColor(MainActivity.TXT);
        title.setTextSize(28);
        top.addView(title);

        TextView sub = new TextView(this);
        sub.setText("  让机器记住你的声音，以后不用联网也能叫醒");
        sub.setTextColor(MainActivity.TXT3);
        sub.setTextSize(13);
        top.addView(sub);

        View sp = new View(this);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        page.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 计数 ──
        num = new TextView(this);
        num.setText("0 / 12");
        num.setTextColor(MainActivity.BLUE);
        num.setTextSize(64);
        num.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = 20;
        page.addView(num, nlp);

        hint = new TextView(this);
        hint.setText("请对着机器，清楚地说一次：小智");
        hint.setTextColor(MainActivity.TXT);
        hint.setTextSize(20);
        hint.setGravity(Gravity.CENTER);
        page.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 电平条（让人知道话筒在工作）──
        barTrack = new LinearLayout(this);
        barTrack.setOrientation(LinearLayout.HORIZONTAL);
        barTrack.setBackgroundDrawable(round(0xFF10161F, MainActivity.CARD_LINE, 8, 2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 22);
        tlp.topMargin = 22;
        tlp.leftMargin = 90;
        tlp.rightMargin = 90;
        page.addView(barTrack, tlp);

        barFill = new View(this);
        barFill.setBackgroundDrawable(round(MainActivity.GREEN, 0, 6, 0));
        barTrack.addView(barFill, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT));

        lvlTxt = new TextView(this);
        lvlTxt.setTextColor(MainActivity.TXT3);
        lvlTxt.setTextSize(12);
        lvlTxt.setGravity(Gravity.CENTER);
        page.addView(lvlTxt, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 结果 / 提示 ──
        msg = new TextView(this);
        msg.setTextColor(MainActivity.TXT2);
        msg.setTextSize(14);
        msg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        mlp.topMargin = 10;
        page.addView(msg, mlp);

        // ── 按钮 ──
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        again = pill("重新学", false);
        again.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startTrain(true); }
        });
        btns.addView(again, new LinearLayout.LayoutParams(0, 84, 1f));

        Button close = pill("先回去", false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, 84, 1f);
        clp.leftMargin = 16;
        btns.addView(close, clp);

        done = pill("完成", true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, 84, 1.3f);
        dlp.leftMargin = 16;
        btns.addView(done, dlp);

        page.addView(btns, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(page);

        startTrain(false);
        h.postDelayed(tick, 120);
    }

    private Runnable tick = new Runnable() {
        @Override public void run() {
            int got = XzService.sTrainGot, need = XzService.sTrainNeed;

            if (XzService.sTraining) {
                finished = false;
                num.setText(got + " / " + need);
                num.setTextColor(MainActivity.BLUE);
                if (got < need) hint.setText("说慢一点，说完停一下再说下一个");
                done.setVisibility(View.INVISIBLE);
            } else {
                // 训练结束：成功或失败都停在这里，等用户决定重来还是回去
                if (XzService.sTrainOk) {
                    num.setText("✓");
                    num.setTextColor(MainActivity.GREEN);
                    hint.setText("学好了。现在直接喊“小智”就行");
                } else {
                    num.setText("✗");
                    num.setTextColor(0xFFE05555);
                    hint.setText("没学成，再来一次");
                }
                done.setVisibility(View.VISIBLE);
                finished = true;
            }

            msg.setText(XzService.sTrainMsg);

            // 电平条：用开方压缩，肉眼看着线性一点
            int lv = XzService.sLevel;
            if (lv < 0) lv = 0;
            if (lv > 32767) lv = 32767;
            float r = (float) Math.sqrt(lv / 8000.0);
            if (r > 1f) r = 1f;
            int w = barTrack.getWidth();
            if (w > 0) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) barFill.getLayoutParams();
                lp.width = (int) (w * r);
                barFill.setLayoutParams(lp);
            }
            lvlTxt.setText(lv < 60 ? "话筒很安静" : ("电平 " + lv));

            h.postDelayed(this, 120);
        }
    };

    private void startTrain(boolean restart) {
        Intent i = new Intent(this, XzService.class);
        i.setAction(restart ? "com.lm.xiaozhi.TRAIN_RESTART" : "com.lm.xiaozhi.TRAIN_START");
        try { startService(i); } catch (Throwable t) { toast("启动失败：" + t); }
        num.setText("0 / 12");
        num.setTextColor(MainActivity.BLUE);
        XzService.sTrainOk = false;
        done.setVisibility(View.INVISIBLE);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(tick);
        // 如果用户中途退出，别把服务一直挂在训练态
        if (XzService.sTraining) {
            try {
                Intent i = new Intent(this, XzService.class);
                i.setAction("com.lm.xiaozhi.TRAIN_STOP");
                startService(i);
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    // ───────── 样式（和设置页同一套）─────────

    private Button pill(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        b.setTextColor(primary ? 0xFFFFFFFF : MainActivity.TXT);
        b.setAllCaps(false);
        b.setBackgroundDrawable(round(primary ? MainActivity.BLUE : MainActivity.CARD,
                primary ? MainActivity.BLUE : MainActivity.CARD_LINE, 40, 2));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private GradientDrawable round(int fill, int stroke, int radius, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, stroke);
        return g;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
