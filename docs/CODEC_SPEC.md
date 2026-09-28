# CODEC SPEC — Brahmic Script Packing + Phoneme Token Fallback

> `RetroSpeechCodec` (formerly BWE / "Z-BWE") + `ProgressiveLayeredPipeline`.
> All "MEASURED" figures come from `PhysicalMeasuredBenchmarkTest` /
> `BaselineMetricsTest` on a Windows 11 JVM, 20 homogeneous iterations per cell.

## 1. Two-stage encoding

1. **Brahmic stage** (`BrahmicCodec`): packs every supported Indic script code
   point into compact bit-width tokens via pure bitwise/offset packing. Lossless
   over its claimed ranges (verified exhaustively, see §5).
2. **Token stage**: dictionary tokens where present, deterministic **phoneme
   fallback** for out-of-dictionary words — the fallback is also lossless, so
   every decode yields the exact original string.

Decode is a re-expansion + case-restore; there is no trained model anywhere in
the codec path.

## 2. Claimed lossless ranges (all exhaustive-tested)

| Range | Coverage |
| :--- | :--- |
| 9 Indic blocks | Devanagari, Bengali, Telugu, Tamil, Malayalam, Gujarati, Kannada, Odia, Gurmukhi + Marathi practice block |
| Prachalit `<->` Devanagari | conjugate-subset, verified lossless both directions |
| Printable ASCII | every code point round-trips |
| Indic digits | every script's digit set round-trips |
| Rapid script switching | en-hi-ta-mr-bn-te-gu-kn-ml-or round-trips while switching per message |
| Truncated payloads | decode yields a valid **prefix** of the full decode; header-only payloads decode to `""` (a valid prefix) |
| Fuzz | seeded whole-code-point corpora round-trip identity |

A truncation **never** yields a corrupt expansion: it yields fewer characters.

## 3. Latency + size (MEASURED, `PhysicalMeasuredBenchmarkTest`)

| CAT | LANG | UTF-8 B | Retro B | WIRE B (packetized) | COMP | ENC ms | DEC ms |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| SHORT | en | 12 | 25 | 116 | −108.3% | 0.068 | 0.085 |
| MEDIUM | en | 57 | 39 | 130 | 31.6% | 0.301 | 0.188 |
| LONG | en | 138 | 95 | 186 | 31.2% | 0.576 | 0.420 |
| SHORT | hi | 25 | 22 | 113 | 12.0% | 0.057 | 0.030 |
| MEDIUM | hi | 97 | 40 | 131 | 58.8% | 0.223 | 0.137 |
| LONG | hi | 284 | 218 | 309 | 23.2% | 0.801 | 0.430 |
| SHORT | ta | 25 | 23 | 114 | 8.0% | 0.059 | 0.020 |
| MEDIUM | ta | 129 | 38 | 129 | 70.5% | 0.106 | 0.096 |
| LONG | ta | 310 | 193 | 284 | 37.7% | 0.401 | 0.166 |

Honest note: 3–9 byte ASCII messages (e.g. `I need help.`, 12 B) can be
**larger** after phoneme encoding + fixed frame pressure (see the en-SHORT cell).
Gain always comes for Indic script prose; tiny ASCII does not always win.

## 4. Mixed-corpus reality (MEASURED, `BaselineMetricsTest`)

- en+hi+ta corpus, UTF-8 416 B → wire mean 331 B (20.4% reduction overall;
  wire never exceeds UTF-8 on the mixed corpus).
- Per-message wire means: en 56 B, hi 129 B, ta 146 B — all comfortably inside
  the 512 B/message target.
- Encode/decode are sub-millisecond on JVM; cold start of the very first
  round-trip ≈ 1 ms vs steady-state mean ≈ 0.033 ms for a 63-char sentence.
- Heap delta after 500 warm round-trips ≈ 41.7 KB (JVM estimate, *not* a
  device RAM claim).

## 5. Verification surface

- `BrahmicCodecTest` — exhaustive block, ASCII, digit, switching, seeded fuzz,
  truncation-prefix tests. **All green.** (Earlier fuzz failure was the test
  generator splitting surrogate pairs, not the codec.)
- `RetroSpeechCodecTest` / codec lab tests — round-trip identity + latency logs.
- `PhysicalMeasuredBenchmarkTest` — the table above.
- `BaselineMetricsTest` — cold start, steady-state percentile, heap estimate.

## 6. Ten-language honesty

The codec is **script-lossless** across all 10 languages' scripts, but that is
not "ten-language offline speech". STT/TTS model availability is governed by
`ModelManager`/`ModelLifecycleManager` (see `MODEL_MANAGEMENT.md`); the codec by
itself does not make speech work.