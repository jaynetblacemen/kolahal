-- todo.lua — a minimal todo list kept in the script's store. No network.
-- @command
-- @desc todo list: todo | todo add <text> | todo done N | todo clear
-- @usage [add <text>|done <n>|clear]
--
-- Usage:
--   todo                 list open items
--   todo add buy milk    add an item
--   todo done 2          complete (remove) item #2
--   todo clear           remove everything
--
-- The header shows "N todos" while the list is not empty.

local function load()
  local raw = store.get("items")
  if not raw then return {} end
  local ok, list = pcall(json.decode, raw)
  if ok and type(list) == "table" then return list end
  return {}
end

local function save(list)
  store.set("items", json.encode(list))
  if #list == 0 then
    status(nil)
  else
    status(#list .. (#list == 1 and " todo" or " todos") .. " · " .. list[1])
  end
end

local function show(list)
  if #list == 0 then
    log("nothing to do")
    return
  end
  for i, text in ipairs(list) do
    log(i .. ". " .. tostring(text), { copy = tostring(text) })
  end
end

local cmd, rest = (argline or ""):match("^%s*(%S*)%s*(.-)%s*$")
local list = load()

if cmd == "add" then
  if rest == "" then return "usage: todo add <text>" end
  list[#list + 1] = rest
  save(list)
  return "added #" .. #list .. ": " .. rest
elseif cmd == "done" or cmd == "rm" then
  local n = tonumber(rest)
  if not n or not list[n] then return "usage: todo done N (see: todo)" end
  local removed = table.remove(list, n)
  save(list)
  return "done: " .. tostring(removed)
elseif cmd == "clear" then
  save({})
  return "cleared"
elseif cmd == "" or cmd == "list" then
  show(list)
  save(list) -- refreshes the status line
else
  return "usage: todo | todo add <text> | todo done N | todo clear"
end
