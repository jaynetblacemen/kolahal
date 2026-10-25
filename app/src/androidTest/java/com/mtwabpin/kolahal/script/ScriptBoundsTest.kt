package com.mtwabpin.kolahal.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.workDataOf
import com.mtwabpin.kolahal.data.KvStore
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.ScriptRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** What one run may write is bounded: a script can't make a row too big to read back, or flood home. */
@RunWith(AndroidJUnit4::class)
class ScriptBoundsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scripts = ScriptRepository.get(context)
    private val status = KvStore(context, HostApi.STATUS_SCOPE)

    @After fun tearDown() {
        scripts.byName("bounds")?.let { scripts.delete(it.id) }
        status.set("bounds", null)
    }

    private fun refused(what: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal: $what")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(what))
        }
    }

    /** An error as long as a script likes is cut in the status kept with the script: its row stays readable. */
    @Test fun aLongErrorIsCutInTheStatus() {
        val s = scripts.create("bounds", ScriptLang.LUA, "error(string.rep('x', 300000), 0)")
        runBlocking { ScriptRun.run(context, workDataOf(ScriptWorker.KEY_SCRIPT_ID to s.id)) }
        val status = scripts.byId(s.id)!!.lastStatus.orEmpty()
        assertTrue(status.take(20), status.startsWith("error: xxx"))
        assertTrue("${status.length} characters", status.length <= ScriptRepository.STATUS_MAX)
        assertTrue(scripts.all().any { it.id == s.id })
    }

    /** A store counts bytes as stored, and keys too. */
    @Test fun aStoreCountsBytesAndKeys() {
        val s = scripts.create("bounds", ScriptLang.LUA, "return 1")
        val host = HostApi(context, "bounds", scriptId = s.id, notifyEnabled = false)
        refused("key too long") { host.storeSet("k".repeat(KvStore.MAX_KEY + 1), "v") }
        // 400,000 CJK characters are 1.2 MB.
        refused("value too large") { host.storeSet("k", "字".repeat(400_000)) }
        host.storeSet("k", "字".repeat(300_000))
        assertEquals(300_000, host.storeGet("k")!!.length)
    }

    /** status() writes only what changed, and a loop of it stops after a few. */
    @Test fun headerLineWritesAreCapped() {
        val s = scripts.create("bounds", ScriptLang.LUA, "status('x')")
        val host = HostApi(context, "bounds", scriptId = s.id, notifyEnabled = false)
        repeat(5) { host.status("same") }
        for (i in 1..40) host.status("n$i")
        // One write for "same", then nineteen more.
        assertEquals("n19", status.get("bounds"))
    }

    /** At most ten notifications a run; the next call says so, once. */
    @Test fun notificationsAreCapped() {
        val host = HostApi(context, "bounds", scriptId = null, notifyEnabled = true)
        repeat(12) { host.notify("t$it", "b") }
        host.flush()
        val said = LogRepository.get(context).recent(50).count { "more than ${HostApi.MAX_NOTIFY_PER_RUN} notifications" in it.text }
        assertEquals(1, said)
    }

    @Test fun aHugeClipIsRefused() {
        val host = HostApi(context, "bounds", scriptId = null, notifyEnabled = false)
        refused("clipboard.set") { host.clipboardSet("x".repeat(HostApi.MAX_CLIP + 1)) }
    }
}
