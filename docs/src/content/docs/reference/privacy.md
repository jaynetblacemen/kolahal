---
title: Privacy & permissions
description: What each permission is for, and what leaves the device.
---

Kolahal has no account, no analytics, no ads and no crash reporting. Its dependencies are AndroidX,
Jetpack Compose, WorkManager, OkHttp and the two script engines (LuaJava and quickjs-kt). Your log,
settings, scripts, feeds and notifications are stored in the app's private storage on the phone.

## What leaves the device

Kolahal itself makes network requests in exactly three cases:

1. **Scripts** you write or install, through `http.*` and `rss.*`. They talk only to the URLs in
   their code. The [library scripts](/scripting/library/) use wttr.in, Open-Meteo,
   CoinGecko, open.er-api.com, api.ipify.org, Wikipedia, xkcd.com, ZenQuotes, Reddit, GitHub
   and the feeds they list. Requests carry a `User-Agent` of `Kolahal-Launcher/0.1` unless the
   script sets its own.
2. **Feeds** you subscribe to: one request per feed when it is due.
3. **Currency conversion** with `= 100 usd in eur`: a request to open.er-api.com for the base
   currency, cached for six hours.

Commands like `search`, `yt`, `wiki`, `maps`, `play` and `url` don't make requests themselves. They
hand a link to your browser or the matching app.

Plain `http://` is allowed, because many feeds are still served without TLS.

:::note[Android's own backup]
The app allows Android's system backup. If device backup is on in your Android settings, Android
may include Kolahal's data in it, as it does for other apps. Kolahal's own
[backup file](/guides/backup/) is separate and is only written where you choose.
:::

## Permissions

Declared in the manifest:

| Permission | Why |
| --- | --- |
| `INTERNET` | scripts, feeds and currency rates (see above) |
| `ACCESS_LOCAL_NETWORK` | <span class="k-new">new</span> Android 17 and newer: scripts with `@uses lan` and feeds on your own network (a home server, a router page). Asked for with `local-network`; optional |
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
Scripts imported from a backup file arrive disabled for that reason.
