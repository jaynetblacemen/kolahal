---
title: Backup & moving devices
description: Back up everything to one JSON file — settings, apps, scripts, todos, notes, reminders, feeds and the log — or sync scripts with a folder.
---

There are four ways to keep your setup or move it between phones.

## Backup file

**Settings → Backup → Export everything** writes one file, `kolahal-backup.json`, through
the system file picker. It contains:

- settings you changed from the default,
- [themes you made](/guides/themes-and-settings/#your-own-themes),
- the font you imported with `font import`,
- aliases,
- hidden apps, custom labels and pinned favourites,
- every script with its code, timers, notification flag and enabled state,
- **everything else** under `data`: todos, notes, reminders, feed subscriptions
  with their items and read state, each script's `store` values, the whole log and ↑ history,
  app launch history (frecency), unlock history, notification and trigger settings.

It's plain JSON, so you can edit it on a computer. **Import a backup** loads it on any phone.

```json
{ "kolahal": 2,
  "config":  { "theme.name": "gruvbox-material", "log.spacing": "16" },
  "aliases": { "m": "open maps" },
  "apps":    { "hidden": [], "labels": {}, "pinned": [] },
  "scripts": [ { "name": "hn", "lang": "lua", "code": "...", "every": 30,
                 "at": ["08:00"], "notify": true, "enabled": true } ],
  "data":    { "prefs": { … }, "tables": { "todos": [ … ], "feeds": [ … ], "logs": [ … ] },
               "scriptStores": { "hn": { "seen": "…" } } } }
```

On import:

- The `data` section **replaces** what is on the phone (todos, notes, reminders, feeds, the log…),
  then the readable sections above are applied on top, so your hand edits to them win. Script
  stores follow their script by name. Reminders, feeds and timers are rescheduled, and Kolahal
  restarts to load it all. Older backups without `data` import as before.
- Unknown or invalid settings are skipped and listed.
- Aliases that would shadow a built-in command are skipped.
- **New scripts arrive switched off**, so you can review them before they run on a timer. Scripts
  that already exist (same name and language) keep their `enabled` flag only if their code is
  unchanged: new code arrives switched off too. Scripts named like a built-in
  command are skipped.
- Restored log lines keep their text but can't run commands or app shortcuts
  when tapped, and keep only web, map and store links.
- The result names the settings worth a look: a custom search address and
  gesture commands.
- A script whose language differs from an existing script of the same name is skipped.
- Intervals under 15 minutes are raised to 15 (Android's minimum).

Hidden apps and labels are keyed by component and profile, so they carry over between phones only
where those match.

Not included, because they can't move between phones: the folder picked with `script dir` (a
storage permission you grant again) and home-screen widgets' live ids (widgets are listed so
they can be added again). Notification and call lines, lines with someone's number, the `call` and
`sms` lines you type, a private space's lines and its apps' pins, labels, hidden flags and launch
history, responses scripts have cached, the copy of recent notifications kept for scripts, and what
this phone has seen of its calls and apps stay on the phone (a restore keeps the new phone's, so it
doesn't log every app as installed). The file can still contain private things
(notes, the rest of the log), so keep it somewhere safe.

## Automatic backups

The backup file can write itself, every day or every week, to a folder you pick: **Settings →
Backup → Back up on its own**, or

```sh
backup every daily     # or weekly, or off
backup                 # one now
backup dir             # pick the folder
```

Each one is `kolahal-backup-2026-10-27.json`, the date it was written; one written later the same
day replaces that day's. The newest 7 are kept and older ones deleted (`config set backup.keep 30`
keeps more); other files in the folder are left alone. Turning backups on without a folder asks for
one, and `backup` with none picks one and then writes the backup. **Backup folder** in Settings
shows where they go and when the last one was written. A backup that fails (the folder was
deleted, say) leaves a line in the log to tap and pick the folder again.

Pick a folder that is copied off the phone (one your cloud drive's app syncs, or a memory card) to
have a backup after losing the phone.

## Moving to a new phone

Android's own transfer from your old phone to your new one (over a cable or Wi-Fi, on Android 12
and newer) carries your settings and everything else: scripts, todos, notes, reminders, feeds and
the log. Script secrets don't work on the new phone (only the old phone's Keystore can read them):
set them again. Android's cloud backup carries only settings; for the rest, use the backup file.

## Single script files

In the script manager (`scripts`), **Add script → Import a file** picks one or more files from
the file manager. The file name becomes the script name, and importing the same name again updates
it. Imported scripts arrive switched off; turn one on with its switch or `script on <name>` after a
look at it. **Export** in a script's sheet (tap the script) saves it as a file.

## Folder sync

```sh
script dir       # pick a folder (any Storage Access Framework location)
script export    # write every script plus kolahal.json (timers and flags) there
script import    # read them back: new scripts are created, changed ones updated
```

`kolahal.json` sets the timers and flags it lists, over any you changed since the export. A changed
script it doesn't list keeps yours, unless its `@every` or `@at` line changed.

This is handy for keeping scripts in a synced folder or a git repository on your computer. An
export deletes only the files of scripts you deleted on this phone since its last export, never
ones another phone put there. Scripts from a backup or the folder arrive switched off, as from any
file.
