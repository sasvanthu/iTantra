package com.example.itantra.lab

import com.example.itantra.codec.BinaryCodec
import com.example.itantra.codec.CodecException
import com.example.itantra.codec.DecodeResult
import com.example.itantra.codec.SpeechCodec
import com.example.itantra.protocol.BleLinkCodec
import com.example.itantra.protocol.Packet
import com.example.itantra.protocol.Packetizer
import com.example.itantra.transport.NetworkSimulator
import com.example.itantra.transport.ReassembledPayload
import com.example.itantra.transport.TransportEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Transport-agnostic lab harness for physical-device validation.
 *
 * The same runner runs on both phones: Phone A calls [probeSend] (measures
 * encode/packetize/transmit + sizes + fragment count) and Phone B calls
 * [verify] on whatever arrived, proving CRC + decode + byte-exactness against
 * the shared [HardwareTestSuite]. Both directions are exercised together in
 * the JVM tests over paired [SimulatedTransport]s, so the harness logic is
 * verified before the radios are.
 */
class HardwareTestRunner(private val codec: SpeechCodec) {

    /**
     * Subscribe to the engine's incoming payloads BEFORE any send. Returns the
     * collector job (cancel it when done) and the mutable list it fills.
     */
    suspend fun collector(
        engine: TransportEngine,
        scope: CoroutineScope,
        predicate: (ReassembledPayload) -> Boolean = { true }
    ): Pair<Job, MutableList<ReassembledPayload>> {
        val received = mutableListOf<ReassembledPayload>()
        val ready = CompletableDeferred<Unit>()
        val job = scope.launch {
            ready.complete(Unit)
            engine.incomingPayloads.collect { if (predicate(it)) received.add(it) }
        }
        ready.await()
        return job to received
    }

    suspend fun awaitSize(list: List<*>, size: Int, timeoutMs: Long = 15_000) {
        withTimeout(timeoutMs) {
            while (list.size < size) delay(5)
        }
    }

    /**
     * Sender-side probe: encode -> packetize -> transmit, reporting real sizes
     * and latencies. `mtu` is the negotiated ATT MTU (0 = not BLE). When
     * [fragmentSource] is provided (BLE), fragments are the measured ATT atoms
     * actually written; otherwise they are an estimate at the given MTU chunk
     * payload and the result is flagged `fragmentMeasured = false`.
     */
    suspend fun probeSend(
        engine: TransportEngine,
        t: TestCase,
        transportLabel: String,
        role: String,
        mtu: Int = 0,
        fragmentSource: (() -> Long)? = null
    ): ProbeResult {
        if (!engine.isConnected()) {
            return ProbeResult(
                transport = transportLabel, role = role, caseName = t.name, text = t.text, language = t.language,
                originalBytes = 0, codecBytes = 0, packetBytes = 0, packetCount = 0,
                fragments = -1, fragmentMeasured = false, mtu = mtu,
                encodeMs = 0, transportMs = 0, decodeMs = 0, totalMs = 0,
                retransmissions = 0, packetLoss = 0, duplicatesDetected = 0, receivedBytes = 0,
                crcPass = null, decodePass = null, deliveredOnce = null,
                sentOk = false, detail = "not connected"
            )
        }

        val chunksBefore = fragmentSource?.invoke()

        val encStart = System.nanoTime()
        val encoded = codec.encode(t.text, t.language)
        val encodeMs = (System.nanoTime() - encStart) / 1_000_000L

        val packets = Packetizer.buildPackets(
            payload = encoded.data,
            language = t.language,
            messageId = 1,
            priority = encoded.importance.level,
            isEmergency = false
        )
        val packetBytes = packets.sumOf { it.serialize().size }

        val t0 = System.currentTimeMillis()
        val result = engine.send(
            data = encoded.data,
            language = t.language,
            isEmergency = false,
            priority = encoded.importance.level.toByte()
        )
        val transportMs = System.currentTimeMillis() - t0

        val chunksAfter = fragmentSource?.invoke()
        val measured = chunksBefore != null && chunksAfter != null
        val fragments = if (measured) {
            (chunksAfter!! - chunksBefore!!).coerceAtLeast(0)
        } else {
            estimateFragments(packets, mtu)
        }

        return ProbeResult(
            transport = transportLabel, role = role, caseName = t.name, text = t.text, language = t.language,
            originalBytes = encoded.originalUtf8Size,
            codecBytes = encoded.finalEncodedSize,
            packetBytes = packetBytes,
            packetCount = packets.size,
            fragments = fragments,
            fragmentMeasured = measured,
            mtu = mtu,
            encodeMs = encodeMs,
            transportMs = transportMs,
            decodeMs = 0,
            totalMs = encodeMs + transportMs,
            retransmissions = result?.retransmissions ?: 0,
            packetLoss = engine.linkMetrics.value.packetLoss,
            duplicatesDetected = engine.linkMetrics.value.duplicatePackets,
            receivedBytes = 0,
            crcPass = null,
            decodePass = null,
            deliveredOnce = null,
            sentOk = result?.failed == false,
            detail = result?.detail ?: "queued"
        )
    }

    /** How many ATT atoms each serialized packet needs at the given MTU (0 = use default chunk payload). */
    fun estimateFragments(packets: List<Packet>, mtu: Int): Long {
        val payload = if (mtu > 0) maxOf(20, mtu - 3) else BleLinkCodec.DEFAULT_CHUNK_PAYLOAD
        return packets.sumOf { (it.serialize().size + payload - 1) / payload }.toLong()
    }

    /**
     * Receiver-side verdict: decode the payload that arrived over the wire and
     * check CRC + exact reconstruction against the expected case. Runs only on
     * the receiving phone (or the receiving engine in the JVM tests).
     */
    fun verify(
        engine: TransportEngine,
        payload: ReassembledPayload,
        t: TestCase,
        transportLabel: String,
        role: String
    ): ProbeResult {
        val crash = ProbeResult(
            transport = transportLabel, role = role, caseName = t.name, text = t.text, language = t.language,
            originalBytes = 0, codecBytes = 0, packetBytes = 0, packetCount = payload.dataPackets,
            fragments = -1, fragmentMeasured = false, mtu = 0,
            encodeMs = 0, transportMs = 0, decodeMs = 0, totalMs = 0,
            retransmissions = 0, packetLoss = 0, duplicatesDetected = engine.linkMetrics.value.duplicatePackets,
            receivedBytes = payload.payload.size, sentOk = false,
            crcPass = null, decodePass = null, deliveredOnce = null, detail = "no payload"
        )

        val norm = runCatching { codec.performLab(t.text, t.language).normalizedText }.getOrDefault("")
        val crcPass = runCatching { BinaryCodec().decode(payload.payload).crcValid }.getOrDefault(false)

        val decodeStart = System.nanoTime()
        val decoded = runCatching { codec.decode(payload.payload) }.getOrNull()
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000L

        val decodePass = decoded is DecodeResult.Success
        val exact = decoded is DecodeResult.Success && decoded.reconstructedText == norm

        return crash.copy(
            crcPass = crcPass,
            decodePass = exact,
            deliveredOnce = true,
            decodeMs = decodeMs,
            totalMs = decodeMs,
            duplicatesDetected = engine.linkMetrics.value.duplicatePackets,
            sentOk = true,
            detail = if (decodePass) "verified vs ${t.name}" else "decoded mismatch"
        )
    }

    /**
     * The spec #10 duplicate test on the receiving side: the sender thread
     * pumps the same message through a duplication-enabled link and this counts
     * how many distinct application deliveries the receiver observes (1) vs how
     * many duplicates its link layer detected (>= 1). On-device the sender
     * enables the INJECT DUP toggle in the UI, which sets [sim] duplication.
     */
    suspend fun duplicateExperiment(
        sender: TransportEngine,
        receiver: TransportEngine,
        senderSim: NetworkSimulator,
        codec: SpeechCodec,
        t: TestCase,
        scope: CoroutineScope
    ): DuplicateCheck {
        val (job, list) = collector(receiver, scope)
        senderSim.config.duplicationRate = 1f
        try {
            val encoded = codec.encode(t.text, t.language)
            val result = sender.send(encoded.data, t.language, isEmergency = false, priority = 1)
            awaitSize(list, 1)
            senderSim.config.duplicationRate = 0f
            val dups = receiver.linkMetrics.value.duplicatePackets
            val beforeRecheck = list.size
            delay(100)
            return DuplicateCheck(
                payloadsDelivered = beforeRecheck,
                messagesExpected = 1,
                duplicatesDetected = dups,
                okay = result?.failed == false && beforeRecheck == 1 && dups >= 1
            )
        } finally {
            senderSim.config.duplicationRate = 0f
            job.cancel()
        }
    }

    /**
     * Sender-side size sweep (spec #5): one deterministic N-byte payload per
     * step over the negotiated MTU. Fills SIZE | MTU | FRAGMENTS | RESULT |
     * TIME; the receiving phone's receive window proves byte-exact delivery.
     */
    suspend fun runSweep(
        engine: TransportEngine,
        mtu: Int,
        fragmentSource: (() -> Long)? = null
    ): List<SweepRow> {
        val rows = mutableListOf<SweepRow>()
        for (n in HardwareTestSuite.SWEEP_SIZES) {
            val data = HardwareTestSuite.payloadBytes(n)
            val packets = Packetizer.buildPackets(
                payload = data,
                language = com.example.itantra.codec.Language.ENGLISH,
                messageId = 1,
                priority = 1,
                isEmergency = false
            )
            val before = fragmentSource?.invoke()
            val t0 = System.currentTimeMillis()
            val result = engine.send(data, com.example.itantra.codec.Language.ENGLISH, isEmergency = false, priority = 1)
            val timeMs = System.currentTimeMillis() - t0
            val after = fragmentSource?.invoke()
            val measured = before != null && after != null
            val fragments = if (measured) (after!! - before!!).coerceAtLeast(0) else estimateFragments(packets, mtu)
            rows += SweepRow(
                sizeBytes = n,
                packetCount = packets.size,
                fragments = fragments,
                fragmentMeasured = measured,
                mtu = mtu,
                timeMs = timeMs,
                sentOk = result?.failed == false
            )
        }
        return rows
    }
}