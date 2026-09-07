# Mesh Chat — System Architecture (v1 Draft)

**Working scope:** privacy-first, serverless, account-less chat with store-and-forward file/audio/video sharing over Bluetooth, with an optional long-range text lane for disasters/off-grid use. No live calling. No location tracking.

This document specifies the primary-path architecture using existing, proven protocols wherever one exists — rather than inventing new radio, crypto, or routing schemes from scratch. Fallbacks are noted where relevant but are not the design center; the primary path must work end-to-end first.

---

## 1. Design Goals & Non-Goals

**In scope (v1):**
- Text chat, peer-to-peer and multi-hop mesh, over Bluetooth LE
- Store-and-forward file sharing — photos, voice clips, video clips, arbitrary files
- No accounts, no phone numbers, no central server
- End-to-end encryption for all message and file content
- Long-range text-only backup lane for disaster/off-grid scenarios, built by bridging into an existing deployed network rather than rolling new radio firmware
- Desktop client parity (not just mobile)

**Explicitly out of scope (dropped in earlier design passes):**
- Live audio/video calling (real-time streaming) — replaced with store-and-forward clips
- Location tracking / "find this person" — cut entirely for privacy reasons
- Building a new LoRa mesh protocol or custom embedded firmware — bridge to an existing one instead (see §7)

---

## 2. Transport Layers

| Layer | Protocol | Purpose | Typical Range |
|---|---|---|---|
| Discovery & signaling | Bluetooth LE (GATT), custom service UUID | Peer discovery, presence, small control messages, session negotiation | ~10–30m |
| Local mesh (text) | BLE GATT + TTL-flood relay | Multi-hop text delivery through nearby peers | Extends with hop count |
| Bulk media transfer | Wi-Fi Direct (Android) / MultipeerConnectivity (iOS) | Chunked file/audio/video transfer once two peers negotiate a link | Local Wi-Fi range (~50–100m) |
| Long-range backup | Bridge into an existing Meshtastic LoRa mesh via its published node API | Short text only, when BLE/Wi-Fi peers are unreachable | Kilometers (via existing deployed nodes) |
| Desktop transport | Platform-native BLE stack (BlueZ / WinRT Bluetooth / CoreBluetooth) behind a shared core | Same protocol, non-mobile client | Same as BLE/Wi-Fi above |

The guiding rule: **BLE is discovery and control only. Wi-Fi Direct is the only lane that carries media. LoRa (via Meshtastic) carries short text only** — this isn't a workaround, it's matching each protocol to what it's actually good at.

---

## 3. Identity & Cryptography

Use the same primitive set that Signal, WireGuard, and bitchat all converge on — this is a solved problem; don't reinvent it.

- **Identity keypair**: Ed25519, generated on-device, local-only. No recovery mechanism (same model as Briar/Signal Desktop) — losing the device means losing the identity, by design.
- **Session establishment**: [Noise Protocol Framework](https://noiseprotocol.org). Use the **XX pattern** for first contact between two peers who don't know each other's key yet (mutual authentication during the handshake), and **IK pattern** for reconnecting to a previously-known peer (faster handshake, key already pinned).
- **Key agreement**: X25519 (Curve25519 ECDH).
- **Symmetric encryption**: ChaCha20-Poly1305 AEAD for all payloads (message bodies and file chunks alike).
- **Forward secrecy**: full double-ratchet-style key rotation for live sessions (both peers in range, actively exchanging). For store-and-forward (queued) delivery — where the recipient isn't present to advance a ratchet — this is a known hard limitation (bitchat has the same gap, disclosed in their own docs). v1 accepts sealed-but-non-forward-secret encryption for queued messages; revisit only after the live-session path is solid.
- **Trust model**: trust-on-first-contact with an out-of-band fingerprint verification option (e.g., QR code scan) for anyone who wants to confirm a peer's key isn't spoofed.

---

## 4. Packet Format

Fixed binary header, same shape as the prior art here (bitchat, ping) — no need to deviate:

```
[ version:1B ][ type:1B ][ ttl:1B ][ sender_id:8B ][ packet_id:16B ]
[ timestamp:8B ][ payload_len:4B ][ payload:variable ]
```

- **Compression**: LZ4 on text payloads before encryption (compress-then-encrypt, never the reverse — encrypting first destroys compressibility).
- **Packet types**: `HELLO` (presence/discovery), `TEXT`, `BLOB_ANNOUNCE`, `BLOB_CHUNK`, `BLOB_ACK`, `ROUTE_ACK` (dedup/relay confirmation).

---

## 5. Mesh Routing (Local, BLE)

- **TTL-flood with dedup**: each node rebroadcasts a packet it hasn't seen (tracked via `packet_id` in a rolling seen-set / bloom filter) until TTL hits zero. Cap TTL at a fixed hop count (bitchat uses 7 — a reasonable starting point; tune based on your own density testing).
- **Store-carry-forward (DTN)**: if the recipient isn't currently reachable, hold the message in a local bundle store with an expiry (e.g., 24–72 hours), and relay it opportunistically if another node in range is later observed to be a path toward the recipient.
- **Rate limiting**: cap relay throughput per node to prevent flood collapse in dense-crowd scenarios — this is where naive flooding mesh networks fail hardest, so load-test this specifically before shipping.

---

## 6. File & Media Transfer (the core differentiator)

This is the layer bitchat doesn't have at all — treat it as first-class, not bolted on.

**Content addressing**: every blob (photo, voice clip, video, arbitrary file) is identified by its SHA-256 hash. This gives automatic deduplication (the same file forwarded through multiple mesh hops only needs to be transferred once) and clean resume semantics.

**Transfer flow**:
1. **Announce** (over BLE control channel): sender advertises `BLOB_ANNOUNCE { hash, size, mime_type, thumbnail? }`.
2. **Accept/queue**: recipient chooses to fetch now, later, or decline. No auto-pull of large files.
3. **Link negotiation**: for anything above a small threshold, negotiate a Wi-Fi Direct group (Android) or MultipeerConnectivity session (iOS) between the two peers.
4. **Chunked transfer**: fixed-size chunks, each independently verifiable against the overall content hash. Use a **piece-bitmap / "have" message** convention (the same idea BitTorrent uses) so the receiver can tell the sender exactly which chunks are missing — this is what makes resume-after-link-drop trivial rather than a special case.
5. **Encrypt-then-chunk**: encrypt the full blob first under the session's symmetric key, then split into chunks. A corrupted or missing chunk just means "wait for retransmit," not a re-keying problem.

**Size tiers** (tune thresholds from real device testing, not guesses):

| Tier | Example | Behavior |
|---|---|---|
| Tiny | short text, small stickers | Inline over BLE control channel, no link negotiation |
| Medium | voice clips, photos (~<15MB) | Auto-transfer over Wi-Fi Direct once link forms |
| Large | video clips, large files | Explicit accept required, progress UI, pause/resume supported |

**Codecs** (compression happens before the blob enters the transfer pipeline, so the transfer layer stays format-agnostic):
- Audio: **Opus**, low-bitrate voice profile (8–24 kbps)
- Video: **H.264 baseline** (widest hardware encode/decode support on both platforms), capped resolution/framerate by default
- Everything else: pass through as-is, or offer client-side compression as an option, not a requirement

---

## 7. Long-Range Backup Lane — Bridge to Meshtastic, Don't Rebuild It

Rather than designing new embedded firmware or a new LoRa mesh protocol (which would compete with Meshtastic and MeshCore's already-large deployed hardware base), the primary-path design **bridges into the existing Meshtastic network**:

- Meshtastic already publishes a documented phone-to-node API (BLE and serial) and protobuf packet definitions for its firmware.
- Your app speaks that API to any Meshtastic-compatible node the user already owns or can buy off the shelf (ESP32 + LoRa boards from Heltec, RAK, Seeed, etc.) — you are not shipping or certifying your own radio hardware in v1.
- This lane carries **text and short structured messages only** — LoRa's realistic throughput (roughly 0.3–50 kbps depending on config) cannot carry photos, audio, or video. Don't attempt to force media over this lane; keep the media pipeline entirely on the BLE/Wi-Fi Direct lane from §6.
- Regulatory compliance (transmit power limits, duty-cycle restrictions per region) is inherited from Meshtastic's already-compliant firmware and certified hardware, rather than something your team has to independently solve.
- This lane activates specifically when a message can't be delivered via the local mesh (§5) — framed to the user as "sent via long-range backup" rather than blended invisibly into normal chat, so expectations about latency and text-only content stay honest.

---

## 8. Desktop Client

- A shared, transport-agnostic core (routing, crypto, packet codec, blob store) — analogous to the `ping` project's platform-independent `core/` module — with thin platform-specific transport bindings underneath.
- Desktop BLE access via each platform's native stack: **BlueZ** (Linux), **WinRT Bluetooth API** (Windows), **CoreBluetooth** (macOS). These differ enough in capability and reliability that budget real integration/testing time here specifically.
- Desktop can also join the mesh over local Wi-Fi/LAN directly (no BLE radio required on many desktops/laptops), similar to how `ping`'s `tools/node` lets a laptop join via LAN for testing — this can become a real feature (a desktop always-on relay node) rather than just a dev tool.

---

## 9. Local Data Storage

- Encrypted at rest: platform keystore-backed encryption or SQLCipher for the message/metadata database.
- Blob store: content-addressed on-disk storage keyed by SHA-256 hash, matching §6.
- Emergency wipe: a fast, explicit "erase everything" action (mirroring bitchat's triple-tap wipe) — cheap to build, meaningful for the privacy-first positioning.

---

## 10. Fallbacks (noted, not built first)

These are real gaps in the primary path above — worth designing room for, but not blocking v1:

- **No internet fallback for out-of-mesh-range messaging.** Bitchat solves this with a Nostr relay fallback; the primary design here deliberately doesn't include one, to keep the "genuinely offline, no relay dependency" privacy story clean. Revisit only if user feedback demands global reach badly enough to justify the privacy trade-off.
- **No custom LoRa firmware/hardware.** If bridging into Meshtastic proves too limiting (e.g., you need message types or routing behavior their firmware doesn't expose), the fallback is building your own firmware — but that's a last resort given the regulatory and hardware-support burden discussed earlier, not a v2 default.
- **Forward secrecy for queued/store-and-forward messages** is a known open problem (see §3) — flag it in any security documentation rather than overstating current guarantees.

---

## 11. Open Decisions Still Needed

- Exact TTL/hop-count and rate-limit constants — needs real device density testing, not a guess.
- Wi-Fi Direct vs. plain local hotspot as the primary media-transfer fallback path on Android (Wi-Fi Direct group formation has known OEM-dependent flakiness).
- Shared-core implementation language (Kotlin Multiplatform vs. Rust with FFI bindings) — affects both mobile and desktop code-sharing depth.
- How explicitly to message the long-range lane's text-only, best-effort nature to users in an actual emergency context, given the liability weight of that promise.
