# TTS Audit — Phase 5 spec #23

## Question
Does the final iTantra build use an open-source, offline text-to-speech engine?

## Current state (audited 2026-09-24)
- All text-to-speech goes through the `TTSEngine` interface
  (`app/src/main/java/com/example/itantra/speech/tts/TTSEngine.kt`).
- The only implementation is `AndroidTTSEngine`, which wraps
  `android.speech.tts.TextToSpeech`.
- Integration point: `MainViewModel` (`ui/main/MainViewModel.kt`) constructs
  `AndroidTTSEngine()` and hands it to `SpeechPipeline`.
- Vosk (offline STT) is used as the speech-input engine and is unrelated to TTS.

## Verdict per spec #23
| Property | Android TTS (`AndroidTTSEngine`) | Required |
|----------|---------------------------------|----------|
| Open source | **NO** — device/Google/vendor-proprietary engine and voice data | YES |
| Offline | Partially — OK once voices are downloaded for a locale | YES |
| Works when the app provides its own engine | NO | yes for the final deliverable |
| Required by spec | only as DEVELOPMENT FALLBACK | — |

Android TTS is convenient for staging (it needs zero bundling), but it violates
the open-source-only constraint for the delivered device, and voices are
proprietary (non-redistributable).

## Decision
- `AndroidTTSEngine` = **DEVELOPMENT FALLBACK.** It stays wired for on-device
  staging of the receive path, but is clearly labeled as such in code.
- The hardware-test lab (`HardwareTestViewModel`) uses a `NOOP_TTS` so Phase 5
  validates STT → codec → BLE → decode *without* TTS (spec #11/#12: no TTS yet).
- The final device needs an open-source offline engine behind `TTSEngine`.

## Open-source candidates (evaluate when the deliverable build starts)
- **Piper** (`rhasspy/piper`) — MIT, neural, offline, ONNX runtime; per-voice
  models (hi, ta available from the community), small-ish footprint.
- **eSpeak-NG** — GPLv3, formant synthesis, tiny, full Unicode; robotic voice
  but provably-free and fully offline; strong TA/HI coverage.
- **RHVoice** — GPL/LGPL, high-quality formant/concatenative, hi + ta voices.

No replacement is implemented yet — spec explicitly says audit first, replace later.

## Acceptance
- [x] TTS engine identified: Android TTS, device-provided, proprietary
- [x] `TTSEngine` abstraction already exists behind the pipeline
- [x] Android TTS labeled DEVELOPMENT FALLBACK only
- [x] No open-source TTS implemented yet (by design)
- [ ] (future) An open-source engine swapped in behind `TTSEngine`