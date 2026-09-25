# SIH Requirement Traceability Matrix

Team iTANTRA — prototype deliverable. Mapping each stated SIH/theory-of-operation requirement to the
in-repo implementation and the test that pins it.

Legend: ✅ implemented + tested · ⛔ not startable on this prototype · ⚠ partial/needs device.
"NOT MEASURED" values are never fabricated zeros (honesty rule).

| # | Requirement | Implementation | Verified by |
|---|---|---|---|
| R1 | End-to-end offline voice relay (speak → wire → hear) | `SpeechPipeline`, `SimulatedTransport` self-loop, `VoskSTTEngine`, `AndroidTTSEngine` receive speak | `PipelineSimulationTest` (pipeline round-trip), 219 JVM suite |
| R2 | Operate with no internet / no cloud speech | engines purely local; no network calls; `assets/models` empty by design | code scan + `TTS_AUDIT.md` verdict |
| R3 | 10 Indian languages supported | `Language` enum: en hi ta bn te mr gu kn ml or; codec lossless for all 10 | Codec round-trip tests (dict + ESCAPE) |
| R4 | Offline STT (priority en/hi/ta) | Vosk wired for en/hi/ta; MODEL CENTER filesDir load (`models/<code>/`, `stt-<LANG>.zip`) | `ModelManagerTest`, Vosk resolve path |
| R5 | Honest STT status for other 7 languages | catalog lists PLANNED; UI shows `STT PLANNED`, never claims working | `ModelManagerTest` |
| R6 | Offline TTS for received messages | speak-on-receive with measured latency; Android fallback = dev-only (documented) | `TTSRegistryAndPresenceTest` |
| R7 | Low-bandwidth codec (compression first) | RETRO token+phoneme dictionary encodes en/hi/ta strongly; ESCAPE is lossless fallback | codec benchmarks in suite |
| R8 | Lossless integrity | CRC32 per packet; decode round-trip exact-match assertions | core codec round-trip tests |
| R9 | Mesh / no-infrastructure networking | Mesh flood + store-and-forward relay over edge engines (Wi-Fi/BLE) | `MeshCoreTest`, mesh relay tests |
| R10 | Reliable transfer on lossy links | ACK/NACK + retransmission (3 tries), gap detection, dedup, re-ACK | transport reliability tests |
| R11 | Adaptive to link conditions | `AdaptiveLinkGovernor` — measured loss (fraction of traffic), RTT, throughput; mode→max packet size | `PhaseComponentsTest` |
| R12 | Emergency broadcast priority | CRITICAL priority queue drains first; send `forceEmergency` | priority-queue tests |
| R13 | Emergency from peer | incoming CRITICAL auto-raises + alarm latch + reason log | `OperationAndEmergencyControllerTest` |
| R14 | Low battery gov re reserving emergency channel | `LowPowerController` gates non-emergency sends at LOW/CRITICAL | `LowPowerControllerTest` |
| R15 | Operation modes (NORMAL/DRILL/EVACUATION) | `OperationModeController` with transition history | `OperationAndEmergencyControllerTest` |
| R16 | Honest performance metrics | Transport counters synced to engine; `NOT MEASURED` UI; STT latency gated on real recording | `MetricsScreen` assertions, transport-sync verif |
| R17 | Model management / deployment | `ModelManager` wanted-catalog + probe; MODEL CENTER tab install docs | `ModelManagerTest` |
| R18 | Demo-ability single-device | DEMO tab: SIM LINK self-loop + SEND SAMPLE + live packet lane | manual demo (script) |
| R19 | Field-hardened protocol (hostile input) | frame cap, packet cap, `MAX_DATA_PACKETS`, stale purge, hostile-seq guard | `MeshCoreTest` hostile END, transport tests |
| R20 | Battery/about screen metric reporting | HW TEST lab + Metrics battery row (real OS value) | `HardwareTestViewModelTest`, manual |
| R21 | Multi-phone field test | `BleTransportEngine` GATT checklist in HW TEST; Wi-Fi TCP two-phone    | on-device HW lab (needs 2 phones) |
| R22 | Final SIH offline TTS (open-source voices) | NOT BUNDLED — build step; Android TTS declared as development fallback only | `TTS_AUDIT.md` |

## Explicit non-claims (already-true honesty)

- No offline model bytes are shipped in this repo (recognizer/voice count 0 unless operator installs).
- Compression % for the 7 escaped languages is ~0 % by design and shown as `OVERHEAD`/`0.0%`, never padded.
- Mesh hop count / neighbors are not reported because the build does not implement neighbor
  discovery — the UI says so rather than inventing a topology.