---
title: Editor
description: The built-in script editor, its symbol bar and keyboard shortcuts.
---

`script edit <name>`, **Edit** in the script manager, or tapping a script error opens the editor: a
monospace text area with line numbers and light syntax colouring for Lua and JavaScript, in the
terminal theme.

- The title shows `name.lua`, with `*` while there are unsaved changes.
- **[save]**, **[run]** and **[close]** sit next to it. Run saves first.
- Opened from an error, the editor selects the failing line and highlights it for a few seconds.
- Nothing is lost by leaving: closing, pressing Back and switching away from the app all save.

## Symbol bar

Above the keyboard there's a row of keys that soft keyboards hide:

```
↶  ↷  ⇥  (  )  {  }  [  ]  "  '  =  .  ,  :  ;  #  -  ~  <  >
```

`↶` / `↷` are undo and redo; `⇥` inserts two spaces.

## Keyboard shortcuts

| Key | Action |
| --- | --- |
| `Ctrl+S` | save |
| `Ctrl+R` | save and run |
| `Ctrl+Z` | undo |
| `Ctrl+Shift+Z`, `Ctrl+Y` | redo |
| `Tab` | insert two spaces |
| `Esc`, Back | save and close |
| `Ctrl+F` <span class="k-new">new</span> | find |
| `Ctrl+H` <span class="k-new">new</span> | find and replace |
| `Ctrl+G` <span class="k-new">new</span> | go to line |

## Autocomplete and inline docs

<span class="k-new">new</span> As you type, the editor suggests [API](../api/) names such as `http.get`, `rss.fresh`,
`store.set` and `device.battery`, and shows a short description with the signature of the call
under the cursor. It's a quick reminder; the [API reference](../api/) has the full details and
examples.

## Editing elsewhere

For longer scripts you may prefer a computer: export a script as a file, sync a folder
(`script dir`, `script export`, `script import`) or edit the [backup file](../../guides/backup/)
directly.
