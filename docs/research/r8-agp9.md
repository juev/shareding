# R8 optimization and the AGP 9 upgrade

Measured on 2026-11-02 for [#51](https://github.com/juev/shareding/issues/51).

## Why

Google Play Console flagged release 28 (0.3.1) under "Improve your app's memory and performance with R8 optimization":

- Optimized resource shrinking isn't enabled.
- Upgrade your Android Gradle plugin to version 9.0 or higher.

The same release also got "Improve your app's performance with bitmap downsampling" for `o3.a.a` and `o3.c.h`. The R8 mapping in the bundle resolves both to `androidx.core` (`ShortcutInfoCompat` and `ShortcutManagerCompat`), which ShareDing uses to publish the Direct Share target. ShareDing does not call `BitmapFactory`, and the shortcut icon comes from a resource, so that item was left alone.

## Builds compared

Unsigned release builds of 0.3.1 (versionCode 28), same sources:

| Build | APK | dex | resources.arsc | Files in `res/` |
|---|---|---|---|---|
| AGP 8.13.2, Gradle 8.13 | 4 267 600 B | 2 915 548 | 159 052 | 37 |
| AGP 8.13.2 + `android.r8.optimizedResourceShrinking=true` | 4 192 908 (−1.8 %) | 2 914 660 | 96 908 | 5 |
| AGP 9.5.0, Gradle 9.8.1 | 4 060 496 (−4.9 %) | 2 780 548 (−4.6 %) | 95 936 | 5 |

AGP 9 applies optimized resource shrinking by default: its `resources.arsc` matches the flagged build without the flag. The dex sizes include the 1 396-byte `classes2.dex` with desugared library classes that the AGP 9 build adds.

Cold start (`am start -W`, six runs per build, twice) and total PSS after launch were measured on an API 36 emulator. Start times varied more between runs than between builds, and PSS stayed at about 28 MB for all three. The gain is in download and install size, not in a measurable runtime difference.

## Checks

On the AGP 9 build:

- `assembleDebug lintDebug testDebugUnitTest connectedDebugAndroidTest` pass: 52 unit tests and 75 instrumented tests on API 36.
- The release APK was signed with a debug key, installed on the emulator, and walked through: Add, Settings with the About icon, and Share from another app. The shared link reached the queue and no crash was logged.
- Two clean release builds produced byte-identical APKs.

## Upgrade notes

- AGP 9 has built-in Kotlin, so the `org.jetbrains.kotlin.android` plugin is removed.
- AGP 9 no longer supports kapt; Room's compiler runs on KSP.
- AGP 9 rejects `Provider` source directories (`You cannot add Provider instances to the Android SourceSet API`). The generated test TLS directories are added by path, and the generating tasks stay wired in by name.
- Gradle 9.8.1 reports one deprecation, `Configuration.setVisible(boolean)`, from a plugin rather than from this build's scripts. It is scheduled for removal in Gradle 11.
