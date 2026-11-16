#!/usr/bin/env bash
# Runs DebridOne's own test suites (storage, accounts, Music, module isolation).
# The Music playback tests need libmpv (apt install libmpv2 / brew install mpv); without it they
# are skipped, unless DEBRIDONE_REQUIRE_LIBMPV=1 is set.
#   ./scripts/debridone/test.sh          fast suites
#   ./scripts/debridone/test.sh --all    also Nuvio's desktop tests
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

tasks=(:debridone:platform:test :debridone:accounts:test :debridone:modules:music:test :debridone:architecture-tests:test)
if [[ "${1:-}" == "--all" ]]; then
  shift
  tasks+=(:composeApp:desktopTest)
fi
exec ./gradlew "${tasks[@]}" --no-configuration-cache "$@"
