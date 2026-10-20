-- weather.lua — current weather in one line from wttr.in (no API key).
-- @command
-- @desc current weather: weather [city] | weather --default <city>
-- @usage [<city>|--default <city>]
--
-- Usage:
--   weather                 weather for the saved default city (or your IP location)
--   weather Paris           weather for Paris
--   weather --default Pune  save Pune as the default city
--
-- On a timer (no arguments) it pins the result as a status line.

local function urlencode(s)
  return (s:gsub("[^%w%-_%.~]", function(c)
    return string.format("%%%02X", string.byte(c))
  end))
end

local function trim(s)
  return (s:gsub("^%s+", ""):gsub("%s+$", ""))
end

local line = trim(argline or "")

-- Save a default city.
local saved = line:match("^%-%-default%s+(.+)$")
if saved then
  store.set("city", trim(saved))
  return "default city set to " .. trim(saved)
end

local city = line ~= "" and line or (store.get("city") or "")
-- An empty path makes wttr.in guess the location from your IP address.
local url = "https://wttr.in/" .. urlencode(city) .. "?format=3"

local ok, r = pcall(http.get, url, { ["Accept-Language"] = "en" })
if not ok then
  return "weather: network error (" .. tostring(r) .. ")"
end
if not r.ok then
  return "weather: HTTP " .. tostring(r.status)
end

local text = trim(r.body or "")
if text == "" or text:find("Unknown location", 1, true) then
  return "weather: unknown location '" .. city .. "'"
end

-- Only background runs (no arguments) update the pinned status line.
if line == "" then status(text) end
return text
