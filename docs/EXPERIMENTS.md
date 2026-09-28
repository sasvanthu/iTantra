# EXPERIMENTS — What Was Tried, What Broke, What We Learned

> Running record of experiments on this repo. Each entry: hypothesis → change →
> observed result → lesson. All are reproducible by the test name given.

## 1. Seeded fuzz vs Brahmic losslessness

- **Tried:** seeded random corpora through `BrahmicCodec` round-trips via a
  char-indexed pool.
- **Broke:** intermittent surrogate-pair corruption (test only, never the codec).
- **Cause:** the generator picked **single `char`s**, splitting valid surrogate
  pairs; the codec's contract is whole code points.
- **Fix:** generate whole-code-point `String` items. All fuzz identities pass now.
- **Lesson:** a failing fuzz harness is evidence about the harness first. The
  exhaustive per-block and per-ASCII round-trips in `BrahmicCodecTest` give the
  real guarantee; fuzz is a smoke layer over it.

## 2. Governor degradation: step-counter vs conservativeness floor

- **First design:** `applyEnvironmentPenalty` was a fixed step-count, so a clean
  HIGH link with bad RSSI could only collapse to LOW, never EMERGENCY.
- **Broke:** `weak rssi and deep queue make the link conservatively degrade` —
  EMERGENCY was unreachable from HIGH by construction.
- **Redesign:** degradation is a **floor**. RSSI `< -90` → EMERGENCY floor,
  `-89..-80` → LOW floor; queue `>= 64` → EMERGENCY, `>= 32` → LOW. A floor
  never upgrades a sensed mode. Green.
- **Lesson:** with hysteresis, don't compose penalties as additive steps; compose
  as a monotone "most conservative" floor so the committed mode is reachable.

## 3. Hysteresis commit semantics

- **Tried:** single-sample evidence flipping the committed mode.
- **Result:** churn between modes under noisy RSSI/queue sampling.
- **Fix:** commit only after `DEFAULT_HYSTERESIS_SAMPLES = 3` consecutive
  leaning samples. `shouldUsePrediction()` reads committed `displayedMode` only.
  Green; governor reset returns to the nominal ceiling and resets the switch log.

## 4. Aggregation byte budget displacement

- **Tried:** `StoreAndForwardQueue` admitting arrivals that collectively exceed
  `maxQueueBytes`.
- **Result (before fix):** byte-limit breach; weakest messages NOT displaced.
- **Fix:** per-message size check + byte-budget loop (`makeRoomForLocked`) that
  displaces weakest lowest-priority messages first; a single oversized arrival
  that nothing can make room for is dropped and counted. Green.

## 5. BaselineMetrics constraint tuning

- **Tried (wrong):** assert mixed-corpus wire `< 50%` of UTF-8.
- **Result:** honest measurement said 20.4% for the en+hi+ta corpus (ASCII-heavy
  English expands under phoneme pressure; the ">50%" claim only holds for Indic
  prose, see CODEC_SPEC §3).
- **Applied:** assert the true invariants — wire ≤ UTF-8 on the corpus, every
  message inside the 512 B budget, encode means non-negative. Green.

## 6. Layer-2 context payload opt-in

- **Hypothesis:** extra SUTRA context layer adds meaning without threatening the
  base message.
- **Result:** default is 2 layers; layer 2 only exists when
  `createLayers(contextPayload = ...)` is used. Reassembler treats layer 2 as
  never-completing and never-downgrading; out-of-order layer 2 still attaches
  context. Green — 3 dedicated tests.

## 7. Serialization overhead audit

- Measured binary envelope ~111 B overhead vs JSON ~432 B; JSON kept as a
  decode fallback so a binary-only reader is never required. Findings logged in
  `WIRE_PROTOCOL.md §3`.

## 8. Known infrastructure quirks (not code bugs)

- `TransportEngineIntegrationTest.packet loss ...` is stochastic by design
  (random 35% loss; whether a drop occurs is a random draw). See
  `PROTOTYPE_STATUS.md §4`.
- Gradle 9.1 emits `java.lang.System` restricted-method warnings on this JDK —
  harmless, not from this code.