package io.github.rscipher001.launcher.script

import android.content.Context
import android.net.Uri

/** Storage Access Framework helpers shared by backup, script import/export and folder sync. */
object Files {
    /** Large enough for any script or backup; guards against picking a huge file by mistake. */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** What a full backup may weigh: the log, feed items, script stores and an imported font. */
    const val MAX_BACKUP_BYTES = 64 * 1024 * 1024

    fun readText(context: Context, uri: Uri, maxBytes: Int = MAX_BYTES): String {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("can't open the file")
        stream.use { input ->
            // Manual loop: InputStream.readNBytes needs API 33.
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) throw IllegalStateException("File is larger than ${maxBytes / (1024 * 1024)} MB")
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    /** Some providers reject truncate mode "wt"; fall back to "w". */
    fun writeText(context: Context, uri: Uri, text: String) {
        val resolver = context.contentResolver
        val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("can't write the file")
        stream.use { it.write(text.toByteArray()) }
    }
}
