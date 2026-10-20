package com.mtwabpin.kolahal.gesture

import android.content.Context

/** Built-in gesture actions; any other gesture value is run as a terminal command. */
@android.annotation.SuppressLint("WrongConstant")
object Gestures {
    const val LOCK = "lock"
    const val SHADE = "shade"
    const val NONE = "none"

    /** Pulls down the notification shade (hidden API used by most launchers; no-op if blocked). */
    fun expandShade(context: Context) {
        runCatching {
            val sbm = context.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        }
    }
}
