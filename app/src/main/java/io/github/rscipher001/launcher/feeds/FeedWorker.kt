package io.github.rscipher001.launcher.feeds

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.data.LogRepository
import io.github.rscipher001.launcher.notify.Notifier
import io.github.rscipher001.launcher.data.shortTime
import java.util.Date

/**
 * Fetches due feeds (or the requested one when forced) and reports new items to the terminal log
 * and, for feeds with notify on, as one notification per feed. Runs in the main process.
 */
class FeedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val repo = FeedRepository.get(ctx)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val feedId = inputData.getLong(KEY_FEED_ID, -1L)

        val now = System.currentTimeMillis()
        val targets = when {
            feedId >= 0 -> listOfNotNull(repo.byId(feedId))
            force -> repo.feeds().filter { it.enabled }
            else -> repo.feeds().filter { FeedScheduler.isDue(it, now) }
        }
        if (targets.isEmpty()) return Result.success()

        val results = FeedFetcher(ctx).fetchAll(targets)
        report(ctx, results, explicit = feedId >= 0)
        return Result.success()
    }

    /** One header for the whole run, then up to [MAX_LINES_PER_FEED] lines per feed. */
    private fun report(ctx: Context, results: List<FeedFetcher.Result>, explicit: Boolean) {
        val lines = ArrayList<LogRepository.Pending>()
        val ts = System.currentTimeMillis()
        for (r in results) {
            // Re-read so a title learned during this fetch is used.
            val feed = FeedRepository.get(ctx).byId(r.feed.id) ?: r.feed
            val source = feed.shortName
            val feedCtx = mapOf(CTX_FEED to feed.id.toString())
            if (r.error != null) {
                // Scheduled failures stay in last_status; only a fetch the user asked for is logged.
                if (explicit) {
                    lines += LogRepository.Pending(
                        LogKind.ERROR, "$source: ${r.error}", source, LogContext(feedCtx + (LogContext.URL to feed.url)), ts,
                    )
                }
                continue
            }
            if (r.newItems.isEmpty()) continue
            for (item in r.newItems.take(MAX_LINES_PER_FEED)) {
                lines += LogRepository.Pending(
                    LogKind.SCRIPT,
                    item.title,
                    source,
                    LogContext.of(LogContext.URL to item.link, LogContext.COPY to item.title) + feedCtx,
                    ts,
                )
            }
            val more = r.newItems.size - MAX_LINES_PER_FEED
            if (more > 0) {
                lines += LogRepository.Pending(
                    LogKind.SCRIPT, "+$more more", source,
                    LogContext.of(LogContext.COMMAND to "feeds read ${feed.id}") + feedCtx, ts,
                )
            }
            if (feed.notify) notify(ctx, feed, r.newItems)
        }
        if (lines.isEmpty()) return
        val time = io.github.rscipher001.launcher.data.Config.get(applicationContext).shortTime(ts)
        val header = LogRepository.Pending(
            LogKind.INFO, "── feeds · $time ──", "feeds", LogContext.of(LogContext.RUN_HEADER to "1"), ts,
        )
        val logs = LogRepository.get(ctx)
        logs.addAll(listOf(header) + lines)
        logs.trim()
    }

    private fun notify(ctx: Context, feed: Feed, items: List<FeedItem>) {
        val first = items.first()
        val title = feed.title?.takeIf { it.isNotBlank() } ?: feed.shortName
        val body = if (items.size == 1) first.title else "${items.size} new — ${first.title}"
        Notifier.post(ctx, "feed-${feed.id}", title, body, first.link)
    }

    companion object {
        const val KEY_FEED_ID = "feed_id"
        const val KEY_FORCE = "force"

        /** Log context key carrying the feed id on feed lines. */
        const val CTX_FEED = "feed"
        const val MAX_LINES_PER_FEED = 10
    }
}
