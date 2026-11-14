package com.lm.deskadd;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.database.Cursor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 桌面自动收录器
 *
 * 背景：T70L 的原厂桌面（com.xylink.launcher）不会像普通安卓桌面那样
 * "装上应用就自动出现图标"，它的 item_info 表要有人往里插一条才行。
 * 以前每次装完软件都要手动插，还容易被系统整表清空。
 *
 * 这个类做的事很简单：
 *   1. 枚举所有「非系统 + 带 LAUNCHER 入口」的应用
 *   2. 和 item_info 里已有的（按包名比对）做差集
 *   3. 缺的插到第二屏及之后的空位上，图标用 PackageManager 里的应用图标转 PNG
 *
 * LauncherProvider 是 exported=true，所以普通应用就能写，不需要 root。
 */
public class AutoAdd {

    static final String TAG = "DeskAdd";
    static final Uri ITEM = Uri.parse("content://com.xylink.launcher.data/ItemInfo");

    static final int COLS = 4;      // 桌面 4 列
    static final int ROWS = 2;      // 每屏 2 行

    /** 返回这次新加了几条 */
    public static int sync(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        ContentResolver cr = ctx.getContentResolver();

        // ── 1) 读现有条目：已有哪些包、哪些格子被占 ──
        Set<String> havePkg = new HashSet<String>();
        int maxScreen = 0;
        List<int[]> occupied = new ArrayList<int[]>();   // {screen, cellX, cellY}

        Cursor c = null;
        try {
            c = cr.query(ITEM, null, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String intent = str(c, "intent");
                    String pkg = pkgOf(intent);
                    if (pkg != null) havePkg.add(pkg);
                    int s = num(c, "screen", 0);
                    int x = num(c, "cellX", 0);
                    int y = num(c, "cellY", 0);
                    int sx = Math.max(1, num(c, "spanX", 1));
                    int sy = Math.max(1, num(c, "spanY", 1));
                    if (s > maxScreen) maxScreen = s;
                    for (int dx = 0; dx < sx; dx++) {
                        for (int dy = 0; dy < sy; dy++) {
                            occupied.add(new int[]{s, x + dx, y + dy});
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读桌面条目失败: " + t);
            return 0;
        } finally {
            if (c != null) c.close();
        }

        // ── 2) 枚举可收录的应用 ──
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        if (list == null) return 0;

        int added = 0;
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;

            if (pkg == null || pkg.equals(ctx.getPackageName())) continue;
            if (havePkg.contains(pkg)) continue;                       // 桌面上已经有了
            if (ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;  // 系统应用不收
            // 原厂那套组件很多是后装进 /data 的（不在 /system 里，所以 FLAG_SYSTEM 拦不住），
            // 它们本来就以 widget / 系统入口的形式存在，硬收进桌面只会多出一堆没用的图标。
            if (pkg.startsWith("vulture.") || pkg.startsWith("com.ainemo.")
                    || pkg.startsWith("com.xylink.")) continue;

            CharSequence label = ri.loadLabel(pm);
            String title = label == null ? pkg : label.toString().trim();
            if (title.length() == 0) title = pkg;
            // 桌面标题区就那么宽，名字太长会被截成"mo"这种残字，先自己截断更可控
            if (title.length() > 7) title = title.substring(0, 7);

            // 同名不同包会造成去重触发器把它们当成重复，加个后缀区分
            if (titleTaken(cr, title) && !title.equals(pkg)) title = title + "·" + pkg;

            int[] slot = findFreeSlot(occupied, maxScreen);
            if (slot == null) break;      // 没位置了（正常不会，会自动开新屏）

            ContentValues cv = new ContentValues();
            cv.put("title", title);
            cv.put("intent", "intent:#Intent;component=" + pkg + "/" + ri.activityInfo.name
                    + ";launchFlags=0x10200000;end");
            cv.put("screen", slot[0]);
            cv.put("cellX", slot[1]);
            cv.put("cellY", slot[2]);
            cv.put("spanX", 1);
            cv.put("spanY", 1);
            cv.put("itemType", 2);
            cv.put("appWidgetId", -1);
            cv.put("iconType", 2);
            cv.put("modified", System.currentTimeMillis());

            byte[] png = iconPng(ri.loadIcon(pm));
            if (png != null) cv.put("icon", png);

            try {
                Uri u = cr.insert(ITEM, cv);
                if (u != null) {
                    added++;
                    markNeedReload(ctx);
                    havePkg.add(pkg);
                    occupied.add(new int[]{slot[0], slot[1], slot[2]});
                    if (slot[0] > maxScreen) maxScreen = slot[0];
                    Log.i(TAG, "已收录 " + title + " (" + pkg + ") → 屏" + slot[0]
                            + " (" + slot[1] + "," + slot[2] + ")");
                } else {
                    Log.w(TAG, "插入返回 null: " + pkg);
                }
            } catch (Throwable t) {
                Log.w(TAG, "插入失败 " + pkg + ": " + t);
            }
        }
        return added;
    }

    /**
     * 插完条目界面不会自己更新：LauncherProvider.insert 里没有 notifyChange，
     * 桌面只在进程重新启动时才重读数据库。普通应用又杀不掉 HOME。
     * 所以留一个标记文件，由 root 的 launcher_guard.sh 看到就重载一次桌面。
     */
    static void markNeedReload(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "need_reload");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(String.valueOf(System.currentTimeMillis()));
            w.close();
            Log.i(TAG, "已留重载标记: " + f);
        } catch (Throwable t) {
            Log.w(TAG, "写重载标记失败: " + t);
        }
    }

    /** 从「第二屏」开始找空位：第一屏是原厂固定布局，不去动它 */
    static int[] findFreeSlot(List<int[]> occ, int maxScreen) {
        for (int s = 1; s <= maxScreen + 1; s++) {
            for (int y = 0; y < ROWS; y++) {
                for (int x = 0; x < COLS; x++) {
                    if (!isUsed(occ, s, x, y)) return new int[]{s, x, y};
                }
            }
        }
        return null;
    }

    static boolean isUsed(List<int[]> occ, int s, int x, int y) {
        for (int[] o : occ) {
            if (o[0] == s && o[1] == x && o[2] == y) return true;
        }
        return false;
    }

    static boolean titleTaken(ContentResolver cr, String title) {
        Cursor c = null;
        try {
            c = cr.query(ITEM, null, "title='" + title.replace("'", "''") + "'", null, null);
            return c != null && c.getCount() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 从 intent 字符串里抠出包名 */
    static String pkgOf(String intent) {
        if (intent == null || intent.length() == 0) return null;
        int i = intent.indexOf("package=");
        if (i >= 0) {
            String s = intent.substring(i + 8);
            int e = s.indexOf(';');
            return e > 0 ? s.substring(0, e) : s;
        }
        i = intent.indexOf("component=");
        if (i >= 0) {
            String s = intent.substring(i + 10);
            int e = s.indexOf(';');
            if (e > 0) s = s.substring(0, e);
            int slash = s.indexOf('/');
            return slash > 0 ? s.substring(0, slash) : s;
        }
        return null;
    }

    /**
     * 把应用图标画成桌面统一的 220x280 卡片。
     *
     * 直接塞原始图标（96x96 甚至更小）的话，桌面会把它拉伸铺满整个卡片，
     * 结果是新图标比其他图标大一圈，一眼就看出是"外来的"。
     * 这里统一成：圆角深色卡片 + 居中 120x120 的应用图标，和 文件夹/看家 那些视觉一致。
     */
    static byte[] iconPng(Drawable d) {
        if (d == null) return null;
        final int W = 220, H = 280;
        try {
            Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);

            // 卡片底：竖向渐变，和桌面其他图标的暗色系一致
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, H, 0xFF2A3550, 0xFF161C28,
                    Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(0, 0, W, H), 26, 26, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f);
            p.setColor(0xFF34415C);
            c.drawRoundRect(new RectF(1, 1, W - 1, H - 1), 26, 26, p);
            p.setStyle(Paint.Style.FILL);

            // 居中的应用图标
            int iw = 120, ih = 120;
            int left = (W - iw) / 2;
            int top = (H - ih) / 2 - 12;
            d.setBounds(left, top, left + iw, top + ih);
            d.draw(c);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "图标转换失败: " + t);
            return null;
        }
    }

    static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 ? null : c.getString(i);
    }

    static int num(Cursor c, String col, int def) {
        int i = c.getColumnIndex(col);
        if (i < 0) return def;
        return c.isNull(i) ? def : c.getInt(i);
    }
}
