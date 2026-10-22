---
title: Commands
description: Every built-in Kolahal command with its usage, aliases and subcommands.
---

This is the full list from `help`, in the same groups. Anything that isn't a command, an
[alias](/guides/apps/#aliases) or a [script command](/scripting/overview/#command-scripts)
is matched against app names. Arguments in `<angle brackets>` are required, `[square brackets]`
optional, and `a|b` means one of.

## apps

| Usage | Aliases | Description |
| --- | --- | --- |
| `open <app>[:shortcut] [-w\|-p\|-s]` | `launch` | launch an app, or one of its shortcuts (`open maps:home`) |
| `shortcuts <app>` | | an app's launcher shortcuts; tap one to start it |
| `apps [work\|personal\|private\|hidden] [-l]` | `ls` | app grid (`-l`: one per line; `-a`: include hidden) |
| `info <app> [-w]` | | open app info |
| `uninstall <app> [-w]` | | uninstall an app |
| `hide <app> [-w]` | | hide an app from lists and search |
| `unhide <app>` | | show a hidden app again (`apps hidden` lists them) |
| `pin <app>` | `fav` | favourite: first in the empty-prompt strip (`pin` alone lists them) |
| `unpin <app>` | `unfav` | remove a favourite |
| `pins` | `favs` | grid of pinned apps |
| `rename <app> = <name>` | | custom label; `rename <app> =` restores the original |
| `usage [<days>]` | `screentime` | screen time per app (1–30 days; needs Usage Access) |
| `installs [<n>]` | `updates` | recent app installs, updates and uninstalls |

## people

| Usage | Aliases | Description |
| --- | --- | --- |
| `call <name\|number>` | `dial` | dial a contact or number |
| `sms <name\|number> [<text>]` | `text` | start a text message |
| `contacts [<query>]` | `contact` | find contacts (favourites when empty) |
| `calls [<n>]` | | recent calls, incoming, outgoing and missed (20 by default; `calls --allow` asks for call log access) |

## web

| Usage | Aliases | Description |
| --- | --- | --- |
| `search <query>` | `g` | web search (engine: `config search.engine`) |
| `yt <query>` | `youtube` | search YouTube |
| `wiki <query>` | | search Wikipedia |
| `maps <place>` | `map` | find a place in your maps app |
| `play <query>` | `store` | search the Play Store |
| `url <address>` | `web` | open a link |

## feeds

| Usage | Aliases | Description |
| --- | --- | --- |
| `feed [add <url>\|rm\|fetch\|read\|open\|notify\|every] [<id>]` | `feeds` | RSS/Atom subscriptions with unread counts |
| `rss <url>` | | fetch a feed into the log once |
| `notifs [all\|enable\|mute <app>\|muted\|clear]` | `notifications-feed` | your notifications in the terminal |

`feed` subcommands:

| Subcommand | Does |
| --- | --- |
| `feed`, `feed ls` | list subscriptions with unread counts, interval and status |
| `feed add <url>` | subscribe and fetch now |
| `feed rm <id>` | unsubscribe and delete its items |
| `feed fetch [id]` | refresh now (alias `refresh`) |
| `feed read [id]` | show unread items, then mark them read |
| `feed all [id]` | show all stored items |
| `feed open [id]` | open up to 10 unread items in the browser and mark them read |
| `feed notify <id> on\|off` | notifications for new items |
| `feed every <id> <30m\|2h\|…>` | check interval (minimum 15m) |
| `feed on\|off <id>` | enable or pause a feed |

`feeds` with no arguments opens the [Feeds screen](/guides/feeds/#feeds-screen).

`notifs` subcommands: `notifs` (active), `notifs all` (including dismissed), `notifs enable`
(notification access settings), `notifs mute <app>`, `notifs unmute <app>`, `notifs muted`,
`notifs log on|off`, `notifs clear` (dismiss all).

## control

See [Media and device controls](/guides/media-and-controls/).

| Usage | Aliases | Description |
| --- | --- | --- |
| `media [play\|pause\|toggle\|next\|prev\|stop]` | | now playing, or control it (needs notification access) |
| `mp` | | play / pause |
| `next` | | next track |
| `prev` | | previous track |
| `torch [on\|off]` | | flashlight (no argument toggles) |
| `volume [0-100\|up\|down\|mute\|unmute]` | `vol` | media volume |
| `ring [normal\|vibrate\|silent]` | | ringer mode (`silent` needs Do Not Disturb access) |
| `dnd [on\|off\|alarms\|none\|status\|access]` | | Do Not Disturb (no argument toggles; `access` opens the grant page) |
| `brightness [0-100\|up\|down\|auto\|access]` | | screen brightness (needs Modify system settings; `access` opens the grant page) |
| `rotate [on\|off\|status]` | | auto-rotate (no argument toggles; needs Modify system settings) |
| `screen-timeout [15s\|30s\|1m\|…]` | | screen-off timeout (needs Modify system settings) |
| `wifi` | | Wi-Fi panel |
| `bt` | `bluetooth` | Bluetooth settings |
| `nfc` | | NFC panel |
| `data` | | Internet / mobile data panel |
| `toggles` | | the state of every toggle; tap a line to change it |

## productivity

See [Productivity](/guides/productivity/). `<n>` is the number shown in the list.

| Usage | Aliases | Description |
| --- | --- | --- |
| `todo [<text>\|add <text>]` | | list open todos, or add one (`todo buy milk`); tap an item to tick it |
| `todo done\|undo\|rm <n>` | | tick, untick or delete an item |
| `todo edit <n> <text>` | | change an item's text |
| `todo all` / `todo clear` | | include finished items / delete finished items |
| `note <text>` | | save a quick note |
| `notes [grep <text>\|rm <n>]` | | notes, newest first; tap copies |
| `remind <when> <text>` | | notification later: `10m`, `2h`, `1h30m`, `18:30`, `6pm`, `tomorrow 9:00`, `fri 8am` |
| `reminders` / `remind rm <n>` | | pending reminders / cancel one |
| `timer <5m\|1h30m\|25> [<label>]` | | start a timer in the clock app (no args: show timers) |
| `alarm <HH:MM\|7am> [<label>]` | | set an alarm in the clock app (no args: show alarms) |
| `alarms` / `stopwatch` | | open the clock app |
| `agenda [<days>]` | | upcoming calendar events (3 days by default, up to 31); tap opens one |

## profiles

| Usage | Description |
| --- | --- |
| `work [on\|off\|status]` | pause / resume the work profile |
| `private [on\|off\|status]` | lock / unlock private space |

## scripts

| Usage | Description |
| --- | --- |
| `scripts` | the script manager (the Scripts tab in Settings); `scripts <sub>` works like `script <sub>` |
| `script <sub> <name>` | manage scripts; subcommands below |
| `lua <code>` | evaluate Lua 5.4 |
| `js <code>` | evaluate JavaScript (QuickJS) |
| `= <expr>` | calculator: `= 5 km in mi`, `= 72 f to c`, `= 100 usd in eur` |
| `status [clear <name\|all>]` | header lines pinned by scripts |

`script` subcommands:

| Subcommand | Does |
| --- | --- |
| `script ls` | list scripts with timers, flags and last result (also plain `script`) |
| `script lib` | the bundled [library](/scripting/library/) |
| `script install <name> [--replace]` | install or update a library script; `--replace` overwrites a copy you edited |
| `script get [<name>] [--refresh] [--replace]` | list the [shared scripts](/scripting/overview/#getting-scripts-from-others), or install or update one |
| `script new <name>[.lua\|.js] [lua\|js] [--cmd]` | create from a template and open the editor |
| `script edit <name>` | open the editor |
| `script run <name>` | run now |
| `script cat <name>` | print the code with line numbers (alias `show`) |
| `script every <name> <15m\|30m\|2h\|1d\|off>` | interval timer |
| `script at <name> <HH:mm[,HH:mm…]\|off>` | daily times |
| `script notify <name> on\|off` | allow `notify()` for this script |
| `script on\|off <name>` | enable / disable (aliases `enable`, `disable`) |
| `script access <name>` | each [access switch](/scripting/overview/#switching-access-off), on or off; tap one to change it |
| `script allow\|deny <name> <what…>` | switch access on or off: `internet`, `header`, an `@uses` item, an `@on` event, or `notify` |
| `script secret <name> [<NAME> [--clear]]` | its [secrets](/scripting/overview/#api-keys-and-other-secrets), set or not; with a name, a box to set it (`--clear` forgets it) |
| `script mv <name> <new-name>` | [rename it](/scripting/overview/#renaming-a-script) (alias `rename`) |
| `script rm <name>` | delete it with its timers, data and status line (alias `delete`) |
| `script dir` | pick a sync folder |
| `script export` | write every script plus `kolahal.json` to the folder |
| `script import` | read scripts back from the folder |

Script names use lowercase letters, digits, `-` and `_` (up to 40), and can't be a built-in command.

## terminal

| Usage | Aliases | Description |
| --- | --- | --- |
| `help` | `?` | show the command list |
| `history` | | recent commands (tap to reuse) |
| `clear [history\|all]` | `cls` | clear the screen, the ↑ history, or both |
| `fresh` | | a clean screen now, like `log.fresh`: nothing is deleted, "▴ N earlier lines" brings it back. Not logged |
| `restart [scripts]` | `reboot` | restart the launcher, or stop running scripts |
| `alias [name = command]` | | list or define aliases |
| `unalias <name>` | | remove an alias |
| `config [ls\|set\|get\|reset] <key> <v>` | `set` | settings (no args opens the settings page) |
| `theme <name>` | | `green` `amber` `mono` `dracula` `solarized` `gruvbox` `gruvbox-material` |
| `font [<name>\|import\|sans [off\|prompt\|all]]` | | terminal font: `system` `jetbrains-mono` `fira-code` `ibm-plex-mono` `source-code-pro` `custom`; no args lists them (tap to apply), `import` picks a .ttf/.otf; `sans prompt` or `sans all` uses the phone's font for the prompt or everything (`font.sans`) |
| `echo <text>` | | print text |
| `date` | `time` | current date and time |

## widgets

See [Widgets](/guides/widgets/).

| Usage | Aliases | Description |
| --- | --- | --- |
| `widgets` | | Widgets page: placed widgets, add, resize, arrange, move to / from home |
| `widgets ls` | `widget ls` | placed widgets as text lines (tap one to open the page) |
| `widget add` | | open the widget picker; the widget goes on the widgets page |

## device

| Usage | Aliases | Description |
| --- | --- | --- |
| `settings` | `prefs` | Kolahal settings screen (+ backup import/export) |
| `setup [text]` | | setup checklist page (`text`: print it in the log) |
| `unlocks [<days>]` | | unlock counts (1–30 days) |
| `notifications [settings]` | | allow script notifications |
| `usage-access` | | grant usage access (unlock count) |
| `local-network [--settings]` | | let scripts (`@uses lan`) and feeds reach your local network (Android 17+); `--settings` opens app settings after a denial |
| `crashes [share\|clear] [<n>]` | | crash reports kept on this phone, newest first (tap one to share it); `share` opens the share sheet with report `n` (default: the newest), `clear` deletes them all. See [Privacy](/reference/privacy/#crash-reports) |
| `battery` | | battery optimization settings |
| `home` | | choose the default launcher |
| `lock` | | lock the screen (needs the accessibility service) |
| `shade` | | pull down the notification shade |
| `accessibility` | | enable the screen-lock service for double-tap |
| `system` | | Android system settings |

## Profile flags

If you have a work profile, a private space or cloned apps, app commands take a flag to pick one:
`-w` work, `-p` personal, `-s` private space, `-c` clone. See
[Work profile & private space](/guides/profiles/).
