# OPERATIONS — Build, Test, Benchmark, Pair, Accept

> Everything is runnable on a Windows host via PowerShell and `.\gradlew.bat`.

## 1. Build & test

```powershell
.\gradlew.bat assembleDebug            # green APK build
.\gradlew.bat :app:testDebugUnitTest --offline   # full JVM suite
.\gradlew.bat :app:lintDebug           # static analysis (if configured)
```

Per-class filtering works:

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.example.itantra.protocol.*"
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.example.itantra.lab.BaselineMetricsTest"
```

**Stale lock:** if a stale `app\build\test-results\...\output.bin` lock appears,
run `.\gradlew.bat --stop` first and retry. **Gradle 9.1 `java.lang.System`
warnings are harmless.**

## 2. Benchmark & measure

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.example.itantra.codec.PhysicalMeasuredBenchmarkTest"  # latency + wire table
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.example.itantra.lab.BaselineMetricsTest"              # cold start / heap / budget
```

Read results from the printed `system-out` blocks, or from
`app\build\test-results\testDebugUnitTest\TEST-*.xml` (parsable). All numbers
are JVM figures until re-measured on hardware.

## 3. Device pairing ceremony (hardware)

1. Two phones, real BLE-capable devices. `adb devices` must list both.
2. `CONFIG → DEVICE PAIRING`: Phone A generates a `SETU-XXXX-…` code; the
   **operator — not the network —** reads it to Phone B; Phone B adopts it.
3. Confirm a matching SHA-256 **fingerprint** on both screens.
4. Run the acceptance harness:

```powershell
.\run_setu_acceptance_test.ps1 -Transport BLUETOOTH   # then -Transport WIFI
```

5. Compare: the packet format is identical across media; only the medium
   changes. The harness emits `setu_acceptance_report.json`.

## 4. Model provisioning (unblocks offline speech)

1. Place real weights under `app/src/main/assets/models/` (e.g. Vosk small for
   `en`/`hi`/`ta`, ~26–40 MB each).
2. MODEL CENTER probes the path; entries become `PRESENT` only when real bytes
   exist.
3. `ModelLifecycleManager` validates the file (magic/size/integrity) on acquire
   and enforces single-resident RAM.
4. Only then attempt the spoken en/hi/ta test — and report only the languages
   actually heard working.

## 5. Ceremony / claim discipline

- Report **MEASURED** only numbers backed by a test run or binary inspection
  (`ResourceBudget.md` + `BENCHMARKS.md`).
- Never present the formant DSP as a real voice; never present pairing as key
  exchange; never present codec losslessness as "10-language speech".
- Keep `PROTOTYPE_STATUS.md` and `docs/KNOWN_LIMITATIONS.md` in sync whenever a
  boundary is crossed.

## 6. Release checklist (short)

- [ ] Full `:app:testDebugUnitTest` green (reserve 5 min; transient/stochastic
      test watch: see `KNOWN_LIMITATIONS §7`).
- [ ] `assembleDebug` green.
- [ ] `ResourceBudget.md` metrics refreshed from the measured run.
- [ ] Hardware checklist (HW TEST tab) run on two phones if available.