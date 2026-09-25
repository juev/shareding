#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git -C "$(dirname "${BASH_SOURCE[0]}")/.." rev-parse --show-toplevel)"
export SHAREDING_RELEASE_KEYSTORE="${SHAREDING_RELEASE_KEYSTORE:-$HOME/.local/share/shareding/release.jks}"

if [[ ! -f "$SHAREDING_RELEASE_KEYSTORE" ]]; then
    printf 'Release keystore not found: %s\n' "$SHAREDING_RELEASE_KEYSTORE" >&2
    exit 1
fi

if [[ -z "${SHAREDING_RELEASE_PASSWORD:-}" ]]; then
    SHAREDING_RELEASE_PASSWORD="$(security find-generic-password -s org.evsyukov.shareding.release -a shareding -w)"
    export SHAREDING_RELEASE_PASSWORD
fi

exec "$repo_root/gradlew" --no-daemon -p "$repo_root" assembleRelease
