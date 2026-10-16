# Kolahal

A keyboard-first terminal launcher for Android (`io.github.rscipher001.launcher`), with first-class
work-profile support and scripts in **Lua 5.4** or **JavaScript (QuickJS)**.

## Features

- **Terminal home screen.** Type a command or any part of an app name. Output is a persistent log.
  Equal matches are ranked by how often and how recently you launch each app (frecency).
- **Profiles.** Apps from personal, work (managed profile), Android 15+ private space and clone
  profiles are listed together and tagged `[P]` / `[W]` / `[S]` / `[C]`, each in its own colour.
  `work on|off` pauses or resumes the work profile (`private on|off` does the same for private
  space). The header shows profile state. Long-press an app installed in both profiles to open it
  in the other one.
- **Touch-friendly CLI.** A configurable key bar provides `ESC TAB ↑ ↓ ← →` and symbols, and a
  suggestion strip shows tappable apps, commands, script names, config keys and values. Tap any
  earlier command to recall it.
- **Actionable log.** Every line stores context (url, package + profile, script, command). Tapping
  a line runs the obvious action; long-press opens a menu: launch, app info, uninstall, launch in
  the other profile, open/share/copy link, run/edit/disable script, copy, delete.
- **Header** with clock, date, unlock count, battery, network, next alarm and profile state. Each
  item can be turned off (`config`).
- **Unlock counter.** Uses UsageStats `KEYGUARD_HIDDEN` events when Usage Access is granted, with
  an `ACTION_USER_PRESENT` receiver as fallback. `unlocks 7` shows a week.
- **Scripts.**
  - Run on an interval (`script every hn 30m`) and/or at daily times (`script at hn 08:00,18:30`).
  - Scripts marked `@command` become terminal commands with arguments (`weather pune`).
  - Optional notifications per script.
  - Export and import scripts to a folder you pick.
- **Customisation.** Five themes, font size, key bar, header items, aliases, hidden and renamed apps.
- **Gestures.** Pinch the log to resize text, swipe down on the header for notifications, tap
  empty space for the keyboard.
- **Hardware keyboard.**
  - Prompt: `↑/↓` history, `Tab` complete, `Esc` clear, `Enter` run, `Ctrl+L` clear screen,
    `Ctrl+U` kill line, `Ctrl+A/E` move to start/end.
  - Editor: `Ctrl+S` save, `Ctrl+R` run, `Esc`/Back save and close.

## Commands

```
help                              setup                         clear        history
apps [work|personal|private|hidden|-a]                          open <app> [-w|-p|-s|-c]
info <app>      uninstall <app>   hide <app>   unhide <app>     rename <app> = <name>
work [on|off|status]              private [on|off|status]       unlocks [days]
alias [name = command]            unalias <name>
config [ls|get|set|reset] <key> [value]                         theme <green|amber|mono|dracula|solarized>
script ls | new <name>[.lua|.js] [--cmd] | edit | run | cat | rm | on | off
       every <name> <15m|2h|1d|off> | at <name> <HH:mm,…|off> | notify <name> on|off
       dir | export | import
lua <code>      js <code>         rss <url>     search <query>    url <address>
notifications   usage-access      battery       home              settings     date     echo
status [clear <name|all>]         = <lua expression>   (calculator)
```

Anything that isn't a command (or alias, or script command) is matched against app names.

## Scripts

`script new hn` creates `hn.lua` from an RSS template. Use `script new feed.js` for JavaScript and
`--cmd` for a command template. Both engines expose the same API:

| Call | Result |
|---|---|
| `log(text, ctx?)` | Appends a line. `ctx.url` makes it a tappable link; any other keys are kept as context. |
| `notify(title, body, ctx?)` | Posts a notification if `script notify <name> on`. `ctx.url` opens on tap. |
| `http.get(url, headers?)` | `{ status, ok, body, headers }` |
| `http.request{ url, method, body, contentType, headers }` | Same as `http.get`. A table body is sent as JSON. |
| `rss.fetch(url)` / `rss.parse(xml)` | `{ title, link, items = [{ title, link, id, date, summary }] }` (RSS 2.0, RDF, Atom) |
| `rss.fresh(url)` | Like `fetch`, but only items not returned before. Items are marked seen only if the run succeeds. |
| `store.get(k)` / `store.set(k, v)` | Persistent per-script strings. |
| `status(text)` | Pins one line for this script into the header (tap it to rerun the script); `status(nil)` clears. |
| `json.encode` / `json.decode` | Lua only (JS has `JSON`). |
| `args`, `argline` | Arguments when run as a command. |

In JS the network calls are `async` (`await http.get(...)`, `Promise.all` works). Lua calls block
(scripts never run on the UI thread). A script's return value is printed.

Comment directives:

```lua
-- @command                     -- runnable from the prompt by its name
-- @desc weather for a city     -- shown in `help`
```

```lua
-- weather.lua  (script new weather --cmd)
-- @command
local city = argline ~= "" and argline or "London"
return (http.get("https://wttr.in/" .. city .. "?format=3").body:gsub("%s+$", ""))
```

**Folder sync.** `script dir` picks a folder (Storage Access Framework). `script export` writes
`<name>.lua|js` plus `kolahal.json` (timers, daily times, notify, enabled). `script import` reads
them back, so you can edit scripts on a computer, keep them in git, or move them between phones.

## Architecture

```
MainActivity (HOME)  ── TerminalViewModel ── CommandProcessor
                                │                    │
         AppRepository (LauncherApps, all profiles) + AppPrefs (hidden, labels, frecency, aliases)
         UnlockCounter (UsageStats + USER_PRESENT)    DeviceStatus (battery, network, alarm)
         Config (typed settings)    LogRepository / ScriptRepository / KvStore (SQLite, WAL)
                                │
         ScriptScheduler ──► WorkManager ──► ScriptWorker  [process :script]
                                              ├─ LuaEngine (LuaJava, Lua 5.4 native)
                                              └─ JsEngine  (quickjs-kt, QuickJS)
                                                    └─ HostApi (http, rss, log, notify, store)
```

- Scripts run in a separate `:script` process (`RemoteCoroutineWorker`), so a native engine crash
  can't take down the home screen. Results come back through the shared SQLite database plus a
  package-scoped broadcast.
- **Lua sandbox:**
  - Fresh state per run. `java`, `io`, `package`/`require`, `debug`, `dofile`, `loadfile` and
    process-level `os` functions are removed. Only text chunks can be loaded.
  - An instruction-count hook, also installed on coroutines, enforces the 60 s timeout and a 64 MB
    memory cap. `pcall`, `xpcall` and `coroutine.resume` can't swallow either one.
  - If a C function outlives the deadline, a watchdog kills the `:script` process.
- **JS sandbox:** QuickJS has no I/O of its own. Only the bindings above exist, with a 64 MB
  memory limit and an evaluation timeout.
- **Scheduling:**
  - Intervals use periodic work, which has Android's 15-minute minimum and requires network.
  - Each daily time is a 24 h periodic job anchored to the next occurrence, re-anchored whenever
    the launcher starts.
- UI-process work goes through one serial IO dispatcher with an exception handler, so a failing
  action is logged instead of crashing home.

## Build

```
./gradlew :app:assembleDebug              # debug APK (package suffix .debug)
./gradlew :app:testDebugUnitTest          # JVM tests
./gradlew :app:connectedDebugAndroidTest  # engines, sandbox, timeouts, :script process, app profiles
```

AGP 9.1, Gradle 9.3.1, Kotlin 2.3.20, compileSdk 37, targetSdk 36, minSdk 28, Jetpack Compose.
After installing, run `setup`. It checks the default launcher, Usage Access, notifications and
battery optimisation, and each unchecked line fixes itself when tapped.
