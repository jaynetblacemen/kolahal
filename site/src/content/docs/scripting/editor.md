---
title: Editor
description: The built-in script editor, its symbol bar and keyboard shortcuts.
---

`script edit <name>`, **Edit** in a script's sheet in the script manager, or tapping a script error
opens the editor: a monospace text area with line numbers and light syntax colouring for Lua and
JavaScript, in the terminal theme.

- The title shows `name.lua`, with `*` while there are unsaved changes.
- **[save]**, **[run]** and **[close]** sit next to it. Run saves first.
- A message line above the symbol bar says what a button did: **Saved** (or
  **No changes to save**), **Saved · running…** and then the run's result (`Ran in 812ms · its
  output is in the log`), how many **[all]** replaced. A failed run stays there, red, as
  `Line 13: attempt to …`; tap it to go to that line. Tap any other message to dismiss it.
- Opened from an error, the editor selects the failing line and highlights it for a few seconds.
- Nothing is lost by leaving: closing, pressing Back and switching away from the app all save.
  Closing with unsaved changes logs `Saved name.lua`.

## Symbol bar

Above the keyboard there's a row of keys that soft keyboards hide:

```
↶  ↷  ⇥  (  )  {  }  [  ]  "  '  =  .  ,  :  ;  #  -  ~  <  >  Ln
```

`↶` / `↷` are undo and redo, dimmed when there's nothing to undo or redo; `⇥` inserts two spaces;
`Ln` opens go to line.

## Keyboard shortcuts

| Key | Action |
| --- | --- |
| `Ctrl+S` | save |
| `Ctrl+R` | save and run |
| `Ctrl+Z` | undo |
| `Ctrl+Shift+Z`, `Ctrl+Y` | redo |
| `Tab` | accept the first completion while the completions show; otherwise insert two spaces |
| `Shift+Tab` | insert two spaces |
| `Esc`, Back | save and close |
| `Ctrl+F` | find |
| `Ctrl+H` | find and replace |
| `Ctrl+G` | go to line |

## Autocomplete and inline docs

As you type, the editor suggests [API](/scripting/api/) names such as `http.get`, `rss.fresh`,
`store.set` and `device.battery`, and shows a short description with the signature of the call
under the cursor. It's a quick reminder; the [API reference](/scripting/api/) has the full details and
examples.

**[?]** in the top bar lists the whole API, then every `@` line a script can start with
([`@command`](/scripting/overview/#command-scripts), `@desc`, `@on`, `@uses`, `@every` and the rest, as in
[describing a script](/scripting/overview/#describing-a-script)). Tap an entry to insert it at the
cursor; an `@` line goes in as a whole comment line.

## Editing elsewhere

For longer scripts you may prefer a computer: export a script as a file, sync a folder
(`script dir`, `script export`, `script import`) or edit the [backup file](/guides/backup/)
directly.
