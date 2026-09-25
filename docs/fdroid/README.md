# F-Droid submission

`org.evsyukov.shareding.yml` and `en-US/summary.txt` are sources for `fdroiddata/metadata/org.evsyukov.shareding.yml` and `fdroiddata/metadata/org.evsyukov.shareding/en-US/summary.txt`. The recipe points to the tagged `v0.1.0` source and the APK signed by ShareDing's existing release key. F-Droid can publish that APK only if its own build verifies against it. The certificate fingerprint in `AllowedAPKSigningKeys` prevents a different signing key from being accepted.

The repository's `fastlane/metadata/android/en-US/` directory contains the app description, icon, screenshots, and versionCode 3 release notes. These files were added after the `v0.1.0` tag. F-Droid reads upstream graphics from the latest release source, so the screenshots will become available with the next tagged release. The draft recipe includes the description and the separate summary file for the initial listing.

Before each future release, increment `versionCode`, update `versionName`, add a matching Fastlane changelog, and publish the signed APK from the same tagged commit. Keep the app signing key unchanged. F-Droid's `Binaries` URL uses the version name to find the corresponding GitHub Release asset.
