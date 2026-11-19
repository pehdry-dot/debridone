#!/usr/bin/env bash
# Runs DebridOne's own test suites (storage, accounts, cloud accounts and downloads, Music, Manga, module isolation).
# The Music playback tests need libmpv (apt install libmpv2 / brew install mpv); without it they
# are skipped, unless DEBRIDONE_REQUIRE_LIBMPV=1 is set.
# The Manga tests start the real Suwayomi-Server: run ./scripts/debridone/fetch-suwayomi-runtime.sh
# once (the pinned server and Java 21 runtime the app bundles). Without them those tests are
# skipped, unless DEBRIDONE_REQUIRE_SUWAYOMI=1 is set.
#   ./scripts/debridone/test.sh          fast suites
#   ./scripts/debridone/test.sh --all    also Nuvio's desktop tests
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

tasks=(:debridone:platform:test :debridone:accounts:test :debridone:cloud:test :debridone:modules:music:test :debridone:modules:manga-bridge:test :debridone:modules:manga:test :debridone:architecture-tests:test)
if [[ "${1:-}" == "--all" ]]; then
  shift
  tasks+=(:composeApp:desktopTest)
fi
exec ./gradlew "${tasks[@]}" --no-configuration-cache "$@"
