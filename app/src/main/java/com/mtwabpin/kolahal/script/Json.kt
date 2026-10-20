package com.mtwabpin.kolahal.script

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Converts between JSON text and plain Kotlin values (Map / List / String / Number / Boolean / null). */
object Json {
    fun parse(text: String): Any? = fromOrg(JSONTokener(text).nextValue())

    fun stringify(value: Any?): String = when (val v = toOrg(value)) {
        is JSONObject, is JSONArray -> v.toString()
        is String -> JSONObject.quote(v)
        null, JSONObject.NULL -> "null"
        else -> v.toString()
    }

    private fun fromOrg(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { fromOrg(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { fromOrg(v.opt(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    private fun toOrg(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, value) -> o.put(k.toString(), toOrg(value)) } }
        is Collection<*> -> JSONArray().also { a -> v.forEach { a.put(toOrg(it)) } }
        is Array<*> -> JSONArray().also { a -> v.forEach { a.put(toOrg(it)) } }
        is Double -> if (v % 1.0 == 0.0 && v in -9.0e15..9.0e15) v.toLong() else v
        is Float -> toOrg(v.toDouble())
        is Number, is Boolean, is String -> v
        else -> v.toString()
    }

    /**
     * Lua tables arrive as maps with numeric keys; turn sequence-like ones (1..n) into lists.
     */
    fun normalizeLua(v: Any?): Any? = when (v) {
        is Map<*, *> -> {
            val n = v.size
            val isSequence = n > 0 && v.keys.all { k ->
                k is Number && k.toDouble() % 1.0 == 0.0 && k.toInt() in 1..n
            }
            if (isSequence) {
                val byIndex = HashMap<Int, Any?>(n * 2)
                v.forEach { (k, value) -> byIndex[(k as Number).toInt()] = value }
                (1..n).map { i -> normalizeLua(byIndex[i]) }
            } else {
                v.entries.associate { (k, value) ->
                    val key = if (k is Double && k % 1.0 == 0.0) k.toLong().toString() else k.toString()
                    key to normalizeLua(value)
                }
            }
        }
        is Double -> if (v % 1.0 == 0.0 && v in -9.0e15..9.0e15) v.toLong() else v
        else -> v
    }

    /** Flattens an arbitrary context object into string values for [com.mtwabpin.kolahal.data.LogContext]. */
    fun toStringMap(v: Any?): Map<String, String> = when (v) {
        is Map<*, *> -> v.entries.mapNotNull { (k, value) ->
            if (k == null || value == null) null
            else k.toString() to when (value) {
                is String -> value
                is Map<*, *>, is Collection<*> -> stringify(value)
                is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
                else -> value.toString()
            }
        }.toMap()
        else -> emptyMap()
    }
}
