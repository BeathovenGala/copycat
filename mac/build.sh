#!/bin/bash
# Builds a universal CopyCat.app and dist/CopyCat-mac.zip. Runs on macOS (CI) only.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=$(tr -d '[:space:]' < ../VERSION)
ARCHS=(--arch arm64 --arch x86_64)
swift build -c release "${ARCHS[@]}"
BIN="$(swift build -c release "${ARCHS[@]}" --show-bin-path)/CopyCat"

APP=dist/CopyCat.app
rm -rf dist
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/CopyCat"
sed "s/__VERSION__/$VERSION/g" Info.plist > "$APP/Contents/Info.plist"
cp ../web/index.html "$APP/Contents/Resources/index.html"

ICONSET="$(mktemp -d)/AppIcon.iconset"
mkdir -p "$ICONSET"
for size in 16 32 128 256 512; do
    sips -z $size $size ../tools/icon-512.png --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    sips -z $((size * 2)) $((size * 2)) ../tools/icon-512.png --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

# Ad-hoc signature: required to run on Apple Silicon. Not notarized, so first launch needs right-click → Open.
codesign --force --deep -s - "$APP"
(cd dist && ditto -c -k --keepParent CopyCat.app CopyCat-mac.zip)
echo "Built $APP ($VERSION)"
