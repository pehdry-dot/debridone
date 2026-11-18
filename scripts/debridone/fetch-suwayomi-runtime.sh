#!/usr/bin/env bash
# Downloads what DebridOne bundles for its Manga backend, and checks each file against a pinned
# SHA-256:
#
#   - Suwayomi-Server (https://github.com/Suwayomi/Suwayomi-Server, MPL-2.0), the release jar;
#   - a Java 21 runtime to run it with (Eclipse Temurin JRE, GPLv2 with Classpath Exception).
#
#   ./scripts/debridone/fetch-suwayomi-runtime.sh [darwin-arm64|darwin-x64|linux-x64] [--root <repo dir>]
#
# Without a platform, the one this computer runs is used. The result is
#   debridone/modules/manga/build/suwayomi-runtime/Suwayomi-Server.jar
#   debridone/modules/manga/build/suwayomi-runtime/<platform>/jre/        (bin/java, lib/, legal/)
# which is where the Gradle build and the tests look. Nothing is installed system-wide.
#
# The packaged macOS app contains these copies and never uses a Java found on the computer:
# Suwayomi-Server needs Java 21, and the app's own trimmed runtime has no `java` launcher.
set -euo pipefail

SERVER_VERSION="v2.4.2366"
SERVER_SHA256="af9feb20af9d7ebe9e30769e6c6ebc7fc7ab1c447388d42e0280b1da5fe07bfd"
SERVER_URL="https://github.com/Suwayomi/Suwayomi-Server/releases/download/${SERVER_VERSION}/Suwayomi-Server-${SERVER_VERSION}.jar"

# From https://api.adoptium.net/v3/assets/latest/21/hotspot?image_type=jre (checksums as published there).
jre_for() {
  case "$1" in
    darwin-arm64) echo "jdk-21.0.13.1%2B2 OpenJDK21U-jre_aarch64_mac_hotspot_21.0.13.1_2.tar.gz 848feb1906c7de79de4f8391636042193fbd46eff86e26299506c4656f62c260" ;;
    darwin-x64) echo "jdk-21.0.13%2B9 OpenJDK21U-jre_x64_mac_hotspot_21.0.13_9.tar.gz c909674b1c10c261b9a3fb7e9c0efebd303e33f88a4fe84be7135b5e2d711f89" ;;
    linux-x64) echo "jdk-21.0.13%2B9 OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd" ;;
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
    *) echo "No pinned Java runtime for $(uname -s) $(uname -m)." >&2; exit 1 ;;
  esac
fi
read -r jre_release jre_archive jre_sha256 <<<"$(jre_for "${platform}")"

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

base="${root}/debridone/modules/manga/build/suwayomi-runtime"
mkdir -p "${base}"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

# ---- the server -----------------------------------------------------------------------------------
jar="${base}/Suwayomi-Server.jar"
if [[ -f "${jar}" && "$(sha256_of "${jar}")" == "${SERVER_SHA256}" ]]; then
  echo "Suwayomi-Server ${SERVER_VERSION} is already present."
else
  echo "Downloading Suwayomi-Server ${SERVER_VERSION} (about 183 MB)..."
  curl -fsSL --retry 3 --retry-delay 2 -o "${work}/server.jar" "${SERVER_URL}"
  actual="$(sha256_of "${work}/server.jar")"
  if [[ "${actual}" != "${SERVER_SHA256}" ]]; then
    echo "Checksum mismatch for Suwayomi-Server ${SERVER_VERSION}:" >&2
    echo "  expected ${SERVER_SHA256}" >&2
    echo "  actual   ${actual}" >&2
    exit 1
  fi
  mv "${work}/server.jar" "${jar}"
  echo "${SERVER_VERSION}" > "${base}/.server-version"
fi

# ---- the Java runtime -----------------------------------------------------------------------------
target="${base}/${platform}/jre"
stamp="${base}/${platform}/.jre-version"
if [[ -x "${target}/bin/java" && "$(cat "${stamp}" 2>/dev/null)" == "${jre_release} ${jre_sha256}" ]]; then
  echo "Java runtime ${jre_release//%2B/+} for ${platform} is already present."
else
  echo "Downloading ${jre_archive}..."
  curl -fsSL --retry 3 --retry-delay 2 -o "${work}/${jre_archive}" \
    "https://github.com/adoptium/temurin21-binaries/releases/download/${jre_release}/${jre_archive}"
  actual="$(sha256_of "${work}/${jre_archive}")"
  if [[ "${actual}" != "${jre_sha256}" ]]; then
    echo "Checksum mismatch for ${jre_archive}:" >&2
    echo "  expected ${jre_sha256}" >&2
    echo "  actual   ${actual}" >&2
    exit 1
  fi
  mkdir -p "${work}/unpacked"
  tar -xzf "${work}/${jre_archive}" -C "${work}/unpacked"
  top="$(find "${work}/unpacked" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  # A macOS runtime is a bundle; only its Home (bin, lib, conf, legal) is needed.
  if [[ -d "${top}/Contents/Home" ]]; then home="${top}/Contents/Home"; else home="${top}"; fi
  if [[ ! -x "${home}/bin/java" ]]; then
    echo "The archive ${jre_archive} has no bin/java where expected." >&2
    exit 1
  fi
  rm -rf "${target}"
  mkdir -p "$(dirname "${target}")"
  mv "${home}" "${target}"
  echo "${jre_release} ${jre_sha256}" > "${stamp}"
fi

echo "Manga backend runtime ready:"
echo "  ${jar}"
echo "  ${target}/bin/java"
