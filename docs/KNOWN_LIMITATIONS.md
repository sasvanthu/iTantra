# KNOWN LIMITATIONS — Honest Constraints

> Cross-referenced from `PROTOTYPE_STATUS.md §3`. Nothing here is dressed up;
> these are the boundaries a demo must never cross.

## 1. Offline speech is still blocked

- `assets/models/` ships **empty** (0 bytes of weights). 3 STT + 10 TTS models
  are *catalogued wanted*, and `ModelLifecycleManager` can validate/admit them —
  but admitting requires a real file the probe can find. **0 of 13 usable
  today.**
- No sherpa-onnx, no Vosk runtime, no Piper, no Silero VAD in the dependency
  graph. `AndroidSTTEngine` is the OS recognizer (not offline-guaranteed);
  `EmbeddedOpenSourceTTS` is a hand-written formant placeholder, **not** a
  language voice.

## 2. Transport has never run between two real phones

- BLE GATT and Wi-Fi TCP paths are implemented and unit-tested in-process only.
  Zero physical runs. Pairing (out-of-band `SETU-…` code) is unproven on
  hardware.
- Pairing code **is** the key — not ECDH/X25519. Anyone who reads it can decrypt
  everything. Labeled in-UI for that reason.

## 3. VAD / streaming ASR sit at the audio boundary

- `StreamingAsrEngine`'s VAD boundary logic and `StablePrefixTracker` flag
  semantics (uncertain/critical/entity/…) are implemented and unit-tested, but
  no acoustic model drives them on-device. The text path that consumes the
  semantic (`SemanticDispatchRouter`) is fully wired; the microphone path is not.

## 4. Codec expansion on tiny ASCII

- A 3–9 byte ASCII message can *grow* after phoneme encoding + fixed frame
  pressure (en-SHORT measured −108%: 12 B → 116 B on the wire). Indic prose is
  where compression lives (58–71%). The 512 B/message target still holds.

## 5. JVM-measured figures ≠ device figures

- All MEASURED latency/memory numbers come from a Windows 11 JVM suite. They
  must be re-measured on target hardware before they may be called device
  numbers (see `BENCHMARKS.md §1`).

## 6. Deterministic trade-offs

- Multilingual keyword matching in SUTRA is **Romanized** (e.g. `thanni`,
  `saans`, `khatra`), not native-script. Native-script input degrades to
  fallback; parser keyword sets are deliberately small.
- `ModelLifecycleManager` allows exactly one model type resident (TTS evicts ASR,
  ASR evicts TTS). High-concurrency multi-model asks are out of scope by design.
- Store-and-forward is bounded at 4 MiB / 256 KiB per message; oversize arrivals
  are displaced or dropped (and counted).

## 7. Determinism (fixed)

- `TransportEngineIntegrationTest.packet loss...` and `MeshTransportIntegrationTest.hop edge
  loss...` previously relied on unseeded random loss. **Fixed:** both now use seed-pinned
  simulators (`seed = 1L` / `7L`), so the loss draw is deterministic and a green suite is
  trustworthy (verified 5/5 consecutive runs). Historical note kept in `PROTOTYPE_STATUS.md §4`.

## 8. Deliberately absent

- No ECDH/key-exchange (out-of-band code only).
- No ProGuard/size trimming in the release block (APKs larger than target).
- No REST endpoints, no cloud SDK — fully offline by construction, which also
  means no remote model provisioning without human file transfer.