package com.example.itantra.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Event and Simulated GPS logging for iTantra: Setu.
 *
 * Records structured communication events and simulated campus GPS records.
 * Prominently tagged as "SIMULATED LOCATION" to maintain technical accuracy.
 */
object CommunicationEventLog {

    data class CampusLocation(
        val nodeId: String,
        val label: String,
        val latitude: Double,
        val longitude: Double,
        val isSimulated: Boolean = true
    )

    // Three campus demonstration nodes
    val NODE_A_LOCATION = CampusLocation(
        nodeId = "PHONE_A",
        label = "Library Complex (Node A)",
        latitude = 13.01024,
        longitude = 80.23548
    )

    val NODE_B_LOCATION = CampusLocation(
        nodeId = "PHONE_B",
        label = "Tech Quad Relay (Node B)",
        latitude = 13.01182,
        longitude = 80.23705
    )

    val NODE_C_LOCATION = CampusLocation(
        nodeId = "PHONE_C",
        label = "Crisis Operations (Node C)",
        latitude = 13.01351,
        longitude = 80.23882
    )

    enum class EventType {
        MESSAGE_CREATED,
        PACKET_SENT,
        PACKET_RECEIVED,
        AES_GCM_VERIFIED,
        PACKET_FORWARDED,
        MESSAGE_DELIVERED,
        DUPLICATE_IGNORED,
        INTEGRITY_FAILURE,
        LINK_CONNECTED,
        LINK_DISCONNECTED
    }

    data class EventRecord(
        val id: Long = System.nanoTime(),
        val timestamp: Long = System.currentTimeMillis(),
        val device: String,
        val destinationDevice: String? = null,
        val eventType: EventType,
        val transport: String,
        val transmissionId: String,
        val messageId: String,
        val packetId: String,
        val latitude: Double,
        val longitude: Double,
        val locationLabel: String,
        val detail: String,
        val isSimulatedLocation: Boolean = true
    ) {
        fun formattedTime(): String {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            return sdf.format(Date(timestamp))
        }

        fun gpsJsonRecord(): String {
            return """
            {
              "device": "$device",
              "destination": "${destinationDevice ?: "N/A"}",
              "latitude": $latitude,
              "longitude": $longitude,
              "location_status": "SIMULATED_LOCATION",
              "timestamp": "$timestamp",
              "event": "$eventType",
              "transport": "$transport",
              "txId": "$transmissionId",
              "msgId": "$messageId",
              "pktId": "$packetId"
            }
            """.trimIndent()
        }
    }

    private val _events = MutableStateFlow<List<EventRecord>>(emptyList())
    val events: StateFlow<List<EventRecord>> = _events.asStateFlow()

    fun logEvent(
        device: String,
        eventType: EventType,
        transport: String,
        transmissionId: String,
        messageId: String,
        packetId: String,
        detail: String,
        destinationDevice: String? = null,
        location: CampusLocation = resolveLocation(device)
    ) {
        val record = EventRecord(
            device = device,
            destinationDevice = destinationDevice,
            eventType = eventType,
            transport = transport,
            transmissionId = transmissionId,
            messageId = messageId,
            packetId = packetId,
            latitude = location.latitude,
            longitude = location.longitude,
            locationLabel = location.label,
            detail = detail
        )

        _events.update { current ->
            (listOf(record) + current).take(200)
        }
    }

    fun clear() {
        _events.value = emptyList()
    }

    fun resolveLocation(deviceId: String): CampusLocation {
        return when {
            deviceId.contains("A", ignoreCase = true) -> NODE_A_LOCATION
            deviceId.contains("B", ignoreCase = true) -> NODE_B_LOCATION
            deviceId.contains("C", ignoreCase = true) -> NODE_C_LOCATION
            else -> NODE_A_LOCATION
        }
    }
}
