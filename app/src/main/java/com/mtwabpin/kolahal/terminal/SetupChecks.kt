package com.mtwabpin.kolahal.terminal

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.notify.Notifier
import com.mtwabpin.kolahal.unlock.UnlockCounter

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
                "Notification access",
                "Your notifications in the terminal (notifs) and for scripts",
                com.mtwabpin.kolahal.notifications.NotificationActions.isEnabled(context),
                "notifs enable",
            ),
            SetupCheck(
                "Double-tap to lock",
                "Optional accessibility service that only locks the screen",
                com.mtwabpin.kolahal.gesture.LockService.isEnabled(context),
                "accessibility",
            ),
            SetupCheck(
                "Do Not Disturb access (dnd, ring silent)",
                "Optional: lets dnd and ring silent change Do Not Disturb",
                context.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted,
                "dnd access",
            ),
            SetupCheck(
                "Modify system settings (brightness, rotate)",
                "Optional: lets brightness, rotate and screen-timeout change system settings",
                android.provider.Settings.System.canWrite(context),
                "brightness access",
            ),
            SetupCheck(
                "Calendar (agenda)",
                "Optional: upcoming events with the agenda command",
                com.mtwabpin.kolahal.productivity.Agenda.hasPermission(context),
                "agenda --allow",
            ),
            SetupCheck(
                "Call log (calls in the log)",
                "Optional: incoming, outgoing and missed calls appear in the log",
                com.mtwabpin.kolahal.calls.CallEvents.hasPermission(context),
                "calls --allow",
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
