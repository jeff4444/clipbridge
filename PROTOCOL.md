# ClipBridge wire protocol, v1

Implemented in `mac/Sources/ClipBridge/Wire.swift` + `Session.swift` and
`android/app/src/main/java/dev/clipbridge/daemon/Wire.java` + `Main.java`. Keep the two in step.

## Transport

TCP. The Mac listens on port 47823 (all interfaces). The phone helper dials, trying in order:

1. `127.0.0.1` — reaches the Mac through `adb reverse` when the phone is plugged in
2. the last address that worked
3. the Mac's Bonjour name (`<LocalHostName>.local`) and its LAN addresses, from the pushed config

LAN candidates are only tried while the phone has a Wi-Fi/ethernet address, never over cellular.

## Handshake (plaintext)

Each side sends 21 bytes; the dialling side (initiator) goes first.

```
"CLBR" | version = 0x01 | nonce (16 random bytes)
```

Session keys, with `PSK` the 32-byte pairing key:

```
salt  = initiatorNonce || responderNonce
k_i2r = HKDF-SHA256(ikm = PSK, salt, info = "clipbridge v1 i2r", 32 bytes)
k_r2i = HKDF-SHA256(ikm = PSK, salt, info = "clipbridge v1 r2i", 32 bytes)
```

There is no explicit authentication message: a peer without the PSK cannot produce a frame that
decrypts, and the connection is dropped on the first failure. Fresh nonces from both sides make
recorded sessions useless for replay.

## Frames

```
length (uint32, big endian) | AES-256-GCM( type (1 byte) | body ) | tag (16 bytes)
```

`length` covers ciphertext and tag. The GCM nonce is 4 zero bytes followed by a 64-bit big-endian
counter that starts at 0 and increments per frame, separately for each direction. Plaintext is at
most 1 MiB.

| Type | Name  | Body |
| ---- | ----- | ---- |
| 1 | HELLO | JSON: `v`, `platform`, `name`, `device` (phone only), `clipId`, `clipTime`, `interactive` (phone only) |
| 2 | CLIP  | JSON header: `id`, `time`, `sensitive`, `items: [{kind, mime, name, size}]` |
| 3 | DATA  | raw bytes: the items' contents, concatenated in order, in chunks of up to 256 KiB |
| 4 | END   | JSON: `id` — the clip is complete and should be applied |
| 5 | PING  | empty |
| 6 | PONG  | empty |
| 7 | STATE | JSON: `interactive` (phone → Mac: screen on/off) |

Item kinds: `text` (UTF-8), `html` (UTF-8, optional companion to `text`), `file` (bytes with a name
and MIME type; images are files with an `image/*` type). A clip is either `text` [+ `html`] or one
or more `file` items.

A CLIP frame that arrives before the previous clip's END cancels the previous clip.

## Sync rules

- Each copy gets a random 64-bit `id` and a `time` (ms since epoch) that is strictly greater than
  any time the copying device has seen, so clock differences between devices cannot reorder copies.
- HELLO carries the id and time of what each side currently holds. If the ids differ, the side
  with the later time sends its clip — if it still has the contents. Whatever is on a clipboard when
  a side starts is stamped "now" with no contents, so a restart neither sends nor receives stale data.
- The receiver remembers what it applied and ignores the resulting change notification (Android: by
  content signature and by provider authority; macOS: by a `dev.clipbridge.remote` marker type).
- While the phone reports `interactive: false`, the Mac holds its clips and sends only the latest
  when the phone reports `true` again.
- Liveness is driven by the phone: it pings on screen-on and every 30 s while the screen is on, and
  reconnects if no PONG arrives within 2.5 s. The Mac drops a session that has been silent for 75 s
  while the phone claimed to be awake, and otherwise relies on a slow TCP keepalive.
