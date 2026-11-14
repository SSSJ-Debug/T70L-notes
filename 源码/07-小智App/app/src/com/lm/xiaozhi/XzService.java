package com.lm.xiaozhi;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.IBinder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

/**
 * XzService —— 小智的常驻大脑（单线程干活，杜绝并发抢麦）
 *
 * 一轮对话的旅程：
 *   ① 录音 48k 立体声 → VAD 判开口 → 选更响的声道 + 软件增益归一化
 *   ② 边说边转 16k 单声道 → Opus 60ms 帧 → 直接怼给小智云（不等说完，省掉大半延迟）
 *   ③ 静音 400ms → listen stop → 等识别 + 回答
 *   ④ 识别文字一到就先判定：喊了"小智"才放答案；指令走本地 300ms；否则 abort 掉
 *   ⑤ 回答播完：连续对话模式下立刻回到"听"，静默超过 N 秒才谢幕
 *
 * 为什么不再用云端 ASR 判唤醒：
 *   AIVS 的 access_token 3 天过期（当前那份 11-05 生成，11-08 就废），
 *   判唤醒还得多一次网络往返。现在直接用小智云自己的识别结果判唤醒词，
 *   少一跳、也不再依赖会过期的 token。
 */
public class XzService extends Service implements XzClient.Callbacks {
    static final String TAG = "Xz";

    static final int SR = 48000;
    static final int CHUNK = SR * 4 / 10;        // 100ms / 19200 字节

    // ★★ 2026-11-07 13:55 ★★ PREROLL 12 → 8（0.8s）
    //   实测：开口检测现在已经很灵（阈值 ≈ 底噪×3），不再需要 1.2 秒的缓冲。
    //   而 vulture.app.home 在后台每秒刷 180 行录音错误日志，
    //   把 A83T 的 CPU 吃得很紧，纯 Java 的 Opus 编码被拖到 76ms/帧
    //   （正常 20ms），20 帧就要 1.6 秒 —— "已发完音频"因此从 1.2 秒涨到 2.6 秒。
    //   预录从 12 帧减到 8 帧，少编 4 帧，直接省 0.3 秒，且不影响句首完整性。
    static final int PREROLL = 8;                // 开口前预录 0.8s
    /**
     * ★★ 2026-11-07 20:50 双门限开口（远场灵敏度的关键）★★
     *
     * 用户主诉："两三米外喊它偶尔不理，过一会儿不叫它更明显。"
     * 实测 20:44 日志的硬数据：
     *   底噪 332~420 ｜ 阈值(=底噪×3.0) 996~1260 ｜ 远场开口电平 1419 / 1702
     * 远场话只比阈值高 12%，底噪一抖就压不住 —— 所以不是"听不见"，是"差一点没够到线"。
     *
     * 解法不是简单降阈值（降了噪声会一直误触发，把麦占住 1~6 秒，反而更聋），
     * 而是**两档门限 + 不同的持续时间要求**：
     *   高门限(底噪×2.4)：连续 3 帧(300ms) → 近场、正常音量，反应最快
     *   低门限(底噪×1.5)：连续 6 帧(600ms) → 远场小声，慢 300ms 但能进
     * 人喊一句"小智放歌"持续 1 秒以上，稳过 600ms；
     * 而碰桌子、关门是 1~2 帧的冲击，房间噪声是上下抖的，都攒不出连续 600ms。
     */
    static final int ONSET = 3;                  // 高门限：连续 3 帧(300ms)
    static final int ONSET_LO = 6;               // 低门限(远场)：连续 6 帧(600ms)
    static final double THR_LO_MULT = 1.5;       // 低门限 = 底噪 × 1.5

    // ★★ 2026-11-08 17:55 播报打断（barge-in）★★
    //   初衷：它答一句话要播 3~5 秒，这期间麦克风是关的，家里人正好这时候喊
    //   "小智放歌"就是"喊了没反应"，看着完全随机。
    //
    //   ★★★ 18:00 实测后关闭（BARGE_ON = false）★★★
    //   原假设"TTS 回声经 AEC 后远低于 1600"是**错的**：本机根本没有 AEC。
    //   真机数据：播报期间麦克风 rms 稳定 23400~23470（-3dBFS，已削顶）；
    //   把媒体音量静音，同一句播报只剩 300~400 → 这 23400 就是自家喇叭
    //   被麦克风抄了回去（DMIC 数字增益 190 + 麦离喇叭近，纯声学反馈）。
    //   于是每播一句就被"打断"一次 → 立刻开新一轮 → 云端回一句 → 再播 → 再打断，
    //   17:44~17:46 每 4 秒一轮，永远在录音、永远听不见人话。
    //   关键矛盾：真人在 3 米外喊话只有 600~2000，自家回声 23400 ——
    //   回声比人声高一个数量级，**任何能量门限都只会先撞上回声**。
    //   要做"播报中被打断"必须上回声消除，本机 HAL 没有，所以放弃，
    //   改为半双工：播报期间整帧丢弃，播完立刻恢复（回答只有 1~2 秒）。
    static final boolean BARGE_ON = false;
    static final int BARGE_RMS = 1600;
    static final int BARGE_FRAMES = 5;
    // ★★ 2026-11-07 13:20 ★★ SIL_END 3 → 4，MAX_CHUNKS 32 → 60
    //   用户原话："长对话的时候它老是听不清我说什么，其实我说的很清楚。"
    //   原因不是 ASR 差，是 MAX_CHUNKS=32（3.2 秒）把长句**硬截断**。
    //   "帮我查一下明天北京天气怎么样" 正常语速就要 3 秒以上，
    //   后半句直接被砍掉，云端只能对着半句话猜 → 用户感受就是"听不清"。
    //   现在放到 60 帧（6 秒），正常长句完全录得下；
    //   短句根本用不到这个上限，靠下面的能量判据 1.2 秒左右就收尾。
    //   SIL_END 3→4（400ms）：中文句子中间换气常停 300~400ms，
    //   300ms 会在逗号处就断句，把一句话劈成两半。
    // ★★ 2026-11-11 16:18 4 → 3（300ms）★★
    //   实测每一句指令的"说完 → 开始上传"里，有 400ms 纯粹是等静音。
    //   3 帧（300ms）足够跨过"小智|放歌"之间的字间停顿：
    //   字间停顿实测 180~240ms，而且下面还有 chunks>=10 的最短录音兜底。
    //   这一改把每条指令的端到端延迟直接削掉 100ms。
    static final int SIL_END = 3;                // 300ms 静音才收尾
    static final int MAX_CHUNKS = 60;            // 单句最长 6s（正常长句够用）

    static final int ABS_FLOOR = 80;
    static final double SNR_MULT = 1.6;
    // ★★ 2026-11-07 13:20 ★★ 1500 → 800
    //   1500 是"上限永远够不着"的错误设计：一旦顶到这个值，
    //   待命阈值就被锁在 2250、收尾线锁在 3000，而真实语音只有 900~1200，
    //   于是既喊不醒（阈值高）、又录不进（收尾线高）。
    //   本机 190/3 增益下真实底噪 350~450，800 已经留足余量。
    static final int NOISE_CAP = 800;
    static final double TARGET_PEAK = 0.71;
    // ★ 12 → 4：软件增益只用来给远场小声音补一点点电平。
    //   12 倍会把 400 的底噪放大成 4800，说话 3000 直接削波成 32768，
    //   ASR 拿到的是削平的方波 —— 这也是"听不清"的帮凶。
    static final double MAX_GAIN = 4.0;
    /**
     * 过零率阈值：低于它认为"不是人声"。
     * 低频稳态噪声（空调/风扇/电源嗡鸣）通常 &lt; 0.02，
     * 人声浊音 0.03~0.15、清音 0.2~0.4。
     * 取 0.06 是两者之间的安全缝，实测调参就改这一个数。
     */
    static final double ZCR_VOICE = 0.06;
    static final int FRAME_BYTES = OpusCodec.ENC_FRAME * 2;   // 1920 = 60ms@16k

    static volatile boolean sRunning = false;

    // ── 本地唤醒（KWS）──
    private Kws kws;
    private ToneGenerator tone;
    /** 训练态的共享信箱：训练由服务驱动（复用同一条录音流），界面只读这些字段画进度 */
    public static volatile boolean sTraining = false;
    public static volatile int sTrainGot = 0;
    public static volatile int sTrainNeed = 12;
    public static volatile String sTrainMsg = "";
    public static volatile boolean sTrainOk = false;
    public static volatile int sLevel = 0;
    public static volatile int sKwsTpl = 0;
    private final ArrayList<float[][]> trainSamples = new ArrayList<float[][]>();
    private final Object trainLock = new Object();

    private Prefs p;
    private volatile boolean running;
    private Thread worker;
    private AudioRecord rec;
    // ★ 2026-11-07 远场关键：硬件降噪 + 自动增益。
    //   这台机器的 /system/lib/soundfx 下有 libaudiopreprocessing.so 和 libldnhncr.so，
    //   audio_effects.conf 里 aec/ns/pre_processing 三个效果都注册了，
    //   只是没有为 mic 源预挂上。主流智能音箱能做到几米外拾音，靠的就是这一层：
    //   NS 把稳态噪声压下去，AGC 把远处的小声自动拉起来。
    //   纯靠 tinymix 调增益是"信号和噪声一起放大"，永远调不出信噪比。
    private NoiseSuppressor fxNs;
    private AutomaticGainControl fxAgc;
    private AcousticEchoCanceler fxAec;
    private MediaPlayer mp;
    private AudioTrack track;
    // 播放解耦：网络线程只把解码后的 PCM 丢进队列，由独立高优先级线程稳定喂给 AudioTrack。
    // 以前是在接收线程里同步 write，网络一抖动（TCP 分段不均）声音就一顿一顿的。
    private final java.util.ArrayDeque<byte[]> playQ = new java.util.ArrayDeque<byte[]>();
    private volatile Thread playThread;

    private volatile XzClient client;
    private volatile boolean playing = false;
    private volatile boolean inTurn = false;   // 正在录这一轮（期间看门狗不许插手）
    private volatile boolean recording = false; // 仅在"内层录音循环"里为 true（不含等回答）
    private volatile long turnStartAt = 0;      // 本轮录音开始时刻，给看门狗判卡死用
    private volatile boolean resetNoise = true;
    private volatile long quietUntil = 0;
    /** 本轮是不是本地唤醒开的头。是的话，云端识别不出"小智"也必须放行 */
    private volatile boolean localWoke = false;
    /** KWS 自己的静音期：比主循环的 quietUntil 长，专门躲 TTS 播完的声卡尾巴 */
    private volatile long kwsQuietUntil = 0;
    /** 本轮结束后开始空闲的时刻；0 表示还在用。连接靠它决定什么时候真正断开 */
    private volatile long idleSince = 0;

    // ── 「刚才那段不是叫我」闸（2026-11-11 16:30）──
    /** 上一轮录音的语音电平（收尾时写入，被拒时用来抬门槛） */
    private volatile int lastTurnLevel = 0;
    /** 动态抬高后的开口门槛；0 = 不生效 */
    private volatile int speechGate = 0;
    /** speechGate 最后一次被抬高的时刻，25 秒后自动失效 */
    private volatile long speechGateAt = 0;

    // 会话状态
    private volatile boolean inSession = false;
    private volatile long lastVoice = 0;
    /**
     * ★ 埋点：最后一次「听到人声」的时刻（过阈值的最后一帧）。
     * 用途：在指令执行路径里算「用户说完最后一个字 → 动作真的执行」的毫秒数，
     * 把整条链路拆成「收尾判定 + 云端识别 + 执行」三段，一眼看出瓶颈在哪。
     */
    private volatile long lastSpeechAt = 0;
    private volatile long turnT0 = 0;          // 本轮开口时刻，用于打印耗时
    // ★ 2026-11-07 19:25 编码性能埋点：这轮一共编了几帧、总共花了多少毫秒。
    //   算出来的「平均 X ms/帧」是判断瓶颈的唯一硬指标 ——
    //   60ms 一帧的音频，只要平均编码耗时 < 60ms 就追得上实时；
    //   ≥60ms 说明这颗 CPU 纯 Java 就不可能实时，只能换路子（本地命令词）。
    private volatile int encFrames = 0;
    private volatile long encMs = 0;
    private long sessionStart = 0;
    private long lastWarmTry = 0;
    private long lastReconnAt = 0;   // ★ 断开即补的最小间隔（防重连风暴）

    // 单轮状态（worker 与 reader 线程共享）
    private final Object lock = new Object();
    private volatile int gate = 0;               // 0 未定 / 1 放行 / -1 拒答
    private volatile long rejectAt = 0;
    private volatile boolean ttsStop = false;
    private volatile boolean stopSent = false;
    private volatile boolean ttsStarted = false;
    private volatile boolean cmdDone = false;
    /** 指令后的一段时间内，云端推下来的音频一律丢弃（防上一轮的尾巴串进下一轮） */
    private volatile long dropTtsUntil = 0;
    private volatile boolean keepSession = false;
    // ══════════════════════════════════════════════════════════════════════
    // ★★ 2026-11-07 19:40 畅聊模式（chatMode）★★
    //
    //   和 p.continuous 的区别，一句话：**畅聊是"这一次"，连续对话是"从今往后"**。
    //   p.continuous 是写进 SharedPreferences 的全局开关，一开就一直开：
    //   客厅里随便说句话它都可能接，而且退出只能再喊一次指令或进设置页。
    //   畅聊模式只活在内存里，说完「不聊了」立刻回落，明天照旧每条都喊名字。
    //
    //   进入方式：一句暗语（小智，陪我聊聊 / 聊会儿 / 畅聊……）
    //   保持方式：静默 CHAT_IDLE_SEC 秒才退（比日常的 25 秒长得多），
    //             且期间每一轮都复用同一条 WebSocket → 服务端上下文不断。
    //   退出方式：说「不聊了 / 拜拜 / 先这样 / 聊够了」或静默到点。
    //
    //   ⚠ 指令不受影响：指令判定在所有状态机之前，
    //     畅聊中直接说「停止播放」也照样生效（见 onStt 开头）。
    // ══════════════════════════════════════════════════════════════════════
    private volatile boolean chatMode = false;
    static final int CHAT_IDLE_SEC = 300;        // 畅聊静默上限 5 分钟
    static final int CHAT_MAX_SEC = 900;         // 畅聊硬上限 15 分钟（防状态卡死）
    private volatile long chatT0 = 0;            // 进入畅聊的时刻
    private final ArrayList<byte[]> pendingAudio = new ArrayList<byte[]>();
    private Pcm.Resampler resampler;
    private final ByteArrayOutputStream sendAcc = new ByteArrayOutputStream();

    // ══════════════════════════════════════════════════════════════════════
    // ★★ 2026-11-07 19:00 关键提速：音频编码/发送线程化 ★★
    //
    //   改这里之前实测的现象（日志可查）：
    //     开口 18:46:18.208 → 收尾 18:46:20.776 = 2568ms
    //     但 chunks 只有 10 个 —— 10 个 100ms 分片最多 1 秒音频，
    //     却整整花了 2.5 秒才录完。中间那 1.5 秒去哪了？
    //
    //   全花在 stream() 里同步做 Opus 编码 + WebSocket 发送上。
    //   这台 A83T 的 vulture.app.home 每秒刷 180 行录音错误日志抢 CPU，
    //   纯 Java 的 Opus 编码被拖到 76ms/帧（正常 20ms），
    //   一个 100ms 分片含 1.67 帧 → 每个分片要卡 120ms 以上。
    //
    //   后果不只是"慢"：录音循环被编码拖住，连"说完之后的静音"都要等
    //   编码做完才能判定，静音判据按分片计数，分片越慢判得越晚。
    //   用户感受就是"喊完愣一秒多它才动"。
    //
    //   现在：录音线程只管 read + to16kMono + 入队（微秒级），
    //   编码和发送全交给下面这条独立的高优先级线程。
    //   录音节拍重新被麦克风约束（100ms 一片），收尾判定准时。
    // ══════════════════════════════════════════════════════════════════════
    // ★ 2026-11-07 19:20：容量 60 → 10。
    //   60 片 = 6 秒积压，编码一旦跟不上，endTurn 就要白等这么久才敢 listenStop，
    //   延迟直接雪球。10 片 = 1 秒，最坏等待被锁死在这个量级。
    //   音频是实时流，旧的那几片对应的时刻早就过去了，丢掉比留着更有意义。
    private final java.util.concurrent.LinkedBlockingQueue<byte[]> sendQ =
            new java.util.concurrent.LinkedBlockingQueue<byte[]>(10);
    private volatile boolean senderAlive = false;
    private Thread senderThread;
    // 收尾握手：主线程置 finishPending，发送线程排空后补零并置 finishDone。
    // 目的只有一个 —— 保证 OpusEncoder 永远只被一根线程使用。
    private volatile boolean finishPending = false;
    private volatile boolean finishDone = false;

    private int aboveCnt = 0;
    // ★ 2026-11-07 20:50 双门限连续计数（必须严格连续，中断即清零）
    private int onsetHi = 0;      // 连续超过高门限的帧数
    private int onsetLo = 0;      // 连续超过低门限（远场）的帧数
    private int zeroRun = 0;
    private int idleTick = 0;
    /** 播报打断计数：连续几帧高电平就认为"有人在跟它抢话" */
    private int bargeCnt = 0;
    /** 播报中回声电平的采样计数（只为低频打日志用） */
    private int spkTick = 0;
    private volatile long lastFrameAt = 0;      // 最近一次成功读到音频的时间
    /**
     * ★★ 2026-11-08 17:30 主循环心跳 ★★
     *
     * 为什么必须单独有它：今天实测（17:12）小智活了 4 小时之后彻底聋了 ——
     *   /proc 里线程还在，xz-loop 卡在 AudioRecord.read() 的 obtainBuffer futex 上，
     *   一个字节都拿不到；日志里从 14:45 之后再没有一条「开口 / [空闲]」，
     *   只剩连接层每分钟一条「连接断开」。用户喊了一下午放歌，一次没成。
     *
     * 老的 xz-micwd 看门狗救不了它，原因写在这里（这是今天真踩到的洞）：
     *   `if (inTurn) { lastFrameAt = now; continue; }`
     *   主循环卡在 endTurn()（等云端回答那 18 秒循环）里时 inTurn 还是 true，
     *   看门狗每次都把 lastFrameAt 刷成"刚刚"，于是永远判不出卡死 ——
     *   **看门狗在主循环最需要它的时候主动失明了**。
     *
     * 现在改成看这个心跳：主循环每转一圈、endTurn 的每个等待步骤、
     * 录音内层循环每一帧都必须刷新它。只要它超过 6 秒没动，
     * 就说明主循环真卡住了，和 inTurn / recording 这些标志无关。
     */
    private volatile long loopBeat = 0;
    /** 自愈次数，用来在日志里留住"今天到底自愈过几次" */
    private volatile int healCount = 0;
    private long lastPeak = 0;          // 本轮开口的原始峰值（未加增益）
    private long sessionPeak = 0;       // 进入会话时那句话的峰值，作为"近场"基准

    /** 整帧是否全零（麦克风被别人独占时的典型表现） */
    private static boolean rmsCh0(byte[] buf, int len) {
        for (int i = 0; i + 1 < len; i += 2) {
            if (buf[i] != 0 || buf[i + 1] != 0) return false;
        }
        return true;
    }
    private int noiseEst = -1;
    private int hiRun = 0;          // 连续"明显高于底噪"的帧数，用于识别环境真的变吵

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── 常驻唤醒锁（11-09 曾加过；这份 11-08 代码里没有，重新补回）──
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    /**
     * 让 CPU 和 WiFi 都不进省电状态。
     *
     * 用户主诉："睡一夜起来要喊好几遍才答应""隔七八个小时就放歌不了了"。
     * 屏幕黑掉后系统让 CPU 深睡，阻塞在 AudioRecord.read() 上的采集线程被冻住 ——
     * 人喊的时候它根本没在采，等 CPU 被别的唤醒，这句话早过去了。
     * 这台机器是插电的智能屏，功耗不是约束，直接用锁把通路保死。
     */
    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager)
                        getSystemService(android.content.Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(
                            android.os.PowerManager.PARTIAL_WAKE_LOCK, "xiaozhi:mic");
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire();
                    L.i(TAG, "★ 已获取 PARTIAL_WAKE_LOCK（CPU 不深睡，采集线程常驻）");
                }
            }
        } catch (Throwable e) { L.w(TAG, "WakeLock 获取失败: " + e); }
        try {
            if (wifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(
                                android.content.Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(
                            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xiaozhi:wifi");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                    L.i(TAG, "★ 已获取 WifiLock(HIGH_PERF)（WiFi 空闲不省电）");
                }
            }
        } catch (Throwable e) { L.w(TAG, "WifiLock 获取失败: " + e); }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String act = intent == null ? null : intent.getAction();

        // 调试入口：am startservice -a com.lm.xiaozhi.TEXT --es text "你好"
        // 走文字通道把一整轮问答跑完（不经过麦克风），用来验证解码/播放/多轮
        if ("com.lm.xiaozhi.TEXT".equals(act)) {
            final String text = intent.getStringExtra("text");
            if (text != null && text.length() > 0) {
                new Thread(new Runnable() {
                    @Override public void run() { debugText(text); }
                }, "xz-debug").start();
                return START_STICKY;
            }
        }

        // 调试入口：模拟云端 ASR 回调，验证文本判定逻辑（完全不需要麦克风）
        //   am startservice -a com.lm.xiaozhi.STT --es text "小智|小智放歌"
        // 用 | 分隔 = 多次回调，专门复现"流式识别先说名字、再说命令"的真实时序，
        // 也就是"小智放歌"被判丢的那个 bug。有了它，判定逻辑可以离线跑，
        // 不用靠人站在机器前面喊，也不会被麦克风链路干扰。
        if ("com.lm.xiaozhi.STT".equals(act)) {
            final String text = intent.getStringExtra("text");
            if (text != null && text.length() > 0) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        p = Prefs.load(XzService.this);
                        String[] segs = text.split("\\|");
                        for (String seg : segs) {
                            synchronized (lock) {
                                gate = 0;
                                cmdDone = false;
                                rejectAt = 0;
                            }
                            L.i(TAG, "=== 注入回调: [" + seg + "]");
                            try { onStt(seg); } catch (Throwable e) { L.e(TAG, "注入异常", e); }
                            try { Thread.sleep(250); } catch (Throwable ignored) {}
                        }
                        L.i(TAG, "=== 注入结束 ===");
                    }
                }, "xz-stt-debug").start();
                return START_STICKY;
            }
        }

        // 唤醒词学习：界面发指令，麦克风始终由这里这一条长命流握着
        boolean wantTrain = "com.lm.xiaozhi.TRAIN_START".equals(act)
                || "com.lm.xiaozhi.TRAIN_RESTART".equals(act);

        if (worker != null && worker.isAlive() && running) {
            if ("com.lm.xiaozhi.TRAIN_STOP".equals(act)) stopTrain();
            else if (wantTrain) startTrain();
            else L.i(TAG, "服务已在运行");
            return START_STICKY;
        }

        L.init(getFilesDir());
        L.init(getFilesDir());
        p = Prefs.load(this);
        acquireLocks();      // ★ 补回：不让 CPU / WiFi 睡过去（隔夜失灵的根因）
        running = true;
        sRunning = true;
        startSender();      // 音频编码/发送线程，跟录音线程解耦
        initKws();
        if (wantTrain) startTrain();
        // ⚠ startForeground / setStreamVolume 在音频 HAL 忙的时候会阻塞十几秒，
        //   放在 onStartCommand 里会直接把服务拖成 ANR。全部挪到工作线程。
        worker = new Thread(new Runnable() {
            @Override public void run() {
                L.i(TAG, "工作线程启动");
                // 通知和音量都可能因为音频 HAL 忙而阻塞十几秒，各自丢到独立线程，别挡主循环
                new Thread(new Runnable() { @Override public void run() {
                    try { startForeground(1, buildNotification()); } catch (Throwable e) { L.w(TAG, "前台通知失败"); }
                }}, "xz-fg").start();
                new Thread(new Runnable() { @Override public void run() {
                    applyVolume();
                    L.i(TAG, "音量已设置 " + p.volume);
                }}, "xz-vol").start();
                L.i(TAG, "小智已启动：连续对话=" + p.continuous + " 静默结束=" + p.idleTimeoutSec
                        + "s 音量=" + p.volume + " 音色档=" + p.tone);
                loop();
            }
        }, "xz-loop");
        worker.start();
        startMicWatchdog();
        startLinkKeeper();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        sRunning = false;
        sTraining = false;
        // 先停发送线程，再关连接 —— 反过来的话它会在已关闭的连接上写，刷异常日志
        senderAlive = false;
        if (senderThread != null) {
            senderThread.interrupt();
            try { senderThread.join(600); } catch (Throwable ignored) {}
            senderThread = null;
        }
        closeClient();
        stopRec();
        stopPlay();
        if (kws != null) { try { kws.stop(); } catch (Throwable ignored) {} kws = null; }
        if (tone != null) { try { tone.release(); } catch (Throwable ignored) {} tone = null; }
        if (worker != null) { try { worker.join(1200); } catch (Throwable ignored) {} }
        super.onDestroy();
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, 0);
        Notification n = new Notification();
        n.icon = android.R.drawable.ic_btn_speak_now;
        n.tickerText = "小智在听";
        n.when = System.currentTimeMillis();
        n.flags |= Notification.FLAG_ONGOING_EVENT;
        n.setLatestEventInfo(this, "小智", "正在待命，喊“小智”就行", pi);
        return n;
    }

    // ───────────────────────── 本地唤醒 & 学习 ─────────────────────────

    private void initKws() {
        try {
            kws = new Kws(new java.io.File(getFilesDir(), "kws_tpl.dat"));
            kws.setEnabled(p.localWake);
            kws.start();
            sKwsTpl = kws.tplCount();
            if (kws.hasTpl()) {
                L.i(TAG, "本地唤醒就绪：模板 " + kws.tplCount() + " 条，阈值 "
                        + String.format(java.util.Locale.US, "%.3f", kws.threshold())
                        + "，开关 " + (p.localWake ? "开" : "关"));
            } else {
                L.i(TAG, "本地唤醒：还没有模板，去设置页点「学习我的声音」");
            }
        } catch (Throwable e) {
            L.e(TAG, "唤醒引擎起不来，退回云端唤醒", e);
            kws = null;
        }
    }

    /** 开始学唤醒词：把 KWS 切到训练态，服务这边的对话流程同时挂起 */
    private void startTrain() {
        if (kws == null) initKws();
        if (kws == null) {
            sTraining = false;
            sTrainOk = false;
            sTrainMsg = "唤醒引擎没起来，重启一下小智再试";
            return;
        }
        // 训练期间不该有播报抢麦克风，直接掐掉正在说的话
        try { playing = false; stopPlay(); } catch (Throwable ignored) {}
        if (inSession) endSession("开始学习唤醒词");

        synchronized (trainLock) { trainSamples.clear(); }
        sTrainGot = 0;
        sTrainNeed = 12;
        sTrainOk = false;
        sTrainMsg = "正在听……请对着机器清楚地说：小智";
        sTraining = true;

        kws.setTrain(true, new Kws.TrainCb() {
            @Override public void onSeg(float[][] feat, short[] pcm) {
                if (!sTraining) return;
                int got;
                synchronized (trainLock) {
                    trainSamples.add(feat);
                    got = trainSamples.size();
                }
                sTrainGot = got;
                if (got >= sTrainNeed) {
                    finishTrain();
                } else {
                    sTrainMsg = got < 4 ? "很好，继续（还差 " + (sTrainNeed - got) + " 遍）"
                            : "不错，保持一致的语速和距离";
                }
            }

            /**
             * 这一段不干净，不算一遍。
             *
             * 为什么必须让用户知道：以前脏样本会静默地算进去，用户以为"12 遍
             * 都录上了"，其实里面一半是噪声。结果模板类内距离被抬到 0.65，
             * 阈值被顶到 0.90，从此动不动就被电视声、聊天声叫醒。
             * 现在退回时会明确提示，用户照着重说一遍就行。
             */
            @Override public void onReject(int reason) {
                if (!sTraining) return;
                if (reason == 1) {
                    sTrainMsg = "这遍太长，不算。喊完“小智”停半秒，再喊下一遍";
                } else if (reason == 3) {
                    sTrainMsg = "两遍挨太近了，隔半秒再说下一遍";
                } else {
                    sTrainMsg = "这遍太轻没听清，不算。对着机器清楚地说：小智";
                }
            }
        });
        L.i(TAG, "开始学习唤醒词，需要 " + sTrainNeed + " 遍");
    }

    private void finishTrain() {
        ArrayList<float[][]> copy;
        synchronized (trainLock) { copy = new ArrayList<float[][]>(trainSamples); }
        java.io.File f = new java.io.File(getFilesDir(), "kws_tpl.dat");
        String msg = Kws.buildTemplates(f, copy);
        kws.setTrain(false, null);
        kws.reload();
        sKwsTpl = kws.tplCount();
        sTrainMsg = msg;
        sTrainOk = kws.hasTpl();
        sTraining = false;
        if (sTrainOk) {
            p.localWake = true;
            p.save(this);
            kws.setEnabled(true);
            beep();
        }
        L.i(TAG, "学习完成：" + msg);
    }

    private void stopTrain() {
        if (kws != null) kws.setTrain(false, null);
        sTraining = false;
        if (!kws.hasTpl()) sTrainMsg = "已取消，还没学到东西";
    }

    /** 命中提示音：短促一声，让用户立刻知道"它听见了" */
    private void beep() {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 40);
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 80);
        } catch (Throwable ignored) {}
    }

    /** 把 KWS 回传的原始样本转成小智云要的 16k 单声道字节流，并做一次温和的电平归一 */
    private static byte[] shortTo16k(short[] s) {
        if (s == null || s.length == 0) return new byte[0];
        long sum = 0;
        for (int i = 0; i < s.length; i++) sum += (long) s[i] * s[i];
        int rms = (int) Math.sqrt((double) sum / s.length);
        // 目标电平 3000、最多放 3 倍：远场小声能拉起来，近场本来就够的不会削波
        double g = rms > 0 ? Math.min(3.0, Math.max(1.0, 3000.0 / rms)) : 1.0;
        byte[] out = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            int v = (int) Math.round(s[i] * g);
            if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
            out[i * 2] = (byte) (v & 0xff);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return out;
    }

    // ───────────────────────── 主循环 ─────────────────────────

    /**
     * 主循环兜底壳。
     *
     * ⚠ 安卓 4.4 上工作线程里任何未捕获异常都会走 AndroidRuntime 的 FATAL EXCEPTION，
     *   系统直接弹「很抱歉，小智已停止运行」。之前看门狗 stopRec() 把 rec 置 null，
     *   主循环还在 rec.read() → NullPointerException → 无限弹窗。
     *   这里兜住整轮循环，出任何错只记日志 + 重开录音 + 2 秒后继续。
     */
    private void loop() {
        while (running) {
            try {
                loopImpl();
            } catch (Throwable e) {
                L.e(TAG, "主循环异常，2 秒后自愈: " + android.util.Log.getStackTraceString(e));
                stopRec();
                // ★ 这 2 秒也得打心跳，否则看门狗会把"正常退避"误判成卡死
                long until = System.currentTimeMillis() + 2000;
                while (running && System.currentTimeMillis() < until) {
                    loopBeat = System.currentTimeMillis();
                    sleep(200);
                }
            }
        }
    }

    private void loopImpl() {
        byte[][] ring = new byte[PREROLL][];
        int ringPos = 0, ringCnt = 0;

        warmUp();                       // 预热连接：喊的时候不用再等握手

        while (running) {
            // ★ 心跳：主循环活着 = 小智能听见。看门狗只认这一个信号。
            loopBeat = System.currentTimeMillis();
            // ★ 2026-11-07：设置页一动开关，这里立刻生效（每帧一次 volatile 读，几乎零成本）。
            //   关掉时如果正在会话里，马上退会话，不再拖到静默超时。
            Boolean pend = Prefs.sContPending;
            if (pend != null) {
                Prefs.sContPending = null;
                boolean want = pend;
                p.continuous = want;
                if (!want && inSession) endSession("设置页关闭连续对话");
                if (!want) keepSession = false;
                L.i(TAG, "设置页切换连续对话 → " + (want ? "开" : "关"));
            }

            // ★ 2026-11-08：失败退避 3 秒。
            //   以前是 sleep(500)，一旦被抢占就每 500ms new/start/release 一次，
            //   等于拿电钻怼音频 HAL，越怼越抢不回来。
            if (rec == null && !openRec()) { sleep(3000); continue; }

            byte[] buf = new byte[CHUNK];
            int read = 0;
            int badRead = 0;
            while (read < CHUNK && running) {
                AudioRecord r0 = rec;
                if (r0 == null) { read = -1; break; }
                int n = r0.read(buf, read, CHUNK - read);
                if (n <= 0) {
                    // ★ 2026-11-08：连续读不到就别耗着。200ms 内拆掉重开，
                    //   比等看门狗 2.5 秒快一个数量级。
                    if (++badRead > 20) {
                        L.w(TAG, "录音连续 " + badRead + " 次读不到数据，拆掉重开");
                        stopRec();
                        read = -1;
                        break;
                    }
                    sleep(10);
                    continue;
                }
                badRead = 0;
                read += n;
            }
            if (read < CHUNK) continue;
            lastFrameAt = System.currentTimeMillis();

            long now = System.currentTimeMillis();
            if (rmsCh0(buf, read)) {
                if (zeroRun++ % 100 == 0) L.w(TAG, "麦克风一直读到静音，可能被别的应用占用");
            } else zeroRun = 0;
            int rmsL = Pcm.rmsCh(buf, read, 0);
            int rmsR = Pcm.rmsCh(buf, read, 1);
            int rms = Math.max(rmsL, rmsR);

            // ★★ 2026-11-08 17:55：先把这一帧存进预录环，再判播报 ★★
            //   原来"播报中就 continue"在进环之前，于是播报期间 ring 被清空。
            //   可是用户最常干的事就是"它正说着，我直接喊小智放歌"——
            //   打断的那一帧应该在 ring 里带着前面 0.8 秒，才不至于只剩半句。
            ring[ringPos] = buf;
            ringPos = (ringPos + 1) % PREROLL;
            if (ringCnt < PREROLL) ringCnt++;

            // 播报中：麦克风直录自己的声音，整帧丢弃且不动底噪估计
            if (playing || now < quietUntil) {
                // ★ 顺带把 KWS 也压住。注意这里不是"暂停"，是让它丢掉队列里
                //   播报期间攒下的帧并重置段状态 —— 只靠"不喂"是不够的：
                //   队列里那十几帧会在播报结束后才被分析，而 TTS 的音色和
                //   训练模板同源（实测距离 0.23，阈值 0.594），稳稳命中，
                //   于是又开一轮、又播 TTS，自己和自己的播报聊起来。
                if (kws != null) kws.suppress = true;
                onsetHi = 0;
                onsetLo = 0;

                // ★★ 播报中继续听：只为了"被人打断"，不做识别 ★★
                //   用户诉求（2026-11-08）：「小智放歌、小智停止播放无论什么时候用都要完美」。
                //   而它每答一句话就要播 3~5 秒，这 3~5 秒里麦克风是关着的 ——
                //   家里人正好在这时候喊放歌，就是"喊了没反应"，而且看着像随机失灵。
                //   这里的判据刻意保守：
                //     · 电平要高于 BARGE_RMS（远场喊话 2000+，TTS 回声经 AEC 后远低于它）
                //     · 还要连续 BARGE_FRAMES 帧（500ms），关门声、碰桌子都混不进来
                //   打断后立刻掐掉云端回答和播放队列，并用 ring 里已经录下的
                //   0.8 秒音频重新走一遍开口流程 —— 用户那句「小智放歌」一个字都不丢。
                // ★★★ 2026-11-08 18:00：打断播报已关闭（BARGE_ON=false）★★★
                //   实测证据：播报期间麦克风读到的 rms 稳定在 23400~23470（约 -3dBFS，
                //   已经削顶）。把媒体音量静音，同样的播报只剩 300~400 —— 说明
                //   这 23400 就是**自家喇叭被麦克风抄了回去**（本机 DMIC 数字增益 190，
                //   麦离喇叭近，属于纯声学正反馈，不是软件回环，mixer 里没有 DAC→ADC 通路）。
                //   后果是灾难性的：每播一句 → 回声 > BARGE_RMS(1600) 且连 5 帧 →
                //   判定"有人打断" → 立刻开新一轮 → 云端回一句 → 再播 → 再被打断。
                //   实测 17:44~17:46 就是这样每 4 秒一轮，永远在录音、永远听不见人话，
                //   用户喊"小智放歌"自然一点反应都没有。
                //   而且这里**没法用能量区分**：回家后真人在 3 米外喊话才 600~2000，
                //   自家回声 23400 —— 回声比人声高一个数量级，任何门限都会先撞上回声。
                //   要支持"播报中被真人打断"，必须做回声消除（AEC），本机 HAL 没有。
                //   所以改为老老实实的半双工：播报时麦克风整帧丢弃（下面 else 分支），
                //   播完立刻恢复。单个回答只有 1~2 秒，代价可以接受；
                //   换来的是"绝大多数时候（待命时）喊一句就放歌"。
                if (playing && BARGE_ON) {
                    if (rms > BARGE_RMS) {
                        if (++bargeCnt >= BARGE_FRAMES) {
                            bargeCnt = 0;
                            L.i(TAG, "★★ 播报中有人说话 rms=" + rms + " → 打断播报，优先听人");
                            discardPending();
                            quietUntil = 0;
                            kwsQuietUntil = 0;
                            XzClient cb = client;
                            if (cb != null) cb.abort();
                            // 不 continue：让这一帧接着走下面的开口判定
                        } else {
                            continue;
                        }
                    } else {
                        bargeCnt = 0;
                        // 低频记录回声电平，万一以后要调 BARGE_RMS 有据可查
                        if (++spkTick >= 40) {
                            spkTick = 0;
                            L.i(TAG, "[播报中] rms=" + rms + " 底噪=" + noiseEst);
                        }
                        continue;
                    }
                } else {
                    continue;
                }
            }
            bargeCnt = 0;
            // 播报结束后的余音期同样压住（声卡缓冲还有几百毫秒在响）
            if (kws != null) kws.suppress = (now < kwsQuietUntil);

            // ★★ 2026-11-07 本地唤醒：每帧给 KWS 一份 16k 单声道拷贝 ★★
            //   接口只做一次数组拷贝 + 入有界队列就返回（满了直接丢），
            //   全部分析在独立最低优先级线程里做。这里**绝不允许**阻塞：
            //   主循环卡 50ms，ALSA 缓冲就溢出，之后一直读到全零，
            //   得重启进程才能恢复 —— 这个坑踩过一次，代价是一晚上的调试。
            int chFeed = (rmsR > rmsL) ? 1 : 0;
            if (kws != null && kws.ready() && !inTurn) {
                byte[] m16 = Pcm.to16kMono(buf, read, chFeed, 1.0);
                kws.feed(m16, m16.length);
            }
            if (sTraining) {
                // 学习唤醒词期间：只喂数据，不判开口、不连云端。
                // 电平回传给界面画条，让用户知道话筒是活的。
                sLevel = rms;
                aboveCnt = 0;
                ringCnt = 0;
                continue;
            }

            // ★ 2026-11-07：重置时不再死写 100。
            //   原来放宽到"最多 100"，可房间稳态噪声是 400，
            //   于是每一轮开头阈值只有 200，噪声立刻误触发开口；
            //   开口结束又把底噪打回 100 —— 死循环，永远学不会环境。
            //   改成"跟随当前帧，但封顶 600"，防止刚好有人在说话时把底噪带上天。
            if (resetNoise) {
                resetNoise = false;
                // ★★ 2026-11-07 13:25 ★★ 封顶 600 → 420
                //   实测重启后一上来就报「底噪=600 阈值=900」，
                //   原因是启动那一帧刚好撞上噪声（vulture 刷屏、开机风扇），
                //   单帧采样直接把底噪钉在上限，之后阈值一直 900，
                //   远场说话 800 就再也唤不醒。
                //   本机真实底噪 320~400，封顶 420 足够，偏了由 hiRun 机制兜回来。
                noiseEst = Math.max(20, Math.min(rms, 420));
                hiRun = 0;
            }
            if (noiseEst < 0) noiseEst = Math.max(20, rms);
            // ★★ 2026-11-07 13:20 最重要的一个修复：底噪估计只喂"非语音帧" ★★
            //   原来这三行是无条件按 rms 更新，说话的能量也算进去 ——
            //   实测证据：静默时底噪稳定在 320，但只要说一句"小智小智放歌"，
            //   下一轮就变成 750/阈值 1125，再下一句就更难触发。
            //   这就是用户说的"第一句挺快，后面越来越慢、越来越听不清"。
            //   一个 22586 的语音帧，0.003 的权重就能把底噪抬高 67，
            //   连说 6 句（20 帧）直接顶到 800 上限，之后永远 1125 阈值。
            //   正确做法（噪声估计的通行规则）：只有当帧电平接近底噪时才更新它。
            else if (rms < noiseEst) {
                noiseEst = (int) (noiseEst * 0.80 + rms * 0.20);   // 变安静 → 快速跟随
                hiRun = 0;
            } else if (rms < noiseEst * 1.6) {
                noiseEst = (int) (noiseEst * 0.997 + rms * 0.003); // 轻微升高 → 极慢跟随
                hiRun = 0;
            } else {
                // 明显高于底噪 = 有人在说话 / 正在播报的回声 → 一律不动底噪估计。
                // 但留一个逃生口：如果连续 12 秒都这么高，那说明是环境真的变吵了
                // （开了空调、窗外施工），这时认下来，否则阈值会永远卡在旧环境的低位。
                if (++hiRun > 120) { noiseEst = (int) (rms * 0.85); hiRun = 0; }
            }
            if (noiseEst > NOISE_CAP) noiseEst = NOISE_CAP;

            // ★★ 2026-11-08 18:10：有唤醒词模板时，能量门限必须抬高 ★★
            //   为什么：能量门限分不清"人在喊它"和"人在聊天"。
            //   今天的日志就是证据 —— 底噪 350，门槛 700/490，家人正常说话 800~1500，
            //   于是每 20~30 秒就误开一轮，录 1 秒送云端，这一秒里麦克风是死的；
            //   而云端还会回一句"哦，你是说大点的那个？"（播报 3~5 秒），
            //   用户正好这时候喊"小智放歌" → 完全没反应。这就是"时灵时不灵"。
            //   有了 KWS 模板之后，"是不是在喊小智"由音色决定，不再靠能量猜，
            //   所以能量门限可以放心抬高一大截，只用来兜住"近场大声"这条快路径。
            //   没有模板时（今天现况）维持原样，否则连人声都进不来。
            boolean hasTpl = (kws != null && kws.hasTpl());
            int[] sp = Prefs.sensParam(p.sens);
            double mult = sp[1] / 10.0;
            int thr = (int) Math.max(noiseEst * (hasTpl ? mult + 0.6 : mult), sp[0]);

            // 会话静默超时：悄悄结束，不打扰
            // ★ 畅聊模式用 5 分钟，日常连续对话仍用 25 秒 ——
            //   聊天时人会想事情、会停顿，25 秒就谢幕太急了。
            int idleTo = chatMode ? CHAT_IDLE_SEC : p.idleTimeoutSec;
            if (inSession && now - lastVoice > idleTo * 1000L) {
                endSession("静默 " + idleTo + " 秒");
                ringCnt = 0;
                continue;
            }

            // 待命时顺手把连接养着（断了就补，但别刷屏）
            if (!inSession && (client == null || !client.isOpen()) && now - lastWarmTry > 4000) {
                // ★ 2026-11-07 20:58 20000 → 4000：断开后的空窗从 20 秒压到 4 秒。
                //   真正触发补连的第一枪是 onClosed（3 秒内），这里只是兜底，
                //   防止"连接假死但没有 close 事件"的情况（NAT 静默回收）。
                lastWarmTry = now;
                new Thread(new Runnable() { @Override public void run() { ensureClient(); } }, "xz-warm").start();
            }

            // （进环已经提到播报判定之前，这里不再重复）

            // ★★ 2026-11-07 本地唤醒命中：不看能量，直接开口 ★★
            //   这是"几米外也能叫醒"的根本原因。原来的开口依据是 rms > 阈值，
            //   而阈值必须高于房间噪声（噪声 400~990 之间乱跳），一提就压掉了远场人声；
            //   本地唤醒的依据是"听清了小智这两个字的音色形状"，
            //   远场小声 800 能中，近场关门声 5000 不会中。
            Kws.Hit kw = (kws != null) ? kws.takeHit() : null;

            // ★★★ 2026-11-11 20:16 音色命中必须配得上说话的音量 ★★★
            //   实测（30 秒静默统计）：本地识别在**纯噪声**上命中 8 次，
            //   那 8 次的 rms 分别是 380/452/464/387…，全部等于房间底噪本身。
            //   也就是说它是拿"底噪的形状"去对"小智"的模板，居然能过 0.86 的线
            //   （这台机器的模板本身不纯，见离线分析：真人 0.765~0.881、噪声 0.778~1.055）。
            //   后果就是：屋里安静着，它每 3~4 秒自己开一轮、把噪声传上云端，
            //   既浪费连接，又会在你开口的瞬间抢占麦克风 —— 这就是"乱套"的体感来源之一。
            //
            //   修法：音色命中 + 音量门槛，两条同时满足才算数。
            //   真人即使两三米外，开口音量也有 700 以上（实测七八百到两千）；
            //   而房间底噪只在 300~500 徘徊，差得很开，不用靠猜。
            //   被这条挡掉的命中不发声、不上传，等于什么都没发生。
            if (kw != null) {
                int kwFloor = (int) Math.max(700, noiseEst * 2.0);
                if (rms < kwFloor) {
                    L.i(TAG, "本地命中被音量否决：rms=" + rms + " < " + kwFloor
                            + "（dist=" + String.format(java.util.Locale.US, "%.3f", kw.dist) + "，当噪声丢弃）");
                    kw = null;
                }
            }

            // ★★ 2026-11-07 20:50 双门限 + 严格连续（原来的 aboveCnt 其实是"累计"）★★
            //   旧写法 `if (rms > thr) { aboveCnt++; if (aboveCnt < ONSET) continue; }`
            //   有两个毛病：
            //     ① 不达标时**不清零**，噪声每 100ms 攒 1 帧，300ms 内攒够 3 帧就开口，
            //        所以"连续 3 帧"从来没真正生效过；
            //     ② 只有一档门限，必须高于噪声，远场小声永远差一点点。
            //   现在拆成两条通道，任一条连续达标就开口：
            //     高门限 thr    → 连续 ONSET(3) 帧   = 近场/正常音量，300ms 响应
            //     低门限 thrLo  → 连续 ONSET_LO(6) 帧 = 远场小声，600ms 响应
            // ★ 有模板时低门限也抬高 + 要求更长的持续（8 帧 = 800ms），
            //   把"家人聊天"和"喊小智放歌"彻底分开：
            //   聊天是一串长短不一的音节，攒不出连续 800ms 稳定高电平；
            //   而喊它的时候一定是连续、对着机器、音量稳定的。
            // ★★ 2026-11-11 16:18 低门限 2.05x → 1.5x，连续帧 8 → 6 ★★
            //   这就是"小声说话它不理我"的直接原因：
            //   底噪 310 时低门槛 676，而两三米外压低声音说话只有 500~700，
            //   上一版还要连续 8 帧（800ms）都在线以上 —— 两条件同时满足几乎不可能。
            //   改成 1.5x（≈495）+ 连续 6 帧（600ms）：
            //   正常音量、远场、小声都能开；而房间噪声的抖动帧
            //   （实测 367~584，均值 450 左右）攒不出连续 600ms，仍然挡得住。
            //   万一真被噪声开了一轮，灯也不会动 —— 指令必须文本里有「小智」。
            // ★★ 2026-11-11 16:30 低门限回到 2.05x / 连续 8 帧 ★★
            //   刚才试过 1.5x + 6 帧，实测是灾难：这个房间现在电视音量下
            //   每 2.5 秒就开一轮，20 秒开了 8 轮，全是电视文本，云端还以为在连续聊天。
            //   结论（61 条真人 + 61 条真实噪声离线跑出来的）：**能量分不开电视和人**，
            //   唯一的出路是下面新加的"记住上一段不是叫我"（speechGate）。
            // ★★ 2026-11-11 20:10 远场重新放开：2.6x → 1.85x，连续 6 帧 ★★
            //   用户要求"两三米之外都能轻松听到"。原来有模板时低门槛被抬到
            //   noiseEst×(2.05+0.55)=2.6x，底噪 310 时就是 806 ——
            //   而两三米外压低声音说话只有 500~700，等于物理上够不着。
            //   现在 1.85x（≈574）+ 连续 6 帧（600ms）：
            //   远场小声进得来；房间噪声的抖动帧攒不出连续 600ms。
            //   就算被噪声开了一轮，也不会出任何声音、不会动灯 ——
            //   指令必须文本里有「小智」，被拒的一轮 TTS 全部作废。
            int thrLo = (int) Math.max(noiseEst * (hasTpl ? 1.85 : THR_LO_MULT), ABS_FLOOR);
            int onsetLoNeed = hasTpl ? ONSET_LO : (ONSET_LO + 2);

            // ★★★ 2026-11-11 16:30 「刚才那段不是叫我」闸 ★★★
            //   由来：屋里开着电视/刷着视频时，能量一直高于门槛，
            //   于是它每隔两秒就录一段送云端，录的却是电视 —— 用户喊"小智放歌"
            //   正好撞在这个"正在录电视"的窗口里，一个字都进不去。
            //   用户感受就是"有背景声的时候叫不应"。
            //
            //   做法：任何一轮被判定"没喊唤醒词，不理这句话"时，记下那一轮的音量，
            //   之后 25 秒内，开口门槛抬到"比那个音量再高 15%"。
            //   因为人对着机器说话（1 米内 5000~20000）本来就比两三米外的电视
            //   （实测 2700~10000）响一截，这一抬正好把电视挡在外面，
            //   而用户的正常音量仍然进得来。
            //   一旦有一轮被真正接受（有人喊小智），立刻把这个闸清零 —— 恢复正常灵敏度。
            // ★★★ 2026-11-11 20:10 彻底删除「记住上一段不是叫我 → 抬门槛 25 秒」★★★
            //   这是今天最糟的一处设计，用户的原话「越喊越乱套」就是它造成的：
            //   只要有一轮被判定"没喊唤醒词"（包括用户自己喊了、但云端 ASR 认错），
            //   门槛就被抬到那一轮电平的 1.15 倍，持续 25 秒。
            //   于是用户第一句没被认出来 → 后面 25 秒怎么喊都听不见 →
            //   只能越喊越大声、越喊越急，而它已经彻底聋了。
            //   "被拒的一轮"本来就是常态（屋里有人说话都算），
            //   用常态事件去惩罚后续 25 秒，等于把机器关掉了。
            //   真正该做的是：宁可多上传几段噪声（无声、不动灯，代价只是流量），
            //   也绝不能在该听见人的时候把耳朵捂上。
            // ★ 两条通道**各自独立**计数，绝不互相清零。
            //   第一版写成"高档命中就把低档计数清 0"，是个致命 bug：
            //   远场说话的能量本来就在门槛上下浮动（实测 727/1068/624/910/536…），
            //   一帧过高档、下一帧只过低档，两条计数轮流归零，
            //   结果 10 帧喊完一帧都没攒够 —— 比改之前还聋。
            //   因为 thrLo < thr，过高档时两条都会涨，这才是正确的包含关系。
            if (rms > thr) onsetHi++; else onsetHi = 0;
            if (rms > thrLo) onsetLo++; else onsetLo = 0;
            boolean opened = kw != null || onsetHi >= ONSET || onsetLo >= onsetLoNeed;
            if (kw == null && !opened) {
                // 只更新"最后一次听到人声"的埋点，不开口
                if (rms > thrLo) lastSpeechAt = now;
                aboveCnt = 0;
                if (++idleTick >= 100) {
                    idleTick = 0;
                    L.i(TAG, "[空闲] rms=" + rms + " 底噪=" + noiseEst
                            + " 高门槛=" + thr + " 低门槛=" + thrLo
                            + " 连续=" + onsetHi + "/" + onsetLo
                            + (inSession ? " [会话中]" : ""));
                }
                continue;
            }

            {
                lastSpeechAt = now;          // ★ 埋点：待命态下最后一次听到人声

                // ── 开口 ──
                aboveCnt = 0;
                onsetHi = 0;
                onsetLo = 0;
                int chan = (rmsR > rmsL) ? 1 : 0;
                long peak = 0;
                for (int i = 0; i < ringCnt; i++) {
                    int idx = (ringPos + PREROLL - ringCnt + i) % PREROLL;
                    if (ring[idx] != null) peak = Math.max(peak, Pcm.peakAbs(ring[idx], CHUNK, chan));
                }
                peak = Math.max(peak, Pcm.peakAbs(buf, read, chan));
                lastPeak = peak;

                // 会话内做"近场"过滤：电视/音箱这种远处的声源峰值明显低于本人说话。
                // ★ 2026-11-07 修 bug：原来用 sessionPeak×0.35，而 sessionPeak 存的是
                //   peakAbs 峰值。这台机器开口头一帧常常削波到 32768 →
                //   near 被抬到 11468，之后正常说话峰值只有 5000~9000，
                //   全被当成"远处声源"丢掉 —— 这就是"开了连续对话却从不接话"的真凶。
                //   改成宽松档：只有明显小得多的声音（远处电视）才挡，
                //   削波基准直接不作数。
                // ★ 本地唤醒命中时**跳过**这个过滤：KWS 已经从音色上确认是人在喊它，
                //   再拿电平去驳回等于白算一遍。
                if (kw == null && inSession && sessionPeak > 0 && sessionPeak < 20000) {
                    long near = Math.max(1000, (long) (sessionPeak * 0.12));
                    if (peak < near) {
                        aboveCnt = 0;
                        ringCnt = 0;
                        continue;
                    }
                }

                // ★★ 2026-11-07 13:20 ★★ 目标电平 3277 → 2000，倍数上限 12 → 4
                //   3277 是"按满量程 1/10 定的"，听着合理，实际很危险：
                //   开口那一帧若是小声（500），gain=6.5，紧接着正常说话 3000
                //   会被放大到 19500 —— 逼近削波；若开口帧 400，gain=8.2，
                //   说话 3000 直接变 24600，再加上环境波动就整段削平。
                //   ASR 对削波极其敏感（方波丢失全部共振峰），表现出来就是
                //   "我说得很清楚，它却总是听错"。目标降到 2000 + 上限 4 倍后，
                //   最坏情况 400×4=1600 起，正常说话也只会到 12000，不会削。
                //
                // ★ 本地唤醒命中时用固定 1.6 倍：那一帧的 rms 是"小智"说完之后的
                //   静音电平（可能只有 200），按 2000/rms 算会直接顶到上限 4 倍，
                //   把后面"放歌"两个字连噪声一起放大成一片糊。
                int rmsAll = Math.max(1, rms);
                // ★★ 2026-11-07 20:50 远场单独给增益 ★★
                //   走低门限通道开口 = 说明是"两三米外的小声"。
                //   原来统一按 2000/rms 算：rms=800 → gain 2.5 → 送到云端只有 2000 电平，
                //   和房间噪声的差距太小，ASR 认不出"小智"两个字，于是虽然开了口，
                //   云端却回一句"今天天气"之类的乱文本，用户感受还是"没理我"。
                //   远场单独抬到 3400（上限 3.2 倍）：800×3.2≈2560，接近正常说话电平，
                //   又不会像 4 倍那样把峰值推到削波（削波会丢共振峰，ASR 反而更差）。
                // ★★ 2026-11-11 20:10 远场增益 3400/3.2 → 3800/3.6 ★★
                //   低门槛放开后，两三米外的小声（实测 500~800）能开口了，
                //   但送上去只有 800 电平，云端仍然认不出"小智"两个字。
                //   3.6 倍 → 2880，落在正常说话的电平区间；
                //   且 800×3.6 离削波（32767）还有十倍余量，不会丢共振峰。
                boolean farField = (rms <= thr * 1.3);
                double target = farField ? 4200.0 : 2800.0;
                double capG = farField ? 3.6 : MAX_GAIN;
                // ★★ 2026-11-11 20:14 本地命中不再固定 1.6 倍 ★★
                //   那条 1.6 是给"KWS 切片"用的 —— 切片取自"小智"说完后的静音电平
                //   （只有 200~400），按 2000/rms 会顶到上限把噪声一起放大。
                //   现在音频改成走完整预录环，电平就是真实电平，
                //   再固定 1.6 倍反而把远场小声压死在 557（实测 20:08:48 就是 348×1.6）。
                //   统一按 target/rms 算，远近场用同一套规则。
                final double gain = Math.min(capG, Math.max(1.0, target / rmsAll));

                beginTurn();
                XzClient c = client;
                if (kw != null) {
                    // 本地唤醒：KWS 已经把"小智"这两个字（含前 30ms）单独切好回传，
                    // 当作本轮录音的开头直接送云端。这样云端 ASR 收到的仍然是
                    // 完整的"小智放歌"，下游的 isWake / stripWake 一个字都不用改，
                    // 而且从命中的那一刻就开始录，不丢"开"的字头。
                    //
                    // ★ 同时置 localWoke：本地认词成功了，云端就不再有资格用
                    //   "没听见唤醒词"把这一轮丢掉。KWS 切出来的那一段有时只有
                    //   260ms（只说"智"没说"小"），云端 ASR 可能只吐出一个"我"，
                    //   靠文本判词必然误杀 —— 而本地已经用音色确认过了。
                    // ★★★ 2026-11-11 20:10 删掉这声「嘀」，这是"滴滴滴"的根 ★★★
                    //   原来每次本地命中都 beep()（ToneGenerator TONE_PROP_ACK 80ms）。
                    //   本地音色匹配在噪声环境里误命中率很高（几分钟一次、有时连着几次），
                    //   用户听到的就是"不时的滴滴滴、不知道它在干嘛"。
                    //   指令成功时本来就有「好啊，开了」的人声确认，这声嘀纯属多余，
                    //   而且和"它到底听没听懂"混淆 —— 去掉，一声不响。
                    //
                    //   同时把 localWoke 收严：只有距离足够近（真的像「小智」）才赋予
                    //   "云端没听出小智也放行"的权力。0.60 是根据 61 条真人样本
                    //   （0.42~0.55）留出的余量；0.60 以上的命中一律只用来加速录音，
                    //   不再让噪声借着误命中混进云端当成命令。
                    localWoke = (kw.dist <= 0.60f);
                    // ★★★ 2026-11-11 20:12 命中也走完整预录环，不再送 KWS 切片 ★★★
                    //   实测（20:07:30）本地命中的那一轮，云端只识别出一个"小。"。
                    //   原因：KWS 回传的 kw.pcm 是它按 DTW 对齐切出来的片段，
                    //   电平只有 926（同一句话走 VAD 路径是 5000~22000），
                    //   而且切点常常把"智"字尾巴截掉。
                    //   现在改成：本地命中只负责"立刻开口"（省掉 VAD 的 6 帧等待），
                    //   音频仍然用主循环一直在填的 0.8 秒预录环 —— 那是未经切片、
                    //   电平完整、增益统一的原始音频。快和准两头都拿到。
                    L.i(TAG, "★ 本地唤醒开口 dist=" + String.format(java.util.Locale.US, "%.3f", kw.dist)
                            + "（音频走完整预录 " + ringCnt + " 帧）");
                }
                for (int i = 0; i < ringCnt; i++) {
                    int idx = (ringPos + PREROLL - ringCnt + i) % PREROLL;
                    if (ring[idx] != null) stream(c, Pcm.to16kMono(ring[idx], CHUNK, chan, gain));
                }
                ringCnt = 0;
                lastVoice = now;
                {
                    // ★ 2026-11-07 20:55 带上连接健康度：连续几次出现"静默 > 60s"，
                    //   就说明是连接假死（NAT 回收）在吃掉远场那几句，而不是灵敏度不够。
                    // ★ 2026-11-11 20:12 去掉 `if (kw == null)`：本地命中的那一轮
                    //   也要打出电平和增益 —— 不然调远场灵敏度时完全没有数据。
                    long rxAge = (c != null && c.lastRxAt > 0)
                            ? (System.currentTimeMillis() - c.lastRxAt) / 1000 : -1;
                    L.i(TAG, "开口 rms=" + rms + " 底噪=" + noiseEst + " 高门槛=" + thr
                            + " 低门槛=" + thrLo + (farField ? " [远场低档]" : " [高档]")
                            + " 声道=" + (chan == 0 ? "L" : "R") + " 增益=" + String.format("%.1f", gain)
                            + (kw != null ? " [本地命中]" : "")
                            + " 连接=" + (c == null ? "无" : (c.isOpen() ? "ok" : "断"))
                            + (rxAge >= 0 ? ("/静默" + rxAge + "s") : "")
                            + (inSession ? " [会话中]" : ""));
                }

                // ── 录到静音 ──
                inTurn = true;
                recording = true;
                turnStartAt = System.currentTimeMillis();
                // ★ 2026-11-07 收尾判据改为"自适应"：
                //   原来固定跟待命阈值 thr 比。可 thr 是按环境底噪算的，
                //   说话时的余音/房间噪声常常刚好压在 thr 上方，
                //   于是"静音"永远判不出来，每句话都录满 MAX_CHUNKS(4 秒)。
                //   用户感受就是"喊完要愣两秒多它才动"。
                //   现在改成：直接用环境底噪的 2 倍做静音线。
                //   2026-11-07 12:50 再修正：原来用 lastPeak*0.15，增益拉满后峰值 25889
                //   → 静音线被抬到 3883，房间本底 516 永远判不出来，还是录满 4 秒。
                //   VAD 的静音线本来就不该跟说话峰值挂钩（同一句话末尾音量必然衰减，
                //   但房间噪声与峰值无关）。改成 noiseEst*2 后，说话时 25889 远大于它，
                //   说完落回 500 立刻判定，不再受峰值拖累。
                // ★ 2026-11-07 13:00 最终版：跟随"本句峰值"的收尾判据
                //   实测这台设备的麦克风很奇怪：
                //     待命时 rms≈11，一旦开始录音，连"静音"段都恒定在 12000~16000，
                //     而说话峰值 28000。信噪比只有 2:1，任何固定阈值（底噪×2）
                //     都判不出静音 → 每句必录满 MAX_CHUNKS，用户感受就是"喊完愣四秒"。
                //   对策：静音线 = 本句峰值 × 0.6（每帧动态更新），并保留底噪下限。
                //   实测 rm 序列尾部 12512,14131,14716,15736,15796,16013 连续低于
                //   28345×0.6=17007，第 3 帧即收尾，录音从 2.4s 降到约 1.2s。
                long voiceRef = 1;             // 本句"语音电平"参考值（不是峰值，见下方说明）
                int silRun = 0, chunks = 0;
                StringBuilder trace = new StringBuilder();
                while (running && !playing) {
                    byte[] b2 = new byte[CHUNK];
                    int r2 = 0;
                    while (r2 < CHUNK && running) {
                        AudioRecord r1 = rec;
                        if (r1 == null) break;
                        int n = r1.read(b2, r2, CHUNK - r2);
                        if (n <= 0) { sleep(10); continue; }
                        r2 += n;
                    }
                    if (r2 < CHUNK) { if (rec == null) break; continue; }
                    int rm = Math.max(Pcm.rmsCh(b2, r2, 0), Pcm.rmsCh(b2, r2, 1));
                    loopBeat = System.currentTimeMillis();   // ★ 录音内层也要打心跳
                    stream(c, Pcm.to16kMono(b2, r2, chan, gain));
                    chunks++;
                    // ★ 埋点：录音过程中持续刷新"最后一次听到人声"的时刻。
                    //   收尾句尾会衰减，用语音电平的一半当界更准（不会把气声当人声）。
                    if (rm > voiceRef * 0.5) lastSpeechAt = System.currentTimeMillis();
                    // ★★ 2026-11-07 13:20 收尾判据第 4 版（前 3 版全错，原因记在这里）★★
                    //   第 1 版 silThr = 待命阈值 thr        → 余音压在 thr 上方，永不收尾，录满 4s
                    //   第 2 版 silThr = lastPeak × 0.15     → 增益拉满后峰值 25889，线被抬到 3883
                    //   第 3 版 silThr = localPeak × 0.6     → 削波帧把 localPeak 顶到 32768，同上
                    //   三版的共同错误：拿"最大值"当语音电平。一个爆音就毁掉整句。
                    //
                    //   第 4 版：voiceRef 用"限速上升"逼近语音电平 ——
                    //   每帧最多涨 (底噪×2)，所以单个削波帧只能把它推高一小步，
                    //   连续说话 3~10 帧后自然收敛到真实电平。
                    //   收尾线 = voiceRef × 0.55，并用"底噪 × 1.35"当下限（防止把句尾吃掉）。
                    if (rm > voiceRef) {
                        long step = Math.max(300, noiseEst * 2);
                        voiceRef = Math.min(rm, voiceRef + step);
                    }
                    long silThr = (long) (voiceRef * 0.55);
                    // ★★ 2026-11-07 13:35 ★★ floor 1.35 → 2.0（回退）
                    //   1.35 版实测灾难：房间噪声在 400~800 之间波动，
                    //   而 floor 只有 463，噪声自己就一直在"说话"，
                    //   结果录满 51 帧（5.1 秒），识别出
                    //   "我们看一下能边使用PCD天线和IPSS天线接设计小智开" 这种纯噪声文本。
                    //   结论：这台设备的噪声电平（400~800）和弱语音（"灯"字约 700~800）
                    //   完全重叠，**任何纯能量判据都无解**。
                    //   先用 2.0 保证"不录满"，真正的解法在下面的过零率判据。
                    long floor = Math.max((long) (noiseEst * 2.8), 400);
                    if (silThr < floor) silThr = floor;
                    // ★ 10 → 6：实测语音尾段在 3500~3900 徘徊时，cap 若给到 3830
                    //   收尾线就跟着卡在那儿，一句话要多拖 700ms 才收。
                    //   压到 6 倍（底噪 383 → 2298）后，尾音一落就判得出来。
                    long cap = Math.max((long) (noiseEst * 6.0), 3000);
                    if (silThr > cap) silThr = cap;

                    // ★★ 2026-11-07 13:40 过零率判据实测失败，已弃用（留着数据备查）★★
                    //   实测 rm/zc 配对：
                    //     噪声段：744/6%, 546/6%, 540/9%, 593/5%, 652/4%, 555/6%, 430/8%,
                    //             456/9%, 987/4%, 747/6%, 723/6%, 430/10%
                    //     语音段：3651/4%, 4900/9%, 5870/14%
                    //   噪声的过零率 4~10%，语音 4~14%，**区间完全重叠**，
                    //   这台机器的噪声不是低频嗡鸣而是宽带噪声（数字麦克风 + vulture 干扰），
                    //   zcr 区分不了。所以收尾只认能量。
                    //
                    //   真正有效的观察：语音 rms 3651~5870，噪声 400~990，差 4~10 倍。
                    //   之前之所以失败，不是判据不够聪明，而是收尾线（floor 底噪×2=926）
                    //   离噪声峰值 990 太近。现在抬到 2.8 倍（≈1300），噪声就压不住了。
                    boolean voiced = rm >= silThr;
                    double zc = Pcm.zcr(b2, r2, chan);   // 仅保留在日志里用于日后诊断

                    if (chunks <= 24) trace.append(rm).append('/').append((int) (zc * 100)).append('/').append(silThr).append(',');
                    if (voiced) silRun = 0; else silRun++;
                    // ★ 最短录音保护：开口那一帧常常是麦克风的瞬态爆音，
                    //   紧接着就是真语音之间的字间停顿（"小智"两个字之间约 200ms）。
                    //   没有这个保护，会在 240ms 就收尾，只录到半句 → 识别成"3。"。
                    //
                    // ★★ 2026-11-07 13:30 ★★ 实测教训：短句也不能只用 2 帧静音收尾。
                    //   "小智放歌" 被识别成 "小智关" —— 缺的那个"灯"字，
                    //   声母是 d、韵母是 -eng，能量特别弱，实测 rm 只有 700~800，
                    //   而收尾线 1430，一出现就被判成静音，2 帧就收尾，字直接没了。
                    //   现在短句也要连续 3 帧（300ms）才收尾，并且最少录 10 帧（1 秒），
                    //   让这种弱尾字有机会进到音频里。
                    //   代价是"小智放歌"多 200ms，但换来的是"说了就认"，值。
                    // ★★ 2026-11-11 16:18 本地命中时用"快速收尾" ★★
                    //   本地 KWS 命中那一刻，它已经把「小智」连同前 30ms 一起切好
                    //   送出去了（日志里的"预录 1125ms"就是这个），这段音频里
                    //   往往已经包含整个"小智放歌"。所以后面的录音只需要兜住
                    //   "用户一个字一个字慢慢说"的情况，不必再等满 1 秒。
                    //   8 帧 + 2 帧静音 = 说完约 1 秒内必定上传，比原来省 200~300ms。
                    int minChunks = (kw != null) ? 8 : 10;
                    int silNeed = (kw != null) ? 2 : ((chunks < 12) ? 3 : SIL_END);
                    if (silRun >= silNeed && chunks >= minChunks) break;
                    if (chunks >= MAX_CHUNKS) break;
                }
                lastTurnLevel = (int) voiceRef;   // 给「不是叫我」闸用
                L.i(TAG, "收尾 chunks=" + chunks + " 语音电平=" + voiceRef + " rm/thr=[" + trace + "]");
                recording = false;      // 录音循环已退出，看门狗 10 秒硬上限不再适用
                turnStartAt = 0;
                // ★ 2026-11-07：inTurn 要一直true到整轮（含等回答、播报）结束。
                //   中间这段不读麦克风，如果让看门狗插进来 stopRec()，
                //   重新 openRec() 在这台机器上会拿不到输入轨道，之后永远读到空，
                //   只能等进程重启 —— 这就是"答完一句就聋了"的真相。
                lastVoice = System.currentTimeMillis();
                endTurn();
                drainRec();
                inTurn = false;
                // 注意：这里【不要】stopRec()。
                //   2026-11-07 试过"每轮播完重建录音"，结果这台的 HAL 重开后读到全零，
                //   看门狗反复重开也救不回来，反而把长连接打断。
                //   结论：AudioRecord 保持一条长命流最稳，卡死交给 5 秒看门狗兜底。
            }
        }
    }

    // ───────────────────────── 调试：纯文字一轮 ─────────────────────────

    private volatile boolean debugMode = false;
    private int dbgFrames = 0, dbgPeak = 0;

    private void debugText(final String text) {
        try {
            p = Prefs.load(this);
            ensureClient();
            XzClient c = client;
            if (c == null) { L.e(TAG, "调试：连不上小智云"); return; }
            synchronized (lock) { gate = 1; pendingAudio.clear(); }
            ttsStop = false;
            dbgFrames = 0; dbgPeak = 0;
            debugMode = true;
            resampler = new Pcm.Resampler(Prefs.toneFactor(p.tone));
            ensureTrack();
            long t0 = System.currentTimeMillis();
            c.askText(text);
            L.i(TAG, "调试问: " + text);
            long end = t0 + 25000;
            while (System.currentTimeMillis() < end && !ttsStop) sleep(60);
            sleep(400);
            long dt = System.currentTimeMillis() - t0;
            debugMode = false;
            L.i(TAG, "调试答完 用时 " + dt + "ms，音频帧 " + dbgFrames + "，峰值 " + dbgPeak);
        } catch (Throwable e) {
            L.e(TAG, "调试失败", e);
        }
    }

    /**
     * 连接保活线程（2026-11-08 新增，专治"过一夜就失联"的第二根病灶）。
     *
     * 现场：早上第一句「小智放歌」完全没反应，可进程在、麦克风也在录。
     * 根因是 TCP 连接被路由器的 NAT 表静默回收（不发 FIN/RST），
     * 本地 socket 以为还连着，音频发出去全进黑洞。
     * 以前只在 beginTurn 里判"闲置超过 180 秒就重连"，
     * 可问题是——不喊话就永远进不了 beginTurn，死连接一直留着，
     * 于是"早上第一句"必然踩空。
     *
     * 策略（两段式）：
     *   · 每 40 秒主动 ping 一帧，让 NAT 表项别过期（多数情况下根本不用重连）；
     *   · 距上次收包超过 240 秒，且此刻不在对话中 → 判定假死，干净重建。
     *     重建放在非对话态，避免把正在说的话拦腰砍断。
     */
    private void startLinkKeeper() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    sleep(40000);
                    try {
                        XzClient c = client;
                        if (c == null || !c.isOpen()) continue;
                        long idle = c.lastRxAt > 0
                                ? System.currentTimeMillis() - c.lastRxAt : 0;
                        if (idle > 240000 && !inTurn && !playing && !recording) {
                            L.w(TAG, "连接已静默 " + (idle / 1000) + " 秒，判定假死，主动重建");
                            closeClient();
                            ensureClient();
                        } else if (idle > 30000) {
                            c.sendPing();
                        }
                    } catch (Throwable e) {
                        L.w(TAG, "连接保活异常 " + e);
                    }
                }
            }
        }, "xz-link");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 主循环看门狗 v2（2026-11-08 17:30 重写）—— 只看 {@link #loopBeat} 心跳。
     *
     * 为什么推翻旧版：旧版靠 inTurn / recording 判断，而今天 14:45 那次卡死
     * 正好发生在 endTurn() 里（inTurn=true、recording=false），
     * 旧代码走的是 `if (inTurn) { lastFrameAt = now; continue; }`
     * —— 看门狗每 1.5 秒亲手中毒一次，把"卡死"刷新成"刚刚还有数据"，
     * 于是一下午都救不回来，用户喊放歌一次没成。
     *
     * v2 的三层恢复（越往后越重，能早恢复绝不用重手段）：
     *   ① 心跳 6 秒没动 → 拆掉 AudioRecord（release 会把阻塞在
     *      obtainBuffer futex 上的 read() 顶回来），主循环立刻自愈续跑；
     *   ② 心跳 10 秒还没动 → 说明连 release 都卡（HAL 死了），
     *      起一个独立 sh 延迟 3 秒重拉服务，然后杀掉自己；
     *      mic_keeper.sh 15 秒巡检是第二道保险，两路谁先到都能回来；
     *   ③ 全部记日志 + healCount，事后能数清"今天自愈了几次"。
     *
     * 待命态、播放态、等云端回答态，一律只认心跳 —— 因为心跳是所有
     * 正常路径都必须刷的（主循环顶 + 录音内层 + endTurn 两个等待循环）。
     */
    private void startMicWatchdog() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long lastBeat = -1;
                long beatAt = System.currentTimeMillis();
                boolean rescued = false;
                while (running) {
                    sleep(1500);
                    long b = loopBeat;
                    if (b != lastBeat) {
                        lastBeat = b;
                        beatAt = System.currentTimeMillis();
                        rescued = false;
                        continue;
                    }
                    long stuck = System.currentTimeMillis() - beatAt;
                    if (stuck >= 6000 && !rescued) {
                        // ① 拆录音 + 断连接，把阻塞的 read()/write() 顶回来。
                        //    ★ 救援动作放到独立线程里跑：万一 close() 自己也被卡住
                        //      （synchronized 写锁），看门狗线程不能陪着一起死，
                        //      否则第二层"自我重启"永远轮不到。
                        rescued = true;
                        healCount++;
                        L.w(TAG, "主循环 " + (stuck / 1000) + " 秒没心跳 → 拆录音+断连接强制唤醒｜第 "
                                + healCount + " 次自愈");
                        turnStartAt = 0;
                        recording = false;
                        inTurn = false;
                        lastFrameAt = System.currentTimeMillis();
                        resetNoise = true;
                        new Thread(new Runnable() {
                            @Override public void run() {
                                try { stopRec(); } catch (Throwable ignored) {}
                                try { closeClient(); } catch (Throwable ignored) {}
                            }
                        }, "xz-heal").start();
                        continue;
                    }

                    if (stuck >= 12000) {
                        // ② 连断开都救不回来 → 这个进程废了，重启自己
                        L.e(TAG, "主循环 " + (stuck / 1000) + " 秒仍无心跳，判定不可救 → 自我重启（3 秒后自动回来）");
                        selfRestart();
                        return;
                    }
                }
            }
        }, "xz-micwd");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 自我重启：直接退出进程，由外部把服务拉回来。
     *
     * ★★ 2026-11-08 17:40 改法（这一版是被整机重启教出来的）★★
     *   第一版写的是 `Runtime.exec("sh -c '(sleep 3; am startservice ...)'")`，
     *   想 3 秒后自己回来。但只跑了 20 分钟，整机就重启了，看门狗账本写着：
     *     "tinymix crash more than 5 times in 180s"
     *   —— 这台机的 NemoWatchdog 把**任何短命进程**都记成 crash
     *   （历史上 ps / grep / ls / sh 各自都中过一次 reboot），
     *   180 秒内 5 次就 rebootSystem。所以绝不能在恢复路径里频繁起新进程。
     *   现在只 kill 自己，零新进程：
     *     · mic_keeper.sh 每 15 秒巡检，发现小智不在就 startservice（已稳定两天）
     *     · 代价是恢复时间从 3 秒变最长 15 秒，但整机不会再被拖着重启。
     */
    private void selfRestart() {
        L.e(TAG, "自我退出，等待外部守护拉起（mic_keeper 15 秒巡检）");
        sleep(300);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /**
     * 麦克风看门狗：AudioRecord.read() 偶尔会永久阻塞（ALSA 状态还是 RUNNING，
     * 但一个字节都不给），表现为"服务活着但永远没反应"。卡住 8 秒就重开。
     */

    // ───────────────────────── 一轮对话 ─────────────────────────

    private void beginTurn() {
        turnT0 = System.currentTimeMillis();
        encFrames = 0;
        encMs = 0;
        // ★★ 2026-11-07 13:20 ★★ 长连接复用
        //   60 秒以内的连续几轮全部复用同一条 WebSocket，
        //   省掉每轮 0.8~1.5 秒的 TCP+TLS+Upgrade+hello 握手。
        //   超过 60 秒才重连 —— 服务端 session 大概率已被回收，
        //   拿着死连接发 listenStart 只会「喊了没反应」。
        long idle = idleSince;
        idleSince = 0;
        // ★ 60 → 180 秒：实测 60 秒这个阈值太保守。
        //   刚才第一轮就是因为闲置刚过 60 秒而强拆重连，白花了 3 秒（4050ms vs 1199ms）。
        //   连接真正死没死，readLoop 收到 close / 对端关闭时会自己把 open 置 false，
        //   到时候 warmUp 会补回来。这里只在闲置很久（3 分钟）时做一次保险性重连。
        if (client != null && idle > 0 && turnT0 - idle > 180000) {
            L.i(TAG, "连接空闲 " + ((turnT0 - idle) / 1000) + " 秒，重连");
            closeClient();
        }
        synchronized (lock) {
            gate = 0;
            rejectAt = 0;
            ttsStop = false;
            stopSent = false;
            ttsStarted = false;
            cmdDone = false;
            keepSession = false;
            pendingAudio.clear();
            localWoke = false;
        }
        // ★★ 2026-11-07 19:55 畅聊状态自检（每轮开头跑一次，成本为零）★★
        //   两种失效必须在这里兜住，否则就是"偷偷变成永久免唤醒"：
        //     ① chatMode 还挂着，但会话已经不在了 —— 状态不一致的残留；
        //     ② 畅聊挂了超过 15 分钟 —— 用户忘了说「不聊了」。
        //   出现任一种立刻落地，下一句就要重新喊「小智」。
        if (chatMode) {
            long nowMs = System.currentTimeMillis();
            if (!inSession) {
                chatMode = false;
                chatT0 = 0;
                L.i(TAG, "畅聊状态残留（会话已不在），已清理");
            } else if (chatT0 > 0 && nowMs - chatT0 > CHAT_MAX_SEC * 1000L) {
                chatMode = false;
                chatT0 = 0;
                L.i(TAG, "畅聊超过 " + (CHAT_MAX_SEC / 60) + " 分钟，强制落地");
            }
        }
        // ★ 异步化配套：上一轮的音频必须全部出队再 reset 累加器，
        //   否则队列里还压着旧数据的引用，编码顺序会错乱。
        drainSender(300);
        sendAcc.reset();
        p = Prefs.load(this);          // 每轮重读设置，改完立刻生效
        resampler = new Pcm.Resampler(Prefs.toneFactor(p.tone));
        ensureClient();
        XzClient c = client;
        // ★ 2026-11-07：这里不再 ensureTrack()。
        //   这台机器的 HAL 很敏感：开一轮时就急着建 AudioTrack，
        //   输入通路会被打断，实测启动后十几秒读不到任何音频。
        //   改成"真要出声时才建"（write()/playRaw() 里懒加载）。
        if (c != null) { c.listenStart(); }
        L.i(TAG, "开始一轮" + (inSession ? "（会话内，免唤醒）" : ""));
    }

    /** 边说边发：累积够 60ms 就编一帧发出去 */
    /**
     * 收音频 —— 只做一次入队就返回（微秒级）。
     *
     * ★ 2026-11-07 19:00 起这里不再编码、不再发送。
     *   以前这个函数在录音主循环里同步做 Opus 编码 + WebSocket 写，
     *   编码被 vulture 抢 CPU 拖到 76ms/帧时，整个录音循环跟着卡，
     *   100ms 的音频要 250ms 才轮到下一片，静音判据（按片计数）随之后延，
     *   表现出来就是"说完要愣一秒多"。
     *   现在编码和发送全在 senderLoop() 里跑，录音节拍只受麦克风约束。
     */
    private void stream(XzClient c, byte[] pcm16k) {
        if (pcm16k == null || pcm16k.length == 0) return;
        if (c == null || !c.isOpen() || c.codec() == null) return;
        // 队列满说明编码严重追不上。音频是实时流，丢最旧的才有意义
        // （旧数据对应的时刻早就过去了，留着只会让延迟雪球越滚越大）。
        if (sendQ.remainingCapacity() == 0) sendQ.poll();
        sendQ.offer(pcm16k);
    }

    // ───────── 独立发送线程：Opus 编码 + WebSocket 写出 ─────────

    private void startSender() {
        if (senderAlive) return;
        senderAlive = true;
        senderThread = new Thread(new Runnable() {
            @Override public void run() { senderLoop(); }
        }, "xz-sender");
        // 比录音线程更优先：宁可录音少睡一会，也不能让编码堆积。
        senderThread.setPriority(Thread.MAX_PRIORITY - 1);
        senderThread.start();
    }

    private void senderLoop() {
        while (senderAlive) {
            byte[] pcm = null;
            try {
                pcm = sendQ.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (pcm != null) {
                try {
                    encodeAndSend(pcm);
                } catch (Throwable e) {
                    L.e(TAG, "发送异常: " + android.util.Log.getStackTraceString(e));
                }
                continue;
            }
            // 队列已空 + 主线程请求收尾 → 补零发最后一帧。
            // 全程只有这一根线程碰 OpusEncoder，杜绝并发踩状态。
            if (finishPending) {
                try {
                    flushTail();
                } catch (Throwable e) {
                    L.e(TAG, "收尾异常: " + android.util.Log.getStackTraceString(e));
                }
                finishDone = true;
            }
        }
    }

    /** 把不足一帧的尾巴补零发出去，别让服务端少一个尾音（只在发送线程调用） */
    private void flushTail() {
        XzClient c = client;
        if (c == null || !c.isOpen() || c.codec() == null) return;
        byte[] rest = sendAcc.toByteArray();
        sendAcc.reset();
        if (rest.length == 0) return;
        byte[] pad = new byte[FRAME_BYTES];
        System.arraycopy(rest, 0, pad, 0, Math.min(rest.length, FRAME_BYTES));
        if (c.rawPcm()) { c.sendAudio(pad); return; }
        long tf = System.currentTimeMillis();
        byte[] opus = c.codec().encode(pad);
        encMs += System.currentTimeMillis() - tf;
        encFrames++;
        if (opus != null) c.sendAudio(opus);
    }
    /** 累积够一帧就编一帧发出去（只在 senderLoop 里跑，sendAcc 不会竞争） */
    private void encodeAndSend(byte[] pcm16k) {
        XzClient c = client;
        if (c == null || !c.isOpen() || c.codec() == null) return;
        sendAcc.write(pcm16k, 0, pcm16k.length);
        byte[] all = sendAcc.toByteArray();
        int frames = all.length / FRAME_BYTES;
        if (frames == 0) return;
        // ★ 2026-11-07 19:30：上行走裸 PCM 时，这里就是纯搬运，零编码开销。
        //   Opus 在这台机器上 71ms/帧 > 60ms/帧的实时线，天生追不上，
        //   只有绕开编码才能真正把「收尾→发完」压到 0。
        boolean raw = c.rawPcm();
        for (int i = 0; i < frames; i++) {
            byte[] f = new byte[FRAME_BYTES];
            System.arraycopy(all, i * FRAME_BYTES, f, 0, FRAME_BYTES);
            if (raw) {
                c.sendAudio(f);
                continue;
            }
            long t0 = System.currentTimeMillis();
            byte[] opus = c.codec().encode(f);
            encMs += System.currentTimeMillis() - t0;
            encFrames++;
            if (opus != null) c.sendAudio(opus);
        }
        sendAcc.reset();
        int rest = all.length - frames * FRAME_BYTES;
        if (rest > 0) sendAcc.write(all, frames * FRAME_BYTES, rest);
    }

    /**
     * 等发送队列排空。
     *
     * ★ 这是异步化必须配套的一步：endTurn 里要发"尾巴补零"和 listenStop，
     *   如果队列里还压着几百毫秒音频，listenStop 会插到音频中间发出去，
     *   服务端会把剩下那截当成新的一句话，识别结果直接错。
     *   超时上限给 1.2 秒 —— 真卡这么久说明连接已废，继续等也没意义。
     */
    private void drainSender(long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (!sendQ.isEmpty() && System.currentTimeMillis() < end && running) sleep(5);
    }

    private void endTurn() {
        // ★★ 2026-11-07 19:05 修正：补尾巴也必须交给发送线程做 ★★
        //   第一版异步化时，这里自己调 c.codec().encode(pad) 补尾音，
        //   结果和发送线程**同时**用同一个 OpusEncoder ——
        //   Concentus 的编码器内部有重采样状态，不是线程安全的，
        //   当场抛 AssertionError（Resampler.silk_resampler），
        //   整轮录音作废，日志里 18:50:12.995 那条崩溃就是它。
        //   现在改成"请发送线程代劳"：置标志 → 等它把队列排空并补完零 → 再 listenStop。
        finishDone = false;
        finishPending = true;
        long fin = System.currentTimeMillis() + 1500;
        while (!finishDone && System.currentTimeMillis() < fin && running) {
            loopBeat = System.currentTimeMillis();       // ★ 等发送队列也要打心跳
            sleep(5);
        }
        finishPending = false;

        XzClient c = client;
        if (c != null && c.isOpen() && c.codec() != null) {
            c.listenStop();
            int nf = encFrames;
            long ms = encMs;
            L.i(TAG, "编码 " + nf + " 帧共 " + ms + "ms（平均 "
                    + (nf > 0 ? (ms / nf) : 0) + "ms/帧，60ms 一帧才算追得上）");
            L.i(TAG, "已发完音频，等回答（从开口算 " + (System.currentTimeMillis() - turnT0) + "ms）");
        }

        stopSent = true;
        long end = System.currentTimeMillis() + 18000;
        while (running && System.currentTimeMillis() < end) {
            // ★★ 这个循环是最长的静默窗口（等云端回答最多 18 秒）。
            //   以前看门狗靠 inTurn 判断，正好在这里失明 —— 现在有心跳就没事了。
            loopBeat = System.currentTimeMillis();
            if (ttsStop || cmdDone) break;
            if (gate == -1) break;
            if (c != null && !c.isOpen()) break;
            if (c == null) break;
            sleep(40);
        }
        if (c != null) sleep(80);       // 播放器尾巴（原 200ms，实测 80ms 够，白等就是白慢）

        boolean accepted = gate == 1;
        // ★★★ 2026-11-08 18:05：默认不再"答一句就进会话"（用户明确要求）★★★
        //   原逻辑 `p.continuous && (accepted || inSession)` 会在连续对话开关打开时，
        //   喊一声"小智"答完一句就进入 25 秒会话 —— 这 25 秒里家人说什么都被接走，
        //   用户感受就是"开着开着它就自己长聊起来了"，而且指令开完也跟着进会话。
        //   现在的规则只有一条：**只有说了暗语（小智陪我聊聊天）才进长会话**，
        //   其它任何一句话（包括放歌、问天气）答完立刻回待命，下一句必须重新喊「小智」。
        //   p.continuous 仍然保留，但只作为"畅聊内部是否一直听"的开关，
        //   不再能让一次普通问答自己升级成会话。
        boolean stay = keepSession || chatMode;

        if (stay) {
            if (!inSession) {
                inSession = true;
                sessionStart = System.currentTimeMillis();
                sessionPeak = lastPeak;
                L.i(TAG, "进入连续对话（近场基准峰值 " + sessionPeak + "），静默 "
                        + p.idleTimeoutSec + " 秒自动结束");
            } else if (lastPeak > sessionPeak) {
                sessionPeak = lastPeak;
            }
            lastVoice = System.currentTimeMillis();
        } else {
            if (inSession) endSession("本轮结束");
            else {
                // ★★ 2026-11-07 13:20 关键提速：不再每轮 closeClient() ★★
                //   原代码每说完一句就主动断开 WebSocket，下一句要重新做
                //   TCP → TLS → HTTP Upgrade → 服务端 hello，实测这一整套 0.8~1.5 秒。
                //   用户感受就是"第一句还行，往后每一句都要愣一下"。
                //   session_id 是服务端在 hello 时分配的、整条连接共用的，
                //   listen start/stop 可以在同一 session 里反复用（协议本来就这设计），
                //   所以连接完全可以一直养着。
                //   这里只记下"从什么时候开始没人说话"，由主循环在空闲够久时才真正断开，
                //   避免拿着一条被服务端悄悄关掉的死连接去发 listenStart。
                idleSince = System.currentTimeMillis();
            }
        }

        // ★ 只在没有 MediaPlayer 在放的时候清 playing。
        //   "在呢"、开停止播放的提示音走 playRaw()，那是同步阻塞播放的；
        //   这里若无条件 playing=false，主循环会以为没在播，
        //   立刻把提示音本身当成用户说话录下来 → 又开一轮 → 死循环。
        //   实测：14:07:30.086 本轮结束清了标志，14:07:30.572 就被"在呢"触发了。
        if (mp == null) playing = false;
        // ★ 350 → 200：播报完这 200ms 是"不拾音"的死区。
        //   连续对话时用户常常紧接着说下一句，350ms 会把"小智"两个字头切掉。
        //   200ms 够躲开播放器的混响尾巴，又不会吃掉下一句开头。
        quietUntil = System.currentTimeMillis() + 200;
        // ★ KWS 单独再静音 600ms：TTS 播完，声卡缓冲里还有 200~400ms 的尾巴。
        //   主循环那 200ms 的 quietUntil 挡不住它，尾巴会被 KWS 当成新的一句话
        //   去分析；而 TTS 的音色和训练模板（同一套合成引擎）相似度极高，
        //   实测 dist 0.24 直接命中 → 又开一轮 → 又播 TTS → 自己跟自己聊起来。
        //   日志里 13:58:03 到 14:01:06 那串连续命中就是自激振荡。
        kwsQuietUntil = System.currentTimeMillis() + 600;
        // ★ 不再每轮 resetNoise。新的噪声估计已经能正确忽略语音帧
        //   （只有接近底噪的帧才更新它），每轮重置反而会被播报余音带到 600。
        L.i(TAG, "本轮结束 接受=" + accepted + " 指令=" + cmdDone
                + (chatMode ? " · 畅聊中" : "")
                + (inSession ? " · 会话保持" : " · 回待命"));
    }

    private void endSession(String why) {
        if (inSession) {
            L.i(TAG, "结束会话（" + why + "），时长 "
                    + ((System.currentTimeMillis() - sessionStart) / 1000) + " 秒"
                    + (chatMode ? "，畅聊模式一并退出" : ""));
        }
        inSession = false;
        sessionPeak = 0;
        // ★ 畅聊是"一次会话"级别的状态，会话没了它就必须跟着没，
        //   否则下次开口还带着免唤醒，等于变成永久全局开关。
        chatMode = false;
        chatT0 = 0;
        keepSession = false;
        closeClient();
    }

    /** ★★ 2026-11-07 19:55 硬回待命（指令专用）★★
     *  只清"会话级"状态，**保留 WebSocket** —— 指令本身就要 abort 云端回答，
     *  但连接留着，下一句话不用重连（省 0.8~1.5 秒）。
     *
     *  为什么指令必须走这里：放歌/停止播放是用户频率最高、最"事务性"的指令。
     *  只要 inSession 还挂着，灯开完那一刻家人随口一句话就会被当成
     *  "会话内免唤醒"接走 —— 用户原话"逻辑冲突了"。
     *  所以指令一命中就落地，无论它发生在待命态还是畅聊中。 */
    private void dropToIdle(String why) {
        boolean was = inSession || chatMode;
        inSession = false;
        sessionPeak = 0;
        chatMode = false;
        chatT0 = 0;
        keepSession = false;
        if (was) L.i(TAG, "回待命（" + why + "）：会话/畅聊状态已清，下句话需重新喊「小智」");
    }

    // ───────────────────────── 回调（reader 线程） ─────────────────────────

    @Override
    public void onStt(String text) {
        if (text == null) return;
        L.i(TAG, (stopSent ? "识别: " : "[边听边识别] ") + text);

        // ★★ 2026-11-07 关键：任何识别结果一到，立刻关闸 ★★
        //   云端是"先推文本、30~200ms 后再推 TTS 音频"，而 playing 标志要等
        //   onAudio/write() 才置 true。中间这几十毫秒里，主循环还在待命态，
        //   扬声器已经开始出声 → 麦克风录到 25000 的能量 → 越过阈值 972 →
        //   误开一轮，把 TTS 自己的声音当用户说话再送一遍云端。
        //   实测日志：14:07:30.989 回答文本，14:07:31.020 就开口 rms=25146。
        //   这里提前关闸，把那 30ms 的窗口堵死。
        long guard = System.currentTimeMillis() + 400;
        if (quietUntil < guard) quietUntil = guard;
        if (kwsQuietUntil < guard) kwsQuietUntil = guard;

        if (cmdDone) return;

        String t = text.replace(" ", "");

        // ★★ 2026-11-07 18:20 指令提到闸门之前（这是"时灵时不灵"的真凶之一）★★
        //   原来上面是 `if (gate != 0 || cmdDone) return;`，会吞掉真命令：
        //   流式 ASR 常常先吐一个部分结果"小智"，紧接着才吐"小智放歌"。
        //   第一次回调只有唤醒词 → 走"只喊名字"分支 → gate=-1 并 abort；
        //   等第二次回调带着"放歌"到达，gate 已经是 -1，被无条件 return 掉，
        //   于是灯死活不开。用户感受就是"刚才能用，现在又不灵了"。
        //   指令是最高优先级：只要听出"灯 + 开关"，不看 gate，也不要求唤醒词。
        //   （"不管喊小智、小度还是小鱼"，正是靠这一条实现的。）
        // ★★★ 2026-11-11 16:00 唯一的补丁：指令必须先确认「在叫我」★★★
        //   11-08 这份代码本身没这个问题，是环境变了 —— 15:41 实录：
        //     电视在讲"非常大，有的还会亮灯。当你旋转调你旋钮"，
        //     指令判定只认开关类动词，于是指令被电视误触发。
        //   判据：文本里有「小智」，或者本地 KWS 刚刚用音色确认过是本人。
        //   电视两条都不满足 —— 灯不会再被它点着。
        //   除此之外一个字都没动，识别、唤醒、应答全是 11-08 原样。
        // ★★ 2026-11-11 20:19 指令要求「必须以唤醒词开头」★★
        //   实测 20:17:46：屋里一句普通说话（rms 6132）被云端听成"小志放歌"，
        //   灯就开了。isWake 是 contains 判据 —— 句子里**任何位置**出现
        //   "小智/小志"都算数，电视里说"你叫小智放歌试试"也会被执行。
        //   wakeAtHead 要求唤醒词在开头（允许前面挂一个"嗯/哎"），
        //   真人喊"小智放歌"永远满足，碰巧提到的句子基本不会满足。
        boolean addressed = wakeAtHead(t) || localWoke;
        //   → gate=-1 并 abort。等第二个回调带着「小智陪我聊聊」到达时，
        //   gate 已经是 -1，被下面的 `if (gate != 0) return;` 吞掉。
        //   指令当初就是栽在这一条上（见 18-指令响应-根因与修复.md）。
        //
        //   这里只置标志，**不 abort、不改 gate**：这句话本身就该让云端回答
        //   （"好呀，想聊点什么？"），比本地干一声"在呢"自然得多。
        //   万一 gate 已经被打成 -1，也不影响 —— 会话已经建立了，
        //   用户接着说下一句（不用喊名字）就能接上。
        // ★★ 2026-11-07 19:55 收紧：暗语必须"小智"在句首 ★★
        //   原来这里是 `(inSession || isWake(t) || localWoke)`，两个漏洞：
        //     ① inSession 那条通道会让"会话里随口一句带'聊'字的话"重新激活畅聊；
        //     ② isWake 是 contains，房间里的任何对话只要提到"小智陪我聊聊"
        //        就会被当成命令。19:48 实测就是这么中招的 —— 用户在旁边口述
        //        "…比如说小智陪我聊聊天，它才会进入长聊天…"，设备听进去直接进了畅聊，
        //        之后家人正常说话全被接走。这正是用户说的"逻辑冲突"。
        //   现在只认三条路：句首有「小智」 / 本地 KWS 刚认出「小智」音色 / 本来就在畅聊。
        // ★★★ 2026-11-11 16:12 畅聊暗号收紧：只认「小智」在句首 ★★★
        //   原来还有一条 `localWoke`：只要本地音色认出"小智"，这一整句都算在对它说。
        //   问题是 localWoke 在本轮里对**任何**一句话都成立，只要句子里碰巧带
        //   "聊聊天 / 陪我聊 / 说说话"，就直接进畅聊 —— 用户原话：
        //   "我不跟它说长聊天的暗号，它不要自己老触发"。
        //   现在只认两条：句首有「小智」，或者本来就在畅聊里（继续聊）。
        boolean chatAddr = wakeAtHead(t) || (inSession && chatMode);
        if (chatAddr && chatIntent(t)) {
            if (!inSession) {
                inSession = true;
                sessionStart = System.currentTimeMillis();
                sessionPeak = 0;
            }
            // 流式 ASR 会把同一句吐两三次（部分结果 + 最终结果），
            // 只有第一次真正"进入"时才打日志，否则日志里会刷两行一样的
            if (!chatMode) {
                chatMode = true;
                chatT0 = System.currentTimeMillis();
                L.i(TAG, "★ 进入畅聊模式（静默 " + CHAT_IDLE_SEC + " 秒自动结束，说「不聊了」退出）");
            } else if (System.currentTimeMillis() - chatT0 > CHAT_MAX_SEC * 1000L) {
                // 防状态卡死：畅聊挂了超过 15 分钟，重说一次暗语就当重新开一场
                chatT0 = System.currentTimeMillis();
                L.i(TAG, "畅聊已超 " + (CHAT_MAX_SEC / 60) + " 分钟，暗语重新计时");
            }
            keepSession = true;
            lastVoice = System.currentTimeMillis();
        }

        // 指令已经处理完了，剩下的才看"这轮到底要不要理"。
        if (gate != 0) return;

        // ★ 2026-11-07 新增：用嘴开关连续对话。
        //   用户反馈"已开启/已关闭不灵敏"，其实之前根本没有语音入口，
        //   只能回设置页点按钮，还得等下一轮 beginTurn 才重读配置。
        //   这里在 ASR 结果里直接拦，改完立即写盘 + 立即生效，不用等下一轮。
        int ct = contIntent(t);
        if (ct != 0 && (inSession || isWake(t) || localWoke)) {
            boolean target = (ct == 2) ? !p.continuous : (ct > 0);
            doContSwitch(target);
            return;
        }

        // ★ 2026-11-07 新增：一句话退出会话
        if ((inSession || isWake(t) || localWoke) && isByeIntent(t)) {
            gate = -1;
            rejectAt = System.currentTimeMillis();
            discardPending();
            XzClient c = client;
            if (c != null) c.abort();
            endSession("用户说再见");
            // ★ 2026-11-11 16:20 这里原来播「在呢」，用户明确不要这个音；改成静默退出。
            return;
        }

        // ★★ 2026-11-07 本地唤醒放行 ★★
        //   走到这里的人声已经过了 KWS 的音色校验（本地认出了"小智"），
        //   所以云端识别文本里有没有"小智"三个字都不再是判据。
        //   这解决了两个实际问题：
        //     ① KWS 切出来的段常常只有 260ms（只含"智"），ASR 吐不出完整唤醒词
        //     ② 用户说得快，"小智放歌"被 KWS 整段切走，后续录音里只剩"放歌"
        //   这两种情况在原来的纯文本判据下都是 100% 被丢掉，表现就是"喊了它不理"。
        // ★★★ 2026-11-11 16:20 只认「文本里有名字」，不再用 localWoke 兜底 ★★★
        //   这是"它自己接我的话、回我在呢、说聊了好久"的总闸门。
        //   原来 localWoke 一为真，**这一整轮任何话**都放行（gate=1），
        //   于是房间里的闲聊、电视、我自言自语，全都被当成在跟它说话。
        //   现在必须文本里真的有「小智」：
        //     · 有 → 正常应答
        //     · 没有 → 一律静默丢弃（不动灯、不出声）
        //   指令不受影响：它前面有独立分支，判据是「灯+开关」+（文本有名字或本地音色确认）。
        if (isWake(t)) {
            // 2026-11-07 提速：看"去掉唤醒词与标点后还剩什么"
            //   旧逻辑 t.length()<=4 会漏掉最常见的"小智小智"（5 个字），
            //   那句本来该本地"在呢"，结果被当成正经问题发到云端，
            //   白等一轮识别+大模型+合成（实测多 1.0~1.2 秒）。
            //   现在"小智"、"小智小智"、"小智同学。"都进本地快速通道。
            String core = coreOf(t);
            if (core.length() <= 1) {
                // 只喊了名字：本地"在呢"，比等云端快得多
                gate = -1;
                rejectAt = System.currentTimeMillis();
                discardPending();
                XzClient c = client;
                if (c != null) c.abort();
                // ★ 2026-11-11 16:20 不再播「在呢」★
                //   用户原话："而且他还会说在呢在呢，我不需要他说在呢。"
                //   只喊名字就当没听见，安静等着他把话说完。
                //   （保留 p.wakeAck 这个设置项，但这条路径不再出声。）
                // ★★ 2026-11-08 18:05：只喊名字不再借机开会话 ★★
                //   原来这里是 `keepSession = p.continuous || chatMode`，
                //   而 p.continuous 在设置里是默认关、但用户点开过一次就一直开着。
                //   于是只要喊一声"小智"、它回一句"在呢"，就顺手把 25 秒会话打开，
                //   接下来家人聊天全被接走 —— 用户说的"时不时就自己长聊起来"就是它。
                //   现在：只有本来就处在畅聊里才保持，其余一律回待命。
                keepSession = chatMode;
                lastVoice = System.currentTimeMillis();
                return;
            }
            gate = 1;
            speechGate = 0;      // 认出来了 → 「不是叫我」闸立刻清零，恢复全灵敏度
            flushPending();
            return;
        }

        // ★★★ 2026-11-11 16:22 会话「免唤醒续聊」只在畅聊模式下有效 ★★★
        //   原来这里是 `if (inSession)`：只要上一轮答过，接下来 25 秒里
        //   任何一句话都免唤醒直接回答 —— 用户说的"它自己接我的话去长聊"就是这条。
        //   现在只有明确说了「小智陪我聊聊天」进了畅聊，才允许免唤醒接着聊；
        //   其余情况每句话都必须带「小智」。
        if (chatMode) {
            gate = 1;
            flushPending();
        } else {
            gate = -1;
            rejectAt = System.currentTimeMillis();
            L.i(TAG, "没喊唤醒词，不理这句话");
            // ★★ 2026-11-11 20:10 删除「抬门槛 25 秒」的执行段 ★★
            //   见上面 thr/thrLo 处的说明：这一轮不是叫我，只是白传一段音频，
            //   不该反过来把接下来 25 秒的耳朵捂住。宁可多传，不许听不见。
            discardPending();
            // ★★★ 2026-11-11 16:25 被拒的一轮，后 3 秒的下行音频一律作废 ★★★
            //   实测（16:00:27 / 16:00:47 / 16:01:10）：明明判了"不理这句话"，
            //   云端 0.6~1 秒后还是推来「我们已经连续聊了好久好久了」——
            //   因为服务端那段会话里已经堆了几十轮，它自己在收尾道别。
            //   这些音频原本靠 gate=-1 挡，但下一轮开始时 gate 会被重置成 0，
            //   于是上一轮的尾巴被当成新答案播了出来。用户听到的就是"它又自己长聊了"。
            dropTtsUntil = System.currentTimeMillis() + 3000;
            XzClient c = client;
            if (c != null) c.abort();
        }
    }

    @Override
    public void onAnswerText(String text) {
        if (text == null) return;
        // ★★★ 2026-11-11 16:28 云端自己"收尾道别"，一律静音 ★★★
        //   现象：用户喊「小智放歌」，它却回「我们已经连续聊了好久好久了，
        //   今天就先聊到这里吧，拜拜」。这不是我们的逻辑误判，是**服务端那段会话
        //   里已经堆了几十轮对话**，它自己在做结束语。
        //   我们这边控制不了别人的服务器，但可以控制嘴：听到这几句就闭嘴，
        //   并把接下来 8 秒的音频全部作废（它通常连着推 3 句）。
        if (text.contains("聊了好久") || text.contains("明天再聊")
                || text.contains("今天就先聊到这里") || text.contains("拜拜啦")) {
            dropTtsUntil = System.currentTimeMillis() + 8000;
            L.i(TAG, "云端自行收尾道别，已静默：" + text);
            return;
        }
        L.i(TAG, "回答: " + text);
    }

    @Override
    public void onAudio(byte[] pcm) {
        ttsStarted = true;
        // 指令刚执行完：这 6 秒内的下行音频一律丢弃（见 onStt 里的说明）
        if (System.currentTimeMillis() < dropTtsUntil) return;
        if (gate == -1) return;
        if (gate == 0) {
            synchronized (lock) {
                if (pendingAudio.size() < 120) pendingAudio.add(pcm);
            }
            return;
        }
        write(pcm);
    }

    @Override
    public void onTtsStop() {
        ttsStop = true;
    }

    @Override
    public void onClosed(String why) {
        L.w(TAG, "连接断开: " + why);
        // ★★ 2026-11-07 20:58 断开即补，不再等主循环的 20 秒空窗 ★★
        //   这就是用户说的"过一会儿不叫它，再在远处喊就不灵"的真凶。
        //   实测（20:56）：闲置 100 秒期间服务端主动 close，
        //   而补连只挂在主循环里、每 20 秒才有一次机会 ——
        //   断开后的头 20 秒里喊它，音频全发进一条已经死掉的连接，
        //   表现就是"像没听见"，下一句才好。
        //   现在一收到 close 立刻起线程重连，空窗从 20 秒压到 3 秒内。
        //   3 秒冷却防止服务端持续拒绝时形成重连风暴。
        long now = System.currentTimeMillis();
        if (now - lastReconnAt < 3000) return;
        lastReconnAt = now;
        new Thread(new Runnable() { @Override public void run() { ensureClient(); } }, "xz-reconn").start();
    }

    private void flushPending() {
        ArrayList<byte[]> copy;
        synchronized (lock) {
            copy = new ArrayList<byte[]>(pendingAudio);
            pendingAudio.clear();
        }
        for (byte[] b : copy) write(b);
    }

    /**
     * ★★ 2026-11-08 17:50：丢掉"这一轮已经被判死，但云端还在推"的音频 ★★
     *
     * 实测（17:23:32）就是这么出洋相的：
     *   环境噪声触发了一轮 → 云端吐出乱文本"水温水质稍微低一点" → 判「没喊唤醒词」
     *   → gate=-1、abort。可是 abort 之前那 200ms 里，gate 还是 0，
     *   onAudio 已经把几包 TTS 塞进 pendingAudio 了。
     *   这些残留不会自己消失，会在**下一轮** flushPending() 时被播出来 ——
     *   用户听到的是"小智莫名其妙说了一句不相干的话"，同时 playing=true
     *   把麦克风关上 3~5 秒，这期间喊"小智放歌"完全没反应。
     *
     * 所以任何"拒绝这轮"的路径都必须顺手把残留清干净：
     *   pendingAudio（还没播的）+ playQ（已进播放队列的）+ playing 标志。
     * 注意不要 stopPlay()：那是预置提示音（"在呢"、"放歌啦"）在用的播放器。
     */
    private void discardPending() {
        synchronized (lock) {
            pendingAudio.clear();
        }
        synchronized (playQ) {
            playQ.clear();
            playQ.notifyAll();
        }
        playing = false;
        ttsStop = true;      // 让 endTurn 的等待循环立刻收手，早点回待命
    }

    /** 只入队，不阻塞网络线程 */
    private void write(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        playing = true;
        synchronized (playQ) {
            playQ.addLast(pcm);
            // 队列太长说明播放端跟不上（或者对端灌太快），丢最旧的防止延迟越滚越大
            while (playQ.size() > 60) playQ.removeFirst();
            playQ.notifyAll();
        }
        startPlayThread();
    }

    /** 独立播放线程：跑 URGENT_AUDIO 优先级，避免被 GC / 其他线程抢走导致断音 */
    private void startPlayThread() {
        if (playThread != null && playThread.isAlive()) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
                int idleMs = 0;
                while (running) {
                    byte[] pcm;
                    synchronized (playQ) {
                        if (playQ.isEmpty()) {
                            try { playQ.wait(120); } catch (InterruptedException ignored) {}
                            if (playQ.isEmpty()) {
                                // ★★ 2026-11-08 17:35 兜底：队列空够久就主动放下 playing ★★
                                //   这是今天"小智又聋了"的第二条隐蔽路径：
                                //   playing 只在 endTurn() 末尾和 playRaw() 的 finally 里复位，
                                //   一旦走到"服务端推完音频但 endTurn 提前 break / 抛异常"，
                                //   或者结束信号丢了，playing 就永久 true ——
                                //   主循环每帧 `if (playing) continue`，一个字节都不处理，
                                //   现象和今天 14:45 那次完全一样：进程活着、日志全停、喊不动。
                                //   2 秒是留足余量：正常 TTS 分段间隔远小于 2 秒，
                                //   不会把"正在播报"误判成"播完了"。
                                idleMs += 120;
                                if (playing && idleMs >= 2000) {
                                    playing = false;
                                    long g = System.currentTimeMillis() + 200;
                                    if (quietUntil < g) quietUntil = g;
                                    if (kwsQuietUntil < g + 400) kwsQuietUntil = g + 400;
                                    resetNoise = true;
                                    L.w(TAG, "播放队列空 2 秒 → 兜底放下 playing（防收尾信号丢失导致永久静音）");
                                }
                                continue;
                            }
                        }
                        pcm = playQ.pollFirst();
                    }
                    idleMs = 0;
                    AudioTrack at = track;
                    // 懒加载：真要出声了才建播放器，避免启动时就把录音通路顶坏
                    if (at == null) { ensureTrack(); at = track; }
                    if (at == null || pcm == null) continue;
                    try {
                        byte[] out = resampler != null ? resampler.process(pcm) : pcm;
                        if (out != null && out.length > 0) at.write(out, 0, out.length);
                    } catch (Throwable e) {
                        L.e(TAG, "播放失败", e);
                    }
                }
            }
        }, "xz-play");
        t.setDaemon(true);
        playThread = t;
        t.start();
    }

    /** 「用户说完最后一个字 → 动作真的执行了」的毫秒数，排查延迟用 */
    private String lagMs() {
        long s = lastSpeechAt;
        if (s <= 0) return "?";
        return (System.currentTimeMillis() - s) + "ms";
    }

    /**
     * 判断这句话是不是在说"放歌/停止播放"。
     * ★★ 2026-11-07 13:25 重写 ★★
     *   原来是硬编码十来个短语（"放歌""打放歌""把灯打开"…），
     *   漏掉的表达太多了：ASR 只要多吐一个"吧"、"啊"、"一下"，
     *   或者把语序说成"灯打开"、"灯关掉"，就整句失配 ——
     *   用户感受是"我明明说得很清楚，它就是不执行"。
     *
     *   现在改成"必须提到灯 + 必须有动作词"的组合判断：
     *     · 一定要出现"灯"（否则"开空调""关电视"会被误判成指令）
     *     · 动作词：开/关/亮/熄/灭/打开/关闭 任意一个
     *   这样"小智放歌""小智把灯开一下""小智停止播放吧""灯关掉"全都能命中。
     */
    /**
     * "小智开" / "小智关" —— 只说了动作、宾语被 ASR 丢掉的情况，按指令处理。
     *
     * ★★ 2026-11-07 13:55 实测依据 ★★
     *   "小智放歌" 的录音电平序列：7747, 5944, 3517, 6188, 461, 374, 359, 418...
     *   前 4 帧是"小智开"，第 5 帧直接掉到 461 —— 那个"灯"字
     *   声母 d 是爆破音（几十毫秒就过）、韵母 -eng 是低能量鼻音，
     *   1 米外实测 rm 只有 400~500，和房间底噪 309 几乎同量级。
     *   云端 ASR 拿到的是"小智开"，用户听到的结果就是"我说了它没反应"。
     *
     *   用户 90% 的语音指令就是开停止播放，所以这里做兜底：
     *   只要"带唤醒词 + 只剩一个开/关动作"，就按指令执行。
     *   精确性靠 coreOf 保证 —— "小智开空调" 的 core 是"开空调"，不等于"开"，不会误触发。
     */
    /**
     * 解析"连续对话"的开/关意图。
     * 返回 1=开, -1=关, 0=不是这条指令（交回正常流程）。
     *
     * 必须先去掉唤醒词再判，否则"小智，关闭连续对话"里的"小智"不影响，
     * 但"开启"含"开"、"关闭"含"关"，不先分开关/关就会判反。
     */
    private int contIntent(String raw) {
        String t = stripWake(raw);
        boolean topic = t.contains("连续对话") || t.contains("多轮对话")
                || t.contains("连续交谈") || t.contains("连续聊天")
                || t.contains("一直对话") || t.contains("一直聊天")
                || t.contains("长对话") || t.contains("连续对化")
                || t.contains("连续对画");
        if (!topic) return 0;
        boolean off = t.contains("关") || t.contains("停") || t.contains("取消")
                || t.contains("退出") || t.contains("结束") || t.contains("不要")
                || t.contains("别");
        boolean on = t.contains("开") || t.contains("启") || t.contains("进入")
                || t.contains("开始") || t.contains("来");
        // "关"优先：中文里"关闭/关掉/关一下"都带"关"，而"打开/开启"不带"关"
        if (off) return -1;
        if (on) return 1;
        // 光说"连续对话"四个字 → 当成开关切换，配合着说更顺手
        return 2;
    }

    private boolean isByeIntent(String raw) {
        String t = stripWake(raw);
        // 只在短句里认，避免"我想退出去旅游"这种被误判
        if (t.length() > 8) return false;
        return t.contains("退出对话") || t.contains("结束对话")
                || t.contains("不聊了") || t.contains("别聊了") || t.contains("不聊啦")
                // ★ 2026-11-11 16:15 补：用户平时就是这么说的，原来一条都不认，
                //   进了畅聊只能等 5 分钟自动结束 —— 这正是"它老自己长聊"的观感来源之一。
                || t.contains("结束聊天") || t.contains("退出聊天")
                || t.contains("停止聊天") || t.contains("关闭聊天")
                || t.contains("先这样") || t.contains("拜拜") || t.contains("再见")
                || t.contains("睡了") || t.contains("聊够了") || t.contains("就到这")
                || t.contains("结束了") || t.contains("结束吧");
    }

    /**
     * 畅聊模式的进入暗语。
     *
     * ★ 挑词的约束（踩过就知道）：
     *   ① 不能含「灯」或「开等/关等」这类同音容错词 —— 会被指令抢走；
     *   ② 不能是 coreOf 之后只剩「开/关」的短句 —— 会被当成短指令；
     *   ③ 要好说、ASR 不容易听错：「陪我聊聊」四个字全是常用字，
     *      而「畅聊」容易被听成「唱聊/常聊」，所以只当补充。
     *
     * ★ 反过来，如果「陪我聊聊」这种日常请求不带唤醒词地出现在畅聊中，
     *   走的是 inSession 分支（免唤醒），不需要再匹配一次。
     */
    /** 唤醒词是否在**句首**（容忍最多 2 个字的口语前缀：嗯/哎/那个…）。
     *
     *  为什么畅聊暗语需要这一条，而指令不需要：
     *    指令只要"灯 + 开关"，说错也只是开个灯，代价极小，所以放得最宽；
     *    畅聊一旦误入，**之后几分钟里家人所有说话都会被接走**，代价极大。
     *    实测 19:48：用户在房间口述"…比如说小智陪我聊聊天，它才会进入长聊天…"，
     *    设备听见后直接进了畅聊。加"句首"这一条，能把转述、引用、
     *    "你下次说小智陪我聊聊天试试"这类话挡在外面。 */
    private boolean wakeAtHead(String t) {
        if (t == null) return false;
        String s = t.replaceAll("[\\p{Punct}，。！？、：；“”‘’\\s]", "");
        if (s.length() < 2) return false;
        String[] pre = {"小智", "小致", "小知", "小志", "小质", "小值", "小只",
                "小治", "小芝", "小度", "小鱼", "小美", "小艺"};
        for (String w : pre) {
            if (s.startsWith(w)) return true;
            int i = s.indexOf(w);
            if (i > 0 && i <= 2) return true;      // "嗯小智…" / "哎小智…"
        }
        return false;
    }

    /**
     * ★★ 2026-11-08 重写：长聊天必须说「明确暗语」，不再靠语气猜 ★★
     *
     * 旧判据是「人称 + 聊」两个条件同时成立就算进畅聊：
     *     who  = 陪我 / 咱俩 / 我们 / 和我 / 跟我
     *     talk = 聊 / 疗 / 辽 / 唠
     * 于是「小智，我们聊聊今天天气」直接就进畅聊 —— 之后 5 分钟内（CHAT_IDLE_SEC）
     * 家里人正常说话全被当成在跟它聊。用户原话「长聊天还是会被不自觉的开启」，
     * 根子就在这个「猜意图」上：猜错一次，后面几分钟都错。
     *
     * 新判据只认完整词组，说清楚才进：
     *     「小智，我们聊聊天」「小智陪我聊会儿」「小智，说说话」
     * 只说「小智，我们聊聊天气」= 普通问答，答完立刻回待命。
     *
     * ASR 容错：实测「聊」常被听成「疗/辽/撩/料」（19:39「聊会儿」→「小治疗慧儿」），
     * 所以先做单字归一化再匹配；但仍然必须凑成完整词组，
     * 单说「治疗」「辽宁」不会命中。
     */
    private boolean chatIntent(String raw) {
        String t = stripWake(raw);
        if (t == null) return false;
        String n = t.replace('疗', '聊').replace('辽', '聊')
                .replace('撩', '聊').replace('料', '聊');
        if (n.length() > 16) return false;      // 长句里顺带带这些字不算

        String[] keys = {
                "聊聊天", "聊聊吧", "聊会儿", "聊一会", "聊一下", "聊一聊",
                "陪我聊", "陪你聊", "和我聊", "跟我聊", "咱俩聊",
                "说说话", "唠嗑", "唠会儿", "侃大山",
                "畅聊", "长聊", "聊天模式"
        };
        for (String k : keys) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    /**
     * 执行连续对话开关。
     * 关键点：改完【立即写盘】+【立即改内存里的 p】，不等下一轮 beginTurn 重读。
     * 原来只有设置页能改，服务要等下次开口才生效，用户就会觉得"点了没反应"。
     */
    private void doContSwitch(boolean target) {
        p.continuous = target;
        p.save(this);

        XzClient c = client;
        if (c != null) { try { c.abort(); } catch (Throwable ignored) {} }
        gate = -1;
        rejectAt = System.currentTimeMillis();

        if (target) {
            inSession = true;
            sessionStart = System.currentTimeMillis();
            sessionPeak = 0;
            lastVoice = System.currentTimeMillis();
            keepSession = true;
            L.i(TAG, "★ 连续对话 → 开启（语音指令），静默 " + p.idleTimeoutSec + " 秒自动结束");
        } else {
            keepSession = false;
            endSession("语音指令关闭连续对话");
            L.i(TAG, "★ 连续对话 → 关闭（语音指令）");
        }
        // 一声"在呢"当确认音：开/关都响，用户一听就知道听懂了
        if (p.wakeAck) playRaw(R.raw.zaine);
    }


    private boolean isWake(String t) {
        if (t == null) return false;
        String w = (p != null && p.wakeWord != null) ? p.wakeWord.trim() : "小智";
        if (w.length() == 0) w = "小智";
        if (t.contains(w)) return true;

        // ★★ 2026-11-07 18:20 唤醒词白名单不再绑死在"小智"上 ★★
        //   用户明确要求"不管喊小智、小度、小鱼，都能放歌"。
        //   原来这一段被 `if ("小智".equals(w))` 包着，只要用户把唤醒词改成
        //   别的名字，白名单整段就失效了，连"小智"都叫不醒 —— 很反直觉。
        //   现在无条件生效：常见语音助手名一律当唤醒。
        //   （"小度小度""小鱼小鱼"这种叠词/带后缀的，靠下面的 startsWith 兜住。）
        String[] alt = {
                // —— 本机的名字及同音变体 ——
                "小智", "小致", "小知", "小志", "小质", "小值", "小只", "小子", "小姿",
                "小治", "小制", "小至", "小芝", "小枝", "小支", "小馗", "小zhi", "xiaozhi",
                // —— 用户明确点名的其他助手名 ——
                "小度", "小鱼", "小美", "小帅", "小七", "小艺", "小微",
                "小天", "小冰", "小薇", "小忆", "小雅", "小讯", "小兔", "小星",
                "小布", "小欧", "小蜜", "小K", "小k", "小T", "小t"};
        for (String s : alt) if (t.contains(s)) return true;

        // 兜底：句首"小"开头的短词（≤4 字）一律当唤醒。
        // 为什么是 4 不是 3：实测 ASR 常把"小度"听成"小度度"、"小鱼"听成"小玉儿"，
        // 三个字就被卡掉了。放宽到 4 只是多认几个短词，不影响长句判断
        // （长句走的是 contains，不靠这条）。
        return t.startsWith("小") && t.length() <= 4;
    }

    private String stripWake(String t) {
        String w = (p != null && p.wakeWord != null) ? p.wakeWord.trim() : "小智";
        if (w.length() == 0) w = "小智";
        int i = t.indexOf(w);
        if (i >= 0) return t.substring(0, i) + t.substring(i + w.length());
        String[] pre = {"小智同学", "小智", "小致", "小知", "小志", "小质", "小值", "小只", "小子", "小姿"};
        for (String s : pre) {
            i = t.indexOf(s);
            if (i >= 0) return t.substring(0, i) + t.substring(i + s.length());
        }
        return t;
    }

    /**
     * 去掉【所有】唤醒词（含同音容错、含重复）和标点空白后剩下的正文。
     * 用途：判断"这句是不是只喊了名字"。
     *   "小智"        → ""
     *   "小智小智"     → ""        ← 最常见的喊法，必须能判成"只喊名字"
     *   "小智同学。"   → ""
     *   "小智，今天天气" → "今天天气"  ← 有正文，交给云端
     */
    private String coreOf(String t) {
        if (t == null) return "";
        String s = t;
        String w = (p != null && p.wakeWord != null) ? p.wakeWord.trim() : "小智";
        if (w.length() > 0) s = s.replace(w, "");
        String[] all = {"小智同学", "小智", "小致", "小知", "小志", "小质", "小值", "小只", "小子", "小姿",
                        "小美", "小帅", "小治", "小制", "小至", "小芝", "小枝", "小支",
                        "小馗", "xiaozhi", "小zhi"};
        for (String x : all) s = s.replace(x, "");

        // ★★ 2026-11-07 18:20 任意"小X"前缀都当唤醒词剥掉 ★★
        //   用户的要求是"不管喊小智、小度、小鱼、小美、小帅，都能放歌"。
        //   云端 ASR 把这两个字听成什么完全不可控，维护白名单永远追不上，
        //   所以按结构剥：句首"小" + 一个字 +（可选的"同学"）整段丢掉。
        //   安全性：只动句首，剥完还要再过开关类动词的双条件，
        //   所以"某手机怎么样"剥成"手机怎么样"也不会误触发。
        if (s.length() >= 2 && s.charAt(0) == '小') {
            s = s.substring(2);
            while (s.startsWith("同学")) s = s.substring(2);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 汉字/字母/数字算正文；标点、空白、符号一律丢掉
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    // ───────────────────────── 音频设备 ─────────────────────────

    private boolean openRec() {
        try {
            // ★ 2026-11-08：先把旧的那把释放掉。
            //   以前这里直接 new，被抢占时（startRecording 失败）旧对象不释放，
            //   AudioFlinger 里的 client 越堆越多，日志出现
            //   "Unable to retrieve AudioRecord object, can't record"。
            AudioRecord old = rec;
            rec = null;
            if (old != null) { try { old.release(); } catch (Throwable ignored) {} }

            int buf = AudioRecord.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (buf < 65536) buf = 65536;
            // ★★ 2026-11-07 13:50 定论：用 MIC，不要 VOICE_COMMUNICATION ★★
            //   实测对比：VOICE_COMMUNICATION 下 vulture 照样每秒刷 180 行录音错误，
            //   说明它用的不是这个源，共享通路的假设不成立；
            //   而 MIC 源实测最稳定（不依赖任何 effect 链和 AEC 宿主）。
            //   既然换来换去都不能让 vulture 闭嘴，就选最稳的那个。
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SR, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, buf);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                L.e(TAG, "录音初始化失败 state=" + rec.getState());
                try { rec.release(); } catch (Throwable ignored) {}
                rec = null;
                return false;
            }
            rec.startRecording();
            // ★★ 2026-11-08 真判定（这条是今早"失联"的根因）★★
            //   startRecording() 返回 void。通道被别的应用占着时，它只在系统日志里
            //   留一句 "startInput() failed: other input already started" 和
            //   "AudioRecord: start() status -38"，代码这边一声不响。
            //   以前这里无条件打「麦克风就绪」再 return true，于是出现
            //   「服务活着、日志漂亮、喊它完全没反应」，今早实测连续 1737 次假成功。
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                L.e(TAG, "麦克风被别的应用占用（录音状态 "
                        + rec.getRecordingState() + "），本次放弃，稍后重试");
                try { rec.release(); } catch (Throwable ignored) {}
                rec = null;
                return false;
            }
            // ★★★ 2026-11-07 13:00 紧急回滚，永远不要再打开 ★★★
            //   给 AudioRecord 挂 NoiseSuppressor / AutomaticGainControl 之后，
            //   这台 A83T 的 HAL 直接崩：日志变成
            //     "麦克风 2.5 秒没数据，重开录音" 无限循环，
            //   连开几次就把 mediaserver 拖死，整机重启（实测两次）。
            //   原因：/system/etc/audio_effects.conf 的 pre_processing 段
            //   根本没有为 "mic" 源配置 ns/agc 的 effect 链，
            //   create() 能成功、setEnabled() 也返回 true，但输入通路当场失效。
            //   结论：远场只能靠 VAD 阈值 + tinymix 增益，不要碰 AudioEffect。
            // attachEffects();
            lastFrameAt = System.currentTimeMillis();
            byte[] junk = new byte[CHUNK];
            int n1 = rec.read(junk, 0, CHUNK);
            int n2 = rec.read(junk, 0, CHUNK);
            if (n1 <= 0 && n2 <= 0) {
                // 状态是 RECORDING 但一个字节都读不到：HAL 层假活，同样当失败处理
                L.e(TAG, "录音通道已建立但读不到数据（" + n1 + "/" + n2 + "），放弃重试");
                try { rec.release(); } catch (Throwable ignored) {}
                rec = null;
                return false;
            }
            L.i(TAG, "麦克风就绪");
            return true;
        } catch (Throwable e) {
            L.e(TAG, "开录音失败", e);
            rec = null;
            return false;
        }
    }

    /**
     * 给录音流挂上系统级音频效果：降噪(NS) + 自动增益(AGC)。
     *
     * 为什么这一步比调 tinymix 重要：
     *   tinymix 是把整条链路的模拟增益提上去，噪声和语音一起放大，信噪比不变；
     *   NS 是频谱维度的抑制（稳态噪声被压掉），AGC 是按说话电平动态补偿距离衰减。
     *   主流智能音箱能几米外听见，靠的就是这两样 + 麦克风阵列波束成形。
     * 这台机 HAL 不一定全支持，所以每一项都独立 try，失败静默跳过。
     */
    private void attachEffects() {
        AudioRecord r = rec;
        if (r == null) return;
        int sid = 0;
        try { sid = r.getAudioSessionId(); } catch (Throwable ignored) {}
        if (sid == 0) { L.w(TAG, "音频会话 id 取不到，跳过效果器"); return; }

        try {
            if (NoiseSuppressor.isAvailable()) {
                fxNs = NoiseSuppressor.create(sid);
                if (fxNs != null) {
                    // ★ create() 只是把 effect 挂到会话上，默认是 disabled，
                    //   不显式 setEnabled(true) 等于没开 —— 实测日志一直是 enabled=false。
                    fxNs.setEnabled(true);
                    L.i(TAG, "硬件降噪 NS 已挂上 enabled=" + fxNs.getEnabled());
                }
            } else L.i(TAG, "硬件降噪 NS 本机不可用");
        } catch (Throwable e) { L.w(TAG, "NS 挂载失败 " + e); }

        try {
            if (AutomaticGainControl.isAvailable()) {
                fxAgc = AutomaticGainControl.create(sid);
                if (fxAgc != null) {
                    fxAgc.setEnabled(true);
                    L.i(TAG, "自动增益 AGC 已挂上 enabled=" + fxAgc.getEnabled());
                }
            } else L.i(TAG, "自动增益 AGC 本机不可用");
        } catch (Throwable e) { L.w(TAG, "AGC 挂载失败 " + e); }
    }

    private void releaseEffects() {
        if (fxNs != null) { try { fxNs.release(); } catch (Throwable ignored) {} fxNs = null; }
        if (fxAgc != null) { try { fxAgc.release(); } catch (Throwable ignored) {} fxAgc = null; }
        if (fxAec != null) { try { fxAec.release(); } catch (Throwable ignored) {} fxAec = null; }
    }

    private void ensureTrack() {
        if (track != null) return;
        try {
            int rate = client != null ? client.decodeRate() : 24000;
            int buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // 最小缓冲只有 ~170ms，网络稍有抖动就 underrun（听感就是"卡网"）。
            // 放大到 4 倍（约 0.6~0.7 秒）当蓄水池，代价是起播晚一点点，换来全程不断。
            buf = buf * 4;
            if (buf < 16384) buf = 16384;
            track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, buf, AudioTrack.MODE_STREAM);
            track.setStereoVolume(1f, 1f);
            track.play();
            L.i(TAG, "播放器就绪 " + rate + "Hz");
        } catch (Throwable e) {
            L.e(TAG, "播放器创建失败", e);
            track = null;
        }
    }

    private void stopTrack() {
        synchronized (playQ) { playQ.clear(); }
        if (track != null) {
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
            track = null;
        }
    }

    private void stopRec() {
        releaseEffects();
        if (rec != null) {
            try { rec.stop(); } catch (Throwable ignored) {}
            try { rec.release(); } catch (Throwable ignored) {}
            rec = null;
        }
    }

    private void drainRec() {
        if (rec == null) return;
        byte[] junk = new byte[CHUNK];
        for (int i = 0; i < 3; i++) {
            try { if (rec.read(junk, 0, CHUNK) <= 0) break; } catch (Throwable e) { break; }
        }
    }

    private void applyVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setMode(AudioManager.MODE_NORMAL);
            am.setSpeakerphoneOn(true);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, p.volume, 0);
        } catch (Throwable e) { L.w(TAG, "调音量失败"); }
    }

    private void playRaw(int resId) {
        stopPlay();
        playing = true;
        try {
            mp = MediaPlayer.create(this, resId);
            if (mp != null) {
                mp.start();
                long ms = mp.getDuration();
                if (ms > 0) sleep(ms + 200);
            }
        } catch (Throwable e) {
            L.e(TAG, "播放预置音失败", e);
        } finally {
            stopPlay();
            playing = false;
            quietUntil = System.currentTimeMillis() + 350;
            kwsQuietUntil = System.currentTimeMillis() + 700;
            resetNoise = true;
        }
    }

    private void stopPlay() {
        if (mp != null) {
            try { if (mp.isPlaying()) mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
    }

    // ───────────────────────── 连接管理 ─────────────────────────

    private void warmUp() {
        new Thread(new Runnable() {
            @Override public void run() { ensureClient(); }
        }, "xz-warm").start();
    }

    private synchronized boolean ensureClient() {
        XzClient c = client;
        if (c != null && c.isOpen()) return true;
        if (c != null) { c.close(); client = null; }
        // ★ 2026-11-07 20:58 去掉 stopTrack()。
        //   和 closeClient() 里已经删掉的理由完全一样：这台机器的音频 HAL 一旦
        //   销毁 AudioTrack，输入通路跟着被拆，AudioRecord.read() 从此一直返回 ≤0，
        //   要等十几秒才恢复 —— 表现就是"重连之后聋十几秒"。
        //   而"断开即补"现在会频繁触发 ensureClient，留着这行等于每重连一次聋一次。
        XzClient n = new XzClient(this);
        if (n.open()) {
            client = n;
            // ★ 2026-11-07：这里不再 ensureTrack()。
            //   开机预热连接时顺手建 AudioTrack，会把录音通路顶死十几秒，
            //   用户一开机就喊，正好撞在这段死窗里 → "喊它不理人"。
            //   播放器改成 write()/播放线程里懒加载。
            return true;
        }
        n.close();
        client = null;
        return false;
    }

    private synchronized void closeClient() {
        XzClient c = client;
        if (c != null) { c.close(); client = null; }
        // ★ 2026-11-07：不再 stopTrack()。
        //   这台机器的音频 HAL 有个怪脾气：AudioTrack 一销毁，输入通路跟着被拆，
        //   AudioRecord.read() 从此一直返回 ≤0（ALSA 还显示 RUNNING），
        //   实测要等十几秒、下一次 ensureTrack() 建好播放器才恢复。
        //   表现就是"每答完一句，小智就聋十几秒"。播放器留着不销毁，通路不断。
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) {}
    }
}
