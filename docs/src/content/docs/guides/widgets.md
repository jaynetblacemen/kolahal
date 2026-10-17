---
title: Widgets
description: Host Android home-screen widgets on a Widgets page or right under the terminal header.
---

Kolahal can host the same home-screen widgets as any other launcher: clocks, weather, calendar,
music, notes. They live on a **Widgets page**, and the ones you choose also sit on home, directly
under the header, without taking room from the log while you work.

## Add a widget

Open the page with `widgets` (or **Settings → Widgets**) and tap **Add widget**, or go straight to
the picker from the prompt:

```sh
widget add          # the new widget goes on the Widgets page
widget add home     # ... or on home, under the header
```

The picker lists every installed widget, grouped by app, with its preview and default size
(`4×2` and so on). Widgets from the work profile carry a **work** badge. Tap one to add it:

1. The first time, Android asks whether Kolahal may create widgets. Allow it (tick "always" to skip
   the question next time).
2. If the widget has a setup screen (pick a city, a calendar, a note), it opens next. Backing out
   of it cancels the widget.

## Arrange and resize

Long-press a widget, or tap the edit button in the top bar for all of them, to show its toolbar:

| Button | Does |
| --- | --- |
| ↑ / ↓ | move it up or down within its group |
| − / + | shorter or taller, in 48dp steps (never below the size the widget needs) |
| home | show it on home under the header, or take it off home |
| delete | remove it (asks first) |

Widgets are told their real width and height, so they lay out for the space they get.

## Widgets on home

Widgets placed on home show in a strip directly under the header, above the log. The strip:

- is capped at `widgets.height` (180dp by default) and scrolls when your widgets need more,
- folds away together with the header when [header auto-hide](../../getting-started/basics/)
  shrinks it while you scroll or run commands, and comes back with it (Home or a tap on the header),
- shows nothing at all when no widget is placed on home.

Long-press a home widget to jump to the Widgets page and edit it. Turn the strip off with
`config set widgets.home off`; the widgets stay on the Widgets page.

## Work profile

Work-profile widgets work like any other. While the work profile is paused (or the private space is
locked) they show a dim **paused** placeholder instead, and come back when you turn work apps on.

## Uninstalled apps and updates

When an app is updated, its widgets keep working. When it's uninstalled, its widgets are removed
from the page and from home automatically.

## Backups

A [backup](../backup/) records which widgets you placed, their height, order and whether they were
on home. Widgets themselves can't be moved between installs, so after an import they show under
**Restored from backup** on the Widgets page; tap **Add again** on each to bind it (and run its setup
if it has one). It keeps its height and place.

## From the terminal

```sh
widgets        # open the Widgets page
widgets ls     # list placed widgets (tap a line to open the page)
widget add     # open the picker
```

See also the [command reference](../../reference/commands/#widgets) and
[configuration keys](../../reference/config/#widgets).

## Widgets page

Swipe sideways on home (from the header, empty space or the prompt; a swipe that starts on a log
line copies or deletes that line instead) to reach a full widgets page beside the terminal. It shows
the widgets placed on the *Widgets page*; **+ add** and **edit** are at the top. Back or Home returns
to the terminal. `widgets.page` picks the side (`left`, the default, `right`) or turns it `off`.
