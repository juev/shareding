# Sending links to linkding on Android

Status: R1–R10 implemented. Build, unit, lint, and all 18 instrumentation tests passed on Android 9 and Android 16. A signed 0.1.0 APK upgraded an installed 0.1.0-rc.2 APK without losing a queued link. A real VPN and an HTTPS server with a trusted certificate have not been tested manually.

Sources: the user's requirements, the agreed RFC, and the iOS app in the sibling `share` repository.

## Purpose and scope

The app accepts a link from Android's Share menu or its add form, saves it on the device, and sends it to the configured linkding server. Saving does not depend on network availability or server settings. Android 9 (API 28) and newer are supported.

## Requirements

- R1. Register as a `text/plain` `ACTION_SEND` target. Accept HTTP(S) URLs and save each entry to the local database before making a network request. Keep the sending app visible through a transparent Share activity, show a short `Saved to queue` Toast only after a successful local write, and then finish. Show distinct feedback for invalid URLs, duplicates, or local save errors.
- R2. Send queued entries when a network becomes available. The server may be on a LAN or VPN without public internet access. `NET_CAPABILITY_VALIDATED` must not block such servers. Check linkding availability with an API request before sending.
- R3. Keep unsent entries after a timeout, HTTP error, connection error, or restart, and keep retrying without a fixed limit. WorkManager uses exponential backoff starting at 30 seconds; Android may run the work later. A successful `POST /api/bookmarks/` removes only the entry that was sent.
- R4. Let users add a URL manually with title, description, and tags. Fill in the page title when it can be retrieved. Settings include the server URL, API token, default tags, unread/archive options, Test Connection, Sync Now, last successful sync time, and queue count. Keep the connection result visible until the server URL or token changes. The queue shows status chips, Retry for failed entries, refresh, and an empty state with an Add bookmark action.
- R5. Keep the two Queue/Settings tabs, full-screen Add Bookmark form, icon, and brief Share confirmation from the iOS design. About shows the installed version and compact rows linking to the developer's website as on iOS and the Android source repository. Use native Android controls, Light/Dark themes, and screen reader labels.
- R6. Crashes, stopped background work, and reboots must not lose entries. Concurrent additions and manual sync must not remove an entry without confirmed delivery. Adding a duplicate URL must not change an entry while it is being sent.
- R7. Allow manual removal of one entry only after user confirmation. Canceling the confirmation keeps the entry.
- R8. Set `minSdk` to 28. Verify behavior on Android 9 and a current Android version.
- R9. Accept `https://` and `http://` server URLs. For HTTP, show that the API token and request are sent without TLS. HTTPS uses normal certificate validation; redirects to another origin must not receive the token.
- R10. Keep one visible Save settings action while editing any Settings section. After a successful save, hide the keyboard and show confirmation; on failure, keep the draft available for correction. Explain comma-separated tag entry with an example in Settings and Add Bookmark. When saved server credentials are available, fetch all pages of existing linkding tags and suggest matching names in both forms. Suggestions must not prevent entering a new tag or saving while the server is unreachable. Default tags are saved locally; Add Bookmark saves the entered tags with the queued bookmark.

## Invariants and compatibility

- A local write happens before background work is scheduled or a network request is made.
- Automatic removal happens only after confirmed API success. Manual removal is a separate user action.
- Delivery has at-least-once semantics. If the server accepts a POST but the response is lost, the request may be repeated. Current linkding updates an existing bookmark with the same URL.
- Missing server URL or token does not prevent local saving. The queue remains available for delivery after setup.
- A saved page URL may use HTTP or HTTPS regardless of the linkding server URL scheme.
- Editing or loading tag suggestions does not change already queued bookmarks. A missing connection does not prevent local tag entry or saving bookmark defaults.

## Design decisions

Kotlin and Jetpack Compose provide the Android UI; Room stores the queue; WorkManager runs durable background sync with a network constraint and retries. Its `NetworkRequest` requires the `INTERNET` capability, but not `VALIDATED` or `NOT_VPN`: Wi-Fi without public internet validation and VPN remain candidates. The worker reads Room entries in creation order, tries available networks, and checks linkding through `GET /api/tags/`. The API token is stored separately from the queue using Android Keystore protection. Android Network Security Config allows HTTP for arbitrary server addresses; the app sends the token only to the configured origin.

For R10, the Settings Save action applies to the current form draft and remains visible below the scrollable sections. Tag suggestions use the saved server URL and token, follow the existing LAN/VPN network selection behavior, and request each page from the configured origin. An unavailable tag list leaves free-text entry usable.

## Verification scenarios

- R1, R2: Share a URL without a network or server settings. ShareDing appears in the text Share menu, keeps the sending app visible, saves the entry, shows a short Toast, and sends it after network access and server settings become available. Sharing invalid text leaves the queue unchanged.
- R3, R6: Return 401/5xx from the API, drop the connection, or stop the worker. The entry remains queued and is removed only after a successful POST. A new link during backoff starts an earlier attempt.
- R2, R9: Reach a linkding server using HTTP or HTTPS only through LAN/VPN. Delivery works without public internet validation; HTTP shows a warning.
- R4, R5: The add form, Queue, and Settings perform their actions and show the agreed states in Light/Dark themes.
- R7: Cancel removal and keep the entry; confirm removal and delete only the selected entry.
- R10: Enter default tags, save from the bottom of Settings, and verify persistence and keyboard dismissal. Type a partial tag name and select a server suggestion in either form; verify comma-separated names and local saving. Return an API error or disconnect the server and verify that manual tag entry and saving still work.

Automated checks: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug connectedDebugAndroidTest`. URL and API cases are covered in `app/src/test/java/org/evsyukov/shareding/network/`; Room queue, Share Intent, worker, scheduler, and UI cases are covered in `app/src/androidTest/java/org/evsyukov/shareding/`.

Manual network checks: On Android 9, turn off Wi-Fi and mobile data, share a URL, close the app, turn Wi-Fi on, then confirm the POST reaches a test server and the queue empties. To check LAN access without public internet, disable public internet validation in the emulator, confirm that `NET_CAPABILITY_VALIDATED` is absent, and send to a reachable linkding server. To check backoff, make the test server return 503 for the first POST and 201 for the next; the entry must remain queued until the second response.
