---
title: Backup & moving devices
description: Export settings, aliases, app labels and scripts to one JSON file, or sync scripts with a folder.
---

There are three ways to move your setup between phones.

## Backup file

**Settings → Backup → Export settings & scripts** writes one file, `kolahal-backup.json`, through
the system file picker. It contains:

- settings you changed from the default,
- aliases,
- hidden apps, custom labels and pinned favourites,
- every script with its code, timers, notification flag and enabled state.

It's plain JSON, so you can edit it on a computer. **Import from file** loads it on any phone.

```json
{ "kolahal": 1,
  "config":  { "theme": "gruvbox-material", "log.spacing": "16" },
  "aliases": { "m": "open maps" },
  "apps":    { "hidden": [], "labels": {}, "pinned": [] },
  "scripts": [ { "name": "hn", "lang": "lua", "code": "...", "every": 30,
                 "at": ["08:00"], "notify": true, "enabled": true } ] }
```

On import:

- Unknown or invalid settings are skipped and listed.
- Aliases that would shadow a built-in command are skipped.
- **New scripts arrive switched off**, so you can review them before they run on a timer. Scripts
  that already exist (same name and language) get the new code and their `enabled` flag.
- A script whose language differs from an existing script of the same name is skipped.
- Intervals under 15 minutes are raised to 15 (Android's minimum).

Hidden apps and labels are keyed by component and profile, so they carry over between phones only
where those match.

Feed subscriptions and the log are not part of the backup.

## Single script files

In the script manager (`scripts`), **Import .lua / .js files** picks one or more files from the file
manager. The file name becomes the script name, and importing the same name again updates it.
**Export** on a script saves it as a file.

## Folder sync

```sh
script dir       # pick a folder (any Storage Access Framework location)
script export    # write every script plus kolahal.json (timers and flags) there
script import    # read them back: new scripts are created, changed ones updated
```

This is handy for keeping scripts in a synced folder or a git repository on your computer.
