---
title: Install
description: Build Kolahal from source with Gradle, or sideload an APK, then make it your home app.
---

Kolahal (package `io.github.rscipher001.launcher`) needs **Android 9 (API 28) or newer**. Profile
toggling needs Android 10+, and private space needs Android 15+.

## Build from source

You need a JDK and the Android SDK. The project's toolchain is AGP 9.1, Gradle 9.3.1 (through the
wrapper), Kotlin 2.3.20, compileSdk 37 and targetSdk 36.

```sh
git clone https://github.com/rscipher001/kolahal.git
cd kolahal

./gradlew :app:assembleRelease            # R8-optimised; signed with keystore.properties or the debug key
./gradlew :app:assembleDebug              # debug APK ("Kolahal debug", package suffix .debug)
./gradlew :app:testDebugUnitTest          # JVM tests
./gradlew :app:connectedDebugAndroidTest  # engines, sandbox, timeouts, :script process, commands
```

The APKs end up in `app/build/outputs/apk/<variant>/`.

:::tip[Use the release build day to day]
Debug Compose builds are noticeably slower to scroll. The debug build installs next to the release
one (it has a `.debug` package suffix), so you can keep both.
:::

### Signing

Without configuration, the release build is signed with the debug key. To sign with your own key,
create `keystore.properties` in the repository root (it is git-ignored) with these keys:

```properties
storeFile=/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## Sideload the APK

Copy the APK to the phone and open it, or install over USB:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

A sideloaded APK runs without ahead-of-time compilation until Android compiles it during idle
maintenance, so it can feel laggy at first. To compile it right away:

```sh
adb shell cmd package compile -m speed -f io.github.rscipher001.launcher
```

## Make it your home app

Press Home and pick Kolahal, or run `home` at the prompt to open Android's default home app
setting. Being the default launcher matters: Android only lets the default launcher pause or resume
the work profile, list app shortcuts and see the private space.

Then run [`setup`](/getting-started/setup/) and work through the checklist.
