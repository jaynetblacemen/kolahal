---
title: Gestures
description: Double-tap to lock, swipe down for the shade, an optional swipe up, and binding gestures to any command.
---

Three gestures are configurable, in **Settings → Gestures** or with `config set`:

| Gesture | Key | Default |
| --- | --- | --- |
| Double-tap empty log space | `gesture.double-tap` | `lock` (`none` in the Play build, which has no screen lock) |
| Swipe down on the header | `gesture.swipe-down` | `shade` |
| Swipe up on the prompt | `gesture.swipe-up` | `none` (off: it fires by accident easily) |

Each value is one of:

- `lock`: lock the screen,
- `shade`: pull down the notification shade,
- `quick`: open the quick actions (as tapping `$` on the prompt does),
- `none`: do nothing,
- **any command**, run as if you typed it.

```sh
config set gesture.swipe-up apps -l   # swipe up on the prompt for the app list
config set gesture.double-tap feed read
config set gesture.swipe-down none
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

- **Pinch** the log to change the text size. When you let go, a toast says the new size.
- **Tap** empty space to show the keyboard.
- **Drag sideways** across the key bar to move the cursor.
