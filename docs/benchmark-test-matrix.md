# Benchmark / Test Matrix

Baseline run: `gradlew.bat testDebugUnitTest` → **398 tests · 29 suites ·
0 failures, 1 skipped** (baseline 321 → current; latest measured full run).

Ctrl-f each keyword in the JVM suite to reproduce the exact test.

## Codec

| Case | Expectation | Suite file |
|---|---|---|
| ASCII dictionary round-trip | exact match, > x % compression | RetroSpeechCodec codec tests |
| Tamil/Hindi dictionary round-trip | exact match | codec tests |
| Out-of-dict words | phoneme fallback, still lossless | codec tests |
| 7 Escape languages (bn/te/mr/gu/kn/ml/or) | lossless, ~0 % compression shown honestly | codec tests |
| Toggle vs BaselineCodec | both verbose, comparable byte counts | CodecComparisonEngineTest |
| Encoding time bound | recorded, not staged | codec lab tests |

## Packetizer / reassembly

| Case | Expectation | Suite file |
|---|---|---|
| Ordered packets | exact payload reassembly | `MeshCoreTest` |
| Out-of-order (reversed) | reassembles anyway | `MeshCoreTest` |
| Duplicates | counted once, delivered once | `MeshCoreTest` |
| Missing tail at END | gap detected, no half-message emitted, purge drops it | `MeshCoreTest` |
| Hostile END count `0x7FFFFFFF` | dropped + `droppedMessages++`, no allocation | `MeshCoreTest` |
| Emergency flag | rides through reassembly | `MeshCoreTest` |
| Stale half-message | purged after retention, counted | `MeshCoreTest` |

## Transport (Retro link, JVM)

| Case | Expectation | Suite file |
|---|---|---|
| Capability handshake (proto v1 · codec v2) | session established | transport/… tests |
| Loss injection | selective NACK + retransmit, recovery ≤ 3 tries | transport reliability tests |
| Corrupt frames | counted, never delivered | transport tests |
| Duplicate delivery | re-ACK, single delivery | transport tests |
| Hostile END / huge seq | dropped, counted, no OOM path | `PhaseComponentsTest`, transport tests |
| Stale receive-buffer purge | bounded memory, loss counted | transport tests |

## Adaptive governor

| Case | Expectation | Suite file |
|---|---|---|
| Loss = 8 % (100-packet floor) | NORMAL + 1024 B ceiling | `PhaseComponentsTest` |
| Loss = 20 % | LOW → 512 B | `PhaseComponentsTest` |
| Loss = 50 % | EMERGENCY → 256 B | `PhaseComponentsTest` |
| Loss = 0 % | HIGH → 2048 B | `PhaseComponentsTest` |
| RTT 700 ms alone | LOW | `PhaseComponentsTest` |
| Fraction vs real traffic totals | NORMAL at ~5 %, LOW at ~23 %, EMERGENCY at ~99 % | `PhaseComponentsTest` |
| reset() | back to NORMAL / 1024 B | `PhaseComponentsTest` |

## Mesh

| Case | Expectation | Suite file |
|---|---|---|
| Hop envelope round-trip (TTL/hops/origin pad) | exact | `MeshCoreTest` |
| Duplicate / self-echo drop | delivered once | `MeshCoreTest` |
| TTL budget | no relay past 0 | `MeshCoreTest` |
| Mesh relay metrics | hops/ttl/dropped real | mesh stats tests |

## Ops / power / emergency / model

| Case | Expectation | Suite file |
|---|---|---|
| Battery 80 % | HEALTHY, everything allowed | `LowPowerControllerTest` |
| Battery 17 % | LOW — HIGH passes, LOW waits, extras deferred | `LowPowerControllerTest` |
| Battery 5 % | CRITICAL — only CRITICAL transmits | `LowPowerControllerTest` |
| Thresholds / clamp / change log | boundary-exact, honest | `LowPowerControllerTest` |
| Op-mode transitions | history + cause strings | `OperationAndEmergencyControllerTest` |
| Emergency latch → ACK → close | state machine correct | `OperationAndEmergencyControllerTest` |
| Model catalog | 3 STT + 10 TTS wanted; honest default = 0 usable | `ModelManagerTest` |
| Probe semantics | PRESENT iff the file really exists | `ModelManagerTest` |
| TTS on receive / suppression | withheld utterances counted, never silent | `TTSRegistryAndPresenceTest` |

## Metrics honesty regression

- STT latency recorded **only** when `recordingStartTime > 0` (manual sends report
  `sttMeasured = false`).
- Transport counters on METRICS come from `LinkMetrics` (wire) via `MetricsEngine.syncTransport`.
- UI renders `NOT MEASURED` for any non-positive latency instead of `0`.

## Baseline metrics / benchmark lab

| Case | Expectation | Suite file |
|---|---|---|
| Cold start vs steady state | cold ≥ steady − slack; p95 ≥ min | `BaselineMetricsTest` |
| Mixed-corpus wire ≤ UTF-8, each ≤ 512 B | honest compression envelope | `BaselineMetricsTest` |
| Heap delta over 500 round-trips | bounded, labeled JVM estimate | `BaselineMetricsTest` |
| Physical latency/wire table (9 cells) | measured, lossless | `PhysicalMeasuredBenchmarkTest` |

## Phase 8-13 regression

| Case | Expectation | Suite file |
|---|---|---|
| Hysteresis: 3 consecutive samples to commit | mode stable, no churn | `PhaseComponentsTest` |
| Weak RSSI / deep queue floors | conservative degrade, never upgrade | `PhaseComponentsTest` |
| Health unknown until first sample | honest initial state | `PhaseComponentsTest` |
| Byte-budget queue displacement / oversized drop | weakest lowest first, counted | `PhaseComponentsTest` |
| Lossless layer 2 opt-in, loss-tolerant reassembly | base never blocked | `ProgressiveLayeredPipelineTest` |
| SUTRA domains/dialects/romanized keywords | English + romanized hi/ta | `SutraFrameTest` |
| Corrupt/missing model → ERROR, valid → READY+evict | lifecycle honest | `StreamingAsrAndModelLifecycleTest` |
| Uncertain STT tokens never ship as truth | flags guard semantics | `StreamingAsrAndModelLifecycleTest` |
| SUTRA router: meaning + importance + ids | deterministic | `SemanticDispatchRouterTest` |

## Build checks

| Command | Contract |
|---|---|
| `gradlew.bat testDebugUnitTest` | 398 tests · 0 failures · 1 skipped (must never regress below) |
| `gradlew.bat assembleDebug` | green APK build |
| `gradlew.bat testDebugUnitTest --offline --tests "com.example.itantra.transport.TransportEngineIntegrationTest" --tests "com.example.itantra.transport.MeshTransportIntegrationTest"` | deterministic (seed-pinned simulators) — repeatable under load |

## On-device hardware checklist (HW TEST tab, not replaceable by JVM)

- Bluetooth GATT checklist: advertising → scanning → connect → service/characteristic ready →
  notifications enabled (real OS events).
- Wi-Fi two-phone socket transfer.
- Model Center install-then-recognize flow.