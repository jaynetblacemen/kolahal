package com.mtwabpin.kolahal.script

import com.mtwabpin.kolahal.data.ScriptLang

/**
 * A scripting runtime. Both implementations expose the same [HostApi] surface, so a script's
 * behaviour only depends on its language syntax, not on which engine runs it.
 */
interface ScriptEngine {
    /**
     * Runs [code] to completion.
     *
     * @param repl when true, the code is a one-liner typed at the prompt and its value is returned.
     * @param args arguments when the script is invoked as a command (`args` / `argline` globals).
     * @param event payload of the device event that triggered the run (`event` global; nil/null otherwise).
     * @return a printable rendering of the result value, or null when there is nothing to show.
     */
    suspend fun run(
        code: String,
        chunkName: String,
        host: HostApi,
        timeoutMs: Long,
        repl: Boolean,
        args: List<String> = emptyList(),
        event: Map<*, *>? = null,
    ): String?

    companion object {
        fun forLang(lang: ScriptLang): ScriptEngine = when (lang) {
            ScriptLang.LUA -> LuaEngine()
            ScriptLang.JS -> JsEngine()
        }
    }
}
