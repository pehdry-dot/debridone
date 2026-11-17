#!/usr/bin/env bash
# Downloads the Node.js runtime that DebridOne bundles for its Manga extension host, and checks it
# against the SHA-256 published by the Node.js project (pinned below).
#
#   ./scripts/debridone/fetch-node-runtime.sh [darwin-arm64|darwin-x64|linux-x64] [--root <repo dir>]
#
# Without a platform, the one this computer runs is used. The runtime is unpacked to
#   debridone/modules/manga/build/node-runtime/<platform>/        (bin/node, lib/node_modules/npm, LICENSE)
# which is where the Gradle build looks for it. Nothing is installed system-wide.
#
# The packaged macOS app contains this copy of `node` and never uses one found on the computer.
set -euo pipefail

NODE_VERSION="v22.23.4"
# From https://nodejs.org/dist/v22.23.4/SHASUMS256.txt
sha256_for() {
  case "$1" in
    darwin-arm64) echo "c9e1dbc748b33ed2d14f42445191ecaf14c56259c7a9a26d0042c49ec2b66428" ;;
    darwin-x64) echo "efcc2b0db6f94bf53eddb038fe2b370277a042bd29e4f19b8f8e23598795b819" ;;
    linux-x64) echo "b8492284c76a54baf58d61668dd0e41ee0fc98adabf9503d30f05d08f55b4406" ;;
    *) return 1 ;;
  esac
}

platform=""
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
while (( $# > 0 )); do
  case "$1" in
    --root) root="$(cd "$2" && pwd)"; shift 2 ;;
    darwin-arm64 | darwin-x64 | linux-x64) platform="$1"; shift ;;
    arm64) platform="darwin-arm64"; shift ;;
    x86_64) platform="darwin-x64"; shift ;;
    *) echo "Usage: $0 [darwin-arm64|darwin-x64|linux-x64] [--root <repo dir>]" >&2; exit 2 ;;
  esac
done
if [[ -z "${platform}" ]]; then
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) platform="darwin-arm64" ;;
    Darwin-x86_64) platform="darwin-x64" ;;
    Linux-x86_64) platform="linux-x64" ;;
    *) echo "No pinned Node.js build for $(uname -s) $(uname -m)." >&2; exit 1 ;;
  esac
fi
expected="$(sha256_for "${platform}")"

target="${root}/debridone/modules/manga/build/node-runtime/${platform}"
stamp="${target}/.node-version"
if [[ -x "${target}/bin/node" && "$(cat "${stamp}" 2>/dev/null)" == "${NODE_VERSION} ${expected}" ]]; then
  echo "Node.js ${NODE_VERSION} for ${platform} is already present."
  exit 0
fi

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

archive_name="node-${NODE_VERSION}-${platform}.tar.gz"
url="https://nodejs.org/dist/${NODE_VERSION}/${archive_name}"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

echo "Downloading ${archive_name}..."
curl -fsSL --retry 3 --retry-delay 2 -o "${work}/${archive_name}" "${url}"
actual="$(sha256_of "${work}/${archive_name}")"
if [[ "${actual}" != "${expected}" ]]; then
  echo "Checksum mismatch for ${archive_name}:" >&2
  echo "  expected ${expected}" >&2
  echo "  got      ${actual}" >&2
  exit 1
fi

rm -rf "${target}"
mkdir -p "${target}"
tar -xzf "${work}/${archive_name}" -C "${target}" --strip-components 1
[[ -x "${target}/bin/node" ]] || { echo "The archive did not contain bin/node." >&2; exit 1; }
echo "${NODE_VERSION} ${expected}" > "${stamp}"
echo "Node.js ${NODE_VERSION} for ${platform} is ready and verified: ${target}/bin/node"
