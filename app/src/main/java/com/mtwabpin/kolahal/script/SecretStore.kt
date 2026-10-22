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
 */
class SecretStore(private val context: Context) {

    fun get(script: String, name: String): String? =
        KvStore(context, scope(script)).get(name)?.let { runCatching { decrypt(it) }.getOrNull() }

    /** Sets [name] for [script]; null or blank clears it. */
    fun set(script: String, name: String, value: String?) {
        KvStore(context, scope(script)).set(name, value?.takeIf { it.isNotBlank() }?.let(::encrypt))
    }

    fun isSet(script: String, name: String): Boolean = KvStore(context, scope(script)).get(name) != null

    /** Every value [script] has, by name: for a run, which may read only what it declares. */
    fun all(script: String): Map<String, String> =
        KvStore(context, scope(script)).all().mapNotNull { (k, v) -> runCatching { k to decrypt(v) }.getOrNull() }.toMap()

    /** Forgets every value of [script] (when it is deleted). */
    fun clear(script: String) {
        val kv = KvStore(context, scope(script))
        kv.all().keys.forEach { kv.set(it, null) }
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
        (keyStore.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
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

        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "kolahal-script-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
