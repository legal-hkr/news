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

/** Where a feed goes: no folder, one that exists, or a new one of this name. */
sealed interface FolderChoice {
    data object None : FolderChoice
    data class Existing(val folder: Folder) : FolderChoice
    data class New(val name: String) : FolderChoice
}
