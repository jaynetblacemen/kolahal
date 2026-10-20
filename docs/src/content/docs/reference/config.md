---
title: Configuration keys
description: Every Kolahal setting with its type, default and meaning.
---

Change settings on the [settings screen](/guides/themes-and-settings/#settings-screen) or with
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
| `header.network` | bool | `off` | show network type |
| `header.alarm` | bool | `on` | show the next alarm |
| `header.profiles` | bool | `on` | show work/private profile state |
| `header.status` | bool | `on` | show status lines pinned by scripts |
| `header.media` | bool | `on` | show what's playing under the clock: tap plays / pauses, long-press skips (needs notification access; see [Media](/guides/media-and-controls/#in-the-header)) |
| `header.todo` | bool | `on` | show the open todo count (tap runs `todo`) |
| `header.autohide` | bool | `on` | scrolling back through older output hides the header; scrolling toward the newest shows it as one line (also after a command); Home or a tap brings the full header back |
| `header.icons` | bool | `on` | icons for battery, network, unlocks, alarm and profiles in the header (off: text labels) |
| `clock.24h` | bool | `off` | use 24-hour time in the header and log |

## Appearance

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `theme` | choice: `green` `amber` `mono` `dracula` `solarized` `gruvbox` `gruvbox-material` | `green` | colour theme (also `theme <name>`) |
| `gui.colors` | choice: `theme` `system` | `theme` | settings screens follow the terminal theme or the system (Material You) colours |
| `statusbar` | choice: `show` `hide` `immersive` | `immersive` | full screen: hide the status bar, or both system bars (immersive); swipe from the edge to peek |
| `font` | choice: `system` `jetbrains-mono` `fira-code` `ibm-plex-mono` `source-code-pro` `custom` | `jetbrains-mono` | terminal font (also `font <name>`); `custom` is a .ttf/.otf imported with `font import` |
| `font.size` | number 10–24 | `14` | terminal font size (sp); pinching the log changes it too |

## Log

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `log.time` | choice: `off` `commands` `all` | `all` | right-aligned times on log lines |
| `log.dim` | bool | `on` | dim output older than the latest command |
| `log.spacing` | number 0–40 | `10` | gap (dp) above each output group: a command and its output, or a background batch (feeds, scripts) |
| `log.gutter` | bool | `on` | thin colour bar at the left of each log line: green for commands, script colour for background output, red for errors |
| `log.lineheight` | number 10–56 | `25` | minimum height (dp) of a log line; tappable lines get +10, two-column rows +14. Script status lines in the header use it too |
| `chips.height` | number 20–64 | `48` | height (dp) of suggestion and app-grid chips |
| `log.keepdays` | number 1–365 | `60` | delete log lines older than this many days |
| `log.fresh` | number 0–240 | `10` | start home with a clean screen after this many minutes away (0: never); earlier output is one tap away |
| `feeds.log` | bool | `on` | post new feed items in the log |
| `feeds.log.lines` | 1–50 | `10` | new items per feed and refresh shown in the log; the rest is one "+N more" line |
| `feeds.read.shown` | bool | `off` | items posted in the log count as read (otherwise only opened or listed ones do) |
| `feeds.backlog.read` | bool | `off` | when you subscribe, the posts already in the feed count as read |
| `feeds.interval` | 15–1440 | `60` | refresh interval (minutes) for newly added feeds |
| `feeds.notify` | bool | `off` | newly added feeds notify about new items |
| `feeds.wifi` | bool | `off` | scheduled refreshes only on Wi-Fi (unmetered); `feed fetch` always runs |

## Launching and suggestions

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `launch.top` | bool | `on` | Enter launches the best match when it starts with what you typed |
| `launch.single` | bool | `on` | launch automatically when only one app matches |
| `launch.instant` | choice: `off` `apps` `all` | `all` | run without Enter once the input can only mean one thing: apps, or apps and commands (sensitive commands always need Enter) |
| `launch.instant.min` | number 1–6 | `2` | letters to type before an instant launch |
| `suggestions.idle` | bool | `off` | show most-used apps when the prompt is empty |
| `suggestions.icons` | bool | `off` | show app icons next to app suggestions, in the list and on chips |
| `suggestions.auto` | bool | `on` | show suggestions only while typing (keyboard up or text in the prompt) |
| `suggestions.style` | choice: `list` `chips` | `list` | while typing: a list above the prompt (best match at the bottom, nearest the thumb, with command descriptions) or a strip of chips |
| `suggestions.rows` | number 1–2 | `2` | rows of suggestion chips while typing (`suggestions.style chips`) |
| `selector.rail` | bool | `on` | App selector on the right edge while the keyboard is down: slide along the right edge to glide through all your apps; let go on one to launch, hold for its actions |
| `selector.handle` | bool | `on` | show the slim A–Z handle on the right edge; off hides it while the selector keeps working (it still appears under your finger) |
| `selector.height` | number 200–900 | `350` | height (dp) of the app selector band, low on the right edge: shorter means less thumb travel |
| `suggestions` | number 0–30 | `12` | max suggestions shown while typing |
| `search.engine` | choice: `duckduckgo` `google` `bing` `brave` `startpage` `kagi` `custom` | `duckduckgo` | web search engine for `search` / `g` |
| `search.url` | text | `https://duckduckgo.com/?q=%s` | custom search URL (`%s` = query), used when `search.engine` = `custom` |

## Gestures

Each takes a command, `lock`, `shade` or `none`. See [Gestures](/guides/gestures/).

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `gesture.doubletap` | text | `lock` | double-tap empty log space |
| `gesture.swipedown` | text | `shade` | swipe down on the header |
| `gesture.swipeup.on` | bool | `off` | turn the swipe-up-on-prompt gesture on (off by default: it fires by accident easily) |
| `gesture.swipeup` | text | `apps` | swipe up on the prompt |

## Widgets

See [Widgets](/guides/widgets/).

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `widgets.home` | bool | `off` | show widgets placed on home under the header (they hide with it while the log is in use) |
| `widgets.page` | choice: `left` `right` `off` | `left` | a widgets page beside the terminal: swipe sideways on home to reach it |
| `widgets.height` | number 60–480 | `180` | max height (dp) of the home widget area; it scrolls when widgets need more |

## Keyboard and key bar

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `keybar.show` | bool | `off` | show the touch key bar |
| `keybar.auto` | bool | `on` | show the key bar only while the on-screen keyboard is up for the prompt (off: always) |
| `keybar.height` | number 20–64 | `40` | key bar row height (dp); smaller leaves more room for the log |
| `prompt.height` | number 20–72 | `40` | prompt line height (dp), with the keyboard up or down; it never gets shorter than its text |
| `keybar` | text | `ESC,/,-,HOME,UP,END,BKSP` | touch keys, comma separated: `ESC` `TAB` `UP` `DOWN` `LEFT` `RIGHT` `HOME` `END` `BKSP` `ENTER` `SPACE` `KBD` or literal text |
| `keybar2` | text | `TAB,\|,.,LEFT,DOWN,RIGHT,ENTER` | second key bar row (same syntax; empty for one row) |
| `keyboard.onhome` | bool | `off` | open the soft keyboard whenever home is shown |
| `keyboard.suggestions` | bool | `off` | prompt keyboard suggestions. Off uses a password-type field: no autocorrect, but Latin-only on some keyboards |
