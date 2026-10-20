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

Or tap **New script** in the script manager (`scripts`). Then:

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
- **on an event** <span class="k-new">new</span>, if it declares [`@on`](#event-triggers).

Intervals have Android's 15-minute minimum. An interval and daily times can be combined. Setting a
timer turns a disabled script back on; `script off <name>` disables it without losing the timers.

`script ls` shows every script with its timers, whether it is a command, notification state and the
last run's result. The script manager shows the same, with buttons.

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
<span class="k-new">new</span> `@usage` lists its arguments, written like the usage lines in `help`: `[a|b]` is a
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
command script run from the prompt may also call [`launcher.run`](/scripting/api/#launcherruncommand). When
the same script runs on a timer, it gets no arguments, which is how most library scripts tell the
two cases apart.

A command that takes no arguments runs as soon as you have typed enough of its name, like the built-in
ones, when `launch.instant` is `all`. That's a script with an empty `@usage` line, or one that never
reads `args` or `argline`. A script that reads them but has no `@usage` line always waits for Enter.

## Event triggers

<span class="k-new">new</span> A script can also run when something happens on the device, by declaring one or more `@on`
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

## Sandbox and limits

Scripts run in a separate `:script` process, so a crashing engine can't take down the home screen.

- **60 seconds** per run, and a **64 MB** memory cap.
- **Lua**: a fresh state per run. `java`, `io`, `package`, `require`, `debug`, `dofile`, `loadfile`
  and process-level `os` functions are removed (`os.time`, `os.date`, `os.clock`, `os.difftime`
  remain), and only text chunks can be loaded. The timeout and memory cap are enforced by an
  instruction-count hook that also covers coroutines, and `pcall`, `xpcall` and `coroutine.resume`
  can't swallow them. A watchdog kills the script process if a C function outlives the deadline.
- **JavaScript**: QuickJS with only the bindings in the [API](/scripting/api/), a 64 MB memory limit and an
  evaluation timeout. There is no file, process or network access besides the API.
- `sleep(ms)` is capped at 5000 ms per call.
- `restart scripts` stops the script process if something is stuck. Scheduled scripts start it again.

## Moving scripts

Scripts are part of the [backup file](/guides/backup/), and can be imported and exported as
single `.lua` / `.js` files or synced with a folder (`script dir`, `script export`, `script import`).
