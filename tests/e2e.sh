#!/bin/bash
# End-to-end checks against the real phone and the real Mac pasteboard.
#
# Needs: the phone connected over adb and unlocked, ClipBridge running on the Mac and connected, and
# the DEBUG phone app installed (adb install -r android/app/build/outputs/apk/debug/app-debug.apk),
# because it uses that build's TestCopyActivity to make copies the way an ordinary app would.
# Reinstall the release app afterwards. NOTE: this overwrites the clipboard on both devices.
export LANG=en_US.UTF-8
S="$(cd "$(dirname "$0")" && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
# Scratch files live outside Documents/Desktop/Downloads so the test never depends on a macOS
# file-access prompt for ClipBridge.
W="${TMPDIR:-/tmp}/clipbridge-e2e"; A="$W/assets"; PB="$W/pbtool"
mkdir -p "$A"
[ -x "$PB" ] && [ "$PB" -nt "$S/pbtool.swift" ] || swiftc -O "$S/pbtool.swift" -o "$PB" || exit 1
[ -f "$A/big.png" ] || python3 - "$A" <<'PY'
import sys, zlib, struct, os, random
d = sys.argv[1]
def png(path, w, h, seed):
    random.seed(seed)
    raw = b''.join(b'\x00' + bytes(random.getrandbits(8) for _ in range(w * 3)) for _ in range(h))
    def chunk(t, data): return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b''))
png(d + "/small.png", 320, 200, 1)
png(d + "/big.png", 1800, 1200, 2)  # ~6.5 MB of noise: exercises chunking
open(d + "/notes.txt", "w").write("clipbridge file test\n" * 50)
open(d + "/archive.bin", "wb").write(os.urandom(3_000_000))
PY
pass=0; fail=0
ok()  { echo "PASS  $1"; pass=$((pass+1)); }
bad() { echo "FAIL  $1 — $2"; fail=$((fail+1)); }
sha16() { shasum -a 256 | cut -c1-16; }
phone() { $ADB shell 'CLASSPATH=$(pm path dev.clipbridge | cut -d: -f2) app_process / dev.clipbridge.daemon.Tool get' | tr -d '\r'; }
phone_uri_sha() { $ADB shell "content read --uri '$1' | sha256sum" | cut -c1-16; }
WAIT=${WAIT:-1.3}

echo "--- Mac -> phone ---"
T="mac text $RANDOM — ünïcödé ✓ 日本語"
"$PB" text "$T"; sleep $WAIT; P=$(phone)
echo "$P" | grep -q "sha256=$(printf '%s' "$T" | sha16)" && ok "M1 unicode text" || bad "M1 unicode text" "$P"

T="rich $RANDOM"
"$PB" html "$T" "<b>$T</b>"; sleep $WAIT; P=$(phone)
echo "$P" | grep -q "sha256=$(printf '%s' "$T" | sha16)" && echo "$P" | grep -q "html len=" && ok "M2 text + html" || bad "M2 text + html" "$P"

"$PB" png "$A/small.png"; sleep 2.5; P=$(phone); U=$(echo "$P" | sed -n 's/.*uri=//p' | head -1)
[ -n "$U" ] && echo "$P" | grep -q "mimes=image/png" && [ "$(phone_uri_sha "$U")" = "$(sha16 < "$A/small.png")" ] && ok "M3 image (190 KB PNG), byte-identical" || bad "M3 image" "$P"

"$PB" png "$A/big.png"; sleep 5; P=$(phone); U=$(echo "$P" | sed -n 's/.*uri=//p' | head -1)
[ -n "$U" ] && [ "$(phone_uri_sha "$U")" = "$(sha16 < "$A/big.png")" ] && ok "M4 large image (6.5 MB), byte-identical" || bad "M4 large image" "$P"

"$PB" tiff "$A/small.png"; sleep 2.5; P=$(phone)
echo "$P" | grep -q "mimes=image/png" && echo "$P" | grep -q "uri=content://dev.clipbridge.clips" && ok "M5 TIFF-only image converted to PNG" || bad "M5 tiff" "$P"

"$PB" files "$A/notes.txt"; sleep 2.5; P=$(phone); U=$(echo "$P" | sed -n 's/.*uri=//p' | head -1)
echo "$U" | grep -q "notes.txt" && [ "$(phone_uri_sha "$U")" = "$(sha16 < "$A/notes.txt")" ] && ok "M6 file copied in Finder" || bad "M6 file" "$P"

"$PB" files "$A/notes.txt" "$A/archive.bin"; sleep 4; P=$(phone)
U1=$(echo "$P" | sed -n 's/^item 0:.*uri=//p'); U2=$(echo "$P" | sed -n 's/^item 1:.*uri=//p')
[ -n "$U2" ] && [ "$(phone_uri_sha "$U1")" = "$(sha16 < "$A/notes.txt")" ] && [ "$(phone_uri_sha "$U2")" = "$(sha16 < "$A/archive.bin")" ] && ok "M7 two files (3 MB)" || bad "M7 two files" "$P"

T="secret $RANDOM"
"$PB" concealed "$T"; sleep $WAIT; P=$(phone)
echo "$P" | grep -q "sha256=$(printf '%s' "$T" | sha16)" && echo "$P" | grep -q "IS_SENSITIVE" && ok "M8 password-manager copy arrives flagged sensitive" || bad "M8 concealed" "$P"

BEFORE=$(phone)
"$PB" transient "should not sync $RANDOM"; sleep $WAIT
[ "$(phone)" = "$BEFORE" ] && ok "M9 transient clip is not synced" || bad "M9 transient" "changed"

echo "--- phone -> Mac ---"
copy() { $ADB shell "am start -n dev.clipbridge/.TestCopyActivity $*" >/dev/null 2>&1; }
T="phone text $RANDOM — ünïcödé ✓ 日本語"
copy "--es text '$T'"; sleep 2; I=$("$PB" info)
echo "$I" | grep -q "public.utf8-plain-text.*sha=$(printf '%s' "$T" | sha16)" && ok "P1 unicode text" || bad "P1 unicode text" "$I"

T="phone rich $RANDOM"
copy "--es text '$T' --es html '<i>$T</i>'"; sleep 2; I=$("$PB" info)
echo "$I" | grep -q "public.utf8-plain-text.*sha=$(printf '%s' "$T" | sha16)" && echo "$I" | grep -q "public.html" && ok "P2 text + html" || bad "P2 html" "$I"

copy "--ez image true"; sleep 3.5; I=$("$PB" info)
PS=$($ADB shell 'run-as dev.clipbridge sh -c "sha256sum files/test/*.png"' | cut -c1-16)
echo "$I" | grep -q "public.png.*sha=$PS" && echo "$I" | grep -q "public.tiff" && ok "P3 image copied in an app, byte-identical PNG + TIFF on Mac" || bad "P3 image" "$I / phone=$PS"

copy "--ez file true"; sleep 3.5; I=$("$PB" info)
PS=$($ADB shell 'run-as dev.clipbridge sh -c "sha256sum files/test/*.pdf"' | cut -c1-16)
echo "$I" | grep -q "^file report-.*\.pdf.*sha=$PS" && ok "P4 file (300 KB PDF) pastes as a file with its name" || bad "P4 file" "$I / phone=$PS"

T="phone secret $RANDOM"
copy "--es text '$T' --ez sensitive true"; sleep 2; I=$("$PB" info)
echo "$I" | grep -q "sha=$(printf '%s' "$T" | sha16)" && echo "$I" | grep -q "ConcealedType" && ok "P5 sensitive copy is hidden from Mac clipboard history apps" || bad "P5 sensitive" "$I"

echo "--- no echo loops ---"
sleep 1.5
ML=$(grep -c "local copy" ~/Library/Logs/ClipBridge/clipbridge.log); sleep 2
[ "$(grep -c "local copy" ~/Library/Logs/ClipBridge/clipbridge.log)" = "$ML" ] && ok "E1 clipboard is quiet after syncing (no ping-pong)" || bad "E1 echo" "Mac keeps re-sending"

echo; echo "$pass passed, $fail failed"
