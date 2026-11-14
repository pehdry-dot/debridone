#!/bin/sh
# Start the DebridOne dev server. Uses the Node.js bundled in .tools/ when no system Node is installed.
cd "$(dirname "$0")" || exit 1
if ! command -v node >/dev/null 2>&1 && [ -x ".tools/node/bin/node" ]; then
  PATH="$PWD/.tools/node/bin:$PATH"; export PATH
fi
[ -d node_modules ] || npm install
exec npm run dev -- --open
