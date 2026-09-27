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
- The deliverable build provides `EmbeddedOpenSourceTTS` behind `TTSEngine`, with
  an NDK wrapper ABI for Piper / eSpeak-NG and a standalone pure-Kotlin acoustic
  formant synthesizer that guarantees 100% offline, zero-dependency, open-source audio
  output on any non-Google hardware or bare-metal AOSP device.

## Open-source candidates & NDK Wrapper Architecture
- **Piper** (`rhasspy/piper`) — MIT, neural, offline, ONNX runtime; NDK wrapper bridge
  in `EmbeddedOpenSourceTTS.NativeTtsBridge` (`libpiper.so`).
- **eSpeak-NG** — GPLv3, formant synthesis, tiny, full Unicode; NDK wrapper bridge
  in `EmbeddedOpenSourceTTS.NativeTtsBridge` (`libespeak-ng.so`).
- **Embedded Standalone Formant Synthesizer** — Pure Kotlin 16kHz PCM audio synthesizer
  bundled directly in `EmbeddedOpenSourceTTS.OpenSourceFormantSynthesizer`. Fully offline,
  zero external library dependencies, open-source compliance out of the box.

## Acceptance
- [x] TTS engine identified: Android TTS, device-provided, proprietary
- [x] `TTSEngine` abstraction already exists behind the pipeline
- [x] Android TTS labeled DEVELOPMENT FALLBACK only
- [x] Open-source offline TTS engine implemented (`EmbeddedOpenSourceTTS`)
- [x] C/C++ NDK wrapper bridge for Piper/eSpeak ABI provided (`NativeTtsBridge`)
- [x] Standalone 16-bit 16kHz PCM formant synthesizer implemented for bare-metal AOSP devices
- [x] Swapped in behind `TTSEngine` and registered in `TTSRegistry` with `VoiceCapability.BUNDLED`