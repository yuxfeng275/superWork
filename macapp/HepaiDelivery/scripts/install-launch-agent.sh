#!/usr/bin/env bash
# 安装 launchd 用户级 LaunchAgent，实现开机自启（登录即启动）。
# 用法： ./scripts/install-launch-agent.sh [--uninstall]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LABEL="com.bu.hepai.delivery"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
APP_BINARY="$ROOT/dist/合拍投递.app/Contents/MacOS/HepaiDelivery"
FALLBACK_BINARY="$ROOT/.build/release/HepaiDelivery"

if [ "${1:-}" = "--uninstall" ]; then
    launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || launchctl unload "$PLIST" 2>/dev/null || true
    rm -f "$PLIST"
    echo "已卸载 $LABEL"
    exit 0
fi

if [ ! -x "$APP_BINARY" ] && [ ! -x "$FALLBACK_BINARY" ]; then
    echo "找不到可执行文件，先跑 ./scripts/make-app.sh" >&2
    exit 1
fi

PROGRAM="$APP_BINARY"
[ -x "$PROGRAM" ] || PROGRAM="$FALLBACK_BINARY"

mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$PROGRAM</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>ProcessType</key>
    <string>Interactive</string>
    <key>StandardErrorPath</key>
    <string>$HOME/Library/Application Support/HepaiDelivery/launchd.err.log</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST" 2>/dev/null || launchctl load "$PLIST"
echo "已加载 $PLIST（ProgramArguments = $PROGRAM）"
