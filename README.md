# Magpie 监控（magpie-screen）

给 [Magpie](https://usemagpie.ai) 网关用的横屏用量看板：把网关的 token/费用/订阅额度画在一台小屏设备上长期挂在桌面上。

目标设备是 **小爱触屏音箱 LX04**（Android 8.1 / API 27，800×480px @240dpi，即 533×320dp），横屏锁定。同尺寸的平板、旧手机也能装。

## 界面

四个入口：**总览 · 请求 · 额度 · 设置**。底栏 36dp，状态栏 24dp，内容区固定 260dp，总览页不滚动。

| 页面 | 内容 |
|---|---|
| 总览 | KPI 四格（总 Token / 缓存命中率 / 请求数 / 错误数）、24 小时柱状图（每根柱标 token 数，点柱子弹出该小时的请求数、Token 与按模型的拆分）、模型用量前 3、底部一行输入/输出/缓存读/缓存写/推理 |
| 请求 | 近 26 周日历热力图（左侧周几、顶部月份带年份），每格一天，点格子弹出当天请求数/Token 与按模型的拆分 |
| 额度 | 订阅额度卡片（各计费窗口的剩余百分比 + 进度条 + 重置时间）与额度趋势：横轴取最长窗口，虚线是「匀速用完」的参考线，实线是真实剩余，标题右侧给出「按此速度，能撑到重置 / 无法撑到重置」 |
| 设置 | 网关地址 + API key（可显示）、保存并测试连接，逐项列出网关能力（统计 / 额度 / 实时并发 / 请求明细） |

界面文案为中文，数值格式统一由 `Fmt` 处理（`3.14M`、`72.2%`、`1.2K`、`10-27 19:36`）。

**刷新策略**：页面在前台时每 5 分钟自动重拉一次，从后台回到前台立刻拉一次；页面可见期间保持屏幕常亮（`FLAG_KEEP_SCREEN_ON`），离开页面即释放，所以挂在墙上不会停在旧数字上。手机息屏/切到后台后不再请求。

## 数据来源

App 只读 Magpie 网关的管理接口，全部为 `GET`，带 `Authorization: Bearer <gateway key>`：

| 接口 | 用途 |
|---|---|
| `/v1/magpie/usage?period=today\|7d\|30d\|all` | 总览的 KPI、趋势桶、模型排行 |
| `/v1/magpie/usage/requests?period=…&offset=&limit=`，或 `?day=YYYY-MM-DD` | 请求页与气泡的按天明细（单页上限 500 条，App 自动翻页并缓存） |
| `/v1/magpie/quotas?kept=1` | 额度卡片（订阅窗口、剩余百分比、重置时间） |
| `/v1/magpie/quotas/history` | 额度趋势的历史采样点 |
| `/v1/magpie/concurrency` | 设置页的能力自检 |

约定：

- **时间归属用网关自己的时区**（响应里形如 `2026-10-27T07:00:00+08:00`），不按手机时区换算，否则跨时区会把一天的数据算到前一天。
- 网关按天聚合的历史通常只保留最近约 30 天，所以热力图前期大部分格子是空的，随时间自然补齐。
- 地址规则见 `net/UrlRules`：必须 `http(s)://`，去掉结尾 `/v1` 以免重复，不接受用户名密码、查询参数或 fragment。

## 构建与运行

需要 JDK 17、Android SDK 35。

```bash
./gradlew test            # 单元测试（URL 规则、token 口径、route/concurrency 解析）
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
app/src/main/java/ai/usemagpie/dashboard/
  MainActivity.kt          底栏四个入口，切页即重建 Fragment
  net/Gateway.kt           OkHttp 客户端、错误分类、地址规则、接口路径
  data/AppPrefs.kt         地址与 key（EncryptedSharedPreferences，Keystore 不可用时退化为普通偏好）
  data/Parse.kt            网关 JSON → 数据类（usage / requests / quotas / limit / concurrency / route）
  ui/OverviewFragment.kt   总览：KPI、柱状图、排行、点柱气泡
  ui/RequestsFragment.kt   请求：热力图、点格气泡
  ui/QuotasFragment.kt     额度：订阅卡片 + 趋势 + 速度判定
  ui/SettingsFragment.kt   设置：连接与能力自检
  ui/SeriesChartView.kt    柱状图（总览用；点按回调报告柱索引）
  ui/HeatmapView.kt        日历热力图（53/26 周 × 7 天，可点）
  ui/QuotaTrendView.kt     额度趋势折线（多窗口、实线/虚线）
  ui/BubbleView.kt         统一的小气泡（标题 + 总计 + 三列表格 + 自动夹边 + 定时关闭）
  ui/DayRequests.kt        按天分页拉请求明细并按模型聚合，按日期缓存
  ui/Fmt.kt                数字/时间格式化，网关时区解析
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
- release 包当前未签名：`./gradlew assembleRelease` 产出 `app-release-unsigned.apk`，需要自己的 keystore 签名后才能分发。
- 订阅额度只来自当前连接的网关；如果 ClinePass 之类的订阅挂在另一台 Magpie 上，那台网关需要对外提供 `/v1/magpie/quotas` 才能看到。

## 许可

仓库暂未附带 LICENSE 文件，默认保留所有权利；打算开源的话请先补一个。
