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
| `data/Parse.kt` | 网关 JSON → 数据类；`Stats`/`Tokens` 的 total 口径 | `TokenMathTest`、`RouteAndConcurrencyTest` |
| `data/AppPrefs.kt` | 地址 + key（EncryptedSharedPreferences，失败回退） | `SettingsFragment` |
| `ui/OverviewFragment.kt` | 总览：今日/本周/7日/30日、KPI、本地 00–23 柱状图、排行、点柱气泡；请求版本号防竞态 | `SeriesChartView`、`BubbleView`、`DayRequests`、`Fmt` |
| `ui/RequestsFragment.kt` | 18 周热力图 + 点格气泡 | `HeatmapView`、`DayRequests`、`Fmt` |
| `ui/QuotasFragment.kt` | 订阅卡片 + 月/周趋势切换 + 速度判定 | `QuotaTrendView`、`Parse.Quotas`、`Fmt` |
| `ui/SettingsFragment.kt` | 连接保存、能力自检 | `Gateway`、`Parse` |
| `ui/BaseFragment.kt` | 每 5 分钟定时刷新（`onRefreshTick`）、回前台立即刷新、页面可见时保持屏幕常亮 | 各页 `onRefreshTick()` 实现 |
| `ui/BubbleView.kt` | 唯一的气泡实现（两侧共用） | — |
| `ui/DayRequests.kt` | 按天明细分页 + 按模型聚合 + 按天缓存 | 500 条/页、3000 行上限 |
| `ui/SeriesChartView.kt` | 柱状图与点按回调 | `OverviewFragment` |
| `ui/HeatmapView.kt` | 日历热力图；月份标签只标完整月份 | `RequestsFragment` |
| `ui/QuotaTrendView.kt` | 额度趋势：月/周轴、日网格、当前时刻虚线 | `QuotasFragment` |
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

## 交互约定

- 点柱子 / 点热力图格子 → 用 `BubbleView.show(...)`，5 秒自动消失、点外部关闭；气泡内容：时间范围 + 请求数/Token + 按模型三列（最多 4 行，多出写「…还有 N 个模型」）。
- 总览周期 chip 只有今日/本周/7日/30日；只切换时间窗口，不弹气泡。
- 总览小时柱状图固定本地 00–23：按本地小时放桶，缺失小时补 0，点空小时不弹气泡。
- 额度趋势默认月窗口；点击图表切换周窗口，只重绘不重新请求；切换状态不持久化，切页重建后回到月视图。图表背景按天画格，白色虚线标记当前时刻。
- 总览首次进入、切周期、点刷新时播放柱状图生长动画；5 分钟自动刷新不播放。额度趋势点击切换时 x 轴缩放并交叉淡入淡出，数据刷新不播放。
- 刷新节奏集中在 `BaseFragment`：可见时每 `REFRESH_INTERVAL_MS`（5 分钟）调一次 `onRefreshTick()`，回前台立即调；`onStop` 必须让定时器和 `FLAG_KEEP_SCREEN_ON` 都停下。设置页覆盖为空实现（表单不该被自动重置）。
- 排名行不可点击。
- 加载失败时保留上一次成功的数据，状态行写明失败原因；总览额外显示「快照 HH:mm:ss」。

## 数据口径

- `Tokens.total = input + output + cacheRead + cacheWrite`；`reasoning` 已含在 output 内，**不重复累加**。
- 模型维度取请求时指定的 `model`（不是实际服务的 `served`）。
- 总览「今日」请求 `period=last:<Go duration>`（本地零点至今，如 `20h5m3s`）；「本周」请求 `period=YYYY-MM-DD..YYYY-MM-DD`；7日/30日用 preset。旧网关不支持滚动窗口/日期区间时分别回退 `today`/`7d`。
- 单页请求上限 500 条，按 `offset` 翻页；一天通常 2–3 页。

## 发版

- 版本在 `app/build.gradle.kts`：`versionCode` / `versionName`，发版前递增 `versionCode`。
- `assembleRelease` 产物未签名（`isMinifyEnabled = false`），发布需要自备 keystore。
- `applicationId = timbo.homebrew.magpiescreen`，`minSdk 27`，`targetSdk 35`。

## 已知取舍（不影响发布，改动前先确认）

这三条不是缺陷，是「留着有用、删了也行」的选择，量化如下：

| 项 | 体量 | 影响 | 为什么还在 |
|---|---|---|---|
| `Parse.kt` 里 `Concurrency` / `ActiveRequest` / `Routes`（第 338–434 行）与 `Endpoints.ROUTE` | 约 97 行 + `RouteAndConcurrencyTest.kt` 69 行 | 运行时零调用，只被单测使用 | 它们是网关 `/v1/magpie/concurrency`、`/route` 的响应契约；将来做「实时请求 / 路由追踪」时直接可用。`Endpoints.CONCURRENCY` 仍被设置页能力自检使用，不能删 |
| `RequestsPage` 的 `through/direct/reported/isConsistent` | 约 12 行 | UI 不读，但 `isConsistent` 有单测 | 请求页已改成热力图，不再展示这些分项；保留可随时恢复明细页 |
| `QuotasFragment` 394 行、纯代码搭视图 | — | 可读性 | 拆 `QuotaCardView` 属结构性改动，发版前不做 |
| 三个 Fragment 各一个 1 行私有 `dp()` | 3 行 | 无 | 合并要多一个文件，收益不抵成本 |

要「零未使用代码」的发布版，删掉第一、二项即可：删 `Parse.kt` 第 338 行到文件末尾、`Endpoints.ROUTE`、`RouteAndConcurrencyTest.kt`，并在 `TokenMathTest` 去掉 `isConsistent` 两条断言，然后重新 `./gradlew test assembleDebug`。其余代码不依赖它们。
