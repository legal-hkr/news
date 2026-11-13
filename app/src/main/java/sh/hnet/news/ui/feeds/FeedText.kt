package sh.hnet.news.ui.feeds

import androidx.annotation.StringRes
import sh.hnet.news.R
import sh.hnet.news.data.Feed
import sh.hnet.news.data.Folder
import sh.hnet.news.feed.NoFeedFoundException
import sh.hnet.news.feed.NotAFeedException
import sh.hnet.news.net.FetchFailure
import java.text.Collator

/** The sentence for a failed lookup of a feed (SPEC §7.1, §10.4). */
@StringRes
fun lookupError(error: Throwable): Int = when (error) {
    is FetchFailure.NoNetwork -> R.string.error_no_network
    is FetchFailure.Refused -> R.string.error_refused_or_login
    is FetchFailure.NoAnswer -> R.string.error_site_no_answer
    is FetchFailure.NotFound, is NoFeedFoundException, is NotAFeedException -> R.string.error_no_feed_found
    else -> R.string.error_site_no_answer
}

/** Feeds and folders by name, as the language orders them (ł after l, not after z). */
fun List<Feed>.byName(): List<Feed> = Collator.getInstance().let { collator -> sortedWith { a, b -> collator.compare(a.title, b.title) } }

fun List<Folder>.foldersByName(): List<Folder> = Collator.getInstance().let { collator -> sortedWith { a, b -> collator.compare(a.name, b.name) } }

/** Feeds and folders as Settings › Feeds orders them (SPEC §6.6): the user's own order, or by name. */
fun List<Feed>.ordered(own: Boolean): List<Feed> = if (own) byName().sortedBy { it.position } else byName()

fun List<Folder>.foldersOrdered(own: Boolean): List<Folder> = if (own) foldersByName().sortedBy { it.position } else foldersByName()

/** Where a feed goes: no folder, one that exists, or a new one of this name. */
sealed interface FolderChoice {
    data object None : FolderChoice
    data class Existing(val folder: Folder) : FolderChoice
    data class New(val name: String) : FolderChoice
}

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val PROBLEM_DAYS = 3
private const val PROBLEM_ATTEMPTS = 3

/**
 * For a feed with problems (SPEC §10.3), the days since its refreshes started to fail: every one
 * for 3 days and at least 3 attempts, "No network" not counted. Null for any other feed.
 */
fun Feed.failingDays(now: Long = System.currentTimeMillis()): Int? {
    val since = failingSince ?: return null
    if (failedAttempts < PROBLEM_ATTEMPTS) return null
    return ((now - since) / DAY_MS).toInt().takeIf { it >= PROBLEM_DAYS }
}

fun List<Feed>.withProblems(): Int = count { it.failingDays() != null }

/** The sentence for a feed's last failed refresh (SPEC §10.4), by the stored name of its kind. */
@StringRes
fun failureSentence(kind: String?): Int = when (kind) {
    "NO_NETWORK" -> R.string.error_no_network
    "NOT_FOUND" -> R.string.error_address_not_found
    "NOT_A_FEED" -> R.string.error_not_a_feed
    "REFUSED" -> R.string.error_site_refuses
    else -> R.string.error_site_no_answer
}
