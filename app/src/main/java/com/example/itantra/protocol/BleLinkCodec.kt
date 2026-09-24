package com.example.itantra.protocol

/**
 * Byte-stream adaptation for BLE GATT atoms.
 *
 * BLE does not expose a stream like TCP: a single ATT packet (a
 * [BluetoothGatt] write or a notification) is bounded by the negotiated MTU,
 * typically 20 bytes out of the box and up to ~512 after [requestMtu]. The
 * RETRO link, however, produces arbitrary-size frames (up to 64 KiB) and
 * expects to consume a plain byte stream via [FrameReader.feed].
 *
 * This codec bridges the two: [BleChunkWriter.split] slices any byte array
 * into GATT-sized chunks and [BleChunkReader.feed] stitches them back into a
 * contiguous byte stream. Each chunk carries:
 *
 * ```
 * +--------+-------+---------------------+
 * | magic  |  seq  | payload             |
 * | 'B','L'|uint16 | <= chunkPayloadSize |
 * +--------+-------+---------------------+
 * ```
 *
 * The 16-bit [seq] (wrapping modulo 65536) makes the reader deterministic
 * about ordering. ATT guarantees in-order, lossless delivery per connection,
 * so out-of-band `seq` means the controller dropped a chunk (e.g. a notification
 * buffer overrun at high throughput). On a gap the reader reports how many
 * chunks were lost and resynchronizes on the next chunk; the underlying
 * [FrameReader] re-synchronizes on 'RFLK' magic and the session-level
 * ACK/NACK reliability layer in `BaseTransportEngine` retransmits what was
 * lost. The chunk codec itself is codec-agnostic.
 *
 * Pure Kotlin on purpose so the whole adaptation is unit-testable on the JVM.
 */
object BleLinkCodec {

    val MAGIC = byteArrayOf(0x42, 0x4C) // 'B','L'

    const val HEADER_SIZE: Int = 4 // magic(2) + seq(2)

    /** Payload bytes we aim for per ATT atom when no MTU negotiation happened. */
    const val DEFAULT_CHUNK_PAYLOAD: Int = 500

    const val MAX_CHUNK_PAYLOAD = 1 shl 15 // 32 KiB, far above any ATT MTU

    /** True if [bytes] starts with the chunk magic. */
    fun hasMagic(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == MAGIC[0] && bytes[1] == MAGIC[1]

    fun readSeq(bytes: ByteArray): Int {
        var seq = 0
        if (bytes.size >= 4) {
            seq = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        }
        return seq
    }
}

/**
 * Slices a byte array into GATT-sized chunks which a [BleChunkReader] on the
 * other side reassembles. One writer per direction per session; call [reset]
 * at the start of a new session so sequence numbers restart cleanly.
 */
class BleChunkWriter(private val chunkPayloadSize: Int) {

    init {
        require(chunkPayloadSize in 1..BleLinkCodec.MAX_CHUNK_PAYLOAD) {
            "invalid BLE chunk payload size $chunkPayloadSize"
        }
    }

    private var seq = 0
    private var started = false

    /** Restart sequence numbering (new session / new epoch). */
    fun reset() {
        seq = 0
        started = false
    }

    /** Chop [stream] into payload-size chunks with magic + running seq headers. */
    fun split(stream: ByteArray): List<ByteArray> {
        if (stream.isEmpty()) return emptyList()
        val count = (stream.size + chunkPayloadSize - 1) / chunkPayloadSize
        val chunks = ArrayList<ByteArray>(count)
        var offset = 0
        repeat(count) {
            val len = minOf(chunkPayloadSize, stream.size - offset)
            val chunk = ByteArray(BleLinkCodec.HEADER_SIZE + len)
            chunk[0] = BleLinkCodec.MAGIC[0]
            chunk[1] = BleLinkCodec.MAGIC[1]
            chunk[2] = ((seq ushr 8) and 0xFF).toByte()
            chunk[3] = (seq and 0xFF).toByte()
            System.arraycopy(stream, offset, chunk, BleLinkCodec.HEADER_SIZE, len)
            chunks.add(chunk)
            seq = (seq + 1) and 0xFFFF
            offset += len
        }
        started = true
        return chunks
    }
}

sealed class BleChunkResult {

    /**
     * A contiguous slice of the reassembled byte stream. Feed it straight into
     * [FrameReader.feed] as though it were a TCP read.
     */
    data class Stream(val bytes: ByteArray) : BleChunkResult()

    /**
     * The reader detected a gap in the sequence: [lostChunks] consecutive
     * chunks are missing. The bytes delivered before this event are intact;
     * the chunk that produced the gap is discarded and the next in-order chunk
     * continues the stream. Callers should bump the link `packetLoss` metric.
     */
    data class Gap(val lostChunks: Int) : BleChunkResult()
}

/**
 * Reassembles chunks from a [BleChunkWriter] into a byte stream. One reader
 * per direction per session; call [reset] for a new session.
 */
class BleChunkReader {

    private var prevSeq = -1
    private var first = true

    /** Restart continuity tracking for a new session. */
    fun reset() {
        prevSeq = -1
        first = true
    }

    fun feed(chunk: ByteArray): BleChunkResult {
        if (!BleLinkCodec.hasMagic(chunk)) {
            // Corrupt chunk header: nothing to deliver; the stream re-syncs on
            // the next valid chunk.
            return BleChunkResult.Gap(1)
        }
        val seq = BleLinkCodec.readSeq(chunk)
        if (!first && seq != ((prevSeq + 1) and 0xFFFF)) {
            val lost = if (seq > prevSeq) seq - prevSeq - 1 else (0xFFFF - prevSeq) + seq
            prevSeq = seq
            return BleChunkResult.Gap(lost)
        }
        first = false
        prevSeq = seq
        val payload = chunk.copyOfRange(BleLinkCodec.HEADER_SIZE, chunk.size)
        return BleChunkResult.Stream(payload)
    }
}