package com.mtwabpin.kolahal.widgets

/**
 * Pure widget bookkeeping (no Android types) so ordering, resizing and serialisation can be unit
 * tested. [WidgetHost] owns the Android side.
 */
object WidgetLocation {
    /** The Widgets page only. */
    const val PANEL = "panel"
    /** Also shown under the terminal header. */
    const val HOME = "home"

    fun of(raw: Any?): String = if (raw?.toString()?.lowercase() == HOME) HOME else PANEL
}

/**
 * A placed widget. [id] is the AppWidget id, or null for an entry restored from a backup that still
 * has to be bound again ("pending re-add"). [provider] is a flattened ComponentName.
 */
data class PlacedWidget(
    val id: Int?,
    val provider: String,
    val profileSerial: Long,
    val heightDp: Int,
    val order: Int,
    val location: String = WidgetLocation.PANEL,
) {
    val pending: Boolean get() = id == null
    val onHome: Boolean get() = location == WidgetLocation.HOME
    /** Stable key for lists: the widget id, or provider + order for pending entries. */
    val key: String get() = id?.let { "w$it" } ?: "p:$provider:$profileSerial:$location:$order"
    /** "com.example/.Clock" -> "com.example". */
    val packageName: String get() = provider.substringBefore('/')
}

object WidgetLayout {
    /** Resize step for the +/- buttons (one touch-target row). */
    const val STEP_DP = 48
    const val MIN_DP = 48
    const val MAX_DP = 1200

    /** Smallest height a widget may be shrunk to: its resize minimum if it can resize vertically, else its minimum. */
    fun minHeight(minHeightDp: Int, minResizeHeightDp: Int, resizableVertically: Boolean): Int {
        val m = if (resizableVertically && minResizeHeightDp > 0) minResizeHeightDp else minHeightDp
        return m.coerceIn(MIN_DP, MAX_DP)
    }

    /** Height for a freshly added widget: the provider's minimum, never below one step. */
    fun defaultHeight(minHeightDp: Int, targetCellHeight: Int = 0): Int {
        val fromCells = if (targetCellHeight > 0) targetCellHeight * 90 else 0
        return maxOf(minHeightDp, fromCells, 72).coerceIn(MIN_DP, MAX_DP)
    }

    /** Applies [deltaDp] (e.g. ±[STEP_DP]) and clamps to [min]..[MAX_DP]. */
    fun resize(current: Int, deltaDp: Int, min: Int): Int = (current + deltaDp).coerceIn(min.coerceAtMost(MAX_DP), MAX_DP)

    /** Launcher grid cells for a size in dp (the classic 70n − 30 rule). */
    fun cells(dp: Int): Int = maxOf(1, (dp + 30) / 70)

    /** "4×2" from provider sizes; target cells (Android 12+) win when the provider declares them. */
    fun sizeLabel(minWidthDp: Int, minHeightDp: Int, targetCellWidth: Int = 0, targetCellHeight: Int = 0): String {
        val w = if (targetCellWidth > 0) targetCellWidth else cells(minWidthDp)
        val h = if (targetCellHeight > 0) targetCellHeight else cells(minHeightDp)
        return "$w×$h"
    }
}

/** Ordering helpers. Orders are only meaningful within one location. */
object WidgetOrder {
    fun sorted(all: List<PlacedWidget>): List<PlacedWidget> = all.sortedWith(compareBy({ it.order }, { it.id ?: Int.MAX_VALUE }))

    fun inLocation(all: List<PlacedWidget>, location: String): List<PlacedWidget> =
        sorted(all.filter { !it.pending && it.location == location })

    fun nextOrder(all: List<PlacedWidget>): Int = (all.maxOfOrNull { it.order } ?: -1) + 1

    /**
     * Moves the widget with [key] one place up (-1) or down (+1) among the placed widgets of its
     * location and renumbers that location 0..n-1. Other entries are untouched.
     */
    fun move(all: List<PlacedWidget>, key: String, direction: Int): List<PlacedWidget> {
        val target = all.firstOrNull { it.key == key } ?: return all
        val group = inLocation(all, target.location).toMutableList()
        val from = group.indexOfFirst { it.key == key }
        val to = from + direction.coerceIn(-1, 1)
        if (from < 0 || to !in group.indices) return all
        group.add(to, group.removeAt(from))
        val renumbered = group.mapIndexed { i, w -> w.key to i }.toMap()
        return all.map { w -> renumbered[w.key]?.let { w.copy(order = it) } ?: w }
    }

    /** Moves a widget to another location, at the end of it. */
    fun relocate(all: List<PlacedWidget>, key: String, location: String): List<PlacedWidget> {
        val next = nextOrder(all.filter { it.location == location })
        return all.map { if (it.key == key && it.location != location) it.copy(location = location, order = next) else it }
    }
}

/**
 * One record per line in SharedPreferences: `id|provider|serial|height|order|location`, with `-`
 * for the id of a pending entry. Flattened ComponentNames never contain `|`.
 */
object WidgetCodec {
    fun encode(all: List<PlacedWidget>): String = all.joinToString("\n") {
        listOf(it.id?.toString() ?: "-", it.provider, it.profileSerial, it.heightDp, it.order, it.location).joinToString("|")
    }

    fun decode(text: String?): List<PlacedWidget> = text.orEmpty().lineSequence().mapNotNull { line ->
        val f = line.split('|')
        if (f.size < 6 || f[1].isBlank() || !f[1].contains('/')) return@mapNotNull null
        PlacedWidget(
            id = f[0].toIntOrNull(),
            provider = f[1],
            profileSerial = f[2].toLongOrNull() ?: 0L,
            heightDp = (f[3].toIntOrNull() ?: WidgetLayout.MIN_DP).coerceIn(WidgetLayout.MIN_DP, WidgetLayout.MAX_DP),
            order = f[4].toIntOrNull() ?: 0,
            location = WidgetLocation.of(f[5]),
        )
    }.toList()

    /** Backup form: ids are device-specific, so they are left out. */
    fun export(all: List<PlacedWidget>): List<Map<String, Any?>> = WidgetOrder.sorted(all).map {
        mapOf(
            "provider" to it.provider,
            "profile" to it.profileSerial,
            "height" to it.heightDp,
            "location" to it.location,
            "order" to it.order,
        )
    }

    /** Parses backup maps (numbers may arrive as Int, Long, Double or String) into pending entries. */
    fun fromExport(list: List<Map<String, Any?>>): List<PlacedWidget> = list.mapNotNull { m ->
        val provider = m["provider"]?.toString()?.takeIf { it.contains('/') && !it.contains('|') && !it.contains('\n') }
            ?: return@mapNotNull null
        fun num(key: String): Long? = when (val v = m[key]) {
            is Number -> v.toLong()
            is String -> v.toDoubleOrNull()?.toLong()
            else -> null
        }
        PlacedWidget(
            id = null,
            provider = provider,
            profileSerial = num("profile") ?: 0L,
            heightDp = (num("height")?.toInt() ?: WidgetLayout.MIN_DP).coerceIn(WidgetLayout.MIN_DP, WidgetLayout.MAX_DP),
            order = num("order")?.toInt() ?: 0,
            location = WidgetLocation.of(m["location"]),
        )
    }

    /** Adds imported pending entries, skipping ones that duplicate an existing pending entry. */
    fun mergePending(existing: List<PlacedWidget>, imported: List<PlacedWidget>): List<PlacedWidget> {
        val seen = existing.filter { it.pending }.map { it.key }.toMutableSet()
        return existing + imported.filter { seen.add(it.key) }
    }
}
