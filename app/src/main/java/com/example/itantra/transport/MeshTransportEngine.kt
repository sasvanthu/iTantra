package com.example.itantra.transport

import com.example.itantra.codec.BinaryCodec
import com.example.itantra.codec.Language
import com.example.itantra.protocol.FrameReader
import com.example.itantra.protocol.FrameReadResult
import com.example.itantra.protocol.FrameWriter
import com.example.itantra.protocol.HopPacket
import com.example.itantra.protocol.MeshReassembler
import com.example.itantra.protocol.MeshRouter
import com.example.itantra.protocol.MeshRouting
import com.example.itantra.protocol.Packet
import com.example.itantra.protocol.Packetizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Store-and-forward mesh overlay.
 *
 * A [MeshTransportEngine] is a `TransportEngine` whose "link" is actually a
 * flooded, best-effort broadcast across one or more child edge engines
 * (e.g. [WifiTransportEngine] / [BleTransportEngine]). Every RETRO frame is
 * wrapped in a [HopPacket], mirrored out of every connected edge, and relayed
 * by every node until the TTL budget runs out:
 *
 * ```
 *  A.edges=[A<->B]  B.edges=[A<->B, B<->C]  C.edges=[B<->C]
 *  A.send(frame) -> A floods A->B
 *                 -> B dedups, delivers, relays B->C
 *                 -> C dedups, delivers (TTL ends or no peers)
 * ```
 *
 * Semantics are chosen to be *honest* about what a device-to-device mesh can
 * guarantee without a fixed peer identity:
 *
 *  - **Broadcast, not addressed**: every new envelope is delivered locally and
 *    relayed; there is no per-peer ACK/NACK end-to-end (the edges DO provide
 *    per-hop reliability, so a single link that drops a hop envelope will
 *    retransmit it on that hop only).
 *  - **Duplicate suppression**: an (origin, hopId) seen-set ensures each
 *    transmission instance is delivered/relayed exactly once per node, even if
 *    the same envelope arrives over several edges.
 *  - **TTL bound**: flooding cannot loop forever; the origin sets the relay
 *    budget ([maxTtl]) and every hop decrements it.
 *  - **Best-effort reassembly**: gaps and lost tails are detected and counted
 *    (packet loss) but never requested — a flooded broadcast has no single
 *    sender to NACK.
 *
 * Mesh nodes are **RELAYs**; they neither listen on a port nor dial one host.
 * Edge lifecycle belongs to the caller: attach [WifiTransportEngine] /
 * [BleTransportEngine] instances with [addEdge]; this engine only mirrors
 * frames across whatever edges are connected then.
 */
class MeshTransportEngine(
    private val maxTtl: Int = MeshRouter.DEFAULT_MAX_TTL
) : TransportEngine {

    override val transportType: TransportType = TransportType.MESH

    private val localDeviceId = "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).uppercase()
    private val router = MeshRouter(localDeviceId, maxTtl)
    private val reassembler = MeshReassembler()

    private val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val edges = mutableListOf<TransportEngine>()
    private val edgeJobs = HashMap<TransportEngine, kotlinx.coroutines.Job>()
    private val edgeReady = HashMap<TransportEngine, CompletableDeferred<Unit>>()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _session = MutableStateFlow<ConnectionSession?>(null)
    override val session: StateFlow<ConnectionSession?> = _session.asStateFlow()

    private val _linkMetrics = MutableStateFlow(LinkMetrics())
    override val linkMetrics: StateFlow<LinkMetrics> = _linkMetrics.asStateFlow()

    private val _messageInfo = MutableStateFlow(MessageInfo())
    override val messageInfo: StateFlow<MessageInfo> = _messageInfo.asStateFlow()

    private val _linkEvents = MutableSharedFlow<LinkMessageEvent>(extraBufferCapacity = 128)
    override val linkEvents: SharedFlow<LinkMessageEvent> = _linkEvents.asSharedFlow()

    private val _incomingPayloads = MutableSharedFlow<ReassembledPayload>(extraBufferCapacity = 64)
    override val incomingPayloads: SharedFlow<ReassembledPayload> = _incomingPayloads.asSharedFlow()

    private val messageIdCounter = AtomicLong(1)
    private var sessionEpoch = 0
    private var lastDupSeen = 0
    private var lastGapsSeen = 0
    private var lastDroppedSeen = 0

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val FRAME_EPOCH = 0 // mesh broadcast has no peer session to tie frames to
        private const val TRANSFER_TIMEOUT_MS = 15_000L
    }

    override fun observeIncoming() = _incomingPayloads.map { it.payload }

    // ------------------------------------------------------------------
    // Edge management
    // ------------------------------------------------------------------

    /**
     * Attach a child edge. The edge may already be connected or become
     * connected later; its status and incoming payloads are observed until
     * [removeEdge]/[disconnect]. Duplicate edge instances are ignored.
     */
    fun addEdge(edge: TransportEngine) {
        synchronized(edges) {
            if (edges.contains(edge) || edgeJobs.containsKey(edge)) return
            edges.add(edge)
        }
        val ready = CompletableDeferred<Unit>()
        edgeReady[edge] = ready
        edgeJobs[edge] = meshScope.launch {
            supervisorScope {
                launch {
                    try {
                        edge.incomingPayloads
                            .onStart { ready.complete(Unit) }
                            .collect { payload ->
                                try {
                                    handleHop(payload.payload, edge)
                                } catch (e: Exception) {
                                    // A single bad envelope must not kill the watcher.
                                }
                            }
                    } catch (e: Exception) {
                        // Edge flow ended (disconnected): derive state below.
                    }
                    deriveConnectivity()
                }
                launch {
                    edge.connectionStatus.collect { deriveConnectivity() }
                }
            }
        }
        deriveConnectivity()
    }

    /**
     * Suspends until the payload collector for [edge] is subscribed and would
     * have received any envelope sent to it (SharedFlow has no replay, so an
     * emission before subscription would be silently lost).
     */
    suspend fun awaitEdgeReady(edge: TransportEngine) {
        (edgeReady[edge] ?: error("edge not attached")).await()
    }

    /** Detach a child edge and stop observing it. */
    fun removeEdge(edge: TransportEngine) {
        edgeReady.remove(edge)
        edgeJobs.remove(edge)?.cancel()
        synchronized(edges) { edges.remove(edge) }
        deriveConnectivity()
    }

    fun edgeCount(): Int = edges.size

    // ------------------------------------------------------------------
    // TransportEngine interface
    // ------------------------------------------------------------------

    /** The mesh has no listener role; returns current edge connectivity. */
    override suspend fun startHost(port: Int): Boolean = isConnected()

    /** The mesh has no dial-a-host role; returns current edge connectivity. */
    override suspend fun connect(host: String, port: Int): Boolean = isConnected()

    override suspend fun disconnect() {
        edgeJobs.values.forEach { it.cancel() }
        edgeJobs.clear()
        edgeReady.clear()
        if (_connectionStatus.value != ConnectionStatus.DISCONNECTED) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            emitEvent(LinkMessageEvent.StatusChange(ConnectionStatus.DISCONNECTED))
        }
        resetLink("mesh closed")
        // Edges belong to the host (the ViewModel), so a torn-down overlay
        // must keep observing them: a later re-host/connect re-arms the mesh.
        edges.toList().forEach { addEdge(it) }
    }

    override fun isConnected(): Boolean =
        edges.any { it.isConnected() }

    override fun getLocalAddress(): String =
        edges.joinToString(",") { it.getLocalAddress() }.ifEmpty { "no edges" }

    override fun getDeviceId(): String = localDeviceId

    override fun getRoleLabel(): String = "RELAY"

    override fun getLinkName(): String = "MESH // ${isConnectedCount()} edges"

    private fun isConnectedCount(): Int = edges.count { it.isConnected() }

    // ------------------------------------------------------------------
    // Send path (flood)
    // ------------------------------------------------------------------

    override suspend fun send(
        data: ByteArray,
        language: Language,
        isEmergency: Boolean,
        priority: Byte
    ): SendResult? {
        if (data.isEmpty()) return null
        val liveCount = isConnectedCount()
        if (liveCount == 0) return null

        val messageId = messageIdCounter.getAndIncrement()
        val packets = Packetizer.buildPackets(
            payload = data,
            language = language,
            messageId = messageId,
            priority = priority.toInt(),
            isEmergency = isEmergency,
            maxPayload = Packetizer.DEFAULT_MAX_PAYLOAD
        )

        _messageInfo.value = MessageInfo(
            state = MessageState.TRANSMITTING,
            detail = "flooding ${packets.size} packets over $liveCount edge(s)",
            originalBytes = data.size,
            encodedBytes = data.size,
            packetCount = packets.size,
            isEmergency = isEmergency
        )
        val startMs = System.currentTimeMillis()
        var transmittedBytes = 0
        var failed = false

        val completed = withTimeoutOrNull(TRANSFER_TIMEOUT_MS) {
            for (p in packets) {
                val frame = FrameWriter.write(FRAME_EPOCH, p.serialize())
                val stats = flood(router.craft(frame).serialize())
                transmittedBytes += stats.bytes
                if (stats.okCount == 0) failed = true
            }
            true
        } != null
        if (!completed) failed = true

        val latency = System.currentTimeMillis() - startMs
        val result = SendResult(
            messageId = messageId,
            transmittedBytes = transmittedBytes,
            packetCount = packets.size,
            retransmissions = 0,
            roundTripTimeMs = latency,
            failed = failed,
            detail = when {
                failed && !completed -> "mesh flood timed out"
                failed -> "no reachable edge"
                else -> "broadcast"
            }
        )

        _messageInfo.update {
            it.copy(
                state = if (failed) MessageState.FAILED else MessageState.COMPLETE,
                detail = result.detail,
                transmittedBytes = transmittedBytes,
                roundTripTimeMs = latency,
                totalLatencyMs = latency,
                failed = failed
            )
        }
        emitEvent(LinkMessageEvent.Transmitted(
            messageId = messageId,
            transmittedBytes = transmittedBytes,
            packetCount = packets.size,
            retransmissions = 0,
            roundTripTimeMs = latency,
            failed = failed
        ))
        return result
    }

    private data class FloodStats(val bytes: Int, val okCount: Int, val failCount: Int)

    /** Mirror [envelope] out of every connected edge except [except]. */
    private suspend fun flood(envelope: ByteArray, except: TransportEngine? = null): FloodStats {
        val targets = edges.filter { it !== except && it.isConnected() }
        if (targets.isEmpty()) return FloodStats(0, 0, 0)
        val results = coroutineScope {
            targets.map { edge ->
                async {
                    try {
                        edge.send(envelope, Language.ENGLISH, isEmergency = false, priority = 2)
                    } catch (e: Exception) {
                        null
                    }
                }
            }.map { it.await() }
        }
        val ok = results.count { it != null && !it.failed }
        val bytes = envelope.size * ok
        link { packetsSent += targets.size; transmittedBytes += bytes }
        return FloodStats(bytes, ok, targets.size - ok)
    }

    // ------------------------------------------------------------------
    // Receive path (dedup -> deliver -> relay)
    // ------------------------------------------------------------------

    private suspend fun handleHop(envelope: ByteArray, fromEdge: TransportEngine) {
        val hop = HopPacket.deserialize(envelope) ?: run {
            link { corruptedFrames++ }
            return
        }
        val routing = router.receive(hop)
        if (routing is MeshRouting.Drop) {
            link { duplicatePackets++ }
            return
        }

        val packet = parseSingleFrame(hop.payload) ?: run {
            link { corruptedFrames++; packetLoss++ }
            return
        }
        link { packetsReceived++; receivedBytes += envelope.size }

        val msg = reassembler.onPacket(packet)
        syncReassemblerMetrics()
        if (msg != null) {
            val reassembled = ReassembledPayload(
                messageId = msg.messageId,
                payload = msg.payload,
                language = msg.language,
                isEmergency = msg.isEmergency,
                receivedAt = msg.receivedAt,
                dataPackets = msg.dataPackets,
                acksSent = 0,
                retransmissionRequestsSent = 0,
                sessionId = _session.value?.sessionId ?: ""
            )
            _incomingPayloads.emit(reassembled)
            emitEvent(LinkMessageEvent.Received(
                messageId = msg.messageId,
                payloadBytes = msg.payload.size,
                dataPackets = msg.dataPackets,
                isEmergency = msg.isEmergency,
                receivedAt = msg.receivedAt
            ))
            _messageInfo.update {
                it.copy(
                    state = MessageState.COMPLETE,
                    detail = "received ${msg.messageId} (${msg.payload.size} bytes)",
                    originalBytes = msg.payload.size,
                    encodedBytes = msg.payload.size,
                    packetCount = msg.dataPackets,
                    isEmergency = msg.isEmergency
                )
            }
        }

        if (routing is MeshRouting.Accept && routing.forwardTtl > 0) {
            val relayed = hop.copy(ttl = routing.forwardTtl, hops = hop.hops + 1)
            flood(relayed.serialize(), except = fromEdge)
        }
    }

    private fun parseSingleFrame(hopPayload: ByteArray): Packet? {
        val results = FrameReader().feed(hopPayload, maxFrames = 1)
        val frame = (results.singleOrNull() as? FrameReadResult.Complete)?.frame ?: return null
        return Packet.deserialize(frame.bytes)
    }

    private fun syncReassemblerMetrics() {
        val dup = reassembler.duplicatesSeen
        val gaps = reassembler.gapsDetected
        val dropped = reassembler.droppedMessages
        if (dup > lastDupSeen) {
            link { duplicatePackets += dup - lastDupSeen }
            lastDupSeen = dup
        }
        if (gaps > lastGapsSeen || dropped > lastDroppedSeen) {
            link { packetLoss += (gaps - lastGapsSeen) + (dropped - lastDroppedSeen) }
            lastGapsSeen = gaps
            lastDroppedSeen = dropped
        }
    }

    // ------------------------------------------------------------------
    // Connectivity / session bookkeeping
    // ------------------------------------------------------------------

    private fun deriveConnectivity() {
        val live = edges.filter { it.isConnected() }
        val status = when {
            edges.isEmpty() -> ConnectionStatus.DISCONNECTED
            live.isNotEmpty() -> ConnectionStatus.CONNECTED
            edges.any { it.connectionStatus.value == ConnectionStatus.WAITING } -> ConnectionStatus.WAITING
            edges.any { it.connectionStatus.value == ConnectionStatus.CONNECTING } -> ConnectionStatus.CONNECTING
            else -> ConnectionStatus.DISCONNECTED
        }
        val prev = _connectionStatus.value
        if (status != prev) {
            _connectionStatus.value = status
            emitEvent(LinkMessageEvent.StatusChange(status))
        }

        if (status == ConnectionStatus.CONNECTED) {
            val neighbors = live.mapNotNull { it.session.value?.remoteDeviceId }.distinct().sorted()
            val neighborId = neighbors.joinToString(",")
            val current = _session.value
            if (current == null) {
                sessionEpoch++
                val now = System.currentTimeMillis()
                val session = ConnectionSession(
                    sessionId = "MSH-$sessionEpoch-${UUID.randomUUID().toString().substring(0, 4)}",
                    localDeviceId = localDeviceId,
                    remoteDeviceId = neighborId,
                    protocolVersion = PROTOCOL_VERSION,
                    codecVersion = BinaryCodec.VERSION,
                    supportedLanguages = Language.values().filterNot { it == Language.UNKNOWN },
                    connectionStartTime = now,
                    lastReceivedTime = now,
                    epoch = sessionEpoch,
                    isHost = false
                )
                _session.value = session
                emitEvent(LinkMessageEvent.Connected(session))
            } else if (current.remoteDeviceId != neighborId) {
                _session.value = current.copy(remoteDeviceId = neighborId)
            }
        } else if (status == ConnectionStatus.DISCONNECTED && prev == ConnectionStatus.CONNECTED) {
            resetLink("all mesh edges lost")
        }
    }

    private fun resetLink(reason: String) {
        if (_session.value != null) {
            emitEvent(LinkMessageEvent.Disconnected(reason))
        }
        _session.value = null
        _linkMetrics.value = LinkMetrics()
        _messageInfo.value = MessageInfo()
        lastDupSeen = 0
        lastGapsSeen = 0
        lastDroppedSeen = 0
    }

    private fun emitEvent(event: LinkMessageEvent) {
        meshScope.launch { _linkEvents.emit(event) }
    }

    private inline fun link(block: LinkMetrics.() -> Unit) {
        _linkMetrics.update { it.copy().apply(block) }
    }
}