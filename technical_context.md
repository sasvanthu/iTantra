Viewed README.md:1-200
Viewed PROTOTYPE_STATUS.md:1-200
Listed directory itantra
Viewed build.gradle.kts:1-101
Listed directory codec
Listed directory protocol
Listed directory transport
Listed directory security
Listed directory mesh
Listed directory speech
Listed directory stt
Listed directory tts
Listed directory vad
Listed directory ops
Listed directory telemetry
Listed directory dispatch
Listed directory ui
Listed directory main
Viewed BinaryCodec.kt:1-80
Viewed BrahmicCodec.kt:1-60
Viewed SetuPacket.kt:1-80
Viewed SetuPacket.kt:80-160
Viewed SpeechPipeline.kt:1-100
Viewed SpeechPipeline.kt:100-250
Viewed SemanticDispatchRouter.kt:1-66

# Technical Summary: iTantra (Setu)
*Offline Voice, Semantic Codec & Mesh Relay System*

---

## 1. Executive Summary

**iTantra (Setu)** is an edge-native, zero-cloud, multi-hop voice and data relay application developed for Android. It is engineered for **tactical, disaster-response, and infrastructure-denied environments** where cellular networks, satellite uplinks, and internet connectivity are unavailable.

Its core architectural philosophy is: **"Meaning travels. Bandwidth doesn't have to."** 

Instead of streaming heavy raw digital audio over fragile low-bandwidth links, iTantra transcribes spoken utterances locally on the edge device, compresses the linguistic and semantic payload by up to **70.5%** using custom domain-specific binary codecs, encapsulates it in authenticated **AES-256-GCM** packets, and transmits it across short-range physical transports (**Bluetooth Low Energy** and **Wi-Fi TCP**) over an ad-hoc store-and-forward mesh. On the receiving end, the message is verified, decrypted, and reconstituted via text and local text-to-speech synthesis.

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             END-TO-END PIPELINE                                 │
│                                                                                  │
│   [AudioRecord / Mic]                                 [AudioTrack / Speaker]    │
│           │                                                      ▲               │
│           ▼                                                      │               │
│   [VAD + STT Engine]                                    [TTS Synthesis]         │
│     (Local Vosk / OS)                                   (Formant / Embedded)     │
│           │                                                      ▲               │
│           ▼                                                      │               │
│  [Codec: Brahmic / Retro] ──► [Progressive SUTRA] ──► [Decryption & Reassembly] │
│   (Offset / Huffman Packing)   (Layer 0 Semantic)       (AES-256-GCM / Tag Check)│
│           │                                                      ▲               │
│           ▼                                                      │               │
│    [SetuPacket Wire]      ──► [AES-256-GCM + AAD] ──► [Transport & Mesh Hop]    │
│   (CRC-32 / Z-BWE Binary)      (Keystore Sealed)        (BLE GATT / Wi-Fi Sockets)│
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Core Architecture & Subsystems

### 2.1 Speech Processing Pipeline
Located in [`SpeechPipeline.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/SpeechPipeline.kt):
* **Voice Activity Detection (VAD)**: Managed by [`VoiceActivityDetector.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/speech/vad/VoiceActivityDetector.kt), segmenting spoken phrases dynamically to prevent dead-air transmission.
* **Speech-to-Text (STT)**: Implemented in [`STTEngine.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/speech/stt/STTEngine.kt). Designed for offline Kaldi/Vosk acoustic models (`VoskSpeechRecognizer`), with fallback to the Android platform `SpeechRecognizer`. Target scope covers 10 official Indian languages (*English, Hindi, Tamil, Bengali, Telugu, Marathi, Gujarati, Kannada, Malayalam, Odia*).
* **Text-to-Speech (TTS)**: Implemented via [`EmbeddedOpenSourceTTS.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/speech/tts/EmbeddedOpenSourceTTS.kt) and [`TTSRegistry.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/speech/tts/TTSRegistry.kt). Includes a zero-dependency acoustic formant synthesizer producing 16-bit 16 kHz PCM directly into Android `AudioTrack`, alongside integration hooks for `SherpaOnnxTtsEngine`.
* **Push-To-Talk (PTT)**: Supports both real-time streaming and press-and-hold Walkie-Talkie interactions with latch release triggers.

---

### 2.2 Proprietary Compression & Linguistic Codecs
iTantra uses a multi-tier codec approach optimized for constrained radio payloads (MTU 512B on BLE):

| Codec | Implementation File | Key Mechanism & Efficiency |
| :--- | :--- | :--- |
| **Brahmic Codec** (VPMC) | [`BrahmicCodec.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/codec/BrahmicCodec.kt) | **Vedic/Brahmic Script Offset Packing**: Packs Indic Unicode characters (normally 3 bytes in UTF-8) into single 7-bit relative offsets (1 byte) within their 128-code-point block. ASCII chars map to `0x80..0xDE`; Indic marks (Danda, ZWJ/ZWNJ) map to single-byte opcodes. Guarantees 100% lossless round-trip. |
| **RetroSpeech Codec** | [`BinaryCodec.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/codec/BinaryCodec.kt), [`RetroSpeechEncoder.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/codec/RetroSpeechEncoder.kt) | LEB128 varint packing, pre-compiled token frequency dictionaries, and context-prediction token flags. Yields **31.6% (EN)**, **58.8% (HI)**, and **70.5% (TA)** size reduction. |
| **SUTRA Progressive Semantic Layer** | [`SutraFrame.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/codec/SutraFrame.kt), [`ProgressiveLayeredPipeline.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/codec/ProgressiveLayeredPipeline.kt) | **Progressive intent dispatch**: Parses critical intent and entity slots (e.g. `Domain: RESCUE`, `Intent: TRAPPED`, count, urgency) into an ultra-compact **5–14 byte Layer 0 frame**. Sent before Layer 1 (the full transcript) to deliver actionable intent instantly. |

---

### 2.3 Wire Protocol & Framing
Located in [`SetuPacket.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/protocol/SetuPacket.kt) and [`BaseTransportEngine.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/transport/BaseTransportEngine.kt):
* **Wire Representation**: Compact binary wire format ([`SetuPacketBinaryCodec.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/protocol/SetuPacketBinaryCodec.kt)) with JSON backward compatibility.
* **Deterministic Framing**: Delimited by magic header bytes (`0x49544E54` / `ITNT`), packet length prefixes, frame type codes (9 distinct control & data frame types), sequence counters, and a trailing CRC-32 checksum.
* **Fragmentation & Reassembly**: [`MeshReassembler.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/protocol/MeshReassembler.kt) manages reassembly buffers with out-of-order packet indexing, gap detection, bounded memory guards (OOM defense), and stale session purges.
* **Reliability**: Selective ACK/NACK mechanics via [`RetransmissionManager.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/protocol/RetransmissionManager.kt).

---

### 2.4 Cryptography & Zero-Trust Security
Security logic resides in [`CryptoEngine.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/security/CryptoEngine.kt) and [`SessionKeyManager.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/security/SessionKeyManager.kt):
* **Cipher Suite**: **AES-256-GCM** (Galois/Counter Mode). Every frame encrypts with a fresh cryptographically secure 12-byte initialization vector (nonce) and produces a 128-bit authentication tag.
* **Associated Authenticated Data (AAD)**: Routing metadata—including `protocolVersion`, `transmissionId`, `messageId`, `packetId`, `originalSenderId`, `receiverId`, `language`, and `messageType`—is cryptographically bound to the tag. Re-routing or modifying header metadata causes immediate `AEADBadTagException` rejection.
* **Replay Protection**: [`ReplayDetector.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/security/ReplayDetector.kt) maintains a time-windowed deduplication table keyed on `(messageId, packetId, sequenceNumber)`.
* **Key Storage & Out-of-Band Pairing**:
  * **Envelope Storage**: The shared 256-bit symmetric session key is sealed using an asymmetric/master key inside the **non-exportable Android Keystore** ([`KeyEnvelope.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/security/KeyEnvelope.kt)).
  * **Pairing Code**: Human-mediated exchange via Crockford Base32 strings (`SETU-XXXX-...`) with strict length checks and CRC-16/CCITT checksums ([`PairingCode.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/security/PairingCode.kt)). The UI only reveals a SHA-256 key fingerprint.

---

### 2.5 Multi-Hop Mesh & Ad-Hoc Transport
Located in [`transport/`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/transport) and [`mesh/`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/mesh):
* **Physical Transports**:
  * **BLE Engine** ([`BleTransportEngine.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/transport/BleTransportEngine.kt)): Implements both GATT Server and Client roles with 2M-PHY and MTU negotiation (up to 512 bytes).
  * **Wi-Fi Engine** ([`WifiTransportEngine.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/transport/WifiTransportEngine.kt)): Raw TCP socket server/client for higher throughput over local ad-hoc Wi-Fi networks.
* **Mesh Store-and-Forward**: [`MultiHopRelayEngine.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/mesh/MultiHopRelayEngine.kt) provides hop-decrementing (TTL), packet deduplication, loop prevention, and multi-hop forwarding across intermediate nodes ($A \to B \to C$).
* **Neighbor Discovery**: [`MeshNeighborDiscovery.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/mesh/MeshNeighborDiscovery.kt) broadcasts and listens for `MeshBeacon` frames. Presence beacons are governed by [`BeaconGovernor.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/mesh/BeaconGovernor.kt), which throttles cadence based on battery state and channel load.

---

### 2.6 Operations, Emergency & Power Governance
Located in [`ops/`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/ops) and [`dispatch/`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/dispatch):
* **Power Governor**: [`LowPowerController.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/ops/LowPowerController.kt) shifts across `HEALTHY`, `LOW`, and `CRITICAL` power tiers based on device battery telemetry. At `CRITICAL`, routine beacons and standard chat are suppressed, reserving battery strictly for emergency SOS frames.
* **Emergency Dispatch**: [`EmergencyController.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/ops/EmergencyController.kt) and [`SemanticDispatchRouter.kt`](file:///c:/Users/sasva/Downloads/iTantra/app/src/main/java/com/example/itantra/dispatch/SemanticDispatchRouter.kt) detect critical emergency keywords ("trapped", "fire", "casualty") to automatically promote messages to `CRITICAL` priority, bypass beacon throttling, and trigger Android `USAGE_ALARM` audio routing on receiving peers.

---

## 3. Empirical Performance & Footprint Metrics

*(Tested on physical hardware: Vivo V2334, Android 16)*

| Dimension | Measured Metric | Detail |
| :--- | :--- | :--- |
| **Cold App Launch Time** | **2.59 seconds** | Measured via `am start -W` |
| **Idle CPU Utilization** | **0.0%** | Zero polling; strictly event-driven coroutine pipelines |
| **RAM Footprint (PSS)** | **123.2 MB** | Java Heap: ~5.89 MB; Native Heap: ~26.33 MB; Private Dirty: ~22.04 MB |
| **APK Package Size** | **54.34 MB** | Multi-ABI bundle (ARM64-v8a installs only ~8.86 MB native footprint) |
| **Network Leakage** | **Zero bytes** | Codebase audit confirms 0 REST endpoints, 0 telemetry trackers, 0 cloud dependencies |
| **Codec Encoding Latency** | **0.02 – 0.12 ms** | In-memory micro-benchmark |
| **Tamil Text Compression** | **70.5% reduction** | 129B UTF-8 $\to$ 38B encoded binary |
| **Hindi Text Compression** | **58.8% reduction** | 97B UTF-8 $\to$ 40B encoded binary |

---

## 4. Technology Stack & Directory Structure

* **Language & Runtime**: Kotlin 2.3.20, Java 17, Android SDK Target 36 (`minSdk 24`).
* **UI Toolkit**: Jetpack Compose + Material 3 (Retro Amber & Tactical Black theme).
* **Concurrency**: Kotlin Coroutines & `StateFlow` / `SharedFlow`.
* **Serialization & Speech**: Kotlinx Serialization, Vosk Android JNI binaries.

```
app/src/main/java/com/example/itantra/
├── codec/           # BinaryCodec, BrahmicCodec, SutraParser, TokenDictionary
├── dispatch/        # SemanticDispatchRouter (intent extraction & routing)
├── mesh/            # MultiHopRelayEngine, MeshNeighborDiscovery, BeaconGovernor
├── metrics/         # MetricsEngine (latency, compression, and error metrics)
├── ops/             # EmergencyController, LowPowerController, OperationModeController
├── protocol/        # SetuPacket, FrameCodec, MeshReassembler, Packetizer
├── security/        # CryptoEngine (AES-256-GCM), KeyEnvelope, PairingCode, ReplayDetector
├── speech/
│   ├── stt/         # STTEngine, StreamingAsrEngine, Vosk binding
│   ├── tts/         # EmbeddedOpenSourceTTS, SherpaOnnxTtsEngine, TTSEngine
│   └── vad/         # VoiceActivityDetector
├── telemetry/       # CommunicationEventLog (JSON event logs & coordinate traces)
├── transport/       # BaseTransportEngine, BleTransportEngine, WifiTransportEngine
└── ui/              # Compose screens: Main, Link, Codec Lab, Metrics, Config, HW Test
```

---

## 5. Current Prototype Status & Verification Ledger

Detailed in [`PROTOTYPE_STATUS.md`](file:///c:/Users/sasva/Downloads/iTantra/PROTOTYPE_STATUS.md):

* **Unit Test Suite**: **427 tests passing, 0 failures, 1 skipped (100% green)**.
* **Verified in Hardware/Simulation**:
  * ✅ Single-device local loopback (Mic capture $\to$ STT $\to$ Codec encode $\to$ Codec decode $\to$ Formant TTS $\to$ Speaker output).
  * ✅ Cryptographic tamper rejection (tampered ciphertext, auth tag, or AAD fails with `AEADBadTagException`).
  * ✅ Replay protection and sequence deduplication.
  * ✅ UI rendering across landscape/portrait without clipping.
* **In-Progress / Pending Hardware Validation**:
  * ⚠️ **2-Phone Physical Over-the-Air Transport**: `BleTransportEngine` and `WifiTransportEngine` are code-complete and unit-tested in-process, but pending multi-phone field validation.
  * ⚠️ **Device Key Pairing**: Crockford Base32 key code import/export and Keystore envelope are unit-tested; end-to-end multi-device pairing is pending physical 2-device verification.
  * ❌ **Offline Voice Models**: Model assets directory (`assets/models/`) is unpopulated in git; offline STT falls back to Android OS recognizer until model weights (~40MB per language) are provisioned. Real multilingual TTS requires full Piper/sherpa-onnx model bundling.