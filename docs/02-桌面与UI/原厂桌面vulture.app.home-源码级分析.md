# 原厂桌面 `vulture.app.home` 源码级分析（XYlinkHome）

> 分析日期：2026-11-03
> 目标：搞清楚「为什么麦克风一直被占」「为什么喊小度小度没反应」，并给出可执行的接管方案
> 方法：解包工具 全量解析 + 解包工具 资源解码 + 逐调用链人工追踪
> 产物路径：`$HOME/re-tools/t70l/apks/vulture/src/sources/`（5918 个 `.java`，32 MB）

---

## 0. 一句话结论（先看这个）

**麦克风不是"被某个 bug 占着"，是原厂写在构造函数里的设计。**

`vulture.app.home` 是**小鱼易连的会议终端桌面**（XYlinkHome）。它的音频模块在**实例化的那一刻**就无条件打开 48 kHz 立体声 `VOICE_COMMUNICATION` 录音，并且**整个包内没有任何一处调用停止它**。这不是泄漏，是会议终端的常态：它要随时能开会、随时能语音唤醒。

同时——

**原厂本来就有一套完整的语音助手，但它不认"小度小度"。它的唤醒词是「你好问问」和「嗨小问」。**

这就是为什么喊了这么久一直没反应：**词喊错了**。我们自己的 BDS 引擎（认"小度小度"）又抢不过它的麦克风。

---

## 1. 分析目标确认

| 项 | 值 |
|---|---|
| APK 文件 | `firmware/apps/main_release_2.24.7-1583.apk`（19 MB） |
| 包名 | `vulture.app.home` |
| 应用名 | XYlinkHome |
| versionName / Code | 2.24.7-1583 / 22407 |
| minSdk / targetSdk | **19 / 21** |
| 权限总数 | 37 个（含 `RECORD_AUDIO`、`MODIFY_AUDIO_SETTINGS`） |
| 组件 | 55 activity / 8 service / 8 receiver / 1 provider |
| dex | 单文件 `classes.dex`，6.4 MB |
| 原生库 | 10 个 `.so`，最大 `libcallmodule_aw.so` 11 MB（音视频会议引擎） |

> 同一固件里还有 `vulture.app.assistant`（68 KB，助理壳）、`vulture.network_service`（1.2 MB）。
> 语音引擎本体不在这个 APK 里，见第 4 章。

---

## 2. 决定性问题：麦克风为什么一直被占

### 2.1 完整调用链（从进程启动到独占 ALSA 设备）

```
VultureService.initModules()                       ← service/VultureService.java:116
  └─ mModules.add(new C5215w(ctx, codecType))      ← 第 144 行，装配"音频模块"
       └─ ModuleContainer 回调模块的 setContainer()
            └─ C5215w.setContainer()               ← module/audio/C5215w.java:59
                 └─ new C5218z(ctx, cb)            ← 第 61 行
                      └─ new C5211s(ctx, this)     ← module/audio/C5218z.java:46
                           └─ C5211s 构造函数
                                ├─ startPlay()     ← C5211s.java:68  ★ 同时打开播放通路
                                └─ startCapture()  ← C5211s.java:70  ★★ 麦克风就此打开
                                     └─ new AudioRecord(7, 48000, 12, 2, buf)
```

### 2.2 关键代码（原文）

**① 构造函数里直接开录** —— `module/audio/C5211s.java:67-71`

```java
this.f13734d = C5213u.m15500b(parseInt, parseInt2);
this.f13734d.startPlay();        // 打开播放
this.f13733c = C5213u.m15498a(parseInt, parseInt2);
this.f13733c.startCapture();     // ★ 麦克风立即打开
this.f13741k = EnumC5212t.Running;   // 状态直接置为 Running
```

注意：**没有条件判断，没有用户操作触发，new 出来就录。**

**② AudioRecord 的精确参数** —— `module/audio/C5182a.java:110-131`

```java
private AudioRecord m15401a(int i2) {
    int i3 = 16;
    if (this.f13652h == 2) {          // f13652h 默认 = 2（立体声）
        i3 = 12;                       // AudioFormat.CHANNEL_IN_STEREO = 12
    }
    int minBufferSize = AudioRecord.getMinBufferSize(48000, i3, 2);
    this.f13649e = (((this.f13652h * 48000) * 2) * 20) / 1000;   // 20ms 一帧 = 3840 字节
    this.f13648d = new byte[this.f13649e];
    int i4 = this.f13649e > minBufferSize ? this.f13649e + minBufferSize : minBufferSize * 2;
    this.f13647c = new AudioRecord(i2, 48000, i3, 2, i4);
    ...
}
```

| 参数 | 值 | 说明 |
|---|---|---|
| `audioSource` | **7** | `MediaRecorder.AudioSource.VOICE_COMMUNICATION`（`f13646b = 7`，第 19 行） |
| `sampleRate` | **48000** | 硬编码，不读 `OUTPUT_SAMPLE_RATE` 环境值 |
| `channelConfig` | **12** | `CHANNEL_IN_STEREO`（双声道） |
| `audioFormat` | **2** | `ENCODING_PCM_16BIT` |
| `bufferSize` | 3840 + minBuffer | 20 ms 音频 + 系统最小缓冲 |

> **`VOICE_COMMUNICATION` 是重点。** 这个 source 在 Android 音频策略里属于"通话类"，优先级最高，会抢占其他录音，并且通常伴随 `setMode(MODE_IN_COMMUNICATION)`。这比普通的 `MIC` 更霸道——即使别的 App 在录，它也能把对方顶掉。

**③ 芯片级验证（我方实测）**

```
$ dumpsys media.audio_flinger
Input thread: 48000 Hz, 1 ch, 16 bit     ← 请求是立体声，HAL 上报单声道
  Client: PID 23279 = vulture.app.home
$ cat /proc/22655/fd | grep pcm
35 -> /dev/snd/pcmC0D0c      ← 录音
xx -> /dev/snd/pcmC0D0p      ← 播放（同进程同时占着两条通路）
```

这与源码完全对上：`startPlay()` + `startCapture()` 同时调用，所以它同时持有 input 和 output。

### 2.3 为什么"永不释放"

| 机制 | 证据 | 后果 |
|---|---|---|
| 全包**只有一处** `new AudioRecord` | `grep -rn "new AudioRecord"` 仅命中 `C5182a.java` | 录音入口唯一，没有第二套 |
| 全包**没有** `MediaRecorder` | `grep -rn "new MediaRecorder"` 零命中 | 不存在"另一条录音路" |
| `stopCapture()` 只在 `destroy()` 里调用 | `C5211s.java:78`、`C5218z.java:55` | 只有音频模块被销毁才停 |
| 而模块销毁只在 `C5215w.destroy()` | `C5215w.java:76` | 由模块容器统一销毁，正常运行时不会发生 |
| Service 返回 `START_STICKY` | `VultureService.onStartCommand` → `return 2` | 被杀自动重建，重建即重开录音 |
| 另有 `vulture.watchdog` 守护 | `NemoWatchdog.apk` + `vulture.app.home` 自身的 watchdog 进程 | 双重保活 |

**结论：只要桌面 App 活着，麦克风就被它占着，直到关机。** 从开机那一刻起（实测 `mediaserver` 自 06:10 起持有），到我们手动干预为止。

### 2.4 有没有开关？—— 没有

我把所有相关开关都找了一遍：

| 可疑开关 | 实际作用 | 能否关掉录音 |
|---|---|---|
| `enableSpeechModule(boolean)` | 只停语音识别（`SpeechModule.java:232`） | ❌ 录音在音频模块，不在这里 |
| `enableAudioRecord(boolean)` | 只是 **dump 到文件**（`SpeechModule.java:335`） | ❌ 名字有误导性，不是开关录音 |
| `VoiceAssitantSettting.enableVoiceAssitant` | 只控制语音助手启停 | ❌ |
| `setMute(boolean)` | `NativeDataSourceManager.setMute` —— 只影响会议上行 | ❌ 不释放设备 |
| `pauseAudioSystem()` | 会议内暂停（`C5211s.java:253`） | ⚠️ 能停，但需要会议流程触发 |

**唯一能让它松手的方法，是让 `AudioRecord` 构造失败** —— 也就是把 `/dev/snd/pcmC0D0c` 的权限改成 root 独占（我们已实施的做法）。

---

## 3. 为什么喊"小度小度"永远没反应

### 3.1 唤醒词真相

原厂语音引擎的唤醒模型在 **`MarlinSpeech.apk` 的 `res/raw/hotword`**（2,192,945 字节，本身是个 ZIP）：

```
$ unzip -l res/raw/hotword
  Length   Name
  386761   hixiaowen_auto
  386761   hixiaowen_home
  323719   hixiaowen_mini
  323719   hixiaowen_mini2_adult
  323683   hixiaowen_mini2_child
  165562   hixiaowen_vpa
   79610   hixiaowen_wear
  165702   nihaowenwen_auto
  165702   nihaowenwen_home
  165702   nihaowenwen_mini
  165702   nihaowenwen_mini2_adult
  165702   nihaowenwen_mini2_child
  165702   nihaowenwen_vpa
   54846   nihaowenwen_wear
   46560   oktico_wear
```

| 文件名前缀 | 唤醒词 | 说明 |
|---|---|---|
| `nihaowenwen_*` | **你好问问** | 出门问问（Mobvoi）标准唤醒词 |
| `hixiaowen_*` | **嗨小问** | 同上，另一套发音模型 |
| `oktico_wear` | OK Tico | 手表版，与 T70L 无关 |

**唤醒词是"烧"进 LSTM 权重里的，不是配置文件。** 全库搜不到任何 `hotword_text` 或中文唤醒词字符串（这一点上次分析 `MarlinSpeech.apk` 已确认，见 `小度音箱-语音栈静态分析分析.md` 第 340 行）。

### 3.2 所以现状是"三方争一个麦克风"

| 参与方 | 认什么词 | 麦克风状态 | 结果 |
|---|---|---|---|
| 原厂语音助手（桌面 + MarlinSpeech） | 你好问问 / 嗨小问 | **持有** | 你喊"小度小度"，它听不懂 |
| 我们的 `duer_wake`（百度 BDS 引擎） | 小度小度 | **抢不到** | 引擎空转，单核 100% 烧了 10 小时 |
| 我们的 `tinycap` | — | 靠 root 600 权限硬抢 | 一旦抢到，原厂语音就死了 |

**这就是全部矛盾的根源。** 不是算法问题，不是增益问题，是**两个主人争一条麦克风，而且各自只认自己的词**。

---

## 4. 原厂语音助手完整架构

### 4.1 两层结构（客户端 + 引擎，跨进程 AIDL）

```
┌─ vulture.app.home（桌面，本 APK）──────────────────────┐
│  C5211s  → AudioRecord(source=7, 48k, stereo)          │
│    ↓ 20ms/帧                                            │
│  C5201i  → 音频分发中心（立体声→单声道）                 │
│    ↓                                                    │
│  SpeechModule.putData()  → VoiceRecognitionProcessor    │
│    ↓ 48k→16k 重采样 (mResampler)                        │
│    ├─ mWakeup.writeAudio(16k PCM)  ──── AIDL ────┐      │
│    └─ mRecognizer.putData(16k PCM) ──── AIDL ────┤      │
│                                                   │      │
│  VoiceRecognitionProcessor.understand()           │      │
└───────────────────────────────────────────────────┼──────┘
                                                    ↓
┌─ MarlinSpeech.apk（引擎，独立进程）───────────────────────┐
│  com.ainemo.speech.intent.action.service.wakeup    │      │
│    → XYWakeupService → libwakeup.so（LSTM，400点/帧）     │
│  com.ainemo.speech.intent.action.service.asr              │
│    → AsrService → libmobvoi_recognizer + Kaldi/Vosk      │
│  com.ainemo.speech.intent.action.service.tts              │
│    → libtts.so（4.9 MB）                                  │
│  com.ainemo.speech.intent.action.service.understand       │
│    → 语义理解 / OfflineQueryAnalyzer                      │
└───────────────────────────────────────────────────────────┘
```

### 4.2 四个 AIDL 绑定点（`com/ainemo/speech/client/`）

| 客户端类 | 绑定 action | 作用 |
|---|---|---|
| `SpeechWakeup` | `com.ainemo.speech.intent.action.service.wakeup` | 常驻唤醒 |
| `SpeechAsr` | `com.ainemo.speech.intent.action.service.asr` | 语音识别 |
| `SpeechTts` | `com.ainemo.speech.intent.action.service.tts` | 语音合成 |
| `SpeechUnderstand` | `com.ainemo.speech.intent.action.service.understand` | 语义理解 |

四个 action **全部由 `MarlinSpeech.apk` 提供**（已用 aapt 在固件全部 APK 中比对，唯一命中）。

### 4.3 音频喂入的精确条件

`VoiceRecognitionProcessor.putData()`（第 908-940 行）：

```java
private void putData(byte[] bArr, int i2, int i3) {
    if (i3 == 16000) {
        bArr2 = bArr;
    } else if (i3 == 48000) {
        ...
        i2 = mResampler.resampling(bArr, i2, bArr2, (i2 + 2) / 3);   // 48k→16k
    }
    if (i2 > 0) {
        if (mWakeup != null && mInWakeupListening && C5386b.m16294r().isStandardUINemo()) {
            mWakeup.writeAudio(bArr2, i2);        // ★ 喂给唤醒引擎
            add2SaveAudioData(bArr2);
        }
        if (mRecognizer != null && isRecognizing && !isRecognizeingAfterWakeup) {
            mRecognizer.putData(bArr2, i2);       // ★ 喂给识别引擎
        }
    }
}
```

三个硬条件：
1. `mWakeup != null` —— AIDL 已连上
2. `mInWakeupListening == true` —— 处于唤醒监听态
3. **`C5386b.m16294r().isStandardUINemo()`** —— 必须是"标准 UI 版"固件

第 3 条很重要：如果这台的 UI 形态不是 `standardUINemo`，**唤醒引擎根本收不到数据**，喊什么都没用。

### 4.4 启停开关

`SpeechModule.setContainer()`（第 149-166 行）：

```java
this.mVoiceProcessor.init();
if (this.isEnable) {                      // isEnable 来自配置
    this.mVoiceProcessor.startSpeechUtils();
    C5201i.m15443a().m15446a(C5201i.f13699a, this);   // 注册为音频消费者
    this.isRunning = true;
}
```

`isEnable` 来源：`C5386b.m16294r().getVoiceAssitantSetting()` → JSON → `VoiceAssitantSettting.enableVoiceAssitant`。

```java
public class VoiceAssitantSettting {
    public boolean enableVoiceAssitant   = true;    // 默认开
    public boolean collectWakeupData     = true;
    public float   wakeupLowThreshold    = 0.4f;
    public float   wakeupHighThreshold   = 0.7f;
}
```

**默认值是 `true`** —— 也就是说原厂语音助手默认就是开的。

---

## 5. 音频数据分发中心 `C5201i`（最有价值的接管点）

这是全包唯一一个"音频集市"：谁注册进来，谁就能白拿麦克风数据，**完全不需要自己打开音频设备**。

`module/audio/C5201i.java:60`

```java
public void m15450a(byte[] bArr, int i2, int i3, int i4, long j2) {
    if (this.f13701c.size() == 1) m15449a(bArr);       // 单消费者时走 AEC 预处理
    if (i4 == 2) {                                     // 立体声
        int i5 = i2 >> 1;
        // ... 分配缓冲
        NativeDataSourceManager.stereo2mono(bArr, this.f13702d, this.f13703e);  // 拆左右声道
    } else {
        this.f13702d = bArr; this.f13703e = bArr;
    }
    // 语音模块拿「单声道」
    InterfaceC5202j speech = this.f13701c.get(f13699a);   // f13699a = "vulture.module.speech.source.id"
    if (speech != null) speech.putData(f13699a, this.f13703e, i5, i3, j2);
    // 其他消费者拿「原始」
    for (String str : this.f13701c.keySet()) {
        if (!str.equals(f13699a)) {
            InterfaceC5202j l = this.f13701c.get(str);
            if (l != null) l.putData(str, this.f13702d, i5, i3, j2);
        }
    }
}
```

**注册接口只有一个**：

```java
public void m15446a(String sourceId, InterfaceC5202j listener)   // 对应第 46 行
public void m15445a(String sourceId)                             // 注销
```

其中 `InterfaceC5202j` 的签名是：

```java
boolean putData(String sourceId, byte[] pcm, int length, int sampleRate, long timestamp);
```

**意义**：如果我们能把一个 `InterfaceC5202j` 塞进 `f13701c` 这个 Map，就能直接拿到 48 kHz 原始 PCM，**不碰 ALSA、不抢设备、原厂功能一点不受影响**。这是所有方案里最干净的一个。

---

## 6. 智能家居命令接口（最快见效的接入点）

原厂语音助手识别出智能家居命令后，**会把命令 JSON 广播出去**，等外部 App 执行。

### 6.1 两个 action

`module/speech/SpeechValues.java:70-75`

```java
public static final String SMART_HOME_COMMAND_INTENT     = "com.ainemo.intent.smarthome.action.command";
public static final String SMART_HOME_COMMAND_INTENT_KEY = "SmartHomeCommand";
public static final long   SMART_HOME_COMMAND_TIMEOUT    = 10000;   // 10 秒超时
public static final String SMART_HOME_RESULT_INTENT      = "com.ainemo.intent.smarthome.action.result";
public static final String SMART_HOME_RESULT_INTENT_KEY  = "SmartHomeResult";
```

### 6.2 发送实现（两种都支持！）

`VoiceRecognitionProcessor.java:2042-2085`

```java
private void sendSmartHomeCommandBroadCast(SmartHomeSpeechAnswer answer) {
    mCurrentSmartHomeSeq = new JSONObject(new JSONTokener(answer.getCommand())).getInt("seq");
    Intent intent = new Intent(SpeechValues.SMART_HOME_COMMAND_INTENT);
    intent.putExtra(SpeechValues.SMART_HOME_COMMAND_INTENT_KEY, answer.getCommand());
    mContext.sendBroadcast(intent);            // ★ 广播方式
    handler.sendEmptyMessageDelayed(45, 10000);   // 10 秒内没回结果就超时
}

private void sendSmartHomeCommand(SmartHomeSpeechAnswer answer) {
    ...
    if (mContext.startService(intent) != null) {     // ★ startService 方式
        handler.sendEmptyMessageDelayed(45, 10000);
    } else {
        mLogger.warning("can not find service for com.ainemo.intent.smarthome.action.command");
    }
}
```

### 6.3 数据格式

`command` 是 **JSON 字符串**，至少包含 `seq`（序号，用于回执对账）：

```json
{"seq": 12, ...}     // 具体字段由语义层生成，见 小度音箱-语音栈静态分析分析.md 第 5 章
```

结果回传：`sendBroadcast("com.ainemo.intent.smarthome.action.result", "SmartHomeResult" -> json)`
然后 `SpeechModule.mReceiver`（第 43-57 行）收到后转给 `mVoiceProcessor.onSmartHomeResult(string)`。

### 6.4 为什么这条路最好

| 优势 | 说明 |
|---|---|
| 零侵入 | 不改原厂 APK、不抢麦克风、不影响会议功能 |
| 现成语义 | 唤醒 → 识别 → 语义理解全由原厂完成，我们只接收结果 |
| 官方设计意图 | 这个 action 本来就是给"外部智能家居 App"用的（`smartHomeController` 是空类，说明原厂期望外部实现） |
| 可回执 | 支持 `result` 回传 + 10 秒超时，能做"确认式控灯" |

**注意**：原厂 `smartHome/smartHomeController.java` 是个**空类**：

```java
package vulture.module.speech.smartHome;
public class smartHomeController { }
```

这是明确信号——**原厂把智能家居留了个空壳，等着外部 App 来实现**。

---

## 7. 三种接管方案对比

| # | 方案 | 原理 | 优点 | 缺点 | 工作量 | 推荐度 |
|---|---|---|---|---|---|---|
| **A** | **接管 smarthome 广播** | 写一个轻量 Service/Receiver 监听 `com.ainemo.intent.smarthome.action.command`，收到 JSON 就转 Mesh 控灯 | 零侵入、不抢麦、保留全部原厂功能、有回执机制 | 唤醒词仍是"你好问问"；依赖原厂语义层能出"开灯"命令 | 小（半天） | ⭐⭐⭐⭐⭐ |
| **B** | **Frida hook `C5201i.m15450a`** | 在运行时 hook 音频分发函数，把 PCM 复制一份喂给我们的 `duer_wake` | 保留原厂录音，同时让我们的引擎也能听到"小度小度" | 需要 armv7 版 frida-server；需 root（已有）；每次开机要重挂 | 中（1~2 天） | ⭐⭐⭐⭐ |
| **C** | **锁死 pcm 节点（现状）** | `chmod 600 /dev/snd/pcmC0D0c`，让原厂 AudioRecord 构造失败，我们独占 | 我们的引擎 100% 拿到麦克风，已验证能唤醒"小度小度" | **原厂语音助手彻底死掉**；需要每次开机重设权限 | 已实现 | ⭐⭐⭐ |
| D | 改 APK 重打包 | 在 `C5201i` 注册自己的 listener 并重签名 | 最彻底 | 破坏签名（`libainemo_signature.so` 有校验风险）；固件升级会丢 | 大 | ⭐⭐ |
| E | 替换唤醒模型 | 把 `MarlinSpeech` 的 `hotword` 换成"小度小度"模型 | 最理想 | 需要训练 LSTM 唤醒模型，我们没数据、没工具链 | 极大 | ⭐ |

**我的建议：先做 A，同时保留 C 作为备份（两条链路互补）。**

理由：
- A 让"你好问问"这条路立刻能用，而且是官方的、稳定的、有回执的
- C 让"小度小度"这条路也能用，但会牺牲原厂语音
- 两者不能同时开（抢麦克风），但可以做成**运行时二选一**

如果一定要"小度小度"，B 是唯一能两全的路。

---

## 8. 现在就能做的验证（不需要改任何东西）

### 8.1 第一步：换唤醒词

设备正常运行时，对着它说：

> **「你好问问」**

间隔 1 秒再说：

> **「嗨小问」**

如果原厂链路是活的，屏幕会切到 `SpeechActivity`（横屏语音界面），并有一声提示音。
这能一次性确认：**音频链路通不通、唤醒引擎活不活、UI 有没有反应**。

### 8.2 第二步：看日志确认唤醒走了哪条路

```sh
adb shell logcat -s SP-SpeechModule SP-SpeechBaseUtil SP-VoiceRecognitionProcessor
```

| 日志 | 含义 |
|---|---|
| `onServiceConnected com.ainemo.speech.intent.action.service.wakeup` | AIDL 已连上 MarlinSpeech |
| `onWakeupResult score=...` | **唤醒命中**，score 越高越确定 |
| `onServiceDisconnected` | 引擎进程挂了 |
| `can not find service for com.ainemo.intent.smarthome.action.command` | 语义层出了命令，但没人接 → 正是我们要补的坑 |

### 8.3 第三步：抓命令广播（零成本确认 A 方案可行）

```sh
# 用 logcat 看原厂有没有发 smarthome 命令
adb shell logcat | grep -iE "smarthome|SmartHomeCommand|send broadcast"
```

如果说完"开灯"后能看到 `send broadcast with {"seq":...}`，**A 方案立刻成立**，我们只需要一个接收器。

---

## 9. 未解问题（需要设备在线才能推进）

| # | 问题 | 卡在哪 | 解法 |
|---|---|---|---|
| 1 | `isStandardUINemo()` 在本机返回什么？ | 需读 SharedPreferences / 跑一次 | 解析 `C5386b` 或直接 dump 配置 |
| 2 | 原厂语义层能否产出"开灯"命令 | 需实际喊一次看日志 | 第 8.3 步 |
| 3 | `command` JSON 的完整字段 | 静态分析只看到 `seq` | 抓到一条真实广播即可 |
| 4 | `libainemo_signature.so` 校验什么 | 未深入（方案 D 才需要） | 用 静态分析 逆 `.so` |
| 5 | 是否有 armv7 版 frida-server | 本机只有 `arm64` 版 | 下载 `frida-server-17.x-android-arm` |

---

## 10. 关键类索引（改代码时直接跳）

### 10.1 录音链路

| 类 | 文件 | 关键行 | 作用 |
|---|---|---|---|
| `C5182a` | `module/audio/C5182a.java` | 110-131 | **唯一** `new AudioRecord` 的地方 |
| `C5213u` | `module/audio/C5213u.java` | 8 | 工厂：OpenSL 可用则用 OpenSL，否则用它 |
| `C5211s` | `module/audio/C5211s.java` | 67-71 | AudioHandler 构造函数，**开录的地方** |
| `C5218z` | `module/audio/C5218z.java` | 43-49 | 持有 AudioHandler |
| `C5215w` | `module/audio/C5215w.java` | 59-72 | 音频模块，`setContainer` 触发创建 |
| `C5201i` | `module/audio/C5201i.java` | 46, 60-91 | **音频分发中心**（接管点） |
| `InterfaceC5202j` | `module/audio/InterfaceC5202j.java` | 全文 | 音频消费者接口（`putData`） |
| `OpenSlCapture` | `module/audio/OpenSlCapture.java` | 全文 | OpenSL ES 录音实现（备选路径） |

### 10.2 语音链路

| 类 | 文件 | 关键行 | 作用 |
|---|---|---|---|
| `VultureService` | `service/VultureService.java` | 116, 144 | 模块装配总入口 |
| `SpeechModule` | `module/speech/SpeechModule.java` | 59-67, 149-166, 224-230 | 语音模块（音频消费者） |
| `VoiceRecognitionProcessor` | `module/speech/VoiceRecognitionProcessor.java` | 908-940, 2042-2085 | 语音处理核心（106 KB） |
| `SpeechValues` | `module/speech/SpeechValues.java` | 全文 | 全部常量与消息号 |
| `VoiceAssitantSettting` | `module/speech/VoiceAssitantSettting.java` | 全文 | 语音助手开关（默认 true） |
| `smartHomeController` | `module/speech/smartHome/smartHomeController.java` | 全文 | **空类**，留给外部实现 |
| `SpeechActivity` | `app/home/speech/activity/SpeechActivity.java` | — | 语音 UI（action `nemo.itent.action.activity.SPEECH`） |

### 10.3 AIDL 客户端

| 类 | action |
|---|---|
| `com/ainemo/speech/client/wakeup/SpeechWakeup.java` | `com.ainemo.speech.intent.action.service.wakeup` |
| `com/ainemo/speech/client/asr/SpeechAsr.java` | `com.ainemo.speech.intent.action.service.asr` |
| `com/ainemo/speech/client/tts/SpeechTts.java` | `com.ainemo.speech.intent.action.service.tts` |
| `com/ainemo/speech/client/understand/SpeechUnderstand.java` | `com.ainemo.speech.intent.action.service.understand` |

---

## 11. 复现方法（换机/重装后如何重做这份分析）

```sh
# 1. 从固件取 APK（或从设备拉）
cp firmware/apps/main_release_2.24.7-1583.apk vulture.app.home-2.24.7.apk

# 2. 解析成 Java 源码（需要 JDK 21）
export JAVA_HOME=$HOME/re-tools/jdk-21.0.12.1+1/Contents/Home
$HOME/re-tools/解包工具/bin/解包工具 -d src --deobf --no-res --threads-count 6 vulture.app.home-2.24.7.apk

# 3. 解码资源与 manifest
$JAVA_HOME/bin/java -jar $HOME/re-tools/解包工具.jar d -f -s -o apktool_out vulture.app.home-2.24.7.apk

# 4. 快速定位录音点
grep -rn "new AudioRecord" src/sources/
grep -rn "startCapture()" src/sources/
```

**注意**：`rea.sh analyze` **不能**直接吃 APK/dex（静态分析工具 只接可执行文件），会报 `target_kind_unsupported: archive`。APK 层用 解包工具 + 解包工具，`.so` 层再用 静态分析（静态分析工具）。

---

## 12. 变更记录

| 时间 | 变更 |
|---|---|
| 2026-11-03 09:40 | 首次建档：完成全量解析（5918 文件）、录音链路源码级定位、唤醒词真相、三种接管方案对比 |
