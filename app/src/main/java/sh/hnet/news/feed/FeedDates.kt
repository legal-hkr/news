package sh.hnet.news.feed

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Dates as feeds write them. RSS means RFC 822 ("Fri, 13 Nov 2026 13:29:48 +0100") and Atom means
 * RFC 3339 ("2026-11-13T13:29:48+01:00"), but feeds of twenty years carry every variation: two-digit
 * years, no seconds, no weekday, zone names, a space for the T, no zone at all. Read leniently; a
 * date that can't be read is null, never an error.
 */
object FeedDates {
    private val iso = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:[Tt\s]+(\d{1,2}):(\d{2})(?::(\d{2})(?:[.,]\d+)?)?)?\s*([Zz]|[+-]\d{2}(?::?\d{2})?|[A-Za-z]{2,5})?""")
    private val rfc822 = Regex("""(\d{1,2})[\s-]+(\p{L}{3,})\.?,?[\s-]+(\d{2,4})(?:,?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?\s*([+-]\d{2}:?\d{2}|[A-Za-z]{1,5})?""")
    // "Nov 13, 2026 13:29" – the American order, seen in hand-made feeds
    private val american = Regex("""(\p{L}{3,})\.?\s+(\d{1,2}),?\s+(\d{4})(?:,?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?\s*([+-]\d{2}:?\d{2}|[A-Za-z]{1,5})?""")

    // English, and Polish for feeds whose software localized the date
    private val months = listOf(
        listOf("jan", "sty"), listOf("feb", "lut"), listOf("mar"), listOf("apr", "kwi"), listOf("may", "maj"), listOf("jun", "cze"),
        listOf("jul", "lip"), listOf("aug", "sie"), listOf("sep", "wrz"), listOf("oct", "paź", "paz"), listOf("nov", "lis"), listOf("dec", "gru"),
    )

    // RFC 822's names and the European ones met in practice; hours from UTC
    private val zones = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0, "WET" to 0, "WEST" to 1, "BST" to 1, "CET" to 1, "MET" to 1, "CEST" to 2, "MEST" to 2,
        "EET" to 2, "EEST" to 3, "MSK" to 3, "EST" to -5, "EDT" to -4, "CST" to -6, "CDT" to -5, "MST" to -7, "MDT" to -6, "PST" to -8, "PDT" to -7,
    )

    fun parse(text: String?): Instant? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        iso.find(value)?.let { m ->
            val (year, month, day, hour, minute, second, zone) = m.destructured
            return instant(year.toInt(), month.toInt(), day.toInt(), hour, minute, second, zone)
        }
        rfc822.find(value)?.let { m ->
            val (day, month, year, hour, minute, second, zone) = m.destructured
            val number = month(month) ?: return@let
            return instant(year(year), number, day.toInt(), hour, minute, second, zone)
        }
        american.find(value)?.let { m ->
            val (month, day, year, hour, minute, second, zone) = m.destructured
            val number = month(month) ?: return@let
            return instant(year.toInt(), number, day.toInt(), hour, minute, second, zone)
        }
        return null
    }

    private fun month(name: String): Int? {
        val lower = name.lowercase()
        val index = months.indexOfFirst { names -> names.any { lower.startsWith(it) } }
        return if (index < 0) null else index + 1
    }

    /** RFC 822 has two-digit years; RFC 2822 reads 00–49 as 2000–2049 and the rest as 19xx. Three digits count from 1900. */
    private fun year(text: String): Int {
        val value = text.toInt()
        return when (text.length) {
            2 -> if (value < 50) 2000 + value else 1900 + value
            3 -> 1900 + value
            else -> value
        }
    }

    private fun instant(year: Int, month: Int, day: Int, hour: String, minute: String, second: String, zone: String): Instant? = try {
        val local = LocalDateTime.of(year, month, day, hour.toIntOrNull() ?: 0, minute.toIntOrNull() ?: 0, second.toIntOrNull() ?: 0)
        local.toInstant(offset(zone))
    } catch (_: DateTimeException) {
        null
    }

    /** No zone, or one we don't know (RFC 822's military letters among them, which RFC 2822 calls unreliable): UTC. */
    private fun offset(zone: String): ZoneOffset {
        if (zone.isEmpty()) return ZoneOffset.UTC
        if (zone[0] == '+' || zone[0] == '-') {
            val digits = zone.drop(1).replace(":", "")
            val hours = digits.take(2).toInt()
            val minutes = digits.drop(2).toIntOrNull() ?: 0
            if (hours > 18 || minutes > 59) return ZoneOffset.UTC
            val seconds = hours * 3600 + minutes * 60
            return ZoneOffset.ofTotalSeconds(if (zone[0] == '-') -seconds else seconds)
        }
        return ZoneOffset.ofHours(zones[zone.uppercase()] ?: 0)
    }
}
