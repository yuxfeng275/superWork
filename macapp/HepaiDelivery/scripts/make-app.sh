#!/usr/bin/env bash
# 把 swift build 的产物包成 合拍投递.app（菜单栏 App，无 Dock 图标）。
# 用法： ./scripts/make-app.sh [--no-sign]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APP_NAME="合拍投递"
BUNDLE_ID="com.bu.hepai.delivery"
BUILD_DIR="$ROOT/.build/release"
DIST_DIR="$ROOT/dist"
APP_BUNDLE="$DIST_DIR/$APP_NAME.app"

echo "==> swift build -c release"
swift build -c release

echo "==> 组装 $APP_BUNDLE"
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_BUNDLE/Contents/MacOS" "$APP_BUNDLE/Contents/Resources"

cp "$BUILD_DIR/HepaiDelivery" "$APP_BUNDLE/Contents/MacOS/HepaiDelivery"
cp "$ROOT/scripts/Info.plist" "$APP_BUNDLE/Contents/Info.plist"
printf 'APPL????' > "$APP_BUNDLE/Contents/PkgInfo"

# 菜单栏图标用系统符号，这里只放一个占位说明，避免 App 图标的空洞观感。
if [ -f "$ROOT/scripts/AppIcon.icns" ]; then
    cp "$ROOT/scripts/AppIcon.icns" "$APP_BUNDLE/Contents/Resources/AppIcon.icns"
fi

if [ "${1:-}" != "--no-sign" ]; then
    echo "==> ad-hoc 签名"
    codesign --force --deep -s - "$APP_BUNDLE"
    codesign --verify --verbose=2 "$APP_BUNDLE"
fi

cat <<EOF

完成： $APP_BUNDLE

跑起来：
  open "$APP_BUNDLE"

开机自启（写完 launchd plist 后 launchctl load）：
  $ROOT/scripts/install-launch-agent.sh
EOF
