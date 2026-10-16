package io.github.rscipher001.launcher.feeds

import android.content.Context
import io.github.rscipher001.launcher.script.Net
import io.github.rscipher001.launcher.script.RssParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

/**
 * Downloads and stores feeds. Uses conditional GETs (ETag / Last-Modified) so unchanged feeds cost
 * a 304 and no parsing. Every attempt is recorded in `feeds.last_status`.
 */
class FeedFetcher(context: Context) {
    private val repo = FeedRepository.get(context)

    /** Outcome of one feed in [fetchAll]; [error] is null on success (including 304). */
    data class Result(val feed: Feed, val newItems: List<FeedItem>, val error: String?)

    /**
     * Fetches [feed] and stores unseen items. Returns them newest first (empty on 304).
     * Failures are recorded as `error: ...` and rethrown.
     */
    suspend fun fetch(feed: Feed): List<FeedItem> = withContext(Dispatchers.IO) {
        try {
            fetchInternal(feed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { repo.recordFetch(feed.id, "error: ${describe(e)}") }
            throw e
        }
    }

    /** Fetches each feed in turn; one failing feed never stops the rest, and this never throws (bar cancellation). */
    suspend fun fetchAll(feeds: List<Feed>): List<Result> = feeds.map { feed ->
        try {
            Result(feed, fetch(feed), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result(feed, emptyList(), describe(e))
        }
    }

    private suspend fun fetchInternal(feed: Feed): List<FeedItem> {
        val request = Request.Builder()
            .url(feed.url)
            .header("User-Agent", Net.USER_AGENT)
            .header("Accept", "application/rss+xml, application/atom+xml, application/rdf+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5")
            .apply {
                feed.etag?.let { header("If-None-Match", it) }
                feed.lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()
        val call = Net.client.newCall(request)
        // Abort the blocking call if the worker is stopped.
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        val (body, etag, lastModified) = try {
            call.execute().use { resp ->
                if (resp.code == 304) {
                    repo.recordFetch(feed.id, "not modified")
                    return emptyList()
                }
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                Triple(readCapped(resp), resp.header("ETag"), resp.header("Last-Modified"))
            }
        } finally {
            cancelHandle.dispose()
        }

        val parsed = RssParser.parse(body)
        if (parsed.items.isEmpty() && parsed.title == null) throw IOException("not a feed")
        val fresh = repo.insertItems(feed.id, parsed.items.map {
            FeedRepository.NewItem(
                guid = it.id.ifBlank { it.link ?: it.title },
                title = it.title,
                link = it.link,
                publishedText = it.date,
                summary = it.summary,
            )
        })
        val newTitle = parsed.title?.takeIf { feed.title.isNullOrBlank() && it.isNotBlank() }
        repo.recordFetch(feed.id, "ok ${fresh.size} new", etag, lastModified, newTitle)
        return fresh
    }

    /** Reads at most [MAX_BODY_BYTES], failing beyond that, and decodes using the declared charset. */
    private fun readCapped(resp: Response): String {
        val body = resp.body
        if (body.contentLength() > MAX_BODY_BYTES) throw IOException("feed too large")
        val out = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BODY_BYTES) throw IOException("feed too large (> 3 MB)")
            }
        }
        val bytes = out.toByteArray()
        val charset = body.contentType()?.charset() ?: xmlDeclaredCharset(bytes) ?: Charsets.UTF_8
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    companion object {
        const val MAX_BODY_BYTES = 3 * 1024 * 1024

        private val ENCODING = Regex("^<\\?xml[^>]*encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']")

        /** `encoding="..."` from the XML prolog, when the server didn't send a charset. */
        internal fun xmlDeclaredCharset(bytes: ByteArray): Charset? {
            val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            val name = ENCODING.find(head)?.groupValues?.get(1) ?: return null
            return runCatching { Charset.forName(name) }.getOrNull()
        }

        /** One-line reason for `last_status` and the log. */
        fun describe(e: Throwable): String =
            e.message?.lines()?.firstOrNull { it.isNotBlank() }?.take(120) ?: e.javaClass.simpleName
    }
}
