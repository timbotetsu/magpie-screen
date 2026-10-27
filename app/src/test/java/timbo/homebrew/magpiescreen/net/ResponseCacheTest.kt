package timbo.homebrew.magpiescreen.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseCacheTest {

    private var wall = 1_000L
    private var elapsed = 50L

    private val cache = ResponseCache(
        ttlMs = 1_000L,
        wallClock = { wall },
        elapsedClock = { elapsed },
    )

    @Test
    fun `entries are fresh until the ttl elapses`() {
        cache.put("usage", "body")
        val entry = cache.get("usage")!!
        assertEquals(wall, entry.fetchedAtWall)
        assertEquals(50L, entry.fetchedAtElapsed)

        elapsed += 999L
        assertNotNull(cache.fresh("usage"))

        elapsed += 1L
        assertNull(cache.fresh("usage"))
        // A stale entry is still readable so the page can show it while refreshing.
        assertNotNull(cache.get("usage"))
    }

    @Test
    fun `clearing drops every response`() {
        cache.put("usage", "body")
        cache.put("quotas", "body")
        cache.clear()
        assertNull(cache.get("usage"))
        assertNull(cache.get("quotas"))
    }

    @Test
    fun `single flight lets only one caller into a key at a time`() = runBlocking {
        var active = 0
        var maxActive = 0
        val jobs = (1..8).map {
            launch(Dispatchers.Default) {
                cache.singleFlight("same-key") {
                    active++
                    maxActive = maxOf(maxActive, active)
                    delay(10L)
                    active--
                }
            }
        }
        jobs.joinAll()
        assertEquals(1, maxActive)
    }
}
