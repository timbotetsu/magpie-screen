package ai.usemagpie.dashboard.ui

import ai.usemagpie.dashboard.data.RequestRow
import ai.usemagpie.dashboard.data.RequestsPage
import ai.usemagpie.dashboard.net.ConnectionSettings
import ai.usemagpie.dashboard.net.Endpoints
import ai.usemagpie.dashboard.net.GatewayClient
import ai.usemagpie.dashboard.net.GatewayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * One gateway day's request rows, cached per day. The endpoint caps a page at 500 rows, so a
 * busy day needs a few calls; the heat-map bubble and the chart bubble share this cache.
 */
class DayRequests(private val gateway: GatewayClient) {

    private val cache = HashMap<LocalDate, List<RequestRow>>()

    suspend fun rows(conn: ConnectionSettings, date: LocalDate): List<RequestRow> {
        cache[date]?.let { return it }
        val all = ArrayList<RequestRow>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total && offset < MAX_ROWS) {
            val result = withContext(Dispatchers.IO) {
                gateway.get(
                    conn,
                    Endpoints.REQUESTS,
                    listOf("day" to date.toString(), "offset" to offset.toString(), "limit" to PAGE_SIZE.toString()),
                )
            }
            val page = (result as? GatewayResult.Ok)?.let { ok ->
                runCatching { RequestsPage.parse(ok.body, null) }.getOrNull()
            } ?: return emptyList()
            total = page.pageTotal.toInt()
            all += page.rows
            offset += page.rows.size
            if (page.rows.isEmpty()) break
        }
        cache[date] = all
        return all
    }

    /** Aggregated per model, busiest first. */
    fun byModel(rows: List<RequestRow>): List<ModelTotals> {
        val map = HashMap<String, ModelTotals>()
        rows.forEach { row ->
            val name = row.model ?: row.served ?: "未提供"
            val prev = map[name] ?: ModelTotals(name, 0, 0)
            map[name] = ModelTotals(name, prev.calls + 1, prev.tokens + row.tokens.total)
        }
        return map.values.sortedByDescending { it.calls }
    }

    data class ModelTotals(val name: String, val calls: Int, val tokens: Long)

    private companion object {
        const val PAGE_SIZE = 500
        const val MAX_ROWS = 3000
    }
}
