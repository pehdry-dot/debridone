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

# Manga's extension host needs Node.js. The packaged app carries its own copy; from source, use the
# pinned build fetched by fetch-node-runtime.sh and the host prepared by Gradle. Local files work
# without either.
if [[ -z "${DEBRIDONE_MANGA_NODE:-}" ]]; then
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) node_platform="darwin-arm64" ;;
    Darwin-x86_64) node_platform="darwin-x64" ;;
    Linux-x86_64) node_platform="linux-x64" ;;
    *) node_platform="" ;;
  esac
  fetched="${PWD}/debridone/modules/manga/build/node-runtime/${node_platform}/bin/node"
  if [[ -n "${node_platform}" && ! -x "${fetched}" ]]; then
    ./scripts/debridone/fetch-node-runtime.sh "${node_platform}" || echo "Manga's online sources will be unavailable in this run (no Node.js runtime)." >&2
  fi
  if [[ -x "${fetched}" ]]; then
    ./gradlew :debridone:modules:manga:prepareMangaHost --no-configuration-cache -q
    export DEBRIDONE_MANGA_NODE="${fetched}"
    export DEBRIDONE_MANGA_HOST_DIR="${PWD}/debridone/modules/manga/build/manga-host"
  fi
fi

exec ./gradlew :composeApp:run --no-configuration-cache "$@"
