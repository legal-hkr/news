package sh.hnet.news.refresh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import sh.hnet.news.Preferences

/**
 * The refreshes the user causes – the Refresh button, a pull, a feed just subscribed, an OPML
 * import – all pass here, because all of them keep the promise of "Only on unmetered networks"
 * (SPEC §10.5): on a metered network they wait for the answer to "Refresh on a metered network?",
 * asked once however many of them wait. Refreshes the app starts itself never ask: in the
 * background the system holds them back, and on opening the app they are left out.
 */
class RefreshRequests(private val context: Context, private val preferences: Preferences, private val refresher: Refresher, private val scope: CoroutineScope) {
    /** What waits for the answer: a refresh ([feedIds] null for every feed), or with [refetch] stored articles to fetch again. */
    class Waiting(val feedIds: Collection<Long>?, val refetch: Refetch? = null)

    private val _waiting = MutableStateFlow<Waiting?>(null)

    /** Not null while the question is to be shown. */
    val waiting: StateFlow<Waiting?> = _waiting

    /** Only a network that is there can be metered: without one a refresh says "No network" itself. */
    fun metered(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
    }

    /**
     * A refresh the user caused.
     * @param feedIds The feeds; null for all
     * @param afterCurrent Waits for the refresh under way instead of being dropped because of it (new feeds must get their first fetch)
     */
    fun request(feedIds: Collection<Long>?, afterCurrent: Boolean = false) {
        scope.launch {
            if (preferences.refreshSettings().unmeteredOnly && metered()) {
                // One question for everything that waits
                _waiting.update { earlier ->
                    Waiting(if (earlier == null) feedIds else if (earlier.feedIds == null || feedIds == null) null else earlier.feedIds + feedIds)
                }
            } else {
                start(feedIds, textOnly = false, afterCurrent)
            }
        }
    }

    /** Stored articles fetched again, at the user's wish (Settings › Storage): the same promise, the same question. */
    fun requestRefetch(which: Refetch) {
        scope.launch {
            if (preferences.refreshSettings().unmeteredOnly && metered()) _waiting.value = Waiting(null, which) else refresher.refetch(which)
        }
    }

    /** The answer: everything, text only (images wait for the next refresh), or null for not now. */
    fun answer(textOnly: Boolean?) {
        val waiting = _waiting.getAndUpdate { null } ?: return
        if (textOnly == null) return
        if (waiting.refetch != null) refresher.refetch(waiting.refetch, textOnly) else scope.launch { start(waiting.feedIds, textOnly, afterCurrent = true) }
    }

    /** When the app opens (SPEC §14.5): the app's own refresh, so on a metered network it follows the setting without asking. */
    fun onOpen() {
        scope.launch {
            val settings = preferences.refreshSettings()
            if (settings.onOpen && !(settings.unmeteredOnly && metered())) refresher.start()
        }
    }

    private suspend fun start(feedIds: Collection<Long>?, textOnly: Boolean, afterCurrent: Boolean) {
        if (!afterCurrent) {
            refresher.start(feedIds, textOnly = textOnly)
            return
        }
        // Several requests may wait for the same refresh, and only one of them starts next: the others wait again
        while (refresher.start(feedIds, textOnly = textOnly) == null) refresher.current()?.join() ?: yield()
    }
}
