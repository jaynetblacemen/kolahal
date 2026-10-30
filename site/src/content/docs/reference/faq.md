---
title: FAQ
description: Answers to common questions, such as why the wallpaper flashes on the way home.
---

## Why does my wallpaper flash when I go home?

With gesture navigation, swiping home from an app can show your phone's wallpaper for an instant
before Kolahal appears, even with Kolahal's wallpaper settings off.

Kolahal isn't drawing it. Android hands those gestures to your phone's own launcher, whichever home
app you use. While you swipe, that launcher's screen shows the wallpaper behind the app, and it opens
Kolahal only at the end. A home app can't take those gestures over.

There are two ways to stop the flash:

| | What to do | What it costs |
| --- | --- | --- |
| **Three-button navigation** | In your phone's settings, search for "navigation" and choose the buttons | You lose the swipe gestures |
| **`wallpaper match`** | Type it in Kolahal and tap to confirm | Your home screen's wallpaper becomes a plain colour |

### What `wallpaper match` does

It sets your home screen's wallpaper to the theme's colour, so there's nothing to see on the way
home.

- **It asks first.** Android doesn't let an app read the wallpaper it replaces, so Kolahal can't bring
  the old one back. Keep a copy of the picture if you want it later.
- **It follows the theme.** Pick another theme, or let dark mode switch, and the wallpaper changes
  with it.
- **Your own wallpaper stays yours.** Set another wallpaper and Kolahal leaves it alone from then on,
  and the flash comes back with it. Run `wallpaper match` again to return to the plain colour.
- **The lock screen keeps its own wallpaper.**
- **The wallpaper behind Kolahal becomes that colour too.** `log.wallpaper` and `widgets.wallpaper`
  have only the plain colour to show.

`wallpaper` on its own says whether the wallpaper is currently Kolahal's or yours.
