package com.example.itantra.transport

import com.example.itantra.codec.BinaryCodec
import com.example.itantra.codec.Language
import com.example.itantra.codec.PacketType
import com.example.itantra.protocol.CapabilityMessage
import com.example.itantra.protocol.FrameReadResult
import com.example.itantra.protocol.FrameReader
import com.example.itantra.protocol.FrameWriter
import com.example.itantra.protocol.Packet
import com.example.itantra.protocol.Packetizer
import com.example.itantra.protocol.RetransmissionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel

/**
 * Shared RETRO link engine, independent of the physical medium.
 *
 * Implements (moving opaque [ReassembledPayload]s in and [ByteArray]s out):
 *  - capability handshake (CAPABILITY -> CAPABILITY_ACK) with protocol + codec
 *    version negotiation and a per-session epoch
 *  - packet framing via [FrameReader]/[FrameWriter]
 *  - ACK / NACK reliability with [RetransmissionManager] (DATA + END tracked)
 *  - duplicate detection (re-ACK, never re-process)
 *  - out-of-order packet reassembly with gap-detection NACK
 *  - emergency priority queue (CRITICAL frames drained before NORMAL/LOW)
 *  - session-scoped epochs so old-session frames are dropped after a reconnect
 *
 * Pure Kotlin (JVM) so the whole reliability stack is unit-testable.
 */
abstract class BaseTransportEngine(
    final override val transportType: TransportType,
    protected val engineName: String,
    protected val networkSimulator: NetworkSimulator,
    protected val ackTimeoutMs: Long = 2000L,
    protected val maxRetries: Int = 3
) : TransportEngine {

    companion object {
        const val PROTOCOL_VERSION = 1
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        const val TRANSFER_TIMEOUT_MS = 30_000L
    }

    protected val baseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    protected val localDeviceId: String =
        "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).uppercase()

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

    override fun observeIncoming() = _incomingPayloads.map { it.payload }

    private val retransmissionManager = RetransmissionManager(ackTimeoutMs, maxRetries)
    private val messageIdCounter = AtomicLong(1)
    private val epochCounter = AtomicLong(1)

    /** Priority scheduling + adaptive bandwidth decisions (Phase 12/15). */
    private val governor = AdaptiveLinkGovernor()

    private val _bandwidthMode =
        MutableStateFlow(com.example.itantra.data.AdaptiveBandwidth.BandwidthMode.NORMAL)

    /** Current adaptive mode derived from measured link metrics. */
    val bandwidthMode: StateFlow<com.example.itantra.data.AdaptiveBandwidth.BandwidthMode> =
        _bandwidthMode.asStateFlow()

    private var isHost = false
    private var sessionEpoch = 0
    @Volatile private var lastError: String? = null

    private var outboundFrames = PriorityFrameQueue()
    private var incomingChunks = Channel<ByteArray>(Channel.BUFFERED)

    private var frameReader = FrameReader()
    private var sessionJob: Job? = null
    private var writeLoopJob: Job? = null
    private var framerLoopJob: Job? = null
    private var retransmitLoopJob: Job? = null

    private var handshakeDeferred: CompletableDeferred<ConnectionSession>? = null

    private val activeSends = ConcurrentHashMap<Long, SendTracker>()
    private val receiveBuffers = ConcurrentHashMap<Long, InProgressMessage>()

    /**
     * Message ids already delivered this session. Late duplicates of any
     * completed message (frame-level duplication, retransmission after
     * delivery) are re-ACKed and dropped so the application sees EXACTLY ONE
     * delivery per message id.
     */
    private val completedMessages = ConcurrentHashMap.newKeySet<Long>()

    init {
        retransmissionManager.onMaxRetries = { key ->
            activeSends[key.messageId]?.onPermanentFailure(key)
        }
    }

    // ------------------------------------------------------------------
    // Subclass hooks
    // ------------------------------------------------------------------

    /** Bind/accept; must set up the read pump and return true on success. */
    protected abstract suspend fun openLinkAsHost(port: Int): Boolean

    /** Connect out; must set up the read pump and return true on success. */
    protected abstract suspend fun openLinkAsClient(host: String, port: Int): Boolean

    /** Write one fully-framed byte array to the peer. Called from the single write loop. */
    protected abstract suspend fun writeRawFrame(frame: ByteArray)

    /** Close sockets/streams after a session ends. */
    protected abstract suspend fun tearDown()

    /** Best-effort local IP addressed over the active link. */
    protected open fun pollLocalAddress(): String = ""

    /** Each raw chunk from the medium arrives here; never throws. */
    protected fun feedIncoming(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        if (!isSessionActive()) return
        baseScope.launch {
            deliverIncoming(chunk)
        }
    }

    /** Deterministic delivery used by loopback transports (single caller, strict FIFO). */
    protected suspend fun deliverIncoming(chunk: ByteArray) {
        if (!isSessionActive()) return
        try {
            incomingChunks.send(chunk)
        } catch (e: Exception) {
            // link already torn down
        }
    }

    /**
     * Report medium-level packet loss observed outside the frame layer (e.g. a
     * BLE notification gap), so metrics stay honest while the stream heals via
     * magic re-synchronization and session-level retransmission.
     */
    protected fun reportLinkPacketLoss(count: Int) {
        if (count <= 0) return
        link { packetLoss += count }
    }

    protected fun isSessionActive(): Boolean = sessionJob?.let { it.isActive } ?: false

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override suspend fun startHost(port: Int): Boolean {
        finishSession("restart")
        prepareSession(true)
        setStatus(ConnectionStatus.WAITING)
        return if (openLinkAsHost(port)) {
            establishLink()
        } else {
            failLink("could not listen on port $port")
            finishSession("host failed")
            false
        }
    }

    override suspend fun connect(host: String, port: Int): Boolean {
        finishSession("restart")
        prepareSession(false)
        setStatus(ConnectionStatus.CONNECTING)
        return if (openLinkAsClient(host, port)) {
            establishLink()
        } else {
            failLink("connection to $host:$port failed")
            finishSession("connect failed")
            false
        }
    }

    override suspend fun disconnect() {
        finishSession("local disconnect")
    }

    private fun prepareSession(host: Boolean) {
        isHost = host
        sessionEpoch = (epochCounter.getAndIncrement() and 0xFF).toInt()
        activeSends.clear()
        receiveBuffers.clear()
        frameReader = FrameReader()
        retransmissionManager.clear()
        _linkMetrics.value = LinkMetrics()
        _messageInfo.value = MessageInfo()

        outboundFrames = PriorityFrameQueue()
        incomingChunks = Channel(Channel.BUFFERED)
        governor.reset()
        _bandwidthMode.value = com.example.itantra.data.AdaptiveBandwidth.BandwidthMode.NORMAL
        completedMessages.clear()

        sessionJob = Job()
        sessionJob?.let { job ->
            writeLoopJob = baseScope.launch { runWriteLoop(job) }
            framerLoopJob = baseScope.launch { runFramerLoop() }
            retransmitLoopJob = baseScope.launch { runRetransmitLoop(job) }
        }
        handshakeDeferred = null
    }

    private suspend fun establishLink(): Boolean {
        setStatus(ConnectionStatus.CONNECTING)

        val deferred = CompletableDeferred<ConnectionSession>()
        handshakeDeferred = deferred

        // Announce ourselves (peer answers symmetrically).
        sendCapability(PacketType.CAPABILITY)

        val established = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { deferred.await() }
        if (established == null) {
            failLink("handshake timed out")
            finishSession("handshake failed")
            return false
        }

        handshakeDeferred = null
        _session.value = established
        setStatus(ConnectionStatus.CONNECTED)
        emitEvent(LinkMessageEvent.Connected(established))
        return true
    }

    protected suspend fun finishSession(reason: String) {
        val hadSession = _session.value != null
        sessionEpoch = (epochCounter.getAndIncrement() and 0xFF).toInt()

        handshakeDeferred?.cancel()
        handshakeDeferred = null

        writeLoopJob?.cancel()
        writeLoopJob = null
        framerLoopJob?.cancel()
        framerLoopJob = null
        retransmitLoopJob?.cancel()
        retransmitLoopJob = null
        sessionJob?.cancel()
        sessionJob = null

        // The priority queue is not cancellable: session cancellation ends the
        // write loop, and the queue itself is replaced on the next prepareSession.
        incomingChunks.close()

        try {
            tearDown()
        } catch (e: Exception) {
            // best-effort socket teardown
        }

        retransmissionManager.clear()

        val reasonDetail = lastError?.let { " ($it)" } ?: ""
        for (tracker in activeSends.values) {
            tracker.completeAbandoned("link closed$reasonDetail")
        }
        activeSends.clear()
        receiveBuffers.clear()
        completedMessages.clear()

        _session.value = null
        _linkMetrics.value = LinkMetrics()
        _messageInfo.value = MessageInfo()
        lastError = null
        setStatus(ConnectionStatus.DISCONNECTED)

        if (hadSession) {
            emitEvent(LinkMessageEvent.Disconnected(reason))
        }
    }

    override fun isConnected(): Boolean = _connectionStatus.value == ConnectionStatus.CONNECTED

    /** Stable per-process device identifier; exposed read-only for the UI/tests. */
    override fun getDeviceId(): String = localDeviceId

    override fun getRoleLabel(): String = if (isHost) "HOST" else "DEVICE"

    override fun getLinkName(): String = engineName

    override fun getLocalAddress(): String = pollLocalAddress()

    /** Record the failure reason and surface an ERROR status to the UI. */
    private fun failLink(reason: String) {
        lastError = reason
        setStatus(ConnectionStatus.ERROR)
    }

    // ------------------------------------------------------------------
    // Send path
    // ------------------------------------------------------------------

    override suspend fun send(
        data: ByteArray,
        language: Language,
        isEmergency: Boolean,
        priority: Byte
    ): SendResult? {
        if (!isConnected()) return null
        if (data.isEmpty()) return null

        val messageId = messageIdCounter.getAndIncrement()
        val packets = Packetizer.buildPackets(
            payload = data,
            language = language,
            messageId = messageId,
            priority = priority.toInt(),
            isEmergency = isEmergency,
            maxPayload = governor.maxPayload()
        )

        val tracker = SendTracker(messageId, packets.size, data.size, isEmergency)
        activeSends[messageId] = tracker

        _messageInfo.value = MessageInfo(
            state = MessageState.TRANSMITTING,
            detail = "sending ${packets.size} packets",
            originalBytes = data.size,
            encodedBytes = data.size,
            packetCount = packets.size,
            isEmergency = isEmergency
        )

        for (p in packets) {
            if (p.packetType == PacketType.TEXT_DATA || p.packetType == PacketType.END) {
                retransmissionManager.track(p)
                tracker.addReliable(p)
            }
            tracker.transmittedPackets++
            enqueueFrame(p)
        }

        _messageInfo.update {
            it.copy(
                state = MessageState.WAITING_ACK,
                detail = "waiting for ACKs",
                transmittedPackets = tracker.transmittedPackets
            )
        }

        // Hard watchdog: never suspend forever on a dead link.
        val killer = baseScope.launch {
            delay(TRANSFER_TIMEOUT_MS)
            if (!tracker.deferred.isCompleted) {
                retransmissionManager.dropMessage(messageId)
                tracker.onTimeout()
            }
        }
        val result = tracker.deferred.await()
        killer.cancel()

        // A "max retries" decision can star-cross an airborne final ACK: the
        // message may have fully delivered on the peer. Give stragglers one
        // retransmission window to reconcile before reporting failure.
        var final = result
        if (final.failed) {
            delay(ackTimeoutMs)
            if (tracker.allReliableAcked()) {
                final = final.copy(failed = false, detail = "delivered (final ACK landed)")
            }
        }
        activeSends.remove(messageId)

        _messageInfo.update {
            it.copy(
                state = if (final.failed) MessageState.FAILED else MessageState.COMPLETE,
                detail = final.detail,
                transmittedBytes = final.transmittedBytes,
                acknowledgedPackets = tracker.acknowledgedCount(),
                retransmissions = final.retransmissions,
                roundTripTimeMs = final.roundTripTimeMs,
                totalLatencyMs = System.currentTimeMillis() - tracker.startMs,
                failed = final.failed
            )
        }
        emitEvent(LinkMessageEvent.Transmitted(
            messageId = messageId,
            transmittedBytes = final.transmittedBytes,
            packetCount = final.packetCount,
            retransmissions = final.retransmissions,
            roundTripTimeMs = final.roundTripTimeMs,
            failed = final.failed
        ))
        return final
    }

    /** Wire priority used by the scheduler for each packet class. */
    private fun framePriority(packet: Packet): Int = when (packet.packetType) {
        PacketType.ACK, PacketType.NACK, PacketType.RETRANSMIT,
        PacketType.CAPABILITY, PacketType.CAPABILITY_ACK -> 2
        else -> packet.priority.toInt().coerceIn(0, 3)
    }

    /** Serialize, count and enqueue one packet (priority-aware, FIFO per bucket). */
    private suspend fun enqueueFrame(packet: Packet) {
        val frame = FrameWriter.write(sessionEpoch, packet.serialize())
        val size = frame.size
        link { transmittedBytes += size }
        val tracker = activeSends[packet.messageId]
        if (tracker != null) synchronized(tracker) { tracker.transmittedBytes += size }

        if (packet.packetType == PacketType.TEXT_DATA || packet.packetType == PacketType.END) {
            link { packetsSent++ }
        } else {
            link { controlPacketsSent++ }
        }
        outboundFrames.enqueue(frame, framePriority(packet))
    }

    private suspend fun runWriteLoop(session: Job) {
        try {
            while (session.isActive) {
                val frame = outboundFrames.take()
                val frames = networkSimulator.applyOutgoing(frame)
                for (f in frames) {
                    writeRawFrame(f)
                }
            }
        } catch (e: Exception) {
            // write failure -> session teardown cancels the loop's session
        }
    }

    private suspend fun runFramerLoop() {
        for (chunk in incomingChunks) {
            link { receivedBytes += chunk.size }
            val results = frameReader.feed(chunk)
            for (result in results) {
                when (result) {
                    is FrameReadResult.Complete -> handleFrame(result.frame)
                    is FrameReadResult.Corrupted -> {
                        link { corruptedFrames++; packetLoss++ }
                    }
                    is FrameReadResult.Incomplete -> { /* keep buffering */ }
                }
            }
        }
    }

    private suspend fun runRetransmitLoop(session: Job) {
        while (session.isActive) {
            delay(ackTimeoutMs / 2)
            governor.observe(_linkMetrics.value)
            val mode = governor.mode()
            if (mode != _bandwidthMode.value) _bandwidthMode.value = mode
            val overdue = retransmissionManager.checkTimeouts()
            for (p in overdue) {
                val tracker = activeSends[p.messageId]
                if (tracker != null) synchronized(tracker) { tracker.retransmissions++ }
                link { retransmissions++; packetsSent++ }
                enqueueFrame(p)
            }
        }
    }

    // ------------------------------------------------------------------
    // Receive path
    // ------------------------------------------------------------------

    private suspend fun handleFrame(frame: com.example.itantra.protocol.ReceivedFrame) {
        if (frame.epoch != sessionEpoch) {
            // Stale frame from an old session: drop silently.
            link { corruptedFrames++ }
            return
        }
        val packet = Packet.deserialize(frame.bytes) ?: run {
            link { corruptedFrames++; packetLoss++ }
            return
        }

        when (packet.packetType) {
            PacketType.CAPABILITY, PacketType.CAPABILITY_ACK -> handleCapability(packet)
            PacketType.ACK -> {
                link { packetsReceived++ }
                handleAck(packet.messageId, packet.sequenceId)
            }
            PacketType.NACK, PacketType.RETRANSMIT -> {
                link { packetsReceived++ }
                handleNack(packet.messageId, packet.sequenceId)
            }
            PacketType.START, PacketType.TEXT_DATA, PacketType.END -> handleMessagePacket(packet)
            else -> { /* HEARTBEAT / LANGUAGE_INFO / unknown: ignore */ }
        }
    }

    private suspend fun handleCapability(packet: Packet) {
        val capability = CapabilityMessage.deserialize(packet.payload)
        if (capability == null) {
            link { corruptedFrames++; packetLoss++ }
            return
        }
        if (capability.protocolVersion != PROTOCOL_VERSION) {
            failLink("incompatible protocol v${capability.protocolVersion}")
            baseScope.launch { finishSession("handshake rejected") }
            return
        }

        // Reply only to announcements, and only while we are still waiting on
        // our own handshake. Replying to CAPABILITY_ACK unconditionally would
        // ping-pong CAPABILITY_ACK forever between the two peers, starving
        // data frames (a bounded channel used to mask this by throttling).
        // A pending handshake still lets a lone CAP_ACK complete our side in
        // case our own announcement was lost, so robustness is preserved.
        if (packet.packetType == PacketType.CAPABILITY || handshakeDeferred != null) {
            sendCapability(PacketType.CAPABILITY_ACK)
        }

        handshakeDeferred?.let { deferred ->
            val now = System.currentTimeMillis()
            deferred.complete(
                ConnectionSession(
                    sessionId = "RFLK-$sessionEpoch-${UUID.randomUUID().toString().substring(0, 4)}",
                    localDeviceId = localDeviceId,
                    remoteDeviceId = capability.deviceId,
                    protocolVersion = capability.protocolVersion,
                    codecVersion = capability.codecVersion,
                    supportedLanguages = capability.languages,
                    connectionStartTime = now,
                    lastReceivedTime = now,
                    epoch = sessionEpoch,
                    isHost = isHost
                )
            )
        }
    }

    private suspend fun sendCapability(type: PacketType) {
        val payload = CapabilityMessage(
            protocolVersion = PROTOCOL_VERSION,
            codecVersion = BinaryCodec.VERSION,
            deviceId = localDeviceId,
            languages = Language.values().filterNot { it == Language.UNKNOWN }
        ).serialize()
        val packet = when (type) {
            PacketType.CAPABILITY_ACK -> Packet.createCapabilityAckPacket(0L, Language.ENGLISH, payload)
            else -> Packet.createCapabilityPacket(0L, Language.ENGLISH, payload)
        }
        enqueueFrame(packet)
    }

    private fun handleAck(messageId: Long, sequenceId: Int) {
        val rtt = retransmissionManager.ack(messageId, sequenceId)
        if (rtt != null) {
            link { roundTripTimeMs = rtt; ackLatencyMs = rtt }
        }
        val tracker = activeSends[messageId]
        tracker?.onAcked(messageId, sequenceId, rtt)
        if (tracker != null) {
            _messageInfo.update {
                it.copy(acknowledgedPackets = tracker.acknowledgedCount(), transmittedPackets = it.transmittedPackets)
            }
        }
    }

    private suspend fun handleNack(messageId: Long, sequenceId: Int) {
        val packet = retransmissionManager.nack(messageId, sequenceId)
        if (packet != null) {
            val tracker = activeSends[messageId]
            if (tracker != null) synchronized(tracker) { tracker.retransmissions++ }
            link { retransmissions++; packetsSent++ }
            enqueueFrame(packet)
        }
    }

    private suspend fun handleMessagePacket(packet: Packet) {
        link { packetsReceived++ }
        val messageId = packet.messageId

        // Already delivered this session (frame duplication, or a
        // retransmission that arrived after assembly): re-ACK, never re-process.
        if (messageId in completedMessages) {
            link { duplicatePackets++ }
            sendAck(packet, null)
            return
        }

        if (packet.packetType == PacketType.START) {
            receiveBuffers.computeIfAbsent(messageId) { InProgressMessage(packet.language) }
            sendAck(packet, null)
            return
        }

        val created = !receiveBuffers.containsKey(messageId)
        val buf = receiveBuffers.computeIfAbsent(messageId) {
            _messageInfo.update { it.copy(state = MessageState.RECEIVING, detail = "receiving message $messageId") }
            InProgressMessage(packet.language)
        }
        if (created) _messageInfo.update { it.copy(state = MessageState.RECEIVING) }
        buf.language = packet.language
        if (packet.priority == 3.toByte()) buf.isEmergency = true

        if (packet.packetType == PacketType.TEXT_DATA) {
            val seq = packet.sequenceId
            if (buf.parts.containsKey(seq)) {
                // Duplicate delivery: re-ACK, never re-process.
                link { duplicatePackets++ }
                sendAck(packet, buf)
                return
            }
            if (seq > buf.maxSeq) {
                for (missing in buf.maxSeq + 1 until seq) {
                    sendNack(messageId, missing)
                }
            }
            buf.parts[seq] = packet.payload
            if (seq > buf.maxSeq) buf.maxSeq = seq
            sendAck(packet, buf)
            tryAssemble(buf, messageId)
            return
        }

        if (packet.packetType == PacketType.END) {
            if (buf.endSeen) {
                sendAck(packet, buf)
                return
            }
            buf.expectedDataCount = packet.payload.toDataCount()
            buf.endSeen = true
            sendAck(packet, buf)
            tryAssemble(buf, messageId)
        }
    }

    private fun ByteArray.toDataCount(): Int {
        if (size == 4) {
            return ((this[0].toInt() and 0xFF) shl 24) or
                ((this[1].toInt() and 0xFF) shl 16) or
                ((this[2].toInt() and 0xFF) shl 8) or
                (this[3].toInt() and 0xFF)
        }
        return -1
    }

    private suspend fun sendAck(packet: Packet, buf: InProgressMessage?) {
        if (buf != null) {
            link { ackLatencyMs = System.currentTimeMillis() - buf.firstReceivedAt }
            synchronized(buf) { buf.acksSent++ }
        }
        val ack = Packet.createAckPacket(packet.messageId, packet.sequenceId)
        enqueueFrame(ack)
    }

    private suspend fun sendNack(messageId: Long, sequenceId: Int) {
        link { packetLoss++ }
        receiveBuffers[messageId]?.let { synchronized(it) { it.nacksSent++ } }
        val nack = Packet.createNackPacket(messageId, sequenceId)
        enqueueFrame(nack)
    }

    private suspend fun tryAssemble(buf: InProgressMessage, messageId: Long) {
        if (!buf.endSeen) return
        if (buf.parts.isEmpty() && buf.expectedDataCount <= 0) return
        val expected = if (buf.expectedDataCount > 0) {
            (1..buf.expectedDataCount).toSet()
        } else {
            (1..buf.maxSeq).toSet()
        }
        if (buf.parts.keys != expected) {
            // Gap detected at END: request the missing packets.
            for (m in expected.filter { !buf.parts.containsKey(it) }) {
                sendNack(messageId, m)
            }
            return
        }

        receiveBuffers.remove(messageId)
        completedMessages.add(messageId)
        val payload = Packetizer.assemble(buf.parts)

        val reassembled = ReassembledPayload(
            messageId = messageId,
            payload = payload,
            language = buf.language,
            isEmergency = buf.isEmergency,
            receivedAt = System.currentTimeMillis(),
            dataPackets = buf.parts.size,
            acksSent = buf.acksSent,
            retransmissionRequestsSent = buf.nacksSent,
            sessionId = _session.value?.sessionId ?: ""
        )

        _messageInfo.update {
            it.copy(
                state = MessageState.COMPLETE,
                detail = "received $messageId (${payload.size} bytes)",
                originalBytes = payload.size,
                encodedBytes = payload.size,
                packetCount = buf.parts.size,
                isEmergency = buf.isEmergency,
                totalLatencyMs = System.currentTimeMillis() - buf.firstReceivedAt
            )
        }

        emitEvent(LinkMessageEvent.Received(
            messageId = messageId,
            payloadBytes = payload.size,
            dataPackets = buf.parts.size,
            isEmergency = buf.isEmergency,
            receivedAt = reassembled.receivedAt
        ))
        _incomingPayloads.emit(reassembled)
    }

    private fun emitEvent(event: LinkMessageEvent) {
        baseScope.launch { _linkEvents.emit(event) }
    }

    private fun setStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
        emitEvent(LinkMessageEvent.StatusChange(status))
    }

    private fun link(block: LinkMetrics.() -> Unit) {
        _linkMetrics.update { it.copy().apply(block) }
    }

    // ------------------------------------------------------------------
    // Tracker (per in-flight message)
    // ------------------------------------------------------------------

    private class SendTracker(
        val messageId: Long,
        val packetCount: Int,
        val originalBytes: Int,
        val isEmergency: Boolean
    ) {
        val startMs = System.currentTimeMillis()
        val deferred = CompletableDeferred<SendResult>()

        private val remaining = mutableSetOf<RetransmissionManager.PacketKey>()
        private val ackedKeys = mutableSetOf<RetransmissionManager.PacketKey>()
        private var failed = false
        private var detail = ""
        private var reliableCount = 0

        @Volatile var transmittedBytes: Int = 0

        @Volatile var transmittedPackets: Int = 0

        @Volatile var retransmissions: Int = 0

        private var acknowledged = 0
        private var rttSum = 0L
        private var rttCount = 0

        fun addReliable(packet: Packet) {
            synchronized(this) {
                remaining.add(RetransmissionManager.PacketKey(packet.messageId, packet.sequenceId))
                reliableCount++
            }
        }

        fun acknowledgedCount(): Int = synchronized(this) { acknowledged }

        /** True once every reliable packet (DATA + END) has been ACKed. */
        fun allReliableAcked(): Boolean = synchronized(this) { reliableCount > 0 && ackedKeys.size >= reliableCount }

        fun onAcked(messageId: Long, sequenceId: Int, rtt: Long?) {
            val done: Boolean
            synchronized(this) {
                val key = RetransmissionManager.PacketKey(messageId, sequenceId)
                if (ackedKeys.add(key)) {
                    acknowledged++
                    remaining.remove(key)
                }
                if (rtt != null && rtt >= 0) {
                    rttSum += rtt
                    rttCount++
                }
                done = remaining.isEmpty()
            }
            if (done) complete()
        }

        fun onPermanentFailure(key: RetransmissionManager.PacketKey) {
            val done: Boolean
            synchronized(this) {
                // A late "max retries" for a packet whose ACK already completed
                // the transfer must not flip a successful send to failed.
                if (deferred.isCompleted) return
                if (!remaining.remove(key)) return // already ACKed: not a real failure
                failed = true
                detail = "max retries exceeded"
                done = remaining.isEmpty()
            }
            if (done) complete()
        }

        fun onTimeout() {
            synchronized(this) {
                if (failed) return
                failed = true
                detail = "transfer timed out"
            }
            complete()
        }

        fun completeAbandoned(reason: String) {
            synchronized(this) {
                if (!failed) {
                    failed = true
                    detail = reason
                }
            }
            complete()
        }

        private fun complete() {
            if (deferred.isCompleted) return
            val snapshot = SendResult(
                messageId = messageId,
                transmittedBytes = synchronized(this) { transmittedBytes },
                packetCount = packetCount,
                retransmissions = synchronized(this) { retransmissions },
                roundTripTimeMs = synchronized(this) { if (rttCount > 0) rttSum / rttCount else 0 },
                failed = synchronized(this) { failed },
                detail = synchronized(this) { detail }
            )
            deferred.complete(snapshot)
        }
    }

    private class InProgressMessage(
        var language: Language,
        var isEmergency: Boolean = false
    ) {
        val firstReceivedAt = System.currentTimeMillis()
        val parts = sortedMapOf<Int, ByteArray>()
        var maxSeq = 0
        var endSeen = false
        var expectedDataCount = -1
        var acksSent = 0
        var nacksSent = 0
    }
}