---
title: Editor
description: The built-in script editor, its symbol bar and keyboard shortcuts.
---

`script edit <name>`, **Edit** in a script's sheet in the script manager, or tapping a script error
opens the editor: a monospace text area with line numbers and light syntax colouring for Lua and
JavaScript, in the terminal theme. [Parts of the app](/getting-started/parts/#script-editor) numbers
each part of it.

- The title shows `name.lua`, with `*` while there are unsaved changes.
- **[save]**, **[run]** and **[close]** sit next to it. Run saves first. A long press on
  **[run]** (or `Ctrl+Shift+R`) is **Run with**: words to run it with, its [`args`](/scripting/api/#args-and-argline)
  as when it runs as a command, and for a script with [`@on`](/scripting/overview/#event-triggers)
  or `@at` lines, an event to run it as, with made-up values (a charger at 64%, a message from
  Alice).
- A message line above the symbol bar says what a button did: **Saved** (or
  **No changes to save**), **Saved · running…** and then the run's result (`Ran in 812ms · 3
  lines below`), how many **[all]** replaced. A failed run stays there, red, as
  `Line 13: attempt to …`; tap it to go to that line. Tap any other message to dismiss it.
- A run's lines show under the code as well as in the log, errors in red, until **✕** or the
  next run.
- Saving says what it changed besides the code: a new or changed `@every` or `@at` line sets the
  script's timers (`Saved · runs every 1h`), and one taken out takes back what it had set.
- Saving also points out what looks wrong, red, with its line (tap it to go there): a name the API
  doesn't have (`http.gte`, with `http.get?`), a call that needs a `@uses` line the script lacks
  (`clipboard.get`), and `@` lines Kolahal doesn't know or can't read (`@evrey`, `@at 25:00`,
  `@on unlok`). The script is saved either way.
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
| `Ctrl+Shift+R` | run with args or as an event |
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
