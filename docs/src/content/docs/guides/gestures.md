---
title: Gestures
description: Double-tap to lock, swipe down for the shade, swipe up for apps, and binding gestures to any command.
---

Three gestures are configurable, in **Settings → Gestures** or with `config set`:

| Gesture | Key | Default |
| --- | --- | --- |
| Double-tap empty log space | `gesture.doubletap` | `lock` |
| Swipe down on the header | `gesture.swipedown` | `shade` |
| Swipe up on the prompt | `gesture.swipeup` | `apps` |

Each value is one of:

- `lock`: lock the screen,
- `shade`: pull down the notification shade,
- `none`: do nothing,
- **any command**, run as if you typed it.

```sh
config set gesture.swipeup apps -l
config set gesture.doubletap feed read
config set gesture.swipedown none
```

`lock` and `shade` are also commands you can type, alias or use from a script command.

## Locking needs an accessibility service

Android only lets an app lock the screen through an accessibility service. Kolahal ships a tiny one,
**Kolahal screen lock**, that does nothing else. It is declared with `canRetrieveWindowContent="false"`
and scoped to a package that doesn't exist, so it receives no accessibility events and can't read
the screen. All it does is call the system "lock screen" action.

Enable it with `accessibility` or **setup → Double-tap to lock**. Until then, `lock` prints a
tappable line that takes you to the setting.

## Other touch gestures

These are fixed:

- **Pinch** the log to change the text size.
- **Tap** empty space to show the keyboard.
- **Drag sideways** across the key bar to move the cursor.
