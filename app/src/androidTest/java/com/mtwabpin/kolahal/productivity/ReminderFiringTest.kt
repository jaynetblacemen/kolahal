package com.mtwabpin.kolahal.productivity

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A reminder firing: a rule it can't read must not take the app down, at this start or the next. */
@RunWith(AndroidJUnit4::class)
class ReminderFiringTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ReminderStore.get(context)
    private val logs = LogRepository.get(context)
    private val before = logs.maxId()

    @After fun tearDown() {
        store.all().filter { it.text.startsWith("firing-") }.forEach { store.remove(it.id) }
        logs.recent(50).filter { it.id > before }.forEach { logs.delete(it.id) }
    }

    private fun fire(id: Long) = context.sendBroadcast(
        Intent(context, ReminderReceiver::class.java).putExtra(ReminderScheduler.EXTRA_ID, id).setPackage(context.packageName),
    )

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 8_000
        while (!check() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(what, check())
    }

    @Test fun aRuleItCantReadFiresOnceAndGoes() {
        val r = store.add(System.currentTimeMillis() - 1_000, "firing-bad", "biweekly")
        fire(r.id)
        waitFor("gone") { store.byId(r.id) == null }
        waitFor("and it says so") {
            logs.recent(50).any { it.id > before && it.kind == LogKind.ERROR && "firing-bad" in it.text && "won't come again" in it.text }
        }
        assertTrue("it still reminds", logs.recent(50).any { it.id > before && it.text == "Reminder: firing-bad" })
    }

    @Test fun aRepeatingOneIsSetForItsNextTime() {
        val at = System.currentTimeMillis() - 1_000
        val r = store.add(at, "firing-good", "day")
        fire(r.id)
        waitFor("moved on") { store.byId(r.id)?.at != at }
        assertNotNull(store.byId(r.id))
        assertEquals("day", store.byId(r.id)!!.repeat)
        assertNotEquals(at, store.byId(r.id)!!.at)
    }
}
