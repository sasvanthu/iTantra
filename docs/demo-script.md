# SIH Demo Script — 3 minutes, single phone, no model needed

Goal: show a **real** offline voice-relay prototype. Everything on screen is measured: the simulated
link is a genuine in-process loop (full handshake, framing, ACK/NACK, reassembly) — not a mock.

## 0. Prep (30 s, before judges watch)

- Build: `gradlew.bat assembleDebug` → install the debug APK.
- Open the app → tap **DEMO** tab → **SIM LINK**. The transport column shows SIMULATED + CONNECTED
  (real capability handshake completes on the self-loop, protocol v1 · codec v2).

## 1. Codec, the punchline (60 s)

- **CONFIG**: switch to RETRO (default).
- **CODEC LAB**: paste a real sentence (e.g. `EVACUATION NEEDED: 27 INJURED, ROUTE 4N1 BLOCKED`,
  or one in Tamil/Hindi if you want). Press RUN LOOPBACK.
- Point at the numbers: **ORIGINAL vs ENCODED bytes**, **compression %**, packet count, and
  the exact-match integrity verdict. The codec is lossless for en/hi/ta; the other 7 languages
  decode losslessly too (Escape mode) — say that so no one mistakes "no compression" for "no support".

## 2. Reliability over a lossy link (60 s)

- **CONFIG**: enable network simulation (random loss/corruption).
- **DEMO tab → SIM LINK → SEND SAMPLE**, several times.
- Point at the **LIVE PACKET LANE**: TX/RX with packet counts and bytes. When loss strikes you see
  retransmissions and RTT figures that are real — RETRO recovers with ACK/NACK + retry up to 3×.

## 3. Emergency + honesty (the closer, 45 s)

- **DEMO → EMERGENCY**: the write queue prioritises the CRITICAL frame (see LINK tab priority badge).
- **METRICS tab**: walk the LATENCY/SIZE/TRANSPORT sections — every row is either a real reading or
  `NOT MEASURED`. The app does not fabricate a zero.
- **MODEL CENTER tab**: show the 10-language table. en/hi/ta show **MODEL MISSING** (installable via
  the instructions), the other 7 show **STT PLANNED**. This visible honesty is the point: the SIH
  judges get a truthful status board, and installing a Vosk model flips languages to READY.

## 4. Optional with models installed (+2 min)

- Drop `stt-TAMIL.zip` in `Android/data/com.example.itantra/files/` → MODEL CENTER flips to
  `STT READY` → **LINK** tab → record speech in Tamil → it encodes, flows on the (simulated) link,
  and the receiving pipeline speaks it back. Fully offline.

## Scripting notes

- If two phones are available: **LINK** tab on both, one HOST one DEVICE over **WIFI**; speak on one,
  hear on the other — the exact same pipeline, over a real TCP socket.
- BLE pair demo needs two phones in range (MTU-512 path in HW TEST checklist).
- Never stage a number: if a row shows something you didn't measure, leave it and say so.