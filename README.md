# MagpieScreen（magpie-screen）

给 [Magpie](https://usemagpie.ai) 网关用的横屏用量看板：把网关的 token/费用/订阅额度画在一台小屏设备上长期挂在桌面上。

目标设备是 **小爱触屏音箱 LX04**（Android 8.1 / API 27，800×480px @240dpi，即 533×320dp），横屏锁定。同尺寸的平板、旧手机也能装。

## 界面

四个入口：**总览 · 请求 · 额度 · 设置**。底栏 36dp，状态栏 24dp，内容区固定 260dp，总览页不滚动。

| 页面 | 内容 |
|---|---|
| 总览 | 今日/本周/7日/30日切换；KPI 四格（总 Token / 缓存命中率 / 请求数 / 错误数）、本地 00–23 柱状图（每根柱标 token 数，点柱子弹出该小时的请求数、Token 与按模型的拆分）、模型用量前 3、底部一行输入/输出/缓存读/缓存写/推理 |
| 请求 | 近 18 周日历热力图（左侧周几、顶部月份），每格一天，点格子弹出当天请求数/Token 与按模型的拆分 |
| 额度 | 订阅额度卡片（各计费窗口的剩余百分比 + 进度条 + 重置时间；点左侧卡片区域在剩余/已用之间切换）与额度趋势：默认按月的窗口展示，点图表切换按周；背景每天一格，白色虚线标记当前时刻；每个窗口各有一根「匀速用完」虚线，实线是真实剩余，标题右侧给出「按此速度，能撑到重置 / 无法撑到重置」 |
| 设置 | 网关地址 + API key（可显示）、保存并测试连接，逐项列出网关能力（统计 / 额度 / 实时并发 / 请求明细） |

界面文案为中文，数值格式统一由 `Fmt` 处理（`3.14M`、`72.2%`、`1.2K`、`10-27 19:36`）。

**刷新策略**：页面在前台时每 1 分钟检查一次缓存，只有数据超过 5 分钟才重新请求；从后台回到前台也同样先查缓存。手动点刷新会绕过缓存强制请求。页面可见期间保持屏幕常亮（`FLAG_KEEP_SCREEN_ON`），离开页面即释放，所以挂在墙上不会停在旧数字上。手机息屏/切到后台后不再请求。

**缓存**：页面共享一份进程内响应缓存（5 分钟 TTL，按网关地址、日期和周期分 key），切 Tab 或重新进入页面会直接复用；过期后先显示旧数据再后台刷新，失败时保留旧数据。连接设置变化时缓存清空。

**动效**：总览首次进入、切换周期或点刷新时，柱状图和模型排行条从底部生长；额度页进入、刷新或切换供应商时，订阅卡片的进度条生长；点卡片区域切到已用时进度条也会重新生长。额度趋势点击切换月/周时，横轴平滑缩放，月线始终保留。自动刷新不做动画。

## 数据来源

App 只读 Magpie 网关的管理接口，全部为 `GET`，带 `Authorization: Bearer <gateway key>`：

| 接口 | 用途 |
|---|---|
| `/v1/magpie/usage?period=today\|7d\|30d\|last:<duration>\|<from>..<to>` | 总览的 KPI、趋势桶、模型排行（今日用 `last:`，本周用日期区间） |
| `/v1/magpie/usage/requests?period=…&offset=&limit=`，或 `?day=YYYY-MM-DD` | 请求页与气泡的按天明细（单页上限 500 条，App 自动翻页并缓存） |
| `/v1/magpie/quotas?kept=1` | 额度卡片（订阅窗口、剩余百分比、重置时间） |
| `/v1/magpie/quotas/history` | 额度趋势的历史采样点 |
| `/v1/magpie/concurrency` | 设置页的能力自检 |

约定：

- **时间是绝对时刻，展示用设备时区**：网关可能以 `Z`（UTC 服务器）或 `+08:00` 序列化，App 统一转成设备时区显示；`?day=` 按天查询仍使用网关自己的日历日。
- **「今日」按设备日历**：请求 `last:<本地零点到现在>`，所以 KPI 和固定的本地 00–23 小时图都是本地日；「本周」请求本地周一到周日的日期区间，`7日/30日` 用网关预设。网关不支持滚动窗口/日期区间时分别回退 `today`/`7d`。
- 网关按天聚合的历史通常只保留最近约 30 天，所以热力图前期大部分格子是空的，随时间自然补齐。
- 地址规则见 `net/UrlRules`：必须 `http(s)://`，去掉结尾 `/v1` 以免重复，不接受用户名密码、查询参数或 fragment。

## 构建与运行

需要 JDK 17、Android SDK 35。

```bash
./gradlew test            # 单元测试（URL 规则、token 口径、时区展示、缓存 TTL、route/concurrency 解析）
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动会直接落在**设置页**（没有连接时不会进总览）：

1. 网关地址填 Magpie 网关根地址，如 `https://magpie.example.com` 或 `http://192.168.1.10:3425`
2. 填 gateway key（Magpie 应用里 Settings → Network and sharing → Gateway keys 创建）
3. 点「保存并测试连接」，看「数据能力」是否逐项可用

模拟器上访问宿主机网关用 `http://10.0.2.2:3425`；建议用 800×480 / 240dpi / API 27 的 AVD，与目标设备一致。

## 项目结构

```
app/src/main/java/timbo/homebrew/magpiescreen/
  MainActivity.kt          底栏四个入口，切页即重建 Fragment
  net/Gateway.kt           OkHttp 客户端、错误分类、地址规则、接口路径
  net/ResponseCache.kt     5 分钟 TTL 响应缓存、stale 读取、single-flight
  data/AppPrefs.kt         地址与 key（EncryptedSharedPreferences，Keystore 不可用时退化为普通偏好）
  data/Parse.kt            网关 JSON → 数据类（usage / requests / quotas / limit / concurrency / route）
  ui/OverviewFragment.kt   总览：周期切换、KPI、本地 00–23 柱状图、排行、点柱气泡
  ui/RequestsFragment.kt   请求：18 周热力图、点格气泡
  ui/QuotasFragment.kt     额度：订阅卡片 + 月/周趋势切换 + 速度判定
  ui/SettingsFragment.kt   设置：连接与能力自检
  ui/SeriesChartView.kt    柱状图（总览用；点按回调报告柱索引）
  ui/HeatmapView.kt        日历热力图（18 周 × 7 天，可点；月份标签只标完整月份）
  ui/QuotaTrendView.kt     额度趋势折线（月/周轴、日网格、当前时刻虚线、实线/虚线）
  ui/BubbleView.kt         统一的小气泡（标题 + 总计 + 三列表格 + 自动夹边 + 定时关闭）
  ui/DayRequests.kt        共享的按天明细缓存（5 分钟 TTL）+ 按模型聚合
  ui/Fmt.kt                数字/时间格式化，网关时间戳转设备时区
app/src/main/res/          布局、颜色与样式 token、drawable
app/src/test/              纯 JVM 单元测试
```

## 安全与隐私

- 网关地址与 key 存在 `EncryptedSharedPreferences`（Keystore 派生密钥），失败才回退到普通偏好。
- `android:allowBackup="false"`，`usesCleartextTraffic="true"` 仅为了支持局域网 HTTP 网关；用公网地址时请一律走 HTTPS。
- App 不做任何统计上报，除你填的网关外不访问任何第三方。

## 已知限制

- 只适配 Magpie 网关的管理接口，别的网关（LiteLLM、One API 等）不适用。
- 网关侧历史约 30 天，热力图/趋势的早期数据会偏空。
- release 包默认由 `keystore.properties` 指定的 key 签名；没有该文件时 `assembleRelease` 产出未签名包，需要自备 keystore。
- 缓存只存在内存里，进程重启后首次进入页面会重新请求；连接设置变化也会清空缓存。
- 订阅额度只来自当前连接的网关；如果 ClinePass 之类的订阅挂在另一台 Magpie 上，那台网关需要对外提供 `/v1/magpie/quotas` 才能看到。

## 许可

以 [MIT License](LICENSE) 发布（Copyright © 2026 timbo）。

本项目是 Magpie 网关的**第三方客户端**，与 Magpie 官方无隶属关系；Magpie 及其名称、图标归其作者所有。App 内不含任何 Magpie 代码，只通过其 REST 接口读取数据。
