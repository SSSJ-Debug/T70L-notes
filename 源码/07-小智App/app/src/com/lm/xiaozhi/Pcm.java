package com.lm.xiaozhi;

/** PCM 工具：RMS/峰值、48k 立体声 → 16k 单声道、播放变速 */
public class Pcm {

    /** 指定声道的 RMS */
    public static int rmsCh(byte[] buf, int len, int ch) {
        long sum = 0; int n = 0, off = ch * 2;
        for (int i = off; i + 3 < len; i += 4) {
            int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            sum += (long) v * v; n++;
        }
        if (n == 0) return 0;
        return (int) Math.sqrt((double) sum / n);
    }

    public static int peakAbs(byte[] buf, int len, int ch) {
        int peak = 0, off = ch * 2;
        for (int i = off; i + 3 < len; i += 4) {
            int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            int a = v < 0 ? -v : v;
            if (a > peak) peak = a;
        }
        return peak;
    }

    /**
     * 过零率（0~1）：相邻样本符号翻转的占比。
     *
     * 为什么需要它：这台 T70L 的房间噪声在 rms 400~800 之间来回波动，
     * 而中文里"灯"这种字（d + -eng）的弱尾能量也是 700~800，两者**数值上完全重叠**。
     * 只看能量永远分不开 —— 要么把噪声当语音录满 5 秒，要么把"灯"当噪声切掉。
     *
     * 但两者的"形状"完全不同：
     *   · 人声：有基频和共振峰，浊音段过零率 0.03~0.15，清音（s/sh/c）可到 0.3
     *   · 空调/风扇/电流这种稳态噪声：集中在低频，过零率极低，通常 < 0.02
     * 所以"能量 + 过零率"两个维度才能把"弱语音"和"强噪声"分开。
     */
    public static double zcr(byte[] buf, int len, int ch) {
        if (buf == null) return 0;
        if (len > buf.length) len = buf.length;
        int off = ch * 2;
        int n = 0, changes = 0, last = 0;
        for (int i = off; i + 1 < len; i += 4) {
            int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            if ((v >= 0) != (last >= 0)) changes++;
            last = v;
            n++;
        }
        return n > 0 ? (double) changes / n : 0;
    }

    /** 48k 立体声 → 16k 单声道：取更响声道 + 3:1 抽取 + 软件增益 */
    public static byte[] to16kMono(byte[] raw, int len, int ch, double gain) {
        if (len > raw.length) len = raw.length;
        int inFrames = len / 4;
        int outFrames = inFrames / 3;
        byte[] mono = new byte[outFrames * 2];
        int off = ch * 2;
        for (int i = 0; i < outFrames; i++) {
            int s = i * 12 + off;
            int v = (short) ((raw[s] & 0xff) | (raw[s + 1] << 8));
            int o = (int) Math.round(v * gain);
            if (o > 32767) o = 32767; else if (o < -32768) o = -32768;
            mono[i * 2] = (byte) (o & 0xff);
            mono[i * 2 + 1] = (byte) ((o >> 8) & 0xff);
        }
        return mono;
    }

    public static byte[] to16kMono(byte[] raw, int ch, double gain) {
        return to16kMono(raw, raw.length, ch, gain);
    }

    /**
     * 播放变速（线性插值）。factor > 1 变快且音调变高，< 1 变慢且低沉。
     * 带跨帧状态，可以逐帧调用。
     */
    public static class Resampler {
        private final double factor;
        private short[] buf = new short[8192];
        private int size = 0;
        private double pos = 0;

        public Resampler(double factor) {
            this.factor = factor <= 0 ? 1.0 : factor;
        }

        public byte[] process(byte[] in) {
            if (Math.abs(factor - 1.0) < 0.001 || in == null || in.length < 2) return in;
            int n = in.length / 2;
            if (size + n > buf.length) {
                short[] nb = new short[Math.max(buf.length * 2, size + n)];
                System.arraycopy(buf, 0, nb, 0, size);
                buf = nb;
            }
            for (int i = 0; i < n; i++) {
                buf[size + i] = (short) ((in[i * 2] & 0xff) | (in[i * 2 + 1] << 8));
            }
            size += n;

            // 采样点数：从 pos 起每 factor 个输入取一个
            int outN = (int) Math.floor((size - 1 - pos) / factor);
            if (outN <= 0) return new byte[0];
            byte[] out = new byte[outN * 2];
            double p = pos;
            for (int i = 0; i < outN; i++) {
                int i0 = (int) p;
                double f = p - i0;
                int a = buf[i0];
                int b = buf[Math.min(i0 + 1, size - 1)];
                int v = (int) (a + (b - a) * f);
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                out[i * 2] = (byte) (v & 0xff);
                out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
                p += factor;
            }
            // 丢掉已经消费掉的输入，保留一点余量给下一帧插值
            int consumed = (int) p;
            if (consumed > 0) {
                int keep = size - consumed;
                if (keep < 0) keep = 0;
                System.arraycopy(buf, consumed, buf, 0, keep);
                size = keep;
                pos = p - consumed;
            } else {
                pos = p;
            }
            return out;
        }
    }
}
