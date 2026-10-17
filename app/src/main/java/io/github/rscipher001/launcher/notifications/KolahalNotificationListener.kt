package io.github.rscipher001.launcher.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.rscipher001.launcher.apps.AppRepository
import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.data.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Feeds posted notifications into [NotificationStore] and, unless muted, the terminal log
 * (as [LogKind.SCRIPT] lines whose context carries [CTX_KEY] so long-press can open/dismiss).
 */
class KolahalNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val userManager by lazy { getSystemService(UserManager::class.java) }
    private val ranking = Ranking()

    /** Last terminal write per key: (time, line). Only touched on the main thread. */
    private val lastLogged = HashMap<String, Pair<Long, String>>()

    override fun onListenerConnected() {
        instance = WeakReference(this)
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        NotificationStore.replaceAll(active.filter(::accept).map { toNotif(it) to it.notification.contentIntent })
        mirror()
    }

    private var mirrorJob: kotlinx.coroutines.Job? = null

    /** Scripts run in the :script process, so they read a snapshot of the feed from the shared database. */
    private fun mirror() {
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            kotlinx.coroutines.delay(400)
            runCatching {
                io.github.rscipher001.launcher.data.KvStore(this@KolahalNotificationListener, MIRROR_SCOPE)
                    .set(MIRROR_KEY, io.github.rscipher001.launcher.script.Json.stringify(NotificationStore.recent(50)))
            }
        }
    }

    override fun onListenerDisconnected() {
        if (instance?.get() === this) instance = null
        // Access revoked or unbound: don't leave a copy of other apps' notifications on disk.
        mirrorJob?.cancel()
        // Synchronous (a single small delete) so onDestroy cancelling the scope can't skip it.
        clearMirror(this)
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (!accept(sbn, rankingMap)) return
        val n = toNotif(sbn)
        val known = NotificationStore.find(n.key)?.let { !it.removed } == true
        NotificationStore.put(n, sbn.notification.contentIntent)
        mirror()
        val extras = sbn.notification.extras
        // Download/upload progress and "alert once" updates re-post the same key many times a
        // second; keep the store current but don't spam the log or fire scripts for each tick.
        if (isQuietRepost(
                known = known,
                flags = sbn.notification.flags,
                progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
                indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false),
            )
        ) return
        maybeLog(n)
        io.github.rscipher001.launcher.script.TriggerDispatcher.get(this).fire(
            "notification",
            mapOf("app" to n.app, "pkg" to n.pkg, "title" to n.title, "text" to n.text, "time" to n.postTime),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        NotificationStore.markRemoved(sbn.key)
        mirror()
    }

    private fun accept(sbn: StatusBarNotification): Boolean = accept(sbn, runCatching { currentRanking }.getOrNull())

    private fun accept(sbn: StatusBarNotification, map: RankingMap?): Boolean {
        if (sbn.packageName == packageName || sbn.isOngoing) return false
        val flags = sbn.notification.flags
        if (flags and (Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return false
        // Silent/minimized channels (weather, "syncing", promos) would only add noise.
        if (map != null && map.getRanking(sbn.key, ranking) &&
            ranking.importance <= NotificationManager.IMPORTANCE_LOW
        ) return false
        val extras = sbn.notification.extras
        return !extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
            !extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
    }

    private fun toNotif(sbn: StatusBarNotification): Notif {
        val extras = sbn.notification.extras
        val serial = runCatching { userManager.getSerialNumberForUser(sbn.user) }.getOrDefault(-1L)
        return Notif(
            key = sbn.key,
            pkg = sbn.packageName,
            userSerial = serial,
            app = labelOf(sbn.packageName, serial),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
            postTime = sbn.postTime,
            hasIntent = sbn.notification.contentIntent != null,
        )
    }

    private fun labelOf(pkg: String, serial: Long): String =
        AppRepository.get(this).find(pkg, null, serial)?.label
            ?: AppRepository.get(this).find(pkg, null, null)?.label
            ?: runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
            }.getOrDefault(pkg)

    private fun maybeLog(n: Notif) {
        if (!NotifPrefs.get(this).shouldLog(n.pkg)) return
        val line = n.line
        if (line.isEmpty()) return
        val now = System.currentTimeMillis()
        val last = lastLogged[n.key]
        // Chat apps re-post the same key on every message; one line per 30s is enough.
        if (last != null && (last.second == line || now - last.first < RATE_LIMIT_MS)) return
        lastLogged[n.key] = now to line
        if (lastLogged.size > NotificationStore.CAP * 2) lastLogged.entries.removeAll { now - it.value.first > RATE_LIMIT_MS }

        val ctx = LogContext.of(
            LogContext.PKG to n.pkg,
            LogContext.USER to n.userSerial.takeIf { it >= 0 }?.toString(),
            LogContext.COPY to line,
            CTX_KEY to n.key,
        )
        val repo = LogRepository.get(this)
        scope.launch { runCatching { repo.add(LogKind.SCRIPT, line, n.app, ctx) } }
    }

    companion object {
        /** LogContext key holding the notification key of a feed line. */
        const val CTX_KEY = "notif"
        const val MIRROR_SCOPE = "system"
        const val MIRROR_KEY = "notifications"
        private const val RATE_LIMIT_MS = 30_000L

        /** Removes the notification snapshot mirrored for scripts. Never throws. */
        fun clearMirror(context: android.content.Context) {
            runCatching {
                io.github.rscipher001.launcher.data.KvStore(context.applicationContext, MIRROR_SCOPE).set(MIRROR_KEY, null)
            }
        }

        /**
         * Whether a post is an update of an already-shown notification that shouldn't be logged or
         * trigger scripts: alert-once updates and progress notifications. New keys always count.
         */
        internal fun isQuietRepost(known: Boolean, flags: Int, progressMax: Int, indeterminate: Boolean): Boolean =
            known && (flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 || progressMax > 0 || indeterminate)

        @Volatile
        private var instance: WeakReference<KolahalNotificationListener>? = null

        /** The connected listener, if the user granted access and the system bound us. */
        internal val connected: KolahalNotificationListener? get() = instance?.get()
    }
}
