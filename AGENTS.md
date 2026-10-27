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
| `ui/OverviewFragment.kt` | 总览：KPI、柱状图、排行、点柱气泡；请求版本号防竞态 | `SeriesChartView`、`BubbleView`、`DayRequests` |
| `ui/RequestsFragment.kt` | 热力图 + 点格气泡 | `HeatmapView`、`DayRequests` |
| `ui/QuotasFragment.kt` | 订阅卡片 + 趋势 + 速度判定 | `QuotaTrendView`、`Parse.Quotas` |
| `ui/SettingsFragment.kt` | 连接保存、能力自检 | `Gateway`、`Parse` |
| `ui/BaseFragment.kt` | 每 5 分钟定时刷新（`onRefreshTick`）、回前台立即刷新、页面可见时保持屏幕常亮 | 各页 `onRefreshTick()` 实现 |
| `ui/BubbleView.kt` | 唯一的气泡实现（两侧共用） | — |
| `ui/DayRequests.kt` | 按天明细分页 + 按模型聚合 + 按天缓存 | 500 条/页、3000 行上限 |

## 必须遵守的不变量

1. **屏幕预算固定**：状态栏 24dp + 底栏 36dp → 内容 260dp。总览页不允许滚动，任何新增块都要在同一预算内重排；热力图/额度页各自内部滚动。
2. **Fragment 里不要用字段初始化器碰 `app`**：`private val x = Foo(app.gateway)` 会在 `onAttach` 之前执行并抛 `Fragment not attached to an activity`（踩过一次）。用 `by lazy` 或放到 `onCreateView`。
3. **时间按网关时区**：响应时间是 `…+08:00` 形式，用 `Fmt.gatewayDate()` / `Fmt.gatewayZoned()`；不要用设备时区换算，否则跨时区会错一天。
4. **图表 API 签名顺序**：`SeriesChartView.setData(points, style = …, formatter)` —— `style` 在 `formatter` 之前是为了保留尾随 lambda 写法（`setData(points) { … }`）。改签名必须同步 `OverviewFragment` 与 `QuotasFragment` 的调用点。
5. **额度页只显示订阅卡**：不显示「本机 key 限制」；无卡片时给一行说明即可（历史上曾误加，已按要求删除）。
6. **数据字段不臆造**：只渲染网关返回的字段，缺失用「未提供」；不要把余额、活跃天数、连续天数、热力图以外的指标编出来。
7. **颜色与尺寸用 token**：`values/colors.xml`（含 `heat0..4`、`series1..4`、`bar`）和 `values/themes.xml` 的 style；不要在代码里硬编码十六进制或 dp 字号。
8. **一切请求只读**：只发 `GET`；不要新增写接口、后台上报或第三方依赖。

## 交互约定

- 点柱子 / 点热力图格子 → 用 `BubbleView.show(...)`，5 秒自动消失、点外部关闭；气泡内容：时间范围 + 请求数/Token + 按模型三列（最多 4 行，多出写「…还有 N 个模型」）。
- 周期 chip 只切换时间窗口，不弹气泡。
- 刷新节奏集中在 `BaseFragment`：可见时每 `REFRESH_INTERVAL_MS`（5 分钟）调一次 `onRefreshTick()`，回前台立即调；`onStop` 必须让定时器和 `FLAG_KEEP_SCREEN_ON` 都停下。设置页覆盖为空实现（表单不该被自动重置）。
- 排名行不可点击。
- 加载失败时保留上一次成功的数据，状态行写明失败原因；总览额外显示「快照 HH:mm:ss」。

## 数据口径

- `Tokens.total = input + output + cacheRead + cacheWrite`；`reasoning` 已含在 output 内，**不重复累加**。
- 模型维度取请求时指定的 `model`（不是实际服务的 `served`）。
- 单页请求上限 500 条，按 `offset` 翻页；一天通常 2–3 页。

## 发版

- 版本在 `app/build.gradle.kts`：`versionCode` / `versionName`，发版前递增 `versionCode`。
- `assembleRelease` 产物未签名（`isMinifyEnabled = false`），发布需要自备 keystore。
- `applicationId = ai.usemagpie.dashboard`，`minSdk 27`，`targetSdk 35`。

## 已知取舍（不影响发布，改动前先确认）

这三条不是缺陷，是「留着有用、删了也行」的选择，量化如下：

| 项 | 体量 | 影响 | 为什么还在 |
|---|---|---|---|
| `Parse.kt` 里 `Concurrency` / `ActiveRequest` / `Routes`（第 338–434 行）与 `Endpoints.ROUTE` | 约 97 行 + `RouteAndConcurrencyTest.kt` 69 行 | 运行时零调用，只被单测使用 | 它们是网关 `/v1/magpie/concurrency`、`/route` 的响应契约；将来做「实时请求 / 路由追踪」时直接可用。`Endpoints.CONCURRENCY` 仍被设置页能力自检使用，不能删 |
| `RequestsPage` 的 `through/direct/reported/isConsistent` | 约 12 行 | UI 不读，但 `isConsistent` 有单测 | 请求页已改成热力图，不再展示这些分项；保留可随时恢复明细页 |
| `QuotasFragment` 352 行、纯代码搭视图 | — | 可读性 | 拆 `QuotaCardView` 属结构性改动，发版前不做 |
| 三个 Fragment 各一个 1 行私有 `dp()` | 3 行 | 无 | 合并要多一个文件，收益不抵成本 |

要「零未使用代码」的发布版，删掉第一、二项即可：删 `Parse.kt` 第 338 行到文件末尾、`Endpoints.ROUTE`、`RouteAndConcurrencyTest.kt`，并在 `TokenMathTest` 去掉 `isConsistent` 两条断言，然后重新 `./gradlew test assembleDebug`。其余代码不依赖它们。
