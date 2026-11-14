#!/bin/bash
# Builds build/icon.icns (macOS app icon) from public/icon.svg using only tools
# that ship with macOS: qlmanage (SVG → PNG), sips (resize) and iconutil (icns).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
qlmanage -t -s 1024 -o "$TMP" public/icon.svg >/dev/null 2>&1
SRC="$TMP/icon.svg.png"
[ -f "$SRC" ] || { echo "could not render public/icon.svg"; exit 1; }
ICONSET="$TMP/DebridOne.iconset"
mkdir -p "$ICONSET"
for s in 16 32 128 256 512; do
  sips -z "$s" "$s" "$SRC" --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
  d=$((s * 2))
  sips -z "$d" "$d" "$SRC" --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o build/icon.icns
echo "wrote build/icon.icns"
