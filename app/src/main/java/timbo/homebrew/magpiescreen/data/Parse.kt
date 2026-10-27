package timbo.homebrew.magpiescreen.data

import org.json.JSONArray
import org.json.JSONObject

/** Token totals. reasoning is reported for information only and is already contained in output. */
data class Tokens(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val reasoning: Long = 0,
) {
    val total: Long get() = input + output + cacheRead + cacheWrite

    companion object {
        val EMPTY = Tokens()

        fun from(obj: JSONObject, inputKey: String = "input", outputKey: String = "output"): Tokens = Tokens(
            input = obj.optLong(inputKey, 0),
            output = obj.optLong(outputKey, 0),
            cacheRead = obj.optLong("cache_read", 0),
            cacheWrite = obj.optLong("cache_write", 0),
            reasoning = obj.optLong("reasoning", 0),
        )
    }
}

data class Stats(
    val tokens: Tokens = Tokens.EMPTY,
    val calls: Long = 0,
    val errors: Long = 0,
    val cost: Double = 0.0,
    val unpriced: Long = 0,
    val timed: Long = 0,
    val ttftMs: Long = 0,
    val decodeMs: Long = 0,
    val decodeOut: Long = 0,
    val decodeN: Long = 0,
) {
    val cacheHitRate: Double? get() = cacheHitRateOf(tokens)
    val averageTtftMs: Double? get() = if (timed > 0) ttftMs.toDouble() / timed else null
    val outputTokensPerSecond: Double? get() = if (decodeMs > 0 && decodeN > 0) 1000.0 * decodeOut / decodeMs else null

    companion object {
        fun cacheHitRateOf(t: Tokens): Double? {
            val den = t.input + t.cacheRead + t.cacheWrite
            if (den <= 0) return null
            val r = t.cacheRead.toDouble() / den
            return if (r in 0.0..1.0) r else null
        }

        fun from(obj: JSONObject?, inputKey: String = "input", outputKey: String = "output"): Stats {
            if (obj == null) return Stats()
            return Stats(
                tokens = Tokens.from(obj, inputKey, outputKey),
                calls = obj.optLong("calls", 0),
                errors = obj.optLong("errors", 0),
                cost = obj.optDouble("cost", 0.0),
                unpriced = obj.optLong("unpriced", 0),
                timed = obj.optLong("timed", 0),
                ttftMs = obj.optLong("ttft_ms", 0),
                decodeMs = obj.optLong("decode_ms", 0),
                decodeOut = obj.optLong("decode_out", 0),
                decodeN = obj.optLong("decode_n", 0),
            )
        }
    }
}

data class Bucket(val label: String, val time: String, val stats: Stats)

data class Group(
    val id: String,
    val name: String,
    val stats: Stats,
)

data class UsageSnapshot(
    val period: String,
    val since: String,
    val bucket: String,
    val stats: Stats,
    val series: List<Bucket>,
    val agents: List<Group>,
    val models: List<Group>,
    val sessionCount: Int,
) {
    companion object {
        fun parse(json: String): UsageSnapshot {
            val o = JSONObject(json)
            val series = ArrayList<Bucket>()
            val seriesArray = o.optJSONArray("series")
            if (seriesArray != null) for (i in 0 until seriesArray.length()) {
                val obj = seriesArray.optJSONObject(i) ?: continue
                series += Bucket(
                    label = obj.optString("label"),
                    time = obj.optString("time"),
                    stats = Stats.from(obj),
                )
            }
            return UsageSnapshot(
                period = o.optString("period"),
                since = o.optString("since"),
                bucket = o.optString("bucket"),
                stats = Stats.from(o),
                series = series,
                agents = groups(o.optJSONArray("agents")),
                models = groups(o.optJSONArray("models")),
                sessionCount = o.optInt("sessionCount", 0),
            )
        }

        private fun groups(arr: JSONArray?): List<Group> {
            if (arr == null) return emptyList()
            val out = ArrayList<Group>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifEmpty { obj.optString("model") }
                val name = obj.optString("name").ifEmpty { obj.optString("model").ifEmpty { id } }
                out += Group(id = id, name = name, stats = Stats.from(obj))
            }
            return out
        }
    }
}

data class RequestRow(
    val timeMillis: Long,
    val agent: String?,
    val model: String?,
    val served: String?,
    val provider: String?,
    val status: Int?,
    val error: String?,
    val durationMs: Long?,
    val ttftMs: Long?,
    val firstTextMs: Long?,
    val tokens: Tokens,
    val cost: Double?,
    val priced: Boolean?,
    val source: String?,
    val kind: String?,
) {
    /** A record without a status field is "unknown", never a failure. */
    val isFailure: Boolean get() = (status ?: 0) >= 400 || !error.isNullOrBlank()
}

data class RequestsPage(
    val period: String,
    val bucket: String,
    val total: Long,
    val rows: List<RequestRow>,
    val stats: Stats,
    val throughCalls: Long,
    val directCalls: Long,
    val reportedCalls: Long,
    val throughTokens: Long,
    val directTokens: Long,
    val isConsistent: Boolean,
    val pageTotal: Long,
) {
    companion object {
        fun parse(json: String, previousTotal: Long? = null): RequestsPage {
            val o = JSONObject(json)
            val rows = ArrayList<RequestRow>()
            val rowsArray = o.optJSONArray("rows")
            if (rowsArray != null) for (i in 0 until rowsArray.length()) {
                val obj = rowsArray.optJSONObject(i) ?: continue
                val status = if (obj.has("status")) obj.optInt("status", 0) else null
                rows += RequestRow(
                    timeMillis = obj.optTimestampMillis("t"),
                    agent = obj.optString("agent").takeIf { it.isNotEmpty() && it != "null" },
                    model = obj.optString("model").takeIf { it.isNotEmpty() && it != "null" },
                    served = obj.optString("served").takeIf { it.isNotEmpty() && it != "null" },
                    provider = obj.optString("provider").takeIf { it.isNotEmpty() && it != "null" },
                    status = status,
                    error = obj.optString("err").takeIf { it.isNotEmpty() && it != "null" },
                    durationMs = obj.optLongOrNull("ms"),
                    ttftMs = obj.optLongOrNull("ttft_ms"),
                    firstTextMs = obj.optLongOrNull("first_text_ms"),
                    tokens = Tokens.from(obj, "in", "out"),
                    cost = if (obj.has("cost")) obj.optDouble("cost", 0.0) else null,
                    priced = if (obj.has("priced")) obj.optBoolean("priced", false) else null,
                    source = obj.optString("source").takeIf { it.isNotEmpty() && it != "null" },
                    kind = obj.optString("kind").takeIf { it.isNotEmpty() && it != "null" },
                )
            }
            val totals = Stats.from(o)
            val through = o.optJSONObject("through")
            val direct = o.optJSONObject("direct")
            val reported = o.optJSONObject("reported")
            val pageTotal = o.optLong("total", 0)
            return RequestsPage(
                period = o.optString("period"),
                bucket = o.optString("bucket"),
                total = pageTotal,
                rows = rows,
                stats = totals,
                throughCalls = through?.optLong("calls", 0) ?: 0,
                directCalls = direct?.optLong("calls", 0) ?: 0,
                reportedCalls = reported?.optLong("calls", 0) ?: 0,
                throughTokens = through?.let { Tokens.from(it).total } ?: 0,
                directTokens = direct?.let { Tokens.from(it).total } ?: 0,
                isConsistent = previousTotal == null || previousTotal == pageTotal,
                pageTotal = pageTotal,
            )
        }
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key, 0) else null

/** Row timestamps arrive either as epoch numbers (s or ms) or as ISO-8601 strings. */
private fun JSONObject.optTimestampMillis(key: String): Long {
    if (!has(key) || isNull(key)) return 0L
    val raw: Any? = opt(key) ?: return 0L
    val toMillis: (Double) -> Long = { v ->
        when {
            v <= 0 -> 0L
            v < 1e12 -> (v * 1000).toLong()
            else -> v.toLong()
        }
    }
    if (raw is Number) return toMillis(raw.toDouble())
    val text = raw.toString().trim()
    if (text.isEmpty()) return 0L
    parseIsoMillis(text)?.let { return it }
    return text.toDoubleOrNull()?.let(toMillis) ?: 0L
}
fun parseIsoMillis(text: String): Long? {
    val direct = runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    if (direct != null) return direct
    val zoned = runCatching {
        java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
    if (zoned != null) return zoned
    return runCatching {
        java.time.Instant.parse(text).toEpochMilli()
    }.getOrNull()
}

data class QuotaWindow(
    val name: String,
    val used: Double?,
    val remaining: Double?,
    val resetsAt: String?,
    val unlimited: Boolean,
    val text: String?,
    val unit: String?,
)

data class QuotaCard(
    val provider: String,
    val name: String,
    val user: String?,
    val plan: String?,
    val balance: String?,
    val error: String?,
    val readAt: String?,
    val asOf: String?,
    val windows: List<QuotaWindow>,
)

data class QuotaHistoryLine(
    val name: String,
    val points: List<Pair<String, Double>>,
    /** Bounds of the window the newest point belongs to; null when the gateway omits them. */
    val windowStart: String? = null,
    val windowReset: String? = null,
)

data class QuotaHistory(val provider: String, val user: String?, val lines: List<QuotaHistoryLine>)

object Quotas {
    fun parseCards(json: String): List<QuotaCard> {
        val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val windows = ArrayList<QuotaWindow>()
            val ws = o.optJSONArray("windows")
            if (ws != null) for (j in 0 until ws.length()) {
                val w = ws.optJSONObject(j) ?: continue
                windows += QuotaWindow(
                    name = w.optString("name"),
                    used = w.optDoubleOrNull("used"),
                    remaining = w.optDoubleOrNull("remaining"),
                    resetsAt = w.optString("resetsAt").takeIf { it.isNotEmpty() },
                    unlimited = w.optBoolean("unlimited", false),
                    text = w.optString("display").takeIf { it.isNotEmpty() && w.optBoolean("text", false) },
                    unit = w.optString("unit").takeIf { it.isNotEmpty() },
                )
            }
            QuotaCard(
                provider = o.optString("provider"),
                name = o.optString("name"),
                user = o.optString("user").takeIf { it.isNotEmpty() },
                plan = o.optString("plan").takeIf { it.isNotEmpty() },
                balance = o.optString("balance").takeIf { it.isNotEmpty() },
                error = o.optString("error").takeIf { it.isNotEmpty() },
                readAt = o.optString("readAt").takeIf { it.isNotEmpty() },
                asOf = o.optString("asOf").takeIf { it.isNotEmpty() },
                windows = windows,
            )
        }
    }

    fun parseHistory(json: String): List<QuotaHistory> {
        val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lines = ArrayList<QuotaHistoryLine>()
            val ls = o.optJSONArray("lines")
            if (ls != null) for (j in 0 until ls.length()) {
                val l = ls.optJSONObject(j) ?: continue
                val points = ArrayList<Pair<String, Double>>()
                var start: String? = null
                var reset: String? = null
                val ps = l.optJSONArray("points")
                if (ps != null) for (k in 0 until ps.length()) {
                    val p = ps.optJSONObject(k) ?: continue
                    val left = p.optDoubleOrNull("left") ?: continue
                    points += p.optString("at") to left
                    p.optString("start").takeIf { it.isNotEmpty() }?.let { start = it }
                    p.optString("resetsAt").takeIf { it.isNotEmpty() }?.let { reset = it }
                }
                lines += QuotaHistoryLine(l.optString("name"), points, start, reset)
            }
            QuotaHistory(o.optString("provider"), o.optString("user").takeIf { it.isNotEmpty() }, lines)
        }
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key, 0.0) else null

data class ActiveRequest(
    val id: String,
    val agent: String?,
    val model: String?,
    val provider: String?,
    val session: String?,
    val elapsedMs: Long?,
    val phase: String?,
)

data class ConcurrencySnapshot(
    val requests: Int,
    val waiting: Int,
    val tools: Int,
    val active: List<ActiveRequest>,
)

object Concurrency {
    fun parse(json: String): ConcurrencySnapshot {
        val root = JSONObject(json)
        val arr = root.optJSONArray("active")
        val out = ArrayList<ActiveRequest>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += ActiveRequest(
                id = o.optString("id"),
                agent = o.optString("agent").takeIf { it.isNotEmpty() },
                model = o.optString("model").takeIf { it.isNotEmpty() },
                provider = o.optString("provider").takeIf { it.isNotEmpty() },
                session = o.optString("session").takeIf { it.isNotEmpty() },
                elapsedMs = o.optLongOrNull("ms"),
                phase = o.optString("phase").takeIf { it.isNotEmpty() },
            )
        }
        return ConcurrencySnapshot(
            requests = root.optInt("requests", out.size),
            waiting = root.optInt("waiting", 0),
            tools = root.optInt("tools", 0),
            active = out,
        )
    }

}

data class RouteAttempt(
    val model: String?,
    val effort: String?,
    val done: Boolean,
    val status: Int?,
    val durationMs: Long?,
)

data class RouteInfo(
    val asked: String?,
    val askedEffort: String?,
    val model: String?,
    val effort: String?,
    val served: String?,
    val done: Boolean,
    val status: Int?,
    val durationMs: Long?,
    val ttftMs: Long?,
    val tries: List<RouteAttempt>,
)

object Routes {
    /** Returns null when the gateway reports route=null for the session. */
    fun parse(json: String): RouteInfo? {
        val o = JSONObject(json)
        if (!o.has("route") || o.isNull("route")) return null
        val route = o.optJSONObject("route") ?: return null
        val tries = ArrayList<RouteAttempt>()
        val arr = route.optJSONArray("tries")
        if (arr != null) for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            tries += RouteAttempt(
                model = t.optString("model").takeIf { it.isNotEmpty() },
                effort = t.optString("effort").takeIf { it.isNotEmpty() },
                done = t.optBoolean("done", false),
                status = if (t.has("status")) t.optInt("status", 0) else null,
                durationMs = t.optLongOrNull("ms"),
            )
        }
        return RouteInfo(
            asked = route.optString("asked").takeIf { it.isNotEmpty() },
            askedEffort = route.optString("asked_effort").takeIf { it.isNotEmpty() },
            model = route.optString("model").takeIf { it.isNotEmpty() },
            effort = route.optString("effort").takeIf { it.isNotEmpty() },
            served = route.optString("served").takeIf { it.isNotEmpty() },
            done = route.optBoolean("done", false),
            status = if (route.has("status")) route.optInt("status", 0) else null,
            durationMs = route.optLongOrNull("ms"),
            ttftMs = route.optLongOrNull("ttft_ms"),
            tries = tries,
        )
    }
}
