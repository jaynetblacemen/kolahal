package com.mtwabpin.kolahal.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Backups on their own (`backup.auto`): the [Backup] file, `kolahal-backup-2026-10-27.json`,
 * written every day or every week to a folder you pick, keeping the newest `backup.keep`. One
 * written the same day replaces that day's.
 */
object AutoBackup {
    private const val PREFS = "auto_backup"
    private const val KEY_FOLDER = "folder"
    private const val KEY_LAST = "last"
    private const val WORK = "auto-backup"
    private val NAME = Regex("""^kolahal-backup-\d{4}-\d{2}-\d{2}\.json$""")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    /** The folder's name, as the file picker showed it. */
    fun folderName(context: Context): String? = folder(context)?.let { runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull() }

    fun setFolder(context: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        // Persisted grants are capped per app: let the previous folder's go.
        folder(context)?.takeIf { it != tree }?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) } }
        prefs(context).edit { putString(KEY_FOLDER, tree.toString()) }
    }

    /** When the last backup was written, or null. */
    fun last(context: Context): Long? = prefs(context).getLong(KEY_LAST, 0L).takeIf { it > 0 }

    /** Schedules the backups `backup.auto` asks for, or cancels them. */
    fun sync(context: Context) {
        val wm = WorkManager.getInstance(context)
        val days = when (Config.get(context)[Keys.BACKUP_AUTO]) {
            "daily" -> 1L
            "weekly" -> 7L
            else -> return wm.cancelUniqueWork(WORK).let { }
        }
        wm.enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(days, TimeUnit.DAYS).build(),
        )
    }

    /** Writes today's backup to the folder and drops the oldest past `backup.keep`. Returns the file's name. */
    fun backUp(context: Context): String {
        val tree = folder(context) ?: throw IllegalStateException("no backup folder: run 'backup dir'")
        val dir = DocumentFile.fromTreeUri(context, tree)
        if (dir == null || !dir.canWrite()) throw IllegalStateException("the backup folder can't be written: run 'backup dir'")
        val name = "kolahal-backup-${LocalDate.now()}.json"
        val file = dir.findFile(name) ?: dir.createFile("application/json", name) ?: throw IllegalStateException("can't create $name")
        Backup(context).export(file.uri)
        prefs(context).edit { putLong(KEY_LAST, System.currentTimeMillis()) }
        val old = expired(dir.listFiles().mapNotNull { it.name }, Config.get(context)[Keys.BACKUP_KEEP]).toSet()
        dir.listFiles().filter { it.name in old }.forEach { runCatching { it.delete() } }
        return file.name ?: name
    }

    /** Of the files in the folder, the automatic backups past the newest [keep] (their names sort by date); nothing else. */
    internal fun expired(names: List<String>, keep: Int): List<String> =
        names.filter(NAME::matches).sortedDescending().drop(keep)
}

/** Writes an automatic backup; a failure is a line in the log that says how to fix it. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { AutoBackup.backUp(applicationContext) }.onFailure {
            LogRepository.get(applicationContext).add(
                LogKind.ERROR, "Automatic backup failed: ${it.message ?: it.javaClass.simpleName} · tap to pick the folder",
                ctx = LogContext.of(LogContext.RUN to "backup dir"),
            )
        }
        return Result.success()
    }
}
