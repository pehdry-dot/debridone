#!/usr/bin/env bash
# Runs DebridOne's own test suites (storage, accounts, module isolation).
#   ./scripts/debridone/test.sh          fast suites
#   ./scripts/debridone/test.sh --all    also Nuvio's desktop tests
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

tasks=(:debridone:platform:test :debridone:accounts:test :debridone:architecture-tests:test)
if [[ "${1:-}" == "--all" ]]; then
  shift
  tasks+=(:composeApp:desktopTest)
fi
exec ./gradlew "${tasks[@]}" --no-configuration-cache "$@"
