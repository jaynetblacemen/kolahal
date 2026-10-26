---
title: Scripting overview
description: Lua 5.4 and JavaScript scripts in Kolahal, timers, notifications, status lines, command scripts and the sandbox.
---

Scripts are small programs that live inside the launcher. They can fetch things from the web,
print lines into the log, post notifications, pin a status line in the header, or become new
terminal commands. Write them in **Lua 5.4** or **JavaScript** (QuickJS). Both languages get the
same [API](/scripting/api/).

## Your first script

```sh
script new hn              # hn.lua from an RSS template, opens the editor
script new news.js         # JavaScript instead (or: script new news js)
script new weather --cmd   # a command template that takes arguments
```

Or tap **Add script → New script** in the script manager (`scripts`). Then:

```sh
script run hn              # run it now
script every hn 30m        # and every 30 minutes
```

The fastest start is the [script library](/scripting/library/): `script lib` lists 16 ready-made
scripts and `script install weather` installs one with sensible timers.

## Lua or JavaScript?

|  | Lua 5.4 | JavaScript (QuickJS) |
| --- | --- | --- |
| Network calls | block (fine, scripts never run on the UI thread) | `async`: `await http.get(…)`; `Promise.all` works |
| JSON | `json.encode` / `json.decode` | built-in `JSON` |
| Print | `log(…)` or `print(…)` | `log(…)`, `console.log`, `console.error` |
| Arguments | `args` (table), `argline` (string) | `args` (array), `argline` (string) |
| Top level | `return value` prints it | `return value` prints it; top-level `await` works |

Pick whichever you like; the bundled library has both.

## Running scripts

A script runs:

- **on demand**: `script run <name>`, the **Run** button, or `Ctrl+R` in the editor,
- **on an interval**: `script every <name> 30m` (`15m`, `2h`, `1d`, a number of minutes, or `off`),
- **at daily times**: `script at <name> 08:00,18:30` (or `off`), or in the script's sheet: type them in
  **Daily at**, or tap its clock to pick one,
- **as a command**, if it is marked [`@command`](#command-scripts),
- **on an event**, if it declares [`@on`](#event-triggers).

Intervals have Android's 15-minute minimum. An interval and daily times can be combined. Setting a
timer turns a disabled script back on; `script off <name>` disables it without losing the timers.

Daily times are alarms. With **Alarms & reminders** allowed for Kolahal (Android's setting; `remind
exact` asks for it), a script runs on the minute, even while the phone sleeps, and in the
foreground for its few seconds; otherwise it runs within the hour. A run at a daily time knows
which one it was ([`event.time`](/scripting/api/#event)), and it waits for a network only if the
script makes requests and there is none.

`script ls` shows every script with its timers, whether it is a command, notification state and the
last run's result. The script manager (`scripts`, or the **Scripts** tab in Settings) shows a card per
script: its title, how it runs and how its last run went (red if it failed), **Run** for a command,
and its on/off switch; an on script's card is tinted in the theme's colour, an off one's fades. The
arrow on a card, or the one at the top for all of them, shows its details: its header line, next
run, usage, and a pill for everything it may do (internet, its header line, notifications, what it
declares, its events): filled in the theme's colour when on, faded when off, and a tap switches it.
A library script you edited (its summary says "edited") gets **Reset to the library's** there too.
Chips filter the cards (commands, timers, events, in the header, failed, off, needs a key, differs
from the library, and what they use), and the search icon finds one by name.
Tap a card for **Run**, **Edit**, **Export**, access, notifications, timers and **Delete**.
**Add script** writes a new one, or takes one from the library, from
[the scripts people share](#getting-scripts-from-others) or from a file.

The script's **return value** is printed into the log (tables and objects as JSON). Each line it
logs ends with the script's name. When a script fails, the error line is
tappable and opens the editor at the failing line.

### One-liners

```sh
lua return 6 * 7
js [1, 2, 3].map(x => x * 2)
= 2^10
```

`lua` and `js` evaluate a snippet and print its value. The Lua REPL tries `return <code>` first, so
expressions work without `return`.

## Notifications

`notify(title, body, ctx)` only posts when notifications are **on for that script**:

```sh
script notify hn on
```

With notifications off, `notify` does nothing visible, but the script's log lines still show the
content. If Android blocks Kolahal's notifications, a "tap to allow" line appears (once a day). See
[Notifications](/guides/notifications/#notifications-from-scripts-and-feeds).

## Status lines

`status(text)` pins one line into the header for this script, like `weather ▸ Pune: ☀️ +27°C`.
`status(nil)` (or `null` in JS) clears it. Only the first line is used, up to 80 characters.

```sh
status                # list status lines
status clear weather  # remove one (or: status clear all)
```

Tapping a status line runs its script again. `header.status off` hides them all.

## Command scripts

Add `@command` in a comment and the script becomes a terminal command named after the script.
`@desc` gives it a one-line description in `help` and in suggestions.
`@usage` lists its arguments, written like the usage lines in `help`: `[a|b]` is a
choice, `<city>` a value you type. The words in it are suggested as you type (`xkcd` offers
`xkcd random`), and `help` shows the line.

```lua
-- weather.lua
-- @command
-- @desc Current weather: weather [city]
-- @usage [<city>]
local city = argline ~= "" and argline or "London"
return (http.get("https://wttr.in/" .. city .. "?format=3").body:gsub("%s+$", ""))
```

```sh
weather paris
```

In JavaScript use `// @command` and `// @desc …`. Arguments arrive in `args` and `argline`. A
command script run from the prompt may also call [`launcher.run`](/scripting/api/#launcherruncommand) (with `@uses commands`). When
the same script runs on a timer, it gets no arguments, which is how most library scripts tell the
two cases apart.

A command that takes no arguments runs as soon as you have typed enough of its name, like the built-in
ones, when `launch.instant` is `all`. That's a script with an empty `@usage` line, or one that never
reads `args` or `argline`. A script that reads them but has no `@usage` line always waits for Enter.

## Event triggers

A script can also run when something happens on the device, by declaring one or more `@on`
directives:

```lua
-- @on unlock
-- @on charger
```

The supported events are `unlock`, `charger`, `unplug`, `wifi`, `offline`, `notification`,
`boot` and `headset`. During an event run, the script gets an `event` object: `event.name` is the
event, and some events add [details](/scripting/api/#event).

```lua
-- @on charger
-- @on unplug
local b = device.battery()
status((b.charging and "charging " or "on battery ") .. tostring(b.level) .. "%")
```

## Describing a script

A few more comment lines describe a script, so the file is complete on its own: the Scripts screen
shows them, and the [library](/scripting/library/) scripts carry them too.

```lua
-- @title Hacker News
-- @desc New front-page stories since the last run
-- @author Your Name
-- @version 1.2
-- @every 1h
-- @notify
-- @tags news, tech
```

| Line | What it does |
| --- | --- |
| `@title <text>` | its name for people, shown above its file name |
| `@desc <text>` | one line about it, in `help`, suggestions and the Scripts screen |
| `@author <name>`, `@version <x.y>` | shown in its sheet in the Scripts screen |
| `@every <15m\|1h\|1d>`, `@at <HH:mm>[, …]` | the timer it suggests, set when it is added from a file, a folder or the library |
| `@notify` | it suggests posting notifications, switched on when it is added |
| `@tags <word>[, …]` | words to find it by in Get more scripts |
| `@requires <x.y.z>` | the oldest Kolahal it works with: Kolahal won't add it from a file, a folder, a backup or Get more scripts until it is that new |

## Sandbox and limits

Scripts run in a separate `:script` process, so a crashing engine can't take down the home screen.

- **60 seconds** per run, and a **64 MB** memory cap. In Lua, `string.rep`
  refuses results over 16 MB, and a run whose memory grows past 256 MB between checks is stopped
  (the script process is restarted).
- At most **500 log lines** and **256K characters** per run, error lines and each line's link and
  other details included; past either, one line says the rest weren't logged.
- At most **10 commands** per run with `launcher.run`. A script started by another script's
  `launcher.run` can't send any, so scripts can't start each other without end.
- **Lua**: a fresh state per run. `java`, `io`, `package`, `require`, `debug`, `dofile`, `loadfile`
  and process-level `os` functions are removed (`os.time`, `os.date`, `os.clock`, `os.difftime`
  remain), and only text chunks can be loaded. The timeout and memory cap are enforced by an
  instruction-count hook that also covers coroutines, and `pcall`, `xpcall` and `coroutine.resume`
  can't swallow them. A watchdog kills the script process if a C function outlives the deadline.
- **JavaScript**: QuickJS with only the bindings in the [API](/scripting/api/), a 64 MB memory limit and an
  evaluation timeout. There is no file, process or network access besides the API.
- `sleep(ms)` is capped at 5000 ms per call.
- `restart scripts` stops the script process if something is stuck. Scheduled scripts start it again.

## What a script may use

Your notifications, the clipboard, the list of your apps, your local network, running
commands, Do Not Disturb and the work profile are only for scripts that ask, with a `@uses` line at
the top:

```lua
-- @uses notifications, clipboard
```

| `@uses` | allows |
| --- | --- |
| `notifications` | [`notifications.recent`](/scripting/api/#notificationsrecentn) and the `@on notification` trigger |
| `clipboard` | [`clipboard.get` and `clipboard.set`](/scripting/api/#clipboardget--clipboardsettext) |
| `apps` | [`apps.list`](/scripting/api/#appslist) |
| `lan` | `http` requests to this phone or your local network (192.168.…, router pages, a home server) |
| `commands` | [`launcher.run`](/scripting/api/#launcherruncommand): terminal commands, and other scripts by their names, when you start it from the prompt |
| `dnd` | [`device.dnd` and `device.setDnd`](/scripting/api/#devicednd--devicesetdndmode): seeing and setting Do Not Disturb |
| `work` | [`device.work` and `device.setWork`](/scripting/api/#devicework--devicesetworkon): seeing, pausing and resuming the work profile |

Calling one of these without its `@uses` line stops the script with an error saying what to add.
On Android 17 and newer, reaching your local network also needs Android's
local network permission: run `local-network` once, or tap the line a blocked request leaves in the
log. Code you type at the prompt (`lua`, `js`, `=`) may use everything.

### Switching access off

Each script's sheet (Scripts → tap the script) has an **Access** group, with a switch for
everything it uses, and an **Events** group, with one for each event of its `@on` lines; the pills
in its card's details switch the same things, in the same two groups. What it uses is read from its
code: **Internet** when it uses `http` or `rss`, puts links or pictures on its lines (`url`,
`image`), or runs commands (`launcher.run`, as a search reaches a site); **Header line** when it calls `status`; **Post notifications** when it calls `notify`;
and what its `@uses` line names. A script gets only those: one that never mentions `http` can't go
online. Switched off, a script gets nothing where nothing is an honest answer, and
stops where it isn't:

| Switch | Switched off, the script… |
| --- | --- |
| Internet | stops at its first `http` or `rss` call, with a line to tap to allow it again; its lines carry no links or pictures |
| Header line | runs as before, but its `status()` line doesn't show |
| Post notifications | runs as before; `notify()` returns false |
| An `@uses` item | gets nothing: `apps.list()` and `notifications.recent()` return empty lists, `clipboard.get()` returns nil and `clipboard.set()` does nothing. Switched-off `lan` requests stop the script, and `launcher.run` returns false |
| An `@on` event | doesn't run on that event |

Long-press a script's line in the header for **Hide from header**. In the terminal,
`script access <name>` lists the switches, and `script allow <name> <what…>` and
`script deny <name> <what…>` change them (`notify` is the notifications switch). A script can
check with [`allowed(what)`](/scripting/api/#allowedwhat) and do something else.

Scripts from outside (a file, a folder, a backup) arrive **switched off**, and so does a script
whose code one of them replaces: what it was allowed has to be allowed again. Switching one on in
the Scripts screen asks about what it declares with `@uses`, all on, so you can switch off what you
don't want first; `script on <name>` allows it all and says what that is. Turning a script on
another way (giving it a timer, or an import of one that was on) allows nothing it asks for: a line
in the log offers to. If an update from the library or Get more scripts declares something more,
or runs on a new event, that starts switched off and a line in the log offers to allow it.
Restoring a backup brings back only the switches you turned off, never the ones you turned on.

Links a script puts on a line (`url`, `image`, or a notification's link) must be web links:
anything else (`tel:`, `upi:`, an app's own link) is dropped. A line's `copy` must be what the line
shows, part of it, or its link.

## API keys and other secrets

A script that needs an API key or a token doesn't keep it in its code. It names the key with an
`@secret` line, and you set the value on the phone:

```lua
-- @secret OWM_KEY api.openweathermap.org Your OpenWeather API key
local key = secrets.get("OWM_KEY")
if not key then return "set OWM_KEY in the script's sheet" end
local r = http.get("https://api.openweathermap.org/data/2.5/weather?q=Pune&appid=" .. key)
```

The line gives the key's name, the site it may be sent to (and its subdomains; several with commas,
`*` for any), and a few words for you. Set the value in the script's sheet (Scripts → tap the script → **Secrets**),
or with `script secret <name> <NAME>`, which opens a box the keyboard doesn't learn from. Switching
on a script whose keys aren't set leaves a line to tap to set them.

- Values are encrypted with a key kept in Android's Keystore, and stay on this phone: they are left
  out of the [backup file](/guides/backup/), Android's backups and exported scripts. Deleting a
  script forgets them.
- `secrets.get` returns a stand-in for the value, never the value, or nil until the value is set.
  The script puts it where the request needs the key, and the value goes in its place only in a
  request to a site the `@secret` line names, over https. A script that needs to work on the key
  itself (encode it, sign with it) can't. Reading a name without its `@secret` line stops the script
  with an error, and code typed at the prompt can't read secrets.
- A value is at least 4 characters, and never shows: in log lines, the header, notifications,
  results and errors it reads `••••`.
- A value goes only to the sites its `@secret` line named when you set it. A request carrying it
  anywhere else stops the script, redirects included. The clipboard, the script's store and
  `launcher.run` refuse a stand-in; anywhere else (a line, a notification's link) it stays just that,
  a stand-in, shown as `••••`. If an update or another file names
  other sites for the key, it isn't used until you set it again; a line in the log says so. New
  code without the key's `@secret` line forgets its value.
- A script never holds the value, so it can't send it anywhere else. It can still use the key at its
  site the way the site allows, so give keys to scripts you trust.

## Getting scripts from others

**Scripts → Add script → Get more scripts** lists the scripts people share in
[kolahal-scripts](https://github.com/mtwabpin/kolahal-scripts) on GitHub. Each one is checked and
read before it is added. Tap one to see what it does, what it uses and its code. **Install** asks
about what it uses first (everything on, to switch off what you don't want), then switches it on
with the timer it suggests.

When Get more scripts (or `script get`) finds a newer version of a shared script you haven't
edited, the Scripts screen says "update available" and its sheet has an **Update to …** button.
Anything new it asks for starts switched off. A copy you edited is only replaced when you say so.

In the terminal, `script get` lists them and `script get <name>` installs or updates one. The list
is kept for an hour; `--refresh` reads it again, and `--replace` replaces a copy you edited. To
share one of yours, see the repository's [contributing guide](https://github.com/mtwabpin/kolahal-scripts/blob/main/CONTRIBUTING.md).

The scripts named `template-…` are starting points, with comments that explain each part: a
command, a timer, an API key, an event, and one in JavaScript. Install one and
[rename it](#renaming-a-script) to make it yours.

## Renaming a script

Tap the ✎ beside a script's name in its sheet (Scripts → tap the script), or run
`script mv <name> <new-name>`. A command script then runs by its new name. Its timers, access
switches, stored data, keys and header line stay with it. A script from the library or Get more
scripts becomes yours: it no longer gets their updates, and the original can be installed again.
Lines already in the log keep the old name.

## Moving scripts

Scripts are part of the [backup file](/guides/backup/), and can be imported and exported as
single `.lua` / `.js` files or synced with a folder (`script dir`, `script export`, `script import`).
