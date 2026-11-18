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

# Manga's backend is Suwayomi-Server, on a Java 21 runtime. The packaged app carries its own
# copies; from source, the pinned ones fetched by fetch-suwayomi-runtime.sh are used.
if [[ -z "${DEBRIDONE_SUWAYOMI_JAVA:-}" || -z "${DEBRIDONE_SUWAYOMI_JAR:-}" ]]; then
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) suwayomi_platform="darwin-arm64" ;;
    Darwin-x86_64) suwayomi_platform="darwin-x64" ;;
    Linux-x86_64) suwayomi_platform="linux-x64" ;;
    *) suwayomi_platform="" ;;
  esac
  runtime="${PWD}/debridone/modules/manga/build/suwayomi-runtime"
  if [[ -n "${suwayomi_platform}" ]]; then
    ./scripts/debridone/fetch-suwayomi-runtime.sh "${suwayomi_platform}" || echo "Manga will be unavailable in this run (Suwayomi-Server could not be fetched)." >&2
  fi
  if [[ -x "${runtime}/${suwayomi_platform}/jre/bin/java" && -f "${runtime}/Suwayomi-Server.jar" ]]; then
    export DEBRIDONE_SUWAYOMI_JAVA="${runtime}/${suwayomi_platform}/jre/bin/java"
    export DEBRIDONE_SUWAYOMI_JAR="${runtime}/Suwayomi-Server.jar"
  fi
fi

exec ./gradlew :composeApp:run --no-configuration-cache "$@"
