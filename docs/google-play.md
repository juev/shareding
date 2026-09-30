# Google Play release checklist

This guide is for the first ShareDing Play release, version 0.2.0 (code 13). The Play-delivered APKs and the GitHub/F-Droid APK must use the same package and app-signing certificate so existing installations can update. The AAB has a separate upload signature.

## Artifacts and signing

The GitHub [release workflow](../.github/workflows/release.yml) signs the APK with the existing app-signing key and retains a separately signed AAB as the `ShareDing-Play-v0.2.0` Actions artifact. The AAB upload certificate SHA-256 is `DE:5C:B1:C8:16:BA:ED:02:55:BE:FA:89:4F:12:BB:D1:1C:47:5F:4B:C5:C1:DC:08:0B:C5:EF:CD:E1:50:FC:38`. The existing APK certificate SHA-256 is `36:93:CA:80:CB:FC:0D:7B:30:A4:03:F4:9C:25:63:7D:78:B1:2D:1A:19:6B:DE:C3:98:F4:13:FB:56:A8:65:7E`.

Before the first Play rollout, open **Protected with Play → Play Store distribution → Play app signing** in Play Console. Choose to provide a copy of the existing app-signing key and follow the Console's PEPK instructions. The local key is `~/.local/share/shareding/release.jks` with alias `shareding`; its password is in macOS Keychain under service `org.evsyukov.shareding.release`, account `shareding`. PEPK encrypts the private key for Google. Keep the keystore, password, and encrypted PEPK output out of this repository. Verify the app-signing certificate shown by Play matches the APK fingerprint above. Register the separate upload certificate at `~/.local/share/shareding/upload_certificate.pem`; its private key is in `~/.local/share/shareding/upload.jks`, alias `shareding`, and its password is in Keychain service `org.evsyukov.shareding.upload`, account `shareding`. Back up both local keys and passwords securely.

The workflow's upload secrets are `SHAREDING_UPLOAD_KEYSTORE_B64` and `SHAREDING_UPLOAD_PASSWORD`. Download the verified AAB from the release workflow artifact for manual Console upload. Keep the APK from the GitHub Release for GitHub/F-Droid users. Do not upload the APK to Play as the release artifact.

## Listing and App content

- Use the English title, short description, full description, icon, feature graphic, screenshots, and release notes under [`fastlane/metadata/android/en-US`](../fastlane/metadata/android/en-US/). The icon is 512 × 512 PNG, the feature graphic is 1024 × 500 PNG, and the three phone screenshots are 1200 × 2400 PNG.
- Set the public privacy policy URL to `https://github.com/juev/shareding/blob/main/docs/privacy.md` and privacy contact to `denis@evsyukov.org`. Check that the URL opens without a GitHub login after the release commit reaches `main`; the app's About screen opens the same page.
- Complete App access, Data safety, ads, target audience, content rating, and any other declarations shown by Console. The app has no advertising or analytics SDK, stores its queue and settings locally, and sends the token and bookmark fields to the user-configured HTTPS linkding server. It may fetch HTTP bookmark pages without TLS; those requests carry no token or credentials. The app has no proxy setting. Use these facts when answering Console questions; review the exact wording of each question before submitting answers. Do not describe all app traffic as encrypted.
- In App access, explain in English how a reviewer gets a stable test API token and HTTPS URL for the existing publicly reachable linkding instance. Use a dedicated account with a limited test library, verify that login and API access work without VPN or regional restrictions, and keep it available through review. Enter the actual URL and token directly in Console, not in this repository.

## Testing and rollout

1. Upload the signed AAB to internal testing. Install it from Play, check bookmark sync against the reviewer instance, and check an update over a release APK. A successful update confirms that Play used the existing app-signing key and preserved local settings and queued bookmarks. Compare the Play app-signing fingerprint with the existing APK fingerprint.
2. Start closed testing and invite at least 12 people who have Google accounts. Google provides the track and opt-in link; the developer recruits testers. Keep 12 or more people opted in continuously for 14 days, gather actual feedback, and fix blocking issues with a higher version code. Invite a buffer of 15–20 willing testers to cover opt-outs.
3. When Console allows it, apply for production access and answer its testing questions with actual results. After approval, submit a production release and confirm the public listing, install, and signer. The account may have an additional device verification task in Console.

Google's current instructions: [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756), [testing for new personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465), [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).
