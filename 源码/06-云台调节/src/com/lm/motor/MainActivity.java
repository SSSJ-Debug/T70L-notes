package com.lm.motor;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 云台调节 —— T70L 步进云台（TC78S600FNG）控制面板
 *
 * 直接写 /sys/devices/platform/drvtc78s600fng.2/{pps,step,cmd}，
 * 全部为运行时参数，不落盘、不改系统文件。
 */
public class MainActivity extends Activity {

    static final String BASE = "/sys/devices/platform/drvtc78s600fng.2/";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(new Panel(this));
    }

    // ══════════════════════════════ 面板 ══════════════════════════════
    static class Panel extends View {

        // ── 配色 ──
        static final int BG_TOP    = 0xFF070A10;
        static final int BG_BOTTOM = 0xFF0C1219;
        static final int CARD_TOP  = 0xFF131B26;
        static final int CARD_BOT  = 0xFF0E151E;
        static final int BORDER    = 0xFF1E2937;
        static final int TXT_HI    = 0xFFE9F1FA;
        static final int TXT_MID   = 0xFF9DB0C6;
        static final int TXT_LOW   = 0xFF5E7086;
        static final int ACCENT    = 0xFF4C8DFF;
        static final int ACCENT_HI = 0xFFA9CBFF;
        static final int DANGER    = 0xFFE8555C;

        // ── 几何 ──
        static final float DCX = 316, DCY = 452;   // 方向盘圆心
        static final float DR = 172;               // 弧线中心半径
        static final float DSW = 76;               // 弧线粗细
        static final float CR = 84;                // 中心圆半径

        final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pArc = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss");

        final List<Hit> hits = new ArrayList<Hit>();
        final List<String> logs = new ArrayList<String>();
        final RectF tmpR = new RectF();
        final Path tmpP = new Path();

        int step = 50;
        int pps = 1000;
        String lastAct = "待命";
        String stCmd = "—", stPos = "—", stStep = "—", stPps = "—";
        String rawCmd = "stop";

        String holdAct = null;
        String pressedAct = null;
        float pulse = 0f;

        final Handler h = new Handler();

        final Runnable tick = new Runnable() {
            public void run() {
                refresh();
                h.postDelayed(this, 800);
            }
        };

        /** 呼吸灯效果：让界面看起来是「活的」 */
        final Runnable breath = new Runnable() {
            public void run() {
                pulse += 0.06f;
                if (pulse > 1f) pulse = 0f;
                invalidate();
                h.postDelayed(this, 60);
            }
        };

        /** 按住连走 */
        final Runnable holdRun = new Runnable() {
            public void run() {
                if (holdAct == null) return;
                write("pps", String.valueOf(pps));
                write("step", String.valueOf(step));
                write("cmd", holdAct);
                h.postDelayed(this, 160);
            }
        };

        Panel(android.content.Context c) {
            super(c);
            buildUi();
        }

        // ────────────────────────── 布局 ──────────────────────────
        void buildUi() {
            hits.clear();

            // 右上：复位 / 自检
            hits.add(new Hit(1014, 40, 1120, 94, "复位", "reset"));
            hits.add(new Hit(1134, 40, 1240, 94, "自检", "auto"));

            // 步数胶囊
            float x = 636, w = 104, gap = 12, y = 200, hh = 68;
            int[] steps = {10, 20, 50, 100, 200};
            for (int v : steps) {
                hits.add(new Hit(x, y, x + w, y + hh, String.valueOf(v), "step:" + v));
                x += w + gap;
            }

            // 速度胶囊
            x = 636; w = 133; y = 384; hh = 68;
            int[] ppss = {1000, 3000, 6000, 16000};
            for (int v : ppss) {
                hits.add(new Hit(x, y, x + w, y + hh, String.valueOf(v), "pps:" + v));
                x += w + gap;
            }
        }

        // ────────────────────────── 绘制 ──────────────────────────
        @Override
        protected void onDraw(Canvas c) {
            drawBackground(c);
            drawHeader(c);
            drawDialCard(c);
            drawStepCard(c);
            drawSpeedCard(c);
            drawStatusCard(c);
        }

        void drawBackground(Canvas c) {
            pFill.setShader(new LinearGradient(0, 0, 0, getHeight(), BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), getHeight(), pFill);
            pFill.setShader(null);

            // 左上角柔光
            pFill.setShader(new RadialGradient(140, -60, 520,
                    new int[]{0x224C8DFF, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), 460, pFill);
            pFill.setShader(null);
        }

        void drawHeader(Canvas c) {
            tp.setShader(null);
            tp.setFakeBoldText(true);
            tp.setColor(TXT_HI);
            tp.setTextSize(40);
            c.drawText("云台调节", 56, 74, tp);

            tp.setFakeBoldText(false);
            tp.setTextSize(18);
            tp.setColor(TXT_LOW);
            c.drawText("T70L  ·  摄像机云台调节", 56, 102, tp);

            for (Hit hit : hits) {
                if (!"reset".equals(hit.act) && !"auto".equals(hit.act)) continue;
                ghost(c, hit);
            }
        }

        /** 右上角描边小按钮 */
        void ghost(Canvas c, Hit hit) {
            boolean down = hit.act.equals(pressedAct);
            tmpR.set(hit.l, hit.t, hit.r, hit.bm);
            pFill.setShader(null);
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(down ? 0xFF1B2735 : 0x00000000);
            c.drawRoundRect(tmpR, 26, 26, pFill);

            pLine.setStyle(Paint.Style.STROKE);
            pLine.setStrokeWidth(1.5f);
            pLine.setColor(down ? ACCENT : BORDER);
            c.drawRoundRect(tmpR, 26, 26, pLine);

            tp.setFakeBoldText(false);
            tp.setTextSize(21);
            tp.setColor(down ? ACCENT_HI : TXT_MID);
            centerText(c, hit.label, (hit.l + hit.r) / 2f, (hit.t + hit.bm) / 2f + 8);
        }

        /** 左侧卡片 + 方向盘 */
        void drawDialCard(Canvas c) {
            card(c, 48, 132, 584, 772);

            // 底部说明
            tp.setFakeBoldText(false);
            tp.setTextSize(17);
            tp.setColor(TXT_LOW);
            centerText(c, "上方 上抬   ·   下方 下压   ·   中心 立即停止", 316, 730);

            // ── 底环 ──
            pArc.setStyle(Paint.Style.STROKE);
            pArc.setStrokeWidth(DSW);
            pArc.setStrokeCap(Paint.Cap.ROUND);
            pArc.setShader(null);
            pArc.setColor(0xFF141C27);
            dialArc(c, 206, 128, pArc);
            dialArc(c, 26, 128, pArc);

            // 内侧细环，增加层次
            pLine.setStyle(Paint.Style.STROKE);
            pLine.setStrokeWidth(1f);
            pLine.setColor(0x14FFFFFF);
            c.drawCircle(DCX, DCY, DR - DSW / 2f - 6, pLine);

            // ── 两段动作弧 ──
            boolean upActive = "up".equals(holdAct) || "up".equals(pressedAct);
            boolean dnActive = "down".equals(holdAct) || "down".equals(pressedAct);
            dialSeg(c, 206, 128, upActive, 0xFF2F6FEA, 0xFF1B4FBF);
            dialSeg(c, 26, 128, dnActive, 0xFF2F6FEA, 0xFF1B4FBF);

            // ── 箭头 ──
            chevron(c, DCX, DCY - DR, true, 0xFFEAF2FF, 9f, 40f);
            chevron(c, DCX, DCY + DR, false, 0xFFEAF2FF, 9f, 40f);

            // ── 中心停止键 ──
            boolean stopDown = "stop".equals(pressedAct);
            pFill.setShader(new RadialGradient(DCX, DCY - 24, CR * 1.5f,
                    stopDown ? 0xFFF16C72 : 0xFF273343,
                    stopDown ? 0xFFC74046 : 0xFF18212D,
                    Shader.TileMode.CLAMP));
            c.drawCircle(DCX, DCY, CR, pFill);
            pFill.setShader(null);

            pLine.setStyle(Paint.Style.STROKE);
            pLine.setStrokeWidth(1.5f);
            pLine.setColor(stopDown ? 0xFFFFC9CB : 0xFF35455A);
            c.drawCircle(DCX, DCY, CR, pLine);

            pLine.setStrokeWidth(1f);
            pLine.setColor(0x0DFFFFFF);
            c.drawCircle(DCX, DCY, CR + 12, pLine);

            // 停止图标（圆角方块）
            tmpR.set(DCX - 14, DCY - 30, DCX + 14, DCY - 2);
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(0xFFFFFFFF);
            c.drawRoundRect(tmpR, 5, 5, pFill);

            tp.setFakeBoldText(true);
            tp.setTextSize(18);
            tp.setColor(0xFFDCE8F6);
            centerText(c, "停止", DCX, DCY + 34);
            tp.setFakeBoldText(false);
        }

        void dialArc(Canvas c, float start, float sweep, Paint p) {
            tmpR.set(DCX - DR, DCY - DR, DCX + DR, DCY + DR);
            c.drawArc(tmpR, start, sweep, false, p);
        }

        void dialSeg(Canvas c, float start, float sweep, boolean active, int c1, int c2) {
            pArc.setStyle(Paint.Style.STROKE);
            pArc.setStrokeWidth(DSW);
            pArc.setStrokeCap(Paint.Cap.ROUND);
            if (active) {
                pArc.setShader(new LinearGradient(DCX - DR, DCY - DR, DCX + DR, DCY + DR,
                        c1, c2, Shader.TileMode.CLAMP));
                pArc.setAlpha(255);
            } else {
                pArc.setShader(null);
                pArc.setColor(0xFF1B2634);
                pArc.setAlpha(255);
            }
            tmpR.set(DCX - DR, DCY - DR, DCX + DR, DCY + DR);
            // Android 的 sweep 正方向即顺时针：200°→340° 正好走过顶部
            c.drawArc(tmpR, start, sweep, false, pArc);
            pArc.setShader(null);
        }

        void chevron(Canvas c, float cx, float cy, boolean up, int color, float w, float half) {
            tmpP.reset();
            if (up) {
                tmpP.moveTo(cx - half / 2, cy + 10);
                tmpP.lineTo(cx, cy - 10);
                tmpP.lineTo(cx + half / 2, cy + 10);
            } else {
                tmpP.moveTo(cx - half / 2, cy - 10);
                tmpP.lineTo(cx, cy + 10);
                tmpP.lineTo(cx + half / 2, cy - 10);
            }
            pLine.setStyle(Paint.Style.STROKE);
            pLine.setStrokeWidth(w);
            pLine.setStrokeCap(Paint.Cap.ROUND);
            pLine.setStrokeJoin(Paint.Join.ROUND);
            pLine.setColor(color);
            c.drawPath(tmpP, pLine);
        }

        void drawStepCard(Canvas c) {
            card(c, 608, 132, 1232, 300);
            label(c, "步数", "STEP", 636, 176);
            for (Hit hit : hits) {
                if (hit.act.startsWith("step:")) pill(c, hit, false, 26);
            }
        }

        void drawSpeedCard(Canvas c) {
            card(c, 608, 316, 1232, 484);
            label(c, "速度", "Hz", 636, 360);
            for (Hit hit : hits) {
                if (hit.act.startsWith("pps:")) pill(c, hit, false, 26);
            }
        }

        void drawStatusCard(Canvas c) {
            card(c, 608, 500, 1232, 772);

            label(c, "状态", "STATUS", 636, 542);

            // 运行动态点
            boolean running = !"stop".equals(rawCmd);
            int dotColor = running ? ACCENT : 0xFF2C3A4C;
            float rr = running ? (5f + 2f * (float) Math.abs(Math.sin(pulse * Math.PI))) : 5f;
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(dotColor);
            c.drawCircle(1204, 534, rr, pFill);

            // 四个字段
            field(c, "电机", stCmd, 636, 588, 622);
            field(c, "限位", stPos, 780, 588, 622);
            field(c, "步数", stStep, 924, 588, 622);
            field(c, "速度", stPps + " Hz", 1068, 588, 622);

            // 分隔线
            pLine.setStyle(Paint.Style.STROKE);
            pLine.setStrokeWidth(1);
            pLine.setColor(0xFF1A2432);
            c.drawLine(636, 646, 1204, 646, pLine);

            tp.setFakeBoldText(false);
            tp.setTextSize(19);
            tp.setColor(TXT_MID);
            c.drawText("本次", 636, 682, tp);
            tp.setColor(0xFFD5E3F3);
            c.drawText(lastAct, 690, 682, tp);

            tp.setTextSize(16);
            tp.setColor(TXT_LOW);
            int y = 716;
            int n = logs.size();
            if (n == 0) {
                c.drawText("暂无操作记录 · 按住方向盘上方或下方开始", 636, y, tp);
            } else {
                for (int k = Math.max(0, n - 2); k < n; k++) {
                    c.drawText(logs.get(k), 636, y, tp);
                    y += 26;
                }
            }
        }

        void field(Canvas c, String name, String val, float x, float labelY, float valY) {
            tp.setFakeBoldText(false);
            tp.setTextSize(15);
            tp.setColor(TXT_LOW);
            c.drawText(name, x, labelY, tp);

            tp.setFakeBoldText(true);
            tp.setTextSize(25);
            tp.setColor(TXT_HI);
            c.drawText(val, x, valY, tp);
            tp.setFakeBoldText(false);
        }

        void label(Canvas c, String cn, String en, float x, float y) {
            tp.setFakeBoldText(true);
            tp.setTextSize(19);
            tp.setColor(TXT_MID);
            c.drawText(cn, x, y, tp);
            float w = tp.measureText(cn);
            tp.setFakeBoldText(false);
            tp.setTextSize(15);
            tp.setColor(TXT_LOW);
            c.drawText(en, x + w + 10, y, tp);
        }

        /** 卡片底板 */
        void card(Canvas c, float l, float t, float r, float b) {
            tmpR.set(l, t, r, b);
            pFill.setStyle(Paint.Style.FILL);
            pFill.setShader(new LinearGradient(0, t, 0, b, CARD_TOP, CARD_BOT, Shader.TileMode.CLAMP));
            c.drawRoundRect(tmpR, 26, 26, pFill);
            pFill.setShader(null);

            pLine.setStyle(Paint.Style.STROKE);
            pLine.setStrokeWidth(1);
            pLine.setColor(BORDER);
            c.drawRoundRect(tmpR, 26, 26, pLine);
        }

        /** 胶囊按钮 */
        void pill(Canvas c, Hit hit, boolean ignored, int textSize) {
            boolean sel = hit.act.startsWith("step:")
                    ? hit.act.equals("step:" + step)
                    : hit.act.equals("pps:" + pps);
            boolean down = hit.act.equals(pressedAct);

            tmpR.set(hit.l, hit.t, hit.r, hit.bm);

            if (sel) {
                pFill.setShader(new LinearGradient(0, hit.t, 0, hit.bm,
                        0xFF1D3A63, 0xFF142942, Shader.TileMode.CLAMP));
                pFill.setStyle(Paint.Style.FILL);
                c.drawRoundRect(tmpR, 22, 22, pFill);
                pFill.setShader(null);

                pLine.setStyle(Paint.Style.STROKE);
                pLine.setStrokeWidth(2f);
                pLine.setColor(ACCENT);
                c.drawRoundRect(tmpR, 22, 22, pLine);
            } else {
                pFill.setShader(null);
                pFill.setStyle(Paint.Style.FILL);
                pFill.setColor(down ? 0xFF1A2635 : 0xFF101822);
                c.drawRoundRect(tmpR, 22, 22, pFill);

                pLine.setStyle(Paint.Style.STROKE);
                pLine.setStrokeWidth(1);
                pLine.setColor(down ? 0xFF2C4A6E : 0xFF1B2534);
                c.drawRoundRect(tmpR, 22, 22, pLine);
            }

            tp.setFakeBoldText(sel);
            tp.setTextSize(textSize);
            tp.setColor(sel ? ACCENT_HI : TXT_MID);
            centerText(c, hit.label, (hit.l + hit.r) / 2f, (hit.t + hit.bm) / 2f + textSize * 0.36f);
            tp.setFakeBoldText(false);
        }

        void centerText(Canvas c, String s, float cx, float baselineY) {
            float w = tp.measureText(s);
            c.drawText(s, cx - w / 2f, baselineY, tp);
        }

        // ────────────────────────── 交互 ──────────────────────────
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();

            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                String act = hitTest(x, y);
                pressedAct = act;
                if ("up".equals(act) || "down".equals(act)) {
                    holdAct = act;
                    lastAct = ("up".equals(act) ? "上抬中…" : "下压中…");
                    doAct(act);
                    h.postDelayed(holdRun, 160);
                } else if (act != null) {
                    doAct(act);
                }
                invalidate();
            } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                String act = hitTest(x, y);
                if (!same(act, pressedAct)) {
                    if ("up".equals(pressedAct) || "down".equals(pressedAct)) return true; // 连走中不跳变
                    pressedAct = act;
                    invalidate();
                }
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                if (holdAct != null) {
                    holdAct = null;
                    h.removeCallbacks(holdRun);
                    write("cmd", "stop");
                    lastAct = "松手停止";
                    say("松手 -> stop");
                }
                pressedAct = null;
                invalidate();
            }
            return true;
        }

        boolean same(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        /** 命中：方向盘两段弧 + 中心圆 + 所有胶囊 */
        String hitTest(float x, float y) {
            float dx = x - DCX, dy = y - DCY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);

            if (dist <= CR) return "stop";

            if (dist >= DR - DSW / 2f - 12 && dist <= DR + DSW / 2f + 12) {
                double ang = Math.toDegrees(Math.atan2(dy, dx));
                if (ang < 0) ang += 360;
                // 上段 200~340，下段 20~160
                if (ang >= 190 && ang <= 350) return "up";
                if (ang >= 10 && ang <= 170) return "down";
            }

            for (Hit hit : hits) {
                if (x >= hit.l && x <= hit.r && y >= hit.t && y <= hit.bm) return hit.act;
            }
            return null;
        }

        void doAct(String act) {
            if (act.startsWith("step:")) {
                step = Integer.parseInt(act.substring(5));
                say("步数设为 " + step);
                return;
            }
            if (act.startsWith("pps:")) {
                pps = Integer.parseInt(act.substring(4));
                say("速度设为 " + pps + " Hz");
                return;
            }
            if ("stop".equals(act)) {
                say("停止 -> " + write("cmd", "stop"));
                lastAct = "停止";
                return;
            }
            if ("reset".equals(act)) {
                say("复位 -> " + write("cmd", "reset"));
                lastAct = "复位脉冲";
                return;
            }
            if ("auto".equals(act)) {
                say("自检 -> " + write("cmd", "auto"));
                lastAct = "自检（200步 / 3000Hz）";
                return;
            }
            String w1 = write("pps", String.valueOf(pps));
            String w2 = write("step", String.valueOf(step));
            String w3 = write("cmd", act);
            lastAct = ("up".equals(act) ? "上抬 " : "下压 ") + step + " 步 · " + pps + "Hz";
            say(lastAct + "   pps:" + w1 + " step:" + w2 + " cmd:" + w3);
        }

        void say(String s) {
            logs.add(hms.format(new Date()) + "   " + s);
            while (logs.size() > 20) logs.remove(0);
        }

        // ── sysfs ──
        String write(String name, String val) {
            try {
                RandomAccessFile f = new RandomAccessFile(BASE + name, "rw");
                f.write(val.getBytes("UTF-8"));
                f.close();
                return "ok";
            } catch (Throwable t) {
                try {
                    Process pr = Runtime.getRuntime().exec(new String[]{
                            "su", "-c", "echo " + val + " > " + BASE + name});
                    int rc = pr.waitFor();
                    return rc == 0 ? "ok(su)" : ("拒绝:" + t.getClass().getSimpleName());
                } catch (Throwable t2) {
                    return "拒绝:" + t.getClass().getSimpleName();
                }
            }
        }

        String read(String name) {
            try {
                RandomAccessFile f = new RandomAccessFile(BASE + name, "r");
                byte[] buf = new byte[512];
                int n = f.read(buf);
                f.close();
                if (n <= 0) return "";
                return new String(buf, 0, n, "UTF-8").replace("\n", " ").replaceAll("\\s+", " ").trim();
            } catch (Throwable t) {
                return "(" + t.getClass().getSimpleName() + ")";
            }
        }

        void refresh() {
            String cmd = read("cmd");
            int i = cmd.indexOf("current motor_cmd is");
            String rc = (i >= 0) ? cmd.substring(i + "current motor_cmd is".length()).trim() : cmd;
            rawCmd = rc;
            stCmd = cn(rc);
            stPos = cn(read("position"));
            stStep = read("step").replace("now step :", "").trim();
            stPps = read("pps").replace("now freq :", "").replace("Hz", "").trim();
            invalidate();
        }

        /** 驱动原始值 -> 界面中文 */
        String cn(String v) {
            if (v == null) return "—";
            String s = v.trim();
            if ("stop".equals(s)) return "已停止";
            if ("up".equals(s)) return "上抬中";
            if ("down".equals(s)) return "下压中";
            if ("auto".equals(s)) return "自检中";
            if ("reset".equals(s)) return "复位";
            if ("Up".equals(s)) return "上端";
            if ("Down".equals(s)) return "下端";
            if ("Unknown".equals(s)) return "中间";
            if (s.startsWith("(")) return "读取失败";
            return s;
        }

        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            refresh();
            h.post(tick);
            h.post(breath);
        }

        protected void onDetachedFromWindow() {
            h.removeCallbacks(tick);
            h.removeCallbacks(breath);
            h.removeCallbacks(holdRun);
            super.onDetachedFromWindow();
        }
    }

    static class Hit {
        float l, t, r, bm;
        String label, act;

        Hit(float l, float t, float r, float bm, String label, String act) {
            this.l = l; this.t = t; this.r = r; this.bm = bm;
            this.label = label; this.act = act;
        }
    }
}
