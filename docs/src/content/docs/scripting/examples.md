---
title: Examples
description: Complete scripts to copy, from a battery logger to an uptime checker and command scripts.
---

Each example is a complete script. Create it with `script new <name>` (add `.js` for JavaScript),
paste the code into the editor and save with `Ctrl+S` or **save**.

## Battery log (Lua, timer)

Records the battery level each run and pins the change since the last run.

```lua
-- battery.lua — run with: script every battery 30m
-- @desc battery level history

local b = device.battery()
if not b.level then return "battery level unknown" end

local history = json.decode(store.get("history") or "[]")
table.insert(history, { t = os.time(), level = b.level, charging = b.charging })
while #history > 48 do table.remove(history, 1) end
store.set("history", json.encode(history))

local prev = history[#history - 1]
local delta = prev and (b.level - prev.level) or 0
status(string.format("%d%%%s (%+d)", b.level, b.charging and " ⚡" or "", delta))

if b.level <= 15 and not b.charging then
  notify("Battery low", b.level .. "% left")
end
```

## Uptime checker (JavaScript, timer, parallel requests)

Checks several sites at once and notifies only when one goes down or comes back.

```js
// uptime.js — script every uptime 15m · script notify uptime on
// @desc site uptime checker

const SITES = ["https://example.com", "https://github.com", "https://lwn.net"];

const results = await Promise.all(
  SITES.map(async (url) => {
    const started = Date.now();
    try {
      const r = await http.request({ url, method: "HEAD" });
      return { url, up: r.ok, info: `${r.status} in ${Date.now() - started}ms` };
    } catch (e) {
      return { url, up: false, info: String(e.message || e) };
    }
  })
);

const before = JSON.parse(store.get("state") || "{}");
const now = {};
for (const { url, up, info } of results) {
  now[url] = up;
  if (before[url] !== undefined && before[url] !== up) {
    notify(up ? "Back up" : "Down", `${url} — ${info}`, { url });
  }
  log(`${up ? "✓" : "✗"} ${url} · ${info}`, { url });
}
store.set("state", JSON.stringify(now));

const down = results.filter((r) => !r.up).length;
status(down ? `${down} site(s) down` : null);
```

## Notes (Lua, command)

A tiny scratchpad: `note buy stamps`, `note` to list, `note rm 2`.

```lua
-- note.lua
-- @command
-- @desc quick notes: note <text> | note | note rm N

local notes = json.decode(store.get("notes") or "[]")
local function save() store.set("notes", json.encode(notes)) end

local n = argline:match("^rm%s+(%d+)$")
if n then
  local removed = table.remove(notes, tonumber(n))
  save()
  return removed and ("removed: " .. removed) or "no note #" .. n
end

if argline ~= "" then
  table.insert(notes, argline)
  save()
  return "saved (" .. #notes .. " notes)"
end

if #notes == 0 then return "no notes yet: note <text>" end
for i, text in ipairs(notes) do log(i .. ". " .. text, { copy = text }) end
```

## GitHub stars (JavaScript, command with caching)

`stars owner/repo` shows a repo's stars. The response is cached for ten minutes, so repeated runs
cost nothing.

```js
// stars.js
// @command
// @desc GitHub stars: stars <owner/repo>

const repo = (args[0] || "rscipher001/kolahal").replace(/^https:\/\/github\.com\//, "");
if (!/^[\w.-]+\/[\w.-]+$/.test(repo)) return "usage: stars owner/repo";

const r = await http.cached(`https://api.github.com/repos/${repo}`, 600);
if (!r.ok) return `stars: HTTP ${r.status}`;

const d = JSON.parse(r.body);
log(`${d.full_name}: ★ ${d.stargazers_count} · ${d.forks_count} forks · ${d.open_issues_count} open issues`,
  { url: d.html_url });
if (d.description) log(d.description);
```

## Morning briefing (Lua, daily time)

Runs at 07:30, then sends one notification with the weather and how many notifications are
waiting.

```lua
-- morning.lua — script at morning 07:30 · script notify morning on
-- @desc morning summary

local parts = {}

local ok, r = pcall(http.get, "https://wttr.in/?format=%C+%t")
if ok and r.ok then table.insert(parts, (r.body:gsub("%s+$", ""))) end

local waiting = #notifications.recent(50)
if waiting > 0 then table.insert(parts, waiting .. " notifications") end

local b = device.battery()
if b.level then table.insert(parts, "battery " .. b.level .. "%") end

local summary = table.concat(parts, " · ")
log(summary)
notify("Good morning", summary)
```

## Work mode (Lua, command driving the launcher)

`launcher.run` only works when the script is started from the prompt, which makes it a good fit
for command scripts.

```lua
-- focus.lua
-- @command
-- @desc focus on|off: work profile and notification logging together

if argline == "off" then
  launcher.run("notifs log on")
  launcher.run("work off")
  return "focus off"
end
launcher.run("work on")
launcher.run("notifs log off")
return "focus on: work profile on, notifications out of the log"
```

## Charging log (Lua, event trigger)

<span class="k-new">new</span> Uses the new [`@on` triggers](/scripting/overview/#event-triggers) to run when the charger is connected or
removed.

```lua
-- charging.lua
-- @on charger
-- @on unplug

local b = device.battery()
local what = b.charging and "plugged in" or "unplugged"
log(os.date("%H:%M") .. " " .. what .. " at " .. tostring(b.level) .. "%")
```
