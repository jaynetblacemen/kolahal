package com.mtwabpin.kolahal.apps

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.exp

/**
 * Per-app user preferences keyed by [AppEntry.key] (component + profile):
 * hidden apps, custom labels, and launch history for frecency ranking.
 * Also stores command aliases.
 */
class AppPrefs private constructor(context: Context) {
    private val apps = context.getSharedPreferences("apps", Context.MODE_PRIVATE)
    private val aliasPrefs = context.getSharedPreferences("aliases", Context.MODE_PRIVATE)
    /**
     * Launch counts and times. A file of their own, so Android's cloud backup can leave them out
     * (data_extraction_rules.xml) while pins, labels and hidden apps still go. Moved out of "apps" once.
     */
    private val usage = context.getSharedPreferences(USAGE_FILE, Context.MODE_PRIVATE).also { u ->
        val old = apps.all.filterKeys { it.startsWith("launch:") }
        if (old.isNotEmpty()) {
            u.edit().apply { old.forEach { (k, v) -> putString(k, v as? String) } }.apply()
            apps.edit().apply { old.keys.forEach(::remove) }.apply()
        }
    }

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    // ---- hidden / labels ------------------------------------------------------------------

    fun isHidden(key: String) = apps.getBoolean("hidden:$key", false)

    fun setHidden(key: String, hidden: Boolean) {
        apps.edit().apply { if (hidden) putBoolean("hidden:$key", true) else remove("hidden:$key") }.apply()
        _version.update { it + 1 }
    }

    fun label(key: String): String? = apps.getString("label:$key", null)

    fun setLabel(key: String, label: String?) {
        apps.edit().apply { if (label.isNullOrBlank()) remove("label:$key") else putString("label:$key", label) }.apply()
        _version.update { it + 1 }
    }

    fun hiddenKeys(): List<String> =
        apps.all.keys.filter { it.startsWith("hidden:") }.map { it.removePrefix("hidden:") }.sorted()

    fun labels(): Map<String, String> = apps.all.mapNotNull { (k, v) ->
        if (k.startsWith("label:") && v is String) k.removePrefix("label:") to v else null
    }.toMap()

    // ---- favourites ------------------------------------------------------------------------

    /** Pinned app keys in the user's order; shown first in the idle suggestion strip. */
    fun pinned(): List<String> = apps.getString("pinned", null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    fun setPinned(key: String, pinned: Boolean) {
        val list = pinned().filter { it != key }.let { if (pinned) it + key else it }
        apps.edit().putString("pinned", list.joinToString("\n")).apply()
        _version.update { it + 1 }
    }

    fun isPinned(key: String) = key in pinned()

    // ---- frecency -------------------------------------------------------------------------

    /** Records a launch: count plus last-used time, stored as "count:epochMillis". */
    fun recordLaunch(key: String) {
        // Read-modify-write: two launches recorded at once must both count.
        synchronized(this) {
            val (count, _) = launchStats(key)
            usage.edit().putString("launch:$key", "${count + 1}:${System.currentTimeMillis()}").apply()
        }
        _version.update { it + 1 } // refreshes the "most used" suggestions
    }

    private fun launchStats(key: String): Pair<Int, Long> {
        val raw = usage.getString("launch:$key", null) ?: return 0 to 0L
        val count = raw.substringBefore(':').toIntOrNull() ?: 0
        val ts = raw.substringAfter(':', "").toLongOrNull() ?: 0L
        return count to ts
    }

    /** Higher is better: launch count weighted by an exponential decay on age (half-life ~7 days). */
    fun frecency(key: String, now: Long = System.currentTimeMillis()): Double {
        val (count, ts) = launchStats(key)
        if (count == 0) return 0.0
        val ageDays = (now - ts).coerceAtLeast(0) / 86_400_000.0
        return count * exp(-ageDays * LN2 / HALF_LIFE_DAYS)
    }

    // ---- aliases --------------------------------------------------------------------------

    private var aliasCache: Map<String, String>? = null
    private val aliasLock = Any()

    fun aliases(): Map<String, String> = synchronized(aliasLock) {
        aliasCache ?: aliasPrefs.all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap().toSortedMap()
            .also { aliasCache = it }
    }

    fun alias(name: String): String? = aliases()[name]

    fun setAlias(name: String, expansion: String?) {
        synchronized(aliasLock) {
            aliasPrefs.edit().apply { if (expansion == null) remove(name) else putString(name, expansion) }.apply()
            aliasCache = null
        }
        _version.update { it + 1 }
    }

    companion object {
        const val USAGE_FILE = "app_usage"
        private const val LN2 = 0.6931471805599453
        private const val HALF_LIFE_DAYS = 7.0

        @Volatile
        private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs = instance ?: synchronized(this) {
            instance ?: AppPrefs(context.applicationContext).also { instance = it }
        }
    }
}
