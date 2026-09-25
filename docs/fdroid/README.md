# F-Droid submission

`org.evsyukov.shareding.yml` is the source for `fdroiddata/metadata/org.evsyukov.shareding.yml`. The recipe points to the tagged `v0.1.2` source and the APK signed by ShareDing's existing release key. F-Droid can publish that APK only if its own build verifies against it. The certificate fingerprint in `AllowedAPKSigningKeys` prevents a different signing key from being accepted.

The tagged source contains the app summary, description, icon, screenshots, and versionCode 5 release notes in `fastlane/metadata/android/en-US/`. F-Droid imports these listing assets from upstream; the fdroiddata recipe only defines the build and source details.

Before each future release, increment `versionCode`, update `versionName`, add a matching Fastlane changelog, and publish the signed APK from the same tagged commit. Keep the app signing key unchanged. F-Droid's `Binaries` URL uses the version name to find the corresponding GitHub Release asset.
