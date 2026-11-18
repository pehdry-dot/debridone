#!/usr/bin/env bash
# Runs DebridOne's own test suites (storage, accounts, cloud accounts and downloads, Music, Manga, module isolation).
# The Music playback tests need libmpv (apt install libmpv2 / brew install mpv); without it they
# are skipped, unless DEBRIDONE_REQUIRE_LIBMPV=1 is set.
# The Manga extension-host tests need Node.js: run ./scripts/debridone/fetch-node-runtime.sh once
# (the pinned build the app bundles), or have `node` 22.13+ on PATH. Without it they are skipped,
# unless DEBRIDONE_REQUIRE_NODE=1 is set.
#   ./scripts/debridone/test.sh          fast suites
#   ./scripts/debridone/test.sh --all    also Nuvio's desktop tests
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

tasks=(:debridone:platform:test :debridone:accounts:test :debridone:cloud:test :debridone:modules:music:test :debridone:modules:manga:test :debridone:architecture-tests:test)
if [[ "${1:-}" == "--all" ]]; then
  shift
  tasks+=(:composeApp:desktopTest)
fi
exec ./gradlew "${tasks[@]}" --no-configuration-cache "$@"
