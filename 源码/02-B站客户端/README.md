# 哔哩哔哩极简客户端 —— 实现说明

> 2026-11-05　目标设备：中移 T70L（Android 4.4.4 / API19 / armv7 / 1GB RAM / 1280×800 / density 160）

## 一、为什么不是浏览器

这是整件事最关键的技术判断，绕了近一个晚上才彻底确认：

| 方案 | 结论 | 证据 |
|---|---|---|
| Via / X 浏览器 / 夸克早期 | **不可能** | 都是 WebView 套壳，渲染引擎 = 系统 WebView = Chromium 33（2014）。换壳等于没换 |
| Bromite 108（Blink 108） | **装不上** | `INSTALL_FAILED_OLDER_SDK`，实测要求 minSdk ≥ 21，本机是 19 |
| Chrome 76 / Firefox 68（最后支持 4.4 的新内核） | **跑不了 B 站** | 不支持 `?.` `??`（Chrome 80+ 才有）。B 站 2025 前端打包默认用这两个语法，解析阶段直接抛错，**整个 bundle 一行都不执行** |
| 网页版 m.bilibili.com | **空壳** | 页面框架能出、导航能点，封面全灰、内容全空、频道页"加载失败"。这不是网慢：ping B 站 14ms、DNS 24ms、零丢包、零 SSL 错误，同机打开百度完美渲染 |

**硬约束**：这是内核版本问题，不是配置问题，任何"换浏览器"的路都堵死。

## 二、最终方案：绕开渲染，直连接口 + 原生播放器

```
B站开放接口 (HTTPS/JSON)  →  ListView 卡片流 (封面/标题/UP/播放量)
                          →  playurl 拿 mp4 直链
                          →  系统 MediaPlayer 硬解播放
```

关键发现：`platform=html5 + qn=16 + fnval=0` 时，B 站返回的是 **durl 数组里的完整 mp4**（非 DASH 分段），系统播放器直接能播，不需要任何解封装。

实测返回：`quality=16, format=mp4, size=3190390`，直链下载 `HTTP 206`，速度 966KB/s，`file` 识别为 `ISO Media, MP4 Base Media v1`。

**成品体积：29,137 字节（29KB）**，比 Via（约 10MB）小 350 倍，零第三方依赖。

## 三、踩过的坑（都可复现）

### 1. TLS 1.2 必须手工开
Android 4.4 的 `HttpsURLConnection` 默认协商 TLSv1，B 站要求 TLSv1.2+，握手直接失败。

```java
SSLContext sc = SSLContext.getInstance("TLSv1.2");
sc.init(null, trustAllManagers, new SecureRandom());
HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
```

同时 4.4 内置 CA 库是 2013 年的，B 站换过根证书，用 trust-all 规避（内网自用设备，只拉公开视频列表，接受该权衡）。

### 2. 不带 buvid3 会吃 -352
直接请求排行榜 → `code=-352`（风控拦截）。

必须先取设备指纹：
```
GET https://api.bilibili.com/x/frontend/finger/spi
→ {"data":{"b_3":"81307456-...","b_4":"669A1919-..."}}
```
之后所有请求带 `Cookie: buvid3=<b_3>; buvid4=<b_4>`。

UA 也不能用老手机 UA，换桌面 Chrome UA 更稳。

### 3. VideoView.setVideoURI(Uri, Map) 是私有方法
bilivideo 有防盗链，必须带 `Referer`。但 API19 的 `VideoView` 只公开了 `setVideoURI(Uri)`，带 headers 的重载是 private。

只能反射：
```java
Method m = VideoView.class.getDeclaredMethod("setVideoURI", Uri.class, Map.class);
m.setAccessible(true);
m.invoke(video, uri, headers);
```
失败降级为无 header。

### 4. SurfaceView 会吃掉父容器的触摸事件
把 `setOnTouchListener` 挂在根 FrameLayout 上，点画面唤不出控制条 —— 事件不会从 SurfaceView 冒泡。必须直接挂在 `video` 上，并 `return true`。

### 5. ❚ 这个字符在 4.4 上没有字形
用 `TextView` 画暂停键（U+275A）显示为**空白**。改成自绘 `PlayPauseView`（Canvas 画两个圆角竖条 / 一个三角形）。

### 6. 应用窗口实际只有 752 高
`Theme.NoTitleBar.Fullscreen` 不等于全屏，底部还有 48px 系统导航栏。改为：

```java
View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | SYSTEM_UI_FLAG_HIDE_NAVIGATION
| SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_IMMERSIVE_STICKY
```

不处理的话，所有"底部按钮"的点击坐标都会算错（实测点了三次都没命中）。

### 7. 竖屏视频贴左边
`VideoView` 自己按视频比例算尺寸，但 FrameLayout 默认放左上角。给 LayoutParams 设 `gravity = CENTER` 才居中。

### 8. rid=13（番剧）在排行榜接口里不存在
返回 `code=-400`。批量实测有效值：

| tab | rid | 条数 |
|---|---|---|
| 全站 | 0 | 100 |
| 动画 | 1 | 95 |
| 音乐 | 3 | 97 |
| 游戏 | 4 | 96 |
| 影视 | 181 | 91 |
| 科技 | 188 | 97 |
| 鬼畜 | 119 | 93 |
| 舞蹈 | 129 | 95 |

无效：13（番剧）、202（资讯）→ 均 `-400`。

## 四、功能清单

- 8 个分区 tab（全部实测 code=0）
- 3 列卡片流：封面（圆角裁剪）/ 标题 2 行 / UP 主 · 播放量 · 时长
- 播放页：全屏，**返回按钮常驻**（本机只有一个电源键且映射成 HOME，返回入口必须随时可达）
- 点击画面切换底部控制条；3.5 秒自动隐藏（顶部不隐藏）
- 粉色进度条 + 白色圆点滑块（4.4 默认是黄色 holo）
- 播完自动播下一个，列表循环
- 封面 LruCache 8MB + 3 线程池 + tag 防错位

## 五、构建

```bash
cd "B站客户端/app" && ./build_apk.sh
# 产出 bili.apk（约 29KB），签名用同目录 bili.jks（口令 bili123）
adb install -r bili.apk
```

工具链：JDK21 + build-tools 34.0.0 + android-19.jar + d8(r8-8.5.35) + apksigner。

> 编译器用 `-source 8 -target 8` + d8 desugar，全部匿名内部类（不用 lambda，避免额外 desugar 差异）。

## 六、抖音：做不了，原因明确

实测（2026-11-05）：

| 探测 | 结果 |
|---|---|
| `https://www.douyin.com/` | HTTP 200，72,914 字节，但**纯 SPA 空壳**：`aweme_id`/`RENDER_DATA`/`play_addr` 关键词命中数全为 **0** |
| `aweme/v1/web/hot/search/list/` | **免签名可用**，返回 48 个热词（含 hot_value），但只有文字，无视频、无播放地址 |
| `iesdouyin.com/web/api/v2/aweme/iteminfo/` | HTTP 200 但内容空 |

要拿推荐视频流或播放地址，必须过 `a_bogus` / `X-Bogus` 签名 —— 这是抖音专门用来阻止第三方客户端的 VMP 混淆算法，分析成本极高且频繁更新；老版本抖音 APK 的接口早已废弃。

**结论：抖音不存在"轻量客户端"这条路。** 可行的最小方案只有"粘贴分享链接播放单条视频"（走 iesdouyin 解析），但那不叫"刷"。

## 七、桌面图标规范（本次实测确定，两次踩坑后定稿）

### 7.1 正确规范

四个原厂图标（指令执行/文件夹/设置/录像机）用 **alpha ≥ 240** 实测，完全一致：

| 项目 | 数值 |
|---|---|
| 画布 | **220 × 280**（等于 item 区域，非正方形） |
| **实心内容区** | **180 × 240** |
| **边距** | **四边均 20** |
| 圆角半径 | ≈ 5（很小，几乎方角） |
| 渐变方向 | **上亮下深** |

指令执行实测渐变色（x=100 竖切）：`y=8% → (254,197,83)`　`y=92% → (243,128,34)`

### 7.2 踩过的两个坑（都导致"看起来大一圈"）

**坑一：用 alpha>8 量边界，把外层羽化阴影算成了内容。**
宽松阈值量出来是 `196×256 / 边距 12,17,12,7`，按这个做，实心方块比原厂**宽 16px、高 16px**，视觉上明显大一圈。

**坑二：渐变方向反了。**
做成"上深下浅"，底部太亮，在深色壁纸上会糊成一片彩色光雾，看起来像图标在发光。

### 7.3 最稳的做法：直接复用原厂 alpha

不要自己画轮廓。取指令执行图标 PNG 的 **alpha 通道**当形状母版，只替换 RGB：

```python
a = alpha_of_launcher[x][y]
rgb = 自己的配色 or 图案
if a < 255:            # 边缘羽化区按 alpha 比例暗化
    k = (a/255.0) ** 1.6
    rgb = rgb * k
out = rgb + a
```

成品与指令执行 **alpha 逐像素 0 差异**，实心区 `180×240 @ (20,20)` 完全相同。

### 7.4 注意：电话图标是例外
`电话` 属于 widget 类（itemType=4），其 PNG 的 alpha 铺满整个 220×280，但内部实心色块同样是 180×240。不要拿它当形状母版，用指令执行/文件夹/设置/录像机任何一个都可以。
