#!/usr/bin/env bash
# Makes sure the bundled libmpv runtime for one macOS architecture is real binaries, not Git LFS
# pointer files.
#
#   ./scripts/debridone/fetch-macos-runtime.sh arm64|x86_64 [--root <repo dir>]
#
# Order of attempts:
#   1. Nothing to do if the files are already binaries.
#   2. `git lfs pull` from this repository, then from upstream Nuvio Desktop.
#   3. Without Git LFS: download each file over HTTPS from upstream's LFS media endpoint.
# In every case each file must match the SHA-256 recorded in its pointer, so a wrong or truncated
# download can never end up inside the app.
set -euo pipefail

UPSTREAM_REPO="NuvioMedia/NuvioDesktop"
# Refs to try on the media endpoint: the commit this fork is based on, then upstream's branch.
UPSTREAM_REFS=("0b283d0a393079c9f7e5ff4b4e825dba76ce80d4" "Dev")

arch="${1:-}"
case "${arch}" in
  arm64 | x86_64) ;;
  *) echo "Usage: $0 arm64|x86_64 [--root <repo dir>]" >&2; exit 2 ;;
esac
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "${2:-}" == "--root" && -n "${3:-}" ]]; then
  root="$(cd "$3" && pwd)"
fi
cd "${root}"

runtime="composeApp/src/desktopMain/native/macos/runtime/${arch}"
[[ -d "${runtime}" ]] || { echo "Missing directory: ${runtime}" >&2; exit 1; }

is_pointer() {
  [[ "$(head -c 40 "$1" 2>/dev/null)" == "version https://git-lfs.github.com/spec/"* ]]
}

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

pointers=()
while IFS= read -r file; do
  if is_pointer "${file}"; then pointers+=("${file}"); fi
done < <(find "${runtime}" -type f -name '*.dylib' | sort)

if (( ${#pointers[@]} == 0 )); then
  echo "libmpv runtime for ${arch} is already present."
  exit 0
fi
echo "Fetching ${#pointers[@]} libmpv runtime files for ${arch}..."

if command -v git-lfs >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  include="${runtime}/**"
  if git lfs pull --include="${include}" --exclude="" 2>/dev/null; then
    :
  else
    git remote get-url upstream >/dev/null 2>&1 ||
      git remote add upstream "https://github.com/${UPSTREAM_REPO}.git"
    git lfs fetch upstream --include="${include}" --exclude="" && git lfs checkout "${runtime}" || true
  fi
fi

remaining=0
for file in "${pointers[@]}"; do
  is_pointer "${file}" || continue
  oid="$(sed -n 's/^oid sha256:\([0-9a-f]\{64\}\)$/\1/p' "${file}")"
  [[ -n "${oid}" ]] || { echo "Unreadable LFS pointer: ${file}" >&2; exit 1; }
  tmp="${file}.download"
  ok=false
  for ref in "${UPSTREAM_REFS[@]}"; do
    url="https://media.githubusercontent.com/media/${UPSTREAM_REPO}/${ref}/${file}"
    if curl -fsSL --retry 3 --retry-delay 2 -o "${tmp}" "${url}" && [[ "$(sha256_of "${tmp}")" == "${oid}" ]]; then
      ok=true
      break
    fi
  done
  if [[ "${ok}" == true ]]; then
    mv "${tmp}" "${file}"
  else
    rm -f "${tmp}"
    echo "Could not fetch a verified copy of ${file}" >&2
    remaining=$((remaining + 1))
  fi
done

if (( remaining > 0 )); then
  echo "${remaining} runtime file(s) are still missing. Install Git LFS (brew install git-lfs) and run this again." >&2
  exit 1
fi
echo "libmpv runtime for ${arch} is ready and verified."
