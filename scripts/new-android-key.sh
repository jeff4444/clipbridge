#!/bin/bash
# Creates the key that signs the phone app for release. Run it once, yourself, in a terminal.
#
# Android only installs an update if it is signed with the same key as the copy already on the
# phone. Lose this key (or its password) and nobody can update: they would have to uninstall and
# set up again. So back up ~/.clipbridge-signing somewhere safe, and never share it.
set -euo pipefail
DIR="$HOME/.clipbridge-signing"
STORE="$DIR/clipbridge-release.jks"
PROPS="$DIR/keystore.properties"
ALIAS="clipbridge"

if [ -e "$STORE" ]; then
  echo "A release key already exists at $STORE. Not replacing it." >&2
  exit 1
fi

KEYTOOL=""
for c in "${JAVA_HOME:-}/bin/keytool" "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" "$(command -v keytool || true)"; do
  if [ -n "$c" ] && [ -x "$c" ]; then KEYTOOL="$c"; break; fi
done
[ -n "$KEYTOOL" ] || { echo "keytool not found. Install Android Studio or a JDK." >&2; exit 1; }

read -r -p "Name to put in the certificate (you or your organisation) [ClipBridge]: " NAME
NAME="${NAME:-ClipBridge}"
case "$NAME" in *[,=+\<\>\#\;\"\\]*) echo "Please use a name without , = + < > # ; \" or \\" >&2; exit 1;; esac

while true; do
  read -r -s -p "Choose a password for the key (at least 6 characters): " CB_STOREPASS; echo
  read -r -s -p "Type it again: " AGAIN; echo
  if [ "$CB_STOREPASS" != "$AGAIN" ]; then echo "They did not match. Try again."; continue; fi
  if [ "${#CB_STOREPASS}" -lt 6 ]; then echo "Too short. Try again."; continue; fi
  case "$CB_STOREPASS" in *[[:space:]\\]*) echo "Please avoid spaces and backslashes. Try again."; continue;; esac
  break
done
export CB_STOREPASS

mkdir -p "$DIR"
chmod 700 "$DIR"
"$KEYTOOL" -genkeypair -keystore "$STORE" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=$NAME" \
  -storepass:env CB_STOREPASS -keypass:env CB_STOREPASS
chmod 600 "$STORE"

umask 177
{
  echo "storeFile=$STORE"
  echo "storePassword=$CB_STOREPASS"
  echo "keyAlias=$ALIAS"
  echo "keyPassword=$CB_STOREPASS"
} > "$PROPS"

echo
echo "Created $STORE"
echo "Release builds are now signed with it (settings in $PROPS)."
echo "Back up the folder $DIR and keep the password somewhere safe."
