package timbo.homebrew.magpiescreen.ui

import java.time.LocalDate
import java.time.ZonedDateTime
import java.text.SimpleDateFormat
import java.util.Date
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

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dayMinutes = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    fun clock(millis: Long): String = clock.format(Date(millis))
    /** Reset times: MM-dd HH:mm, no seconds. */
    fun isoShortMinutes(iso: String?): String {
        if (iso.isNullOrBlank()) return "未提供"
        val millis = timbo.homebrew.magpiescreen.data.parseIsoMillis(iso)
        return if (millis != null) dayMinutes.format(Date(millis)) else iso
    }

    /** Calendar day as the gateway stamped it ("2026-10-27T07:00:00+08:00" -> 2026-10-27). */
    fun gatewayDate(iso: String?): LocalDate? {
        if (iso.isNullOrBlank() || iso.length < 10) return null
        return runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()
    }

    /** The whole timestamp with the gateway's offset, for hour-level buckets. */
    fun gatewayZoned(iso: String?): ZonedDateTime? {
        if (iso.isNullOrBlank()) return null
        return runCatching { ZonedDateTime.parse(iso) }.getOrNull()
    }

}
