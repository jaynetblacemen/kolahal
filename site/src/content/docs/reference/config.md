---
title: Configuration keys
description: Every Kolahal setting with its type, default and meaning.
---

Change settings on the [settings screen](/guides/themes-and-settings/#settings-screen) or with
`config set <key> <value>`. `config ls` lists every key with its current value; `*` marks keys
changed from the default, and `config reset <key|all>` restores defaults.

Types: **bool** takes `on`/`off` (or `true`/`false`, `yes`/`no`, `1`/`0`); **number** takes an
integer in the range shown; **choice** takes one of the listed values; **text** takes free text.

:::note[Renamed in 0.3.0]
Some keys got clearer names in 0.3.0. Your settings moved over by themselves, the old names still
work in `config` (which tells you the new name), and older backups import as before.

| Old name | New name |
| --- | --- |
| `theme` | `theme.name` |
| `gui.colors` | gone in 0.3.1: Settings always use the theme's colours |
| `widgets.home`, `widgets.height` | gone: widgets live on the widgets page (`widgets.page`) |
| `font` | `font.family` |
| `statusbar` | `screen.bars` |
| `suggestions` | `suggestions.max` |
| `keybar`, `keybar2` | `keybar.row1`, `keybar.row2` |
| `launch.instant.min` | `launch.instant-letters` |
| `feeds.log.lines`, `feeds.backlog.read`, `feeds.read.shown` | `feeds.log-lines`, `feeds.backlog-read`, `feeds.read-shown` |
| `gesture.doubletap`, `gesture.swipedown`, `gesture.swipeup` | `gesture.double-tap`, `gesture.swipe-down`, `gesture.swipe-up` |
| `gesture.swipeup.on` | gone: `gesture.swipe-up` is `none` while swiping up is off |
| `log.lineheight`, `log.keepdays` | `log.line-height`, `log.keep-days` |
| `keyboard.onhome` | `keyboard.on-home` |

The `theme` and `font` commands are unchanged.
:::

## Header

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `header.show` | bool | `on` | show the header; off: the log takes the whole screen (open settings with `settings`) |
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
| `theme.name` | choice: `green` `amber` `mono` `dracula` `solarized` `gruvbox` `gruvbox-material`, or [one you made](/guides/themes-and-settings/#your-own-themes) | `green` | colour theme (also `theme <name>`); its dark or [light version](/guides/themes-and-settings/#light-and-dark) follows the phone's dark mode |
| `theme.always-dark` | bool | `off` | always the theme's dark version, whatever the phone's mode |
| `theme.paper` | number 0–100 | `0` | an old book's page behind home in light versions: 0 is the flat colour, higher is older and more textured; not in Settings yet |
| `screen.bars` | choice: `show` `hide` `immersive` | `immersive` | full screen: hide the status bar, or both system bars (immersive); swipe from the edge to peek |
| `font.family` | choice: `system` `jetbrains-mono` `fira-code` `ibm-plex-mono` `source-code-pro` `custom` | `jetbrains-mono` | terminal font (also `font <name>`); `custom` is a .ttf/.otf imported with `font import` |
| `font.sans` | choice: `off` `prompt` `all` | `off` | the phone's own font instead of the terminal font: for the prompt, or for all of the terminal (also `font sans <mode>`); lists that line up columns and the script editor stay in the terminal font |
| `font.size` | number 10–24 | `14` | terminal font size (sp); pinching the log changes it too |

## Log

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `log.time` | choice: `off` `commands` `groups` | `groups` | right-aligned times on log lines: `groups` on each group's first line and when the minute changes, `commands` on the lines you type |
| `log.dim` | bool | `on` | dim output older than the latest command |
| `log.spacing` | number 0–40 | `5` | gap (dp) above each output group: a command and its output, or a background batch (feeds, scripts) |
| `log.gutter` | bool | `on` | thin colour bar at the left of each log line: green for commands, script colour for background output, red for errors |
| `log.line-height` | number 10–56 | `22` | minimum height of a log line, in sp: 22 is 22 dp at the phone's usual text size, and it grows with the text when the phone's text size is larger. With `log.taller-taps`, tappable lines get +10 and two-column rows +14. Up to 3 dp above and below a line, less as it nears one line of text (19 at text size 14), none at it; lower still, lines are as short as the text allows. Script status lines in the header use it too |
| `log.taller-taps` | bool | `off` | lines you can tap a little taller than the others, easier to hit; off makes every line the same height |
| `chips.height` | number 20–64 | `48` | height (dp) of suggestion and app-grid chips |
| `log.keep-days` | number 1–365 | `60` | delete log lines older than this many days (checked at least twice a day) |
| `log.keep-lines` | number 500–50000 | `5000` | delete the oldest log lines past this many. Typed commands are counted apart, so ↑ history stays. Only with `config set`: the settings screen doesn't show it |
| `log.fresh` | number 0–240 | `10` | start home with a clean screen after this many minutes away (0: never); earlier output is one tap away. `fresh` does it now |
| `feeds.log` | bool | `on` | post new feed items in the log |
| `feeds.log-lines` | 1–50 | `10` | new items per feed and refresh shown in the log; the rest is one "+N more" line |
| `feeds.read-shown` | bool | `off` | items posted in the log count as read (otherwise only opened or listed ones do) |
| `feeds.backlog-read` | bool | `off` | when you subscribe, the posts already in the feed count as read |
| `feeds.interval` | 15–1440 | `60` | refresh interval (minutes) for newly added feeds |
| `feeds.notify` | bool | `off` | newly added feeds notify about new items |
| `feeds.wifi` | bool | `off` | scheduled refreshes only on Wi-Fi (unmetered); `feed fetch` always runs |

## Launching and suggestions

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `launch.top` | bool | `on` | Enter launches the best match when it starts with what you typed |
| `launch.single` | bool | `on` | launch automatically when only one app matches |
| `launch.instant` | choice: `off` `apps` `all` | `all` | run without Enter once the input can only mean one thing: apps, or apps and commands, including whole lines like `torch on` (sensitive commands always need Enter) |
| `launch.instant-letters` | number 1–6 | `2` | letters to type before an instant launch |
| `suggestions.idle` | bool | `off` | show most-used apps when the prompt is empty |
| `suggestions.icons` | bool | `on` | show app icons next to app suggestions, in the list and on chips |
| `suggestions.wrap` | bool | `off` | show a command's or setting's whole description in the suggestion list, over more lines (off: one line, cut short) |
| `suggestions.auto` | bool | `on` | show suggestions only while typing (keyboard up or text in the prompt) |
| `suggestions.style` | choice: `list` `chips` | `list` | while typing: a list above the prompt (best match at the bottom, nearest the thumb, with command descriptions) or a strip of chips |
| `suggestions.rows` | number 1–2 | `2` | rows of suggestion chips while typing (`suggestions.style chips`) |
| `selector.rail` | bool | `off` | the app selector on an edge of the log while the keyboard is down: slide along it to a letter, then onto the list to pick an app, let go on one to launch |
| `selector.filter` | bool | `off` | the list shows only the letter's apps, instead of the whole list at that letter |
| `selector.side` | choice: `right` `left` | `right` | the edge you slide on; the list of apps opens on the other side |
| `selector.position` | choice: `bottom` `middle` `top` | `bottom` | where on the edge you slide: keep it away from where you swipe back |
| `selector.handle` | bool | `on` | show the slim A–Z handle on the edge; off hides it while the selector keeps working |
| `selector.letters` | bool | `off` | show the letters along the edge at rest, each where its stretch is, so you can see where to touch; only letters that have apps |
| `selector.height` | number 200–900 | `350` | length (dp) of the part of the edge you slide on: shorter means less thumb travel; the list uses the whole height. With gesture navigation a touch starts it only in the middle 200 dp, where the handle is |
| `selector.actions` | choice: `far-side` `off` | `off` | while you pick, an app's actions: `far-side` opens them when you lift on the list's far side; `off` leaves them to the open list (long-press) |
| `selector.icons` | bool | `off` | show each app's icon in the app selector's list |
| `selector.system-font` | bool | `off` | the app selector in the phone's own font instead of the terminal font: its list, the letters on the edge and the one beside your thumb |
| `suggestions.max` | number 0–30 | `30` | max suggestions shown while typing |
| `search.engine` | choice: `duckduckgo` `google` `bing` `brave` `startpage` `kagi` `custom` | `duckduckgo` | web search engine for `search` / `g` |
| `search.url` | text | `https://duckduckgo.com/?q=%s` | custom search URL (`%s` = query), used when `search.engine` = `custom` |

## Gestures

Each takes a command, `lock`, `shade`, `quick` (quick actions) or `none`. See [Gestures](/guides/gestures/).

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `gesture.double-tap` | text | `lock` (`none` in the Play build, which has no screen lock) | double-tap empty log space |
| `gesture.swipe-down` | text | `shade` | swipe down on the header |
| `gesture.swipe-up` | text | `none` | swipe up on the prompt (`none`, the default, turns it off: it fires by accident easily; `apps` opens the app grid) |

## Widgets

See [Widgets](/guides/widgets/).

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `widgets.page` | choice: `left` `right` `off` | `left` | a widgets page beside the terminal: swipe sideways on home to reach it |

## Keyboard and key bar

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `keybar.show` | bool | `off` | show the touch key bar |
| `keybar.auto` | bool | `on` | show the key bar only while the on-screen keyboard is up for the prompt (off: always) |
| `keybar.height` | number 20–64 | `40` | key bar row height (dp); smaller leaves more room for the log |
| `prompt.height` | number 20–72 | `40` | prompt line height (dp), with the keyboard up or down; it never gets shorter than its text |
| `keybar.row1` | text | `ESC,/,-,HOME,UP,END,BKSP` | touch keys, comma separated: `ESC` `TAB` `UP` `DOWN` `LEFT` `RIGHT` `HOME` `END` `BKSP` `ENTER` `SPACE` `KBD` or literal text |
| `keybar.row2` | text | `TAB,\|,.,LEFT,DOWN,RIGHT,ENTER` | second key bar row (same syntax; empty for one row) |
| `keyboard.on-home` | bool | `off` | open the soft keyboard whenever home is shown |
| `keyboard.suggestions` | bool | `off` | prompt keyboard suggestions. Off uses a password-type field: no autocorrect, but Latin-only on some keyboards |
