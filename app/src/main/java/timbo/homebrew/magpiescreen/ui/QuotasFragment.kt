package timbo.homebrew.magpiescreen.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import timbo.homebrew.magpiescreen.R
import timbo.homebrew.magpiescreen.data.QuotaCard
import timbo.homebrew.magpiescreen.data.QuotaHistory
import timbo.homebrew.magpiescreen.data.parseIsoMillis
import timbo.homebrew.magpiescreen.data.QuotaWindow
import timbo.homebrew.magpiescreen.data.Quotas
import timbo.homebrew.magpiescreen.databinding.FragmentQuotasBinding
import timbo.homebrew.magpiescreen.net.ConnectionSettings
import timbo.homebrew.magpiescreen.net.Endpoints
import timbo.homebrew.magpiescreen.net.GatewayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Subscription allowances only: the cards the gateway reports for the subscriptions it serves
 * (ClinePass, Claude, …). Nothing here reads this key's own limits.
 */
class QuotasFragment : BaseFragment() {

    private var binding: FragmentQuotasBinding? = null
    private var cards: List<QuotaCard> = emptyList()
    private var history: List<QuotaHistory> = emptyList()
    private var providers: List<String> = emptyList()
    private var selected: String? = null
    private var loadError: String? = null
    private var windowColors: Map<String, Int> = emptyMap()
    /** false: month axis; true: week axis. */
    private var weeklyTrend = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentQuotasBinding.inflate(inflater, container, false)
        binding = b
        b.refresh.setOnClickListener { load() }
        b.trend.setOnClickListener {
            weeklyTrend = !weeklyTrend
            renderHistory(b, animate = true)
            b.trendMode.alpha = 0f
            b.trendMode.animate().alpha(1f).setDuration(180L).start()
        }
        load()
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    override fun onRefreshTick() {
        load()
    }

    private fun load() {
        val b = binding ?: return
        val source = app.connection()
        if (source == null) {
            b.status.text = "未配置连接"
            return
        }
        b.status.text = "读取中…"
        lifecycleScope.launch {
            val cardsResult = withContext(Dispatchers.IO) {
                app.gateway.get(source, Endpoints.QUOTAS, listOf("kept" to "1"))
            }
            val historyResult = withContext(Dispatchers.IO) {
                app.gateway.get(source, Endpoints.QUOTA_HISTORY)
            }
            val view = binding ?: return@launch

            loadError = (cardsResult as? GatewayResult.Err)?.let { describe(it) }
            cards = (cardsResult as? GatewayResult.Ok)?.let { r ->
                runCatching { Quotas.parseCards(r.body) }.getOrNull()
            }.orEmpty()
            history = (historyResult as? GatewayResult.Ok)?.let { r ->
                runCatching { Quotas.parseHistory(r.body) }.getOrNull()
            }.orEmpty()

            providers = cards.map { it.provider }.filter { it.isNotBlank() }.distinct()
            if (selected !in providers) selected = providers.firstOrNull()

            val palette = intArrayOf(R.color.series1, R.color.series2, R.color.series3, R.color.series4)
            val names = (cards.flatMap { card -> card.windows.map { it.name } } +
                history.flatMap { entry -> entry.lines.map { it.name } })
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            windowColors = names.mapIndexed { index, name ->
                name to requireContext().getColor(palette[index % palette.size])
            }.toMap()

            view.status.text = if (loadError != null) {
                "读取失败：$loadError"
            } else {
                "${providers.size} 个供应商 · ${cards.size} 张卡片 · 更新 ${Fmt.clock(System.currentTimeMillis())}"
            }
            renderProviders(view)
            renderCards(view)
            renderHistory(view)
        }
    }

    private fun renderProviders(b: FragmentQuotasBinding) {
        b.providers.removeAllViews()
        b.providerScroll.visibility = if (providers.size > 1) View.VISIBLE else View.GONE
        if (providers.size <= 1) return
        providers.forEach { provider ->
            val chip = TextView(requireContext()).apply {
                text = provider
                isSelected = provider == selected
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(context.getColor(if (provider == selected) R.color.accent else R.color.muted))
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                setBackgroundResource(R.drawable.chip_bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(20),
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    selected = provider
                    binding?.let { view ->
                        renderProviders(view)
                        renderCards(view)
                        renderHistory(view)
                    }
                }
            }
            b.providers.addView(chip)
        }
    }

    private fun renderCards(b: FragmentQuotasBinding) {
        b.cards.removeAllViews()
        if (cards.isEmpty()) {
            val message = loadError?.let { "额度读取失败：$it" }
                ?: "暂无订阅额度卡片。设置页填写上游 magpie 地址与 key 后，这里显示 ClinePass 等订阅额度。"
            b.cards.addView(note(message))
            return
        }
        val shown = cards.filter { it.provider == selected }
        shown.forEach { b.cards.addView(cardView(it)) }
        fillColumn(b, shown)
    }

    private fun cardView(card: QuotaCard): LinearLayout {
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(7), dp(5), dp(7), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }
        val title = when {
            card.name.isBlank() -> card.provider
            card.name.equals(card.provider, ignoreCase = true) -> card.name
            else -> "${card.provider} · ${card.name}"
        }
        box.addView(text(title, 11, R.color.fg))
        card.error?.let { box.addView(text("error：$it", 10, R.color.error)) }

        if (card.windows.isEmpty()) {
            box.addView(text("窗口：未提供", 10, R.color.muted))
        } else {
            card.windows.forEachIndexed { index, window -> box.addView(windowView(window, index)) }
        }

        val stamp = listOfNotNull(
            card.plan?.let { "plan $it" },
            card.balance?.let { "balance $it" },
        ).joinToString(" · ")
        if (stamp.isNotEmpty()) box.addView(text(stamp, 9, R.color.muted))
        return box
    }

    private fun windowView(window: QuotaWindow, index: Int): LinearLayout {
        val block = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { if (index == 0) topMargin = dp(5) }
        }
        val remaining = percentOf(window)
        val head = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val seriesColor = windowColors[window.name] ?: requireContext().getColor(R.color.series1)
        head.addView(weighted(text(windowLabel(window.name), 11, R.color.fg).apply { setTextColor(seriesColor) }))
        head.addView(
            text(
                when {
                    window.unlimited -> "不限量"
                    window.text != null -> window.text
                    remaining != null -> "剩余 ${Fmt.percent(remaining / 100.0)}"
                    else -> "未提供"
                },
                11,
                if (remaining != null && remaining <= 10) R.color.error else R.color.accent,
            ).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                if (remaining == null || remaining > 10) setTextColor(seriesColor)
            },
        )
        block.addView(head)

        if (remaining != null && !window.unlimited && window.text == null) {
            block.addView(
                ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = remaining
                    val fill = if (remaining <= 10) R.color.error else R.color.bar
                    progressTintList = android.content.res.ColorStateList.valueOf(context.getColor(fill))
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.line))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(4),
                    ).apply {
                        topMargin = dp(3)
                        bottomMargin = dp(3)
                    }
                },
            )
        }
        val facts = listOfNotNull(
            window.resetsAt?.let { "将重置于 ${Fmt.isoShortMinutes(it)}" },
        ).joinToString(" · ")
        if (facts.isNotEmpty()) {
            block.addView(
                text(facts, 9, R.color.muted).apply { maxLines = 1 },
            )
        }
        return block
    }

    /** Windows carry either a remaining or a used percentage; both are 0..100. */
    private fun percentOf(window: QuotaWindow): Int? {
        val remaining = window.remaining ?: window.used?.let { 100.0 - it }
        return remaining?.coerceIn(0.0, 100.0)?.roundToInt()
    }

    private fun renderHistory(b: FragmentQuotasBinding, animate: Boolean = false) {
        val entries = history.filter { selected == null || it.provider == selected }
        val windows = entries.flatMap { entry -> entry.lines }
            .filter { it.points.size >= 2 }
            .sortedBy { it.name }

        // The axis is the month by default; tapping the chart zooms it to the current week.
        val bounds = windows.mapNotNull { line ->
            val start = line.windowStart?.let { parseIsoMillis(it) }
            val reset = line.windowReset?.let { parseIsoMillis(it) }
            if (start == null || reset == null || reset <= start) null else Triple(line, start, reset)
        }
        val monthly = bounds.maxByOrNull { it.third - it.second }
        val weekly = bounds
            .filter { (it.third - it.second) in (2 * DAY_MS)..(10 * DAY_MS) }
            .minByOrNull { kotlin.math.abs(it.third - it.second - WEEK_MS) }
        val canToggle = monthly != null && weekly != null
        if (!canToggle) weeklyTrend = false
        val axis = if (weeklyTrend) weekly else monthly
        b.titleHistory.visibility = if (axis != null) View.VISIBLE else View.GONE
        b.trend.visibility = if (axis != null) View.VISIBLE else View.GONE
        b.trendEmpty.visibility = if (axis != null) View.GONE else View.VISIBLE
        if (axis == null) {
            b.trendMode.visibility = View.GONE
            return
        }
        b.trendMode.visibility = if (canToggle) View.VISIBLE else View.GONE
        b.trendMode.text = if (weeklyTrend) {
            "当前按周展示，点击图表切换按月展示"
        } else {
            "当前按月展示，点击图表切换按周展示"
        }
        b.trend.isClickable = canToggle

        val lastPoint = axis.first.points.lastOrNull()
        val nowMs = lastPoint?.first?.let { parseIsoMillis(it) }
        val left = lastPoint?.second
        val holds = if (nowMs != null && left != null && axis.third > axis.second) {
            val expected = 100.0 * (axis.third - nowMs).toDouble() / (axis.third - axis.second).toDouble()
            left >= expected
        } else {
            null
        }
        b.trendHint.visibility = if (holds == null) View.GONE else View.VISIBLE
        b.trendHint.text = when (holds) {
            true -> "按此速度，能撑到重置"
            false -> "按此速度，无法撑到重置"
            null -> ""
        }
        b.trendHint.setTextColor(
            requireContext().getColor(if (holds == false) R.color.error else R.color.accent),
        )

        val series = ArrayList<QuotaTrendView.Series>()
        // Dashed pace lines: the two longest windows, shown in both axis modes.
        bounds
            .sortedByDescending { it.third - it.second }
            .take(2)
            .forEach { (line, start, reset) ->
                val color = windowColors[line.name] ?: return@forEach
                series += QuotaTrendView.Series(
                    line.name,
                    color,
                    dashed = true,
                    points = listOf(QuotaTrendView.Point(start, 100.0), QuotaTrendView.Point(reset, 0.0)),
                )
            }
        // Solid: what was actually measured. The month line stays visible in the week axis too.
        windows.forEach { line ->
            val points = line.points.mapNotNull { (at, left) ->
                parseIsoMillis(at)?.let { QuotaTrendView.Point(it, left) }
            }
            if (points.size < 2) return@forEach
            val color = windowColors[line.name] ?: requireContext().getColor(R.color.series1)
            series += QuotaTrendView.Series(line.name, color, dashed = false, points = points)
        }

        b.trend.setData(series, axis.second, axis.third, "", "", animate = animate)
    }

    /** Grows the cards to the column height so the left side has no dead space. */
    private fun fillColumn(b: FragmentQuotasBinding, shown: List<QuotaCard>) {
        val scroll = b.cards.parent as? View ?: return
        if (shown.isEmpty()) return
        b.cards.post {
            val viewport = scroll.height
            if (viewport <= 0) return@post
            val gap = dp(4) * shown.size // every card carries a 4dp bottom margin
            val perCard = (viewport - gap) / shown.size
            if (perCard <= 0) return@post
            val needed = shown.maxOf { card ->
                dp(22) + card.windows.size.coerceAtLeast(1) * dp(46)
            }
            if (perCard < needed) return@post
            for (i in 0 until b.cards.childCount) {
                val child = b.cards.getChildAt(i)
                child.layoutParams = child.layoutParams.apply { height = perCard }
            }
        }
    }

    /** Gateway window names are English; the board shows Chinese. */
    private fun windowLabel(name: String): String = when (name.trim().lowercase()) {
        "5 hours", "5 hour", "5h" -> "5 小时"
        "1 hour", "1 hours", "1h" -> "1 小时"
        "weekly", "week", "7 days", "7 day" -> "每周"
        "month", "monthly", "30 days" -> "每月"
        "daily", "24 hours", "day" -> "每日"
        else -> name
    }

    private fun weighted(view: TextView): TextView = view.apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun note(message: String): TextView = text(message, 11, R.color.muted).apply {
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun text(value: String, sizeSp: Int, colorRes: Int): TextView = TextView(requireContext()).apply {
        text = value
        textSize = sizeSp.toFloat()
        setTextColor(context.getColor(colorRes))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val WEEK_MS = 7L * DAY_MS
    }
}
