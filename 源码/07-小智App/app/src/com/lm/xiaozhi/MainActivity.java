package com.lm.xiaozhi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 小智 · 设置（2026-11-06 重做界面）
 *
 * 旧版直接堆系统默认控件：纯黑背景、灰色小方块按钮、输入框一进来就抢焦点把键盘顶起来挡住半屏，
 * 在电视上又丑又难按。现在改成和「看家 / 云台」同一套视觉语言：
 *   深色底 + 圆角卡片 + 胶囊按钮 + 主蓝强调色，横屏两栏，一屏放完不用滚。
 */
public class MainActivity extends Activity {

    // ── 和看家/云台统一的色板 ──
    static final int BG = 0xFF0B0F16;
    static final int CARD = 0xFF171E2A;
    static final int CARD_LINE = 0xFF243044;
    static final int TXT = 0xFFE9EFF8;
    static final int TXT2 = 0xFF9AACC2;
    static final int TXT3 = 0xFF5E7089;
    static final int BLUE = 0xFF2F6FEA;
    static final int BLUE_D = 0xFF1B4FBF;
    static final int GREEN = 0xFF2FBF71;

    private Prefs p;
    private TextView status, wakeNow;
    private Button bCont, bIdle, bTone, bVol, bAck, bSens, bLocal, bLearn;
    private EditText editWake;
    private LinearLayout presetBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = Prefs.load(this);

        // 关键：不许一进来就弹键盘。旧版没设这个，输入框自动拿焦点，键盘挡住下半屏。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(46, 26, 46, 20);

        // ───── 顶栏 ─────
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("小智");
        title.setTextColor(TXT);
        title.setTextSize(30);
        top.addView(title);

        TextView sub = new TextView(this);
        sub.setText("  语音助手 · 设置");
        sub.setTextColor(TXT3);
        sub.setTextSize(14);
        top.addView(sub);

        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        status = new TextView(this);
        status.setTextSize(14);
        top.addView(status);
        page.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ───── 两栏主体 ─────
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = 16;
        page.addView(body, bodyLp);

        // 左栏：唤醒词
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.12f);
        leftLp.rightMargin = 18;
        body.addView(left, leftLp);

        left.addView(buildWakeCard(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 右栏：对话 + 声音 + 重启
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        body.addView(right, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        right.addView(buildTalkCard(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        gap.topMargin = 16;
        right.addView(buildSoundCard(), gap);

        setContentView(page);
        refresh();
    }

    // ══════════════════════ 左栏：唤醒词卡片 ══════════════════════

    private View buildWakeCard() {
        LinearLayout card = card();
        card.setPadding(26, 22, 26, 20);

        card.addView(label("唤醒词"), full());

        wakeNow = new TextView(this);
        wakeNow.setTextColor(GREEN);
        wakeNow.setTextSize(26);
        card.addView(wakeNow, full());

        TextView hint = new TextView(this);
        hint.setText("换成什么都能用 —— 判断是拿云端识别出的文字做匹配，不用重新训练");
        hint.setTextColor(TXT3);
        hint.setTextSize(12);
        card.addView(hint, full());

        // 预设：4 个一行，两行
        presetBar = new LinearLayout(this);
        presetBar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams plp = full();
        plp.topMargin = 12;
        card.addView(presetBar, plp);

        String[] presets = Prefs.WAKE_PRESETS;
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = r * 4; i < r * 4 + 4 && i < presets.length; i++) {
                final String name = presets[i];
                Button b = pill(name, false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 74, 1f);
                if (i % 4 != 0) lp.leftMargin = 10;
                line.addView(b, lp);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        p.wakeWord = name;
                        commit();
                        toast("以后喊“" + name + "”");
                    }
                });
            }
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (r != 0) llp.topMargin = 10;
            presetBar.addView(line, llp);
        }

        // 自定义输入
        LinearLayout editLine = new LinearLayout(this);
        editLine.setOrientation(LinearLayout.HORIZONTAL);
        editLine.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams elp = full();
        elp.topMargin = 14;
        card.addView(editLine, elp);

        editWake = new EditText(this);
        editWake.setHint("或者自己输一个");
        editWake.setTextColor(TXT);
        editWake.setHintTextColor(TXT3);
        editWake.setTextSize(16);
        editWake.setSingleLine(true);
        editWake.setInputType(InputType.TYPE_CLASS_TEXT);
        editWake.setBackgroundDrawable(roundBg(0xFF10161F, CARD_LINE, 16, 2));
        editWake.setPadding(20, 14, 20, 14);
        // 不自动抢焦点，避免键盘一进来就顶上来
        editWake.setFocusableInTouchMode(true);
        editLine.addView(editWake, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button imeBtn = pill("中/英", false);
        imeBtn.setTextSize(13);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(112, 64);
        ilp.leftMargin = 10;
        editLine.addView(imeBtn, ilp);
        imeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 弹出系统输入法选择器：电视上没有"地球键"，这里给个明路
                try {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showInputMethodPicker();
                } catch (Throwable t) {
                    toast("打开输入法选择失败，请到系统设置里切换");
                }
            }
        });

        Button save = pill("保存", true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(120, 64);
        slp.leftMargin = 10;
        editLine.addView(save, slp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String w = editWake.getText().toString().trim();
                if (w.length() == 0) { toast("先输入一个名字"); return; }
                p.wakeWord = w;
                editWake.setText("");
                hideKeyboard();
                commit();
                toast("唤醒词已改成“" + w + "”");
            }
        });

        // ── 本地唤醒：让「小智」这两个字直接在本机认出来，不等云端往返 ──
        //   原来判定靠云端的识别文本，一个来回 1.5~3 秒，而且房间噪声一抖
        //   就误触发占麦，真喊的那句反而没人听。本地认词是 200ms 级。
        LinearLayout lwRow = new LinearLayout(this);
        lwRow.setOrientation(LinearLayout.HORIZONTAL);
        lwRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lrlp = full();
        lrlp.topMargin = 16;
        card.addView(lwRow, lrlp);

        bLocal = pill("本地唤醒", false);
        bLocal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                p.localWake = !p.localWake;
                commit();
            }
        });
        lwRow.addView(bLocal, new LinearLayout.LayoutParams(0, 68, 1f));

        bLearn = pill("学我的声音", true);
        bLearn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(MainActivity.this, KwsActivity.class)); }
                catch (Throwable t) { toast("打不开学习页：" + t); }
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, 68, 1.3f);
        blp.leftMargin = 10;
        lwRow.addView(bLearn, blp);

        return card;
    }

    // ══════════════════════ 右栏上：对话卡片 ══════════════════════

    private View buildTalkCard() {
        LinearLayout card = card();
        card.setPadding(26, 20, 26, 18);
        card.addView(label("对话"), full());

        bCont = row(card, "连续对话", "答完不用再喊名字");
        bCont.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                p.continuous = !p.continuous;
                // ★ 2026-11-07：立即通知常驻服务，别等下一轮才生效。
                //   这就是"关了它还在聊 / 开了它不接话"的直接原因。
                Prefs.sContPending = p.continuous;
                commit();
            }
        });

        bIdle = row(card, "静默结束", "这么久没人说话就收工");
        bIdle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (p.idleTimeoutSec >= 60) p.idleTimeoutSec = 15;
                else if (p.idleTimeoutSec >= 35) p.idleTimeoutSec = 60;
                else if (p.idleTimeoutSec >= 25) p.idleTimeoutSec = 35;
                else p.idleTimeoutSec = 25;
                commit();
            }
        });

        bAck = row(card, "只喊名字时应答", "回一声“在呢”");
        bAck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { p.wakeAck = !p.wakeAck; commit(); }
        });

        return card;
    }

    // ══════════════════════ 右栏下：声音卡片 ══════════════════════

    private View buildSoundCard() {
        LinearLayout card = card();
        card.setPadding(26, 20, 26, 18);
        card.addView(label("声音"), full());

        bTone = row(card, "音色", "本机实时变调");
        bTone.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { p.tone = (p.tone + 1) % 5; commit(); }
        });

        bVol = row(card, "音量", "媒体音量");
        bVol.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                p.volume = p.volume >= 15 ? 3 : p.volume + 2;
                try {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, p.volume, 0);
                } catch (Throwable ignored) {}
                commit();
            }
        });

        bSens = row(card, "唤醒灵敏度", "吵的房间调低");
        bSens.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { p.sens = (p.sens + 1) % 3; commit(); }
        });

        // 重启服务：一个小按钮放右下角，不用占一整行
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = full();
        blp.topMargin = 10;
        card.addView(bottom, blp);

        Button restart = pill("重启服务", false);
        restart.setTextSize(13);
        bottom.addView(restart, new LinearLayout.LayoutParams(150, 60));
        restart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, XzService.class));
                try { Thread.sleep(300); } catch (Throwable ignored) {}
                startService(new Intent(MainActivity.this, XzService.class));
                toast("已重启");
                refresh();
            }
        });

        return card;
    }

    // ══════════════════════ 小工具 ══════════════════════

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundDrawable(roundBg(CARD, CARD_LINE, 22, 2));
        return v;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TXT3);
        t.setTextSize(13);
        t.setPadding(0, 0, 0, 8);
        return t;
    }

    /** 一行设置：左边标题+说明，右边胶囊值按钮 */
    private Button row(LinearLayout card, String name, String desc) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = 12;
        card.addView(line, lp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(TXT);
        t.setTextSize(16);
        texts.addView(t);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(TXT3);
        d.setTextSize(11);
        texts.addView(d);
        line.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button b = pill("", false);
        b.setTextSize(14);
        line.addView(b, new LinearLayout.LayoutParams(176, 68));
        return b;
    }

    private Button pill(String text, boolean filled) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(filled ? 0xFFFFFFFF : TXT2);
        b.setBackgroundDrawable(filled
                ? roundBg(BLUE, BLUE, 100, 0)
                : roundBg(0x00000000, CARD_LINE, 100, 2));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private GradientDrawable roundBg(int fill, int stroke, int radius, int strokeW) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeW > 0) d.setStroke(strokeW, stroke);
        return d;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void commit() {
        p.save(this);
        refresh();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (getCurrentFocus() != null) imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    private void refresh() {
        wakeNow.setText("现在喊：「" + p.wakeWord + "」");

        styleToggle(bCont, p.continuous ? "已开启" : "已关闭", p.continuous);
        bIdle.setText(p.idleTimeoutSec + " 秒");
        bIdle.setTextColor(TXT2);
        styleToggle(bAck, p.wakeAck ? "已开启" : "已关闭", p.wakeAck);
        bTone.setText(Prefs.TONE_NAMES[p.tone]);
        bTone.setTextColor(TXT2);
        bVol.setText("到 " + p.volume);
        bVol.setTextColor(TXT2);
        String[] sens = {"高 · 安静", "中 · 有电视", "低 · 很吵"};
        bSens.setText(sens[p.sens]);
        bSens.setTextColor(TXT2);

        styleToggle(bLocal, p.localWake ? "本地唤醒 已开" : "本地唤醒 已关", p.localWake);
        if (bLearn != null) {
            bLearn.setText(XzService.sKwsTpl > 0 ? ("重学 · " + XzService.sKwsTpl + "条") : "学我的声音");
        }

        status.setText(XzService.sRunning ? "● 待命中，喊一声试试" : "○ 服务未运行，点右下角重启");
        status.setTextColor(XzService.sRunning ? GREEN : 0xFFE08A8A);
    }

    /** 开关类按钮：开=蓝底白字，关=透明边框 */
    private void styleToggle(Button b, String text, boolean on) {
        b.setText(text);
        b.setTextColor(on ? 0xFFFFFFFF : TXT2);
        b.setBackgroundDrawable(on
                ? roundBg(BLUE, BLUE, 100, 0)
                : roundBg(0x00000000, CARD_LINE, 100, 2));
    }

    @Override
    protected void onResume() {
        super.onResume();
        p = Prefs.load(this);
        refresh();
        if (!XzService.sRunning) {
            try { startService(new Intent(this, XzService.class)); } catch (Throwable ignored) {}
        }
    }
}
