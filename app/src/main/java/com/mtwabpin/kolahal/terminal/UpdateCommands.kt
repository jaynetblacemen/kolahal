package com.mtwabpin.kolahal.terminal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.script.Json
import com.mtwabpin.kolahal.script.Net
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Request

/**
 * `update` says whether a newer release is out; `update install` downloads its APK and hands it to
 * Android's installer, once it's checked to be Kolahal's and signed with the release key. Not in the
 * Play build, which may update only through Google Play ([com.mtwabpin.kolahal.BuildConfig.SELF_UPDATE]).
 */
internal class UpdateCommands(
    private val context: Context,
    private val background: (String, () -> Unit) -> Unit,
    private val info: (String, LogContext) -> Unit,
    private val onError: (String) -> Unit,
) {
    data class Release(
        val version: String,
        val versionParts: List<Int>,
        val url: String,
        val size: Long,
        val digest: String?,
    )

    companion object {
        const val RELEASE_PAGE = "https://github.com/mtwabpin/kolahal/releases/latest"

        private const val API_URL = "https://api.github.com/repos/mtwabpin/kolahal/releases/latest"

        private const val OFFICIAL_CERT = "21223ad4312a871e4a1dd16440efccac84f4660c56fa1e80467631062938e210"

        private const val MAX_APK_SIZE = 100L * 1024 * 1024

        /** One at a time, whichever command started it. */
        private val running = AtomicBoolean(false)

        /** The parts of a plain x.y.z version; null for anything else. */
        fun parseVersion(version: String): List<Int>? {
            if (!version.matches(Regex("""\d+\.\d+\.\d+"""))) return null
            return version.split('.').map { it.toIntOrNull() ?: return null }
        }

        fun compareVersions(a: List<Int>, b: List<Int>): Int {
            for (i in 0..2) {
                val result = a[i].compareTo(b[i])
                if (result != 0) return result
            }
            return 0
        }

        /** The latest release in GitHub's answer: the full build's APK, which is what `update` installs. */
        fun parseRelease(body: String): Release {
            val data = Json.decode(body) as? Map<*, *>
                ?: throw IllegalStateException("Invalid GitHub release response.")

            val tag = data["tag_name"] as? String
                ?: throw IllegalStateException("Release has no version tag.")

            val version = tag.removePrefix("v")
            val parts = parseVersion(version)
                ?: throw IllegalStateException("Invalid release version: $tag")

            val assets = data["assets"] as? List<*>
                ?: throw IllegalStateException("Release has no assets.")

            val asset = assets
                .filterIsInstance<Map<*, *>>()
                .firstOrNull { it["name"] == "kolahal-$version.apk" }
                ?: throw IllegalStateException("Release APK not found.")

            val url = asset["browser_download_url"] as? String
                ?: throw IllegalStateException("Release APK has no download URL.")

            val parsedUrl = runCatching { URI(url) }.getOrNull()
            if (parsedUrl == null || parsedUrl.scheme != "https" || parsedUrl.host != "github.com") {
                throw IllegalStateException("Unexpected APK download host.")
            }

            val size = (asset["size"] as? Number)?.toLong()
                ?: throw IllegalStateException("Release APK has no valid size.")

            if (size <= 0 || size > MAX_APK_SIZE) {
                throw IllegalStateException("Release APK has an invalid size.")
            }

            val digest = (asset["digest"] as? String)
                ?.takeIf { it.startsWith("sha256:") }
                ?.substringAfter("sha256:")

            return Release(version, parts, url, size, digest)
        }

        /** An APK file with its signers: below Android 11, GET_SIGNING_CERTIFICATES gives none for a file. */
        @Suppress("DEPRECATION")
        fun apkPackageInfo(context: Context, apk: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        )

        fun installedPackage(context: Context): PackageInfo =
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

        @Suppress("DEPRECATION")
        fun signerDigests(packageInfo: PackageInfo): Set<String> {
            val signatures = packageInfo.signingInfo?.apkContentsSigners
                ?: packageInfo.signatures
                ?: throw IllegalStateException("Could not read APK signing certificates.")

            return signatures.map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(Locale.ROOT, it) }
            }.toSet()
        }
    }

    /** The APK can take longer than a request's limit on a slow connection; the read timeout still stops a stalled one. */
    private val downloadClient by lazy { Net.client.newBuilder().callTimeout(10, TimeUnit.MINUTES).build() }

    fun run(args: List<String>) {
        if (args.size > 1 || (args.isNotEmpty() && args[0] != "install")) {
            onError("Usage: update [install]")
            return
        }

        if (!running.compareAndSet(false, true)) {
            onError("An update operation is already running.")
            return
        }

        val install = args.firstOrNull() == "install"

        background("update") {
            try {
                val release = fetchRelease()

                if (install) {
                    install(release)
                } else {
                    check(release)
                }
            } catch (e: Exception) {
                onError("Update failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                running.set(false)
            }
        }
    }

    private fun check(release: Release) {
        val installed = installedVersion()

        when {
            release.versionParts == installed.first ->
                info("Kolahal is already up to date (${release.version}).", LogContext.EMPTY)

            compareVersions(release.versionParts, installed.first) < 0 ->
                info("Installed version is newer than the latest release.", LogContext.EMPTY)

            else -> {
                info(
                    "Update available: ${installed.second} → ${release.version}",
                    LogContext.of(LogContext.URL to RELEASE_PAGE),
                )
                info(
                    "Run 'update install' to download and install it.",
                    LogContext.of(LogContext.COMMAND to "update install"),
                )
            }
        }
    }

    private fun install(release: Release) {
        val installed = installedPackage(context)

        // Check signing identity before spending data on the APK.
        val installedSigners = signerDigests(installed)
        if (OFFICIAL_CERT !in installedSigners) {
            throw IllegalStateException(
                "This installation is not signed with Kolahal's official release certificate.",
            )
        }

        if (compareVersions(release.versionParts, installedVersion().first) <= 0) {
            throw IllegalStateException("There is no newer release to install.")
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(intent)
            throw IllegalStateException(
                "Allow Kolahal to install unknown apps in Android settings, then run 'update install' again.",
            )
        }

        // An APK from an earlier try: only now, as an installer may still be reading the last one until then.
        val directory = updatesDir()
        directory.deleteRecursively()
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Could not create update cache directory.")
        }

        val apk = File.createTempFile("kolahal-${release.version}-", ".apk", directory)
        var keepApk = false

        try {
            info("Downloading Kolahal ${release.version} (${release.size / (1024 * 1024)} MB)…", LogContext.EMPTY)
            download(release, apk)

            val archive = apkPackageInfo(context, apk)
                ?: throw IllegalStateException("Downloaded file is not a valid APK.")
            if (archive.packageName != context.packageName) {
                throw IllegalStateException("Downloaded APK has an unexpected package ID.")
            }

            if (archive.longVersionCode <= installed.longVersionCode) {
                throw IllegalStateException("Downloaded APK is not newer than the installed version.")
            }

            val downloadedSigners = signerDigests(archive)
            if (downloadedSigners != installedSigners ||
                OFFICIAL_CERT !in downloadedSigners
            ) {
                throw IllegalStateException("Downloaded APK has an unexpected signing certificate.")
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            keepApk = true
            // Android may keep an installer from opening if you left Kolahal during the download.
            info(
                "Downloaded and verified Kolahal ${release.version}. If the installer doesn't open, run 'update install' again.",
                LogContext.of(LogContext.COMMAND to "update install"),
            )
        } finally {
            // Keep the completed APK available to Android's installer.
            // A failed or incomplete download is never retained.
            if (!keepApk) apk.delete()
        }
    }

    private fun fetchRelease(): Release {
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", Net.USER_AGENT)
            .build()

        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub returned HTTP ${response.code}")
            }

            return parseRelease(response.body.string())
        }
    }

    private fun download(release: Release, destination: File) {
        val request = Request.Builder()
            .url(release.url)
            .header("User-Agent", Net.USER_AGENT)
            .build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("APK download returned HTTP ${response.code}")
            }

            val body = response.body
            if (body.contentLength() > MAX_APK_SIZE) {
                throw IllegalStateException("APK exceeds the allowed size.")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L

            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)

                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break

                        total += count
                        if (total > release.size || total > MAX_APK_SIZE) {
                            throw IllegalStateException("Downloaded APK is larger than expected.")
                        }

                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }

            if (total != release.size) {
                throw IllegalStateException("Downloaded APK size does not match the release.")
            }

            val actualDigest = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
            if (release.digest != null &&
                !actualDigest.equals(release.digest, ignoreCase = true)
            ) {
                throw IllegalStateException("APK SHA-256 checksum verification failed.")
            }
        }
    }

    private fun installedVersion(): Pair<List<Int>, String> {
        val version = installedPackage(context).versionName ?: "unknown"
        val parts = parseVersion(version.removeSuffix("-dev"))
            ?: throw IllegalStateException("Installed version is not supported: $version")
        return parts to version
    }

    private fun updatesDir() = File(context.cacheDir, "updates")
}
