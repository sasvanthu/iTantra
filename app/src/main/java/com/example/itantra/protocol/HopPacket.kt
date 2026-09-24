package com.example.itantra.protocol

import java.io.ByteArrayOutputStream

/**
 * Mesh hop envelope.
 *
 * The mesh overlay is a pure store-and-forward broadcast: it has no per-peer
 * session, so every RETRO link frame a mesh node originates is wrapped in a
 * hop envelope before being flooded out over its edges. Relaying nodes never
 * decode the payload — they only forward the envelope (deduplicated, bounded
 * by a TTL) so each hop link's own reliability still applies, but the mesh
 * itself is best-effort.
 *
 * ```
 * +--------+---------+--------+--------+-----+------+------+--------+---------+
 * | magic  | version | hopId  | origin | ttl | hops | len  | crc    | payload |
 * | 4 bytes| 1 byte  | 8 bytes|16 bytes|1 b  | 1 b  |2 b   | 4 bytes| opaque  |
 * +--------+---------+--------+--------+-----+------+------+--------+---------+
 * ```
 *
 * [ttl] is the number of remaining relay hops (origin sets it via the router;
 * each relay decrements by one). [hops] counts how many relays already
 * forwarded this envelope. [payload] is a single opaque RETRO frame produced
 * by [FrameWriter]; this codec never inspects it.
 */
data class HopPacket(
    val hopId: Long,
    val origin: String,
    val ttl: Int,
    val hops: Int,
    val payload: ByteArray
) {
    fun serialize(): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "hop payload too large: ${payload.size}" }
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        writeLong(out, hopId)
        writeOrigin(out, origin)
        out.write(ttl.coerceIn(0, 0xFF) and 0xFF)
        out.write(hops.coerceIn(0, 0xFF) and 0xFF)
        out.write((payload.size shr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        val header = out.toByteArray()
        val crc = Packet.computeCRC(header)
        out.write(byteArrayOf(
            (crc shr 24).toByte(), (crc shr 16).toByte(),
            (crc shr 8).toByte(), crc.toByte()
        ))
        out.write(payload)
        return out.toByteArray()
    }

    companion object {
        val MAGIC = byteArrayOf(0x52, 0x4D, 0x58, 0x48) // 'R','M','X','H'
        const val VERSION: Int = 1
        const val ORIGIN_BYTES: Int = 16
        const val HEADER_SIZE: Int = 4 + 1 + 8 + ORIGIN_BYTES + 1 + 1 + 2 + 4 // 37
        const val CRC_OFFSET: Int = HEADER_SIZE - 4
        const val MAX_PAYLOAD_BYTES: Int = 1 shl 16 // 64 KiB (a RETRO frame is <= 64 KiB)

        fun hasMagic(data: ByteArray): Boolean =
            data.size >= 4 && data[0] == MAGIC[0] && data[1] == MAGIC[1] &&
            data[2] == MAGIC[2] && data[3] == MAGIC[3]

        fun deserialize(data: ByteArray): HopPacket? {
            if (data.size < HEADER_SIZE || !hasMagic(data)) return null
            if ((data[4].toInt() and 0xFF) != VERSION) return null

            val len = ((data[CRC_OFFSET - 2].toInt() and 0xFF) shl 8) or (data[CRC_OFFSET - 1].toInt() and 0xFF)
            if (len > MAX_PAYLOAD_BYTES || data.size < HEADER_SIZE + len) return null

            val header = data.copyOfRange(0, CRC_OFFSET)
            val storedCrc = ((data[CRC_OFFSET].toInt() and 0xFF) shl 24) or
                ((data[CRC_OFFSET + 1].toInt() and 0xFF) shl 16) or
                ((data[CRC_OFFSET + 2].toInt() and 0xFF) shl 8) or
                (data[CRC_OFFSET + 3].toInt() and 0xFF)
            if (Packet.computeCRC(header) != storedCrc) return null

            var offset = 5
            var hopId = 0L
            for (i in 0 until 8) {
                hopId = (hopId shl 8) or (data[offset + i].toLong() and 0xFF)
            }
            offset += 8
            val origin = String(data, offset, ORIGIN_BYTES, Charsets.US_ASCII)
                .substringBefore('\u0000').trim()
            offset += ORIGIN_BYTES
            val ttl = data[offset].toInt() and 0xFF
            val hops = data[offset + 1].toInt() and 0xFF
            val payload = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + len)
            return HopPacket(hopId = hopId, origin = origin, ttl = ttl, hops = hops, payload = payload)
        }

        /** Fixed-width US-ASCII origin field (padded/truncated, case preserved). */
        fun encodeOrigin(deviceId: String): ByteArray {
            val bytes = ByteArray(ORIGIN_BYTES)
            val id = deviceId.take(ORIGIN_BYTES).toByteArray(Charsets.US_ASCII)
            System.arraycopy(id, 0, bytes, 0, id.size)
            return bytes
        }

        private fun writeLong(out: ByteArrayOutputStream, value: Long) {
            for (shift in 56 downTo 0 step 8) {
                out.write(((value ushr shift) and 0xFF).toInt())
            }
        }

        private fun writeOrigin(out: ByteArrayOutputStream, origin: String) {
            out.write(encodeOrigin(origin))
        }
    }
}