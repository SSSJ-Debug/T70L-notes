# 13 - 电源键「在软件里当 Home 键」的完整机制、失效根因与守护方案

> 2026-11-07 定稿。**这是 T70L 上最容易被自己人搞坏的一个功能**，前后出过很多次。
> 本文是唯一权威说明。以后凡是要 force-stop `vulture.app.home`，先读本文第七节。
>
> 设备脚本：`/data/local/tmp/homekey_guard.sh`（本文附源码）
> 解析产物：`/system/framework/services.jar` → `com.android.server.power.PowerManagerService`

---

## 一、现象（用户视角）

T70L 整机**只有一颗物理按键** —— 电源键（AXP81x PMIC，`/dev/input/event0`，`axp81x-supplyer`）。
它一个人干两件事：

| 场景 | 按下电源键应有的行为 |
|---|---|
| 正在某个 App 里 | **退回桌面第一屏**，屏幕保持亮 |
| 已经在桌面上 | **息屏**（Asleep） |

因为机器**没有返回键、没有 Home 键、很多 App 也没有退出入口**，
这个「电源键 = Home 键」是用户**唯一**能退出软件的途径。

**失效时的表现（关键）**：
- 在 App 里按电源键 → **直接息屏**，不会回桌面；
- 亮屏后还在 App 里，**软件退不出来**，只能 `adb` 或强杀；
- 用户感受：「卡在软件里，怎么也回不去」。

出问题的**不是按键硬件，也不是我们写的 App**，而是系统里一个布尔标志位被卡住了。

---

## 二、机制：解析 `services.jar` 拿到的原文

`PowerManagerService` 被厂商改动过。核心方法原文（解包工具 解析）：

```java
private int cntPowerKey = 0;
private boolean isHome = true;                                   // ★ 默认 true
private Intent mGoHomeIntent = new Intent("com.nemo.action.GOHOME");

private void goToSleepFromNative(long eventTime, int reason) {
    if (this.cntPowerKey != -1) {
        this.cntPowerKey++;
    }
    Slog.i(TAG, "gotosleepfromnative " + this.isHome + " cnt " + this.cntPowerKey);
    if (!this.isHome) {                                          // 不在桌面
        this.mContext.sendOrderedBroadcastAsUser(this.mGoHomeIntent, UserHandle.ALL, ...);
        this.isHome = true;                                      // 立刻标成"已回桌面"
    } else {                                                     // 在桌面
        goToSleepInternal(eventTime, reason);                    // 真息屏
    }
}

// isHome 唯一的更新入口：
private final class NemoHomeReceiver extends BroadcastReceiver {
    public void onReceive(Context ctx, Intent intent) {
        synchronized (mLock) {
            Slog.i(TAG, "got a Nemohome intent " + intent);
            if (intent != null) {
                PowerManagerService.this.isHome = intent.getBooleanExtra("isHome", true);  // ★ 缺省 true
            }
        }
    }
}

// 注册处（约 251 行）
IntentFilter filter6 = new IntentFilter();
filter6.addAction("com.nemo.action.HOME");
registerReceiver(new NemoHomeReceiver(), filter6, null, mHandler);
```

### 状态机（务必背下来）

```
物理电源键按下
      │
      ▼
native → goToSleepFromNative()
      │
      ├── isHome == false ──▶ 发广播 com.nemo.action.GOHOME ──▶ 桌面回前台，屏幕保持亮
      │                        isHome = true
      │
      └── isHome == true  ──▶ goToSleepInternal() ──▶ 真息屏
```

**结论一句话**：
> 「在软件里按电源键回桌面」这件事，系统自己是**判断不出来**的。
> 它完全依赖**外部有谁给它发 `com.nemo.action.HOME` 广播，并在 extra 里带 `isHome=false`**。
> 没人发 → `isHome` 永远停在默认值 `true` → 在任何软件里按都是息屏。

### 验证日志（可用 logcat 直接看到）

正常（在 App 里按）：
```
I/PowerManagerService: gotosleepfromnative false cnt -1     ← false = 会回桌面
I/LauncherActivity: action: com.nemo.action.GOHOME
```

失效（在 App 里按）：
```
I/PowerManagerService: gotosleepfromnative true cnt -1      ← true = 直接息屏
```
（没有任何 GOHOME 广播）

---

## 三、谁是那个「发广播的人」

解析 `vulture.app.home`（会议 App，`/data/app/vulture.app.home-1.apk`）的 dex，
里面同时存在字符串 `com.nemo.action.HOME` 与 `isHome`，并且注册了：

```
Action: "android.intent.action.SCREEN_OFF"
Action: "android.intent.action.SCREEN_ON"
Action: "com.nemo.action.GOHOME"
（ScreenReceiver receive GOHOME event com.nemo.action.GOHOME）
```

**原厂是由 `vulture.app.home` 负责上报「当前在不在桌面」。**

---

## 四、真正根因：是我们自己的脚本把它杀了

本机有三个脚本会 `am force-stop vulture.app.home`，各有正当理由：

| 脚本 | 为什么要杀它 |
|---|---|
| `mic_guard.sh` | Android 4.4 全局只允许一个录音客户端，它占着麦克风 → 小智听不见 |
| `boot_svc.sh`（`ensure_mic`） | 开机时抢麦克风，同一原因 |
| `cam_guard.sh` | 看家要开摄像头，它开机后一直抱着摄像头 |

`am force-stop` 会把应用打进 **stopped 状态**：
- 进程死；
- 之后**收不到任何隐式广播**，也不会自己再起来；
- 于是「上报 isHome」这条线**永久断开**。

**这就是「做软件做着做着电源键就失灵」的真相**：
不是新软件写错了，而是**新的抢麦 / 抢摄像头逻辑把上报者干掉了**。

> ⚠ 历史记录里 `核心资产/08-桌面稳定化-根因与修复.md` 第五节写的是
> 「`pm disable com.android.launcher` 导致 HOME 解析链断裂」——
> 那是另一次同类现象（**表象相同、根因不同**）。
> 本文的根因是 `isHome` 标志位，两者都要防：
> ① 不要禁用 `com.android.launcher`；② 不要长期 force-stop `vulture.app.home`。

---

## 五、30 秒诊断法（以后先跑这个）

```bash
adb shell "dumpsys activity activities | grep mResumedActivity"   # 当前前台是什么
adb shell "logcat -d -v brief | grep gotosleepfromnative"          # 按一次电源键后看这行
```

判读：

| 日志 | 含义 | 处置 |
|---|---|---|
| `gotosleepfromnative false` | 正常，会回桌面 | 无需处理 |
| `gotosleepfromnative true` 且前台是 App | **故障**：isHome 卡在 true | 见第六节急救 |

---

## 六、急救：一条命令立刻恢复

```bash
# 让系统知道"当前不在桌面"，之后按电源键就会回桌面
adb shell "am broadcast -a com.nemo.action.HOME --ez isHome false"
```

实测：发完之后在音乐 App 里按电源键 →
`gotosleepfromnative false cnt -1` → 前台变 `com.xylink.launcher` → **屏幕仍亮**。

反向（在桌面时让它回到「该息屏」的状态）：
```bash
adb shell "am broadcast -a com.nemo.action.HOME --ez isHome true"
```

> 注意：**不要**发不带 extra 的 `am broadcast -a com.nemo.action.HOME`。
> `getBooleanExtra("isHome", true)` 的缺省值是 `true`，等于告诉系统「我在桌面」，
> 结果就是立刻息屏。必须显式带 `--ez isHome false`。

---

## 七、长期方案：`homekey_guard.sh`（已上线）

思路：**不让电源键功能再依赖 vulture.app.home**，自己当那个「上报者」。

```
监听 events 缓冲区的 am_resume_activity（Activity 切前台事件，毫秒级）
      │
      ├── 顶层是桌面（com.xylink.launcher / vulture.app.lanucher）→ 发 isHome=true
      └── 顶层是任何其它 App                                    → 发 isHome=false
      只在状态变化时发一次；另有 20 秒 dumpsys 兜底对账
```

好处：
- 完全不碰 vulture.app.home，它被 force-stop 多少次都不影响电源键；
- 只发广播，不杀进程、不改系统文件、不注入按键；
- 桌面 App、看家、小智、抖音……任何 App 都自动生效，新写软件不用改一行代码。

### 关键实现坑（写脚本时踩到的）

1. **`logcat -b main -s ActivityManager:I` 在 4.4 上会把过滤结果吞成 0 行**
   （`logcat -d -b main -s ActivityManager:I | grep -c START` = 0，
   去掉 `-b main` 才有输出）。所以**不要带 `-b main`**。
2. 改用 `logcat -b events -s am_resume_activity`，行格式：
   ```
   I/am_resume_activity(  471): [0,1109642664,2,com.xylink.launcher/.app.LauncherActivity]
   ```
   取最后一段、去 `]`、取 `/` 前即包名。数据量极小，非常适合当事件源。
3. **不带 `-d` 的 `logcat` 会先把整个历史缓冲区重放一遍**，
   导致状态疯狂翻转（bili→launcher→douyin→launcher…）并停在过期记录上。
   **开流前必须 `logcat -b events -c` 清一次，再用 dumpsys 对齐当前状态。**
4. 设备上**没有** `head` / `tail` / `sed` / `strings` / `basename`，
   一律走 `/sbin/busybox xxx`。
5. `sleep 0.2` 这种小数在 `/sbin/busybox sleep` 上不认，用整数。
6. **`start` 不能用 pid 文件判断"是否在跑"**。pid 文件一被删（比如手工 kill 后忘了重建），
   `start` 就会误判"没在跑"再起一个，历史上一度堆出 3 个实例同时跟 logcat、重复发广播。
   已改成扫 `/proc/*/cmdline` 找 `*homekey_guard.sh*loop`，`stop` 也会全杀。
   （注意 `ps | grep homekey` 抓不到：4.4 的 `ps` 只显示 comm=`sh`。）

### 保活：谁来看住它

`desk_watchdog.sh`（本来负责保活 `launcher_guard` 的那个，每 30 秒一轮）
已加上对 `homekey_guard` 的保活。实测：手工 `stop` 之后 30 秒内自动拉起，
日志 `homekey_guard 不在，重启它` → `homekey_guard 已拉起 pid=...`。

所以现在有**两道保险**：
| 保险 | 作用 |
|---|---|
| `boot_svc.sh` → `start_daemons()` | 开机第一秒就把它拉起来 |
| `desk_watchdog.sh` 每 30 秒巡检 | 运行中被低内存杀掉也能自愈 |

### 部署与开关

```bash
adb push homekey_guard.sh /data/local/tmp/
adb shell "chmod 755 /data/local/tmp/homekey_guard.sh"
adb shell "sh /data/local/tmp/homekey_guard.sh start"
adb shell "sh /data/local/tmp/homekey_guard.sh status"   # 看状态 + 日志尾
```

已接入 `boot_svc.sh` 的 `start_daemons()`，开机自启，无需手工。

---

## 八、实测验证记录（2026-11-07）

| 步骤 | 前台 | isHome | 屏幕 | 结论 |
|---|---|---|---|---|
| 进音乐 | `com.lm.music` | `false` | 亮 | 守护正确上报 |
| 按一次电源 | **`com.xylink.launcher`** | `true` | **亮** | ✅ 回桌面，不息屏 |
| 桌面再按一次 | 桌面 | `true` | **灭** | ✅ 正常息屏 |

日志佐证：`I/PowerManagerService: gotosleepfromnative false cnt -1`

---

## 九、铁律（写进新脚本前先看一眼）

1. **不要在常驻守护里周期性 force-stop `vulture.app.home`**。
   抢麦/抢摄像头可以杀，但必须是「一次性、按需」，不能进 20 秒轮询。
2. 即使杀了，`homekey_guard.sh` 会兜住电源键 —— **但它必须在跑**。
   新脚本如果会杀 vulture，请顺手确认 `homekey_guard` 还活着。
3. **不要禁用 `com.android.launcher`**（另一次同类故障的根因，见 08 文档）。
4. 用户报「电源键又不回桌面了」，**第一条命令永远是**：
   ```bash
   adb shell "cat /data/local/tmp/homekey_guard.state; ps | grep -c homekey"
   ```
   先确认守护在不在，再看 `gotosleepfromnative`。

---

## 十、附：`cntPowerKey` 的另一个用途

同一段代码里还有一处（约 114–130 行）：

```java
if (cntPowerKey >= 4 || sm) {
    SystemProperties.set("sys.nemo.securitymode", "true");
} else {
    SystemProperties.set("sys.nemo.securitymode", "false");
}
cntPowerKey = -1;
```

即**连按电源键 ≥4 次会进入 `sys.nemo.securitymode=true`**（厂商的"安全模式/重启恢复"通道）。
排查时如果发现莫名其妙的模式变化，先看这个计数。
正常使用中它是 `-1`（表示未计数）。

---

_文档版本：2026-11-07 · 作者：WorkBuddy · 依据：`services.jar` 解析 + 真机实测_
