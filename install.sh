#!/bin/bash
# Installs the locally built ClipBridge.app into ~/Applications and starts it in the background.
#
# A build signed with a Developer ID registers itself to start at login, exactly as a downloaded
# copy does. A locally signed (ad-hoc) build gets a plain LaunchAgent instead: macOS ties a
# self-registered login item to the exact build, and every local rebuild is a new one.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/mac/build/ClipBridge.app"
DEST="$HOME/Applications/ClipBridge.app"
LABEL="dev.clipbridge.agent"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

[ -d "$SRC" ] || MAC_ONLY=1 "$ROOT/build.sh"

# Stop whatever is running, whichever way it was started.
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$PLIST"
pkill -x ClipBridge 2>/dev/null || true
sleep 1

mkdir -p "$HOME/Applications" "$HOME/Library/LaunchAgents"
rm -rf "$DEST"
# Without the extended attributes that synced folders add: they would break the code signature.
ditto --norsrc --noextattr --noqtn "$SRC" "$DEST"
codesign --verify --strict "$DEST"

SIGNATURE="$(codesign -dv "$DEST" 2>&1 || true)"
if [[ "$SIGNATURE" == *"TeamIdentifier=not set"* ]]; then
  # Locally signed. Drop any self-registered login item left by an earlier install, then use a
  # plain LaunchAgent: start at login, restart after a crash, stay stopped after Quit.
  "$DEST/Contents/MacOS/ClipBridge" --unregister-login-item || true
  cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>$LABEL</string>
    <key>ProgramArguments</key>
    <array><string>$DEST/Contents/MacOS/ClipBridge</string></array>
    <key>AssociatedBundleIdentifiers</key><string>dev.clipbridge.mac</string>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
    <key>ProcessType</key><string>Interactive</string>
    <key>LimitLoadToSessionType</key><string>Aqua</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>CLIPBRIDGE_AGENT</key><string>1</string>
        <key>ANDROID_HOME</key><string>${ANDROID_HOME:-$HOME/Library/Android/sdk}</string>
    </dict>
    <key>StandardErrorPath</key><string>/dev/null</string>
    <key>StandardOutPath</key><string>/dev/null</string>
</dict>
</plist>
PLIST
  launchctl bootstrap "gui/$(id -u)" "$PLIST"
else
  open "$DEST" # a Developer ID build registers itself as a login item on launch
fi

echo "ClipBridge installed and running. Look for the clipboard icon in the menu bar."
echo "Log: ~/Library/Logs/ClipBridge/clipbridge.log"
