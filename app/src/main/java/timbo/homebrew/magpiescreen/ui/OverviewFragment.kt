package timbo.homebrew.magpiescreen.ui

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import timbo.homebrew.magpiescreen.R
import timbo.homebrew.magpiescreen.data.Group
import timbo.homebrew.magpiescreen.data.UsageSnapshot
import timbo.homebrew.magpiescreen.databinding.FragmentOverviewBinding
import timbo.homebrew.magpiescreen.databinding.ItemGroupBinding
import timbo.homebrew.magpiescreen.net.Endpoints
import timbo.homebrew.magpiescreen.net.GatewayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class OverviewFragment : BaseFragment() {

    private var binding: FragmentOverviewBinding? = null
    private var period = "today"
    private var snapshot: UsageSnapshot? = null
    private var lastUpdatedMillis: Long = 0
    private var loadVersion = 0
    private val adapter = RankingAdapter()
    private val dayRequests by lazy { DayRequests(app.gateway) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentOverviewBinding.inflate(inflater, container, false)
        binding = b

        b.chipToday.setOnClickListener { switchPeriod("today") }
        b.chip7d.setOnClickListener { switchPeriod("7d") }
        b.chip30d.setOnClickListener { switchPeriod("30d") }
        b.chipAll.setOnClickListener { switchPeriod("all") }
        b.refresh.setOnClickListener { load() }
        b.ranking.layoutManager = LinearLayoutManager(requireContext())
        b.ranking.adapter = adapter

        applyChips()
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

    private fun switchPeriod(value: String) {
        period = value
        applyChips()
        load()
    }

    private fun applyChips() {
        val b = binding ?: return
        b.chipToday.isSelected = period == "today"
        b.chip7d.isSelected = period == "7d"
        b.chip30d.isSelected = period == "30d"
        b.chipAll.isSelected = period == "all"
    }

    private fun load() {
        val b = binding ?: return
        val conn = app.connection()
        if (conn == null) {
            b.status.text = "未配置连接，请到设置页填写网关地址与 API key"
            return
        }
        val version = ++loadVersion
        b.status.text = "读取中…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                app.gateway.get(conn, Endpoints.USAGE, listOf("period" to period))
            }
            if (version != loadVersion) return@launch
            val view = binding ?: return@launch
            val parsed = (result as? GatewayResult.Ok)?.let { ok ->
                runCatching { UsageSnapshot.parse(ok.body) }.getOrNull()
            }
            when {
                result is GatewayResult.Err -> {
                    // Snapshot stamp first so truncation can never hide it.
                    view.status.text = SpannableStringBuilder().apply {
                        if (snapshot != null) append("快照 ${Fmt.clock(lastUpdatedMillis)} · ")
                        append("读取失败", ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.error)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        append("：${describe(result)}")
                    }
                }
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
        val tokens = stats.tokens

        b.mToken.text = Fmt.compact(tokens.total)
        b.mCache.text = Fmt.percent(stats.cacheHitRate)
        b.mCalls.text = Fmt.compact(stats.calls)
        b.mErrors.text = Fmt.compact(stats.errors)

        b.breakdown.text = buildString {
            append("输入 ${Fmt.compact(tokens.input)}")
            append(" · 输出 ${Fmt.compact(tokens.output)}")
            append(" · 缓存读 ${Fmt.compact(tokens.cacheRead)}")
            append(" · 缓存写 ${Fmt.compact(tokens.cacheWrite)}")
            append(" · 推理 ${Fmt.compact(tokens.reasoning)}")
        }

        val head = "已连接"
        b.status.text = SpannableStringBuilder()
            .append(head, ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.accent)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append(" · ${periodLabel(snap.period)} · 更新 ${Fmt.clock(lastUpdatedMillis)}")

        val points = snap.series.map { bucket ->
            SeriesChartView.Point(bucket.label, bucket.stats.tokens.total.toDouble())
        }
        b.chart.setData(
            points,
            SeriesChartView.Style(valueLabels = true, allPointLabels = true, maxLabel = false),
        ) { value -> Fmt.compactShort(value.toLong()) }
        b.chart.onPointTap = { index -> showBucketBubble(index) }

        val groups = snap.models
            .sortedWith(compareByDescending<Group> { it.stats.tokens.total }.thenBy { it.id })
            .take(3)
        b.rankingEmpty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(groups, groups.maxOfOrNull { it.stats.tokens.total } ?: 0L)
    }

    /** Tapping a bar pops that bucket's window, its totals and the per-model split. */
    private fun showBucketBubble(index: Int) {
        val b = binding ?: return
        val snap = snapshot ?: return
        val bucket = snap.series.getOrNull(index) ?: return
        val start = Fmt.gatewayZoned(bucket.time)
        val hourly = snap.bucket == "hour"
        val title = buildString {
            if (start == null) {
                append(bucket.label)
            } else {
                append("${start.year}年${start.monthValue}月${start.dayOfMonth}日")
                if (hourly) append(" ${"%02d".format(start.hour)}:00–${"%02d".format((start.hour + 1) % 24)}:00")
            }
        }
        val chart = b.chart
        val loc = IntArray(2)
        chart.getLocationOnScreen(loc)
        val slot = chart.width.toFloat() / snap.series.size.coerceAtLeast(1)
        val handle = BubbleView.show(
            parent = b.root,
            anchorPointX = (loc[0] + slot * (index + 0.5f)).toInt(),
            anchorPointY = loc[1] + dp(2),
            below = true,
            title = title,
            totals = "请求 ${Fmt.raw(bucket.stats.calls)} · Token ${Fmt.compact(bucket.stats.tokens.total)}",
        )
        if (start == null) {
            handle.setRows(emptyList(), null)
            return
        }
        val date = start.toLocalDate()
        val hour = start.hour
        lifecycleScope.launch {
            val conn = app.connection() ?: return@launch
            val rows = dayRequests.rows(conn, date)
            val scoped = if (hourly) {
                rows.filter { row -> Instant.ofEpochMilli(row.timeMillis).atZone(start.zone).hour == hour }
            } else {
                rows
            }
            if (!handle.isShowing) return@launch
            val totals = dayRequests.byModel(scoped)
            handle.setRows(
                totals.take(4).map {
                    BubbleView.Row(it.name, Fmt.raw(it.calls.toLong()), Fmt.compact(it.tokens))
                },
                if (totals.size > 4) "…还有 ${totals.size - 4} 个模型" else null,
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun periodLabel(period: String): String = when (period) {
        "today" -> "今日"
        "7d" -> "7日"
        "30d" -> "30日"
        "all" -> "全部"
        else -> period
    }

    private class RankingAdapter : RecyclerView.Adapter<RankingAdapter.Holder>() {

        private var groups: List<Group> = emptyList()
        private var maxTokens: Long = 0
        fun submit(groups: List<Group>, maxTokens: Long) {
            this.groups = groups
            this.maxTokens = maxTokens
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun getItemCount(): Int = groups.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val group = groups[position]
            val tokens = group.stats.tokens.total
            holder.binding.name.text = group.name
            holder.binding.value.text = Fmt.compact(tokens)
            val ratio = if (maxTokens <= 0) 0 else (tokens * 1000 / maxTokens).toInt()
            holder.binding.bar.progress = ratio
        }

        class Holder(val binding: ItemGroupBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
