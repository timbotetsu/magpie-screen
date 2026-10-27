package timbo.homebrew.magpiescreen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAndConcurrencyTest {

    @Test
    fun `concurrency snapshot keeps counters and active requests`() {
        val json = """
            {
              "object": "concurrency", "requests": 1, "waiting": 2, "tools": 3,
              "active": [
                {"id": "r1", "agent": "omp", "model": "m1", "provider": "p1",
                 "session": "s1", "started": "2026-10-27T12:00:00+08:00", "ms": 1500, "phase": "stream"}
              ]
            }
        """.trimIndent()

        val snapshot = Concurrency.parse(json)
        assertEquals(1, snapshot.requests)
        assertEquals(2, snapshot.waiting)
        assertEquals(3, snapshot.tools)
        val active = snapshot.active.single()
        assertEquals("omp", active.agent)
        assertEquals(1500L, active.elapsedMs)
        assertEquals("s1", active.session)
        assertEquals("stream", active.phase)
    }

    @Test
    fun `empty concurrency payload still reports zero counters`() {
        val snapshot = Concurrency.parse("""{"active":[],"requests":0,"tools":0,"waiting":0}""")
        assertTrue(snapshot.active.isEmpty())
        assertEquals(0, snapshot.requests)
    }

    @Test
    fun `route parses the attempt chain`() {
        val json = """
            {"route":{"id":1,"time":"2026-10-27T11:22:57.5067+08:00","asked":"a/model-1","asked_effort":"low",
                      "model":"a/model-1","effort":"low","served":"b/model-2","tries":[
                        {"model":"a/model-1","effort":"low","done":false,"status":502,"ms":120},
                        {"model":"a/model-1","effort":"high","done":true,"status":200,"ms":680}
                      ],"done":true,"status":200,"ms":900,"ttft_ms":3912},
             "seq":7,"session":"s1"}
        """.trimIndent()

        val route = Routes.parse(json)!!
        assertEquals("a/model-1", route.asked)
        assertEquals("low", route.askedEffort)
        assertEquals("b/model-2", route.served)
        assertTrue(route.done)
        assertEquals(900L, route.durationMs)
        assertEquals(3912L, route.ttftMs)
        assertEquals(2, route.tries.size)
        assertEquals(502, route.tries[0].status)
        assertTrue(!route.tries[0].done)
        assertEquals("high", route.tries[1].effort)
        assertTrue(route.tries[1].done)
    }

    @Test
    fun `null route is reported as no route instead of a failure`() {
        assertNull(Routes.parse("""{"route":null,"seq":0,"session":"s1"}"""))
    }
}
