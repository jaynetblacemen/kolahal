# Release notes

One file per version, named after it: `0.3.1.md`. The Release workflow
(`.github/workflows/release.yml`) publishes it as the GitHub release's notes when the tag `v0.3.1`
is pushed, and adds a last line naming the commit both APKs are built from.

What a file holds, as the earlier releases do:

- the package and the Android version it needs, and what it installs over;
- the two APKs (full and lite) and which to pick, with the Play Protect workaround;
- **What's new**, in plain words;
- a line saying the mapping zip is only for reading crash reports.

Google Play asks for its own short "What's new" (500 characters at most) in Play Console. Keep it
beside the notes as `0.3.1-play.txt` and paste it there.
