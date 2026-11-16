#!/usr/bin/env bash
# Runs DebridOne from source.
#   ./scripts/debridone/dev.sh            run with your real data folder
#   ./scripts/debridone/dev.sh --sandbox  run with a throw-away data folder under build/
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

if [[ "${1:-}" == "--sandbox" ]]; then
  shift
  export DEBRIDONE_DATA_DIR="${PWD}/build/debridone-sandbox/data"
  export DEBRIDONE_CACHE_DIR="${PWD}/build/debridone-sandbox/cache"
  echo "Using sandbox data folder: ${DEBRIDONE_DATA_DIR}"
fi

exec ./gradlew :composeApp:run --no-configuration-cache "$@"
