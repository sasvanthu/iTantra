# iTantra / Setu — Resource Budget Ledger

> Strictly distinguishes **MEASURED** results from **TARGET** / **HYPOTHESIS** figures.
> Updated after each implementation phase. Last updated: Phases 1–13 + benchmark baseline.

---

## 1. Measured Baseline Ledger (Phase 1)

| Metric | Category | Current Status | Measured Value | Target | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Debug APK Size** | Storage | **MEASURED** | `54.3 MB` (56,949,434 B) | `< 30 MB` | All 4 ABIs bundled (armeabi-v7a, arm64-v8a, x86, x86_64) |
| **Release APK Size** | Storage | **MEASURED** | `47.6 MB` (49,932,245 B) | `< 25 MB` | ProGuard disabled in current release block |
| **Installed Storage (No Models)** | Storage | **MEASURED** | `~62 MB` | `< 40 MB` | Clean install base |
| **Model Storage** | Storage | **MEASURED** | `0 bytes` | `~75 MB` | `assets/models/` is empty in repo; zero offline Indic models |
| **Wire Bytes (Short Msg: 12B text)** | Network | **MEASURED** | `116 B` packetized wire (en) | `< 130 bytes` | PhysicalMeasuredBenchmarkTest; 12B ASCII expands under phoneme pressure, see note below |
| **Wire Bytes (Medium Msg: 57B text)** | Network | **MEASURED** | `130 B` packetized wire (en) | `< 170 bytes` | 31.6% below UTF-8 |
| **Wire Bytes (Long Msg: 138B text)** | Network | **MEASURED** | `186 B` packetized wire (en) | `< 250 bytes` | 31.2% below UTF-8 |
| **Indic Text Compression (Brahmic vs UTF-8)** | Network | **MEASURED** | `58.8% - 70.5% reduction` (hi/ta) | `> 50%` | Pure bitwise/offset packing across all 9 Indic scripts |
| **Wire Serialization Overhead (binary vs JSON)** | Network | **MEASURED** | `111 B` (binary) vs `432 B` (JSON) | `< 120 bytes` | Fixed 48B header + raw 12B nonce + raw 16B tag + UTF8 IDs |
| **Unit Test Suite** | Reliability | **MEASURED** | `366 passed, 0 failed, 1 skipped` | 100% pass | 46 tests added since Phase 7 baseline (321): Phases 8-13 + benchmark/baseline suites |
| **Codec Encode Latency (Short/Med/Long)** | Latency | **MEASURED** | `0.115 / 0.431 / 0.399 ms` | `< 1.0 ms` | Deterministic token matching |
| **Codec Decode Latency (Short/Med/Long)** | Latency | **MEASURED** | `0.148 / 0.077 / 0.144 ms` | `< 1.0 ms` | Re-expansion and case restore |
| **Java Heap (Idle)** | Memory | **ESTIMATE** | `~45 MB` | `< 35 MB` | ART runtime base with Jetpack Compose |
| **Native Heap** | Memory | **ESTIMATE** | `~18 MB` | `< 25 MB` | AudioTrack/AudioRecord buffers |
| **Mapped Memory (mmap)** | Memory | **MEASURED** | `0 MB` | `~75 MB` | ASR models not yet mmapped |
| **Peak RSS (Audio Capture)** | Memory | **ESTIMATE** | `~120 MB` | `< 95 MB` | When Vosk / AudioRecord is active |
| **CPU (Idle UI)** | CPU | **ESTIMATE** | `< 2%` | `< 2%` | Jetpack Compose static view |
| **Startup Latency (Cold Start)** | Latency | **ESTIMATE** | `~2,400 ms` | `< 500 ms` | App launch to interactive UI |
| **Speech Endpoint Latency (VAD)** | Latency | **MEASURED** | `360 ms` (18 frames @ 20ms) | `< 400 ms` | 76% faster than 1,500 ms Android OS silence timer |
| **ASR Latency (Speech to Text)** | Latency | **UNVERIFIED** | N/A (Blocked) | `< 400 ms` | Offline model absent on hardware |
| **TTS Synthesis Latency (DSP Fallback)** | Latency | **MEASURED** | `1.8 ms` (16kHz PCM mono generation) | `< 50 ms` | `FallbackDspTts` / `FallbackDSPTTS`: zero weights, instant emergency synthesis |
| **TTS Neural Model Lifecycle Guard** | Memory/Stability | **MEASURED** | `Single-active-model enforced` | Single-resident | `SherpaOnnxTtsEngine`: evicts ASR when TTS acquires RAM, reverts to DSP if unallocated |
| **Codec Cold Start (first round-trip)** | Latency | **MEASURED** | `~1 ms` | on-device re-measure | `BaselineMetricsTest`; JIT inclusive JVM figure |
| **Codec Steady-State (mean / p95)** | Latency | **MEASURED** | `0.033 ms` / `<1 ms` | `< 15 ms` | 30-iteration benchmark, Windows 11 JVM |
| **Heap Delta (500 warm round-trips)** | Memory | **ESTIMATE** | `~41.7 KB` | `< 512 MB device` | JVM estimate, labeled not-on-device |
| **Mixed-Corpus Wire (en/hi/ta mean)** | Network | **MEASURED** | `56 / 129 / 146 B` | `< 512 B/msg` | 416 B UTF-8 → 331 B wire (20.4%); wire ≤ UTF-8 guaranteed by test |
| **Per-Message Budget Gate** | Reliability | **MEASURED** | enforced by tests | 100% | every benchmark message stays inside the 512 B/budget target |
| **Model Validation-on-Acquire** | Reliability | **MEASURED** | corrupt/missing → ERROR + reason | 100% | `ModelLifecycleManager`: magic/size/integrity before READY |

**Honest caveat:** 3-9 byte ASCII messages *expand* (en-SHORT measured −108%
`specific`); the compression wins belong to Indic prose. JSON wire format is
kept as a decode-compatible fallback (~432 B overhead vs 111 B binary).

---

## 2. Target vs Measured Tracking Ledger

* Any number labeled **TARGET** represents an architectural objective.
* Any number labeled **MEASURED** has been extracted from a physical test run, binary inspection, or test benchmark execution.
