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
    private var loadingKey: String? = null
    private val dayRequests get() = app.dayRequests

    private sealed class Loaded {
        data class Ok(val snapshot: UsageSnapshot, val at: Long) : Loaded()
        data class Failed(val error: GatewayResult.Err) : Loaded()
        object Invalid : Loaded()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentRequestsBinding.inflate(inflater, container, false)
        binding = b
        b.refresh.setOnClickListener { load(force = true) }
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
        load(force = false)
    }

    private fun load(force: Boolean = false) {
        val b = binding ?: return
        val conn = app.connection()
        if (conn == null) {
            b.status.text = "未配置连接"
            return
        }
        val key = "${conn.base}|usage|requests:$YEAR_PERIOD:${LocalDate.now()}"
        if (loadingKey == key) return
        val version = ++loadVersion

        if (!force) {
            app.cache.fresh(key)?.let { cached ->
                val parsed = parseUsageBody(cached.body)
                if (parsed != null) {
                    snapshot = parsed
                    lastUpdatedMillis = cached.fetchedAtWall
                    render()
                    return
                }
            }
            app.cache.get(key)?.let { stale ->
                val parsed = parseUsageBody(stale.body)
                if (parsed != null) {
                    snapshot = parsed
                    lastUpdatedMillis = stale.fetchedAtWall
                    render()
                }
            }
        }

        loadingKey = key
        b.status.text = if (snapshot != null && !force) {
            "缓存 ${Fmt.clock(lastUpdatedMillis)} · 更新中…"
        } else {
            "读取中…"
        }
        lifecycleScope.launch {
            try {
                val loaded = app.cache.singleFlight(key) {
                    if (!force) {
                        app.cache.fresh(key)?.let { cached ->
                            parseUsageBody(cached.body)?.let {
                                return@singleFlight Loaded.Ok(it, cached.fetchedAtWall)
                            }
                        }
                    }
                    val result = withContext(Dispatchers.IO) {
                        app.gateway.get(conn, Endpoints.USAGE, listOf("period" to YEAR_PERIOD))
                    }
                    when {
                        result is GatewayResult.Err -> Loaded.Failed(result)
                        else -> {
                            val body = (result as GatewayResult.Ok).body
                            val parsed = parseUsageBody(body)
                            if (parsed == null) {
                                Loaded.Invalid
                            } else {
                                app.cache.put(key, body)
                                Loaded.Ok(parsed, app.cache.get(key)?.fetchedAtWall ?: System.currentTimeMillis())
                            }
                        }
                    }
                }
                if (version != loadVersion) return@launch
                val view = binding ?: return@launch
                when (loaded) {
                    is Loaded.Ok -> {
                        snapshot = loaded.snapshot
                        lastUpdatedMillis = loaded.at
                        render()
                    }
                    is Loaded.Failed -> view.status.text = "读取失败：${describe(loaded.error)}"
                    Loaded.Invalid -> view.status.text = "响应不可解析：usage 返回的不是预期结构"
                }
            } finally {
                if (loadingKey == key) loadingKey = null
            }
        }
    }

    private fun parseUsageBody(body: String): UsageSnapshot? =
        runCatching { UsageSnapshot.parse(body) }.getOrNull()

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
