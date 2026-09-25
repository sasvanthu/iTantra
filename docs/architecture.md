# iTANTRA Architecture

## 1. Layered view

```
┌────────────────────────────────────────────────────────────────────────────┐
│ UI LAYER — Jetpack Compose (Material 3, retro-terminal theme)               │
│  LINK · METRICS · CODEC LAB · CONFIG · HW TEST · MODEL CENTER · DEMO       │
└───────────────────────────────┬────────────────────────────────────────────┘
                                │ StateFlow / viewModel events
┌───────────────────────────────▼────────────────────────────────────────────┐
│ VIEW-MODEL — MainViewModel                                                 │
│  · one UIState flow the screens observe                                    │
│  · owns SpeechPipeline, transport engines, codecs, controllers             │
│  · OperationModeController / EmergencyController / LowPowerController       │
│  · ModelManager probe (real files) · packet-activity console               │
│  · mirrors transport counters + RTT into MetricsEngine                     │
└───────────────────────────────┬────────────────────────────────────────────┘
                                │ speak()/stop/ack/send
┌───────────────────────────────▼────────────────────────────────────────────┐
│ PIPELINE — SpeechPipeline                                                  │
│  send: STT → encode → packetize → transport.send → report                  │
│  recv: transport.incomingPayloads → decode → deliver text → speakReceived()│
│  measures STT(real)/encode/packetize/network/ACK-RTT/decode/TTS latency    │
└───────────────┬───────────────────────────────────┬────────────────────────┘
                │ encode/decode                      │ packets
┌───────────────▼──────────┐               ┌─────────▼────────────────────────┐
│ CODEC — RetroSpeechCodec │               │ TRANSPORT — BaseTransportEngine │
│  token dict (en/hi/ta)   │               │  framing + CRC32                │
│  phoneme dict (en/hi/ta) │               │  ACK/NACK + RetransmissionMgr   │
│  ESCAPE (lossless, 7)    │               │  reassembly + dedup + purge     │
│  BaselineCodec (LZUTF8)  │               │  priority queue + governor      │
│  version = 2             │               │  epoch-scoped handshake (proto 1│
└──────────────────────────┘               └───────┬──────────┬──────────────┘
                                                   │ extends/relays on
                   ┌───────────────────────────────┼──────────┼──────────────┐
                   │ WifiTransport  BleTransport   │Simulated │ MeshTransport│
                   │ (TCP sockets)  (GATT MTU-512) │(selfloop)│ (flood+relay)│
                   └───────────────┬───────────────┴──────────┴──────────────┘
                                   │ physical transport chosen per transportMode
┌──────────────────────────────────▼──────────────────────────────────────────┐
│ MEASUREMENT — MetricsEngine + AdaptiveLinkGovernor                         │
│  per-message snapshot · transport sync · RTT · loss-per-observed-traffic    │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 2. Send data flow (one message)

```
1  operator speaks / types text
2  STT (Vosk) transcribes ────────────────► latency recorded iff actually recorded
3  RetroSpeechCodec.encode()
   · token maps to byte ids (en/hi/ta dictionary)
   · phonemes fallback for out-of-dict words
   · ESCAPE for the 7 non-shipped languages (lossless, overhead guard)
   ───────────────────────────────────────► encoding latency recorded
4  Packetizer.buildPackets(payload, lang, id, priority, governor.maxPayload())
   START + DATA* + END (END carries dataCount), CRC per packet
   ───────────────────────────────────────► packetization latency recorded
5  transport.send(): enqueue with priority (CRITICAL before NORMAL/LOW),
   DATA + END tracked by RetransmissionManager
6  write loop frames each packet (magic|ver|epoch|len|payload|CRC) and writes
   to medium (socket write / GATT characteristic / self-loop / mesh flood emit)
   ───────────────────────────────────────► transport latency, TX bytes counted
7  peer ACKs each packet; ACK RTT feeds the adaptive governor and report
8  report surfaced with real byte counts, retransmissions, RTT, latencies
```

Power governor: before the write, `LowPowerController.canTransmit(importance)` is checked.
LOW profile defers below-NORMAL traffic; CRITICAL profile reserves the wire for CRITICAL only.

## 3. Receive data flow

```
1 frame bytes arrive → frameReader decodes (epoch must match session)
2 Packet.deserialize + CRC32 verify → corrupt frames counted as loss
3 per messageId buffer collects DATA with gap-NACK for holes
4 END arrives → tryAssemble:
   · expected count guarded (MAX_DATA_PACKETS) → hostile END dropped
   · missing tail => selective NACKs, buffer kept until purge
   · complete → dedup set (MAX_COMPLETED_MESSAGES), emit ReassembledPayload
5 pipeline decodes (latency recorded) → text delivered to UI
6 emergency flag at CRITICAL auto-raises the emergency path (alarm + latch)
7 speakReceived(): offline TTS with measured synthesis time (20 s bound)
```

## 4. Mesh flooding + store-and-forward

```
         ┌────────┐          ┌────────┐          ┌────────┐
         │  A     │  hop     │   B    │  hop     │   C    │
         │ origin │────────►│ relay  │────────►│  sink  │
         └────────┘  (edge) └────────┘  (edge)  └────────┘

* each node holds a MeshRouter (dedup, origin+hopId) and MeshReassembler
* a message is emitted as RETRO frames wrapped in HopPacket envelopes
* relays accept an envelope once (duplicate suppression), then:
    - deliver payload up to the pipeline
    - re-emit on edges with forwardTtl > 0 (flood)
* hop TTL default 8, hops field advances
* MAX_DATA_PACKETS guard prevents hostile END allocation in the reassembler
* stale half-messages purge after 30 s (droppedMessages counted)
* store-and-forward: if a relay is isolated (no live edges yet) the frame is
  held in a bounded buffer and flushed on the next edge connect
* MeshTransportEngine observes AdaptiveLinkGovernor per edge and chooses
  packet size from the measured mode (256/512/1024/2048 B)
* roundTripTimeMs on mesh = one-way flood wall time (documented as such)
```

## 5. Emergency + power sequence

```
link:  Peer sends CRITICAL (isEmergency) ──► reassembled ──► pipeline ──► UI
        └► incomingMessages collector: raiseEmergencyFromPeer(text)
             EmergencyController latches EMERGENCY, reason recorded
             UI shows alarm + last alert text; operator ACK clears the latch
send:  operator raises EMERGENCY ──► sendEmergency() ──► pipeline forceEmergency
        priority = CRITICAL → drains before NORMAL/LOW in the write loop
battery: <20% profile LOW (defer <NORMAL), <10% CRITICAL (only CRITICAL goes out)
```

## 6. Protocols & constants

| Constant | Value | Guard |
|---|---|---|
| RETRO protocol version | 1 | handshake rejects incompatible peers |
| Codec version | 2 | negotiated in CAPABILITY |
| Frame payload cap | 64 KiB (`1 shl 16`) | hostile frame dropped pre-allocation |
| Packetizer default / governor max | 1024 / 256·512·1024·2048 | driven by measured link mode |
| `MAX_DATA_PACKETS` | 16 384 | hostile END count → drop + count |
| Stale receive retention | 30 s | bounded memory, counted as loss |
| `MAX_COMPLETED_MESSAGES` | 512 | dedup set stays flat |
| Mesh hop TTL | 8 | flood bounded |
| BLE chunk payload | 500 B (≤ ATT MTU) | per characteristic notification |
| TTS utterance bound | 20 s | failed/missing TTS cannot block receive |
| ACK timeout / max retries | 2000 ms / 3 | RETRO retransmission budget |
| Transfer watchdog | 30 s | a dead link cannot suspend `send()` forever |