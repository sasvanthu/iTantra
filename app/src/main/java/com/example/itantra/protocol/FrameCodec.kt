package com.example.itantra.protocol

import java.io.ByteArrayOutputStream

/**
 * Byte-stream framing for the RETRO link.
 *
 * TCP is a stream: one send() != one receive(). Every logical unit
 * (a serialized [Packet]) is wrapped in a frame so the receiver can find:
 *
 * ```
 * +--------+-----------+-------+--------+--------+-----------+-------+-------+
 * | magic  | version   | epoch | length | payload(<=64KiB) | crc32 | ...   |
 * | 4 bytes| 1 byte    |1 byte | 2 bytes|                  | 4 b   | next  |
 * +--------+-----------+-------+--------+--------+-----------+-------+-------+
 * ```
 *
 * The `epoch` byte ties a frame to a connection session, so late packets from
 * an old session can be identified and dropped after a reconnect.
 *
 * [FrameReader] tolerates partial reads, multiple frames per read and
 * corrupted bytes (it re-synchronizes on the magic). Corrupted frames are
 * reported to the caller but never crash it.
 */
object FrameWriter {

    val MAGIC = byteArrayOf(0x52, 0x46, 0x4C, 0x4B) // 'R','F','L','K'
    const val VERSION: Int = 1
    const val MAX_PAYLOAD_BYTES: Int = 1 shl 16 // 64 KiB
    const val HEADER_SIZE: Int = 8 // magic(4) + version(1) + epoch(1) + length(2)
    const val CRC_BYTES: Int = 4

    fun write(epoch: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "frame payload too large: ${payload.size}"
        }
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        out.write(epoch and 0xFF)
        out.write((payload.size shr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
        val body = out.toByteArray()
        val crc = Packet.computeCRC(body)
        out.write(byteArrayOf(
            (crc shr 24).toByte(), (crc shr 16).toByte(),
            (crc shr 8).toByte(), crc.toByte()
        ))
        return out.toByteArray()
    }
}

/**
 * A fully received frame. [epoch] is the session epoch embedded in the header,
 * [bytes] is the original payload (a serialized Packet).
 */
data class ReceivedFrame(val epoch: Int, val bytes: ByteArray)

sealed class FrameReadResult {
    /** A complete, CRC-valid frame extracted from the stream. */
    data class Complete(val frame: ReceivedFrame) : FrameReadResult()

    /** Progress consumed; more bytes needed before a frame boundary is known. */
    data class Incomplete(val bufferedBytes: Int) : FrameReadResult()

    /**
     * A corruption / framing error was encountered. [droppedBytes] is how many
     * bytes were skipped while re-synchronizing on the magic.
     */
    data class Corrupted(val droppedBytes: Int) : FrameReadResult()
}

class FrameReader {

    private val buffer = java.io.ByteArrayOutputStream()
    private var buffered: ByteArray = ByteArray(0)
    private var bufferedSize = 0

    fun snapshotSize(): Int = bufferedSize

    /** Feed a raw chunk of the byte stream; returns up to `maxFrames` results. */
    fun feed(chunk: ByteArray, maxFrames: Int = 64): List<FrameReadResult> {
        if (chunk.isNotEmpty()) {
            val grown = ByteArray(bufferedSize + chunk.size)
            if (bufferedSize > 0) System.arraycopy(buffered, 0, grown, 0, bufferedSize)
            System.arraycopy(chunk, 0, grown, bufferedSize, chunk.size)
            buffered = grown
            bufferedSize += chunk.size
        }

        val results = mutableListOf<FrameReadResult>()
        var pos = 0
        while (pos < bufferedSize && results.size < maxFrames) {
            if (bufferedSize - pos < FrameWriter.HEADER_SIZE) break

            val magicOk =
                buffered[pos] == FrameWriter.MAGIC[0] && buffered[pos + 1] == FrameWriter.MAGIC[1] &&
                    buffered[pos + 2] == FrameWriter.MAGIC[2] && buffered[pos + 3] == FrameWriter.MAGIC[3]

            if (!magicOk) {
                // Corrupt frame: skip one byte and keep re-synchronizing.
                results.add(FrameReadResult.Corrupted(1))
                pos++
                continue
            }

            val epoch = buffered[pos + 5].toInt() and 0xFF
            val length = ((buffered[pos + 6].toInt() and 0xFF) shl 8) or (buffered[pos + 7].toInt() and 0xFF)
            val total = FrameWriter.HEADER_SIZE + length + FrameWriter.CRC_BYTES

            if (length > FrameWriter.MAX_PAYLOAD_BYTES || total > bufferedSize - pos) {
                // Invalid length (corruption) or still waiting for more bytes.
                if (length > FrameWriter.MAX_PAYLOAD_BYTES) {
                    results.add(FrameReadResult.Corrupted(1))
                    pos++
                    continue
                }
                break
            }

            val body = ByteArray(FrameWriter.HEADER_SIZE + length)
            System.arraycopy(buffered, pos, body, 0, body.size)
            val storedCrc = ((buffered[pos + body.size].toInt() and 0xFF) shl 24) or
                ((buffered[pos + body.size + 1].toInt() and 0xFF) shl 16) or
                ((buffered[pos + body.size + 2].toInt() and 0xFF) shl 8) or
                (buffered[pos + body.size + 3].toInt() and 0xFF)
            val computedCrc = Packet.computeCRC(body)

            val payload = ByteArray(length)
            System.arraycopy(buffered, pos + FrameWriter.HEADER_SIZE, payload, 0, length)

            if (storedCrc == computedCrc) {
                results.add(FrameReadResult.Complete(ReceivedFrame(epoch, payload)))
            } else {
                results.add(FrameReadResult.Corrupted(1))
            }
            pos += total
        }

        if (pos > 0) {
            val remaining = bufferedSize - pos
            if (remaining == 0) {
                buffered = ByteArray(0)
                bufferedSize = 0
            } else {
                val moved = ByteArray(remaining)
                System.arraycopy(buffered, pos, moved, 0, remaining)
                buffered = moved
                bufferedSize = remaining
            }
        }

        if (results.isEmpty() && bufferedSize >= 512 * 1024) {
            // Unbounded garbage that never re-synchronized: reset.
            buffered = ByteArray(0)
            bufferedSize = 0
            results.add(FrameReadResult.Corrupted(bufferedSize))
        }
        return results
    }
}