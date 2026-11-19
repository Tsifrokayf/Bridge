#!/bin/bash
# Builds Bridge.app and packs it into Bridge.dmg (macOS only: needs hdiutil).
# Usage: ./make-dmg.sh [universal]
set -euo pipefail
cd "$(dirname "$0")"
./build.sh "$@"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp -R Bridge.app "$STAGE/"
ln -s /Applications "$STAGE/Applications"

rm -f Bridge.dmg
hdiutil create -volname "Bridge" -srcfolder "$STAGE" -ov -format UDZO Bridge.dmg

echo
echo "Готово: $(pwd)/Bridge.dmg"
echo "Откройте его и перетащите Bridge в Applications."
