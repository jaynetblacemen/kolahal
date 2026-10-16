package io.github.rscipher001.launcher.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which profile an app lives in. [tag] is the short indicator printed in the terminal. */
enum class ProfileKind(val tag: String, val label: String) {
    PERSONAL("P", "personal"),
    WORK("W", "work"),
    PRIVATE("S", "private"),
}

data class Profile(
    val user: UserHandle,
    val serial: Long,
    val kind: ProfileKind,
    /** True when the profile is paused ("work apps off") or the private space is locked. */
    val quiet: Boolean,
)

data class AppEntry(
    val label: String,
    val component: ComponentName,
    val profile: Profile,
) {
    val pkg: String get() = component.packageName
    val key: String get() = "${component.flattenToShortString()}#${profile.serial}"
}

/**
 * Lists launchable activities across every profile visible to the launcher
 * (personal, managed work profile, Android 15 private space) and keeps the list fresh.
 */
class AppRepository private constructor(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
            override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
            override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        })
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            if (Build.VERSION.SDK_INT >= 35) {
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
            }
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = refresh()
        }, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    fun refresh() {
        scope.launch {
            val profiles = loadProfiles()
            val apps = profiles.flatMap { profile ->
                runCatching { launcherApps.getActivityList(null, profile.user) }
                    .getOrDefault(emptyList())
                    .map { AppEntry(it.label.toString(), it.componentName, profile) }
            }.sortedWith(compareBy({ it.label.lowercase() }, { it.profile.kind.ordinal }))
            _profiles.value = profiles
            _apps.value = apps
        }
    }

    private fun loadProfiles(): List<Profile> {
        val me = Process.myUserHandle()
        return launcherApps.profiles.map { user ->
            Profile(
                user = user,
                serial = userManager.getSerialNumberForUser(user),
                kind = kindOf(user, me),
                quiet = user != me && runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(false),
            )
        }.sortedBy { it.kind.ordinal }
    }

    private fun kindOf(user: UserHandle, me: UserHandle): ProfileKind {
        if (user == me) return ProfileKind.PERSONAL
        if (Build.VERSION.SDK_INT >= 35) {
            val type = runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull()
            if (type == UserManager.USER_TYPE_PROFILE_PRIVATE) return ProfileKind.PRIVATE
        }
        return ProfileKind.WORK
    }

    fun profileBySerial(serial: Long): Profile? = _profiles.value.firstOrNull { it.serial == serial }

    fun find(pkg: String, cls: String?, serial: Long?): AppEntry? = _apps.value.firstOrNull {
        it.pkg == pkg && (cls == null || it.component.className == cls) &&
            (serial == null || it.profile.serial == serial)
    }

    /** Ranked fuzzy match: exact > prefix > word prefix > substring > subsequence. */
    fun search(query: String, kind: ProfileKind? = null): List<AppEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return _apps.value
            .asSequence()
            .filter { kind == null || it.profile.kind == kind }
            .mapNotNull { app -> score(app.label.lowercase(), q)?.let { app to it } }
            .sortedWith(compareBy({ it.second }, { it.first.label.length }, { it.first.profile.kind.ordinal }))
            .map { it.first }
            .toList()
    }

    private fun score(label: String, q: String): Int? = when {
        label == q -> 0
        label.startsWith(q) -> 1
        label.split(' ', '-', '_', '.').any { it.startsWith(q) } -> 2
        label.contains(q) -> 3
        isSubsequence(q, label) -> 4
        else -> null
    }

    private fun isSubsequence(q: String, s: String): Boolean {
        var i = 0
        for (ch in s) if (i < q.length && ch == q[i]) i++
        return i == q.length
    }

    fun launch(app: AppEntry) {
        launcherApps.startMainActivity(app.component, app.profile.user, null, null)
    }

    fun openAppInfo(app: AppEntry) {
        launcherApps.startAppDetailsActivity(app.component, app.profile.user, null, null)
    }

    fun uninstall(app: AppEntry) {
        if (app.profile.kind == ProfileKind.PERSONAL) {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            // Packages in other profiles can only be removed from their own settings page.
            openAppInfo(app)
        }
    }

    /**
     * Pause/resume the work profile (or lock/unlock private space).
     * Returns false when unsupported (needs API 29+ and being the default launcher).
     */
    fun setQuietMode(profile: Profile, quiet: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            userManager.requestQuietModeEnabled(quiet, profile.user)
            true
        }.getOrDefault(false).also { refresh() }
    }

    companion object {
        @Volatile
        private var instance: AppRepository? = null

        fun get(context: Context): AppRepository = instance ?: synchronized(this) {
            instance ?: AppRepository(context.applicationContext).also { instance = it }
        }
    }
}
