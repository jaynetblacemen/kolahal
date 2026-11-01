package com.mtwabpin.kolahal.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On a device: GitHub's answer is read with Android's JSON, and an APK file's signers depend on the Android version. */
@RunWith(AndroidJUnit4::class)
class UpdateReleaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun answer(
        tag: String = "v0.4.11",
        name: String = "kolahal-0.4.11.apk",
        url: String = "https://github.com/mtwabpin/kolahal/releases/download/v0.4.11/kolahal-0.4.11.apk",
        size: String = "11021042",
        digest: String = "\"digest\": \"sha256:abc\",",
    ) = """{ "tag_name": "$tag", "assets": [
        { "name": "kolahal-0.4.11-play.apk", "browser_download_url": "https://github.com/x/play.apk", "size": 5 },
        { "name": "$name", "browser_download_url": "$url", $digest "size": $size }
    ] }"""

    private fun refused(json: String, message: String) {
        val error = assertThrows(IllegalStateException::class.java) { UpdateCommands.parseRelease(json) }
        assertTrue(error.message, message in error.message.orEmpty())
    }

    @Test fun itTakesTheFullBuildsApk() {
        val release = UpdateCommands.parseRelease(answer())
        assertEquals("0.4.11", release.version)
        assertEquals(listOf(0, 4, 11), release.versionParts)
        assertEquals("https://github.com/mtwabpin/kolahal/releases/download/v0.4.11/kolahal-0.4.11.apk", release.url)
        assertEquals(11021042L, release.size)
        assertEquals("abc", release.digest)
    }

    @Test fun aChecksumIsOptional() {
        assertNull(UpdateCommands.parseRelease(answer(digest = "")).digest)
        assertNull(UpdateCommands.parseRelease(answer(digest = "\"digest\": \"md5:abc\",")).digest)
    }

    @Test fun whatItWontTake() {
        refused("[]", "Invalid GitHub release response")
        refused("""{ "assets": [] }""", "no version tag")
        refused(answer(tag = "nightly"), "Invalid release version")
        refused("""{ "tag_name": "v0.4.11" }""", "no assets")
        refused(answer(name = "other.apk"), "APK not found")
        refused(answer(url = "http://github.com/a.apk"), "Unexpected APK download host")
        refused(answer(url = "https://example.com/a.apk"), "Unexpected APK download host")
        refused(answer(url = "https://github.com.example.com/a.apk"), "Unexpected APK download host")
        refused(answer(size = "0"), "invalid size")
        refused(answer(size = "999999999999"), "invalid size")
    }

    /** Below Android 11, GET_SIGNING_CERTIFICATES gives nothing for an APK file, and `update install` failed after the download. */
    @Test fun anApkFilesSignersAreRead() {
        val apk = UpdateCommands.apkPackageInfo(context, File(context.packageCodePath))!!
        val signers = UpdateCommands.signerDigests(apk)
        assertTrue(signers.isNotEmpty())
        assertEquals(UpdateCommands.signerDigests(UpdateCommands.installedPackage(context)), signers)
    }
}
