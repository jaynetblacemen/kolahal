package io.github.rscipher001.launcher.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import io.github.rscipher001.launcher.data.Config
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.data.KvStore
import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.data.LogRepository
import io.github.rscipher001.launcher.script.Json
import java.util.concurrent.Executors

/**
 * Install / update / uninstall log (`log.packages`).
 *
 * Android reports an update in several ways (removed + added with "replacing", changed, or a real
 * uninstall followed by a reinstall), so events are never logged as they arrive. Instead every
 * signal schedules a settle-and-diff: the installed apps (per profile: version, update time,
 * label) are compared with the last snapshot. A package that is still there with a new version or
 * update time is one "updated" line; one that disappeared and came back inside the settle window
 * is also an update. The snapshot persists, so changes made while the launcher wasn't running
 * show up on the next start.
 */
class PackageEvents private constructor(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val store = KvStore(context, "packages")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** While an install / update burst is settling (the short delay), [recheck] must not cut it short. */
    @Volatile private var settlingUntil = 0L
    /** When the pending diff runs (0: none pending). */
    @Volatile private var dueAt = 0L
    private val settle = Runnable { dueAt = 0L; io.execute { runCatching { diff() } } }

    /** [removedAt]: when it went missing (0 = installed); kept for a grace period before "uninstalled". */
    data class Pkg(val label: String, val version: String, val code: Long, val updated: Long, val profile: String, val removedAt: Long = 0L)

    fun start() {
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = schedule()
            override fun onPackageAdded(packageName: String, user: UserHandle) = schedule()
            override fun onPackageChanged(packageName: String, user: UserHandle) = schedule()
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = schedule()
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = Unit
        }, main)
        // Catch up on anything that changed while the launcher was not running.
        schedule(delayMs = 2_000)
    }

    /**
     * Waits for the burst of callbacks an install or update produces to finish, then diffs once.
     * [keepSooner]: a diff already due earlier stays (it re-arms the grace check itself).
     */
    private fun schedule(delayMs: Long = SETTLE_MS, keepSooner: Boolean = false) {
        val now = System.currentTimeMillis()
        if (keepSooner && dueAt in now..now + delayMs) return
        if (delayMs <= SETTLE_MS && !keepSooner) settlingUntil = now + delayMs
        dueAt = now + delayMs
        main.removeCallbacks(settle)
        main.postDelayed(settle, delayMs)
    }

    /**
     * Called when home comes back on screen. The grace-period re-check is a timer in this process,
     * which a restart, kill or freeze can lose or delay; this catches up straight away. A settle
     * already waiting (an install in progress) is left alone.
     */
    fun recheck() {
        if (System.currentTimeMillis() >= settlingUntil) schedule(delayMs = 500, keepSooner = true)
    }

    /** Installed apps, and the serials of paused profiles (whose apps are hidden, not gone). */
    private fun current(): Pair<Map<String, Pkg>, Set<Long>> {
        val out = HashMap<String, Pkg>()
        val paused = HashSet<Long>()
        val me = android.os.Process.myUserHandle()
        for (user in launcherApps.profiles) {
            // Paused profiles report no apps; keep their last known state instead of "uninstalled".
            if (runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(false)) {
                paused += userManager.getSerialNumberForUser(user)
                continue
            }
            val serial = userManager.getSerialNumberForUser(user)
            val profile = if (user == me) "" else profileTag(user)
            val activities = runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
            for (a in activities) {
                val key = "${a.componentName.packageName}#$serial"
                if (key in out) continue
                val info = runCatching {
                    launcherApps.getApplicationInfo(a.componentName.packageName, 0, user)
                }.getOrNull()
                val pi = runCatching {
                    context.packageManager.getPackageInfo(a.componentName.packageName, 0)
                }.getOrNull().takeIf { user == me }
                out[key] = Pkg(
                    label = a.label.toString(),
                    version = pi?.versionName.orEmpty(),
                    code = pi?.longVersionCode ?: 0L,
                    // Other profiles: the app's install-time stamp is not readable; the APK's
                    // file time changes on every update, which is what we need.
                    updated = pi?.lastUpdateTime ?: info?.sourceDir?.let { java.io.File(it).lastModified() } ?: 0L,
                    profile = profile,
                )
            }
        }
        return out to paused
    }

    private fun profileTag(user: UserHandle): String {
        val type = if (android.os.Build.VERSION.SDK_INT >= 35) {
            runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull()
        } else null
        return when (type) {
            UserManager.USER_TYPE_PROFILE_PRIVATE -> "S"
            "android.os.usertype.profile.CLONE" -> "C"
            else -> "W"
        }
    }

    @Synchronized
    private fun diff() {
        val (now, paused) = current()
        val before = load()
        if (before == null) {
            // First run: remember what is installed without logging every app as "installed".
            save(now)
            return
        }
        val enabled = Config.get(context)[Keys.LOG_PACKAGES]
        val logs = LogRepository.get(context)
        val events = ArrayList<Triple<String, String, Pkg>>() // kind, key, pkg
        val next = HashMap(now)
        val t = System.currentTimeMillis()
        for ((key, p) in now) {
            val old = before[key]
            when {
                old == null -> events += Triple("installed", key, p)
                // Gone a moment ago and back again: an uninstall + reinstall is one update.
                old.removedAt > 0 -> events += Triple("updated", key, p)
                old.code != p.code || old.version != p.version || old.updated != p.updated -> events += Triple("updated", key, p)
            }
        }
        // Soonest moment a missing app has been gone for the whole grace period.
        var nextCheck = Long.MAX_VALUE
        for ((key, p) in before) {
            if (key in now) continue
            when {
                // A paused / locked profile hides its apps: they are not uninstalled.
                key.substringAfter('#').toLongOrNull() in paused -> next[key] = p
                p.removedAt == 0L -> { next[key] = p.copy(removedAt = t); nextCheck = minOf(nextCheck, GRACE_MS) }
                t - p.removedAt < GRACE_MS -> { next[key] = p; nextCheck = minOf(nextCheck, GRACE_MS - (t - p.removedAt)) }
                else -> events += Triple("uninstalled", key, p)
            }
        }
        save(next)
        // Check again once the grace period is over, so a real uninstall is still logged.
        if (nextCheck != Long.MAX_VALUE) main.post { schedule(nextCheck + 500, keepSooner = true) }
        if (!enabled || events.isEmpty()) return
        val pending = events.map { (kind, key, p) ->
            val old = before[key]
            val tag = if (p.profile.isNotEmpty()) " [${p.profile}]" else ""
            val ver = when {
                kind == "updated" && old != null && old.version.isNotEmpty() && old.version != p.version -> "\n${old.version} → ${p.version}"
                kind != "uninstalled" && p.version.isNotEmpty() -> "\n${p.version}"
                else -> ""
            }
            val glyph = when (kind) { "installed" -> "+"; "updated" -> "↻"; else -> "−" }
            val pkg = key.substringBefore('#')
            val ctx = if (kind == "uninstalled") LogContext.of(LogContext.COPY to pkg)
            else LogContext.of(LogContext.PKG to pkg, LogContext.USER to key.substringAfter('#'), LogContext.COPY to pkg)
            // Detail lines (dimmed by the log view): the package, then the version.
            LogRepository.Pending(LogKind.INFO, "${kind.replaceFirstChar { it.uppercase() }} ${p.label}$tag $glyph\n$pkg$ver", "apps", ctx, System.currentTimeMillis())
        }
        logs.addAll(pending)
        // Short history for the `installs` command.
        val history = (Json.parse(store.get(HISTORY) ?: "[]") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        val added = pending.map { mapOf("t" to it.ts, "text" to it.text) }
        store.set(HISTORY, Json.stringify((added.reversed() + history).take(MAX_HISTORY)))
    }

    /** Newest first: (time, line). */
    fun history(): List<Pair<Long, String>> =
        (Json.parse(store.get(HISTORY) ?: "[]") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull {
            val t = (it["t"] as? Number)?.toLong() ?: return@mapNotNull null
            t to (it["text"]?.toString() ?: return@mapNotNull null)
        }

    private fun load(): Map<String, Pkg>? {
        val raw = store.get(SNAPSHOT) ?: return null
        val map = Json.parse(raw) as? Map<*, *> ?: return null
        return map.entries.mapNotNull { (k, v) ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            k.toString() to Pkg(
                label = m["l"]?.toString().orEmpty(),
                version = m["v"]?.toString().orEmpty(),
                code = (m["c"] as? Number)?.toLong() ?: 0L,
                updated = (m["u"] as? Number)?.toLong() ?: 0L,
                profile = m["p"]?.toString().orEmpty(),
                removedAt = (m["r"] as? Number)?.toLong() ?: 0L,
            )
        }.toMap()
    }

    private fun save(map: Map<String, Pkg>) {
        store.set(SNAPSHOT, Json.stringify(map.mapValues { (_, p) ->
            mapOf("l" to p.label, "v" to p.version, "c" to p.code, "u" to p.updated, "p" to p.profile, "r" to p.removedAt)
        }))
    }

    companion object {
        /** An uninstall + reinstall (or the remove/add pair of an update) inside this window is one update. */
        private const val SETTLE_MS = 4_000L
        /** A missing app that reappears within this window was reinstalled: logged as an update. */
        private const val GRACE_MS = 20_000L
        private const val SNAPSHOT = "snapshot"
        private const val HISTORY = "history"
        private const val MAX_HISTORY = 200

        @Volatile
        private var instance: PackageEvents? = null

        fun get(context: Context): PackageEvents = instance ?: synchronized(this) {
            instance ?: PackageEvents(context.applicationContext).also { instance = it }
        }
    }
}
