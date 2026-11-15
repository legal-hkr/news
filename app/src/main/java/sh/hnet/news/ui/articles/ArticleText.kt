package sh.hnet.news.ui.articles

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.news.LastRefresh
import sh.hnet.news.Look
import sh.hnet.news.R
import sh.hnet.news.data.ArticleRow
import sh.hnet.news.refresh.RefreshProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** A row's look and preview lines: the feed's own when it has one, else the app's (SPEC §4.10). */
fun ArticleRow.look(appLook: Look): Look = if (ownLook) Look.entries.firstOrNull { it.name == look } ?: appLook else appLook

fun ArticleRow.previewLines(appLines: Int): Int = if (ownLook) previewLines else appLines

fun dayOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** "Today", "Yesterday", then "Monday, 9 November", with the year when it is not this one (SPEC §4.4). */
@Composable
fun dayHeader(day: LocalDate, today: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (day) {
        today -> stringResource(R.string.label_today)
        today.minusDays(1) -> stringResource(R.string.label_yesterday)
        else -> DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (day.year == today.year) "EEEEdMMMM" else "EEEEdMMMMy"), locale).format(day)
            .replaceFirstChar { it.titlecase(locale) }
    }
}

/** The time of day in the system's 12 or 24 hour form. */
fun timeOfDay(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

/** "12 Nov", for a moment on another day; with the year when it is not this one. */
fun shortDate(locale: Locale, millis: Long, zone: ZoneId): String {
    val day = dayOf(millis, zone)
    val pattern = DateFormat.getBestDateTimePattern(locale, if (day.year == LocalDate.now(zone).year) "dMMM" else "dMMMy")
    return DateTimeFormatter.ofPattern(pattern, locale).format(day)
}

/**
 * The list's subtitle (SPEC §4.8, §4.9): the refresh under way, else the last one; nothing before
 * the first. Failures of single feeds don't appear here.
 *
 * @param empty No article is stored yet: the first fetch has its own wording
 */
@Composable
fun refreshSubtitle(progress: RefreshProgress?, last: LastRefresh?, empty: Boolean): String? {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    if (progress != null) {
        return stringResource(if (progress.again) R.string.msg_refetching else if (empty) R.string.msg_first_refresh else R.string.msg_refreshing, progress.done, progress.total)
    }
    if (last == null) return null
    val zone = ZoneId.systemDefault()
    val time = timeOfDay(context, last.at)
    // On another day the date comes first: "11 Nov, 14:20"
    val moment = if (dayOf(last.at, zone) == LocalDate.now(zone)) time else stringResource(R.string.label_date_time, shortDate(locale, last.at, zone), time)
    return if (last.offline) stringResource(R.string.msg_no_network_refreshed, moment) else stringResource(R.string.msg_refreshed, moment, last.new)
}

/** Counts are shown as they are up to 9999 (SPEC §17). */
fun countText(count: Int): String = if (count > 9999) "9999+" else count.toString()
