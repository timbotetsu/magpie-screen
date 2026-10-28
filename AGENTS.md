# AGENTS.md

给在本仓库写代码的 agent 的约定。产品是给 **Magpie 网关** 用的横屏用量看板，目标设备 LX04（Android 8.1 / API 27，800×480 @240dpi = 533×320dp），横屏锁定。UI 文案用中文。

## 命令

```bash
./gradlew test            # 纯 JVM 单测，不依赖设备
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease # 未签名；发版前需要自己的 keystore
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

验证要求：改完 `test` + `assembleDebug` 必过；凡是涉及布局/交互的改动，装到 800×480 / 240dpi / API 27 的模拟器或真机上截图核对，不要只看代码。

## 架构地图

| 文件 | 职责 | 改它要连带看 |
|---|---|---|
| `MainActivity.kt` | 四个底栏入口；切页即新建 Fragment（页面状态不保留） | `activity_main.xml`、各 Fragment 的 `load()` |
| `net/Gateway.kt` | OkHttp 客户端、错误分类、`UrlRules`、`Endpoints`、`ConnectionSettings` | 所有请求方、`UrlRulesTest` |
| `net/ResponseCache.kt` | 5 分钟 TTL 的响应缓存、stale 读取、single-flight、连接切换清空 | 所有页面的 `load()`、`BaseFragment` |
| `data/Parse.kt` | 网关 JSON → 数据类；`Stats`/`Tokens` 的 total 口径 | `TokenMathTest`、`RouteAndConcurrencyTest` |
| `data/AppPrefs.kt` | 地址 + key（EncryptedSharedPreferences，失败回退） | `SettingsFragment` |
| `ui/OverviewFragment.kt` | 总览：今日/本周/7日/30日、KPI、本地 00–23 柱状图、排行、点柱气泡；请求版本号防竞态、走缓存 | `SeriesChartView`、`BubbleView`、`DayRequests`、`Fmt` |
| `ui/RequestsFragment.kt` | 18 周热力图 + 点格气泡、走缓存 | `HeatmapView`、`DayRequests`、`Fmt` |
| `ui/QuotasFragment.kt` | 订阅卡片（剩余/已用切换）+ 5小时/周/月趋势切换 + 速度判定、走缓存 | `QuotaTrendView`、`Parse.Quotas`、`Fmt` |
| `ui/SettingsFragment.kt` | 连接保存、能力自检 | `Gateway`、`Parse` |
| `ui/BaseFragment.kt` | 每 1 分钟检查缓存（`onRefreshTick`），5 分钟内不发请求；回前台先查缓存；页面可见时保持屏幕常亮 | 各页 `onRefreshTick()` 实现、`ResponseCache` |
| `ui/BubbleView.kt` | 唯一的气泡实现（两侧共用） | — |
| `ui/DayRequests.kt` | 共享的按天明细缓存（5 分钟 TTL、single-flight）+ 按模型聚合 | 500 条/页、3000 行上限 |
| `ui/SeriesChartView.kt` | 柱状图与点按回调 | `OverviewFragment` |
| `ui/HeatmapView.kt` | 日历热力图；月份标签只标完整月份 | `RequestsFragment` |
| `ui/QuotaTrendView.kt` | 额度趋势：5小时/周/月轴、日网格、当前时刻虚线 | `QuotasFragment` |
| `ui/Fmt.kt` | 数字格式化；网关时间戳 → 设备时区；网关自己的日历日 | 所有时间展示、`FmtTest` |

## 必须遵守的不变量

1. **屏幕预算固定**：状态栏 24dp + 底栏 36dp → 内容 260dp。总览页不允许滚动，任何新增块都要在同一预算内重排；热力图/额度页各自内部滚动。
2. **Fragment 里不要用字段初始化器碰 `app`**：`private val x = Foo(app.gateway)` 会在 `onAttach` 之前执行并抛 `Fragment not attached to an activity`（踩过一次）。用 `by lazy` 或放到 `onCreateView`。
3. **展示按设备时区，按天查询按网关日期**：网关时间戳是绝对时刻，不同网关会用不同 offset 序列化（`Z`、`+08:00`），展示（柱状图标签、气泡标题、热力图格子）一律用 `Fmt.localZoned()` / `Fmt.localDate()` 转成设备时区；只有 `/usage/requests?day=` 这类由网关自己解释的日期参数用 `Fmt.gatewayDate()`。不要直接截取时间戳前 10 位来展示，否则 UTC 网关下北京 00:00–07:59 的请求会被算到前一天。
4. **图表 API 签名顺序**：`SeriesChartView.setData(points, style = …, animate = …, formatter)` —— `style`/`animate` 在 `formatter` 之前是为了保留尾随 lambda 写法（`setData(points) { … }`），调用点在 `OverviewFragment`；`QuotaTrendView.setData(series, xStart, xEnd, …, animate = …)` 的调用点在 `QuotasFragment`。改签名要同步对应调用点。
5. **额度页只显示订阅卡**：不显示「本机 key 限制」；无卡片时给一行说明即可（历史上曾误加，已按要求删除）。
6. **数据字段不臆造**：只渲染网关返回的字段，缺失用「未提供」；不要把余额、活跃天数、连续天数、热力图以外的指标编出来。
7. **颜色与尺寸用 token**：`values/colors.xml`（含 `heat0..4`、`series1..4`、`bar`）和 `values/themes.xml` 的 style；不要在代码里硬编码十六进制或 dp 字号。
8. **一切请求只读**：只发 `GET`；不要新增写接口、后台上报或第三方依赖。
9. **总览周期口径**：「今日」用 `last:<设备本地零点到现在>`（滚动窗口）；「本周」用 `YYYY-MM-DD..YYYY-MM-DD`（本地日历周一到周日，网关按自己的时区解释日期）；7日/30日用网关 preset。不要恢复「全部」或 `period=all`。
10. **页面数据先查缓存**：所有页面请求走 `ResponseCache`，加载顺序固定为 fresh 缓存 → stale 预览 → single-flight 请求；5 分钟内不发请求，过期才刷新；手动刷新必须 `force=true`；状态行的“更新”时间用缓存的 `fetchedAtWall`，不要用当前时间；连接设置变化时清空 `cache` 与 `dayRequests`。缓存 key 必须包含网关地址和会改变口径的日期/周期。

## 交互约定

- 点柱子 / 点热力图格子 → 用 `BubbleView.show(...)`，5 秒自动消失、点外部关闭；气泡内容：时间范围 + 请求数/Token + 按模型三列（最多 4 行，多出写「…还有 N 个模型」）。
- 总览周期 chip 只有今日/本周/7日/30日；只切换时间窗口，不弹气泡。
- 总览小时柱状图固定本地 00–23：按本地小时放桶，缺失小时补 0，点空小时不弹气泡。
- 额度趋势默认 5 小时窗口；点击图表按 5小时 → 周 → 月 → 5小时 循环，只重绘不重新请求；切换状态不持久化，切页重建后回到 5 小时视图。图表背景按天画格，白色虚线标记当前时刻，每个计费窗口各有一根「匀速用完」虚线。
- 额度页的供应商 chip 和卡片标题显示网关返回的 `name`（展示名），`provider` 只作为选择、卡片过滤和匹配 `quotas/history` 的 key；`name` 为空时回退到 `provider`。
- 总览首次进入、切周期、点刷新时，柱状图和模型排行条从 0 生长；额度页进入、刷新或切换供应商时，三条进度条从 0 生长；点左侧订阅额度区域在剩余/已用之间切换，文字和进度条一起切换。1 分钟缓存检查命中时不播放动画。额度趋势点击循环 5小时/周/月 时只做 x 轴缩放过渡，各窗口曲线保持可见；数据刷新不播放。
- 刷新节奏集中在 `BaseFragment`：可见时每 `CHECK_INTERVAL_MS`（1 分钟）做一次缓存检查，只有超过 `ResponseCache.DEFAULT_TTL_MS`（5 分钟）才发请求；回前台同样先查缓存；`onStop` 必须让定时器和 `FLAG_KEEP_SCREEN_ON` 都停下。设置页覆盖为空实现（表单不该被自动重置）。
- 排名行不可点击。
- 加载失败时保留上一次成功的数据，状态行写明失败原因；总览额外显示「快照 HH:mm:ss」。

## 数据口径

- `Tokens.total = input + output + cacheRead + cacheWrite`；`reasoning` 已含在 output 内，**不重复累加**。
- 模型维度取请求时指定的 `model`（不是实际服务的 `served`）。
- 总览「今日」请求 `period=last:<Go duration>`（本地零点至今，如 `20h5m3s`）；「本周」请求 `period=YYYY-MM-DD..YYYY-MM-DD`；7日/30日用 preset。旧网关不支持滚动窗口/日期区间时分别回退 `today`/`7d`。
- 单页请求上限 500 条，按 `offset` 翻页；一天通常 2–3 页。

## 发版

- 版本在 `app/build.gradle.kts`：`versionCode` / `versionName`，发版前递增 `versionCode`。
- `assembleRelease` 在存在 `keystore.properties` 时用其中的 release key 签名；没有该文件时产物未签名（`isMinifyEnabled = false`），需要自备 keystore。
- `applicationId = timbo.homebrew.magpiescreen`，`minSdk 27`，`targetSdk 35`。

## 已知取舍（不影响发布，改动前先确认）

这三条不是缺陷，是「留着有用、删了也行」的选择，量化如下：

| 项 | 体量 | 影响 | 为什么还在 |
|---|---|---|---|
| `Parse.kt` 里 `Concurrency` / `ActiveRequest` / `Routes`（第 338–434 行）与 `Endpoints.ROUTE` | 约 97 行 + `RouteAndConcurrencyTest.kt` 69 行 | 运行时零调用，只被单测使用 | 它们是网关 `/v1/magpie/concurrency`、`/route` 的响应契约；将来做「实时请求 / 路由追踪」时直接可用。`Endpoints.CONCURRENCY` 仍被设置页能力自检使用，不能删 |
| `RequestsPage` 的 `through/direct/reported/isConsistent` | 约 12 行 | UI 不读，但 `isConsistent` 有单测 | 请求页已改成热力图，不再展示这些分项；保留可随时恢复明细页 |
| `QuotasFragment` 560 行、纯代码搭视图 | — | 可读性 | 拆 `QuotaCardView` 属结构性改动，发版前不做 |
| 三个数据页各自的缓存加载编排（fresh / stale / force / single-flight） | 约 100 行重复，页面加载流程没有纯 JVM 测试 | 新增页面或修改 TTL、状态行规则时要同步三处 | 目前只有 3 个数据页，抽 `CachedPageLoader` 的收益还不抵抽象成本；新增第 4 个数据页前先抽 |
| 三个 Fragment 各一个 1 行私有 `dp()` | 3 行 | 无 | 合并要多一个文件，收益不抵成本 |

要「零未使用代码」的发布版，删掉第一、二项即可：删 `Parse.kt` 第 338 行到文件末尾、`Endpoints.ROUTE`、`RouteAndConcurrencyTest.kt`，并在 `TokenMathTest` 去掉 `isConsistent` 两条断言，然后重新 `./gradlew test assembleDebug`。其余代码不依赖它们。
