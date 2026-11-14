# 07 · 小智 App（长对话版）—— 实现逻辑与交接（2026-11-06）


---

## 一、它和旧版（com.lm.xiaoai）有什么不一样


**为什么敢把 AIVS 拿掉**：AIVS 的 `access_token` 3 天过期（原来那份 11-05 13:42 生成，
11-08 就会失效，届时整个唤醒会直接哑掉），而且判唤醒要多一次网络往返。
现在唤醒判断就用小智云返回的 `stt` 文字，少一跳、也不再怕 token 过期。

---

## 二、一轮对话的完整旅程

```
① 常驻录音 48k 立体声（VOICE_COMMUNICATION）
   每 100ms 一帧，全部先进 600ms 环形缓冲（★ 见"坑1"）
   VAD：rms > max(底噪×灵敏度倍数, 绝对门槛) 连续 2 帧 = 开口

② 开口瞬间：比较 L/R 取更响声道；按 RMS 算增益归一化（3277/rms，夹在 1~12 倍）

③ 边说边发：48k→16k 单声道（3:1 抽取+增益）→ Opus 60ms 帧 → 直接怼给小智云
   （不等说完，省掉录音那段时间）

④ 静音 400ms → listen stop → 等 stt / tts
   ★ 识别文字一到就先做判断，答案音频先缓着不放：
      · 含"开灯/关灯" → 本地网关执行（约 300ms），其余不发
      · 只喊了"小智" → 播本地"在呢"（几十毫秒），开连续对话
      · 没喊"小智"且不在会话里 → abort 掉，什么都不播
      · 会话中 → 直接放行

⑤ 回答播完（tts state=stop）：
   · 连续对话开着 → 保持这条连接，立刻回到"听"，静默 N 秒才真正结束
   · 关着 → 关连接回待机
```

**关键：一条 WebSocket = 一段会话。** 服务端按 `session_id` 保存上下文，
所以同一个连接里连续问"今天天气"→"那明天呢"，它知道"那明天"指的是天气。
（Mac 端 Python 实测 5 轮不丢：`/tmp/xz_test_multiturn.py`）

---

## 三、源码地图（`小智App/app/src/com/lm/xiaozhi/`）


**设置项**（都在 `Prefs`）：连续对话开关、静默超时 15/25/35/60 秒、音量 0–15、
音色（低沉/标准/清亮，播放端变速 + 音调）、唤醒灵敏度（高/中/低）、只要"在呢"不要。

---

## 四、编译与部署（换人必看）

```bash
cd ~/Desktop/中移T70L/小智App
bash deploy.sh          # 编译 → 卸载旧包 → 安装 → 清缓存 → 起服务
```

`deploy.sh` 里那几步每一个都有血泪原因：

1. **必须"先卸再装"**。T70L 是 Android 4.4，`adb install -r` 覆盖安装有时**不换 dex**，
   装完还是旧类 → 运行时报 `ClassNotFoundException`。
2. **绝对不要删 `/data/dalvik-cache/data@app@com.lm.xiaozhi-1.apk@classes.dex`**。
   删了之后 Dalvik 想自己重建，但 `/data/dalvik-cache` 是 `drwxrwx--x system system`，
   应用没有写权限 → `Dex cache directory isn't writable` → 一启动就崩溃，
   桌面上会不停弹"很抱歉，小智已停止运行"。**唯一解**：卸载重装（让 installd 重新 dexopt）。
3. **构建脚本不能吞 javac 错误**。曾经的写法是
   `javac ... | grep -v 注:`，`grep` 没输出就返回 1，被 `set -e` 吃掉，
   于是"编译失败"被当成"编译通过"，d8 打出来的 dex 少了 `XzService`，
   装上就是 `ClassNotFoundException`。现在改成把日志落盘 + 显式判返回码。

---

## 五、踩坑实录（这一版新增的，别重犯）

**坑A · 唤醒词被吃掉（最隐蔽）**
VAD 只把"安静帧"放进环形缓冲，刚开口那 1~2 帧（200ms）既不入环也不发出去，
正好丢掉"小智"两个字 → ASR 得到"今天天气怎么样？"，永远判不出唤醒词。
修法：**每帧都先入环，再判 VAD**，开口时把整环（600ms）按顺序发出去。

**坑B · auto 模式把句子切碎**
`listen start mode=auto` 让服务端自己断句，实测它会把电视里的长句切成
"两毕。""啊，所入。"这种碎片，识别质量暴跌。**改回 `manual`**（由我们发 stop 决定说完），
整句识别立刻干净。auto 唯一的优点是会"边听边出字幕"，但不值得。

**坑C · 读超时把好连接判死**
握手后 `soTimeout=12s`，会话里一旦 12 秒没数据就抛 `SocketTimeoutException`，
reader 线程退出 → 会话被杀。修法：**握手成功后 `setSoTimeout(0)`**，靠 `close()` 打断。

**坑D · 用峰值算增益，一个爆音毁一句**
开口那一帧如果有回声/关门声，峰值直接顶到 32768，增益被算成 1.0，
后面正常说话的电平就小得可怜，ASR 全废。修法：**按 RMS 归一**（`g = 3277/rms`，夹 1~12）。

**坑E · 电视/音箱把会话带跑**
连续对话里"听到就答"会把电视对白也接上。修法：**近场过滤**——
进入会话时记下那句的原始峰值 `sessionPeak`，之后低于 `max(2500, sessionPeak×0.35)` 的声音直接忽略。

**坑F · 两个 App 抢麦克风（最容易被误判成"坏了"）**
旧 `com.lm.xiaoai` 由 `wake_guard.sh` 每 30 秒保活，它一拿到录音，
新服务 `AudioRecord.read()` 全是零 → 没 VAD、没日志，看起来"彻底没反应"。
**判断方法**：`cat /proc/asound/card0/pcm0c/sub0/status`，看 `owner_pid` 是谁的。
现在旧包已被 `pm disable com.lm.xiaoai` 停用。

**坑G · 音频 HAL 卡住会拖死 onStartCommand**
`startForeground()` / `setStreamVolume()` 在 HAL 忙时会阻塞十几秒，
放在 `onStartCommand` 里直接 ANR（`Timeout executing service`）。
修法：两个调用各自丢进独立线程，`loop()` 立刻开跑。

---

## 六、实测记录（2026-11-06，Mac 扬声器当人声）

```
15:24:10  工作线程启动 / 麦克风就绪 / 会话建立 393ms / 播放器就绪 24000Hz
15:26:13  识别: 小智  → 播"在呢" → 进入连续对话（近场基准峰值 32768）
15:26:16  开口 rms=17262（会话中，免唤醒）
15:26:19  识别: 今天气怎么样？ → 回答: 辽阳现在是晴天，气温4度…（约 4s 出声）
15:26:35  识别: 那明天。      → 回答: 明天辽阳白天多云…（上下文接住了）
```


---

## 七、已知短板（别以为它完美）

1. **短唤醒词靠云端 ASR，天生不稳**。只喊"小智小智"（不到 1 秒、两个音节）时，
   云 ASR 有一定概率听成"3。""是。"这类结果。真实人声比 Mac TTS 好，但仍不是 100%。
   **根治只能上本地 KWS**（见 `04-小智-实现逻辑.md` §5 的三条死路与唯一活路：
   TFLite 2.4.0 + microWakeWord NON_STREAM）。
2. **电视声音大时会跟人抢**。灵敏度调到"中/低"能滤掉大部分，但电视对白一旦超过门槛，
   这一句仍会被当成一轮对话（会被判"没喊小智"丢掉，但会占用 1~2 秒听不见）。
3. **不支持打断**。它说话时麦克风整帧丢弃（防自激），要等它说完才能插话。
4. **会话跨度受服务端限制**。闲置连接会被服务端关掉（实测 60 秒左右）；
   我们这边的静默超时（25s）比它早，正常不会撞上。

---

## 八、回滚（很重要）

旧版一条命令就能回来：

```bash
adb shell "pm enable com.lm.xiaoai"
adb shell "sh /data/local/tmp/xz_guard.sh stop"      # 停小智守护
adb shell "am force-stop com.lm.xiaozhi"
adb shell "sh /data/local/tmp/wake_guard.sh start"   # 起旧守护
adb shell "pm disable com.lm.xiaozhi"                # 可选：把小智停用
```

`boot_init.sh` 里两处 `xz_guard` 改回 `wake_guard` 即可（原文件备份：
`/tmp/boot_init.sh.bak-20261106`，和设备上的 `boot_init.sh` 同源）。

---

## 九、设备当前状态（2026-11-06 15:30）


---

## 十、2026-11-06 下午追加：唤醒词自定义 + 五档音色 + 自愈

### 10.1 唤醒词可以随便改（真能改，不是噱头）

唤醒判断不是本地模型，而是**拿小智云返回的 `stt` 文字去匹配**，所以改唤醒词只是改一个字符串，
不需要重新训练任何模型：


**代价**：云端 ASR 对两个音节的短词本来就容易听错。实测 Mac 合成音"小智小智"偶尔被识别成
"嗯。""3。"——真人说话会好一些，但这不是 100%。要根治只能上本地 KWS。

### 10.2 音色：五档，本机实时变调

云端那个嗓子是绑在智能体上的，**客户端协议里没有选音色的字段**（实测给 `listen` 塞
`voice`/`voice_id`/`speaker`/`tts_voice` 都不能可靠区分——LLM 每次回答内容都不一样，
音频指纹没法比较）。所以音色做成**播放端 DSP**：

| 档位 | 变速系数 | 听感 |
|---|---|---|
| 低沉男声 | 0.88 | 明显低一个头，语速略慢 |
| 沉稳 | 0.94 | 稍低、稍慢 |
| 标准 | 1.00 | 原声 |
| 清亮 | 1.07 | 亮一点、快一点 |
| 童声 | 1.14 | 明显尖、快 |

实现：`Pcm.Resampler`（线性插值 + 跨帧状态），对解码后的 24kHz PCM 逐帧重采样。

### 10.3 麦克风看门狗（新）

现象：ALSA `pcm0c` 状态是 `RUNNING`、`owner_pid` 也是我们，但 `AudioRecord.read()`
一个字节都不给，`xz-loop` 卡在 read 里 → "服务活着但永远没反应"。
对策：`lastFrameAt` 记最近一次读到数据的时间，看门狗每 4 秒查一次，超过 8 秒没数据就
`stopRec()` 让阻塞的 read 抛错退出，主循环自动重开录音。实测能自愈。

### 10.4 自带日志文件（排查必需）

设备上 NEMO_UI 疯狂刷日志，logcat 缓冲几秒就被冲干净，`logcat -s Xz` 经常什么都看不到。
现在所有日志同时写 `/data/data/com.lm.xiaozhi/files/xz.log`（保留 256KB，环形截断），

```bash
adb shell "cat /data/data/com.lm.xiaozhi/files/xz.log"
```

还有每 10 秒一条 `[空闲] rms=… 底噪=… 阈值=…`，一眼能看出 VAD 是否在工作、门槛是否合适。

### 10.5 部署脚本的两个死坑（本轮又踩了一次）

1. **绝对不要在安装后删 `/data/dalvik-cache/data@app@com.lm.xiaozhi-1.apk@classes.dex`**。
   之前的 `deploy.sh` 里留着这一行，结果每次部署后 App 一启动就 `ClassNotFoundException`，
   桌面上不停弹"很抱歉，小智已停止运行"。目录是 `drwxrwx--x system system`，应用无权重建。
2. **installd 的 dexopt 会偶发崩溃**（logcat 里能看到 dexopt 的 DEBUG 崩溃栈），崩溃后缓存文件
   就不生成，同样表现为启动崩溃。所以 `deploy.sh` 现在装完会等缓存文件出现，最多重装 3 次。
