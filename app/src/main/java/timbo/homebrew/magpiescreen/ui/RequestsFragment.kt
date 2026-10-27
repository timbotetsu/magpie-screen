package timbo.homebrew.magpiescreen.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import timbo.homebrew.magpiescreen.R
import timbo.homebrew.magpiescreen.data.RequestsPage
import timbo.homebrew.magpiescreen.data.UsageSnapshot
import timbo.homebrew.magpiescreen.databinding.FragmentRequestsBinding
import timbo.homebrew.magpiescreen.net.Endpoints
import timbo.homebrew.magpiescreen.net.GatewayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The requests tab is a year view: one cell per day, shaded by how many calls it saw. */
class RequestsFragment : BaseFragment() {

    private var binding: FragmentRequestsBinding? = null
    private var snapshot: UsageSnapshot? = null
    private var lastUpdatedMillis = 0L
    private var loadVersion = 0
    // Constructed on first use: a field initialiser would run before the fragment is attached.
    private val dayRequests by lazy { DayRequests(app.gateway) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentRequestsBinding.inflate(inflater, container, false)
        binding = b
        b.refresh.setOnClickListener { load() }
        // Draw the empty grid at its final size while the first request is in flight.
        b.heatmap.setData(emptyMap(), null, LocalDate.now(), columns = WEEKS, fillHeight = false) { day, x, y ->
            showBubble(day, x, y)
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
        val conn = app.connection()
        if (conn == null) {
            b.status.text = "未配置连接"
            return
        }
        val version = ++loadVersion
        b.status.text = "读取中…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                app.gateway.get(conn, Endpoints.USAGE, listOf("period" to YEAR_PERIOD))
            }
            if (version != loadVersion) return@launch
            val view = binding ?: return@launch
            val parsed = (result as? GatewayResult.Ok)?.let { ok ->
                runCatching { UsageSnapshot.parse(ok.body) }.getOrNull()
            }
            when {
                result is GatewayResult.Err -> view.status.text = "读取失败：${describe(result)}"
                parsed == null -> view.status.text = "响应不可解析：usage 返回的不是预期结构"
                else -> {
                    snapshot = parsed
                    lastUpdatedMillis = System.currentTimeMillis()
                    render()
                }
            }
        }
    }

    private fun render() {
        val b = binding ?: return
        val snap = snapshot ?: return
        val stats = snap.stats

        b.summary.text = buildString {
            append("调用 ${Fmt.raw(stats.calls)}")
            append(" · 错误 ${Fmt.raw(stats.errors)}")
            append(" · 总 Token ${Fmt.compact(stats.tokens.total)}")
        }
        b.status.text = "更新 ${Fmt.clock(lastUpdatedMillis)}"

        val days = HashMap<LocalDate, HeatmapView.Day>()
        snap.series.forEach { bucket ->
            val date = Fmt.localDate(bucket.time) ?: return@forEach
            val gatewayDate = Fmt.gatewayDate(bucket.time) ?: return@forEach
            days[date] = HeatmapView.Day(
                date = date,
                gatewayDate = gatewayDate,
                calls = bucket.stats.calls.toInt(),
                errors = bucket.stats.errors.toInt(),
                tokens = bucket.stats.tokens.total,
                cost = bucket.stats.cost,
            )
        }
        val since = Fmt.localDate(snap.since)
        val anchor = days.keys.maxOrNull() ?: LocalDate.now()
        b.heatmap.setData(days, since, anchor, columns = WEEKS, fillHeight = false) { day, x, y ->
            showBubble(day, x, y)
        }

        val active = days.values.count { it.calls > 0 }
        val gridStart = anchor.with(java.time.DayOfWeek.MONDAY).minusWeeks((WEEKS - 1).toLong())
        b.footer.text = buildString {
            append("近 $WEEKS 周 · ")
            append("${gridStart.year}年${gridStart.monthValue}月${gridStart.dayOfMonth}日")
            append(" 到 ")
            append("${anchor.year}年${anchor.monthValue}月${anchor.dayOfMonth}日")
            append(" · 有记录 $active 天")
            if (active == 0) append(" · 尚未有可展示的记录")
        }
    }

    /** Small bubble next to the tapped cell: day totals first, model rows when they arrive. */
    private fun showBubble(day: HeatmapView.Day, cellX: Float, cellTop: Float) {
        val root = binding?.root ?: return
        val heatmap = binding?.heatmap ?: return
        val loc = IntArray(2)
        heatmap.getLocationOnScreen(loc)
        val handle = BubbleView.show(
            parent = root,
            anchorPointX = loc[0] + cellX.toInt(),
            anchorPointY = loc[1] + cellTop.toInt(),
            below = false,
            title = "${day.date.year}年${day.date.monthValue}月${day.date.dayOfMonth}日",
            totals = "请求 ${Fmt.raw(day.calls.toLong())} · Token ${Fmt.compact(day.tokens)}",
        )
        lifecycleScope.launch {
            // The cell is labelled with the device's date, but ?day= takes the gateway's.
            val models = loadDayModels(day.gatewayDate)
            if (!handle.isShowing) return@launch
            handle.setRows(
                models.take(MAX_MODEL_ROWS).map {
                    BubbleView.Row(it.name, Fmt.raw(it.calls.toLong()), Fmt.compact(it.tokens))
                },
                if (models.size > MAX_MODEL_ROWS) "…还有 ${models.size - MAX_MODEL_ROWS} 个模型" else null,
            )
        }
    }

    private suspend fun loadDayModels(date: LocalDate): List<DayRequests.ModelTotals> {
        val conn = app.connection() ?: return emptyList()
        val rows = dayRequests.rows(conn, date)
        return if (rows.isEmpty()) emptyList() else dayRequests.byModel(rows)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** The gateway answers with daily buckets and clamps to what it retains. */
        const val YEAR_PERIOD = "365d"
        const val WEEKS = 18
        const val MAX_MODEL_ROWS = 4
        const val CARD_VISIBLE_MS = 5000L
    }
}
