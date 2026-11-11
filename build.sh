#!/bin/bash
# Builds the phone app and the Mac agent, and assembles mac/build/ClipBridge.app.
#
#   ./build.sh               normal build (Mac app signed for this Mac only)
#   TEST=1 ./build.sh        debug phone app, bundled into the Mac app for USB mode
#   USB=1 ./build.sh         release phone app, bundled into the Mac app for USB mode
#   MAC_ONLY=1 ./build.sh    skip the phone app
#
# release.sh drives this with SIGN_ID (a Developer ID identity) and UNIVERSAL=1 (also build for
# Intel Macs). The version of both apps comes from the VERSION file.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
IFS=. read -r V_MAJOR V_MINOR V_PATCH <<< "$VERSION"
BUILD_NUMBER=$((V_MAJOR * 10000 + V_MINOR * 100 + V_PATCH))
SIGN_ID="${SIGN_ID:--}"

APK=""
if [ "${MAC_ONLY:-0}" != "1" ]; then
  echo "==> Phone app $VERSION"
  if [ "${TEST:-0}" = "1" ]; then
    (cd "$ROOT/android" && ./gradlew -q :app:assembleDebug)
    APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
  else
    (cd "$ROOT/android" && ./gradlew -q :app:assembleRelease)
    APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
  fi
fi

echo "==> Mac agent $VERSION"
build_arch() { # $1 = arm64 | x86_64 ; prints the path of the built binary
  local scratch="$ROOT/mac/.build-$1"
  local flags=(-c release --triple "$1-apple-macosx13.0" --scratch-path "$scratch")
  (cd "$ROOT/mac" && swift build "${flags[@]}" 2>&1 | grep -E "error|warning: unre|Build complete" >&2 || true)
  echo "$(cd "$ROOT/mac" && swift build "${flags[@]}" --show-bin-path)/ClipBridge"
}

# Assemble and sign outside the project folder: folders that macOS syncs (Documents, Desktop) tag
# bundles with extended attributes, and codesign refuses to sign a bundle that carries them.
OUT_DIR="${OUT_DIR:-$ROOT/mac/build}"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/clipbridge-build.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
APP="$STAGE/ClipBridge.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$APP/Contents/Library/LaunchAgents"

if [ "${UNIVERSAL:-0}" = "1" ]; then
  ARM="$(build_arch arm64)"
  INTEL="$(build_arch x86_64)"
  [ -x "$ARM" ] && [ -x "$INTEL" ] || { echo "Mac build failed" >&2; exit 1; }
  lipo -create "$ARM" "$INTEL" -output "$APP/Contents/MacOS/ClipBridge"
else
  BIN="$(build_arch "$(uname -m)")"
  [ -x "$BIN" ] || { echo "Mac build failed" >&2; exit 1; }
  cp "$BIN" "$APP/Contents/MacOS/ClipBridge"
fi

cp "$ROOT/mac/Info.plist" "$APP/Contents/Info.plist"
plutil -replace CFBundleShortVersionString -string "$VERSION" "$APP/Contents/Info.plist"
plutil -replace CFBundleVersion -string "$BUILD_NUMBER" "$APP/Contents/Info.plist"
cp "$ROOT/mac/Agent.plist" "$APP/Contents/Library/LaunchAgents/dev.clipbridge.mac.agent.plist"

# The phone app rides along only for USB mode, where the Mac installs it over the cable.
if [ -n "$APK" ] && { [ "${TEST:-0}" = "1" ] || [ "${USB:-0}" = "1" ]; }; then
  cp "$APK" "$APP/Contents/Resources/clipbridge.apk"
fi

xattr -cr "$APP"
# The hardened runtime is on in every build, so what is tested locally behaves like what ships.
if [ "$SIGN_ID" = "-" ]; then
  # Ad-hoc: runs on this Mac; other Macs' Gatekeeper will refuse it.
  codesign --force --options runtime --sign - --identifier dev.clipbridge.mac "$APP" 2>&1 | grep -v "replacing existing signature" || true
else
  # Developer ID with a secure timestamp, as notarization requires.
  codesign --force --options runtime --timestamp --sign "$SIGN_ID" --identifier dev.clipbridge.mac "$APP"
fi
codesign --verify --strict "$APP"

mkdir -p "$OUT_DIR"
rm -rf "$OUT_DIR/ClipBridge.app"
ditto --norsrc --noextattr --noqtn "$APP" "$OUT_DIR/ClipBridge.app"
echo "==> Built $OUT_DIR/ClipBridge.app ($(lipo -archs "$APP/Contents/MacOS/ClipBridge"))"
