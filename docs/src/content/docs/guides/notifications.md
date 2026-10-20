---
title: Notifications
description: Your notifications in the terminal log, muting apps, and notifications posted by scripts and feeds.
---

There are two separate things here:

1. **Notification access** lets Kolahal *read* your notifications, so they appear in the log.
2. The **notifications permission** lets Kolahal *post* notifications for scripts and feeds.

## Notifications in the log

Grant access with `notifs enable` (or **setup → Notification access**). From then on, new
notifications appear in the log as `title — text`, ending in the app's status-bar icon, muted
(<span class="k-new">new</span>). A line whose icon isn't saved (older lines, or an app that draws its icon at
run time) ends in the app's name instead, `· Mail`. Notifications an app marks secret are left out.
Copying a notification line marks the copy as sensitive, so keyboards don't show it in their
clipboard previews.

- **Tap** opens the notification (or the app, if the notification is gone).
- **Long-press** to dismiss it, mute the app, or copy the text.

To keep the log readable, Kolahal skips its own notifications, ongoing ones (music, navigation),
foreground-service and group-summary notifications, low-importance (silent) channels, and
notifications without a title or text. A chat app re-posting the same notification logs at most
one line per 30 seconds.

```sh
notifs                 # active notifications
notifs all             # recent ones, including dismissed
notifs mute whatsapp   # stop logging an app (it still shows in the shade)
notifs unmute whatsapp
notifs muted           # list muted apps (tap one to get its unmute command)
notifs log off         # stop logging notifications at all; notifs log on to resume
notifs clear           # dismiss everything and clear the list
```

Swipe down on the header to open the system shade (see [Gestures](/guides/gestures/)).

Scripts can read recent notifications with
[`notifications.recent(n)`](/scripting/api/#notificationsrecentn) while access is granted.

## Notifications from scripts and feeds

Allow posting with `notifications` (on Android 13+ this asks for the permission; otherwise it
opens Kolahal's notification settings; `notifications settings` always opens them).

- A script posts with [`notify(title, body, ctx)`](/scripting/api/#notifytitle-body-ctx), but
  only if notifications are on for that script: `script notify <name> on`, or the switch in its
  sheet in the script manager.
- A feed with notifications on (`feed notify <id> on`) posts one notification per fetch that found
  new items. See [Feeds](/guides/feeds/).
