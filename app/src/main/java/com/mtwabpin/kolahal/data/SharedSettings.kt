package com.mtwabpin.kolahal.data

import android.content.Context

/**
 * Settings the :script process needs. It reads the settings file once, when it starts, and never
 * sees a change after; so home copies these into the database both processes read, whenever they
 * change ([com.mtwabpin.kolahal.LauncherApp]).
 */
object SharedSettings {
    const val SCOPE = "shared-settings"

    fun <T> get(context: Context, key: ConfigKey<T>): T =
        KvStore(context, SCOPE).get(key.key)?.let { key.parse(it) } ?: key.default

    fun <T> set(context: Context, key: ConfigKey<T>, value: T) = KvStore(context, SCOPE).set(key.key, key.format(value))

    /** "22:40" or "10:40 PM" per `clock.24h`, for lines the :script process writes. */
    fun shortTime(context: Context, millis: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat(if (get(context, Keys.CLOCK_24H)) "HH:mm" else "h:mm a", java.util.Locale.getDefault()).format(java.util.Date(millis))
}
