-- feeds.lua — a tiny RSS/Atom reader for several feeds at once.
-- @desc Several RSS feeds: new items since the last run, prefixed by feed name
-- @title RSS Feeds
-- @every 1h
-- @notify
--
-- Edit the FEEDS list below. Each run logs only items you have not seen,
-- at most PER_FEED per feed, and pins "Feeds: N new" in the header.

local FEEDS = {
  { name = "lobsters", url = "https://lobste.rs/rss" },
  { name = "lwn",      url = "https://lwn.net/headlines/rss" },
  { name = "ars",      url = "https://feeds.arstechnica.com/arstechnica/index" },
}

local PER_FEED = 5

local total, first = 0, nil

for _, f in ipairs(FEEDS) do
  local ok, feed = pcall(rss.fresh, f.url)
  if not ok then
    log("[" .. f.name .. "] fetch failed (" .. tostring(feed) .. ")")
  else
    local items = feed.items or {}
    for i, item in ipairs(items) do
      if i > PER_FEED then
        log("[" .. f.name .. "] … " .. (#items - PER_FEED) .. " more")
        break
      end
      log("[" .. f.name .. "] " .. (item.title or "(untitled)"), { url = item.link })
    end
    if #items > 0 and first == nil then
      first = { feed = f.name, item = items[1] }
    end
    total = total + #items
  end
end

if total == 0 then
  status(nil)
  return
end

status("Feeds: " .. total .. " new")
notify("Feeds: " .. total .. " new", "[" .. first.feed .. "] " .. (first.item.title or ""),
  { url = first.item.link })
