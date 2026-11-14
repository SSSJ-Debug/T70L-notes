package com.lm.xiaozhi;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * 自带日志：既打 logcat，也写文件。
 *
 * 为什么需要：这个设备的 NEMO_UI 会疯狂刷日志，logcat 环形缓冲几秒就被冲掉，
 * 排查问题时经常"什么都看不到"。写到 /data/data/com.lm.xiaozhi/files/xz.log
 * 就不会丢，adb shell cat 一下就能看（最多保留 256KB）。
 */
public class L {
    static final String TAG = "Xz";
    static final String TAG_WS = "XzClient";
    static File file;
    static final long MAX = 256 * 1024;

    public static void init(File dir) {
        file = new File(dir, "xz.log");
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        write("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        write("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        write("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        write("E", tag, msg + " :: " + (t == null ? "" : t.toString()));
    }

    private static synchronized void write(String lv, String tag, String msg) {
        if (file == null) return;
        try {
            if (file.length() > MAX) {
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                byte[] keep = new byte[(int) (MAX / 2)];
                raf.seek(file.length() - keep.length);
                raf.readFully(keep);
                raf.setLength(0);
                raf.write(keep);
                raf.close();
            }
            FileOutputStream fos = new FileOutputStream(file, true);
            String line = now() + " " + lv + "/" + tag + ": " + msg + "\n";
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private static String now() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS");
        return f.format(new java.util.Date());
    }
}
