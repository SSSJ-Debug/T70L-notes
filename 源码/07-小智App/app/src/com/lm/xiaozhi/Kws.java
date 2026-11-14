package com.lm.xiaozhi;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 本地唤醒引擎：MFCC + DTW，纯 Java，不联网、不依赖任何厂商库。
 *
 * ═══════════════ 为什么必须走本地 ═══════════════
 *
 * 原来的唤醒是"借"云端 ASR 做的：录一整句 → 上传 → 等识别文本 → 用 contains("小智")
 * 判断。这在链路上有个绕不过去的死结 —— 云端要等你说**完**、静音 300ms 判定收尾、
 * 上传编码、排队识别、返回文本，用户才能听到反应。实测 1.5~3 秒。
 * 主流智能音箱"喊一声就应"不是因为它们的服务器快，而是因为唤醒发生在**设备本地**，
 * 说完最后一个字 200ms 内就出结果，云端那条链路是在"应了之后"才开始的。
 *
 * 顺带解决第二个病根：原来靠能量阈值决定"要不要开口"，房间噪声一抖就误触发，
 * 一触发就占麦 1~6 秒上传识别，这期间真人喊的话没人听 —— 这就是"时灵时不灵"。
 * 现在开口的**唯一**依据是"听清了小智这两个字"，噪声再抖也不会占麦。
 *
 * ═══════════════ 怎么做到"同一个词、不同人、不同距离"都能认 ═══════════════
 *
 * MFCC 去掉 c0（总能量）后做 CMN（倒谱均值归一化）—— 相当于把"音色形状"和
 * "音量/距离/麦克风增益"彻底解耦。同一个人 3 米外和 30 厘米说"小智"，
 * 归一化后的特征几乎重合，而"放歌""你好小智"的形状完全不同。
 * DTW 再把时间轴拉伸对齐，容忍语速差异。
 *
 * 最终判定只用一层：类内距离的 2 倍。类内距离 = 训练时那 12 遍之间的平均距离，
 * 是"这个人说这个词"的天然抖动尺度。所以阈值跟着人走，不用手工调。
 *
 * ═══════════════ 与主循环的数据契约 ═══════════════
 *
 * 主循环每 100ms 调一次 feed()，**只做一次数组拷贝就返回**（offer 到有界队列，
 * 满了直接丢）。全部分析在独立最低优先级线程做，绝不阻塞录音 —— 这个原则是
 * 血的教训换来的：主循环哪怕卡 50ms，ALSA 缓冲就溢出，之后读到全零，
 * 得重启进程才能恢复。
 *
 * 命中时不只给一个布尔值，还把"小智"那一段**原始 PCM** 一起交回去，
 * 主循环把它当作本轮录音的开头直接送给云端。这样云端 ASR 收到的仍然是
 * 完整的"小智开灯"，下游的 isWake 逻辑一个字都不用改，而且不丢"开"的字头。
 */
public class Kws {

    private static final String TAG = "Kws";

    // ── 端点检测参数 ──
    private static final int END_SIL = 18;      // 连续 180 ms 静音 → 一段结束
    // ★ 26 → 32（320ms）。这是被实测数据逼出来的：
    //   合成音经扬声器放出来，麦克风只能切到 260ms 的残缺片段，
    //   拿这种片段当模板，跟任何 260ms 的噪声片段的距离都只有 0.3 几 ——
    //   因为序列太短，CMN 又抹掉了频谱倾斜，统计上根本分不开。
    //   验证过：同一份录音，用 26 帧和用完整 79 帧当模板，
    //   跟真实噪声的距离分别是 3.5 和 2.9，但跟"另一个短片段"距离都在 0.3 量级。
    //   结论：模板必须够长。真人说"小智"两个音节至少 350ms，32 帧是合理下限。
    private static final int MIN_FRM = 32;
    private static final int MAX_FRM = 160;     // 1.6 s 以上不是唤醒词，直接丢

    // ── 滑窗匹配 ──
    // "小智开灯"连说时，KWS 切出来的段是整句 1.2 秒，而模板只有 0.5 秒。
    // 所以要在段内滑动一个固定宽度的窗口去找"小智"最像的那一段。
    // 只滑前 12 个位置（最多 360 ms）：唤醒词永远在句首，用户不会先说
    // "那个……小智"，真那么说了漏一次也无所谓，比每次多算十倍要值。
    private static final int SLIDE_STEP = 3;
    private static final int SLIDE_MAX = 12;

    private static final int COOL_MS = 900;     // 命中后 900ms 内不再检测（躲自己的提示音+回声）
    private static final int RING = 32768;      // 2.05 秒 PCM 环形缓冲
    private static final int MASK = RING - 1;

    /** 一次命中：pcm 是 16k 单声道原始样本（含唤醒词），dist 是匹配距离（越小越像） */
    public static class Hit {
        public final short[] pcm;
        public final float dist;
        public Hit(short[] p, float d) { pcm = p; dist = d; }
    }

    /** 训练回调（在 KWS 线程里调用） */
    public interface TrainCb {
        void onSeg(float[][] feat, short[] pcm);
        /**
         * 这一段被质量门禁退回了，不算一遍。
         *
         * reason：1 = 太长（说到一半没停，或被环境噪声拖着不收尾）
         *         2 = 太轻（远低于训练起点门限，基本是环境噪动）
         *         3 = 跟上一次太近（500ms 内，属于同一句）
         */
        void onReject(int reason);
    }

    private final Mfcc mfcc = new Mfcc();
    private final Dtw dtw = new Dtw();

    private final short[] tail = new short[512];
    private int tailLen = 0;
    private short[] tmp = new short[8192];

    private final short[] ring = new short[RING];
    private long ringFrame = 0;                 // 已写入环形缓冲的帧数

    private final float[] cur = new float[Mfcc.NCEP];

    private final LinkedBlockingQueue<Blk> q = new LinkedBlockingQueue<Blk>(24);
    private final LinkedBlockingQueue<short[]> pool = new LinkedBlockingQueue<short[]>(32);

    /**
     * 队列元素带时间戳。
     *
     * vulture.app.home 在后台每秒 180 次录音重试，把 CPU 吃掉一大块，
     * KWS 线程（最低优先级）会被挤得处理不过来，队列里压着 1~2 秒前的音频。
     * 不做时效检查的话，播报结束、主循环恢复喂数据时，KWS 会从最旧的那帧
     * 开始慢慢啃 —— 用户听到的是"我都说完半分钟了它突然应一声"。
     * 实测日志抓到过 1.8 秒的滞后。
     */
    private static final class Blk {
        final short[] pcm;
        final long at;
        Blk(short[] p, long t) { pcm = p; at = t; }
    }

    private volatile boolean alive = false;
    private Thread worker;

    /**
     * 抑制开关：正在播报 / 刚播完的余音期里由主循环置 true。
     *
     * 为什么需要它（而不是只靠主循环"不喂数据"）：
     *   主循环不喂，但队列里可能还压着十几帧没消化的数据。等播报结束、
     *   主循环重新开始喂的时候，KWS 才把这些**播报期间攒下的**旧帧分析完 ——
     *   其中就有 TTS 的声音。TTS 的音色和训练模板同源，距离只有 0.23，
     *   稳稳命中 → 又开一轮 → 又播 TTS → 自己和自己的 TTS 聊起来。
     *   实测日志：14:05:35 命中 → 播报 → 14:05:41 又命中，无限循环。
     *
     * 置 true 时不是"暂停"，而是"丢弃并重置段状态"，保证播报结束后
     * 从干净的状态重新开始，不会拿着半截旧音频去做匹配。
     */
    public volatile boolean suppress = false;

    private int nEst = -1;
    private int gate = 300;

    // 段状态
    private boolean inSeg = false;
    private int silRun = 0;
    private int segPeak = 0;
    /**
     * 上一次**被接受**的训练样本时间。用来保证两遍之间至少有 500ms 间隔，
     * 否则一口气连着说会被切成两段、算成两遍。
     */
    private long lastAccAt = 0;
    private float segFlat = 0;
    /** 段内最小的三个谱平坦度。取最小值而不是平均：段里混着静音帧和噪声帧，
     *  一平均就全被拉高（实测语音段平均 0.444、噪声段平均 0.41，完全重叠）；
     *  但真语音里一定有几个浊音帧（"小"的 iao、"智"的 i）平坦度低到 0.05~0.15，
     *  噪声段永远没有这种谷。 */
    private float f1 = 1, f2 = 1, f3 = 1;
    private final ArrayList<float[]> segF = new ArrayList<float[]>();

    private long coolUntil = 0;
    private volatile Hit hit = null;

    private volatile boolean train = false;
    private volatile TrainCb trainCb = null;

    // 模板
    private final ArrayList<float[][]> tpls = new ArrayList<float[][]>();
    private float intra = 0.30f;
    private float thr = 0.60f;
    private int wlen = 55;
    private float[][] winBuf = new float[0][];
    private float[] dtmp = new float[64];
    private volatile boolean enabled = false;
    private final File tplFile;

    public Kws(File tplFile) {
        this.tplFile = tplFile;
        loadTemplates();
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    public void start() {
        if (alive) return;
        alive = true;
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "xz-kws");
        worker.setDaemon(true);
        // 最低优先级：vulture 那个每秒 180 次录音重试的疯子还在后台跑，
        // 唤醒引擎绝不能跟它抢 CPU，宁可晚 10ms 出结果。
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.start();
    }

    public void stop() {
        alive = false;
        Thread w = worker;
        if (w != null) w.interrupt();
        worker = null;
        q.clear();
    }

    /** 有模板 + 开关打开（或正处在训练态），才值得往这里喂数据 */
    public boolean ready() { return alive && (train || (enabled && !tpls.isEmpty())); }

    public void setEnabled(boolean b) { enabled = b; }

    public boolean hasTpl() { return !tpls.isEmpty(); }

    public float threshold() { return thr; }

    public int tplCount() { return tpls.size(); }

    /** 模板更新后重新读盘（训练完调用） */
    public void reload() { loadTemplates(); }

    // ───────────────────────── 喂数据 ─────────────────────────

    /**
     * 喂一段 16k 单声道 PCM（小端 byte[]）。任意长度，内部排队，**绝不阻塞**。
     */
    public void feed(byte[] pcm16k, int len) {
        if (!alive || suppress) return;
        if (!train && (!enabled || tpls.isEmpty())) return;
        if (pcm16k == null || len < 2) return;
        if (len > pcm16k.length) len = pcm16k.length;
        int n = len / 2;
        short[] s = pool.poll();
        if (s == null || s.length != n) s = new short[n];
        for (int i = 0; i < n; i++) {
            s[i] = (short) ((pcm16k[i * 2] & 0xff) | (pcm16k[i * 2 + 1] << 8));
        }
        // 队列满了丢新的，不丢旧的 —— 丢了旧数据会让唤醒结果更晚，
        // 而丢新数据只是这一帧不参与，下一帧马上补上。
        q.offer(new Blk(s, System.currentTimeMillis()));
    }

    /** 取走一次命中（取完即清空） */
    public synchronized Hit takeHit() {
        Hit h = hit;
        hit = null;
        if (h != null) {
            // 命中后把队列里还没消化的旧帧全部丢掉。
            // 主循环是在下一帧开头才来取结果的（最多晚 100ms），这期间队列里
            // 可能还压着"小智"那一段的尾巴；不清理的话 KWS 会把同一句话
            // 再分析一遍 → 同一个唤醒词连中两次 → 云端被喂两轮重复音频。
            // 日志实证：13:59:30 命中和 13:59:32 命中，送上去的识别结果都是"我。"。
            q.clear();
            tailLen = 0;
        }
        return h;
    }

    /** 清掉待处理的命中（例如用户刚手动操作过，不需要再回应） */
    public synchronized void clearHit() { hit = null; }

    // ───────────────────────── 训练 ─────────────────────────

    public void setTrain(boolean b, TrainCb cb) {
        this.trainCb = cb;
        this.train = b;
        segF.clear();
        inSeg = false;
        silRun = 0;
        segPeak = 0;
        // 每次开训都重置间隔计时，否则上一轮训练留下的时间戳
        // 会把新一轮的第一遍误判成"离上一遍太近"（reason=3）而白白丢掉。
        lastAccAt = 0;
    }

    // ───────────────────────── 工作线程 ─────────────────────────

    private void loop() {
        while (alive) {
            Blk b;
            try {
                b = q.take();
            } catch (InterruptedException e) {
                break;
            }
            short[] blk = b.pcm;
            try {
                long age = System.currentTimeMillis() - b.at;
                if (age > 1000) {
                    // 超过 1 秒还没轮到处理 → 这段音频已经没有实时价值了。
                    // 直接丢掉并重置段状态，否则会从半截开始切出一个假段。
                    if (inSeg) { inSeg = false; silRun = 0; segF.clear(); }
                    tailLen = 0;
                } else {
                    process(blk);
                }
            } catch (Throwable e) {
                L.e(TAG, "KWS 处理异常: " + android.util.Log.getStackTraceString(e));
            }
            if (pool.size() < 32) pool.offer(blk);
        }
    }

    private void process(short[] blk) {
        // 抑制期：把这一块连同段状态一起丢掉。
        // 不能只 return 不处理 —— 那样 tail 会错位，之后所有帧的时间轴都歪掉。
        if (suppress) {
            inSeg = false;
            silRun = 0;
            segF.clear();
            tailLen = 0;
            return;
        }
        int total = tailLen + blk.length;
        if (tmp.length < total) {
            short[] nt = new short[Math.max(total, tmp.length * 2)];
            tmp = nt;
        }
        System.arraycopy(tail, 0, tmp, 0, tailLen);
        System.arraycopy(blk, 0, tmp, tailLen, blk.length);

        int pos = 0;
        while (pos + Mfcc.WIN <= total) {
            onFrame(tmp, pos);
            pos += Mfcc.HOP;
        }
        // 保留未处理的尾巴，下一块拼上来之后帧起点才严格落在 160 的整数倍上。
        // 这一步错了会让每秒 100 帧变成 96 帧，特征被慢慢拉长 —— 极难查的 bug。
        int keep = total - pos;
        if (keep > tail.length) keep = tail.length;
        if (keep > 0) System.arraycopy(tmp, pos, tail, 0, keep);
        tailLen = keep;
    }

    private void onFrame(short[] pcm, int off) {
        // 1) 写环形缓冲（这一帧的全部 400 样本，重叠部分下一帧会覆盖成相同值）
        long base = ringFrame * Mfcc.HOP;
        for (int i = 0; i < Mfcc.WIN; i++) ring[(int) ((base + i) & MASK)] = pcm[off + i];
        ringFrame++;

        // 2) 特征
        mfcc.frame(pcm, off, cur);

        // 3) 帧能量
        long s = 0;
        for (int i = 0; i < Mfcc.WIN; i++) {
            int v = pcm[off + i];
            s += (long) v * v;
        }
        int rms = (int) Math.sqrt((double) s / Mfcc.WIN);

        // 4) 底噪估计：只让"接近底噪"的帧参与，说话帧一律不动它
        if (nEst < 0) nEst = Math.max(30, Math.min(rms, 600));
        else if (rms < nEst) nEst = (int) (nEst * 0.85 + rms * 0.15);
        else if (rms < nEst * 1.6) nEst = (int) (nEst * 0.998 + rms * 0.002);
        if (nEst > 600) nEst = 600;
        // 切段门限：原来 1.9 倍（底噪 400 → 760），实测 -12dB 的"小智"
        // （相当于 2~3 米外说话）就切不出段了，直接漏唤醒。
        // KWS 的判定不靠能量（靠音色），能量只用来决定"从哪切到哪"，
        // 所以门限可以压得很低 —— 多切几段只是多算几次 DTW，
        // 误匹配交给 top3 距离 + 浊音谷两道判据去挡。
        gate = Math.max((int) (nEst * 1.35), 200);

        long now = System.currentTimeMillis();
        if (now < coolUntil) {
            inSeg = false;
            silRun = 0;
            segF.clear();
            return;
        }

        if (!inSeg) {
            // ★ 训练时的起点门限比识别时高一档：
            //   识别宁可多切几段（多算几次 DTW），训练却一条都不能脏 ——
            //   只要混进一条噪声样本，类内距离整体被抬高，阈值就跟着松掉，
            //   从此天天误唤醒（2026-11-08 就是这么坏掉的）。
            //   实测本机底噪 350，真人 3 米喊"小智"峰值 600~1300，
            //   1.7 倍（约 600）既挡得住环境噪动，又不至于把远场真话挡在外面。
            int startGate = train ? Math.max((int) (nEst * 1.7f), 430) : gate;
            if (rms > startGate) {
                inSeg = true;
                silRun = 0;
                segPeak = rms;
                segF.clear();
                segFlat = mfcc.flatness;
                f1 = mfcc.flatness; f2 = 1; f3 = 1;
                segF.add(copy(cur));
            }
            return;
        }

        segF.add(copy(cur));
        segFlat += mfcc.flatness;
        pushFlat(mfcc.flatness);
        // ★★ 结束判据改成"相对段内峰值"（2026-11-08 晚修订）★★
        //   原来只看绝对门限：rms <= gate 才算静音。房间底噪 350、gate 只有 470，
        //   噪声帧在 350~600 之间来回抖，180ms 的连续静音永远攒不够，
        //   于是一段一路涨到 1.6 秒上限才被砍断。
        //   后果就是那次学习：12 遍里 9 遍变成 160 帧的整块，模板里装的是
        //   "小智 + 后面一大截噪声"，彼此毫不相像（类内 0.653），
        //   阈值被顶到上限 0.90，噪声 0.82 就能"命中" —— 一天到晚自己在跟自己说话。
        //   现在：低于"段内峰值的 40%"连续 180ms 就算说完，噪声再抖也拦不住收尾。
        if (rms > segPeak) segPeak = rms;
        int endThr = Math.max(gate, (int) (segPeak * 0.40f));
        if (rms > endThr) silRun = 0;
        else silRun++;

        int n = segF.size();
        if (silRun >= END_SIL || n >= MAX_FRM) {
            int endFrame = (int) (ringFrame - 1) - silRun;
            int drop = Math.min(silRun, n - MIN_FRM);
            if (drop < 0) drop = 0;
            finishSeg(n - drop, endFrame);
        }
    }

    /** 维护段内最小的三个平坦度 */
    private void pushFlat(float v) {
        if (v < f1) { f3 = f2; f2 = f1; f1 = v; }
        else if (v < f2) { f3 = f2; f2 = v; }
        else if (v < f3) { f3 = v; }
    }

    private void finishSeg(int keep, int endFrame) {
        inSeg = false;
        silRun = 0;
        int rawN = segF.size();
        float flatAvg = rawN > 0 ? segFlat / rawN : 1f;
        // 浊音谷：段内最浊的三帧的平均。语音 0.05~0.2，噪声 0.25 以上。
        float flat = (f1 + f2 + f3) / 3f;
        segFlat = 0;
        f1 = f2 = f3 = 1;
        if (keep < MIN_FRM) { segF.clear(); return; }
        if (keep > MAX_FRM) keep = MAX_FRM;
        if (keep > segF.size()) keep = segF.size();

        float[][] seg = new float[keep][];
        for (int i = 0; i < keep; i++) seg[i] = segF.get(i);
        segF.clear();

        // 倒谱均值归一化：整段每一维减去这一段的均值。
        // 这一步是"远场也能叫醒"的核心 —— 它把麦克风增益、距离衰减、
        // 设备滤波这些**整段恒定的乘性影响**全部消掉，剩下的才是音色形状。
        cmn(seg);

        // 往前多带 3 帧（30ms）给云端 ASR：段的起点常常落在"小"字的
        // 韵母上，声母 x 是清擦音能量低，可能被 gate 挡在外面。
        int startF = endFrame - keep + 1 - 3;
        if (startF < 0) startF = 0;
        short[] pcm = readRing(startF, endFrame);

        // 调试落盘：私有目录下建个 kwsdump 文件夹，就会把每一段存成 wav。
        // 用来查"为什么噪声也能命中"——只有拿到真实录音才能判断到底是环境里
        // 真有人说话，还是录音链路把噪声处理成了假语音。
        dumpSeg(pcm);

        if (train) {
            L.i(TAG, "训练段 " + keep + " 帧 / " + pcm.length + " 样本 / 峰值 " + segPeak
                    + " / 浊音谷 " + f(flat) + " / 平坦均值 " + f(flatAvg));
            TrainCb cb = trainCb;
            long now = System.currentTimeMillis();
            // ★★ 训练质量门禁（2026-11-08 晚）★★
            //   这三条是拿"学废了的那份模板"反推出来的：那 12 遍里有 9 遍是
            //   1.6 秒的整块（撞上限），模板里装的其实是"小智 + 一截噪声"。
            //   一条脏样本就足以把类内距离从 0.3 抬到 0.65，阈值被顶到 0.90，
            //   于是噪声 0.82 也能"命中"，全屋人说话都能把它叫起来。
            //   宁可让用户多喊两遍，也不能让一条脏样本进模板。
            int reason = 0;
            if (keep >= MAX_FRM - 2) {
                reason = 1;      // 撞了 1.6 秒上限：说明没停顿，或被噪声拖着不收尾
            } else if (segPeak < Math.max((int) (nEst * 1.7f), 430)) {
                reason = 2;      // 峰值不够：环境噪动，不是人声
            } else if (lastAccAt > 0 && now - lastAccAt < 500) {
                reason = 3;      // 离上一遍太近：还是同一句的尾巴
            }
            if (reason != 0) {
                L.i(TAG, "训练段退回 reason=" + reason);
                if (cb != null) cb.onReject(reason);
                return;
            }
            lastAccAt = now;
            if (cb != null) cb.onSeg(seg, pcm);
            return;
        }
        match(seg, pcm, flat, flatAvg);
    }

    private void dumpSeg(short[] pcm) {
        try {
            File dir = new File(tplFile.getParentFile(), "kwsdump");
            if (!dir.exists() || pcm.length < 100) return;
            if (dir.list() != null && dir.list().length > 60) return;   // 别把磁盘塞满
            File f = new File(dir, "s" + System.currentTimeMillis() + ".wav");
            FileOutputStream fo = new FileOutputStream(f);
            int dataLen = pcm.length * 2;
            byte[] h = new byte[44];
            h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
            put32(h, 4, 36 + dataLen);
            h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
            h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
            put32(h, 16, 16);
            put16(h, 20, 1);
            put16(h, 22, 1);
            put32(h, 24, 16000);
            put32(h, 28, 32000);
            put16(h, 32, 2);
            put16(h, 34, 16);
            h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
            put32(h, 40, dataLen);
            fo.write(h);
            byte[] b = new byte[dataLen];
            for (int i = 0; i < pcm.length; i++) {
                b[i * 2] = (byte) (pcm[i] & 0xff);
                b[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xff);
            }
            fo.write(b);
            fo.close();
        } catch (Throwable ignored) {}
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8);
    }

    private void match(float[][] seg, short[] pcm, float flat, float flatAvg) {
        if (tpls.isEmpty()) return;
        int n = seg.length;
        float best = 1e9f, bestSingle = 1e9f;
        int bi = -1;

        if (n <= wlen + 8) {
            best = avg3(seg, n);
            bestSingle = dtmp[0];
            bi = 0;
        } else {
            ensureWin(wlen);
            int maxStart = Math.min(n - wlen, SLIDE_MAX * SLIDE_STEP);
            for (int st = 0; st <= maxStart; st += SLIDE_STEP) {
                for (int k = 0; k < wlen; k++) {
                    System.arraycopy(seg[st + k], 0, winBuf[k], 0, Mfcc.NCEP);
                }
                float s = avg3(winBuf, wlen);
                if (s < best) { best = s; bestSingle = dtmp[0]; bi = st; }
                // 已经比阈值小三成，不用再往后滑了（滑窗是为了找唤醒词，
                // 找到就该停，继续滑只会找到更"凑巧"的短片段）
                if (bi >= 0 && best < thr * 0.7f) break;
            }
        }

        boolean ok = bi >= 0 && best < thr;
        L.i(TAG, "段 " + n + "帧 top3=" + f(best) + " 最近=" + f(bestSingle)
                + " 浊音谷=" + f(flat) + " 均=" + f(flatAvg)
                + " 阈值=" + f(thr) + (ok ? " ★命中" : ""));

        if (ok) {
            synchronized (this) { hit = new Hit(pcm, best); }
            coolUntil = System.currentTimeMillis() + COOL_MS;
        }
    }

    /**
     * 输入与所有模板的距离，排序后取前 3 个的平均。
     *
     * ★★ 这是抗误唤醒最关键的一处改动 ★★
     *   原来用"最小距离"，实测在 vulture 每秒 180 次录音重试的爆音环境下，
     *   2 分钟误唤醒 9 次（4.5 次/分钟），完全没法用。
     *   原因很实在：10 条模板里只要有一条在某个 27 帧窗口上"凑巧"像，
     *   就被判成命中 —— 10 条里蒙中 1 条的概率并不低。
     *
     *   但真人说"小智"时，是**十条模板全都像**（它们本来就是同一个词的
     *   十二个变体，类内平均距离才 0.3）。所以取前 3 名的平均：
     *     · 真语音：三条都接近 → 平均 ≈ 最小值，基本不变
     *     · 噪声：只有一两条偶然接近，第三条必然拉高 → 平均被显著抬高
     *   这一改把"碰巧"的成本从 1/10 提到 (1/10)³ 量级。
     */
    private float avg3(float[][] a, int aLen) {
        int tn = tpls.size();
        if (dtmp.length < tn) dtmp = new float[tn];
        for (int t = 0; t < tn; t++) dtmp[t] = dtw.dist(a, aLen, tpls.get(t));
        Arrays.sort(dtmp, 0, tn);
        int k = tn < 3 ? tn : 3;
        float s = 0;
        for (int i = 0; i < k; i++) s += dtmp[i];
        return s / k;
    }

    private void ensureWin(int w) {
        if (winBuf.length != w) {
            winBuf = new float[w][Mfcc.NCEP];
        }
    }

    // ───────────────────────── PCM 环形缓冲 ─────────────────────────

    private short[] readRing(int startF, int endF) {
        int len = (endF - startF) * Mfcc.HOP + Mfcc.WIN;
        if (len <= 0) return new short[0];
        if (len > RING - Mfcc.WIN) {
            startF = endF - (RING - Mfcc.WIN) / Mfcc.HOP;
            len = (endF - startF) * Mfcc.HOP + Mfcc.WIN;
        }
        short[] out = new short[len];
        long base = (long) startF * Mfcc.HOP;
        for (int i = 0; i < len; i++) out[i] = ring[(int) ((base + i) & MASK)];
        return out;
    }

    // ───────────────────────── 工具 ─────────────────────────

    private static float[] copy(float[] a) {
        float[] r = new float[a.length];
        System.arraycopy(a, 0, r, 0, a.length);
        return r;
    }

    private static void cmn(float[][] f) {
        int n = f.length;
        if (n <= 0) return;
        int d = f[0].length;
        float[] m = new float[d];
        for (int i = 0; i < n; i++) {
            float[] r = f[i];
            for (int k = 0; k < d; k++) m[k] += r[k];
        }
        for (int k = 0; k < d; k++) m[k] /= n;
        for (int i = 0; i < n; i++) {
            float[] r = f[i];
            for (int k = 0; k < d; k++) r[k] -= m[k];
        }
    }

    private static String f(float v) { return String.format(Locale.US, "%.3f", v); }

    // ───────────────────────── 模板读写 ─────────────────────────

    private static final int MAGIC = 0x4B575331;   // "KWS1"

    public void loadTemplates() {
        tpls.clear();
        DataInputStream in = null;
        try {
            if (!tplFile.exists()) return;
            in = new DataInputStream(new FileInputStream(tplFile));
            if (in.readInt() != MAGIC) { L.w(TAG, "模板文件 magic 不对，忽略"); return; }
            int dim = in.readInt();
            if (dim != Mfcc.NCEP) { L.w(TAG, "模板维数不匹配: " + dim); return; }
            int n = in.readInt();
            intra = in.readFloat();
            thr = in.readFloat();
            wlen = in.readInt();
            for (int i = 0; i < n; i++) {
                int fr = in.readInt();
                if (fr <= 0 || fr > 400) break;
                float[][] t = new float[fr][dim];
                for (int a = 0; a < fr; a++) {
                    for (int b = 0; b < dim; b++) t[a][b] = in.readFloat();
                }
                tpls.add(t);
            }
            if (wlen < 10) wlen = 55;
            L.i(TAG, "模板加载 " + tpls.size() + " 条，阈值 " + f(thr) + "，窗宽 " + wlen);
        } catch (Throwable e) {
            L.e(TAG, "模板读取失败: " + e);
            tpls.clear();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 从训练样本里构建模板文件。
     *
     * 三步剔除，全自动，用户不用管：
     *   1) 长度离群 —— 太短（说漏了）太长（说了"小智小智"）的先按中位数的 ±60% 筛掉
     *   2) 形状离群 —— 每条跟其余各条的平均距离，超过中位数 1.9 倍的丢掉
     *      （这通常是训练时刚好咳了一下、或者旁边有人说话）
     *   3) 类内均值只用**留下来的**那批算，阈值 = 2 倍。
     *
     * @return 给用户看的一句话结果
     */
    public static String buildTemplates(File out, List<float[][]> raw) {
        if (raw == null || raw.size() < 4) return "样本太少（" + (raw == null ? 0 : raw.size()) + "），至少要 4 条";

        int[] lens = new int[raw.size()];
        for (int i = 0; i < lens.length; i++) lens[i] = raw.get(i).length;
        int[] sl = lens.clone();
        Arrays.sort(sl);
        int med = sl[sl.length / 2];
        int lo = (int) (med * 0.60), hi = (int) (med * 1.60);

        ArrayList<float[][]> keep = new ArrayList<float[][]>();
        for (int i = 0; i < raw.size(); i++) {
            float[][] s = raw.get(i);
            if (s.length >= lo && s.length <= hi) keep.add(s);
        }
        if (keep.size() < 4) {
            keep.clear();
            for (int i = 0; i < raw.size(); i++) keep.add(raw.get(i));
        }

        Dtw d = new Dtw();
        int m = keep.size();
        float[][] dm = new float[m][m];
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                float v = d.dist(keep.get(i), keep.get(j));
                dm[i][j] = v;
                dm[j][i] = v;
            }
        }
        float[] rowMean = new float[m];
        for (int i = 0; i < m; i++) {
            float s = 0; int c = 0;
            for (int j = 0; j < m; j++) if (i != j) { s += dm[i][j]; c++; }
            rowMean[i] = c > 0 ? s / c : 0;
        }
        float[] rs = rowMean.clone();
        Arrays.sort(rs);
        float cut = rs[rs.length / 2] * 1.9f;
        ArrayList<float[][]> fin = new ArrayList<float[][]>();
        for (int i = 0; i < m; i++) if (rowMean[i] <= cut) fin.add(keep.get(i));
        if (fin.size() < 4) fin = keep;

        // 类内平均 —— 必须和识别时的口径一致（前 3 名平均），否则阈值对不上：
        // 用"两两平均"算出来的 intra 偏小，再乘 2 得到的阈值会把真人说话挡在外面。
        float sum = 0; int cnt = 0;
        for (int i = 0; i < fin.size(); i++) {
            float[] row = new float[fin.size() - 1];
            int idx = 0;
            for (int j = 0; j < fin.size(); j++) {
                if (i != j) row[idx++] = d.dist(fin.get(i), fin.get(j));
            }
            Arrays.sort(row, 0, idx);
            int k = idx < 3 ? idx : 3;
            float s = 0;
            for (int t = 0; t < k; t++) s += row[t];
            sum += s / k;
            cnt++;
        }
        float intra = cnt > 0 ? sum / cnt : 0.30f;
        // 阈值系数 1.6：比"类内抖动"宽六成，够容纳远场/近场、快说/慢说的差异，
        // 又不会宽到把不相干的语音放进来。
        // 上限压到 0.90：实测"小智"与真实噪声的距离在 2.7~3.0，
        // 只要阈值不超过 1.2 就有一倍以上的安全余量，0.90 更保守。
        float th = intra * 1.6f;
        if (th < 0.38f) th = 0.38f;
        // ★ 上限从 0.90 压到 0.70（2026-11-08 晚）：0.90 这个上限是被
        //   "学废了的那份模板"逼出来的（类内 0.653 × 1.6 = 1.045 撞顶）。
        //   但它同时也意味着"任何 0.9 以内的东西都算小智" —— 实测噪声能到 0.82，
        //   于是全屋人说话都在唤醒它，一次误唤醒就占麦两秒，真正的"小智开灯"
        //   全被挡在外面。修好训练之后类内会落在 0.3 上下，阈值约 0.48，
        //   0.70 只作为兜底上限，不参与日常判定。
        if (th > 0.70f) th = 0.70f;

        // 训练质量门槛：类内距离太大说明这 12 遍彼此不像
        // （环境太吵、距离变来变去、或者中间夹了别人的声音）。
        // 拿这种样本训练出来的模板，要么漏唤醒要么乱唤醒，不如让用户重录。
        if (intra > 0.72f) {
            return "样本差异太大（类内 " + f(intra) + "），可能环境太吵或距离不一致，请重录";
        }

        int[] fl = new int[fin.size()];
        for (int i = 0; i < fl.length; i++) fl[i] = fin.get(i).length;
        Arrays.sort(fl);
        int w = (int) (fl[fl.length / 2] * 1.15f) + 2;

        DataOutputStream o = null;
        try {
            File dir = out.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            o = new DataOutputStream(new FileOutputStream(out));
            o.writeInt(MAGIC);
            o.writeInt(Mfcc.NCEP);
            o.writeInt(fin.size());
            o.writeFloat(intra);
            o.writeFloat(th);
            o.writeInt(w);
            for (int i = 0; i < fin.size(); i++) {
                float[][] t = fin.get(i);
                o.writeInt(t.length);
                for (int a = 0; a < t.length; a++) {
                    for (int b = 0; b < Mfcc.NCEP; b++) o.writeFloat(t[a][b]);
                }
            }
            o.flush();
        } catch (Throwable e) {
            return "保存失败：" + e;
        } finally {
            try { if (o != null) o.close(); } catch (Throwable ignored) {}
        }

        return "模板 " + fin.size() + " 条 ｜ 类内 " + f(intra) + " ｜ 阈值 " + f(th)
                + " ｜ 窗宽 " + w + " 帧";
    }
}
