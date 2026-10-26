---
title: Security
description: What keeps your phone and data safe, how to check a download, and how to report a problem privately.
---

Kolahal is young, and its code is still under review. Treat it as beta software: keep a
[backup](/guides/backup/), give scripts only what they need, and report anything that looks wrong.

## What keeps you safe

- **Your data stays on the phone.** There is no account and no server; Kolahal reaches the network
  only for what you ask of it. [Privacy & permissions](/reference/privacy/) lists every request and
  every permission.
- **Permissions are yours to give.** Everything beyond the basics is optional and asked for when
  you first use it. The Play build leaves out the screen lock, notification access and the call log
  altogether.
- **Scripts run apart.** They run in a process of their own, a minute at most, with their memory
  capped; one stuck in a loop the engine can't interrupt is stopped all the same.
- **A script gets only what you allow.** Your notifications, clipboard, apps, local network, terminal
  commands, Do Not Disturb and work profile only with an `@uses` line and your yes; the internet and
  its header line only if its code uses them. Each has a switch, per script
  ([switching access off](/scripting/overview/#switching-access-off)).
- **Scripts from outside start switched off.** One from a file, a folder or a backup asks before it
  runs, and an update that asks for more (the internet included) starts with that off.
- **A script never holds your keys.** `secrets.get` gives it a stand-in; the value goes only to the
  sites its `@secret` line names, over https. Values are encrypted with a key kept in Android's
  Keystore and left out of backups ([API keys and other secrets](/scripting/overview/#api-keys-and-other-secrets)).
- **A script can't act for you.** Its lines can't run a command or dial a number when tapped, and it
  can't run commands that change things (`clear`, `uninstall`, `config` and the like).

## Checking a download

Releases are built by GitHub Actions from the commit their tag points to, and their notes name that
commit. Both APKs on GitHub are signed with Kolahal's release key, as is the version on Google Play.
The key's certificate has this SHA-256 digest:

```
21223ad4312a871e4a1dd16440efccac84f4660c56fa1e80467631062938e210
```

To check an APK before installing it, run `apksigner verify --print-certs kolahal-X.Y.Z.apk` (from
the Android SDK's build tools) and compare the digest it prints for the signer's certificate.

## Reporting a vulnerability

Please don't open a public issue for a security problem. Report it privately on GitHub:
[**Report a vulnerability**](https://github.com/mtwabpin/kolahal/security/advisories/new) (the
repository's **Security** tab). Say:

- the version and build (`fetch` shows them), the phone and its Android version;
- what someone could do with the problem;
- how to make it happen: the steps, or a small script that shows it.

We answer there, fix it in a release, and say so in its notes; tell us if you'd like credit. Only
the latest release gets fixes.

Reports written with an AI tool's help are welcome if you've checked them yourself on a real build.
A report that is only a tool's output, not reproduced, is closed without a reply.

### What counts

- A way around a script's limits or switches: reaching data, the network or commands it wasn't
  allowed, or a secret going anywhere its `@secret` line doesn't name.
- Anything that sends your data off the phone, or to another app, without you asking.
- Problems with the release builds or their signing.

Not counted: a rooted phone, someone holding your unlocked phone, bugs in Android itself, and
scripts doing what you allowed them to.
