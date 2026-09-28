# MODEL MANAGEMENT — Catalog, Probe, Validation, Lifecycle

> Two cooperating components: `ModelManager` (catalog + honest presence probe)
> and `ModelLifecycleManager` (RAM residency + validation-on-acquire).

## 1. Catalog (`ModelManager`)

- **Wanted:** 3 STT models (`stt-en`, `stt-hi`, `stt-ta`) and 10 TTS models
  (`tts-<en hi ta bn te mr gu kn ml or>`).
- Size hints are **catalog** figures (en-STT 40 MB, hi 26 MB, ta 24 MB,
  TTS 64 MB each) — informational only.
- `resolve(probe)` answers `PRESENT` iff the probe finds **real bytes on the
  device right now**. Default honest result on a clean install:
  **0 usable of 13** (`assets/models/` ships empty).
- `missing(probe)`, `countByKind(probe)` feed the MODEL CENTER UI.

## 2. Validation-on-acquire (`ModelLifecycleManager`)

`acquireValidatedStt(entry, file)` / `acquireValidatedTts(entry, file)`:

1. `ModelValidator.validate(manifest, file)` — header magic, declared sizes,
   integrity check, file readability.
2. On failure → `ERROR` state, `lastValidationFailure` records the reason,
   the model is never admitted to RAM.
3. On success → `READY`.

`acquireSttLocked` / `acquireTtsLocked` are the single internal admission paths;
`shortHash()` keeps fingerprint strings compact in the UI/logs.

### Residency rule (single-active-model)

- Only **one** model type is RAM-resident at a time.
- Acquiring TTS evicts a resident ASR model first (TTS RAM wins); acquiring ASR
  evicts resident TTS.
- If allocation cannot be satisfied, the engine reverts to `FallbackDspTts`
  (zero-weight DSP synthesis) rather than running out of memory.

## 3. Test surface (all green)

- Missing file → `ERROR` + reason; corrupt model (bad magic/size) → `ERROR`;
  valid model → `READY`, then eviction returns residency to zero.
- `ModelManagerTest` — 3 STT + 10 TTS wanted, default honest = 0 usable;
  probe semantics PRESENT iff the file truly exists.

## 4. Boundaries (do not over-claim)

- Zero model weights ship in the repo today. Until a model is provisioned and
  the runtime (sherpa-onnx / Vosk / Piper) exists, **offline speech is not
  working** — validation only guards the lifecycle that will host it.
- Model bytes on this JVM are estimates (`sizeHintBytes`), never shipped-state.