#!/bin/bash
# Builds Bridge.app (menu-bar app + Share extension) next to this script.
# Usage: ./build.sh            — native architecture
#        ./build.sh universal  — arm64 + x86_64 (needs full Xcode)
set -euo pipefail
cd "$(dirname "$0")"

ARCH_FLAGS=()
ARCHS=("$(uname -m)")
if [ "${1:-}" = "universal" ]; then ARCH_FLAGS=(--arch arm64 --arch x86_64); ARCHS=(arm64 x86_64); fi

swift build -c release ${ARCH_FLAGS[@]+"${ARCH_FLAGS[@]}"}
BIN="$(swift build -c release ${ARCH_FLAGS[@]+"${ARCH_FLAGS[@]}"} --show-bin-path)/Bridge"

APP="Bridge.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$BIN" "$APP/Contents/MacOS/Bridge"
cp Resources/Info.plist "$APP/Contents/Info.plist"

# ---- Share extension ("Поделиться" → Bridge) ----
# Built with swiftc: an app extension's entry point is _NSExtensionMain, not main().
APPEX="$APP/Contents/PlugIns/BridgeShare.appex"
mkdir -p "$APPEX/Contents/MacOS"
SHARE_BINS=()
for A in "${ARCHS[@]}"; do
  OUT="$(mktemp -d)/BridgeShare-$A"
  swiftc -O -parse-as-library -application-extension -module-name BridgeShare \
    -target "$A-apple-macos13.0" ShareExtension/ShareViewController.swift \
    -Xlinker -e -Xlinker _NSExtensionMain -o "$OUT"
  SHARE_BINS+=("$OUT")
done
if [ "${#SHARE_BINS[@]}" -gt 1 ]; then
  lipo -create "${SHARE_BINS[@]}" -output "$APPEX/Contents/MacOS/BridgeShare"
else
  cp "${SHARE_BINS[0]}" "$APPEX/Contents/MacOS/BridgeShare"
fi
cp ShareExtension/Info.plist "$APPEX/Contents/Info.plist"

# Ad-hoc signatures (inside-out): enough for local use; they give the app a stable identity
# for notifications / local-network prompts, and the extension needs its sandbox entitlements.
codesign --force --sign - --entitlements ShareExtension/BridgeShare.entitlements "$APPEX"
codesign --force --sign - "$APP"

# Register the extension and the Services entry right away (best effort; harmless if it fails).
pluginkit -a "$APPEX" 2>/dev/null || true
/System/Library/CoreServices/pbs -update 2>/dev/null || true

echo
echo "Готово: $(pwd)/$APP"
echo "Запуск:  open \"$(pwd)/$APP\""
echo "Меню «Поделиться»: включите Bridge в Системные настройки → Конфиденциальность и безопасность → Расширения → Общий доступ."
