package com.mtwabpin.kolahal.script

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mtwabpin.kolahal.data.KvStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Values for the names a script declares with `@secret` (API keys and the like), so they never sit in
 * its code. Encrypted with AES-GCM under a key kept in the Android Keystore, which never leaves it;
 * stored in kolahal.db (scope [scope] per script name), which Android's cloud backup and device
 * transfer leave out, as do Kolahal's own backups ([com.mtwabpin.kolahal.data.FullBackup]). Both the
 * launcher and the `:script` process read them: the Keystore key belongs to the app, not a process.
 *
 * Each value remembers the sites its `@secret` line named when you set it ([Stored.hosts]): code
 * that later names other sites (an update, a file over it) doesn't get the value until you set it
 * again, having seen where it would go.
 */
class SecretStore(private val context: Context) {

    /** A value and the sites it was set for; [hosts] is null for a value set before they were kept. */
    class Stored(val value: String, val hosts: List<String>?) {
        /** Whether the sites [secret] names now are all ones this value was set for. */
        fun covers(secret: ScriptMeta.Secret): Boolean = hosts == null || secret.hosts.all { it in hosts }
    }

    fun get(script: String, name: String): Stored? =
        KvStore(context, scope(script)).get(name)?.let { runCatching { decode(decrypt(it)) }.getOrNull() }

    /** Sets [name] for [script], for the sites [hosts] its line names; null or blank clears it. */
    fun set(script: String, name: String, value: String?, hosts: List<String>) {
        KvStore(context, scope(script)).set(name, value?.takeIf { it.isNotBlank() }?.let { encrypt(encode(it, hosts)) })
    }

    /** Whether [secret] has a value that may go where its line says now. */
    fun isSet(script: String, secret: ScriptMeta.Secret): Boolean = get(script, secret.name)?.covers(secret) == true

    /** It has a value, but its line names sites the value wasn't set for. */
    fun hostsChanged(script: String, secret: ScriptMeta.Secret): Boolean = get(script, secret.name)?.let { !it.covers(secret) } == true

    /** Every value [script] has that its [declared] lines may use: for a run. */
    fun usable(script: String, declared: List<ScriptMeta.Secret>): Map<String, String> =
        declared.mapNotNull { d -> get(script, d.name)?.takeIf { it.covers(d) }?.let { d.name to it.value } }.toMap()

    /** Forgets every value of [script] (when it is deleted). */
    fun clear(script: String) {
        val kv = KvStore(context, scope(script))
        kv.all().keys.forEach { kv.set(it, null) }
    }

    private fun encode(value: String, hosts: List<String>) = "$MARK${hosts.joinToString(",")}$MARK$value"

    private fun decode(plain: String): Stored {
        if (!plain.startsWith(MARK)) return Stored(plain, null)
        val end = plain.indexOf(MARK, 1)
        if (end < 0) return Stored(plain, null)
        return Stored(plain.substring(end + 1), plain.substring(1, end).split(',').filter { it.isNotEmpty() })
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey = synchronized(SecretStore::class.java) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        // A key the Keystore can no longer read (it was reset) can't open anything again: start over.
        // Values sealed with it then read as not set, and setting one again works.
        val existing = runCatching { keyStore.getKey(ALIAS, null) as? SecretKey }.getOrElse {
            runCatching { keyStore.deleteEntry(ALIAS) }
            null
        }
        existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        /** kv scopes holding secrets start with this; backups skip them. */
        const val SCOPE_PREFIX = "secret:"
        fun scope(script: String) = SCOPE_PREFIX + script

        /** Shorter values can't be told apart from ordinary text, so they couldn't be kept out of the log. */
        const val MIN_LENGTH = 4

        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "kolahal-script-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val MARK = "\u0000"
    }
}
