package com.lm.xiaozhi;

/**
 * MFCC 特征提取 —— 16 kHz / 25 ms 窗 / 10 ms 步 / 26 Mel / 24 倒谱
 *
 * 为什么自己写而不引第三方：这台 A83T 上没有可用的 .so 加载环境
 * （试过 Vosk，8 MB 的 so 一加载就 abort），而纯 Java 实现全部加起来
 * 不到 20 KB，编进 APK 零风险。
 *
 * 参数不是随便定的，是「人声」和「房间噪声」在这台设备上的实际分布决定的：
 *   · 25 ms 窗：低于 20 ms 抓不到 100 Hz 基频的完整周期，高于 30 ms 会把
 *     相邻音节糊在一起。"小智"两个字每个音节约 200~300 ms，25 ms 刚好。
 *   · 10 ms 步：DTW 的时间分辨率。再密没有信息增益，再疏会让对齐误差变大。
 *   · 24 个倒谱系数（c1..c24，丢掉 c0）：c0 是总能量，会被距离和音量主导。
 *     丢掉它 + 做 CMN 之后，同一个人 3 米外和 30 厘米说"小智"算出来的特征
 *     几乎一样 —— 这正是"远场也要能叫醒"的关键。
 */
public class Mfcc {

    public static final int SR = 16000;
    public static final int WIN = 400;        // 25 ms
    public static final int HOP = 160;        // 10 ms
    public static final int FFT = 512;        // >= WIN 的最小 2 的幂
    public static final int NMEL = 26;
    public static final int NCEP = 24;        // 输出维数

    /**
     * 本帧的谱平坦度（0~1）。几何平均 / 算术平均。
     *
     * 为什么需要这个：MFCC 做过 CMN 之后，"平稳噪声"和"语音"在倒谱空间里
     * 会互相靠近 —— 噪声的倒谱本来就接近它的均值，CMN 一减就全成了零，
     * 而模板 CMN 之后也是围绕零的小幅波动。距离算出来只有 0.3 几，
     * 比真人说话的 0.25 差不了多少，光靠距离根本分不开。
     * （实测：静置 90 秒，vulture 的爆音被切出 26 帧，top3 距离 0.342 直接命中。）
     *
     * 平坦度是另一个维度：
     *   · 白/宽带噪声：能量摊在所有频带上 → 平坦度 0.4~0.9
     *   · 浊音（a/o/e/i 这些元音）：能量集中在共振峰 → 平坦度 0.01~0.2
     * 两者差一个数量级，比距离判据可靠得多。
     */
    public float flatness = 0;

    private final float[] win = new float[WIN];
    private final float[] re = new float[FFT];
    private final float[] im = new float[FFT];
    private final float[] pwd = new float[FFT / 2 + 1];
    private final float[] twr = new float[FFT / 2];
    private final float[] twi = new float[FFT / 2];

    private final int[] f0 = new int[NMEL];
    private final float[][] fw = new float[NMEL][];
    private final float[] mel = new float[NMEL];

    private final float[][] dct = new float[NCEP][NMEL];

    public Mfcc() {
        // 汉明窗
        for (int i = 0; i < WIN; i++) {
            win[i] = (float) (0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (WIN - 1)));
        }
        // FFT 旋转因子（预计算，避免每帧做三角函数）
        for (int k = 0; k < FFT / 2; k++) {
            double a = -2.0 * Math.PI * k / FFT;
            twr[k] = (float) Math.cos(a);
            twi[k] = (float) Math.sin(a);
        }
        // Mel 滤波器组：60 Hz ~ 7600 Hz
        //   下限用 60 而不是 20：20~130 Hz 这一段在 512 点 FFT 里只有 4 个 bin，
        //   三角窗退化成两三个点，算出来的特征全是数值噪声。人声最低基频
        //   （男低音）也就 80 Hz，60 起足够覆盖第一共振峰以下的部分。
        double mlo = melOf(60), mhi = melOf(7600);
        int[] edge = new int[NMEL + 2];
        for (int i = 0; i < NMEL + 2; i++) {
            double hz = hzOf(mlo + (mhi - mlo) * i / (NMEL + 1));
            int b = (int) Math.round(hz * FFT / SR);
            if (b < 1) b = 1;
            if (b > FFT / 2) b = FFT / 2;
            edge[i] = b;
        }
        for (int i = 1; i < NMEL + 2; i++) {
            if (edge[i] <= edge[i - 1]) edge[i] = edge[i - 1] + 1;   // 保证严格递增
        }
        for (int i = 0; i < NMEL; i++) {
            int a = edge[i], c = edge[i + 1], d = edge[i + 2];
            int len = d - a;
            if (len < 2) {                       // 极端兜底，避免除零
                len = 2;
                d = a + 2;
            }
            f0[i] = a;
            float[] w = new float[len];
            for (int k = a; k < d; k++) {
                float v;
                if (k <= c) v = (c == a) ? 0f : (float) (k - a) / (c - a);
                else v = (d == c) ? 0f : (float) (d - k) / (d - c);
                w[k - a] = v;
            }
            fw[i] = w;
        }
        // DCT-II（正交归一化），只取 k = 1..NCEP，跳过 k = 0 的能量项
        for (int k = 0; k < NCEP; k++) {
            for (int i = 0; i < NMEL; i++) {
                dct[k][i] = (float) (Math.cos(Math.PI * (k + 1) * (i + 0.5) / NMEL)
                        * Math.sqrt(2.0 / NMEL));
            }
        }
    }

    private static double melOf(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    private static double hzOf(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    /**
     * 算一帧。x 从 off 开始取 WIN 个样本，结果写进 dst（长度 NCEP）。
     */
    public void frame(short[] x, int off, float[] dst) {
        // 去直流：把这一帧的均值减掉。设备带轻微偏置，不去掉的话
        // 第一个 Mel 滤波器永远被一个恒定分量顶着，低频维度全是死值。
        float mean = 0;
        for (int i = 0; i < WIN; i++) mean += x[off + i];
        mean /= WIN;

        float prev = 0;
        for (int i = 0; i < WIN; i++) {
            float raw = x[off + i] - mean;
            // 预加重 0.97：抵消声门激励带来的 -6 dB/oct 谱倾斜，
            // 让高频谱（辅音 s/sh/x 所在区域）的能量能体现出来
            float v = raw - 0.97f * prev;
            prev = raw;
            re[i] = v * win[i];
            im[i] = 0;
        }
        for (int i = WIN; i < FFT; i++) { re[i] = 0; im[i] = 0; }

        fft();

        for (int i = 0; i <= FFT / 2; i++) pwd[i] = re[i] * re[i] + im[i] * im[i];

        float sumP = 0, sumLog = 0;
        for (int m = 0; m < NMEL; m++) {
            float[] w = fw[m];
            int b = f0[m];
            float s = 0;
            for (int k = 0; k < w.length; k++) s += pwd[b + k] * w[k];
            mel[m] = (float) Math.log(s + 1e-6f);      // 对数：把乘性信道变成加性
            sumP += s;
            sumLog += mel[m];
        }

        // 谱平坦度 = exp(对数平均) / 算术平均
        float arith = sumP / NMEL;
        flatness = arith > 1e-9f
                ? (float) (Math.exp(sumLog / NMEL) / arith)
                : 1f;
        if (flatness > 1f) flatness = 1f;

        for (int k = 0; k < NCEP; k++) {
            float[] d = dct[k];
            float s = 0;
            for (int i = 0; i < NMEL; i++) s += mel[i] * d[i];
            dst[k] = s;
        }
    }

    /** 原地基 2 迭代 FFT（512 点，位反转 + 蝶形） */
    private void fft() {
        final int n = FFT;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                int ti = 0;
                for (int k = 0; k < half; k++) {
                    float cr = twr[ti], ci = twi[ti];
                    ti += step;
                    int a = i + k, b = a + half;
                    float xr = re[b] * cr - im[b] * ci;
                    float xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }
}
