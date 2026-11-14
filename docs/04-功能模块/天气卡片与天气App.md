# 天气卡片与天气 App（2026-11-01）

> 目标：在**原厂桌面**（`com.xylink.launcher`）上，时钟右边那块空白处，加一个天气显示。
> 结果：**卡片已上线，点击可打开天气 App**。桌面 APK 一个字节没改。
> 全程遵守红线第 -3 条：不刷机、不动固件、每一步都有回滚。

---

## 一、先说结论

| 项目 | 状态 |
|---|---|
| 天气卡片（时钟右侧） | ✅ 已上线，与原厂卡片同规格（220×280 底图 + 桌面自己画的标题） |
| 点击卡片 | ✅ 打开天气 App（城市选择页） |
| 天气数据 | ✅ 设备**自己联网**获取（15 天预报），不依赖 Mac |
| 重启后 | ✅ 卡片位置与点击都保留 |
| 实时温度显示在卡片上 | ❌ 未实现（原因见第三节，是固件缺陷，不是没做） |
| 拖动 / 长按菜单 / 下拉状态栏 | ⏳ 未做，属下一阶段 |

---

## 二、为什么不能直接装第三方天气 App


**所以给这台机器单独写了一个原生 App**，源码在 `~/re-tools/t70l/wx2/`。

---

## 三、桌面组件为什么做不成"实时温度"（重要，别再踩）

原厂桌面 `com.xylink.launcher` 用的是标准 `AppWidgetHost` 机制，但有两个硬缺陷：

### 3.1 缺系统权限
它被 OEM 的静默安装器装在 `/data/app/MarlinLauncher.apk`，是**普通应用**，没有
`BIND_APPWIDGET`（protectionLevel=18，signature|privileged）：

```
W/PackageManager: Not granting permission android.permission.BIND_APPWIDGET
                  to package com.xylink.launcher
```

**已解决**：把 APK 复制到 `/system/priv-app/`，重启后权限生效（0 次拒绝）。
详见 `桌面系统化.md`。

### 3.2 它申请的组件 id 从不绑定
即使有了权限，桌面的逻辑仍然有问题：它往 `item_info` 里写 `appWidgetId`，
但**从不调用 `allocateAppWidgetId()`**，也就是写进去的 id 全是"空号"。

```
dumpsys appwidget | grep -A3 "id=223"   → 查无此 id
```

所以**原厂那块天气从出厂就是空的**——不是网络问题，是它自己没绑。
（时钟能显示，是因为时钟是桌面自己画的，不走组件。）

### 3.3 我们试过的绕法，以及为什么最后还是退回静态卡

| 尝试 | 结果 |
|---|---|
| root 身份（uid 0 避开权限检查）替它 `bindAppWidgetId` | ✅ 能绑上，**卡片当场能显示实时天气** |
| 重启后 | ❌ 桌面重新建 view 时不认这个绑定（绑定的 host 是 `android/1000` 而非 `com.xylink.launcher/10024`），`updateAppWidget` 推不进去，卡片回落到 `--°` |
| 顶替原厂天气组件（同包名 `com.xylink.weatherwidget` 换成我们的实现） | ✅ 装上了，但桌面同样不分配 id，仍然空 |
| 删行让桌面按默认重建 | ❌ 桌面不会重建已删除的条目，`天气`直接消失 |

**结论：这版固件的组件槽位是坏的，能做到"显示"但做不到"重启后自己更新"。**
要让组件真正常驻，只剩一条路：写一个**开机重绑脚本**（有限次、非循环），
这需要写 `/system`——**按红线必须先经用户逐项同意**，目前未做。

---

## 四、最终实现（干净、可回滚、零系统改动）

### 4.1 卡片怎么加进去的

原厂桌面的每个图标来自 `item_info` 表，其中：
- `iconType=2` + `icon`(BLOB) → 用图片当卡片底图
- `title` → 桌面**自己**在卡片下方画文字（所以图片里不需要印字）
- `intent` → 点击后要拉起的 Activity

于是只要往表里写一条记录即可，**桌面 APK 完全不动**：

```sql
update item_info set
  itemType=2, iconType=2,
  spanX=1, spanY=1,
  icon=X'<220x280 PNG 的十六进制>',
  intent='intent:#Intent;component=com.xylink.weatherwidget/.MainActivity;launchFlags=0x10000000;end'
where title='天气';
```

**两个坑：**
1. `intent` 字段**必须带 `intent:` 前缀**（原厂那条也是 `intent:#Intent;action=...;end`），
   少了这个前缀桌面解析不出来，点了没反应。
2. 改完要 `kill -9` 桌面进程并**等 5 秒**再 `am start`，桌面会缓存 intent，不重启不生效。

### 4.2 卡片底图

原厂卡片是 **220×280**（画布），可见区 190×250，圆角 14px。
因为没有 PIL，用**纯 Python（zlib + 手写 PNG）**生成：渐变底 + 太阳 + 云，4170 字节。
生成脚本：`/tmp/mkcard.py`（已备份到 `~/re-tools/t70l/wx2/mkcard.py`）

### 4.3 天气 App

```
包名    com.xylink.weatherwidget      ← 顶替了原厂那个天气壳（原包已卸载）
组件    .WeatherWidget (AppWidgetProvider)
        .MainActivity  (城市选择器，50 个主要城市)
数据源  http://t.weather.itboy.net/api/weather/city/<城市码>
        走 HTTP 不走 HTTPS —— 这台机器的 TLS 只支持老版本，接现代公网 HTTPS 容易握手失败
```

**已实现的自愈逻辑：**
- 上次成功的天气写入 SharedPreferences，**先渲染缓存再联网**，开机瞬间网络没好也不会空着
- 联网失败用 `AlarmManager` 退避重试（30s / 60s / 120s / 240s / 480s，最多 5 次）
- 开机广播后延迟 20s / 60s 各补刷一次

构建：
```bash
cd ~/re-tools/t70l/wx2 && ./build.sh      # 产物 nemowx2.apk
adb install -r nemowx2.apk
```

---

## 五、怎么换城市

**点桌面上的「天气」卡片** → 打开城市列表 → 点你要的城市，卡片立即刷新。

当前默认是**上海**（`101020100`）。城市码用的是中国天气网编码。

---

## 六、回滚（任何一步出问题）

| 出问题的东西 | 怎么退 |
|---|---|
| 桌面数据库被改坏 | `adb push ~/re-tools/t70l/repair_20261101/launcher_db/_setting.db.bak` 回 `/data/data/com.xylink.launcher/databases/_setting.db`，`chown 10024:10024`，重启桌面 |
| 天气 App 不要了 | `adb uninstall com.xylink.weatherwidget` |
| 想恢复原厂天气壳 | `adb install ~/re-tools/t70l/repair_20261101/MarlinWeather.apk.bak` |
| 桌面系统化要退 | 删 `/system/priv-app/MarlinLauncher.apk`，重启；再把 `/data/app/MarlinLauncher.apk` 装上 |

**所有备份位置**：`~/re-tools/t70l/repair_20261101/`

---

## 七、遗留 / 下一步

- [ ] 天气 App 目前点开是"城市选择器"，**还没做完整预报页**（数据里已有 15 天预报，只差 UI）
- [ ] 卡片上显示实时温度：需要开机重绑脚本（写 /system，待批）
- [ ] 图标拖动 / 长按卸载菜单：桌面 APK 里**没有拖动代码**（`自建桌面.md` 已确认），
      要做只能在解析的 MarlinLauncher 里补一套拖动逻辑，工作量大，需单独评估
- [ ] 下拉状态栏：SystemUI 在跑，但**没有创建状态栏窗口**（`dumpsys window` 里查无 StatusBar），
      属 kiosk 定制，需要改 SystemUI.apk，需单独评估
- [ ] `com.nemo.weather`（旧包名）还在设备上，可删
