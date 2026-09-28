# iTantra: Setu — PROTOTYPE STATUS (HONEST)

> **Meaning travels. Bandwidth doesn't have to.**
>
> This document records what is **actually verified** versus what is merely
> **implemented**. Nothing here is claimed as working unless a test or a
> physical run backs it. Last updated: 2026-09-28.

---

## 0. Read this first

The prototype is **not** the full SIH system, and it is **not** finished. Two of
the eleven core building blocks you listed are blocked on work that has not been
done. They are named explicitly in §3 so nobody discovers them during a demo.

| Layer | State |
| :--- | :--- |
| Cryptography, packet protocol, IDs, replay defence | **Implemented, unit-tested, sound** |
| Transport abstraction (BLE / Wi-Fi) | **Implemented, never run between two real phones** |
| Device pairing (shared AES-256 key) | **Implemented this session, unit-tested, never run on hardware** |
| Offline multilingual STT | **BLOCKED — no models exist on the device** |
| Offline multilingual TTS | **BLOCKED — current engine is not a real TTS** |
| Silero VAD | **NOT IMPLEMENTED** |
| sherpa-onnx runtime | **NOT PRESENT** |

---

## 1. Platform reality check

* The app is **native Android** — Kotlin + Jetpack Compose. There is no Flutter
  layer. This is the correct choice: raw BLE GATT, TCP sockets, `AudioRecord`,
  `AudioTrack` and Vosk JNI are all far cleaner from Kotlin.
* `minSdk 24`, `compileSdk`/`targetSdk 36` (Android 16). AGP 9.0.1, Kotlin 2.3.20.
* Bluetooth is **Bluetooth LE (GATT)**, not classic RFCOMM. Two phones must be
  BLE-capable and the link is established from the LINK tab.
* `BluetoothTransport` in the architecture diagram maps to `BleTransportEngine`.

---

## 2. Verification ledger

`PASS` = executed and observed. `UNIT` = passes on the JVM, not yet on hardware.
`CODE` = implemented, never executed. `BLOCKED` = cannot work yet.

### Build & suite

| Check | Result | Evidence |
| :--- | :--- | :--- |
| Debug APK assembles | **PASS** | `app-debug.apk`, 54.6 MB, build green |
| Unit test suite | **PASS** | **283 tests, 0 failures, 1 skipped** (was 262 before this session) |
| Cloud / network dependency | **PASS** | 0 REST endpoints, 0 cloud SDKs in source |
| Physical device attached | **FAIL** | no device connected during this session, so §2.2 could not run |

### 2.2 Transport, security and multi-hop

| Test | Expected | Result | Notes |
| :--- | :--- | :--- | :--- |
| TEST 01 Bluetooth A→B | message received | **CODE** | BLE GATT written, zero physical runs |
| TEST 02 Wi-Fi A→B | message received | **CODE** | TCP socket written, zero physical runs |
| TEST 03 Encryption | ciphertext on wire, B decrypts | **UNIT** | in-process only; cross-device was broken, see §3.1 |
| TEST 04 Tamper: ciphertext | auth failure | **UNIT** | `AEADBadTagException` asserted |
| TEST 04 Tamper: auth tag | auth failure | **UNIT** | `AEADBadTagException` asserted |
| TEST 04 Tamper: AAD metadata | auth failure | **UNIT** | receiverId is authenticated |
| TEST 05 Replay / duplicate | second copy rejected | **UNIT** | `ReplayDetector`, 2-minute window |
| TEST 06 Multi-hop A→B→C | C receives once | **UNIT** | `MultiHopRelayEngineTest`, in-process |
| TEST 07 Transport switching | both carry same packet | **CODE** | `TransportManager` is transport-agnostic by construction |
| TEST 08 Event log | every TX logged | **PASS** | `CommunicationEventLog`, JSON + GPS-style records |
| TEST 09 Map visualization | A→B→C animation | **PASS** | `CampusCommunicationMap`, coordinates labelled `SIMULATED_LOCATION` |
| TEST 10 Multilingual speech | STT + TTS per language | **BLOCKED** | see §3.2 |

### Security panel result (as currently reported in-app)

```
Encryption:            AES-256-GCM   PASS (UNIT)
Decryption:            PASS (UNIT)
Tamper Detection:      PASS (UNIT) - ciphertext, tag and AAD
Replay Detection:      PASS (UNIT) - messageId + packetId + sequenceNumber
Cross-device key share: IMPLEMENTED, NOT YET RUN ON HARDWARE
Key exchange:          NOT IMPLEMENTED (out-of-band pairing code only)
```

---

## 3. The two blockers

### 3.1 Cross-device AES-256 — FIXED in code this session, unproven on hardware

**Was:** `CryptoEngine` derived a random 256-bit key per process. Phone A and
Phone B held *different* keys, so every packet died with `AEADBadTagException`
on the receiver. `setSessionKey()` existed but was never called from anywhere.
The suite passed only because all tests share one process and therefore one
key. Success criteria #2, #6, #7 and #11 were **not reachable**.

**Now:** `SessionKeyManager` provisions one key out of band. Phone A generates a
key and shows a `SETU-XXXX-…` code; the operator reads it to Phone B; Phone B
adopts it. Both devices then hold identical key material.

* `PairingCode` — Crockford Base32, 52 key symbols + CRC-16/CCITT, strict decode
  (rejects every single-character typo, wrong length, bad checksum,
  non-canonical padding). 13 unit tests.
* `KeyEnvelope` — the key is sealed with a **non-exportable Android Keystore**
  AES-256-GCM key before it touches disk. It is never written in plaintext.
* The UI shows a SHA-256 **fingerprint**, never key bytes, so an operator can
  confirm both sides match.
* `CONFIG → DEVICE PAIRING` in the app; also drivable over adb.

**Still true and must not be overstated:** this is *not* authenticated key
exchange. The pairing code **is** the key, carried by a human over voice or
camera. Anyone who reads it can decrypt everything. The label appears in the UI
for exactly that reason.

**Replacement seam:** `SessionKeyManager.adoptPairingCode()` is the only place
key material enters the system. Swapping it for X25519/ECDH needs no change to
`CryptoEngine`, `SetuPacket`, `TransportManager` or any UI. No ECDH is
implemented now, deliberately.

### 3.2 Offline multilingual speech — BLOCKED

* `app/src/main/assets/models/` is an **empty directory**. Zero bytes of model
  weights ship in the APK.
* `STTEngine.kt:53` therefore finds no Vosk model and **falls back to
  `AndroidSTTEngine`**, the OS `SpeechRecognizer`. That is not offline-guaranteed
  and is not IndicConformer.
* **0 of the 10 required languages work.** There is no sherpa-onnx, no
  Piper, no Silero VAD, and no IndicConformer in the dependency graph.
* `EmbeddedOpenSourceTTS` is a **hand-written formant synthesizer**, not Piper
  and not sherpa-onnx. It produces audio shaped like speech; it is not
  intelligible TTS and must not be presented as a language voice.

Per your own brief — *"Do not claim all ten work until they have actually been
tested"* — the honest statement is: **zero of ten are currently working.**

Also required and missing: per-language model assets, a single shared runtime
(sherpa-onnx), Silero VAD for utterance boundaries, and real TTS voices.

### 3.3 Fixed this session: language tag mismatch

`SetuPacket` carries BCP-47 (`ta-IN`); the codec wire format used the bare
ISO-639-1 code (`ta`) and `fromCode()` was a strict map — so **every packet was
tagged `UNKNOWN` in transport**. Added `Language.fromBcp47()` / `toBcp47()`,
switched `TransportManager` and all six `createEncrypted` call sites. 8 unit
tests including a regression guard.

---

## 4. Known flaky test (pre-existing, not introduced here)

`TransportEngineIntegrationTest.packet loss is recovered by retransmission and
message still delivers` asserts `result.retransmissions > 0` while relying on a
**random** 35% loss rate over a 30 KB message. Whether any packet is actually
dropped depends on the random draw, so the test is stochastic: it passed 6/6 in
isolation and failed once under full-suite load during this session. It exercises
only `BaseTransportEngine` / `NetworkSimulator`, neither of which was modified.
Left unfixed as out of scope — but it should be made deterministic (seeded RNG,
or a loss pattern that guarantees a drop) before anyone treats a green suite as
reliable.

---

## 5. What is genuinely solid

* **The transport framing layer** (`BaseTransportEngine`, 38 KB): CRC-32,
  ACK/NACK, gap detection, out-of-order reassembly, bounded receive buffers with
  an OOM guard, stale-buffer purging, session isolation. This is the healthiest
  code in the repository — **do not rewrite it.**
* **Authenticated metadata.** The AAD covers protocol version, transmission ID,
  message ID, packet ID, original sender, receiver, language and message type,
  so re-routing a packet breaks authentication.
* **Honest instrumentation.** `EventRecord` labels coordinates
  `SIMULATED_LOCATION`; `isSimulated` is explicit. The convention is good and
  was preserved.
* **A large test suite** that was already green before this session.

---

## 6. Next actions, in order

1. **Get two phones on a desk.** Nothing else matters until §2.2 runs.
   `run_setu_acceptance_test.ps1` drives the whole checklist over adb and emits
   a PASS/FAIL/UNVERIFIED report plus `setu_acceptance_report.json`.
2. **Pair them** in `CONFIG → DEVICE PAIRING`, confirm matching fingerprints.
3. Run the harness once with `-Transport BLUETOOTH`, once with `-Transport WIFI`.
   Compare: the packet format is identical, only the medium changes.
4. **Provision real STT models** for `en`, `hi`, `ta` (Vosk small, ~40 MB each)
   and wire them through MODEL CENTER. Only then can TEST 10 be attempted.
5. **Replace the TTS stub** with Piper via sherpa-onnx, or keep it and label it
   "formant placeholder, not speech".
6. Only after 4–5: attempt the Tamil/Hindi/English end-to-end spoken test, and
   only report the languages actually heard working.

---

## 7. Claim discipline

State these only when true:

* ✅ "AES-256-GCM with authenticated routing metadata" — true, unit-tested.
* ✅ "Duplicate packets are detected by messageId + packetId + sequenceNumber" — true.
* ✅ "The same packet format is used over BLE and Wi-Fi" — true by construction, unproven on hardware.
* ✅ "Coordinates are simulated" — true, and labelled in the app.
* ❌ "Secure key exchange" — **false.** Out-of-band code only.
* ❌ "Ten-language offline speech" — **false.** Zero of ten.
* ❌ "Piper / sherpa-onnx / IndicConformer" — **false.** Not present.
* ❌ "Production-hardened" — **false.** Prototype; transport layer unrun on real devices.
