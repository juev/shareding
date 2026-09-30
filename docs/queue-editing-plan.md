---
task: "Edit queued bookmarks while pausing sync"
status: done
mode: execution
authorization: "User approved the queue editing form and whole-queue pause design on 2026-09-30."
approved:
worktree: "/Users/denis.evsyukov/src/github.com/juev/shareding"
branch: "main"
updated: "2026-09-30"
---

# Queue editing

Problem: Queued bookmarks cannot be corrected, and background work can send an entry while its draft is being edited.
Shipping: Edit in expanded cards, a full-screen URL/title/description/tags form, explicit save/discard, and a whole-queue pause until the editor closes.
Not shipping: Commit, push, release, F-Droid, Play publication, or editing already delivered server bookmarks.
Verification: Room and scheduler integration tests, cancellation against a TLS mock server, Compose editing/recreation tests, debug/release build, unit tests, and lint on Android 9 and 16.

## Requirements and decisions

The user approved the interaction and pause design in this conversation. Extend the existing behavior contract in `docs/specs/share-to-linkding.md`.

- Opening Edit establishes a process-local pause, cancels the unique sync work, and waits for the active worker to finish before loading the entry. If delivery already removed it, report that it has left the queue; never recreate it.
- Every scheduling entrypoint respects the same pause. Recovery remains registered, and shares still save to Room. A worker also checks the pause before starting and sending.
- A ViewModel owns the editor session and draft through Activity recreation. Closing it releases the pause; process death removes the in-memory gate and startup recovery resumes delivery. A session identifier prevents stale cleanup from resuming a newer editor.
- Save validates HTTP(S) URL, atomically updates the same ID, preserves creation time, notes, unread/archive flags, resets errors and attempts, and restarts sync after dropping the pause. A duplicate or missing entry keeps the draft open with a visible error.
- Changing only URL, description, or tags preserves the original title and `sendTitle`. Changing title makes a nonblank normalized title manual; clearing it omits title. Do not truncate or normalize shared preview text merely by opening or saving other fields.
- Cancel without changes resumes immediately; cancel with changes asks whether to discard. A failed save keeps the queue paused while the draft remains open.

## Steps

1. Extend the scheduler with an owner-scoped pause, serialize worker completion, and cover all automatic/manual entrypoints.
2. Add atomic queued updates and editor session/draft state, including duplicate/missing errors and title-source preservation.
3. Add Edit to expanded cards, reuse tag input in a full-screen editor, and preserve drafts through rotation.
4. Verify worker cancellation, save/discard, pause cleanup, automatic recovery, and UI on both Android versions; update documentation and screenshots.

## State

Completed: Scheduler pause and worker draining, atomic updates, editor ViewModel and UI, regression coverage, documentation, and screenshots. Commit `357532d` contains the previous queue improvements. The user subsequently authorized committing, pushing, and publishing a GitHub prerelease on 2026-09-30.

Verification: All 61 instrumented tests passed on Android 9 and 16, followed by the additional real Share-during-edit test on both devices (62 scenarios per device in total). All 38 unit tests passed for debug and release. Debug/release APK builds and lint passed. Lint was rerun separately after a kapt/lint race during combined compilation. Manual inspection confirmed the expanded card and editor layout; killing the app process with an unsaved draft and restarting retained the queued URL, discarded the draft, and released the pause. No linkding connection was configured for this process-death check.

Remaining: None within the approved scope. Device testing and publication are separate user actions.
