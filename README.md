# iTANTRA — SIH (Smart India Hackathon) Prototype

**Offline voice relay for emergency operations** — speak in your language, and the app encodes the
message with an ultra-lightweight codec, transports it over Wi-Fi / Bluetooth / LoRa-mesh style
flooding, and speaks it back to the receiving operator. Everything runs on-device: **no internet,
no cloud speech APIs, no remote servers**.

```
 SPEAK ──► STT (Vosk, offline) ──► RETRO CODEC (compress) ──► PACKETS ──► TRANSPORT
   (operator)                                                        (Wi-Fi socket / BLE GATT / mesh flood)
 SPOKEN ◄── TTS (offline) ◄── DECODE ──► REASSEMBLE ◄── FEED ──► (receiving operator)
```

This repository is a **working pre-SIH prototype**: the entire voice→codec→wire→decode→voice path
exists, runs end-to-end on one or two Android devices, and is covered by 219 JVM unit tests
(29 suites, 0 failures). Every metric on screen is a **real measurement**; nothing is fabricated.

---

## 1. What problem does it solve?

In disaster / evacuation scenarios there is often no cellular or internet coverage. Rescue
operators still need to coordinate: "27 injured, route 4N1 blocked". Available networks
(Wi-Fi Direct, Bluetooth, peer-to-peer radio) are **low band-width** and **lossy**.

iTANTRA is a phone-to-phone offline voice relay that:
- understands 3 practical languages offline (English, Hindi, Tamil) and reports the *true* status of
  all 10 (see [Honest language status](#6-supported-languages));
- compresses speech text so it fits through low-bandwidth links;
- pushes messages over Wi-Fi, Bluetooth, or a store-and-forward mesh flood with ACK/NACK reliability;
- prioritises the emergency path and adapts its packet size to the measured link health;
- keeps the operator honest — every figure printed is measured, or explicitly `NOT MEASURED`.

## 2. Build

- **Language / runtime** — Kotlin (JVM + Android); no Java source, no Rust, no C++ AOT.
- **SDK** — `compileSdk 36`, `targetSdk 36`, `minSdk 24`.
- **UI** — Jetpack Compose (Material 3), retro-terminal theme.
- **Unit tests** — plain JUnit4 on the JVM (no instrumentation needed for 99% of the logic).

```bash
# Full unit-test suite on the JVM
gradlew.bat testDebugUnitTest
# Build a debug APK
gradlew.bat assembleDebug
```

Baseline checked at every change: **219 tests / 29 suites / 0 failures** (see
[docs/benchmark-test-matrix.md](docs/benchmark-test-matrix.md)).

## 3. Quick demo (no peer, no model, no internet)

1. Install the APK on one phone, open the app.
2. Tap the **DEMO** tab → press **SIM LINK** (in-process simulated transport), then **SEND SAMPLE**.
3. Watch the **LIVE PACKET LANE**: real TX/RX events, real retransmissions, real ACK round-trips
   (the simulated link is a full self-loop, not a mock).
4. Open **METRICS** — every row is a real reading.
5. For speech: install an offline Vosk model via the **MODEL CENTER** tab (see §7), then speak from
   the **LINK** tab.

A walked-through 3-minute script is in [docs/demo-script.md](docs/demo-script.md).

## 4. Feature map

| Area | What is real | Status |
|---|---|---|
| Offline STT | Vosk recognition wired for **en / hi / ta**; loads models installed on-device (MODEL CENTER) or from assets | ✅ engine + install path |
| Offline TTS | Speak received messages through Android device TTS (development fallback) with measured latency | ✅ wired + measured |
| Low-bandwidth codec | RETRO codec: token + phoneme dictionary encoders (en/hi/ta), **lossless** ESCAPE for the other 7 languages | ✅ lossless, honest |
| Transport | TCP Wi-Fi, BLE GATT (MTU-512), simulated self-loop, mesh flooding overlay | ✅ |
| Reliability | Capability handshake, framing, CRC, ACK/NACK, retransmission, out-of-order + duplicate handling, gap detection | ✅ |
| Adaptive link | Bandwidth mode (HIGH/NORMAL/LOW/EMERGENCY) derived from *measured* loss/RTT/throughput; drives max packet size | ✅ |
| Emergency | CRITICAL priority queue, auto-raise on incoming emergency, emergency-only reserve on critical battery | ✅ |
| Low power | Battery-driven power profile gates non-emergency sends | ✅ |
| Store-and-forward | Mesh relay buffers flood-isolated messages until a next hop appears | ✅ |
| Metrics honesty | `NOT MEASURED` instead of `0`; transport counters wired to the real wire values | ✅ |
| Model Center | 10-language STT/TTS status table (PRESENT / MODEL MISSING / PLANNED) + install instructions | ✅ |
| Demo mode | Guided autopilot, self-check table, topology view, live packet lane | ✅ |

## 5. Architecture

```
┌────────────────────────────────────────────────────────────── On-device ──┐
│  UI (Compose)                                                              │
│   LINK · METRICS · CODEC LAB · CONFIG · HW TEST · MODEL CENTER · DEMO      │
│        │ (viewModel.uiState)                          (send/stop/speak)    │
│  ┌─────▼─────────┐          ┌───────────────────┐     ┌────────────────┐  │
│  │ MainViewModel │          │ SpeechPipeline    │     │ MetricsEngine  │  │
│  │ state + ops   │┼────────►│ STT→encode→send   │────►│ per-message +  │  │
│  │ op-mode/emgry │          │ recv→decode→TTS   │     │ transport sync │  │
│  └─────┬─────────┘          └────────┬──────────┘     └────────────────┘  │
│        │                             │ RTTO codec (RetroSpeechCodec)    │
│        │                             ▼                                  │
│  ┌─────▼─────────────────────────────┴─────────────────────────────┐   │
│  │ TransportCore (BaseTransportEngine)                             │   │
│  │  framing/CRC · ACK/NACK+retransmit · reassembly · dedup          │   │
│  │  priority queue · AdaptiveLinkGovernor · stale-buffer purge      │   │
│  └─────┬───────────────────────────────────────────────────────────┘   │
│        │ extends                                                     │
│  ┌─────┴────────┐ ┌──────────────┐ ┌───────────────┐ ┌─────────────┐  │
│  │ Wifi (TCP)   │ │ Ble (GATT)   │ │ Simulated     │ │ Mesh        │  │
│  │ sockets      │ │ adv/scan/not │ │ self-loop     │ │ flood+relay │  │
│  └──────────────┘ └──────────────┘ └───────────────┘ └─────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

`BaseTransportEngine` is medium-agnostic, pure Kotlin, and drives all four engines — so the full
reliability stack is JVM-tested.

- Architecture + data flow + mesh topology + send/emergency sequence diagrams:
  [docs/architecture.md](docs/architecture.md)
- SIH requirement-by-requirement traceability: [docs/requirement-matrix.md](docs/requirement-matrix.md)

## 6. Supported languages

| Language | Code | Encoding | Offline STT | TTS |
|---|---|---|---|---|
| English | en | dictionary (lossless) | READY* | Android fallback |
| Hindi | hi | dictionary (lossless) | READY* | Android fallback |
| Tamil | ta | dictionary (lossless) | READY* | Android fallback |
| Bengali, Telugu, Marathi, Gujarati, Kannada, Malayalam, Odia | bn te mr gu kn ml or | ESCAPE (lossless, 0% ratio) | PLANNED | Android fallback |

\* **READY means the engine is wired and the model can be installed**; recognition works only while an
actual model is present. See §7 and the MODEL CENTER tab — the app never claims a language works when
its model is missing.

## 7. Offline models (MODEL CENTER)

This APK ships **zero model bytes** (honest). To enable offline STT for en/hi/ta:

1. Get a Vosk small model for the language (e.g. `vosk-model-small-en-us` ~40 MB, `...-hi` ~26 MB, `...-ta` ~24 MB).
2. Zip it and place on the device at
   `Android/data/com.example.itantra/files/<stt-ENGLISH.zip | stt-HINDI.zip | stt-TAMIL.zip>`
   — **or** copy an already-unzipped model directory to
   `Android/data/com.example.itantra/files/models/<en|hi|ta>/`.
3. Re-open **MODEL CENTER**: the STT column flips to `STT READY`, recognition goes fully offline.

TTS: Android device voices are used as a **development fallback** so received text can be heard during
review. The final SIH build targets open-source TTS voices (Piper / eSpeak-NG); that is a
packaging change, not a code change — see [TTS_AUDIT.md](TTS_AUDIT.md).

## 8. Testing

- `gradlew.bat testDebugUnitTest` — 219 tests / 29 suites / 0 failures.
- Coverage spans: codec (dict + ESCAPE lossless round-trips), packetizer/reassembler (hostile END
  guard, dedup, gaps), transport reliability (ACK/NACK, retransmit, purge), mesh routing (duplicate
  suppression, TTL), adaptive governor (loss-rate math), operation-mode + emergency state machines,
  low-power gating, model-manager honesty, metrics screen behavior.
- See [docs/benchmark-test-matrix.md](docs/benchmark-test-matrix.md).

## 9. Honesty rules (do not regress)

1. A metric on screen is a **measured** value or it is `NOT MEASURED` — never a fabricated zero.
2. A language is listed as supported only when its real stack (model present, engine wired) is runnable.
3. `AdaptiveLinkGovernor` loss/retransmission rates are **fractions of observed traffic** — no `/100`
   hack, no invented percentages.
4. STT latency is recorded **only** when speech was actually recorded before the send.
5. Transport counters (sent / received / retransmit / loss / bytes / duplicates / corrupt) are synced
   from the wire layer — not hard-coded.

## 10. Project layout (key paths)

```
app/src/main/java/com/example/itantra/
  codec/             Language, packet types, RETRO + baseline codecs, packetizer
  protocol/          Packet, HopPacket, MeshRouter, MeshReassembler, RetransmissionManager
  transport/         BaseTransportEngine + Wifi/Ble/Simulated/Mesh, NetworkSimulator, governor
  speech/stt/        STTEngine, VoskSTTEngine (filesDir + asset model resolution)
  speech/tts/        TTSEngine, AndroidTTSEngine, PresenceAwareTTS, TTSRegistry
  metrics/           MetricsEngine (per-message snapshot + transport sync)
  ops/               OperationModeController, EmergencyController, LowPowerController
  data/              ModelManager (model catalog + probe), experiment engine, codec lab, retro codec
  ui/main/           MainViewModel + LINK, METRICS, CODEC LAB, CONFIG, HW TEST, MODEL, DEMO screens
```

## 11. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| MODEL CENTER shows all `MODEL MISSING` | Models are not shipped — install per §7 |
| `STT` does nothing on the LINK tab | No model installed; install one, or use SIMULATED mode |
| BLE won't connect between two phones | MTU/notification setup on device; check HW TEST GATT checklist |
| `LAST SEND` says `deferred by power governor` | Battery is below the reserve threshold; charge or use emergency path |
| Mesh node-to-node relay isn't visible | Mesh needs physical nodes; use WIFI/SIMULATED for point-to-point demos |