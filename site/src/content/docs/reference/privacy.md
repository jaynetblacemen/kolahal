---
title: Privacy & permissions
description: What each permission is for, and what leaves the device.
---

Kolahal has no account, no analytics, no ads and no crash reporting service: a
[crash report](#crash-reports) stays on your phone until you share it. Its dependencies are AndroidX,
Jetpack Compose, WorkManager, OkHttp and the two script engines (LuaJava and quickjs-kt). Your log,
settings, scripts, feeds and notifications are stored in the app's private storage on the phone.

## What leaves the device

Kolahal itself makes network requests in exactly four cases:

1. **Scripts** you write or install, through `http.*` and `rss.*`. They talk only to the URLs in
   their code. The [library scripts](/scripting/library/) use wttr.in, Open-Meteo,
   CoinGecko, open.er-api.com, api.ipify.org, Wikipedia, xkcd.com, ZenQuotes, Reddit and
   GitHub. Requests carry a `User-Agent` of `Kolahal-Launcher/0.1` unless the
   script sets its own. You can switch internet off for any script
   ([switching access off](/scripting/overview/#switching-access-off)).
2. **Feeds** you subscribe to: one request per feed when it is due.
. **Getting scripts**: when you open Get more scripts, refresh it or run `script get`, the list of
   shared scripts from mtwabpin.github.io (kept for an hour), and the file of a script you look at
   or install.

Commands like `search`, `yt`, `wiki`, `maps`, `play` and `url` don't make requests themselves. They
hand a link to your browser or the matching app.

Plain `http://` is allowed, because many feeds are still served without TLS.

## Crash reports

If Kolahal stops unexpectedly, it keeps a report on the phone: the app version and
build, the Android version, the phone model, which part of Kolahal stopped, and the error with
its stack trace (the code that was running). On Android 11 and newer it also notes when Kolahal
froze ("not responding") or crashed inside native code such as the script engines, from what
Android recorded.

The next time you open home, a red line says Kolahal stopped unexpectedly; tap it to share the
report with the share sheet (email, a chat, or paste it into a GitHub issue). `crashes` lists the
reports, and `crashes clear` deletes them. **Settings → About → Crash reports** lists them too: tap
one to email it to the developer (your mail app opens a draft with the report, which you send
yourself) or to delete it, or delete them all at once. Nothing is sent by itself, the newest 10 are kept, and
they are left out of Android's backup. An error message can contain a bit of what you were doing
(a command, a file name), so read the report before you send it.

:::note[Android's own backup]
The app allows Android's system backup. If device backup is on in your Android settings, Android
may include Kolahal's data in it, as it does for other apps. Kolahal's own
[backup file](/guides/backup/) is separate and is only written where you choose.
:::

## Permissions

The Play build (the Google Play version, also an APK on GitHub) leaves three of these out:
`READ_CALL_LOG`, the accessibility service and notification access. The calls in the log, the
screen lock and notifications in the log are only in the full build.

Declared in the manifest:

| Permission | Why |
| --- | --- |
| `INTERNET` | scripts, feeds and currency rates (see above) |
| `ACCESS_LOCAL_NETWORK` | Android 17 and newer: scripts with `@uses lan` and feeds on your own network (a home server, a router page). Asked for with `local-network`; optional |
| `ACCESS_NETWORK_STATE` | the `net` item in the header and `device.network()` |
| `READ_CONTACTS` | `call`, `sms` and `contacts` name matching. Asked for on first use; optional |
| `READ_CALENDAR` | `agenda` lists upcoming events. Asked for on first use; optional; nothing leaves the device |
| `SET_ALARM` | `timer` and `alarm` hand the timer or alarm to your clock app |
| `SCHEDULE_EXACT_ALARM` | `remind` fires on time (Android 12+ lets you turn it off; reminders may then be a few minutes late) |
| `RECEIVE_BOOT_COMPLETED` | pending reminders are set again after a reboot |
| `POST_NOTIFICATIONS` | notifications from scripts and feeds (Android 13+ asks at runtime) |
| `REQUEST_DELETE_PACKAGES` | `uninstall` and the Uninstall action open Android's uninstall dialog |
| `EXPAND_STATUS_BAR` | swipe down on the header (or `shade`) pulls down the notification shade |
| `ACCESS_HIDDEN_PROFILES` | lets the default launcher see the Android 15+ private space |
| `PACKAGE_USAGE_STATS` | Usage Access, which you grant in Settings: accurate unlock counts and `usage` screen time |
| `ACCESS_NOTIFICATION_POLICY` | lists Kolahal on the Do Not Disturb access page, for `dnd` and `ring silent` (you still grant it) |
| `WRITE_SETTINGS` | "Modify system settings", which you grant in Settings: `brightness`, `rotate`, `screen-timeout` |
| `ACCESS_WIFI_STATE` | whether Wi-Fi is on, for `toggles` |

`torch` needs no camera permission: it only switches the flash.

Kolahal also asks Android for the list of launchable apps (a `<queries>` entry for launcher
activities). That's needed to show your apps at all.

## Special access you can grant

These are off until you enable them, usually from the [setup checklist](/getting-started/setup/):

| Access | Why | Without it |
| --- | --- | --- |
| Default home app | opens on Home; pausing/resuming profiles, app shortcuts, private space | works as a normal app |
| Usage access | accurate unlocks, `usage` | unlocks counted while the launcher runs; no `usage` |
| Notification access | notifications in the log, `notifs`, `notifications.recent()`, `media` and the header's now-playing line (reading media sessions) | none of these |
| Do Not Disturb access | `dnd`, `ring silent` | those print a tappable line to grant it |
| Modify system settings | `brightness`, `rotate`, `screen-timeout` | those print a tappable line to grant it |
| Accessibility service **Kolahal screen lock** | double-tap / `lock` locks the screen. It can't read window content and receives no events | no lock gesture |
| Local network (Android 17+) | scripts with `@uses lan`, feeds on a home server | those requests fail with a line to tap and allow it |
| Battery unrestricted | timers and feeds run on time | Android may defer them |

Notification text read through notification access stays on the device: it goes into the log and
an in-memory list, and a copy of the 50 most recent is kept so scripts can call
`notifications.recent()`. A script you write could send that data somewhere, the same as anything
else it can read, so only install scripts you trust and read the code of ones you get from others.
Scripts imported from a file or a backup arrive switched off for that reason, and each script's
access switches take back what it may read.
