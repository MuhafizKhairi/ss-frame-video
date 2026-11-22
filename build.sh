#!/bin/bash
# Build Frameshot.app dari source.
#   ./build.sh            → build release ke ./Frameshot.app
#   ./build.sh --install  → sekaligus salin ke /Applications
set -euo pipefail
cd "$(dirname "$0")"

APP="Frameshot.app"
echo "▸ Compile (release)…"
swift build -c release --product Frameshot
BIN="$(swift build -c release --product Frameshot --show-bin-path)/Frameshot"

echo "▸ Menyusun $APP…"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/Frameshot"

if [ ! -f build/AppIcon.icns ]; then
  echo "▸ Membuat ikon…"
  mkdir -p build
  swift scripts/make-icon.swift build/AppIcon.iconset
  iconutil -c icns build/AppIcon.iconset -o build/AppIcon.icns
fi
cp build/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>Frameshot</string>
  <key>CFBundleDisplayName</key><string>Frameshot</string>
  <key>CFBundleIdentifier</key><string>local.frameshot.app</string>
  <key>CFBundleExecutable</key><string>Frameshot</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.photography</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>CFBundleDocumentTypes</key>
  <array>
    <dict>
      <key>CFBundleTypeName</key><string>Video</string>
      <key>CFBundleTypeRole</key><string>Viewer</string>
      <key>LSHandlerRank</key><string>Alternate</string>
      <key>LSItemContentTypes</key>
      <array><string>public.movie</string><string>public.video</string><string>org.matroska.mkv</string></array>
    </dict>
  </array>
</dict>
</plist>
PLIST

echo "▸ Tanda tangan ad-hoc…"
codesign --force --deep --sign - "$APP" >/dev/null

if [ "${1:-}" = "--install" ]; then
  echo "▸ Menyalin ke /Applications…"
  rm -rf "/Applications/$APP"
  cp -R "$APP" /Applications/
  echo "✓ Terpasang di /Applications/$APP"
else
  echo "✓ Selesai: $(pwd)/$APP"
fi
