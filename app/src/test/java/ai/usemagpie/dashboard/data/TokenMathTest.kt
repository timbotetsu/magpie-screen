package ai.usemagpie.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenMathTest {

    @Test
    fun `reasoning is not added on top of output`() {
        val tokens = Tokens(input = 10, output = 20, cacheRead = 30, cacheWrite = 40, reasoning = 5)
        assertEquals(100L, tokens.total)
    }

    @Test
    fun `cache hit rate uses input cache read and cache write as denominator`() {
        val tokens = Tokens(input = 10, output = 20, cacheRead = 30, cacheWrite = 40, reasoning = 5)
        val rate = Stats.cacheHitRateOf(tokens)
        assertEquals(0.375, rate!!, 1e-9)
    }

    @Test
    fun `cache hit rate is unavailable when the denominator is zero`() {
        assertNull(Stats.cacheHitRateOf(Tokens.EMPTY))
    }

    @Test
    fun `performance values need their own denominators`() {
        val stats = Stats(timed = 0, ttftMs = 500, decodeMs = 0, decodeOut = 100, decodeN = 0)
        assertNull(stats.averageTtftMs)
        assertNull(stats.outputTokensPerSecond)

        val measured = Stats(timed = 4, ttftMs = 1000, decodeMs = 500, decodeOut = 250, decodeN = 2)
        assertEquals(250.0, measured.averageTtftMs!!, 1e-9)
        assertEquals(500.0, measured.outputTokensPerSecond!!, 1e-9)
    }

    @Test
    fun `usage snapshot parses totals and groups`() {
        val json = """
            {
              "period": "7d", "since": "2026-10-21T00:00:00+08:00", "bucket": "day",
              "calls": 3, "errors": 1, "input": 10, "output": 20, "cache_read": 30,
              "cache_write": 40, "reasoning": 5, "cost": 0.5, "unpriced": 0,
              "timed": 2, "ttft_ms": 100, "decode_ms": 200, "decode_out": 50, "decode_n": 2,
              "sessionCount": 1,
              "series": [{"label": "Oct 21", "time": "2026-10-21T00:00:00+08:00", "calls": 3,
                          "input": 10, "output": 20, "cache_read": 30, "cache_write": 40, "reasoning": 5}],
              "agents": [{"id": "omp", "name": "omp", "calls": 3, "input": 10, "output": 20,
                          "cache_read": 30, "cache_write": 40, "reasoning": 5}],
              "models": [{"id": "m1", "name": "m1", "calls": 3, "input": 10, "output": 20,
                          "cache_read": 30, "cache_write": 40, "reasoning": 5}]
            }
        """.trimIndent()

        val snapshot = UsageSnapshot.parse(json)
        assertEquals(100L, snapshot.stats.tokens.total)
        assertEquals("7d", snapshot.period)
        assertEquals(1, snapshot.series.size)
        assertEquals(100L, snapshot.series[0].stats.tokens.total)
        assertEquals("omp", snapshot.agents.single().id)
        assertEquals(100L, snapshot.models.single().stats.tokens.total)
        assertEquals(0.375, snapshot.stats.cacheHitRate!!, 1e-9)
    }

    @Test
    fun `request rows treat missing status as unknown instead of failure`() {
        val json = """
            {
              "period": "all", "bucket": "week", "total": 2, "calls": 1, "errors": 0,
              "rows": [
                {"t": "2026-10-27T12:00:00+08:00", "agent": "omp", "in": 1, "out": 2, "status": 0, "source": "log"},
                {"t": "2026-10-27T12:00:01+08:00", "agent": "omp", "in": 1, "out": 2, "status": 502, "err": "upstream"}
              ],
              "through": {"calls": 1, "input": 1, "output": 2}
            }
        """.trimIndent()

        val page = RequestsPage.parse(json)
        assertEquals(2, page.rows.size)
        assertTrue(!page.rows[0].isFailure)
        assertTrue(page.rows[1].isFailure)
        assertTrue(page.rows[0].timeMillis > 0)
        assertEquals(3L, page.rows[0].tokens.total)
    }

    @Test
    fun `page totals that move between reads are reported as inconsistent`() {
        val json = """{"period":"all","bucket":"week","total":10,"rows":[],"calls":0,"errors":0}"""
        assertTrue(RequestsPage.parse(json, 10).isConsistent)
        assertTrue(!RequestsPage.parse(json, 9).isConsistent)
    }
}
