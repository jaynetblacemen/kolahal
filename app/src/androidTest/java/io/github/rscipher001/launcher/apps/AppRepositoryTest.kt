package io.github.rscipher001.launcher.apps

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppRepositoryTest {
    @Test fun listsAppsPerProfile() {
        val repo = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        repo.refresh()
        val deadline = System.currentTimeMillis() + 5_000
        while (repo.apps.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val byKind = repo.apps.value.groupingBy { it.profile.kind }.eachCount()
        repo.profiles.value.forEach { Log.i("AppRepositoryTest", "profile ${it.kind} serial=${it.serial} quiet=${it.quiet}") }
        Log.i("AppRepositoryTest", "apps per profile: $byKind")
        assertTrue((byKind[ProfileKind.PERSONAL] ?: 0) > 0)
        // Work/private profiles are device-specific, so they are only logged, not asserted.
    }
}
