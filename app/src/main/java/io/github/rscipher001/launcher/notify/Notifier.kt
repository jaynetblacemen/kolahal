package io.github.rscipher001.launcher.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.rscipher001.launcher.MainActivity
import io.github.rscipher001.launcher.R

object Notifier {
    const val CHANNEL_SCRIPTS = "scripts"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCRIPTS,
                context.getString(R.string.channel_scripts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_scripts_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_reminders_desc) }
        )
    }

    fun canPost(context: Context, channelId: String = CHANNEL_SCRIPTS): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    const val CHANNEL_REMINDERS = "reminders"

    /** Posts a `remind` notification; tapping opens the launcher. One notification per reminder id. */
    fun postReminder(context: Context, id: Long, text: String): Boolean {
        if (!canPost(context, CHANNEL_REMINDERS)) return false
        val pi = PendingIntent.getActivity(
            context, ("reminder$id").hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(text)
            .setContentText("reminder")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return runCatching {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(CHANNEL_REMINDERS, id.toInt(), notification)
            true
        }.getOrDefault(false)
    }

    /**
     * Posts a script notification. Tapping opens [url] if given, otherwise the launcher. Pass [id]
     * to replace an earlier notification with the same tag/id (e.g. one per feed); by default each
     * distinct title/body gets its own. Never throws: a malformed [url] falls back to the launcher.
     */
    fun post(context: Context, tag: String, title: String, body: String, url: String?, id: Int? = null): Boolean =
        runCatching {
            if (!canPost(context)) return false
            val notifId = id ?: (tag + title + body).hashCode()
            val launcher = { Intent(context, MainActivity::class.java) }
            val tapIntent = (url?.let { runCatching { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }.getOrNull() } ?: launcher())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = runCatching { PendingIntent.getActivity(context, notifId, tapIntent, flags) }.getOrNull()
                ?: PendingIntent.getActivity(context, notifId, launcher().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            val notification = NotificationCompat.Builder(context, CHANNEL_SCRIPTS)
                .setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSubText(tag)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(tag, notifId, notification)
            true
        }.getOrDefault(false)
}
