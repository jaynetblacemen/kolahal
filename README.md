# Kolahal

A keyboard-first terminal launcher for Android (`io.github.rscipher001.launcher`), with first-class
work-profile support and timer scripts in **Lua 5.4** or **JavaScript (QuickJS)**.

## Features

- **Terminal home screen.** Type a command or any part of an app name. Output is a persistent log.
- **Profiles.** Apps from personal, work (managed profile) and Android 15+ private space are listed
  together and tagged `[P]` / `[W]` / `[S]` in distinct colours. `work on|off` pauses or resumes the
  work profile. The header shows the profile state.
- **Touch-friendly CLI.** An on-screen key bar provides `ESC TAB ↑ ↓ ← →` and common symbols, and a
  suggestion strip shows tappable app and command completions. Tap any previous command to recall it.
- **Actionable log.** Every line stores structured context (url, package + profile, script,
  command). Tapping a line runs the obvious action; long-press opens a menu: launch, app info,
  uninstall, launch in the other profile, open/share/copy link, run/edit/disable script, copy, delete.
- **Unlock counter** in the header (`unlocks [days]` for history). Uses UsageStats `KEYGUARD_HIDDEN`
  events when Usage Access is granted, with an `ACTION_USER_PRESENT` receiver as fallback.
- **Timer scripts** (`script every hn 30m`) via WorkManager, with optional notifications per script.
- **Hardware keyboard.** `↑/↓` history, `Tab` complete, `Esc` clear, `Enter` run, `Ctrl+L` clear
  screen, `Ctrl+U` kill line. In the editor: `Ctrl+S` save, `Ctrl+R` run, `Esc` close.

## Commands

```
help                          apps [work|personal|private]     open <app> [-w|-p|-s]
info <app>                    uninstall <app>                  work|private [on|off|status]
unlocks [days]                history                          clear
script ls|new|edit|run|every|notify|on|off|rm|cat <name>
lua <code>                    js <code>                        rss <url>
search <query>                url <address>                    notifications   home   settings
```

`script new hn` creates `hn.lua`. Use `script new feed.js` (or `script new feed js`) for JavaScript.

## Script API (identical in both languages)

| Call | Result |
|---|---|
| `log(text, ctx?)` | Appends a line. `ctx` keys such as `url` drive tap and long-press actions. |
| `notify(title, body, ctx?)` | Posts a notification if `script notify <name> on`. `ctx.url` opens on tap. |
| `http.get(url, headers?)` | `{ status, ok, body, headers }` |
| `http.request{ url, method, body, contentType, headers }` | Same as `http.get`. |
| `rss.fetch(url)` / `rss.parse(xml)` | `{ title, link, items = [{ title, link, id, date, summary }] }` |
| `rss.fresh(url)` | Like `fetch`, but only items not seen on earlier runs. |
| `store.get(k)` / `store.set(k, v)` | Persistent per-script strings. |
| `json.encode` / `json.decode` | Lua only (JS has `JSON`). |

In JS the network calls are `async`: `await http.get(...)` or `await rss.fresh(...)`, and
`Promise.all` works. Lua calls block (scripts already run off the UI thread).

```lua
-- hn.lua
local feed = rss.fresh("https://hnrss.org/frontpage")
for i, item in ipairs(feed.items) do
  if i > 5 then break end
  log(item.title, { url = item.link })
end
```

## Architecture

```
MainActivity (HOME)  ── TerminalViewModel ── CommandProcessor
                                │                    │
         AppRepository (LauncherApps, all profiles)  │
         UnlockCounter (UsageStats + USER_PRESENT)   │
         LogRepository / ScriptRepository / KvStore (SQLite, WAL)
                                │
         ScriptScheduler ──► WorkManager ──► ScriptWorker  [process :script]
                                              ├─ LuaEngine (LuaJava, Lua 5.4 native)
                                              └─ JsEngine  (quickjs-kt, QuickJS)
                                                    └─ HostApi (http, rss, log, notify, store)
```

- Scripts run in a separate `:script` process (`RemoteCoroutineWorker`), so a native crash in an
  engine can't take down the home screen. Results come back through the shared SQLite database
  plus a package-scoped broadcast.
- **Lua sandbox:** fresh state per run; `java`, `io`, `package`/`require`, `debug`, `dofile`,
  `loadfile` and process-level `os` functions are removed, and only text chunks can be loaded. An
  instruction-count hook, also installed on coroutines, enforces the 60 s timeout.
- **JS sandbox:** QuickJS has no I/O of its own. Only the bindings above exist, with a 64 MB memory
  limit and an evaluation timeout.
- Periodic work has Android's 15-minute minimum and requires network.

## Build

```
./gradlew :app:assembleDebug            # debug APK (package suffix .debug)
./gradlew :app:testDebugUnitTest        # JVM tests
./gradlew :app:connectedDebugAndroidTest  # engine, sandbox, timeout and :script-process tests on a device
```

AGP 9.1, Gradle 9.3.1, Kotlin 2.3.20, compileSdk 37, minSdk 28, Jetpack Compose.
After installing, run `home` to make Kolahal the default launcher (required for `work on|off`).
