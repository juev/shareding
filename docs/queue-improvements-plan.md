---
task: "Fix shared titles and queue synchronization controls"
status: done
mode: execution
authorization: "User requested implementation of all listed queue and title improvements on 2026-09-30."
approved:
worktree: "/Users/denis.evsyukov/src/github.com/juev/shareding"
branch: "main"
updated: "2026-09-30"
---

# Queue improvements

Problem: Share extras become unvalidated titles. Manual sync preserves retry backoff, deletion does not restart work, and queue cards hide URLs and server errors.
Shipping: An agreed title contract, immediate worker replacement for manual actions and deletion, queue sync status, expandable selectable details, and HTTP error bodies.
Not shipping: Release, store publication, and changes to the separate active Google Play plan.
Verification: API unit tests, WorkManager and worker integration tests, Compose UI tests, build, and lint on available Android emulators.

## Verified behavior

`ShareActivity` saves `EXTRA_TITLE` or `EXTRA_SUBJECT` verbatim. `LinkdingApi` always sends title and discards error bodies. `requestSync` keeps pending work and appends behind running work. The worker holds batches of 50 bookmarks and uses blocking OkHttp requests. Queue deletion only updates Room. Settings shows only the last successful sync time.

## Decisions and scenarios

- Keep automatic save/recovery scheduling behavior. Add a distinct replacement operation for manual sync, Retry, settings changes, and confirmed deletion. Replacement must cancel cancellable network calls and read the queue again. A request already accepted by the server cannot be recalled.
- Delete from Room before replacing work; check that each batch entry still exists before sending. A removed later batch entry must never be posted.
- Queue status remains visible for an empty queue. Show active/waiting work and the last completed attempt time and outcome; Settings loses its Sync section.
- Tapping a card toggles full title, URL, and error details. Text can be selected and copied. Delete and Retry remain separate actions.
- Preserve HTTP status and response body in queued errors, including structured validation errors and text responses. An empty body still produces a useful HTTP error.
- The user chose to omit Share titles and send form titles. Room version 2 records `sendTitle`; legacy titles stay local and default to server extraction. Manual titles use one line and at most 512 Unicode code points. No background metadata fetch is needed.

## Steps

1. Confirm title strategy and update `docs/specs/share-to-linkding.md`.
2. Add replacement scheduling, cancellation-aware API requests, and stale batch protection; verify replacement, deletion, and immediate delivery.
3. Persist completed sync attempts and show queue status; remove Settings sync controls and expand card details.
4. Implement title/error serialization contract and regression coverage.
5. Run unit, instrumentation, build, and lint checks; reconcile docs and record results.

## State

Completed: Title decision, Room migration, scheduler replacement, cancellable requests, queue status and expandable cards, HTTP error bodies, documentation, screenshots, and regression coverage. Debug/release builds and lint passed. All 34 unit tests passed for debug and release. All 47 instrumentation tests passed on Android 9 and 16; after fixing a visual overlap found in the expanded card, all 15 UI tests passed again on both versions, including a regression assertion for text positions. WorkManager replacement tests accept a removed old row as well as a retained CANCELLED state. Lint was run after compilation to avoid a kapt-stub analysis race.
Remaining: None within this app implementation task. Real-phone validation against the user's linkding server and a real VPN were not performed. All release, F-Droid, and Play work is deferred by the user.
Next step: User validation before any separate publication request.
