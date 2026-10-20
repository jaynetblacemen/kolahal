package com.mtwabpin.kolahal.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Contact(
    val id: Long,
    val name: String,
    val number: String,
    val label: String,
    val starred: Boolean,
    val lookupUri: Uri,
)

/**
 * Contacts with phone numbers, cached in memory for instant `call` / `sms` suggestions.
 * Empty until READ_CONTACTS is granted; refreshes when the contacts database changes.
 */
class ContactsRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    private var observing = false

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

    fun refresh() {
        if (!hasPermission()) return
        if (!observing) {
            observing = true
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    // Sync adapters fire bursts of changes; coalesce them into one reload.
                    override fun onChange(selfChange: Boolean) = reload(RELOAD_DEBOUNCE_MS)
                },
            )
        }
        reload(0)
    }

    private var reloadJob: Job? = null

    /** Cancels any pending reload and starts a new one after [delayMs]. */
    @Synchronized
    private fun reload(delayMs: Long) {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!hasPermission()) return@launch
            runCatching { _contacts.value = load() }
        }
    }

    private fun load(): List<Contact> {
        val out = ArrayList<Contact>()
        val seen = HashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
            "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY}",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val number = c.getString(2)?.trim() ?: continue
                // Same number stored twice (e.g. synced from two accounts): keep one.
                if (!seen.add("$id:${number.filter { it.isDigit() || it == '+' }}")) continue
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    context.resources, c.getInt(3), c.getString(4),
                ).toString().lowercase()
                out += Contact(
                    id = id,
                    name = name,
                    number = number,
                    label = label,
                    starred = c.getInt(5) != 0,
                    lookupUri = ContactsContract.Contacts.getLookupUri(id, c.getString(6)),
                )
            }
        }
        return out
    }

    /** Name or number matches, favourites first. */
    fun search(query: String, limit: Int = 12): List<Contact> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val digits = q.filter { it.isDigit() }
        return _contacts.value.asSequence()
            .mapNotNull { c ->
                val name = c.name.lowercase()
                val score = when {
                    name == q -> 0
                    name.startsWith(q) -> 1
                    name.split(' ').any { it.startsWith(q) } -> 2
                    name.contains(q) -> 3
                    digits.length >= 3 && c.number.filter { it.isDigit() }.contains(digits) -> 4
                    else -> null
                }
                score?.let { c to it }
            }
            .sortedWith(compareBy({ it.second }, { !it.first.starred }, { it.first.name }))
            .map { it.first }
            .take(limit)
            .toList()
    }

    companion object {
        private const val RELOAD_DEBOUNCE_MS = 1_000L

        @Volatile
        private var instance: ContactsRepository? = null

        fun get(context: Context): ContactsRepository = instance ?: synchronized(this) {
            instance ?: ContactsRepository(context.applicationContext).also { instance = it }
        }

        fun looksLikeNumber(s: String) = s.isNotBlank() && s.all { it.isDigit() || it in "+-() *#" } && s.count { it.isDigit() } >= 3
    }
}
