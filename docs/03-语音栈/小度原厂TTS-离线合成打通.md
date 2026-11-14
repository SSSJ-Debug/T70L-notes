# 小度原厂离线 TTS（ETTS）在 T70L 上打通 —— 完整分析记录

> 日期：2026-11-03 上午
> 目标：让 T70L 喊一声「小度小度」之后，用人声回一句「在呢」，音色要和真小度一样。
> 结论：**已打通**。`bd_etts_synthesis()` 在 T70L 上跑通，用小度原厂女声模型
> `bd_etts_speech_female.dat` 合成出 16kHz PCM，实测产出 4 条唤醒应答语。
>
> 一句话记住：**不是自己找 TTS 合成的，是小度本人的声库在 T70L 上跑起来了。**

---

## 0. 为什么非得走这条路

一开始想的是"找个中文 TTS 合成一句在呢"，但都不可行：

| 方案 | 问题 |
|---|---|
| Android 4.4 自带 Pico TTS | 根本不含中文 |
| macOS `say` 灌 wav | 音色是苹果的，跟小度不像；而且用户要"一模一样" |
| 在线 TTS | 要联网 + 要 key，唤醒应答必须离线零延迟 |
| 现成 wav | 小度固件 `appresources/` 里只有连接/蓝牙/音量提示音，**没有"在呢"** |

关键发现：小度的"在呢"是**云端 TTS 下发**的，本地没存。
但小度固件里带着**百度 ETTS 离线合成引擎 + 女声模型**，只要把这个引擎驱动起来，
就能让它在 T70L 上现说任何话 —— 音色天然一致。

---

## 1. 资产盘点

### 1.1 模型（`07_SDK官方包/output_path/resources/`）

| 文件 | 大小 | 作用 |
|---|---|---|
| `bd_etts_text.dat` | 4.7 MB | 文本前端（分词、多音字、韵律预测） |
| `bd_etts_speech_female.dat` | 324 KB | **女声声学模型**（小度的音色） |
| `bd_etts_speech_male.dat` | 378 KB | 男声（备选） |
| `common.res` | 487 KB | 公共资源 |
| `bds_license.dat` | 512 B | 授权 |

### 1.2 引擎（`01_固件/duer/lib/`）

| 文件 | 大小 | 说明 |
|---|---|---|
| `libDcsSdk.so` | 64 MB | 主库，ETTS 实体在这里（`bd_etts_*` 全在里面） |
| `libTTSManager.so` | 8.3 MB | TTS 管理器，**同时自带一份 ETTS**（这是后面的大坑） |

### 1.3 运行环境（**设备上已经有**）

`/data/local/tmp/xd/run/` 是一套完整的 ARM glibc 用户态（之前跑 BDS 唤醒引擎时建的）：

```
/data/local/tmp/xd/run/lib/
    ld-linux-armhf.so.3  libc.so.6  libdl.so.2  libm.so.6
    libpthread.so.0  librt.so.1  libstdc++.so.6
    libiconv.so.2  libcharset.so.1  libsqlite3.so.0
    libcurl.so.4  libssl.so.1.0.0  libcrypto.so.1.0.0  libz.so.1
    libDcsSdk.so
```

**这是能跑起来的前提** —— T70L 的 Android bionic 加载不了 glibc 编的 .so，
必须走这套 glibc 环境（duer_wake 也是这么跑的）。

---

## 2. 静态分析 逆出来的 API（可直接抄）

解析产物：`小度音箱分析/05_分析源码/libDcsSdk/functions/`

### 2.1 三个入口

```c
// 0xbc934c  —— 初始化，返回 0 表示成功
int bd_etts_engine_init(char *text_model_path,    // bd_etts_text.dat
                        char *speech_model_path,  // bd_etts_speech_female.dat
                        t_handle *p_hTTSHandle);  // 输出句柄

// 0xbb97d8  —— 设参数
int bd_etts_set_param(t_handle h, TTS_PARAM_TYPE type, unsigned long value);

// 0xbc9d0c  —— 合成
int bd_etts_synthesis(t_handle h, char *text, int text_len, void *user);
```

### 2.2 音频回调怎么挂（**最关键的一段**）

`bd_tts_session_set_param`（0xbc6208）里：

```c
if (eParamType == TTS_PARAM_OUTPUT_CALLBACK) {      // 枚举值 = 0
    if (uParamValue == 0) return 0x7d2;             // 2002 = 空指针错误
    *(ulong *)(hTTSHandle + 0x70) = uParamValue;    // ← value 就是函数指针本身
    g_wav_output = (callback_wav_output *)(hTTSHandle + 0x70);
}
```

**要点：第三个参数传的是回调函数地址，不是结构体地址。**
结构体是嵌在 handle 里的（从 +0x70 开始），引擎自己维护。

布局（由 `bd_etts_synthesis_control` 和 `bd_tts_callback_output_data` 交叉印证）：

| 偏移 | 字段 | 说明 |
|---|---|---|
| +0x70 | `m_pfnTTSSynthOutput` | 回调函数指针 |
| +0x74 | `m_pvUserParam` | 透传 user 参数 |
| +0x78 | `szWavBuf[0x1860]` | 内部 PCM 缓冲 |
| +0x190c | 另一个 user 参数 | |

回调签名（从 `0xd4c548` 的调用点读出）：

```c
int cb(char *pcm, int short_cnt, int word_num, void *user);
//  ★第二个参数是**采样点数(short)**，不是字节数。每块最多 0xc30 = 3120 shorts。
```

### 2.3 枚举值

`TTS_PARAM_TYPE` 在 DWARF 里没保留数值，但解析的分支顺序和百度公开的
`bd_tts.h` 完全一致：

```c
TTS_PARAM_OUTPUT_CALLBACK = 0,
TTS_PARAM_PERSON,  TTS_PARAM_VOLUME,  TTS_PARAM_SPEED,
TTS_PARAM_PITCH,   TTS_PARAM_VOCODER_OPTIM_LEVEL, TTS_PARAM_PCM_TYPE,
TTS_PARAM_DOMAIN_SYNTH, TTS_PARAM_OPEN_XML, ...
// 取值范围 0..0x14
```

可以用 `TTS_PARAM_PERSON` 切男女声、`TTS_PARAM_SPEED/PITCH/VOLUME` 调语速语调，
目前只用到了 0。

---

## 3. 踩的五个坑（按发生顺序，每个都是硬坑）

### 坑 1：`analyze` 吃不了 .so 的同名函数

静态分析 索引里 `bd_etts_engine_init` 有**两条**：
`functions/0x6ca428.c` 和 `functions/0xbc934c.c`。
前者是 PLT thunk，解析出来是**自己调自己**（无限递归），是假货。
真实现看 0xbc934c。**遇到同名符号要挑地址大的/在 .text 主段里的那个看。**

### 坑 2：交叉编译工具链默认静态，`dlopen` 用不了

`build.sh` 编 duer_wake 用的是 `-static`。但 TTS 必须 `dlopen` libDcsSdk，
一旦 `-static` 就会出现：

```
libdl.a(dlopen.o): undefined reference to `__dlopen'
```

修法（`build_tts.sh`）：

```sh
-Wl,-Bdynamic -ldl -lc \
-Wl,--dynamic-linker=/data/local/tmp/xd/run/lib/ld-linux-armhf.so.3
```

### 坑 3：Jessie sysroot 里只有 .a，没有动态库

`arm-toolchain/jessie/` 是**纯静态**的 sysroot：

- `libdl.so` 是个绝对软链 `-> /lib/arm-linux-gnueabihf/libdl.so.2`，在 Mac 上是断的
- `libc.so.6` / `libdl.so.2` **根本不存在**

解决：从设备把动态库拉回来，建一个 `arm-toolchain/jessie-dyn/lib/`：

```sh
adb pull /data/local/tmp/xd/run/lib/libc.so.6   .
adb pull /data/local/tmp/xd/run/lib/libdl.so.2  .
... (libm, libpthread, librt, libgcc_s, ld-linux-armhf)
```

然后**手工写一个相对路径的 `libc.so` 链接脚本**（不能是软链！）：

```
OUTPUT_FORMAT(elf32-littlearm)
GROUP ( libc.so.6 libc_nonshared.a AS_NEEDED ( ld-linux-armhf.so.3 ) )
```

`libc_nonshared.a` 要从 jessie sysroot 复制过来，否则报
`undefined reference to __libc_csu_init/fini`。

编译时 `-L$DL` 必须放在 `-L$L` **前面**，否则先命中 libdl.a 又回去了。

### 坑 4：`LD_PRELOAD` 缺一堆符号（逐个补）

libDcsSdk.so 的 `DT_NEEDED` 不全，`dlopen` 时报的错一个接一个：

| 报错 | 缺的是 |
|---|---|
| `undefined symbol: _ZN12duerOSDcsSDK...switchTtsSynthesizeEngineToneType` | `libTTSManager.so` |
| `undefined symbol: pthread_mutexattr_init` | `libpthread.so.0`（glibc 2.19 里还没并进 libc） |
| `undefined symbol: libiconv_open` | `libiconv.so.2` |

最终 `libTTSManager.so` 还依赖 libcurl/libssl/libcrypto/libsqlite3/libstdc++/libz，
索性全预加载：

```sh
export LD_PRELOAD="$LIB/libpthread.so.0 $LIB/libiconv.so.2 $LIB/libcharset.so.1 \
$LIB/libstdc++.so.6 $LIB/libsqlite3.so.0 $LIB/libcurl.so.4 \
$LIB/libssl.so.1.0.0 $LIB/libcrypto.so.1.0.0 $LIB/libz.so.1 $LIB/libTTSManager.so"
```

### 坑 5（最阴）：**符号插占** —— 初始化成功却返回 11

现象：

```
[3] bd_etts_engine_init -> 0, handle=0x1aae230   ← 成功
[4] set OUTPUT_CALLBACK -> 11                    ← 失败(0xb = 未初始化)
```

排查过程（值得记下来，思路比结论重要）：

1. `0xb` 在包装层 `bd_etts_set_param` 里只有一个来源：
   `if (s_g_etts_engine_runtime) return 0xb;`
2. 于是去 objdump 符号表找这个变量：
   `_ZL23s_g_etts_engine_runtime` 在 `.bss` VMA `0x0104da8a`（1 字节 bool），
   前一个字节 `0x0104da89` 是 `_ZL20s_g_etts_engine_init`。
3. 算出偏移 `0x0104da8a - 0x00bb934c = 0x49473E`，运行时读出来是
   **`init_flag=1, runtime_flag=0`** —— 锁是干净的！
4. 说明 11 不是锁来的，是**内部**返回的：
   `bd_tts_session_set_param` 里 `if (s_g_etts_engine_init) ... else return 0xb;`
   即它看到的 init_flag 是 0。
5. 但我读同一个地址明明是 1。**同地址不同值 → 不是同一个库的变量。**
6. 真凶：为了补 `switchTtsSynthesizeEngineToneType`，`LD_PRELOAD` 了
   `libTTSManager.so`，而它**自带一整套 ETTS**，把全局符号表里的
   `bd_etts_engine_init` / `bd_etts_set_param` 全占了。
   `dlsym(libDcsSdk)` 拿到 init 是 DcsSdk 的（句柄有效），
   `set_param` 却走进 TTSManager 那份（它自己的 init_flag 还是 0）。

**修法一行：**

```c
void *h = dlopen(libp, RTLD_NOW | RTLD_LOCAL | RTLD_DEEPBIND);
//                                  ^^^^^^^^^^^^^^^
```

`RTLD_DEEPBIND` 让 libDcsSdk 内部解析符号时优先查自己。改完立刻 `-> 0`。

> 这个坑的通用教训：**预加载一个"功能重叠"的库来补符号，会静默改变原有符号的指向。**
> 以后遇到"调用 A 却像走了 B 的逻辑"，先怀疑符号插占，用 RTLD_DEEPBIND 隔离。

---

## 4. 工具与用法

### 4.1 `tts_etts`（C，设备上运行）

源码：`$HOME/re-tools/t70l/duer/tts_etts.c`
编译：`$HOME/re-tools/t70l/duer/build_tts.sh`

```sh
# 设备上
sh /data/local/tmp/duer/tts.sh "在呢" /data/local/tmp/duer/voice/ans1.wav
```

`tts.sh` 已经封装好 LD_LIBRARY_PATH / LD_PRELOAD，结尾还会 `unset` 掉
（否则会污染 `ls` 等命令）。

输出是**标准 16kHz / 单声道 / 16bit WAV**，44 字节头 + PCM 数据。

### 4.2 实测产物（`/data/local/tmp/duer/voice/`）

| 文件 | 文本 | 时长 | 采样点 |
|---|---|---|---|
| ans1.wav | 在呢 | 948 ms | 15170 |
| ans2.wav | 我在呢 | 1330 ms | 21273 |
| ans3.wav | 在呢，你说 | 1929 ms | 30869 |
| ans4.wav | 嗯，你说 | 1308 ms | 20922 |

波形包络验证（ans1，每 100ms 一段）：
`0, 84, 841, 5272, 2966, 2405, 1980, 412, 0`
—— 典型双音节语音，"在"重、"呢"轻，前后有静音，是正常语音不是噪声。
峰值 12042（满量程 32767），所以播放时乘 2.4 倍增益。

桌面备份：`中移T70L/tts/在呢.wav`

### 4.3 合成耗时

引擎冷启动（加载 4.7MB 文本模型 + 324KB 声学模型）约 3~5 秒，
单句合成本身 1 秒内。**所以唤醒应答不走实时合成，全部预生成成 wav。**

---

## 5. 接进唤醒闭环

改动文件：`$HOME/re-tools/t70l/asr/java/com/lm/rec/SpeakerService.java`

### 5.1 时序

```
用户喊「小度小度」
   ↓ duer_wake（BDS DNN 唤醒引擎）命中
   ↓ 连 127.0.0.1:18999
SpeakerService.asrServer()
   ├─ wakeAt = RING.total()          ← 记下唤醒瞬间的音频游标
   ├─ playAnswer()                   ← 随机播一条应答语，同步等它播完
   │    · 从 voice/ 里随机选
   │    · 乘 2.4 倍增益（ETTS 输出偏轻）
   │    · AudioTrack MODE_STATIC，零延迟
   └─ listenOnce(wakeAt, durMs + 120)
        · 底噪取 wakeAt 之前 300ms（那时还没播语音，是纯环境声）
        · VAD 起点 = wakeAt + durMs + 120，把整段应答语音跳过去
        · 能量 VAD 判开口/停口 → 切段 → Vosk 限定词表识别
```

### 5.2 为什么 VAD 起点必须跳过应答语音

Vosk 用的是 **GRAMMAR 限定词表**，它有个特性：**对任何输入都会强制输出词表里的某个词**。
如果把自己刚说的「在呢」送进去，必然得到一个假命令。

这就是上午那个 bug 的根源（当时是跳过"叮咚"的 320ms 不够，取底噪时把提示音
尾音算进去了，阈值被抬到 22413，真人喊话永远超不过，只能走 fallback 盲识别，
结果把提示音识别成了「开灯」——**明明没人说话，灯自己亮了**）。

### 5.3 顺带修掉的两个 VAD 缺陷

| 问题 | 修法 |
|---|---|
| 底噪取样在提示音之后，被自己的声音污染 | 改成取 `wakeAt` **之前** 300ms |
| `thr = max(250, noise*3)` 会被环境噪声抬到 2 万+ | 钳死在 `[1200, 2500]`：实测安静态 AGC 后 rms 600~1200，说话 3000~6000，分界很干净 |

另外 fallback 盲识别加了能量闸门：必须 `rms > 1500 && peak > 8000` 才允许，
否则直接放弃、不识别 —— 宁可哑火也不能误开灯。

---

## 6. 现状与待办

### 已通
- ETTS 离线合成在 T70L 上跑通，小度原厂女声
- 4 条唤醒应答语预生成并接入
- 唤醒 → 应答 → VAD 听指令 → 识别 → 控灯 全链路自测通过
- 误触发防护（能量闸门 + VAD 跳过应答语音）
- `tts.sh` 一键合成任意语句

### 待办
1. **真人实测唤醒**（等用户喊「小度小度」验证）
2. 应答语库扩充（"在呢"、"我在"、"嗯"、"你说"…）
3. 动作反馈语音：开灯后回"已为你打开"（`tts.sh` 已可合成，未接入）
4. 应答语是否要随机 + 是否要加"叮"的前置音（对齐小度手感）
5. 开机自启 + 音量守护（`DAC volume` 会被厂商 App 压回 160，需定期纠正）

---

## 7. 复现清单（换设备/重刷后照这个来）

```sh
# 1) 推模型
adb push resources/bd_etts_text.dat          /data/local/tmp/tts/res/
adb push resources/bd_etts_speech_female.dat /data/local/tmp/tts/res/
adb push resources/common.res                /data/local/tmp/tts/res/

# 2) 推 glibc 运行环境（若无）
#    /data/local/tmp/xd/run/lib/ 需含 libc.so.6 + ld-linux-armhf.so.3 + libDcsSdk.so
#    + libTTSManager.so + libiconv/libcharset/libstdc++/libsqlite3/libcurl/libssl/libcrypto/libz

# 3) 推合成器与脚本
adb push tts_etts /data/local/tmp/duer/
adb push tts.sh   /data/local/tmp/duer/

# 4) 生成应答语
sh /data/local/tmp/duer/tts.sh "在呢" /data/local/tmp/duer/voice/ans1.wav

# 5) 重启识别服务加载声音
sh /data/local/tmp/duer/restart_speaker.sh
```

改代码后要重新编译：`t70l/duer/build_tts.sh`（C）和 `t70l/asr/build_app.sh`（Java）。
