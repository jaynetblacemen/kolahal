-- hn.lua — Hacker News front page, only stories you have not seen yet.
-- @desc Hacker News front page: new stories since the last run
--
-- Runs well on a timer (every 30-60 min). Each run logs the new front-page
-- stories (tap a line to open it), notifies about the top new one and pins
-- a short "HN: N new" status line in the header.

local FEED = "https://hnrss.org/frontpage?points=50" -- drop ?points=50 for every story
local MAX_LINES = 10

-- rss.fresh raises an error on HTTP failure; catch it so a flaky network
-- produces one short line instead of a stack trace.
local ok, feed = pcall(rss.fresh, FEED)
if not ok then
  log("hn: could not fetch feed (" .. tostring(feed) .. ")")
  return
end

local items = feed.items or {}
local count = #items

if count == 0 then
  status("HN: nothing new")
  return
end

for i, item in ipairs(items) do
  if i > MAX_LINES then
    log("… and " .. (count - MAX_LINES) .. " more")
    break
  end
  log(item.title or "(untitled)", { url = item.link })
end

notify("Hacker News", items[1].title or "New story", { url = items[1].link })
status("HN: " .. count .. " new")
