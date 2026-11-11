#!/bin/bash
# Stops and removes ClipBridge from this Mac, and from the phone if it is reachable over adb.
set -uo pipefail
for APP in "$HOME/Applications/ClipBridge.app" "/Applications/ClipBridge.app"; do
  [ -x "$APP/Contents/MacOS/ClipBridge" ] || continue
  # Only the app itself can remove its own login item.
  [ -d "$APP/Contents/Library/LaunchAgents" ] && "$APP/Contents/MacOS/ClipBridge" --unregister-login-item
  pkill -x ClipBridge 2>/dev/null
  rm -rf "$APP"
done
# The hand-written LaunchAgent used by early builds.
launchctl bootout "gui/$(id -u)/dev.clipbridge.agent" 2>/dev/null
rm -f "$HOME/Library/LaunchAgents/dev.clipbridge.agent.plist"
pkill -x ClipBridge 2>/dev/null

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
if [ -x "$ADB" ]; then
  for s in $("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}'); do
    "$ADB" -s "$s" shell "pkill -f '^sh /data/local/tmp/clipbridge/run\\.sh\$'; pkill -f '^clipbridged'; rm -rf /data/local/tmp/clipbridge"
    "$ADB" -s "$s" uninstall dev.clipbridge >/dev/null 2>&1
    "$ADB" -s "$s" reverse --remove tcp:47823 >/dev/null 2>&1
    echo "Removed from phone $s"
  done
fi
echo "ClipBridge removed. Settings and pairing key remain in ~/Library/Application Support/ClipBridge (delete to reset)."
