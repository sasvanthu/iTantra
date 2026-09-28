# WIRE PROTOCOL — Setu Binary Wire, Framing, Packetization, Progression

> Status: implemented + unit-tested on JVM. **Never yet carried by two real
> phones.** All sizes labelled MEASURED come from `PhysicalMeasuredBenchmarkTest`
> and `BaselineMetricsTest` on a Windows 11 JVM, not from hardware.

## 1. Layers on the stack

```
┌─────────────────────────────────────────────────────────────┐
│ Message (text, language)                                     │
├─────────────────────────────────────────────────────────────┤
│ Codec layer    RetroSpeechCodec / ProgressiveLayeredPipeline │
│                -> EncodedPayload  (Brahmic + phoneme tokens) │
├─────────────────────────────────────────────────────────────┤
│ Semantic layer SemanticDispatchRouter (SUTRA frame optional) │
├─────────────────────────────────────────────────────────────┤
│ L4 Transport  BaseTransportEngine (CRC-32, ACK/NACK, gap)    │
├─────────────────────────────────────────────────────────────┤
│ L3 Routing    MeshRouter -> HopPacket (TTL/hops/origin)      │
├─────────────────────────────────────────────────────────────┤
│ L2 Packet     SetuPacket (AES-256-GCM + AAD)                 │
├─────────────────────────────────────────────────────────────┤
│ L1 Wire       SetuPacketBinaryCodec (default) | toJson()     │
├─────────────────────────────────────────────────────────────┤
│ L0 Media      BLE GATT chunks (BleLinkCodec) | TCP | Memory  │
└─────────────────────────────────────────────────────────────┘
```

## 2. Packet envelope (`SetuPacket`)

`com.example.itantra.protocol.SetuPacket` is the unit of authenticated exchange:

- `computeAad()` — authenticated additional data covers protocol version,
  transmission ID, message ID, packet ID, origin sender, receiver, language and
  message type. **Re-routing a packet breaks authentication.**
- `encryptPayload(key)` — AES-256-GCM with random 12-byte nonce + 16-byte tag.
- `toWireBytes()` → `SetuPacketBinaryCodec.encode(this)` (default wire format).
- `toJson()` → kotlinx.serialization JSON (kept as a decode-compatible fallback;
  ~4× larger than binary but preserves full fidelity).

### Binary wire format (`SetuPacketBinaryCodec`)

Fixed head + tagged IDs + var-length payload:

- 48-byte fixed header (proto v1, codec v2, message/packet type, priority byte,
  transport byte, language byte, nonce 12 B, auth tag 16 B)
- varstring IDs (messageId, transmissionId, sender, receiver)
- payload body
- decode rejects wrong length/unknown enum codes; every field is bounds-checked.

## 3. Serialization overhead (MEASURED, JVM)

| Envelope | Bytes | Notes |
| :--- | :--- | :--- |
| Binary (`encodeBinary`) | ~111 B overhead + body | 48 B header + nonce + tag + UTF-8 IDs |
| JSON (`toJson`) | ~432 B overhead | retained for compatibility/debug |

## 4. Chunking and packetization (`Packetizer`)

- `chunk(payload, maxSize = 1024)` — splits an encoded payload into parts.
- Python-indexed/packets carry `messageId`, `sequenceId`, `priority`, `isEmergency`.
- `Packet` frame types: START / DATA / END / ACK / NACK / RETRANSMIT /
  EMERGENCY / CAPABILITY / CAPABILITY-ACK, all CRC-32 protected on the wire.

## 5. Progressive / multi-layer delivery

`ProgressiveTransmission` (`app/.../data/ProgressiveTransmission.kt`):

- `PREVIEW_MAX_BYTES = 64` — first band carries a trimmed preview;
- `DEFAULT_MAX_PACKET_PAYLOAD = 1024` — steady-state packet budget;
- rounds: PREVIEW → BACKGROUND → NOMINAL, each band adding fidelity layers.

`ProgressiveLayeredPipeline.createLayers(text, lang, isEmergency, contextPayload)`:

- default 2 layers; with a SUTRA context payload an opt-in **Layer 2** (priority
  0) rides the background band and attaches domain/meaning; losing Layer 2 never
  blocks or downgrades the base message (reassembly is layer-loss-tolerant).

## 6. Low-bandwidth adaptation (`AdaptiveLinkGovernor`)

- Commit a mode only after `DEFAULT_HYSTERESIS_SAMPLES = 3` consecutive samples.
- `shouldUsePrediction()` is true only for committed `LOW_BANDWIDTH`.
- Conservativeness floors (never upgrade a sensed state):
  - RSSI `< -90 dBm` → EMERGENCY floor; `-89..-80` → LOW floor;
  - queue backlog `>= 64` → EMERGENCY floor; `>= 32` → LOW floor.
- Link health starts `UNKNOWN` until the first sample; reset returns to NORMAL +
  nominal ceiling.

## 7. Multi-hop (`MeshRouter` / `HopPacket` / `MeshReassembler`)

- Hop envelope carries TTL/hops/origin; relay stops at TTL 0; duplicates and
  self-echoes are dropped (LRU seen-set).
- `MeshReassembler` reconciles gaps; hostile END count `0x7FFFFFFF` is dropped
  with `droppedMessages++` and no allocation; stale half-messages purged after
  retention.

## 8. Guard rails

- CRC-32 over every opaque frame; AEAD tags over every `SetuPacket`.
- Bounded receive buffers + OOM guard; stale-buffer purging; session isolation.
- Capability handshake is proto v1 / codec v2 (see `CapabilityMessage`).

## 9. Reproduce

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.example.itantra.protocol.*"
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.example.itantra.codec.PhysicalMeasuredBenchmarkTest"
```