-- charge-reminder.lua — reacts to the charger instead of a timer. No network.
-- @on charger, unplug
-- @desc charging session summary; warns when unplugged below 30%
--
-- `event` is the trigger payload: event.name ("charger" / "unplug"), event.level (battery %),
-- event.plug ("ac", "usb", "wireless"). Run by hand (event is nil) it shows the last session.

local LOW = 30

local function pct(n) return n and (math.floor(n) .. "%") or "?%" end

local function minutes(since)
  local m = math.floor(os.difftime(os.time(), since) / 60 + 0.5)
  if m < 60 then return m .. " min" end
  return string.format("%dh %02dm", m // 60, m % 60)
end

if event == nil then
  local last = store.get("last")
  return last or "no charging session yet — plug in the charger"
end

local level = event.level or device.battery().level

if event.name == "charger" then
  store.set("since", tostring(os.time()))
  store.set("from", level and tostring(level) or nil)
  status("charging since " .. os.date("%H:%M") .. " (" .. pct(level) .. ")")
  return
end

if event.name == "unplug" then
  status(nil)
  local since, from = tonumber(store.get("since")), tonumber(store.get("from"))
  store.set("since", nil)
  local line = "unplugged at " .. pct(level)
  if since and level and from then
    line = line .. string.format(" (%+d%% in %s)", math.floor(level - from), minutes(since))
  elseif since then
    line = line .. " (after " .. minutes(since) .. ")"
  end
  store.set("last", os.date("%H:%M ") .. line)
  log(line)
  if level and level < LOW then
    notify("Battery at " .. pct(level), "Unplugged before it was charged — plug in again soon.")
  end
end
