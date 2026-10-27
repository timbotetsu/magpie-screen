package timbo.homebrew.magpiescreen.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    /** Compact token/magnitude formatting: 950 / 12.3K / 4.56M / 1.23B (max two decimals). */
    fun compact(value: Long): String {
        val v = value.toDouble()
        return when {
            value < 1_000 -> value.toString()
            value < 1_000_000 -> decimals(v / 1_000.0) + "K"
            value < 1_000_000_000 -> decimals(v / 1_000_000.0) + "M"
            else -> decimals(v / 1_000_000_000.0) + "B"
        }
    }

    private fun decimals(v: Double): String {
        val s = String.format(Locale.US, "%.2f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    /** Shorter than [compact]: at most five characters, for chart labels above narrow bars. */
    fun compactShort(value: Long): String {
        val v = value.toDouble()
        return when {
            value < 1_000 -> value.toString()
            value < 1_000_000 -> Math.round(v / 1_000.0).toString() + "K"
            value < 10_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
            else -> Math.round(v / 1_000_000.0).toString() + "M"
        }
    }

    fun raw(value: Long): String = String.format(Locale.US, "%,d", value)

    fun percent(ratio: Double?): String {
        if (ratio == null) return "未提供"
        return String.format(Locale.US, "%.1f%%", ratio * 100)
    }

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val dayMinutesFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)

    fun clock(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(clockFormat)

    /** Reset times: MM-dd HH:mm, no seconds, on the device clock. */
    fun isoShortMinutes(iso: String?): String {
        if (iso.isNullOrBlank()) return "未提供"
        val zoned = localZoned(iso) ?: return iso
        return zoned.format(dayMinutesFormat)
    }

    /**
     * The gateway's timestamp moved into the device's zone: the wall clock the user compares
     * against. Gateway instances in different zones serialise the same instant with different
     * offsets (`Z`, `+08:00`), so display must never trust the offset in the string.
     */
    fun localZoned(iso: String?, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        val instant = instantOf(iso, zone) ?: return null
        return instant.atZone(zone)
    }

    /** Calendar date of a gateway timestamp in the device's zone. */
    fun localDate(iso: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        localZoned(iso, zone)?.toLocalDate()

    /** Hour-of-day label ("08") for a fixed 00–23 chart axis. */
    fun hourOfDay(hour: Int): String = String.format(Locale.US, "%02d", hour)

    /** Date label ("10-27") in the device's zone, for daily and weekly chart buckets. */
    fun dayLabel(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
        localZoned(iso, zone)?.let { String.format(Locale.US, "%02d-%02d", it.monthValue, it.dayOfMonth) }

    /**
     * The gateway's own calendar date. Use only where the gateway interprets the date itself
     * (`/usage/requests?day=`); dates shown to the user come from [localDate].
     */
    fun gatewayDate(iso: String?): LocalDate? {
        if (iso.isNullOrBlank() || iso.length < 10) return null
        return runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()
    }

    /** Absolute instant of a gateway timestamp; a bare local timestamp is read in [zone]. */
    private fun instantOf(iso: String?, zone: ZoneId): Instant? {
        if (iso.isNullOrBlank()) return null
        runCatching { OffsetDateTime.parse(iso) }.getOrNull()?.let { return it.toInstant() }
        runCatching { LocalDateTime.parse(iso).atZone(zone) }.getOrNull()?.let { return it.toInstant() }
        return null
    }
}
