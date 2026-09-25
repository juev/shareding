# Contributing to ShareDing

Bug reports, ideas, documentation fixes, and code contributions are welcome. Report vulnerabilities through the [security policy](SECURITY.md).

## Before opening an issue

Search [existing issues](https://github.com/juev/shareding/issues) first. Include your Android version, ShareDing version, steps to reproduce, and the expected and actual behavior. Attach logs or screenshots when useful, after removing API tokens, private server URLs, bookmark contents, and other personal information.

## Build and verify

Install JDK 17 or newer, Android SDK Platform 36, and Build Tools 36.0.0. Configure `ANDROID_HOME` or `local.properties` (`sdk.dir=...`), then run:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The GitHub Actions workflow runs these checks for pull requests. Instrumentation tests need a connected Android device or running emulator and can be run with `./gradlew connectedDebugAndroidTest`.

## Pull requests

- Keep changes focused and explain the user-visible behavior or problem being addressed.
- Update documentation when behavior or setup changes.
- Run the checks above and report the commands and results in your pull request. For UI or sharing changes, include the device or emulator version and describe any manual checks.
- Do not include credentials, private server details, personal bookmarks, or generated build outputs.
