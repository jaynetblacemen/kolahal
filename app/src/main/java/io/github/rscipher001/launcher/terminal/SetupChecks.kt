package io.github.rscipher001.launcher.terminal

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import io.github.rscipher001.launcher.apps.AppRepository
import io.github.rscipher001.launcher.apps.ProfileKind
import io.github.rscipher001.launcher.notify.Notifier
import io.github.rscipher001.launcher.unlock.UnlockCounter

/** One item of the setup checklist; [fix] is a terminal command that opens the right system page. */
data class SetupCheck(
    val title: String,
    val detail: String,
    val ok: Boolean,
    val fix: String?,
)

/** Shared by the `setup` GUI page and its text form (`setup text`). */
object SetupChecks {
    fun run(context: Context): List<SetupCheck> {
        val power = context.getSystemService(PowerManager::class.java)
        val profiles = AppRepository.get(context).profiles.value.filter { it.kind != ProfileKind.PERSONAL }
        return listOf(
            SetupCheck(
                "Default home app",
                "Kolahal opens on Home and can pause or resume the work profile",
                isDefaultLauncher(context),
                "home",
            ),
            SetupCheck(
                "Usage access",
                "Accurate unlock counts, including unlocks while the launcher wasn't running",
                UnlockCounter.get(context).hasUsageAccess(),
                "usage-access",
            ),
            SetupCheck(
                "Notifications",
                "Scripts can alert you about new items",
                Notifier.canPost(context),
                "notifications",
            ),
            SetupCheck(
                "Battery unrestricted",
                "Timer scripts run on schedule instead of being deferred",
                power.isIgnoringBatteryOptimizations(context.packageName),
                "battery",
            ),
            SetupCheck(
                "Work / private profiles",
                if (profiles.isEmpty()) "None found on this device"
                else profiles.joinToString { "${it.kind.label} (${if (it.quiet) "paused" else "on"})" },
                profiles.isNotEmpty(),
                null,
            ),
        )
    }

    fun isDefaultLauncher(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 29) {
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    } else {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == context.packageName
    }
}
