package sh.hnet.news.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.NetworkException
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import sh.hnet.news.BuildConfig
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The system's HTTP stack (android.net.http.HttpEngine: HTTP/2 and 3, Brotli), so the app ships no
 * network library. Nothing is kept between requests: no cookies (the engine has no cookie store), no
 * HTTP cache. Redirects are followed. Plain HTTP is allowed by the network security configuration.
 */
class SystemHttp(private val context: Context) : HttpFetcher {
    private val engine: HttpEngine by lazy {
        HttpEngine.Builder(context)
            .setUserAgent(USER_AGENT)
            .setEnableHttp2(true)
            .setEnableQuic(true)
            .setEnableBrotli(true)
            .build()
    }
    private val executor = Executors.newCachedThreadPool()

    override suspend fun get(url: String, maxBytes: Int, validators: Validators?): HttpResponse {
        // Asked first: without a network the engine's own error doesn't always say so
        if (context.getSystemService(ConnectivityManager::class.java).activeNetwork == null) throw FetchFailure.NoNetwork()
        return try {
            withTimeout(TIMEOUT_MS) { request(url, maxBytes, validators) }
        } catch (e: TimeoutCancellationException) {
            throw FetchFailure.NoAnswer("Timed out", e)
        }
    }

    private suspend fun request(url: String, maxBytes: Int, validators: Validators?): HttpResponse = suspendCancellableCoroutine { continuation ->
        val body = ByteArrayOutputStream()
        val callback = object : UrlRequest.Callback {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                if (info.urlChain.size > MAX_REDIRECTS) {
                    request.cancel()
                    if (continuation.isActive) continuation.resumeWithException(FetchFailure.NoAnswer("Too many redirects"))
                } else {
                    request.followRedirect()
                }
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                request.read(ByteBuffer.allocateDirect(BUFFER))
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer) {
                buffer.flip()
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                body.write(chunk)
                if (body.size() > maxBytes) {
                    request.cancel()
                    if (continuation.isActive) continuation.resumeWithException(FetchFailure.NoAnswer("Larger than $maxBytes bytes"))
                    return
                }
                buffer.clear()
                request.read(buffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                if (!continuation.isActive) return
                val failure = failureOf(info.httpStatusCode)
                if (failure != null) {
                    continuation.resumeWithException(failure)
                    return
                }
                fun header(name: String) = info.headers.asMap.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
                val (type, charset) = parseContentType(header("content-type"))
                val unchanged = info.httpStatusCode == 304
                continuation.resume(
                    HttpResponse(
                        info.url, type, charset, if (unchanged) ByteArray(0) else body.toByteArray(),
                        etag = header("etag") ?: validators?.etag.takeIf { unchanged },
                        lastModified = header("last-modified") ?: validators?.lastModified.takeIf { unchanged },
                        notModified = unchanged,
                    ),
                )
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
                if (!continuation.isActive) return
                val offline = error is NetworkException &&
                    (error.errorCode == NetworkException.ERROR_INTERNET_DISCONNECTED || error.errorCode == NetworkException.ERROR_NETWORK_CHANGED)
                continuation.resumeWithException(if (offline) FetchFailure.NoNetwork() else FetchFailure.NoAnswer(error.javaClass.simpleName, error))
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                continuation.cancel()
            }
        }
        val request = try {
            engine.newUrlRequestBuilder(url, executor, callback)
                .addHeader("Accept", ACCEPT)
                .apply {
                    validators?.etag?.let { addHeader("If-None-Match", it) }
                    validators?.lastModified?.let { addHeader("If-Modified-Since", it) }
                }
                .build()
        } catch (e: IllegalArgumentException) {
            // Not an address the engine can ask for
            continuation.resumeWithException(FetchFailure.NoAnswer("Bad address", e))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { request.cancel() }
        request.start()
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val MAX_REDIRECTS = 10
        const val BUFFER = 64 * 1024
        /** Says what the app is and where its source lives; no device or user detail. */
        val USER_AGENT = "News/${BuildConfig.VERSION_NAME.removePrefix("v")} (Android; feed reader; +https://git.hnet.sh/hkr/news)"
        const val ACCEPT = "application/rss+xml, application/atom+xml, application/rdf+xml, application/xml;q=0.9, text/xml;q=0.9, text/html;q=0.8, */*;q=0.5"
    }
}
