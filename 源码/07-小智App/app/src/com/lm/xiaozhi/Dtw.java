package com.lm.xiaozhi;

/**
 * DTW（动态时间规整）—— 用于比较两段 MFCC 序列的形状相似度。
 *
 * 为什么不能用欧氏距离直接比：同一个人两次说"小智"，一次 420 ms、一次 560 ms，
 * 帧数 42 对 56，逐帧对齐会把后半句全错开。DTW 允许时间轴非线性伸缩，
 * 把"小"="小"、"智"="智" 对上，只比较音色轨迹，不管语速。
 *
 * 两个工程上的关键点：
 *   1) Sakoe-Chiba 带 —— 限制路径偏离对角线的幅度。不加这个约束，DTW 会
 *      找到一条病态路径（把一帧对到几十帧上），距离算出来很小但毫无意义。
 *      带宽取 max(n,m) 的 30%，实测刚好允许正常语速差，又挡住病态对齐。
 *   2) 距离除以 (n+m) —— 路径长度约等于 n+m，归一化之后不同长度的模板
 *      才有可比性，否则永远是"最短的那个模板最近"。
 *
 * 距离用平方值不开根号（省掉每格一次 sqrt），阈值也用平方尺度。
 */
public class Dtw {

    private static final float BIG = 1e9f;

    // 复用两块行缓冲，避免每次匹配都 new 二维数组（段最长 150 帧，一帧 24 float，
    // 一次匹配临时分配 14 KB，每秒十几次就是稳定的 GC 抖动，在这台 1 GB 机器上很致命）
    private float[] prev;
    private float[] cur;

    public float dist(float[][] a, float[][] b) {
        return dist(a, a.length, b);
    }

    /**
     * @param aLen 只取 a 的前 aLen 行 —— 滑窗匹配时复用同一块缓冲，不用每次复制子数组
     * @return 归一化距离（平方尺度）。越小越像，典型命中值 0.2~0.6，无关语音 1.5 以上。
     */
    public float dist(float[][] a, int aLen, float[][] b) {
        int n = aLen, m = b.length;
        if (n < 6 || m < 6) return BIG;

        if (prev == null || prev.length < m + 1) {
            prev = new float[m + 1];
            cur = new float[m + 1];
        }

        // ★★ 带宽必须能容下两段长度的差 ★★
        //   原来只有 max(n,m)*0.30 + 4。当 n=30、m=79 时带宽 27，
        //   可路径要从 (1,1) 走到 (30,79)，光 j 方向就要走 78 步，
        //   带宽不够 → 整条路径被裁掉 → 返回 BIG（1e9），也就是"永远不匹配"。
        //   实测 8 条模板里有 5 条因此**从来没参与过判定**，
        //   top3 平均实际只由剩下 3 条算，判据完全失真。
        int band = Math.abs(n - m) + 6;
        int wide = (int) (Math.max(n, m) * 0.30) + 4;
        if (wide > band) band = wide;

        for (int j = 0; j <= m; j++) prev[j] = BIG;
        prev[0] = 0;

        for (int i = 1; i <= n; i++) {
            for (int j = 0; j <= m; j++) cur[j] = BIG;
            int jlo = i - band; if (jlo < 1) jlo = 1;
            int jhi = i + band; if (jhi > m) jhi = m;
            float[] fa = a[i - 1];
            for (int j = jlo; j <= jhi; j++) {
                float p = prev[j];
                if (prev[j - 1] < p) p = prev[j - 1];
                if (j > 1 && cur[j - 1] < p) p = cur[j - 1];
                if (p >= BIG) continue;
                cur[j] = p + euc2(fa, b[j - 1]);
            }
            float[] t = prev; prev = cur; cur = t;
        }
        float d = prev[m];
        if (d >= BIG) return BIG;
        return d / (n + m);
    }

    /** 每维平均平方差（除以维数，让距离尺度不随特征维数漂移） */
    private static float euc2(float[] x, float[] y) {
        int d = x.length;
        float s = 0;
        for (int k = 0; k < d; k++) {
            float v = x[k] - y[k];
            s += v * v;
        }
        return s / d;
    }
}
