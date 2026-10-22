-- countdown.lua — days until (or since) dates. No network.
-- @command
-- @desc days until a date: countdown YYYY-MM-DD | add YYYY-MM-DD label | rm N
-- @usage [<YYYY-MM-DD>|add <YYYY-MM-DD> <label>|rm <n>]
-- @title Countdowns
-- @at 00:05
--
-- Usage:
--   countdown 2026-12-25               days until a date
--   countdown add 2026-12-25 Christmas save a countdown
--   countdown                          list saved countdowns (nearest first)
--   countdown rm 2                     delete saved countdown #2
--
-- On a timer (e.g. daily) it pins the nearest upcoming countdown as a status line.

local DAY = 86400

-- Parse "YYYY-MM-DD" into a timestamp at local noon (noon avoids DST edge cases).
local function parseDate(s)
  local y, m, d = (s or ""):match("^(%d%d%d%d)-(%d%d?)-(%d%d?)$")
  if not y then return nil end
  y, m, d = tonumber(y), tonumber(m), tonumber(d)
  if m < 1 or m > 12 or d < 1 or d > 31 then return nil end
  local t = os.time({ year = y, month = m, day = d, hour = 12 })
  -- os.time normalises 2026-02-31 to March; reject such dates.
  local back = os.date("*t", t)
  if back.month ~= m or back.day ~= d then return nil end
  return t
end

local function today()
  local now = os.date("*t")
  return os.time({ year = now.year, month = now.month, day = now.day, hour = 12 })
end

local function daysUntil(t)
  return math.floor(os.difftime(t, today()) / DAY + 0.5)
end

local function describe(days, label)
  if days == 0 then return label .. ": today" end
  if days == 1 then return label .. ": tomorrow" end
  if days == -1 then return label .. ": yesterday" end
  if days > 0 then return label .. ": in " .. days .. " days" end
  return label .. ": " .. (-days) .. " days ago"
end

local function load()
  local raw = store.get("items")
  if not raw then return {} end
  local ok, list = pcall(json.decode, raw)
  if ok and type(list) == "table" then return list end
  return {}
end

local function save(list)
  store.set("items", json.encode(list))
end

-- Saved items sorted by date (stable order for "rm N").
local function sorted(list)
  table.sort(list, function(a, b) return a.date < b.date end)
  return list
end

local cmd, rest = (argline or ""):match("^%s*(%S*)%s*(.-)%s*$")

if cmd == "add" then
  local date, label = rest:match("^(%S+)%s*(.*)$")
  if not parseDate(date) then return "usage: countdown add YYYY-MM-DD label" end
  if label == nil or label == "" then label = date end
  local list = load()
  list[#list + 1] = { date = date, label = label }
  save(sorted(list))
  return describe(daysUntil(parseDate(date)), label)
end

if cmd == "rm" then
  local n = tonumber(rest)
  local list = sorted(load())
  if not n or not list[n] then return "usage: countdown rm N (see: countdown)" end
  local removed = table.remove(list, n)
  save(list)
  return "removed " .. removed.label
end

if cmd ~= "" then
  local t = parseDate(cmd)
  if not t then return "usage: countdown YYYY-MM-DD | add YYYY-MM-DD label | rm N" end
  return describe(daysUntil(t), rest ~= "" and rest or cmd)
end

-- No arguments: list saved countdowns and pin the nearest upcoming one.
local list = sorted(load())
if #list == 0 then
  status(nil)
  return "no countdowns saved; try: countdown add 2026-12-25 Christmas"
end

local nearest = nil
for i, item in ipairs(list) do
  local t = parseDate(item.date)
  if t then
    local days = daysUntil(t)
    log(i .. ". " .. describe(days, item.label) .. " (" .. item.date .. ")")
    if days >= 0 and nearest == nil then nearest = describe(days, item.label) end
  end
end
status(nearest)
