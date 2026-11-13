package sh.hnet.news.net

/** What a site answered. */
class HttpResponse(
    /** The address that answered, after redirects. */
    val url: String,
    /** The media type without parameters, lower case ("text/html"); null when the site named none. */
    val contentType: String?,
    /** The charset the site named, if any. */
    val charset: String?,
    val body: ByteArray,
)

/** Why a fetch failed, as the UI names it (SPEC §10.4). */
sealed class FetchFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The phone has no network. */
    class NoNetwork : FetchFailure("No network")
    /** No such host, no connection, a timeout, a server error, an answer too large to take. */
    class NoAnswer(detail: String, cause: Throwable? = null) : FetchFailure(detail, cause)
    /** 404 or 410: nothing at this address. */
    class NotFound(status: Int) : FetchFailure("HTTP $status")
    /** 401, 403, 407, 429, 451: the site refuses the app or wants a login. */
    class Refused(status: Int) : FetchFailure("HTTP $status")
}

/** Fetches one address. An interface so that the logic above it runs in JVM tests. */
fun interface HttpFetcher {
    /**
     * @param maxBytes A larger answer is a failure, not a truncated body
     * @throws FetchFailure For every failure, an HTTP status of 400 and above among them
     */
    suspend fun get(url: String, maxBytes: Int): HttpResponse
}

object HttpLimits {
    /** Feeds and pages; real ones stay well under. */
    const val DOCUMENT = 8 * 1024 * 1024
    const val ICON = 1024 * 1024
}

/** The failure an HTTP status means, or null for success. */
fun failureOf(status: Int): FetchFailure? = when (status) {
    in 200..299 -> null
    404, 410 -> FetchFailure.NotFound(status)
    401, 403, 407, 429, 451 -> FetchFailure.Refused(status)
    else -> FetchFailure.NoAnswer("HTTP $status")
}

/** "text/html; charset=ISO-8859-2" → ("text/html", "ISO-8859-2"). */
fun parseContentType(header: String?): Pair<String?, String?> {
    val value = header?.trim().orEmpty()
    if (value.isEmpty()) return null to null
    val type = value.substringBefore(';').trim().lowercase().takeIf { it.isNotEmpty() }
    val charset = Regex("""charset\s*=\s*"?([^";\s]+)""", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)
    return type to charset
}
