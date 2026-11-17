#!/usr/bin/env bash
# Builds an UNSIGNED development DMG for this Mac's architecture.
# Honours JAVA_HOME and GRADLE_USER_HOME, so the JDK and Gradle's caches can live anywhere.
# For both architectures, signing and notarization see scripts/build-macos-release-dmgs.sh
# and debridone/docs/BUILD.md.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS DMGs can only be built on macOS." >&2
  exit 1
fi

case "$(uname -m)" in
  arm64) arch="arm64" ;;
  x86_64) arch="x86_64" ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

# Real libmpv binaries are needed (they are Git LFS pointers in a fresh clone). Works with or
# without Git LFS installed; every file is checked against its recorded SHA-256.
./scripts/debridone/fetch-macos-runtime.sh "${arch}"

# The Node.js runtime for Manga's extension host: the pinned official build, checked against its
# published SHA-256 and unpacked under the build folder. Nothing is installed on this Mac.
./scripts/debridone/fetch-node-runtime.sh "${arch}"

if ! xcrun --find clang++ >/dev/null 2>&1; then
  echo "Xcode command-line tools are required. Install them with: xcode-select --install" >&2
  exit 1
fi
if ! java_bin="$(command -v "${JAVA_HOME:+${JAVA_HOME}/bin/}java" 2>/dev/null)" || ! "${java_bin}" -version >/dev/null 2>&1; then
  echo "A JDK 17 or newer is required. Install one (for example Temurin 17) or set JAVA_HOME." >&2
  exit 1
fi

# Never sign from this script.
export NUVIO_MACOS_SIGNING_IDENTITY=""
export NUVIO_MACOS_NOTARY_KEYCHAIN_PROFILE=""

./gradlew :composeApp:packageReleaseDmg \
  "-Pnuvio.macos.arch=${arch}" \
  -Pcompose.desktop.packaging.checkJdkVendor=false \
  --no-configuration-cache \
  "$@"

echo
echo "Unsigned development DMG(s):"
ls -lh composeApp/build/compose/binaries/main-release/dmg/*.dmg composeApp/build/compose/release-dmgs/*.dmg 2>/dev/null || true
app="$(find composeApp/build/compose/binaries/main-release/app -maxdepth 1 -name '*.app' | head -n 1)"
if [[ -n "${app}" ]]; then
  echo
  echo "Signature status of ${app}:"
  codesign -dv "${app}" 2>&1 | sed 's/^/  /' || true
fi
echo
echo "This build is not signed with a Developer ID or notarized. To open it the first time,"
echo "right-click the app and choose Open, or run: xattr -dr com.apple.quarantine /Applications/DebridOne.app"
