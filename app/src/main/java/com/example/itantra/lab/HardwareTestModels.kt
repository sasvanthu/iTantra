package com.example.itantra.lab

import com.example.itantra.codec.Language

/**
 * Radio choices for a single mesh edge. SIM forced loopback for lab/testing.
 */
enum class LabRadio(val label: String) {
    WIFI("WIFI"), BLE("BLE"), SIMULATED("SIM")
}

enum class LabRole(val label: String) {
    HOST("HOST"), DEVICE("DEVICE")
}

/** Node identity blurb printed at the top of every hardware-test report. */
data class DeviceReport(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val deviceId: String,
    val codecVersion: Int,
    val protocolVersion: Int
) {
    fun headerRows(): List<Pair<String, String>> = listOf(
        "DEVICE" to "$manufacturer $model",
        "ANDROID" to androidVersion,
        "APP" to appVersion,
        "DEVICE ID" to deviceId,
        "CODEC" to "v$codecVersion",
        "PROTOCOL" to "v$protocolVersion"
    )
}

/**
 * A known message the sender and receiver phones both know, so phone B can
 * verify that whatever arrived wire actually decodes back to it exactly.
 */
data class TestCase(
    val name: String,
    val text: String,
    val language: Language
)

/**
 * Outcome of one raw wire probe. `crcPass`/`decodePass`/`deliveredOnce` stay
 * null until [HardwareTestRunner.verify] runs on the receiving phone; the
 * sender phone only ever fills the sizes, fragments and latency.
 */
data class ProbeResult(
    val transport: String,
    val role: String,
    val caseName: String,
    val text: String,
    val language: Language,
    val originalBytes: Int,
    val codecBytes: Int,
    val packetBytes: Int,
    val packetCount: Int,
    val fragments: Long,
    val fragmentMeasured: Boolean,
    val mtu: Int,
    val encodeMs: Long,
    val transportMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val retransmissions: Int,
    val packetLoss: Int,
    val duplicatesDetected: Int,
    val receivedBytes: Int,
    val crcPass: Boolean?,
    val decodePass: Boolean?,
    val deliveredOnce: Boolean?,
    val sentOk: Boolean,
    val simulated: Boolean = false,
    val detail: String = ""
) {
    val status: String
        get() = when {
            !sentOk -> "FAIL"
            crcPass == false || decodePass == false || deliveredOnce == false -> "FAIL"
            crcPass != null && decodePass != null && deliveredOnce != null -> "PASS"
            else -> "PENDING"
        }
}

/** One row of the SIZE | MTU | FRAGMENTS | RESULT | TIME sweep. */
data class SweepRow(
    val sizeBytes: Int,
    val packetCount: Int,
    val fragments: Long,
    val fragmentMeasured: Boolean,
    val mtu: Int,
    val timeMs: Long,
    val sentOk: Boolean,
    val simulated: Boolean = false
) {
    val pass: String get() = if (sentOk) "PASS" else "FAIL"
}

/** Receiver-side verdict on a delivered-payload-once probe (duplicates). */
data class DuplicateCheck(
    val payloadsDelivered: Int,
    val messagesExpected: Int,
    val duplicatesDetected: Int,
    val okay: Boolean
) {
    val pass: String get() = if (okay) "PASS" else "FAIL"
}

/** Session identity before/after a manual disconnect-reconnect cycle. */
data class ReconnectReport(
    val sessionBefore: String,
    val epochBefore: Int,
    val sessionAfter: String,
    val epochAfter: Int,
    val deliveredAfter: Boolean,
    val detail: String = ""
) {
    val freshSession: Boolean get() = sessionBefore != sessionAfter || epochBefore != epochAfter
}

/**
 * MESH NODE CONFIGURATION: a node's role in an A → B → C demo and the radio
 * spec of each edge it maintains.
 */
enum class MeshNodeRole(val label: String) {
    SOURCE("SOURCE"),
    RELAY("RELAY"),
    DESTINATION("DESTINATION")
}

data class EdgeSpec(
    val radio: LabRadio = LabRadio.BLE,
    val role: LabRole = LabRole.HOST,
    val address: String = "",
    val port: String = "9876"
) {
    fun label(): String = "${radio.label}/${if (role == LabRole.HOST) "HOST" else (if (address.isNotBlank()) address else "AUTO-SCAN")}"
}

/** A started mesh node: its edges and the engines backing each one. */
data class StartedMeshNode(
    val role: MeshNodeRole,
    val deviceId: String,
    val edgeLabels: List<String>,
    val startedAtMs: Long
)