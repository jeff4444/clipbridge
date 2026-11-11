# ClipBridge

Seamless clipboard sync between a Mac and an Android phone. Copy on one, paste on the other —
text, rich text, images and files — with nothing to tap on either device, and no cable.

Developed and tested against macOS 27 and a Pixel 6a on Android 17 (unrooted).

## How it works

```
┌──────────── Mac ────────────┐                    ┌─────────── Android ───────────┐
│ ClipBridge.app (menu bar)   │  encrypted TCP     │ clipbridged  (shell user)     │
│  • watches the pasteboard   │◄──────────────────►│  • real clipboard-change      │
│  • listens on port 47823    │  over Wi-Fi        │    events, reads in background│
│  • shows a pairing QR code  │                    │  • dials the Mac, reconnects  │
└─────────────────────────────┘                    │ ClipBridge app                │
                                                   │  • one-time setup, starts the │
                                                   │    helper, restarts it at boot│
                                                   │  • serves pasted images/files │
                                                   └───────────────────────────────┘
```

Android does not let ordinary apps read the clipboard in the background. ClipBridge avoids the
usual workarounds (accessibility services, invisible focus-stealing windows, "tap to send"
notifications) by running a small helper as the **shell user**, which is allowed to listen for
clipboard changes — so capture is exact and instant.

The phone app starts that helper itself, with no computer: it pairs once with the phone's **own**
adb daemon over Android's *Wireless debugging* (the technique Shizuku uses) and from then on can
launch the helper whenever it is not running — after setup, after a reboot, and from a periodic
watchdog. The helper is not an app process, so battery optimisation never kills it.

The Mac side is a menu-bar agent with no window. Installed as a login item it starts at login and is
restarted by macOS if it crashes; choosing **Quit ClipBridge** (or `kill`) stops it until next login.

## Requirements

- A Mac with Apple silicon running macOS 13 or later
- An Android 12+ phone with **Developer options** enabled
- Both devices on the same Wi-Fi network

## Install from the downloads

`./release.sh` produces the two files people install (see [Releasing](#releasing)):

- **Mac:** open `ClipBridge-<version>.dmg`, drag ClipBridge into Applications, and open it from
  there. It adds itself to the menu bar, registers itself to start at login, and on first launch
  shows the pairing code. Nothing else to install.
- **Phone:** open `ClipBridge-<version>.apk` on the phone. Android asks once whether the browser or
  file manager you opened it from may install apps, and Play Protect may offer to scan it first.

Then follow [One-time setup](#one-time-setup).

To update, install the new version over the old one on each device. The phone's helper restarts
itself onto the new version within a few seconds; on the Mac, quit ClipBridge, replace it in
Applications and open it again. Pairing is kept.

## Build from source

Needs the Swift toolchain (Xcode or just the Command Line Tools), and the Android SDK with a JDK
(Android Studio provides both).

```bash
./build.sh      # builds the phone app and mac/build/ClipBridge.app
./install.sh    # copies the Mac app to ~/Applications and starts it in the background
```

Install the phone app from `android/app/build/outputs/apk/release/app-release.apk` (for example
`adb install -r <that file>`, or copy it to the phone and open it).

`./uninstall.sh` removes everything from the Mac and, if it is plugged in, the phone.

## One-time setup

1. **On the Mac:** click the ClipBridge menu-bar icon › **Pair a Phone…**. A QR code appears. It
   carries the shared key and how to reach this Mac.
2. **On the phone:** open ClipBridge › **Set up ClipBridge**, then follow its three steps:
   1. *Turn on Wireless debugging* (Developer options). Tap Allow for your network.
   2. *Scan the Mac code* shown in step 1.
   3. *Start pairing*. ClipBridge puts up a notification and opens Developer options. Go to
      Wireless debugging › **Pair device with pairing code**, swipe down the notification shade and
      type the six-digit code into the ClipBridge notification.

The code is typed into a notification on purpose: Android stops its pairing server the moment
Settings leaves the foreground, and the notification shade is the one place you can type without
that happening. The app does not wait for the dialog or watch for it. The one thing it needs besides
the code is the dialog's port, which Android picks afresh every time the dialog opens, so it looks
that up at the moment you send the code. If the lookup ever fails, type the code followed by the
port (the number after the colon in the dialog), for example `123456 34567`.

If setup is interrupted after the phone has accepted the code, tap *Start pairing* again: the app is
already authorised, so it finishes without a new code. *Run setup again* on the app's main screen
repeats setup later, for example to pair with a different Mac.

After this, nothing needs repeating. The pairing survives reboots and app updates.

## Everyday behaviour

- **Latency** is a fraction of a second in both directions.
- **Images** copied on the Mac (screenshots, "Copy Image") paste as images on Android, and the
  reverse. PNG and JPEG are transferred byte-for-byte; TIFF/HEIC are converted to PNG.
- **Files** copied in Finder arrive as files on the phone's clipboard; files copied on Android paste
  in Finder with their original names. Limit: 100 MB per copy, 20 files. Folders are not synced.
- **Phone asleep?** The Mac holds its latest copy and delivers it the moment the screen turns on,
  so copying on the Mac never wakes a sleeping phone.
- **Apart for a while?** When the devices reconnect, whichever one copied most recently wins.
  Restarting either side never pushes stale contents.
- **Passwords**: clips flagged as sensitive (password managers on either side) are synced but stay
  flagged, so Android hides the preview and Mac clipboard-history apps skip them. Clips flagged
  transient on the Mac are never sent.
- **Pause Syncing** in the menu stops both directions; nothing copied while paused is ever sent.

### After a phone restart

Nothing to do, as long as Wireless debugging is still on. Once the phone has been unlocked, a boot
job reconnects to the on-device adb with the saved key and starts the helper again (about three
seconds on the Pixel 6a). A watchdog job repeats the check roughly every 20 minutes, and opening the
app does it immediately.

On the Pixel 6a / Android 17, Wireless debugging stayed on across reboots. If a phone turns it off,
switch it back on and open ClipBridge; there is no need to pair again.

### The "Shell pasted from your clipboard" toast

Android shows this each time the helper reads something you copied. It is Android's standard
clipboard-access notice. To hide it: **Settings › Security & privacy › More security & privacy ›
Show clipboard access** (off). Note that this hides the notice for every app.

### macOS permissions

- **Paste from Other Apps** (*System Settings › Privacy & Security*): if macOS asks whether
  ClipBridge may read the clipboard, set it to **Allow**. The menu shows a shortcut if access is
  restricted.
- **Files and Folders**: the first time you copy a file that lives in Documents, Desktop or
  Downloads, macOS asks whether ClipBridge may read that folder. Until you answer, only that file
  copy waits; everything else keeps syncing. If you decline, such files are skipped and the menu
  says so.
- A build you make yourself is signed for this Mac only. Rebuilding changes its signature, so macOS
  may ask again. Downloads signed with a Developer ID keep their permissions across updates.

## Security

- The Mac generates a random 256-bit key on first launch
  (`~/Library/Application Support/ClipBridge/config.json`, mode 0600). The pairing QR code contains
  that key: anyone who can photograph it can pair, so show it only when pairing.
- Every connection derives fresh session keys from that key and encrypts all traffic with
  AES-256-GCM. A device without the key cannot read or inject clipboard contents.
- On the phone the key is stored in the app's private storage and in a directory only the shell
  user can read.
- The phone app holds an adb key authorised on its own device. Wireless debugging has to stay
  enabled, which also leaves adb reachable (to paired keys only) on the networks you allowed.
- Logs record sizes and types only, never clipboard contents.
- Images/files received on the phone are served by an exported content provider under a random
  64-bit id, because the shell user cannot issue per-app URI grants. Only the last few are kept.

## Troubleshooting

| Where | What |
| --- | --- |
| Mac log | `~/Library/Logs/ClipBridge/clipbridge.log` (also **Show Log** in the menu) |
| Phone log | `adb shell cat /data/local/tmp/clipbridge/daemon.log` |
| App-side log | `adb logcat -s ClipBridge` (pairing, boot reconnect, watchdog) |
| Phone clipboard | `adb shell 'CLASSPATH=$(pm path dev.clipbridge \| cut -d: -f2) app_process / dev.clipbridge.daemon.Tool get'` |
| Helper process | `adb shell 'ps -A \| grep clipbridged'` |
| Force the watchdog | `adb shell cmd jobscheduler run -f dev.clipbridge 101` |

## Releasing

```bash
./release.sh    # writes dist/ClipBridge-<version>.dmg, .apk and SHA256SUMS.txt
```

The version of both apps comes from the `VERSION` file. The script signs with whatever it finds,
says plainly what is missing, and still produces files you can test with. `STRICT=1 ./release.sh`
fails instead, for the real thing.

**Mac.** Other people's Macs only open apps that are signed with an Apple *Developer ID* certificate
and notarized by Apple; anything else is blocked by Gatekeeper. The script reads three settings from
`.env` (or the environment):

| Setting | What it is |
| --- | --- |
| `SIGNING_IDENTITY` | The certificate's name, `Developer ID Application: Name (TEAMID)` |
| `SIGNING_CERTIFICATE_ID` | Its SHA-1 fingerprint (used in preference to the name) |
| `NOTARY_PROFILE` | A notarytool profile saved in your keychain |

The certificate **and its private key** have to be in this Mac's keychain
(`security find-identity -v -p codesigning` lists what is usable). The notary profile is created
once, by you, with `xcrun notarytool store-credentials <profile name>`; it asks for your Apple ID,
team ID and an app-specific password and keeps them in the keychain. With both in place the script
signs with the hardened runtime, notarizes and staples the app, then builds, signs, notarizes and
staples the disk image. Without them it signs ad-hoc and tells you so.

**Phone.** Android installs an update only if it is signed with the same key as the copy already
installed. Create your release key once, in a terminal:

```bash
scripts/new-android-key.sh
```

It asks for a name and a password and writes the key to `~/.clipbridge-signing/`, outside the
project. Back that folder up: without the key and its password you can never ship an update that
installs over an existing copy. Until the key exists, release builds are signed with the Android
debug key, which is fine on your own phone only. Moving a phone from a debug-signed build to a
release-signed one means uninstalling ClipBridge and setting it up again.

**Intel Macs.** `UNIVERSAL=1 ./release.sh` also builds for Intel. That slice builds but has never
been run (it needs an Intel Mac to test), so it is off by default.

**Licences.** The phone app contains open-source code under the Apache 2.0, MIT, LGPL 3.0 and GPL
2.0 with Classpath exception licences. `legal/` holds the notices and the licence texts; they are
packed into the app (main screen › *Open-source licences*) and copied to `dist/`. The LGPL library
(the pairing-code handshake) must stay replaceable by the people you give the app to; publishing
ClipBridge's source is the simplest way to meet that. Have this checked before you distribute.

**Not done:** there is nowhere to download from yet, and the apps do not check for updates.

## Developing

**Starting at login.** A Developer ID build registers itself as a login item the first time it is
opened from an Applications folder, and steps aside for the copy macOS then starts (the one that is
restarted after a crash). macOS ties such a login item to the identity of the build that registered
it. A locally signed build is a new identity every time it is rebuilt, and macOS then refuses to
launch the login item, so `install.sh` gives local builds a plain LaunchAgent instead.
`"selfRegister": true` in the Mac's `config.json` makes a local build register itself anyway, to
exercise that code; the app recovers from the refusal by itself after about 40 seconds.

**USB mode.** Setting `"usbSetup": true` in the Mac's `config.json` brings back the original
developer flow: whenever adb can see the phone, the Mac installs the bundled phone app, pushes the
pairing config and starts the helper over the cable. It is off by default, and the phone app is only
bundled into the Mac app when it is built with `USB=1 ./build.sh` (or `TEST=1` for the debug app).

**Tests.** `tests/e2e.sh` runs 15 end-to-end checks against the real devices (text, HTML, images,
files, sensitive and transient clips, echo suppression). It needs the debug phone app, which adds
test-only entry points, so do not leave that build installed:

```bash
(cd android && ./gradlew :app:assembleDebug)
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
tests/e2e.sh        # phone unlocked and connected; overwrites both clipboards
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

**Layout.**

```
android/   Gradle project. app/src/main/java/dev/clipbridge/
             daemon/   the shell-user helper (Main, ShellClipboard, Wire, …)
             adb/      on-device adb: key store, connection manager, pairing + launch
             SetupActivity, PairingService, PairReplyReceiver   one-time setup
             BootReceiver, WatchdogJob, Reconnector             keeping the helper alive
             MainActivity, ClipProvider, Prefs
           app/src/debug/   test hooks, debug builds only
mac/       Swift package: menu-bar agent (Server/Session, Pasteboard, Pairing, LoginItem,
           AppDelegate; Adb for USB mode). Agent.plist is the bundled login item.
tests/     end-to-end test and its Mac pasteboard helper
legal/     third-party notices and licence texts (packed into the phone app)
scripts/   new-android-key.sh
build.sh, install.sh, uninstall.sh, release.sh, VERSION
PROTOCOL.md   wire format
```

## Known limitations and open items

- Same Wi-Fi network only; there is no relay for syncing over the internet.
- The phone needs Developer options and Wireless debugging left on.
- After the Mac wakes from sleep, the phone reconnects within about five seconds while its screen is
  on; a copy made in that window is delivered as soon as it does.
- One Mac per phone. Several phones can connect to one Mac.
- Other phones on the same network with Wireless debugging switched on advertise the same services;
  the app only ever uses the ones that resolve to its own address.
- Android's developer-options "16 KB compatibility" warning may appear for the app's native
  libraries. Their load segments are 16 KB aligned and they are stored uncompressed and aligned in
  the APK; whether the remaining warning matters for Play has not been confirmed.
- Distribution is built but not yet usable for other people: the release script works end to end,
  but until a Developer ID certificate is in the keychain the Mac app is signed ad-hoc (other Macs
  refuse it), and until a release key exists the phone app carries the Android debug key. The
  Developer ID signing and notarization steps have therefore never actually run.
- Apple silicon Macs only, unless the untested Intel build is switched on.
