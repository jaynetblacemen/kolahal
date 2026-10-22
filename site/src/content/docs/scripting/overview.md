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
script new feed.js         # JavaScript instead (or: script new feed js)
script new weather --cmd   # a command template that takes arguments
```

Or tap **Add script → New script** in the script manager (`scripts`). Then:

```sh
script run hn              # run it now
script every hn 30m        # and every 30 minutes
```

The fastest start is the [script library](/scripting/library/): `script lib` lists 15 ready-made
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
- **at daily times**: `script at <name> 08:00,18:30` (or `off`),
- **as a command**, if it is marked [`@command`](#command-scripts),
- **on an event**, if it declares [`@on`](#event-triggers).

Intervals have Android's 15-minute minimum. An interval and daily times can be combined. Setting a
timer turns a disabled script back on; `script off <name>` disables it without losing the timers.

`script ls` shows every script with its timers, whether it is a command, notification state and the
last run's result. The script manager (`scripts`, or the **Scripts** tab in Settings) shows a card per
script: its title, how it runs and how its last run went (red if it failed), **Run** for a command,
and its on/off switch. The arrow on a card, or the one at the top for all of them, shows its details:
its header line, next run, usage, events and what it may use. Chips filter the cards (commands,
timers, events, in the header, failed, off, and what they use), and the search icon finds one by name.
Tap a card for **Run**, **Edit**, **Export**, access, notifications, timers and **Delete**.
**Add script** writes a new one, or takes one from the library, from
[the scripts people share](#getting-scripts-from-others) or from a file.

The script's **return value** is printed into the log (tables and objects as JSON). Each run gets a
`── name · 22:40 ──` header and the script's own colour. When a script fails, the error line is
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
-- @desc current weather: weather [city]
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
`boot` and `headset`. During an event run, the script gets an `event` object describing what
happened (at least its type). Check the in-editor docs for the exact fields of each event.

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
-- @desc new front-page stories since the last run
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
| `@tags <word>[, …]` | words to find it by |
| `@requires <x.y.z>` | the oldest Kolahal it works with: an older Kolahal won't import it |

## Sandbox and limits

Scripts run in a separate `:script` process, so a crashing engine can't take down the home screen.

- **60 seconds** per run, and a **64 MB** memory cap. In Lua, `string.rep`
  refuses results over 16 MB, and a run whose memory grows past 256 MB between checks is stopped
  (the script process is restarted).
- At most **500 log lines** per run; past that one line says the rest weren't logged.
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

Your notifications, the clipboard, the list of your apps, your local network and running
commands are only for scripts that ask, with a `@uses` line at the top:

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

Calling one of these without its `@uses` line stops the script with an error saying what to add.
On Android 17 and newer, reaching your local network also needs Android's
local network permission: run `local-network` once, or tap the line a blocked request leaves in the
log. Code you type at the prompt (`lua`, `js`, `=`) may use everything.

### Switching access off

Each script has an **Access** group in its sheet (Scripts → tap the script), with a switch for
everything it can use. Switched off, a script gets nothing where nothing is an honest answer, and
stops where it isn't:

| Switch | Switched off, the script… |
| --- | --- |
| Internet | stops at its first `http` or `rss` call, with a line to tap to allow it again |
| Header line | runs as before, but its `status()` line doesn't show |
| Post notifications | runs as before; `notify()` returns false |
| An `@uses` item | gets nothing: `apps.list()` and `notifications.recent()` return empty lists, `clipboard.get()` returns nil and `clipboard.set()` does nothing. Switched-off `lan` requests stop the script, and `launcher.run` returns false |
| An `@on` event | doesn't run on that event |

Long-press a script's line in the header for **Hide from header**. In the terminal,
`script access <name>` lists the switches, and `script allow <name> <what…>` and
`script deny <name> <what…>` change them (`notify` is the notifications switch). A script can
check with [`allowed(what)`](/scripting/api/#allowedwhat) and do something else.

Scripts from outside (a file, a folder, a backup) arrive **switched off**. Switching one on asks
about what it declares with `@uses`, all on, so you can switch off what you don't want first;
`script on <name>` allows it all and says what that is. If a library update declares something
more, that starts switched off and a line in the log offers to allow it. A backup carries the
switches you turned off, never the ones you turned on.

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

The line gives the key's name, the site it may be sent to (several with commas, `*` for any), and
a few words for you. Set the value in the script's sheet (Scripts → tap the script → **Secrets**),
or with `script secret <name> <NAME>`, which opens a box the keyboard doesn't learn from. Switching
on a script whose keys aren't set leaves a line to tap to set them.

- Values are encrypted with a key kept in Android's Keystore, and stay on this phone: they are left
  out of the [backup file](/guides/backup/), Android's backups and exported scripts. Deleting a
  script forgets them.
- `secrets.get` returns nil until the value is set. Reading a name without its `@secret` line stops
  the script with an error, and code typed at the prompt can't read secrets.
- A value never shows: in log lines, the header, notifications, results and errors it reads `••••`.
- A request carrying a value to a site its `@secret` line doesn't name stops the script. This
  catches mistakes; a script written to leak a key could still find a way, so only give keys to
  scripts you trust.

## Getting scripts from others

**Scripts → Get more scripts** lists the scripts people share in
[kolahal-scripts](https://github.com/mtwabpin/kolahal-scripts) on GitHub. Each one is checked and
read before it is added. Tap one to see what it does, what it uses and its code. **Install** asks
about what it uses first (everything on, to switch off what you don't want), then switches it on
with the timer it suggests.

When a shared script you haven't edited gets a newer version, the Scripts screen says
"update available" and its sheet has an **Update** button. Anything new it asks for starts switched
off. A copy you edited is only replaced when you say so.

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
