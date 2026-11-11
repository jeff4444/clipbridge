#!/bin/bash
# Builds the two downloads people install:
#
#   dist/ClipBridge-<version>.dmg   the Mac app (drag to Applications)
#   dist/ClipBridge-<version>.apk   the phone app
#   dist/SHA256SUMS.txt             checksums to publish next to them
#
# Signing is picked up automatically and anything missing is reported, not fatal:
#
#   Mac    .env (or the environment) names the Developer ID certificate and the notarytool profile:
#            SIGNING_IDENTITY        "Developer ID Application: Name (TEAMID)"
#            SIGNING_CERTIFICATE_ID  the certificate's SHA-1 (preferred when both are set)
#            NOTARY_PROFILE          a profile saved with `xcrun notarytool store-credentials`
#          The certificate and its private key must be in this Mac's keychain. If they are not, the
#          app is signed ad-hoc: fine here, but Gatekeeper on other Macs will refuse it.
#   Phone  the key made by scripts/new-android-key.sh. Without it the APK carries the Android debug
#          key: fine for testing, not for handing out.
#
#   STRICT=1 ./release.sh      fail instead of warning when either signature is missing
#   UNIVERSAL=1 ./release.sh   also build the Mac app for Intel Macs (untested: needs an Intel Mac)
#   SKIP_MAC=1 / SKIP_PHONE=1  build only one of the two
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
DIST="$ROOT/dist"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/clipbridge-release.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$DIST"

MAC_STATE="skipped"
PHONE_STATE="skipped"
PROBLEMS=0
warn() { echo "!!  $*" >&2; PROBLEMS=$((PROBLEMS + 1)); }

# Reads KEY=value lines for the three settings above. Values already in the environment win.
load_env() {
  local file="$ROOT/.env" line key val
  [ -f "$file" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|\#*) continue ;; esac
    line="${line#export }"
    key="$(printf '%s' "${line%%=*}" | tr -d '[:space:]')"
    val="${line#*=}"
    val="${val#"${val%%[![:space:]]*}"}"
    val="${val%"${val##*[![:space:]]}"}"
    case "$val" in
      \"*\") val="${val#\"}"; val="${val%\"}" ;;
      \'*\') val="${val#\'}"; val="${val%\'}" ;;
    esac
    case "$key" in
      SIGNING_IDENTITY|SIGNING_CERTIFICATE_ID|NOTARY_PROFILE)
        [ -n "${!key:-}" ] || printf -v "$key" '%s' "$val" ;;
    esac
  done < "$file"
}

# Prints the identity to hand to codesign, or nothing if no usable Developer ID is in the keychain.
find_signing_identity() {
  local available want
  available="$(security find-identity -v -p codesigning 2>/dev/null || true)"
  for want in "${SIGNING_CERTIFICATE_ID:-}" "${SIGNING_IDENTITY:-}"; do
    [ -n "$want" ] || continue
    if grep -qiF -- "$want" <<< "$available"; then
      printf '%s' "$want"
      return 0
    fi
  done
  if [ -z "${SIGNING_CERTIFICATE_ID:-}${SIGNING_IDENTITY:-}" ]; then
    sed -n 's/.*"\(Developer ID Application: [^"]*\)".*/\1/p' <<< "$available" | sed -n 1p
  fi
}

# Uploads a file to Apple's notary service and waits for the verdict.
notarize() { # $1 = file
  local out
  if ! out="$(xcrun notarytool submit "$1" --keychain-profile "$NOTARY_PROFILE" --wait --output-format json 2>&1)"; then
    echo "$out" >&2
    return 1
  fi
  if grep -Eq '"status"[[:space:]]*:[[:space:]]*"Accepted"' <<< "$out"; then
    return 0
  fi
  echo "$out" >&2
  echo "Apple did not accept $(basename "$1"). See why with:" >&2
  echo "  xcrun notarytool log <id from above> --keychain-profile \"\$NOTARY_PROFILE\"" >&2
  return 1
}

build_mac() {
  local sign_id app dmg stage
  load_env
  sign_id="$(find_signing_identity)"
  app="$WORK/app/ClipBridge.app"
  dmg="$WORK/ClipBridge-$VERSION.dmg"

  if [ -n "$sign_id" ]; then
    echo "==> Mac: signing with your Developer ID certificate"
  elif [ -n "${SIGNING_CERTIFICATE_ID:-}${SIGNING_IDENTITY:-}" ]; then
    warn "Mac: the certificate named in .env is not in this Mac's keychain (with its private key). Signing ad-hoc instead."
  else
    warn "Mac: no Developer ID certificate found. Signing ad-hoc instead."
  fi

  MAC_ONLY=1 OUT_DIR="$WORK/app" SIGN_ID="${sign_id:--}" UNIVERSAL="${UNIVERSAL:-0}" "$ROOT/build.sh"

  local notarized=0
  if [ -n "$sign_id" ]; then
    if [ -z "${NOTARY_PROFILE:-}" ]; then
      warn "Mac: NOTARY_PROFILE is not set, so the app was signed but not notarized."
    else
      # Notarize and staple the app itself first, so it also passes Gatekeeper offline once it has
      # been dragged out of the disk image.
      echo "==> Mac: notarizing the app (this waits for Apple, usually a few minutes)"
      ditto -c -k --keepParent "$app" "$WORK/ClipBridge.zip"
      if notarize "$WORK/ClipBridge.zip" && xcrun stapler staple "$app"; then
        notarized=1
      else
        warn "Mac: notarization failed, so the disk image is signed but not notarized."
      fi
    fi
  fi

  echo "==> Mac: building the disk image"
  stage="$WORK/dmg"
  mkdir -p "$stage"
  ditto "$app" "$stage/ClipBridge.app"
  ln -s /Applications "$stage/Applications"
  # (newer macOS prints a deprecation notice for this form of hdiutil; it still works everywhere)
  hdiutil create -volname "ClipBridge" -srcfolder "$stage" -fs HFS+ -format UDZO -ov "$dmg" 2>&1 >/dev/null \
    | grep -v "is deprecated" >&2 || true
  [ -s "$dmg" ] || { echo "Could not create the disk image" >&2; exit 1; }

  if [ -n "$sign_id" ]; then
    codesign --force --timestamp --sign "$sign_id" "$dmg"
    if [ "$notarized" = "1" ]; then
      echo "==> Mac: notarizing the disk image"
      if notarize "$dmg" && xcrun stapler staple "$dmg"; then
        MAC_STATE="signed with Developer ID and notarized"
      else
        warn "Mac: the app is notarized but the disk image is not."
        MAC_STATE="app notarized, disk image NOT notarized"
      fi
    else
      MAC_STATE="signed with Developer ID, NOT notarized (Gatekeeper will block it)"
    fi
  else
    MAC_STATE="AD-HOC SIGNED: runs on this Mac only, Gatekeeper blocks it elsewhere"
  fi

  rm -f "$DIST/ClipBridge-$VERSION.dmg"
  cp "$dmg" "$DIST/ClipBridge-$VERSION.dmg"
}

build_phone() {
  local apk out signer sdk apksigner
  echo "==> Phone app $VERSION"
  (cd "$ROOT/android" && ./gradlew -q :app:assembleRelease)
  apk="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
  out="$DIST/ClipBridge-$VERSION.apk"
  cp "$apk" "$out"

  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  apksigner="$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort | tail -1 || true)"
  if [ -z "$apksigner" ]; then
    warn "Phone: apksigner not found, so the APK's signature was not checked."
    PHONE_STATE="signature not checked"
    return 0
  fi
  signer="$("$apksigner" verify --print-certs "$out" 2>/dev/null | sed -n 's/^Signer #1 certificate DN: //p' || true)"
  case "$signer" in
    "") warn "Phone: the APK's signature did not verify."; PHONE_STATE="SIGNATURE INVALID" ;;
    *"Android Debug"*)
      warn "Phone: signed with the Android debug key. Run scripts/new-android-key.sh once to create a release key."
      PHONE_STATE="DEBUG KEY: for testing only" ;;
    *) PHONE_STATE="signed with your release key ($signer)" ;;
  esac
}

[ "${SKIP_MAC:-0}" = "1" ] || build_mac
[ "${SKIP_PHONE:-0}" = "1" ] || build_phone

rm -rf "$DIST/licences" "$DIST/THIRD-PARTY-NOTICES.md"
cp -R "$ROOT/legal" "$DIST/licences"
(cd "$DIST" && rm -f SHA256SUMS.txt && shasum -a 256 ClipBridge-"$VERSION".* > SHA256SUMS.txt)

echo
echo "ClipBridge $VERSION"
[ "${SKIP_MAC:-0}" = "1" ]   || echo "  Mac    dist/ClipBridge-$VERSION.dmg  $(du -h "$DIST/ClipBridge-$VERSION.dmg" | cut -f1 | tr -d ' ')  $MAC_STATE"
[ "${SKIP_PHONE:-0}" = "1" ] || echo "  Phone  dist/ClipBridge-$VERSION.apk  $(du -h "$DIST/ClipBridge-$VERSION.apk" | cut -f1 | tr -d ' ')  $PHONE_STATE"
if [ "$PROBLEMS" -gt 0 ]; then
  echo
  echo "Not ready to hand out: $PROBLEMS item(s) above need attention."
  [ "${STRICT:-0}" = "1" ] && exit 1
fi
exit 0
