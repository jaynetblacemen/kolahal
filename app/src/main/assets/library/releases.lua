-- releases.lua — watch GitHub repositories for new releases (Atom feeds, no API key).
-- @command
-- @desc GitHub release watcher: releases [add|rm owner/repo | list]
-- @usage [add <owner/repo>|rm <owner/repo>|list]
-- @title GitHub Releases
-- @every 3h
-- @notify
--
-- Usage:
--   releases                    check every watched repo now
--   releases add neovim/neovim  start watching a repo
--   releases rm neovim/neovim   stop watching a repo
--   releases list               show watched repos
--
-- On a timer it checks every repo and notifies about each new release.
-- The first check of a repo only records what exists, so you are not
-- flooded with old releases.

local DEFAULT_REPOS = { "neovim/neovim", "termux/termux-app" }

-- ---------------------------------------------------------------- helpers

local function loadRepos()
  local raw = store.get("repos")
  if raw == nil then return DEFAULT_REPOS end
  local list = {}
  for repo in raw:gmatch("%S+") do list[#list + 1] = repo end
  return list
end

local function saveRepos(list)
  store.set("repos", table.concat(list, "\n"))
end

local function indexOf(list, value)
  for i, v in ipairs(list) do
    if v:lower() == value:lower() then return i end
  end
  return nil
end

-- ---------------------------------------------------------------- commands

local cmd, arg = (argline or ""):match("^%s*(%S*)%s*(.-)%s*$")
local repos = loadRepos()

if cmd == "list" then
  if #repos == 0 then return "no repos watched; try: releases add owner/repo" end
  return table.concat(repos, "\n")
end

if cmd == "add" or cmd == "rm" then
  if not arg:match("^[%w%.%-_]+/[%w%.%-_]+$") then
    return "usage: releases " .. cmd .. " owner/repo"
  end
  local i = indexOf(repos, arg)
  if cmd == "add" then
    if i then return arg .. " is already watched" end
    repos[#repos + 1] = arg
    saveRepos(repos)
    return "watching " .. arg .. " (" .. #repos .. " repos)"
  else
    if not i then return arg .. " is not watched" end
    table.remove(repos, i)
    saveRepos(repos)
    return "stopped watching " .. arg
  end
end

if cmd ~= "" then
  return "usage: releases [add|rm owner/repo | list]"
end

-- ---------------------------------------------------------------- check

if #repos == 0 then
  log("releases: no repos watched; try: releases add owner/repo")
  return
end

local total = 0
for _, repo in ipairs(repos) do
  local url = "https://github.com/" .. repo .. "/releases.atom"
  local ok, feed = pcall(rss.fresh, url)
  if not ok then
    log(repo .. ": fetch failed (" .. tostring(feed) .. ")")
  else
    local items = feed.items or {}
    local firstKey = "init:" .. repo
    if store.get(firstKey) == nil then
      -- First check: show the latest release, but don't notify.
      store.set(firstKey, "1")
      if #items > 0 then
        log(repo, "latest: " .. (items[1].title or "?"), { url = items[1].link })
      end
    else
      for i, item in ipairs(items) do
        if i > 3 then break end -- a repo rarely has more than a few releases between runs
        local title = item.title or "new release"
        log(repo, title, { url = item.link })
        notify(repo, title, { url = item.link })
        total = total + 1
      end
    end
  end
end

if total > 0 then
  status("Releases: " .. total .. " new")
else
  status(nil)
end
