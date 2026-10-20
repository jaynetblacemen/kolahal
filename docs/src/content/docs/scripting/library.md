---
title: Script library
description: The 15 ready-made Lua and JavaScript scripts bundled with Kolahal.
---

Kolahal ships 15 scripts you can install with one tap in the script manager's **Library**
section (installed ones move up to your scripts), or from the prompt:

```sh
script lib                 # list them, with ✓ for installed ones
script install weather     # install (or update) one
```

Installing sets the script's suggested timers and notification flag. They are only suggestions:
change them with `script every`, `script at` and `script notify`, or in the script manager. After
an app update, `script lib` shows **(update available)** for scripts you haven't edited and
**(edited)** for ones you have. Installing again updates a copy you haven't edited.
<span class="k-new">new</span> A copy you edited, or your own script with the same name, is only replaced
when you ask: `script install weather --replace`, or **Replace with library version** in its sheet
in the script manager. Export it first to keep your version.

None of the network scripts need an API key.

| Script | Lang | Command | Suggested timer | Notifies |
| --- | --- | --- | --- | --- |
| [hn](#hn) | Lua | | every 1h | yes |
| [feeds](#feeds) | Lua | | every 1h | yes |
| [releases](#releases) | Lua | yes | every 3h | yes |
| [reddit](#reddit) | JS | yes | | |
| [weather](#weather) | Lua | yes | every 1h | |
| [forecast](#forecast) | JS | yes | daily 07:00 | |
| [sun](#sun) | JS | yes | daily 05:00 | |
| [crypto](#crypto) | JS | yes | every 30m | |
| [exchange](#exchange) | JS | yes | | |
| [ip](#ip) | Lua | yes | every 30m | yes |
| [wikipedia](#wikipedia) | JS | yes | | |
| [xkcd](#xkcd) | JS | yes | every 6h | yes |
| [quote](#quote) | JS | yes | daily 08:00 | yes |
| [countdown](#countdown) | Lua | yes | daily 00:05 | |
| [todo](#todo) | Lua | yes | | |

## hn

**Hacker News.** New front-page stories with 50+ points since the last run (via hnrss.org), up
to 10 lines. Notifies about the top new story and pins `HN: N new` in the header. Edit the `FEED`
line to drop the points filter.

## feeds

**RSS feeds.** Reads several feeds (Lobsters, LWN and Ars Technica by default; edit the `FEEDS`
list at the top) and logs new items prefixed by feed name, at most 5 per feed, with a
`Feeds: N new` status line. The built-in [feed reader](/guides/feeds/) does a similar job
without code.

## releases

**GitHub releases.** Watches repositories through their release Atom feeds and notifies about each
new release. The first check of a repo only records what exists, so you aren't flooded.

```sh
releases                     # check now
releases add neovim/neovim
releases rm neovim/neovim
releases list
```

## reddit

**Reddit top posts.** Today's top 5 posts of a subreddit with scores and comment counts, falling
back to the subreddit's RSS feed when Reddit blocks anonymous JSON requests. `reddit` (r/android)
or `reddit linux`.

## weather

**Weather.** Current weather in one line from wttr.in. On a timer it pins the result in the header.

```sh
weather                      # saved default city (or your IP's location)
weather Paris
weather --default Pune       # save a default
```

## forecast

**3-day forecast** from Open-Meteo: conditions, min/max temperature and rain chance. On a timer it
pins today's summary. `forecast`, `forecast Tokyo`, `forecast --default Pune`.

## sun

**Sunrise and sunset**, plus day length, from Open-Meteo, in the location's own time zone.
`sun Lisbon` or `sun 12.97 77.59` saves the location; then just `sun`.

## crypto

**Coin prices** with 24h change from CoinGecko. On a timer it pins a compact line such as
`BTC 67.1k ▲1.2% · ETH 3.1k ▼0.4%`.

```sh
crypto                       # bitcoin and ethereum in USD
crypto solana dogecoin       # CoinGecko ids
crypto bitcoin --vs eur
```

## exchange

**Currency converter** with daily rates from open.er-api.com. On a timer it pins USD rates. The
built-in [`= 100 usd in eur`](/guides/web-and-calculator/#currency) covers simple cases.

```sh
exchange 100 usd inr eur
exchange usd inr             # 1 USD
exchange 50 eur              # in USD
```

## ip

**Public IP** from api.ipify.org; long-press the line to copy it. On a timer it notifies when the
IP changes (handy for noticing a dropped VPN) and pins it as a status line.

## wikipedia

**Wikipedia summary** with the article's picture and a link to the full article: `wikipedia Alan Turing`. It tries the exact
title, then the best search match. With no topic it shows a random article. Change `LANG` at the
top for another language edition.

## xkcd

**xkcd**, the comic itself in the log with its alt text below: `xkcd`, `xkcd 327`, `xkcd random`. On a timer it notifies only when a
new comic is out.

## quote

**Quote of the day** from ZenQuotes; long-press to copy. On a timer it arrives as a notification.

## countdown

**Countdowns.** Days until (or since) dates, with the nearest one pinned in the header. No network.

```sh
countdown 2026-12-25
countdown add 2026-12-25 Christmas
countdown                    # list, nearest first
countdown rm 2
```

## todo

**Todo list** kept in the script's store, with the open count in the header. No network.

```sh
todo add buy milk
todo                         # list
todo done 2
todo clear
```
