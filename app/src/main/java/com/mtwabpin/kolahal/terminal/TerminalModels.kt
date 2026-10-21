package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.apps.AppEntry

/** One-off requests from the view model to the activity or the screen. */
sealed interface UiEvent {
    data object RequestNotificationPermission : UiEvent
    data object ScrollToBottom : UiEvent
    data object FocusPrompt : UiEvent
    data object HideKeyboard : UiEvent
    data object PickScriptFolder : UiEvent
    data object RequestContactsPermission : UiEvent
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

    data class App(val app: AppEntry) : Suggestion {
        override val label get() = app.label
        override val key get() = "a:" + app.key
    }

    /** [app]: the app this completion is about (`uninstall maps`, `open maps:…`), for its icon. */
    data class Completion(override val label: String, val replacement: String, val app: AppEntry? = null) : Suggestion {
        override val key get() = "c:" + replacement.trimEnd()
    }
}

/**
 * One entry of a log line's long-press menu. [icon]: a short symbol drawn in a fixed-width column,
 * so labels line up whatever its width.
 */
data class LogAction(val label: String, val destructive: Boolean = false, val icon: String? = null, val run: () -> Unit)
