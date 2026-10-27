package timbo.homebrew.magpiescreen.ui

import android.os.SystemClock
import timbo.homebrew.magpiescreen.data.RequestRow
import timbo.homebrew.magpiescreen.data.RequestsPage
import timbo.homebrew.magpiescreen.net.ConnectionSettings
import timbo.homebrew.magpiescreen.net.Endpoints
import timbo.homebrew.magpiescreen.net.GatewayClient
import timbo.homebrew.magpiescreen.net.GatewayResult
import timbo.homebrew.magpiescreen.net.ResponseCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * One gateway day's request rows, shared across pages and cached for five minutes. The endpoint
 * caps a page at 500 rows, so a busy day needs a few calls; concurrent callers for one day wait
 * for a single load.
 */
class DayRequests(private val gateway: GatewayClient) {

    private data class Entry(val rows: List<RequestRow>, val fetchedAtElapsed: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun rows(conn: ConnectionSettings, date: LocalDate): List<RequestRow> {
        val key = "${conn.base}|requests|$date"
        fresh(key)?.let { return it.rows }
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            fresh(key)?.rows?.let { return@withLock it }
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
                } ?: return@withLock emptyList()
                total = page.pageTotal.toInt()
                all += page.rows
                offset += page.rows.size
                if (page.rows.isEmpty()) break
            }
            cache[key] = Entry(all, SystemClock.elapsedRealtime())
            all
        }
    }

    fun clear() {
        cache.clear()
    }

    private fun fresh(key: String): Entry? =
        cache[key]?.takeIf { SystemClock.elapsedRealtime() - it.fetchedAtElapsed < ResponseCache.DEFAULT_TTL_MS }

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
