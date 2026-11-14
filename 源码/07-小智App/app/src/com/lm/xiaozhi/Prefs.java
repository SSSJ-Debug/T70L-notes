package com.lm.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;

/** 小智的全部可调项，存在 SharedPreferences（服务与界面共用） */
public class Prefs {
    static final String FILE = "xiaozhi";

    // ★★ 2026-11-08：默认关闭 ★★
    //   用户主诉「长聊天自己就开了」：continuous=true 时，喊一次「小智」答完一句，
    //   接下来 25 秒（idleTimeoutSec）里所有话都不用唤醒词就被接走，
    //   家人正常聊天也会被当成在跟它说话。
    //   现在默认 false：每句都要「小智」开头；要长聊必须说暗语（见 chatIntent）。
    public boolean continuous = false;     // 连续对话（长对话），默认关
    public int idleTimeoutSec = 25;        // 会话静默多久自动结束
    public int volume = 8;                 // 系统音乐音量 0~15
    public int tone = 2;                   // 音色档 0~4（本机变调，见 toneFactor）
    public boolean wakeAck = true;         // 只喊唤醒词时回一句"在呢"
    public int sens = 1;                   // 0 高(安静房间) / 1 中(有电视) / 2 低(很吵)
    public String wakeWord = "小智";        // ★ 唤醒词，用户随便改
    public boolean localWake = true;       // ★ 本地唤醒引擎（MFCC+DTW，需要先学一次声音）

    /**
     * ★ 2026-11-07 新增：设置页改完"连续对话"要【立刻】通知服务。
     *   原来服务只在 beginTurn() 里重读 SharedPreferences，
     *   用户点"关闭"之后，当前这轮还在会话里，要等下一轮才开始读新值，
     *   表现就是"关了它还在聊"，最长能拖到静默超时（25 秒）。
     *   这里用静态字段做一次性信箱：界面写、服务取，取完就清空，零 IPC 成本。
     *   null = 没有待处理的变更。
     */
    public static volatile Boolean sContPending = null;

    public static final String[] TONE_NAMES = {"低沉男声", "沉稳", "标准", "清亮", "童声"};
    public static final String[] WAKE_PRESETS = {"小智", "小美", "小帅", "小度", "小艺", "小欣", "小云", "小可"};

    /**
     * 灵敏度 → {绝对门槛, 相对底噪倍数×10}
     *
     * ★ 2026-11-07 大改：原来的门槛是照着"近处正常音量"定的，
     *   实测待命底噪只有 195，门槛却卡 900 —— 相差 4.6 倍。
     *   几米外的人声 rms 也就 400~800，永远够不到门槛，
     *   于是"在旁边喊怎么都没反应"。主流智能音箱能几米外听到，
     *   靠的就是把这条线压到底噪之上一点点，再用"持续时间"挡掉噪声。
     *   现在按 底噪×倍数 为主、绝对门槛只做兜底，中档 195×1.8≈351，
     *   远处人声能进；瞬时噪声（关门、碰桌子）靠 ONSET 连续 3 帧挡。
     */
    /**
     * 灵敏度 → {绝对门槛, 相对底噪倍数×10}
     *
     * ★★ 2026-11-07 13:50 大改，从"1.5x"抬到"3.0x" ★★
     *   用户主诉："时灵时不灵，在旁边喊怎么喊都没反应。"
     *   根因不是阈值太高，恰恰是**太低**：
     *   实测本机噪声在 400~990 之间波动（vulture.app.home 在后台每秒 180 次
     *   失败录音重试，把音频通路搞出爆音），而原来的门槛只有底噪×1.5≈500，
     *   噪声一抖就"开口"，于是每一两秒就误触发一轮：
     *     误触发 → 录 1~6 秒噪声 → 上传 → 识别出"我。""好是。" → 丢弃。
     *   这 1~6 秒里用户真正喊的那句话**根本没人听**，表现出来就是"喊了没反应"。
     *   门槛提到 3.0 倍（底噪 380 → 1140）后，噪声 990 压不住了，
     *   而真实说话（近场 4000~15000、远场 1500~2500）依然轻松过线。
     *   这是"宁可少误触发，也不能把话筒占着"的取舍。
     */
    /**
     * ★★ 2026-11-07 20:50 再改：3.0x → 2.4x ★★
     *   用户主诉："两三米外喊它，偶尔就不理我了，过一会儿不叫它更明显。"
     *   实测（20:44 日志）：
     *     底噪 332~420，阈值 = 底噪×3.0 = 996~1260，
     *     而两三米外正常说话的开口气平是 1419 / 1702 —— 只剩 12% 余量。
     *   底噪只要抖到 420，阈值就顶到 1260，远场 1400 就压不住了：
     *   **远场失灵不是"听不见"，是"差一点点没够到线"**。
     *   降到 2.4x 后阈值 797~1008，远场余量从 12% 提到 40~75%。
     *   误触发靠下面新加的"低门限必须连续 600ms"来挡（见 XzService 开口判据）。
     */
    public static int[] sensParam(int sens) {
        if (sens == 0) return new int[]{150, 20};     // 高 · 安静房间（2.0x）
        if (sens == 2) return new int[]{500, 38};     // 低 · 很吵（3.8x）
        return new int[]{200, 24};                    // 中 · 默认（2.4x）
    }

    /**
     * 音色档 → 播放变速系数。
     * 说明：云端合成的声音是固定的（音色绑在智能体上），这里是在本机对解码后的
     * PCM 做重采样变速——快=音调高、慢=音调低，听感上就是"换了个嗓子"。
     */
    public static double toneFactor(int tone) {
        switch (tone) {
            case 0: return 0.88;    // 低沉男声
            case 1: return 0.94;    // 沉稳
            case 3: return 1.07;    // 清亮
            case 4: return 1.14;    // 童声
            default: return 1.0;    // 标准
        }
    }

    public static Prefs load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Prefs p = new Prefs();
        p.continuous = sp.getBoolean("continuous", p.continuous);
        p.idleTimeoutSec = sp.getInt("idleTimeoutSec", p.idleTimeoutSec);
        p.volume = sp.getInt("volume", p.volume);
        p.tone = sp.getInt("tone", p.tone);
        p.wakeAck = sp.getBoolean("wakeAck", p.wakeAck);
        p.sens = sp.getInt("sens", p.sens);
        p.wakeWord = sp.getString("wakeWord", p.wakeWord);
        p.localWake = sp.getBoolean("localWake", p.localWake);
        if (p.wakeWord == null || p.wakeWord.trim().length() == 0) p.wakeWord = "小智";
        return p;
    }

    public void save(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("continuous", continuous)
                .putInt("idleTimeoutSec", idleTimeoutSec)
                .putInt("volume", volume)
                .putInt("tone", tone)
                .putBoolean("wakeAck", wakeAck)
                .putInt("sens", sens)
                .putString("wakeWord", wakeWord)
                .putBoolean("localWake", localWake)
                .apply();
    }
}
