# iTantra: Setu — Offline Voice & Mesh Relay System (PROTOTYPE)

> ### ⚠️ READ `PROTOTYPE_STATUS.md` FIRST
>
> This README previously described the project as a *"fully functional,
> production-hardened prototype"*. **That was not accurate.** The corrections:
>
> * The transport layer (BLE / Wi-Fi) and the multi-device relay have **never been
>   run between two physical phones**. All transport tests are in-process.
> * **Zero of the ten required offline speech languages work.** No model weights
>   ship in the APK; `assets/models/` is empty and STT falls back to the OS
>   `SpeechRecognizer`.
> * There is **no sherpa-onnx, no Piper, no Silero VAD and no IndicConformer** in
>   the build. The TTS engine is a hand-written formant synthesizer, not a
>   neural voice.
> * There is **no NDK/JNI source in this repository** and no Piper/eSpeak bridge.
>   The ~35 MB of native libraries are the **Vosk** JNI binary.
> * Device pairing (shared AES-256 key) is new and **unit-tested but unproven on
>   hardware**.
>
> `PROTOTYPE_STATUS.md` carries the itemised PASS / UNIT / CODE / BLOCKED ledger.

**Offline voice communication prototype** — capture speech, encode it into
compact binary frames with `RetroSpeechCodec`, wrap it in an authenticated
AES-256-GCM `SetuPacket`, transport it over Wi-Fi / Bluetooth LE / multi-hop
relay, and reconstruct it on the peer. Everything runs **on-device with no
internet, cloud API or external server**.

```
  MICROPHONE ──► STT (Vosk, model not yet bundled) ──► PACKETIZER ──► AES-256-GCM
       │                                                              │
    SPEAKER ◄── TTS (formant synth placeholder) ◄── DECRYPT ◄── TRANSPORT (Wi-Fi / BLE)
```

This is a **working prototype of the core pipeline**, validated by **283 JVM unit
tests (0 failures, 1 skipped)**. Physical transport validation is outstanding.

---

## 1. Status & Validation Matrix

| Subsystem | Verification Status | Evaluation Method |
| :--- | :---: | :--- |
| **Local Speech Pipeline** | ⚠️ **LOOPBACK ONLY** | Mic capture $\to$ STT $\to$ Codec $\to$ Decode $\to$ Formant TTS $\to$ `AudioTrack` on a single device. **No STT model is bundled**, so STT falls back to the OS recognizer. |
| **Offline Multilingual STT (10 langs)** | ❌ **NOT AVAILABLE** | `assets/models/` is empty. 0 of 10 languages work offline. No sherpa-onnx, no IndicConformer, no Silero VAD in the build. |
| **STT Test & Diagnostics (10 Metrics)** | ✅ **VERIFIED ON DEVICE** | Real-time diagnostic card measuring latency, duration, sizes, and states |
| **Embedded Open-Source TTS** | ⚠️ **PLACEHOLDER, NOT A VOICE** | Acoustic formant synthesizer producing 16-bit 16 kHz PCM to `AudioTrack`. Speech-shaped, not intelligible. No Piper/sherpa-onnx. |
| **RetroSpeechCodec** | ✅ **VERIFIED BY UNIT TEST** | 20-iteration benchmark across English, Hindi, and Tamil (31%–70.5% compression) |
| **SetuPacket + AES-256-GCM** | ✅ **VERIFIED BY UNIT TEST** | 12-byte nonce/op, 128-bit tag, AAD over routing metadata; ciphertext/tag/AAD tamper rejection asserted |
| **Device Pairing (shared key)** | ⚠️ **UNIT TESTED / HARDWARE PENDING** | `PairingCode` + `KeyEnvelope` (Android Keystore). 13 unit tests. 2-phone run outstanding. |
| **IDs (TX / MSG / PKT)** | ✅ **VERIFIED BY UNIT TEST** | `TX-YYYYMMDD-XXXXXX`, `MSG-XXXXXX`, `PKT-XXXXXX-NNN` |
| **Replay / Duplicate Defence** | ✅ **VERIFIED BY UNIT TEST** | `ReplayDetector` keyed on messageId + packetId + sequenceNumber |
| **Packet Protocol (9 Types, CRC, Frag)** | ✅ **VERIFIED BY UNIT TEST** | All 9 control/data frames, CRC-32, bit-corruption rejection, out-of-order reassembly |
| **Mesh Store-and-Forward Logic** | ✅ **VERIFIED BY SIMULATION** | 3-node multi-hop line (A $\to$ B $\to$ C), deduplication, loop prevention, TTL expiration. **In-process, not 3 phones.** |
| **PTT / Walkie-Talkie Mode** | ✅ **VERIFIED ON DEVICE** | Press-and-hold interaction and single-device local loopback validation |
| **Emergency Priority & Presence Bypass** | ✅ **VERIFIED BY UNIT TEST** | `CRITICAL` priority queueing, `USAGE_ALARM` audio routing, presence bypass |
| **Low-Power Governor** | ✅ **VERIFIED BY UNIT TEST** | OS battery-driven gating (`HEALTHY`, `LOW`, `CRITICAL` power profiles) |
| **UI Responsiveness & Layout** | ✅ **VERIFIED ON DEVICE** | Tested in portrait & landscape on physical hardware; zero overflow or clipping |
| **Event Log + Simulated GPS** | ✅ **VERIFIED ON DEVICE** | `CommunicationEventLog`; coordinates explicitly tagged `SIMULATED_LOCATION` |
| **Campus Map Visualization** | ✅ **VERIFIED ON DEVICE** | `DEMO` tab; A→B→C packet animation over fixed demo coordinates |
| **Wi-Fi Transport (TCP Sockets)** | ⚠️ **CODE VERIFIED / PHYSICAL TEST PENDING** | Code audited, socket lifecycles verified; 2-phone physical test pending |
| **BLE Transport (GATT / 2M-PHY)** | ⚠️ **CODE VERIFIED / PHYSICAL TEST PENDING** | Code audited, MTU & GATT callbacks verified; 2-phone physical test pending |
| **X25519 / ECDH Key Exchange** | ❌ **NOT IMPLEMENTED** | Out-of-band pairing code only. `SessionKeyManager.adoptPairingCode()` is the replacement seam. |

---

## 2. Core Architecture

```
┌────────────────────────────────────────────────────────────── On-Device ──┐
│  UI Layer (Jetpack Compose, Retro Amber/Black Aesthetic)                   │
│   LINK · METRICS · CODEC LAB · CONFIG · HW TEST · MODEL CENTER · DEMO      │
│        │ (viewModel.uiState)                          (speech / PTT)       │
│  ┌─────▼─────────┐          ┌───────────────────┐     ┌────────────────┐  │
│  │ MainViewModel │          │ SpeechPipeline    │     │ MetricsEngine  │  │
│  │ State & Ops   │┼────────►│ STT $\to$ Codec   │────►│ Telemetry &    │  │
│  │ Mode Governor │          │ Decode $\to$ TTS  │     │ Transport Sync │  │
│  └─────┬─────────┘          └────────┬──────────┘     └────────────────┘  │
│        │                             │ RetroSpeechCodec (Huffman + Dict)  │
│        │                             ▼                                     │
│  ┌─────▼─────────────────────────────┴─────────────────────────────┐      │
│  │ Transport Core (BaseTransportEngine)                            │      │
│  │  Framing (0x49544E54) · CRC-32 · ACK/NACK Reliability · Dedup   │      │
│  │  Priority Queue · Adaptive Voice Transport · Buffer Purge       │      │
│  └─────┬───────────────────────────────────────────────────────────┘      │
│        │ Extends                                                          │
│  ┌─────┴────────┐ ┌──────────────┐ ┌───────────────┐ ┌─────────────┐     │
│  │ Wi-Fi Engine │ │ BLE Engine   │ │ Simulated     │ │ Mesh Relay  │     │
│  │ TCP Sockets  │ │ GATT 2M-PHY  │ │ Self-Loop     │ │ Flood & TTL │     │
│  └──────────────┘ └──────────────┘ └───────────────┘ └─────────────┘     │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Supported Languages & Codec Performance

iTantra provides a contract for 10 Indian and official languages:

* **Tuned Dictionary Support (Immediate 30%–70% Compression)**:
  * **English (`en`)**: 31.6% compression (Medium: 57B $\to$ 39B, 0.126ms encode).
  * **Hindi (`hi`)**: 58.8% compression (Medium: 97B $\to$ 40B, 0.048ms encode).
  * **Tamil (`ta`)**: 70.5% compression (Medium: 129B $\to$ 38B, 0.030ms encode).
* **Lossless Escape Support (Zero Data Loss)**:
  * Bengali (`bn`), Telugu (`te`), Marathi (`mr`), Gujarati (`gu`), Kannada (`kn`), Malayalam (`ml`), Odia (`or`) encode losslessly using UTF-8 `ESCAPE` framing.

### Empirical Codec Benchmark Table

| Category | Lang | UTF-8 Bytes | Baseline Bytes | Retro Bytes | Wire Packets | Compression % | Encode Latency | Decode Latency | Status |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **SHORT** | EN | 12 B | 12 B | 25 B | 116 B | *-108.3%* | 0.056 ms | 0.095 ms | ✅ Lossless |
| **MEDIUM** | EN | 57 B | 57 B | 39 B | 130 B | **31.6%** | 0.126 ms | 0.062 ms | ✅ Lossless |
| **LONG** | EN | 138 B | 138 B | 95 B | 186 B | **31.2%** | 0.115 ms | 0.051 ms | ✅ Lossless |
| **SHORT** | HI | 25 B | 25 B | 22 B | 113 B | **12.0%** | 0.022 ms | 0.027 ms | ✅ Lossless |
| **MEDIUM** | HI | 97 B | 97 B | 40 B | 131 B | **58.8%** | 0.048 ms | 0.020 ms | ✅ Lossless |
| **LONG** | HI | 284 B | 284 B | 218 B | 309 B | **23.2%** | 0.120 ms | 0.087 ms | ✅ Lossless |
| **SHORT** | TA | 25 B | 25 B | 23 B | 114 B | **8.0%** | 0.009 ms | 0.014 ms | ✅ Lossless |
| **MEDIUM** | TA | 129 B | 129 B | 38 B | 129 B | **70.5%** | 0.030 ms | 0.025 ms | ✅ Lossless |
| **LONG** | TA | 310 B | 310 B | 193 B | 284 B | **37.7%** | 0.100 ms | 0.074 ms | ✅ Lossless |

---

## 4. TTS Engine & Open-Source Compliance

**Honest status: the default TTS engine is a placeholder, not a speech voice.**

* **`OPEN-SOURCE EMBEDDED` (selected by default)**:
  * Implemented in `EmbeddedOpenSourceTTS`.
  * Generates raw 16-bit 16 kHz PCM in memory with an acoustic **formant
    synthesizer** (`OpenSourceFormantSynthesizer`).
  * Plays through Android `AudioTrack`. No proprietary voice service is used.
  * **Limitation:** this is rule-based speech-shaped audio. It is **not
    intelligible TTS** and is not a language voice. It exists to prove the
    decode→synthesize→output path, not to sound like a person.
  * There is **no Piper, sherpa-onnx or eSpeak integration in this repository.**
    A previously documented `NativeTtsBridge` JNI wrapper does not exist; there
    is no `app/src/main/cpp` directory and no NDK source.
* **`DEVELOPMENT FALLBACK`**: `AndroidTTSEngine` uses the device OEM engine.

**Next step for real TTS:** integrate Piper through sherpa-onnx and bundle the
voice models. Until then, multilingual spoken output must be reported as
unavailable.

---

## 5. Session Key & Device Pairing

Packets are sealed with **AES-256-GCM** (12-byte random nonce per encryption,
128-bit tag). A packet is only decryptable by a device holding the same key, so
two devices must be paired before they can talk.

* `SessionKeyManager` — key lifecycle. One device generates the key and shows a
  `SETU-XXXX-…` code; the operator reads it to the peer; the peer adopts it.
  Driven from `CONFIG → DEVICE PAIRING`, or over adb.
* `PairingCode` — Crockford Base32, 52 key symbols + CRC-16/CCITT-FALSE.
  Strict decode: every single-character typo, wrong length, bad checksum and
  non-canonical padding is rejected.
* `KeyEnvelope` — the key is sealed with a **non-exportable Android Keystore**
  AES-256-GCM key before it is persisted. Key material is never written to disk
  in plaintext.
* The UI shows a SHA-256 **fingerprint** (e.g. `A1B2C3D4E5F60718`) so both
  devices can be confirmed to hold the same key. **The key is never displayed.**

> **This is not authenticated key exchange.** The pairing code *is* the key,
> carried by a human over voice or camera. Anyone who reads it can decrypt all
> traffic. `SessionKeyManager.adoptPairingCode()` is the single seam where an
> X25519/ECDH agreement can replace it later without touching `CryptoEngine`,
> `SetuPacket`, `TransportManager` or the UI.

---

## 6. Offline Guarantee & Resource Footprint

* **Zero Cloud Calls**: Audit of entire source codebase verified 0 instances of HTTP/HTTPS REST APIs, cloud STT/TTS SDKs, Firebase, or telemetry trackers.
* **Total APK Size**: 54.34 MB (includes multi-architecture NDK binaries for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`). On 64-bit ARM hardware, only 8.86 MB native library footprint is installed.
* **RAM Footprint (Measured on Vivo V2334)**:
  * Java Heap: **5.89 MB**
  * Native Heap: **26.33 MB**
  * Private Dirty RAM: **22.04 MB**
  * Total PSS: **123.2 MB**
* **Idle CPU Usage**: **0.0%** (zero busy polling; event-driven coroutine flow).
* **Cold Startup Time**: **2.59 seconds** (Measured via `am start -W`).

---

## 7. Building and Running

### Prerequisites
* JDK 17
* Android SDK (API 36)

### Run Unit Tests
```bash
./gradlew.bat testDebugUnitTest
```
*Current test suite: 283 tests, 0 failures, 1 ignored.*

### Build Release APK
```bash
./gradlew.bat assembleRelease
```
*Output: `app/build/outputs/apk/release/app-release.apk` (49.91 MB / ~47.60 MB)*

### Install to Connected Android Phone
```bash
# Debug Build
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release Build
adb install -r app/build/outputs/apk/release/app-release.apk

# Launch App
adb shell am start -n com.example.itantra/.MainActivity
```

---

## 8. APK Size & Architecture Comparison

| Metric | Debug APK | Release APK | Delta | Notes |
| :--- | :---: | :---: | :---: | :--- |
| **Total Size** | 56,987,481 B (~54.35 MB) | 49,915,861 B (~47.60 MB) | **-6.75 MB (-12.4%)** | Release DEX optimization & packaging |
| **DEX Payload** | ~60.85 MB uncompressed | ~53.40 MB uncompressed | Reduced | Dead code elimination & metadata pruning |
| **Native Libs** | 35.31 MB across 4 ABIs | 35.31 MB across 4 ABIs | 0 MB | Preserves Vosk JNI bindings intact |
| **Asset Models** | 0 B | 0 B | 0 B | **No model weights are bundled.** STT models must be sideloaded via MODEL CENTER; until then STT falls back to the OS recognizer and 0 of 10 languages work offline. |
| **Target SDK** | Android 16 (API 36) | Android 16 (API 36) | Identical | Modern Android 16 platform compliance |
| **Min SDK** | Android 7.0 (API 24) | Android 7.0 (API 24) | Identical | Broad legacy hardware backwards compatibility |

---

## 9. Dependencies & Open-Source Licenses

| Component / Library | Version | License | Usage & Compliance Scope |
| :--- | :---: | :---: | :--- |
| **Vosk Android SDK** | 0.3.47 | Apache 2.0 | Embedded offline acoustic speech recognizer |
| **Jetpack Compose BOM** | 2024.10.01 | Apache 2.0 | Declarative UI framework & Material 3 components |
| **Kotlin Standard Library** | 2.0.21 | Apache 2.0 | Core language runtime & coroutines dispatchers |
| **Kotlinx Serialization** | 1.7.3 | Apache 2.0 | Compact JSON & binary serialization |
| **AndroidX Core KTX / Lifecycle** | 1.15.0 / 2.8.7 | Apache 2.0 | Architecture components & lifecycle view models |
| **JUnit 4** | 4.13.2 | EPL 2.0 | JVM unit test runner (test scope only) |
| **Kotlinx Coroutines Test** | 1.9.0 | Apache 2.0 | Coroutine virtual time test harnesses |

*No proprietary, cloud-linked, or restrictive copyleft licenses are included in the application bundle.*

---

## 10. Physical Testing Limitations & Disclosure

* **Single-Device Validated**: All tests involving microphone capture, STT recognition, RetroSpeechCodec compression/decompression, Embedded formant TTS audio synthesis, AudioTrack output, battery governor, and UI responsiveness have been executed and verified on a physical phone (Vivo V2334, Android 16).
* **Multi-Device Physical Status (Pending)**: Physical Wi-Fi Direct socket connectivity, BLE 2M-PHY peripheral/central GATT exchanges, and 3-phone physical multi-hop RF mesh forwarding require additional dedicated physical devices. In the current prototype, their wire serialization, packet framing, CRC-32 validation, and routing state machines are fully simulated and validated by 283 automated tests.