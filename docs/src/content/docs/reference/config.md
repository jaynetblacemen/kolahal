---
title: Configuration keys
description: Every Kolahal setting with its type, default and meaning.
---

Change settings on the [settings screen](../../guides/themes-and-settings/#settings-screen) or with
`config set <key> <value>`. `config ls` lists every key with its current value; `*` marks keys
changed from the default, and `config reset <key|all>` restores defaults.

Types: **bool** takes `on`/`off` (or `true`/`false`, `yes`/`no`, `1`/`0`); **number** takes an
integer in the range shown; **choice** takes one of the listed values; **text** takes free text.

## Header

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `header.device` | bool | `off` | show `kolahal@device` in the header |
| `header.date` | bool | `on` | show the date next to the clock |
| `header.unlocks` | bool | `on` | show today's unlock count |
| `header.battery` | bool | `on` | show battery level |
| `header.network` | bool | `on` | show network type |
| `header.alarm` | bool | `on` | show the next alarm |
| `header.profiles` | bool | `on` | show work/private profile state |
| `header.status` | bool | `on` | show status lines pinned by scripts |
| `header.media` | bool | `on` | show what's playing under the clock: tap plays / pauses, long-press skips (needs notification access; see [Media](../../guides/media-and-controls/#in-the-header)) |
| `header.todo` | bool | `on` | show the open todo count (tap runs `todo`) |
| `header.autohide` | bool | `on` | shrink the header to one line while scrolling or after a command; Home or a tap brings it back |
| `clock.24h` | bool | `on` | use 24-hour time in the header and log |

## Appearance

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `theme` | choice: `green` `amber` `mono` `dracula` `solarized` `gruvbox` `gruvbox-material` | `green` | colour theme (also `theme <name>`) |
| `gui.colors` | choice: `theme` `system` | `theme` | settings screens follow the terminal theme or the system (Material You) colours |
| `font.size` | number 10–24 | `14` | terminal font size (sp); pinching the log changes it too |

## Log

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `log.time` | choice: `off` `commands` `all` | `commands` | right-aligned times on log lines |
| `log.dim` | bool | `on` | dim output older than the latest command |
| `log.spacing` | number 0–40 | `12` | gap (dp) above each command and its output |
| `log.keepdays` | number 1–365 | `60` | delete log lines older than this many days |

## Launching and suggestions

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `launch.top` | bool | `on` | Enter launches the best match when it starts with what you typed |
| `launch.single` | bool | `on` | launch automatically when only one app matches |
| `launch.instant` | choice: `off` `apps` `all` | `apps` | run without Enter once the input can only mean one thing: apps, or apps and commands (sensitive commands always need Enter) |
| `launch.instant.min` | number 1–6 | `2` | letters to type before an instant launch |
| `suggestions.idle` | bool | `on` | show most-used apps when the prompt is empty |
| `suggestions` | number 0–30 | `12` | max suggestions shown while typing |
| `search.engine` | choice: `duckduckgo` `google` `bing` `brave` `startpage` `kagi` `custom` | `duckduckgo` | web search engine for `search` / `g` |
| `search.url` | text | `https://duckduckgo.com/?q=%s` | custom search URL (`%s` = query), used when `search.engine` = `custom` |

## Gestures

Each takes a command, `lock`, `shade` or `none`. See [Gestures](../../guides/gestures/).

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `gesture.doubletap` | text | `lock` | double-tap empty log space |
| `gesture.swipedown` | text | `shade` | swipe down on the header |
| `gesture.swipeup` | text | `apps` | swipe up on the prompt |

## Widgets

See [Widgets](../../guides/widgets/).

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `widgets.home` | bool | `on` | show widgets placed on home under the header (they hide with it while the log is in use) |
| `widgets.height` | number 60–480 | `180` | max height (dp) of the home widget area; it scrolls when widgets need more |

## Keyboard and key bar

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `keybar.show` | bool | `on` | show the touch key bar |
| `keybar` | text | `ESC,/,-,HOME,UP,END,BKSP` | touch keys, comma separated: `ESC` `TAB` `UP` `DOWN` `LEFT` `RIGHT` `HOME` `END` `BKSP` `ENTER` `SPACE` `KBD` or literal text |
| `keybar2` | text | `TAB,\|,.,LEFT,DOWN,RIGHT,ENTER` | second key bar row (same syntax; empty for one row) |
| `keyboard.onhome` | bool | `off` | open the soft keyboard whenever home is shown |
| `keyboard.suggestions` | bool | `off` | prompt keyboard suggestions. Off uses a password-type field: no autocorrect, but Latin-only on some keyboards |
