---
title: Widgets
description: Host Android home-screen widgets on a widgets page beside the terminal.
---

Kolahal can host the same home-screen widgets as any other launcher: clocks, weather, calendar,
music, notes. They live on a **widgets page** beside the terminal, so the terminal keeps all its
room.

## The widgets page

Swipe sideways on home (from the header, empty space or the prompt; a swipe that starts on a log
line copies or deletes that line instead) to reach the widgets page. **+ add** and **edit** are at
the top. Back or Home returns to the terminal. `widgets.page` picks the side (`left`, the default,
`right`) or turns the page `off`.

## Add a widget

Tap **+ add** on the widgets page, open **Settings → Widgets** (or `widgets`) and tap **Add
widget**, or go straight to the picker from the prompt with `widget add`.

The picker lists every installed widget, grouped by app, with its preview and default size
(`4×2` and so on). Tap one to add it:

1. The first time, Android asks whether Kolahal may create widgets. Allow it (tick "always" to skip
   the question next time).
2. If the widget has a setup screen (pick a city, a calendar, a note), it opens next. Backing out
   of it cancels the widget.

## Arrange and resize

In **Settings → Widgets**, long-press a widget, or tap the edit button in the top bar for all of
them, to show its toolbar:

| Button | Does |
| --- | --- |
| ↑ / ↓ | move it up or down the page |
| − / + | shorter or taller, in 48dp steps (never below the size the widget needs) |
| delete | remove it (asks first) |

Widgets are told their real width and height, so they lay out for the space they get.

## Uninstalled apps and updates

When an app is updated, its widgets keep working. When it's uninstalled, its widgets are removed
from the page automatically.

## Backups

A [backup](/guides/backup/) records which widgets you placed, their height and order. Widgets
themselves can't be moved between installs, so after an import they show under **Restored from
backup** in Settings → Widgets; tap **Add again** on each to bind it (and run its setup if it has
one). It keeps its height and place.

## From the terminal

```sh
widgets        # open Settings → Widgets
widgets ls     # list placed widgets (tap a line to open them)
widget add     # open the picker
```

See also the [command reference](/reference/commands/#widgets) and
[configuration keys](/reference/config/#widgets).

## Work profile

Work-profile widgets work like any other, and carry a **work** badge in the picker. While the work
profile is paused (or the private space is locked) they show a dim **paused** placeholder instead,
and come back when you turn work apps on.
