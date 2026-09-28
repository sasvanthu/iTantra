# BENCHMARKS — Honest Measured Numbers

> Reproduce everything with the commands below. **All numbers are JVM-measured
> on Windows 11 x64 (SASVANTHU_S16, Gradle 9.1.0).** They describe this
> machine's codec and wire path; they are NOT device figures and must be
> re-measured on target hardware before being advertised there.

## 1. Reproduce

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.example.itantra.codec.PhysicalMeasuredBenchmarkTest"   # bandwidth + latency table
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.example.itantra.lab.BaselineMetricsTest"               # cold start / steady state / heap / budget
.\gradlew.bat :app:testDebugUnitTest --offline                                                                    # full suite
```

## 2. Codec latency + wire size (MEASURED)

See `CODEC_SPEC.md §3` for the full 9-cell table. Headlines:

- Indefinite Indic-prose gain: ta 70.5%, hi 58.8% (medium census) vs UTF-8.
- Encode/decode 0.02–0.80 ms across all cells.
- en-SHORT *expands* (−108%) — tiny ASCII pays phoneme + frame pressure.

## 3. Cold start vs steady state (MEASURED)

| Metric | Value | Meaning |
| :--- | :--- | :--- |
| Cold start (first ever round-trip) | **1 ms** | class-load + JIT inclusive |
| Steady-state mean (30 iters) | **0.033 ms** | warm encode+decode |
| Steady-state p95 | **0 ms** (sub-ms) | all iter < 1 ms wall |

## 4. Memory (labeled, MEASURED-on-JVM)

- Heap delta over 500 warm round-trips: **≈ 41.7 KB** — labeled *"JVM estimate,
  not on-device RAM"* in the test report. No device-RAM claim is made.
- Embedded budgets: store-and-forward `maxQueueBytes = 4 MiB`,
  `maxMessageBytes = 256 KiB`; per-message wire target 512 B.

## 5. Budget vs measured tracking (Phase 1 baseline)

| Metric | MEASURED now | TARGET |
| :--- | :--- | :--- |
| Per-message wire (en/hi/ta mixed) | 56 / 129 / 146 B mean | `< 512 B` |
| Codec encode latency (mixed) | ≤ 1.56 ms mean | `< 15 ms` |
| Indefinite Indic-prose compression | 58–71% vs UTF-8 | `> 50%` |
| Cold-start round-trip | ≈ 1 ms | on-device re-measure |
| Heap delta per 500 round-trips | ≈ 41.7 KB | `< 512 MB device RAM` |

`BaselineLimits` in `app/.../lab/BaselineMetrics.kt` exposes the targets; they
are explicitly NOT claims about this repo's JVM.

## 6. Honesty rules (enforced by tests)

- Failed iterations stay counted (`successRate` surfaced, never imputed).
- Cold start may never be reported below steady-state mean.
- Wire may never exceed UTF-8 on the mixed corpus; every message stays inside
  the 512 B budget.
- Encode means may be `0.0` ms (sub-millisecond truth) — tests forbid only
  negatives.