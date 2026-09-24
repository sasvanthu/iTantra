package com.example.itantra.protocol

import com.example.itantra.codec.Language
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Handshake payload exchanged at link start (CAPABILITY / CAPABILITY_ACK).
 *
 * Fixed-width binary layout so both sides agree up-front on protocol version,
 * codec version, supported languages and identity:
 *
 * ```
 * +---------------+-----------+---------------------+------------+--------+------+-----------+
 * | protocolVer=1 | codecVer  | deviceId (len+utf8) | lang count | lang[] | flags| marker    |
 * | 1 byte        | 1 byte    | 1+<=255 bytes       | 1 byte     | n*1    | 1    | bytes 'R'
 * +---------------+-----------+---------------------+------------+--------+------+-----------+
 * ```
 *
 * payloadSizeBound = 1 + 1 + (1 + deviceId.length) + 1 + languages.size + 1 (+ 3 marker)
 */
data class CapabilityMessage(
    val protocolVersion: Int,
    val codecVersion: Int,
    val deviceId: String,
    val languages: List<Language>
) {
    val payloadSize: Int
        get() = CAP_HEADER + deviceIdBytes.size + languages.size + CAP_FOOTER

    private val deviceIdBytes: ByteArray
        get() = deviceId.encodeToByteArray()

    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(protocolVersion)
        dos.writeByte(codecVersion)
        val devId = deviceIdBytes
        dos.writeByte(devId.size)
        dos.write(devId)
        dos.writeByte(languages.size)
        for (lang in languages) dos.writeByte(Language.toByte(lang).toInt())
        dos.writeByte(0) // flags
        dos.write(byteArrayOf(0x52, 0x47, 0x45)) // 'RGE' trailing marker
        return out.toByteArray()
    }

    companion object {
        const val CAP_HEADER: Int = 4 // protocolVer + codecVer + devIdLen + langCount
        const val CAP_FOOTER: Int = 4 // flags + 3 marker bytes

        const val MAX_DEVICE_ID_BYTES: Int = 255
        const val MAX_LANGUAGES: Int = 64

        /** Sanity bound for the whole serialized payload. */
        const val MAX_PAYLOAD_BYTES: Int = 1 + 1 + 1 + MAX_DEVICE_ID_BYTES + 1 + MAX_LANGUAGES + 4

        fun serialize(capability: CapabilityMessage): ByteArray = capability.serialize()

        fun deserialize(data: ByteArray): CapabilityMessage? {
            return try {
                val dis = DataInputStream(ByteArrayInputStream(data))
                val protocolVersion = dis.readByte().toInt() and 0xFF
                val codecVersion = dis.readByte().toInt() and 0xFF
                val devIdLen = dis.readByte().toInt() and 0xFF
                if (devIdLen > MAX_DEVICE_ID_BYTES) return null
                val devId = ByteArray(devIdLen)
                dis.readFully(devId)
                val langCount = dis.readByte().toInt() and 0xFF
                if (langCount > MAX_LANGUAGES) return null
                val languages = ArrayList<Language>(langCount)
                repeat(langCount) {
                    languages.add(Language.fromByte(dis.readByte()))
                }
                dis.readByte() // flags
                val marker = ByteArray(3)
                dis.readFully(marker)
                if (marker[0] != 0x52.toByte() || marker[1] != 0x47.toByte() || marker[2] != 0x45.toByte()) return null

                CapabilityMessage(
                    protocolVersion = protocolVersion,
                    codecVersion = codecVersion,
                    deviceId = String(devId, Charsets.UTF_8),
                    languages = languages
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}