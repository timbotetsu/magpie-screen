package timbo.homebrew.magpiescreen.net

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** A successful response body and when it was fetched. */
data class CachedResponse(
    val body: String,
    val fetchedAtWall: Long,
    val fetchedAtElapsed: Long,
)

/**
 * In-memory TTL cache for gateway responses.
 *
 * Fresh entries are served without a request; [get] also exposes stale entries so a page can show
 * its previous data while a refresh runs. [singleFlight] serialises concurrent loads for one key
 * so a timer tick cannot start a second request while the first is still in flight.
 */
class ResponseCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val entries = ConcurrentHashMap<String, CachedResponse>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun fresh(key: String): CachedResponse? =
        entries[key]?.takeIf { elapsedClock() - it.fetchedAtElapsed < ttlMs }

    /** The last successful response, fresh or stale. */
    fun get(key: String): CachedResponse? = entries[key]

    fun put(key: String, body: String) {
        entries[key] = CachedResponse(body, wallClock(), elapsedClock())
    }

    fun clear() {
        entries.clear()
    }

    suspend fun <T> singleFlight(key: String, block: suspend () -> T): T =
        locks.computeIfAbsent(key) { Mutex() }.withLock { block() }

    companion object {
        const val DEFAULT_TTL_MS = 5L * 60 * 1000
    }
}
