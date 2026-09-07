# Mesh Chat

A working local-network desktop prototype and the start of the Android BLE client, based on [the supplied architecture](architecture.md). No hosted service, accounts, or runtime package dependencies.

## Run the desktop app

Requires Node.js 22 or newer (tested on Node 24 on Windows).

```powershell
npm.cmd start
```

Open **http://127.0.0.1:4310**. Choose a display name and a vault passphrase of at least 12 characters. The UI is loopback-only; the peer listener uses LAN port **4311**. The app makes no firewall changes. If Windows blocks incoming traffic, allow the peer port on your private network before connecting another computer.

On a second computer, run the app and exchange the mesh addresses displayed in the side panel. **Add each other** using those addresses, then compare fingerprints in person. You can send messages immediately; a peer that has not added you will reject them, and they remain queued.

For another local instance:

```powershell
$env:PORT = '4330'
$env:MESH_PORT = '4331'
$env:DATA_DIR = '.\data-second'
npm.cmd start
```

Open http://127.0.0.1:4330. Add `127.0.0.1:4331` from the first instance and `127.0.0.1:4311` from the second. Each instance needs separate ports and storage. Restarting requires the same passphrase; there is no recovery.

## Try the populated demo

```powershell
npm.cmd run demo
```

Open **http://127.0.0.1:4320**. This starts two actual local processes, exchanges encrypted example messages, and offers a small test file. Click **Accept file** to exercise the real transfer. Demo listeners are loopback-only, identities are temporary, and the UI labels the session as a demo. Ctrl+C removes its temporary data.

## Implemented and tested

- Ed25519 local identities with signed X25519 key binding; pinned peers and manual fingerprint verification.
- Experimental signed, encrypted sealed envelopes using ephemeral X25519, HKDF-SHA256, and ChaCha20-Poly1305.
- Text delivery over LAN, TTL-bounded relay forwarding, duplicate suppression, and recipient-signed acknowledgments.
- Encrypted origin outbox with retries every 10 seconds and 72-hour expiry; restart persistence.
- Explicit file acceptance, 192 KiB authenticated chunks, persisted received-piece indexes, pause/resume, and final SHA-256 verification. Media is direct LAN only.
- Passphrase-encrypted local identity, history, peer metadata, and blob chunks. Private material is not exposed by the UI API.
- Isolated loopback management API, session token, Host/Origin checks, CSP, bounded requests, peer rate limits, and storage limits.
- Responsive conversation UI, identity comparison, queue/delivery state, and file controls.
- Architecture binary codec in JavaScript and Java with the same 39-byte big-endian header.
- Android native foreground BLE scan/advertise source and a discovery screen. **Not yet compiled or device-tested.**

## Deliberate limits of this build

This is **not the completed v1 architecture or production security implementation**. The LAN transport currently carries versioned JSON envelopes; the binary codec is tested but not yet wired into transport. There is no LZ4, Noise XX/IK, live double ratchet, automatic route discovery, relay-side durable store-carry-forward, native desktop BLE, Android chat, Wi-Fi Direct, Meshtastic integration, voice/video recording, or emergency wipe. Relays forward opportunistically; only the original sender durably queues messages.

The sealed-envelope protocol is experimental and unaudited. It has **no forward secrecy**, including while peers are online. It exposes routing identities, sizes, and timing. First contact can be intercepted until fingerprints are compared. The eight-byte sender field is only a routing hint, never an authentication identity. The current KDF and passphrase policy are prototype defaults, not a completed password-hardening assessment.

Files are limited to 25 MiB each and 250 MiB of announced/shared file metadata per node. Every incoming file needs consent. A whole-file plaintext hash identifies content **inside the encrypted announcement**; individual chunks are authenticated and the completed file is hash-checked. File chunks and vault state are written synchronously for this small prototype. Local encrypted storage is in `data/`; **this workspace is under OneDrive**, so use a `DATA_DIR` outside sync folders if you want to keep even encrypted data off cloud backup.

Closing the browser does not lock the node. Stop the Node process to remove the running unlocked session. The current UI has no delete/lock controls. Storage encryption does not protect an already-unlocked or compromised operating system. Do not use this preview for safety-critical communications.

## Android

Open `android/` in Android Studio. Install SDK Platform 35 and Build Tools 35.0.0, use JDK 17 and Gradle 8.11.1, and sync the pinned Android Gradle Plugin 8.9.0 project. No Gradle wrapper is included yet. With Gradle installed, run `gradle :app:assembleDebug` in that directory.

The discovery app targets Android 12+ to use Nearby devices permissions without requesting location. It only discovers this project's custom service UUID and advertises no device name or persistent identity. Discovery stops after 30 seconds or when the screen closes. Bluetooth chat is intentionally not available until GATT framing, Noise sessions, trust storage, and real-device integration are ready. Desktop nodes do not advertise this BLE service yet.

The local SDK directory was inspected and contains no usable SDK platform or build tools. The Android APK has therefore **not** been built. Java-only core checks do run locally:

```powershell
.\android\test-core.ps1
```

## Verification

```powershell
npm.cmd test
.\android\test-core.ps1
```

Node tests check wrong recipients, tampered ciphertext/identities, vault encryption and wrong passphrases, malformed binary frames, plus three live processes exercising queued delivery, a forced relay path, signed acknowledgments, file consent, interrupted transfer recovery, and restart persistence. Android core checks confirm the wire vector, truncation rejection, TTL decrement, and media lane restrictions. These are development checks, not a security audit or hardware validation.

## Next implementation milestone: Android encrypted BLE text

1. Select and validate a maintained Noise implementation; finalize canonical identity binding and wire vectors before making the experimental LAN protocol interoperable.
2. Connect BLE GATT central/peripheral adapters with bounded MTU fragmentation, ordered writes, reconnection, and packet reassembly.
3. Add Android Keystore-backed identity/storage and fingerprint verification.
4. Run two-device encrypted text and three-device relay tests with radio dropouts, app suspension, and denied permissions.
5. Add Wi-Fi Direct negotiation and reuse the acceptance/resume model. Bridge encrypted short text to Meshtastic only after the local path works.

Architecture references: [Noise specification](https://noiseprotocol.org/noise.html), [Node crypto](https://nodejs.org/api/crypto.html), [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [AGP 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes).
