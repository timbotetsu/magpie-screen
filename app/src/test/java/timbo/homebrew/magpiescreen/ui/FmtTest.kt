package timbo.homebrew.magpiescreen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FmtTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val newYork = ZoneId.of("America/New_York")

    private fun hour(iso: String, zone: ZoneId): Int? = Fmt.localZoned(iso, zone)?.hour

    @Test
    fun `utc buckets render on the device clock`() {
        val iso = "2026-10-27T12:00:00Z"
        assertEquals(20, hour(iso, shanghai))
        assertEquals(21, hour(iso, tokyo))
        assertEquals(8, hour(iso, newYork))
    }

    @Test
    fun `the same instant can fall on a different device date`() {
        val iso = "2026-10-27T23:30:00Z"
        assertEquals(LocalDate.of(2026, 10, 28), Fmt.localDate(iso, shanghai))
        assertEquals(LocalDate.of(2026, 10, 27), Fmt.localDate(iso, newYork))
        // The gateway still queried its own 10-27 bucket.
        assertEquals(LocalDate.of(2026, 10, 27), Fmt.gatewayDate(iso))
    }

    @Test
    fun `offset timestamps are converted too`() {
        val iso = "2026-10-27T07:00:00+08:00"
        assertEquals(7, hour(iso, shanghai))
        assertEquals(8, hour(iso, tokyo))
        assertEquals("10-27", Fmt.dayLabel(iso, tokyo))
    }

    @Test
    fun `a timestamp without an offset is read as device local time`() {
        assertEquals(20, hour("2026-10-27T20:00:00", shanghai))
    }

    @Test
    fun `broken timestamps have no local form`() {
        assertNull(Fmt.localZoned("", shanghai))
        assertNull(Fmt.localDate("not a time", shanghai))
        assertNull(Fmt.localZoned(null, shanghai))
        assertNull(Fmt.dayLabel("2026-13-99T99:99:99", shanghai))
    }
}
