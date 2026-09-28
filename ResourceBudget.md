# iTantra / Setu — Resource Budget Ledger

> Strictly distinguishes **MEASURED** results from **TARGET** / **HYPOTHESIS** figures.
> Updated after each implementation phase. Last updated: Phase 1 (Baseline).

---

## 1. Measured Baseline Ledger (Phase 1)

| Metric | Category | Current Status | Measured Value | Target | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Debug APK Size** | Storage | **MEASURED** | `54.3 MB` (56,949,434 B) | `< 30 MB` | All 4 ABIs bundled (armeabi-v7a, arm64-v8a, x86, x86_64) |
| **Release APK Size** | Storage | **MEASURED** | `47.6 MB` (49,932,245 B) | `< 25 MB` | ProGuard disabled in current release block |
| **Installed Storage (No Models)** | Storage | **MEASURED** | `~62 MB` | `< 40 MB` | Clean install base |
| **Model Storage** | Storage | **MEASURED** | `0 bytes` | `~75 MB` | `assets/models/` is empty in repo; zero offline Indic models |
| **Wire Bytes (Short Msg: 12B text)** | Network | **MEASURED** | `123 bytes` (Z-BWE) / `444 bytes` (JSON) | `< 130 bytes` | 72.3% reduction achieved via Z-BWE binary codec |
| **Wire Bytes (Medium Msg: 57B text)** | Network | **MEASURED** | `165 bytes` (Z-BWE) / `500 bytes` (JSON) | `< 170 bytes` | 67.0% reduction achieved via Z-BWE binary codec |
| **Wire Bytes (Long Msg: 138B text)** | Network | **MEASURED** | `244 bytes` (Z-BWE) / `608 bytes` (JSON) | `< 250 bytes` | 59.9% reduction achieved via Z-BWE binary codec |
| **Indic Text Compression (Brahmic vs UTF-8)** | Network | **MEASURED** | `57.5% - 60.7% reduction` | `> 50%` | Pure bitwise/offset packing across all 9 Indic scripts |
| **Wire Serialization Overhead** | Network | **MEASURED** | `111 bytes` (Z-BWE) vs `432 bytes` (JSON) | `< 120 bytes` | Fixed 48B header + raw 12B nonce + raw 16B tag + UTF8 IDs |
| **Unit Test Suite** | Reliability | **MEASURED** | `315 passed, 0 failed, 1 ignored` | 100% pass | 22.4s execution; 32 new tests added across Phases 2-6 |
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
| **TTS Latency (Text to Speech)** | Latency | **MEASURED** | `< 5 ms` (DSP buzz) | `< 400 ms` | Current engine is synthetic formant buzz, not intelligible voice |

---

## 2. Target vs Measured Tracking Ledger

* Any number labeled **TARGET** represents an architectural objective.
* Any number labeled **MEASURED** has been extracted from a physical test run, binary inspection, or test benchmark execution.
