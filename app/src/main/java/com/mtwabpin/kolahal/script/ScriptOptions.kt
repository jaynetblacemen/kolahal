package com.mtwabpin.kolahal.script

import android.content.Context
import com.mtwabpin.kolahal.data.KvStore

/**
 * The values you gave a script's `@option`s ([ScriptMeta.options]), by script name, apart from its
 * code: a library update keeps them, and backups carry them. An option you never set, or set to a
 * choice its code no longer offers, reads as its default.
 */
class ScriptOptions(private val context: Context) {
    private fun store(script: String) = KvStore(context, scope(script))

    /** Every declared option's value: yours, or its default. */
    fun values(script: String, declared: List<ScriptMeta.Option>): Map<String, String> {
        val set = store(script).all()
        return declared.associate { o -> o.name to (set[o.name]?.takeIf { o.choices.isEmpty() || it in o.choices } ?: o.default) }
    }

    /** The options you set, of those declared. */
    fun changed(script: String, declared: List<ScriptMeta.Option>): Set<String> {
        val set = store(script).all()
        return declared.filter { o -> set[o.name]?.let { o.choices.isEmpty() || it in o.choices } == true }.mapTo(HashSet()) { it.name }
    }

    /** [value] for [option]; null goes back to its default. */
    fun set(script: String, option: String, value: String?) = store(script).set(option, value)

    companion object {
        const val SCOPE_PREFIX = "option:"
        fun scope(script: String) = SCOPE_PREFIX + script
    }
}
