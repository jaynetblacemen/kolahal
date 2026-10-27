package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.apps.ProfileKind

/** One-off requests from the view model to the activity or the screen. */
sealed interface UiEvent {
    data object RequestNotificationPermission : UiEvent
    data object ScrollToBottom : UiEvent
    data object FocusPrompt : UiEvent
    data object HideKeyboard : UiEvent
    data object PickScriptFolder : UiEvent
    data object RequestContactsPermission : UiEvent
    data object PickContact : UiEvent
    data object RequestCalendarPermission : UiEvent
    data object RequestCallLogPermission : UiEvent
    data object RequestLocalNetworkPermission : UiEvent
    data object PickFont : UiEvent
    data object ShowQuick : UiEvent
}

/** Something the user can tap in the suggestion strip. */
sealed interface Suggestion {
    val label: String

    /** Identity in the suggestion lists; [TerminalViewModel.suggestions] never returns two with the same key. */
    val key: String

    /** Enter does what this row does: only ever the first row, highlighted with a ⏎. */
    val enter: Boolean

    data class App(val app: AppEntry, override val enter: Boolean = false) : Suggestion {
        override val label get() = app.label
        override val key get() = "a:" + app.key
    }

    /**
     * [app]: the app this completion is about (`uninstall maps`, `open maps:…`), for its icon; else
     * [icon], a drawable for what kind of thing it is (a feed, a script, a contact), else the
     * terminal glyph. [help]: shown beside it, when the command and config descriptions don't apply.
     */
    data class Completion(
        override val label: String,
        val replacement: String,
        val app: AppEntry? = null,
        val icon: Int? = null,
        val help: String? = null,
        override val enter: Boolean = false,
    ) : Suggestion {
        override val key get() = "c:" + replacement.trimEnd()
    }
}

/**
 * What the Undo bar under the log offers: [text] says what was done, [undo] takes it back, and
 * [expire] runs once it can't be any more (it timed out, or another offer took its place).
 */
class UndoOffer(val text: String, val expire: () -> Unit = {}, val undo: () -> Unit)

/**
 * One entry of a log line's long-press menu. [icon]: drawn in a fixed-width column, so labels
 * line up whatever its width.
 */
data class LogAction(val label: String, val destructive: Boolean = false, val icon: ActionIcon? = null, val run: () -> Unit)

/** A menu icon in the header's style and size. */
sealed interface ActionIcon {
    /** A drawable [id], dim like the header's, or coloured as the header colours it: [profile]'s colour, red for [alert]. */
    data class Drawable(val id: Int, val profile: ProfileKind? = null, val alert: Boolean = false) : ActionIcon

    /** The header's battery, drawn to its level. */
    data class Battery(val pct: Int, val charging: Boolean) : ActionIcon
}
