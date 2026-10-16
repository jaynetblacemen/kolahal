---
title: Commands
description: Every built-in Kolahal command with its usage, aliases and subcommands.
---

This is the full list from `help`, in the same groups. Anything that isn't a command, an
[alias](../../guides/apps/#aliases) or a [script command](../../scripting/overview/#command-scripts)
is matched against app names. Arguments in `<angle brackets>` are required, `[square brackets]`
optional, and `a|b` means one of.

Profile flags for app commands: `-w` work, `-p` personal, `-s` private space, `-c` clone. See
[Profiles](../../guides/profiles/).

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
| `rename <app> = <name>` | | custom label; `rename <app> =` restores the original |
| `usage [days]` | `screentime` | screen time per app (1–30 days; needs Usage Access) |

## people

| Usage | Aliases | Description |
| --- | --- | --- |
| `call <name\|number>` | `dial` | dial a contact or number |
| `sms <name\|number> [text]` | `text` | start a text message |
| `contacts [query]` | `contact` | find contacts (favourites when empty) |

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
| `feed [add <url>\|rm\|fetch\|read\|open\|notify\|every] [id]` | `feeds` | RSS/Atom subscriptions with unread counts |
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

<span class="k-new">new</span> `feeds` with no arguments opens the [Feeds screen](../../guides/feeds/#feeds-screen).

`notifs` subcommands: `notifs` (active), `notifs all` (including dismissed), `notifs enable`
(notification access settings), `notifs mute <app>`, `notifs unmute <app>`, `notifs muted`,
`notifs log on|off`, `notifs clear` (dismiss all).

## profiles

| Usage | Description |
| --- | --- |
| `work [on\|off\|status]` | pause / resume the work profile |
| `private [on\|off\|status]` | lock / unlock private space |

## scripts

| Usage | Description |
| --- | --- |
| `scripts` | script manager screen (import files, timers); `scripts <sub>` works like `script <sub>` |
| `script <sub> <name>` | manage scripts; subcommands below |
| `lua <code>` | evaluate Lua 5.4 |
| `js <code>` | evaluate JavaScript (QuickJS) |
| `= <expr>` | calculator: `= 5 km in mi`, `= 72 f to c`, `= 100 usd in eur` |
| `status [clear <name\|all>]` | header lines pinned by scripts |

`script` subcommands:

| Subcommand | Does |
| --- | --- |
| `script ls` | list scripts with timers, flags and last result (also plain `script`) |
| `script lib` | the bundled [library](../../scripting/library/) |
| `script install <name>` | install or update a library script |
| `script new <name>[.lua\|.js] [lua\|js] [--cmd]` | create from a template and open the editor |
| `script edit <name>` | open the editor |
| `script run <name>` | run now |
| `script cat <name>` | print the code with line numbers (alias `show`) |
| `script every <name> <15m\|30m\|2h\|1d\|off>` | interval timer |
| `script at <name> <HH:mm[,HH:mm…]\|off>` | daily times |
| `script notify <name> on\|off` | allow `notify()` for this script |
| `script on\|off <name>` | enable / disable (aliases `enable`, `disable`) |
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
| `restart [scripts]` | `reboot` | restart the launcher, or stop running scripts |
| `alias [name = command]` | | list or define aliases |
| `unalias <name>` | | remove an alias |
| `config [ls\|set\|get\|reset] <key> <v>` | `set` | settings (no args opens the settings page) |
| `theme <name>` | | `green` `amber` `mono` `dracula` `solarized` `gruvbox` `gruvbox-material` |
| `echo <text>` | | print text |
| `date` | `time` | current date and time |

## device

| Usage | Aliases | Description |
| --- | --- | --- |
| `settings` | `prefs` | Kolahal settings screen (+ backup import/export) |
| `setup [text]` | | setup checklist page (`text`: print it in the log) |
| `unlocks [days]` | | unlock counts (1–30 days) |
| `notifications [settings]` | | allow script notifications |
| `usage-access` | | grant usage access (unlock count) |
| `battery` | | battery optimization settings |
| `home` | | choose the default launcher |
| `lock` | | lock the screen (needs the accessibility service) |
| `shade` | | pull down the notification shade |
| `accessibility` | | enable the screen-lock service for double-tap |
| `system` | | Android system settings |
