# ShareDing for Android

ShareDing saves links from Android's Share menu to a local queue and sends them to [linkding](https://github.com/sissbruecker/linkding). It is written in Kotlin, with a Jetpack Compose interface, Room queue, and WorkManager background sync. It requires Android 9 (API 28) or newer.

## Use

1. Enter your linkding server URL and API token in Settings. The URL may use `https://` or `http://`.
2. Share a link from a browser to ShareDing, or add one with the `+` button in Queue.
3. ShareDing saves the link before making a network request. When a network is available, it checks the linkding server and sends queued bookmarks. It removes a bookmark from the queue only after a successful response.

Failed sends stay in the queue. WorkManager retries with exponential backoff starting at 30 seconds; Android may run the work later. Adding another link, using Retry or Sync Now, and saving settings can trigger an earlier attempt. The linkding server may be reachable through a LAN or VPN without public internet access. HTTP is supported, but Settings warns that it sends the API token and request data without TLS.

You can remove a queued bookmark manually after confirming the action. If the server accepts a POST but its response is lost, ShareDing may send the bookmark again; linkding updates an existing bookmark with the same URL.

## Install and update

For automatic update checks, use [Obtainium](https://github.com/ImranR98/Obtainium). Add `https://github.com/juev/shareding` as a source and enable **Include prereleases** while only prerelease versions are available. Install `ShareDing-v0.1.0-rc.1.apk` from the release it finds.

For manual installation, open [Releases](https://github.com/juev/shareding/releases) on your phone, download the latest APK, and confirm the installation. If Android asks for permission to install apps from this source, grant it to your browser or file manager. To update, download the newer APK and install it over the current version.

You can also install the same APK over USB with `adb install -r ShareDing-v0.1.0-rc.1.apk`. All methods require Android 9 or newer. Release APKs use the same signing key, which Android requires for updates. Debug APKs use a different key. If you already installed a debug build, uninstall it before installing the release build. Uninstalling deletes its settings and local queue.

## Build

Install JDK 17 or newer, Android SDK Platform 36, and Build Tools 36.0.0. Set the SDK path with `ANDROID_HOME` or `local.properties` (`sdk.dir=...`).

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```

The last command requires a running emulator or connected device. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`; install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

CI builds the app and runs unit tests and lint for pushes to `main` and pull requests. Build a signed release APK locally on macOS with `./scripts/build-release-macos.sh`. The script reads the key from `$HOME/.local/share/shareding/release.jks` and its password from macOS Keychain (service `org.evsyukov.shareding.release`, account `shareding`). Set `SHAREDING_RELEASE_KEYSTORE` and `SHAREDING_RELEASE_PASSWORD` to use other locations. Back up both the key and Keychain password securely: without them, future APKs cannot update existing installations.

For each new release, increment `versionCode` and update `versionName` in `app/build.gradle.kts`. The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. See [Android App Signing](https://developer.android.com/studio/publish/app-signing) for the signing key requirement.

The behavior contract is in the [specification](docs/specs/share-to-linkding.md).
