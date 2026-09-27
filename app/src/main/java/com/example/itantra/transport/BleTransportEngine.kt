package com.example.itantra.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.itantra.protocol.BleChunkReader
import com.example.itantra.protocol.BleChunkResult
import com.example.itantra.protocol.BleChunkWriter
import com.example.itantra.protocol.BleLinkCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE (Bluetooth LE / GATT) RETRO link for direct two-phone communication
 * with no Wi-Fi or infrastructure.
 *
 * Roles:
 *  - HOST  = GATT server: advertises [SERVICE_UUID], accepts one central,
 *            receives frames via the RX characteristic, sends frames as
 *            notifications on the TX characteristic.
 *  - DEVICE = GATT client: optionally scans for an advertiser (or connects to
 *            a given MAC), discovers the service, subscribes to TX
 *            notifications, negotiates the ATT MTU, and writes frames to RX
 *            with response (per-ATT flow control).
 *
 * BLE has no stream: every ATT atom is bounded by the negotiated MTU (20 bytes
 * default, up to ~512). [BleChunkWriter]/[BleChunkReader] slice the FrameCodec
 * byte stream into MTU-sized chunks with a sequence header and reassemble it.
 * A chunk gap (rare controller notification overrun) is surfaced as packet
 * loss and healed by the FrameCodec magic re-synchronization plus the
 * session-level ACK/NACK reliability inherited from [BaseTransportEngine].
 *
 * The GATT layer itself is Android-bound, so it is verified on device; the
 * chunk/framing adaptation is pure Kotlin and covered by JVM unit tests.
 */
class BleTransportEngine(
    private val context: Context,
    networkSimulator: NetworkSimulator,
    ackTimeoutMs: Long = 2000L,
    maxRetries: Int = 3,
    private val requestMtuOverride: Int = 512,
    private val chunkPacingMs: Long = 0L,
    private val connectTimeoutMs: Long = 45_000L
) : BaseTransportEngine(
    transportType = TransportType.BLUETOOTH,
    engineName = "BLUETOOTH",
    networkSimulator = networkSimulator,
    ackTimeoutMs = ackTimeoutMs,
    maxRetries = maxRetries
) {

    companion object {
        private const val SERVICE_UUID_STR = "6c12e1b0-1f9a-4f5e-bf2c-e8c8d8f9a0b4"
        private const val RX_CHAR_UUID_STR = "6c12e1b1-1f9a-4f5e-bf2c-e8c8d8f9a0b4"
        private const val TX_CHAR_UUID_STR = "6c12e1b2-1f9a-4f5e-bf2c-e8c8d8f9a0b4"
        private const val CCCD_UUID_STR = "00002902-0000-1000-8000-00805f9b34fb"

        val SERVICE_UUID: UUID = UUID.fromString(SERVICE_UUID_STR)
        private val RX_CHAR_UUID: UUID = UUID.fromString(RX_CHAR_UUID_STR)
        private val TX_CHAR_UUID: UUID = UUID.fromString(TX_CHAR_UUID_STR)
        private val CCCD_UUID: UUID = UUID.fromString(CCCD_UUID_STR)

        const val DEFAULT_ATT_MTU = 23

        private val NOTIFICATION_ENABLE = byteArrayOf(0x01, 0x00)
    }

    // ------------------------------------------------------------------
    // Bluetooth handles (single active session)
    // ------------------------------------------------------------------

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null
    private var vendorAdvertiser: BluetoothLeAdvertiser? = null
    private var leScanner: BluetoothLeScanner? = null
    private var advertising = false
    private var scanning = false

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null

    // MTU is negotiated at runtime; chunk size follows the most conservative
    // value seen so far. All chunk state is guarded by [chunkLock] because
    // GATT callbacks and the write loop touch it concurrently.
    @Volatile private var mtuBytes = DEFAULT_ATT_MTU
    private val chunkLock = Any()
    private var chunkWriter: BleChunkWriter? = null
    private var chunkWriterPayload = 0
    private val chunkReader = BleChunkReader()

    private val connectAwait = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val scanAwait = AtomicReference<CompletableDeferred<BluetoothDevice>?>(null)
    private val writeAwait = AtomicReference<CompletableDeferred<Int>?>(null)
    private val serviceAddAwait = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val writeMutex = Mutex()

    // ------------------------------------------------------------------
    // Hardware-test instrumentation (additive; no behavior change)
    // ------------------------------------------------------------------

    private val _bleCheck = MutableStateFlow(BleLinkCheck())
    /** Granular BLE milestone checklist for the Hardware Test screen. */
    val bleCheck: StateFlow<BleLinkCheck> = _bleCheck.asStateFlow()

    private fun mark(check: BleLinkCheck = _bleCheck.value, block: BleLinkCheck.() -> BleLinkCheck) {
        _bleCheck.value = check.block()
    }

    /** Cumulative ATT atoms actually written (callbacks/frames), for fragment accounting. */
    @Volatile private var chunksSentTotal = 0L

    fun chunksSent(): Long = chunksSentTotal

    /** Current negotiated ATT MTU (23 until [onMtuChanged]). */
    fun negotiatedMtu(): Int = mtuBytes

    /**
     * Hardware-test hook: when > 0, every Nth inbound ATT atom arriving over
     * the link is deliberately dropped. The chunk sequence gap this creates is
     * detected by [BleChunkReader] and surfaces as packet loss + frame-level
     * recovery, exactly like a real notification overrun. MUST be labeled
     * "SIMULATED LOSS" wherever it is reported.
     */
    @Volatile var chunkDropEveryNth: Int = 0

    private var incomingChunkCounter = 0L
    @Volatile private var simulatedChunksDroppedTotal = 0L

    fun simulatedChunksDropped(): Long = simulatedChunksDroppedTotal

    private fun negotiatedChunkPayload(): Int {
        val maxPayload = mtuBytes - 3 - BleLinkCodec.HEADER_SIZE
        return maxOf(1, maxPayload)
    }

    /** Reset chunk state for a fresh session; MTU is re-negotiated per link. */
    private fun resetCodecs() {
        synchronized(chunkLock) {
            chunkWriter = null
            chunkWriterPayload = 0
            chunkReader.reset()
        }
        mtuBytes = DEFAULT_ATT_MTU
        _bleCheck.value = BleLinkCheck()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** BLUETOOTH_CONNECT (API 31+) or legacy BLUETOOTH — safe-gated at call sites. */
    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }

    private fun blePermissionsOk(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH) &&
                hasPermission(Manifest.permission.BLUETOOTH_ADMIN) &&
                (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                 hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // ------------------------------------------------------------------
    // Subclass hooks
    // ------------------------------------------------------------------

    override suspend fun openLinkAsHost(port: Int): Boolean {
        resetCodecs()
        val bt = adapter
        if (bt == null) {
            setEngineError("Bluetooth adapter not available on this device")
            return false
        }
        if (!bt.isEnabled) {
            setEngineError("Bluetooth is turned off. Please turn on Bluetooth.")
            return false
        }
        if (!blePermissionsOk()) {
            setEngineError("Bluetooth permissions not granted (Advertise/Connect/Scan)")
            return false
        }

        val hostReady = CompletableDeferred<Boolean>()
        connectAwait.set(hostReady)

        val server = try {
            bluetoothManager?.openGattServer(context, serverCallback)
        } catch (e: SecurityException) {
            setEngineError("Security exception opening GATT server: ${e.message}")
            null
        } ?: run {
            connectAwait.set(null)
            return false
        }
        gattServer = server

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        rxCharacteristic = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        txCharacteristic = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        txCharacteristic?.addDescriptor(
            BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )
        service.addCharacteristic(rxCharacteristic)
        service.addCharacteristic(txCharacteristic)
        val added = CompletableDeferred<Boolean>()
        serviceAddAwait.set(added)
        try {
            server.addService(service)
        } catch (e: SecurityException) {
            setEngineError("Security exception adding BLE service: ${e.message}")
            serviceAddAwait.set(null)
            connectAwait.set(null)
            closeGattServer()
            return false
        }
        val serviceOk = withTimeoutOrNull(5000L) { added.await() } ?: false
        serviceAddAwait.set(null)
        if (!serviceOk) {
            setEngineError("Timed out or failed adding GATT service")
            connectAwait.set(null)
            closeGattServer()
            return false
        }
        mark { copy(serviceReady = true, characteristicsReady = true) }

        val advertiser = try {
            bt.bluetoothLeAdvertiser
        } catch (e: SecurityException) {
            setEngineError("Security exception getting BLE advertiser: ${e.message}")
            null
        } ?: run {
            setEngineError("BLE advertising unsupported on this hardware")
            connectAwait.set(null)
            closeGattServer()
            return false
        }
        vendorAdvertiser = advertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // Do NOT put device name in primary advertisement to avoid ADVERTISE_FAILED_DATA_TOO_LARGE (31-byte cap)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            advertising = true
            mark { copy(advertising = true) }
        } catch (e: Exception) {
            advertising = false
            setEngineError("Failed to start advertising: ${e.message}")
        }
        if (!advertising) {
            connectAwait.set(null)
            closeGattServer()
            return false
        }

        // Wait until a central has connected AND is ready to receive
        // notifications (CCCD subscribed) so the first CAPABILITY frame is not
        // dropped on the wire.
        val ok = withTimeoutOrNull(connectTimeoutMs) { hostReady.await() } ?: false
        connectAwait.set(null)
        if (!ok) {
            if (getLastError() == null) setEngineError("No peer subscribed to BLE host within timeout")
            stopAdvertising()
            closeGattServer()
        } else {
            // Allow physical GATT state machine to settle before write/handshake loop starts
            delay(150L)
        }
        return ok
    }

    override suspend fun openLinkAsClient(host: String, port: Int): Boolean {
        resetCodecs()
        val bt = adapter
        if (bt == null) {
            setEngineError("Bluetooth adapter not available on this device")
            return false
        }
        if (!bt.isEnabled) {
            setEngineError("Bluetooth is turned off. Please turn on Bluetooth.")
            return false
        }
        if (!blePermissionsOk()) {
            setEngineError("Bluetooth permissions not granted")
            return false
        }

        val device = resolveTarget(bt, host) ?: run {
            if (getLastError() == null) setEngineError("Could not find BLE host peer")
            return false
        }

        val ready = CompletableDeferred<Boolean>()
        connectAwait.set(ready)

        val gatt = try {
            device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            setEngineError("Security exception connecting GATT: ${e.message}")
            null
        } ?: run {
            connectAwait.set(null)
            return false
        }
        gattClient = gatt

        val ok = withTimeoutOrNull(connectTimeoutMs) { ready.await() } ?: false
        connectAwait.set(null)
        if (!ok) {
            if (getLastError() == null) setEngineError("GATT connection or notification setup timed out")
            closeGattClient()
        } else {
            // Allow physical GATT state machine to settle before write/handshake loop starts
            delay(150L)
        }
        return ok
    }

    /** Directly target a MAC, or scan for an advertiser of [SERVICE_UUID]. */
    private suspend fun resolveTarget(bt: BluetoothAdapter, host: String): BluetoothDevice? {
        val trimmed = host.trim()
        if (trimmed.isNotBlank()) {
            if (BluetoothAdapter.checkBluetoothAddress(trimmed)) {
                return try {
                    bt.getRemoteDevice(trimmed)
                } catch (e: Exception) {
                    null
                }
            }
        }
        val scanner = try {
            bt.bluetoothLeScanner
        } catch (e: SecurityException) {
            setEngineError("Security exception accessing BLE scanner: ${e.message}")
            null
        } ?: return null
        leScanner = scanner

        setStatus(ConnectionStatus.DISCOVERING)

        val found = CompletableDeferred<BluetoothDevice>()
        scanAwait.set(found)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            // Software filtering in scanCallback ensures compatibility with diverse OEM Bluetooth stacks
            scanner.startScan(emptyList(), settings, scanCallback)
            scanning = true
            mark { copy(scanning = true) }
        } catch (e: SecurityException) {
            scanning = false
            setEngineError("Security exception starting BLE scan: ${e.message}")
        }
        if (!scanning) {
            scanAwait.set(null)
            return null
        }
        val device = withTimeoutOrNull(connectTimeoutMs) { found.await() }
        scanAwait.set(null)
        stopScanning()
        if (device == null && getLastError() == null) {
            setEngineError("No iTantra peer found scanning for service UUID")
        }
        return device
    }

    override suspend fun writeRawFrame(frame: ByteArray) {
        if (isHosting()) {
            notifyServerChunks(frame)
        } else {
            writeClientChunks(frame)
        }
    }

    private fun isHosting(): Boolean = connectedDevice != null && gattServer != null

    /** HOST -> DEVICE: fire-and-forget notifications with small pacing. */
    private suspend fun notifyServerChunks(frame: ByteArray) {
        val server = gattServer ?: return
        val device = connectedDevice ?: return
        val char = txCharacteristic ?: return
        val chunks = splitOutgoing(frame)
        try {
            for ((i, chunk) in chunks.withIndex()) {
                val effectivePacing = if (mtuBytes > DEFAULT_ATT_MTU) 10L else chunkPacingMs
                if (effectivePacing > 0 && i > 0) delay(effectivePacing)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val res = server.notifyCharacteristicChanged(device, char, false, chunk)
                    android.util.Log.i("BleTransportEngine", "[BLE] [NOTIFY] Server notify len=${chunk.size}, res=$res")
                } else {
                    @Suppress("DEPRECATION")
                    char.value = chunk
                    @Suppress("DEPRECATION")
                    val res = server.notifyCharacteristicChanged(device, char, false)
                    android.util.Log.i("BleTransportEngine", "[BLE] [NOTIFY] Server legacy notify len=${chunk.size}, res=$res")
                }
                chunksSentTotal++
            }
        } catch (e: SecurityException) {
            android.util.Log.e("BleTransportEngine", "[BLE] [NOTIFY] SecurityException: ${e.message}")
        }
    }

    /** DEVICE -> HOST: high-throughput write-without-response with automatic retry. */
    private suspend fun writeClientChunks(frame: ByteArray) {
        val gatt = gattClient ?: return
        val char = rxCharacteristic ?: return
        val chunks = splitOutgoing(frame)
        val supportsNoResponse = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        writeMutex.withLock {
            for ((idx, chunk) in chunks.withIndex()) {
                if (idx > 0) delay(10L)
                var status = if (supportsNoResponse) {
                    writeWithoutResponse(gatt, char, chunk)
                } else {
                    writeAndAwait(gatt, char, chunk)
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Retry with small backoff
                    delay(50L)
                    status = writeAndAwait(gatt, char, chunk)
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    android.util.Log.e("BleTransportEngine", "[BLE] [WRITE] Client write failed permanently with status $status")
                    baseScope.launch { finishSession("BLE write failed (status $status)") }
                    return
                }
                chunksSentTotal++
            }
        }
    }

    private fun writeWithoutResponse(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                android.util.Log.i("BleTransportEngine", "[BLE] [WRITE] writeWithoutResponse (len=${chunk.size}): res=$res")
                return if (res == 0) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            } else {
                @Suppress("DEPRECATION")
                char.value = chunk
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                val res = gatt.writeCharacteristic(char)
                android.util.Log.i("BleTransportEngine", "[BLE] [WRITE] legacy writeWithoutResponse (len=${chunk.size}): res=$res")
                return if (res) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }
        } catch (e: SecurityException) {
            android.util.Log.e("BleTransportEngine", "[BLE] [WRITE] SecurityException: ${e.message}")
            return BluetoothGatt.GATT_FAILURE
        }
    }

    private suspend fun writeAndAwait(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Int {
        val awaiter = CompletableDeferred<Int>()
        writeAwait.set(awaiter)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (res != 0) { // 0 = BluetoothStatusCodes.SUCCESS
                    writeAwait.set(null)
                    return BluetoothGatt.GATT_FAILURE
                }
            } else {
                @Suppress("DEPRECATION")
                char.value = chunk
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                val res = gatt.writeCharacteristic(char)
                if (!res) {
                    writeAwait.set(null)
                    return BluetoothGatt.GATT_FAILURE
                }
            }
        } catch (e: SecurityException) {
            writeAwait.set(null)
            return BluetoothGatt.GATT_FAILURE
        }
        return withTimeoutOrNull(ackTimeoutMs * 2) { awaiter.await() } ?: BluetoothGatt.GATT_FAILURE
    }

    override suspend fun tearDown() {
        stopAdvertising()
        stopScanning()
        closeGattClient()
        closeGattServer()
    }

    private fun stopAdvertising() {
        val a = vendorAdvertiser ?: return
        if (!advertising) return
        try {
            a.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            // nothing to clean
        }
        advertising = false
        vendorAdvertiser = null
    }

    private fun stopScanning() {
        val s = leScanner ?: return
        if (!scanning) return
        try {
            s.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // nothing to clean
        }
        scanning = false
        leScanner = null
    }

    // Guarded by hasConnectPermission(); lint cannot trace checks through the
    // helper, so the annotation re-declares the runtime BLUETOOTH_CONNECT guard.
    @SuppressLint("MissingPermission")
    private fun closeGattClient() {
        val g = gattClient ?: return
        if (!hasConnectPermission()) {
            gattClient = null
            rxCharacteristic = null
            txCharacteristic = null
            return
        }
        try {
            g.disconnect()
        } catch (e: SecurityException) {
            // ignore
        }
        try {
            g.close()
        } catch (e: Exception) {
            // ignore
        }
        gattClient = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGattServer() {
        val s = gattServer ?: return
        if (!hasConnectPermission()) {
            gattServer = null
            connectedDevice = null
            rxCharacteristic = null
            txCharacteristic = null
            return
        }
        try {
            s.clearServices()
        } catch (e: Exception) {
            // ignore
        }
        try {
            s.close()
        } catch (e: Exception) {
            // ignore
        }
        gattServer = null
        connectedDevice = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    override fun pollLocalAddress(): String =
        if (hasConnectPermission()) adapter?.name ?: "" else ""

    override fun getLinkName(): String {
        val name = pollLocalAddress()
        return if (name.isNotEmpty()) "BLUETOOTH // $name" else "BLUETOOTH"
    }

    // ------------------------------------------------------------------
    // Chunk adaptation (shared by both roles)
    // ------------------------------------------------------------------

    /**
     * Splits a frame into ATT atoms. Recreates the writer when the negotiated
     * MTU grows so larger payloads kick in automatically; the reader is only
     * reset at session start so an inbound mid-flight stream is never broken.
     */
    private fun splitOutgoing(frame: ByteArray): List<ByteArray> = synchronized(chunkLock) {
        val payload = negotiatedChunkPayload()
        if (chunkWriter == null || chunkWriterPayload != payload) {
            chunkWriter = BleChunkWriter(payload)
            chunkWriterPayload = payload
        }
        chunkWriter!!.split(frame)
    }

    private fun handleIncomingChunk(chunk: ByteArray) {
        if (!isSessionActive()) return
        val everyNth = chunkDropEveryNth
        if (everyNth > 0) {
            incomingChunkCounter++
            if (incomingChunkCounter % everyNth.toLong() == 0L) {
                // SIMULATED LOSS: drop one ATT atom on purpose. The seq gap this
                // leaves is reported by the reader exactly like a real overrun.
                simulatedChunksDroppedTotal++
                return
            }
        }
        val stream: ByteArray? = synchronized(chunkLock) {
            when (val r = chunkReader.feed(chunk)) {
                is BleChunkResult.Gap -> {
                    reportLinkPacketLoss(maxOf(1, r.lostChunks))
                    null
                }
                is BleChunkResult.Stream -> r.bytes
            }
        }
        stream?.let { feedIncoming(it) }
    }

    private fun updateMtu(mtu: Int) {
        if (mtu >= DEFAULT_ATT_MTU) {
            mtuBytes = maxOf(mtuBytes, mtu)
            android.util.Log.i("BleTransportEngine", "[BLE] [MTU] Negotiated MTU: $mtuBytes bytes (event MTU: $mtu)")
        }
    }

    // ------------------------------------------------------------------
    // Advertiser / scanner callbacks
    // ------------------------------------------------------------------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            android.util.Log.i("BleTransportEngine", "[BLE] [ADV] onStartSuccess: mode=${settingsInEffect?.mode}, txPower=${settingsInEffect?.txPowerLevel}")
            advertising = true
            mark { copy(advertising = true) }
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            mark { copy(advertising = false) }
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "advertising unsupported"
                else -> "code $errorCode"
            }
            android.util.Log.e("BleTransportEngine", "[BLE] [ADV] onStartFailure: $errorCode ($reason)")
            setEngineError("BLE advertising failed: $reason")
            connectAwait.get()?.complete(false)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val record = result.scanRecord
            val uuids = record?.serviceUuids?.map { it.uuid } ?: emptyList()
            val hasService = uuids.contains(SERVICE_UUID)
            val devName = dev.name ?: record?.deviceName ?: ""
            android.util.Log.i("BleTransportEngine", "[BLE] [SCAN] Seen: ${dev.address} name='$devName' uuids=$uuids")
            val isItantraDevice = devName.contains("iTantra", ignoreCase = true) ||
                    devName.contains("vivo", ignoreCase = true) ||
                    devName.contains("oppo", ignoreCase = true) ||
                    devName.contains("cph", ignoreCase = true) ||
                    devName.contains("T3", ignoreCase = true)

            if (hasService || isItantraDevice) {
                android.util.Log.i("BleTransportEngine", "[BLE] [SCAN] Found matching peer: ${dev.address} ($devName), RSSI=${result.rssi}")
                val await = scanAwait.get()
                if (await != null && !await.isCompleted) {
                    await.complete(result.device)
                }
                stopScanning()
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            val matched = results?.firstOrNull { res ->
                val uuids = res.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                val devName = res.device.name ?: res.scanRecord?.deviceName ?: ""
                uuids.contains(SERVICE_UUID) ||
                        devName.contains("iTantra", ignoreCase = true) ||
                        devName.contains("vivo", ignoreCase = true) ||
                        devName.contains("oppo", ignoreCase = true) ||
                        devName.contains("cph", ignoreCase = true) ||
                        devName.contains("T3", ignoreCase = true)
            } ?: return
            android.util.Log.i("BleTransportEngine", "[BLE] [SCAN] Batch found peer: ${matched.device.address}")
            val await = scanAwait.get()
            if (await != null && !await.isCompleted) {
                await.complete(matched.device)
            }
            stopScanning()
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mark { copy(scanning = false) }
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "unsupported"
                else -> "code $errorCode"
            }
            android.util.Log.e("BleTransportEngine", "[BLE] [SCAN] onScanFailed: $errorCode ($reason)")
            setEngineError("BLE scan failed: $reason")
            scanAwait.getAndSet(null)?.cancel()
        }
    }

    // ------------------------------------------------------------------
    // HOST (GATT server) callbacks
    // ------------------------------------------------------------------

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (advertising) stopAdvertising()
                connectedDevice = device
                mark { copy(connected = true) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        gattServer?.setPreferredPhy(
                            device,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED
                        )
                    } catch (_: SecurityException) {}
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mark { copy(connected = false) }
                if (connectedDevice == device) {
                    connectedDevice = null
                    connectAwait.get()?.complete(false)
                    if (isSessionActive()) {
                        baseScope.launch { finishSession("peer disconnected") }
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (e: SecurityException) {
                    return
                }
            }
            if (characteristic.uuid == RX_CHAR_UUID) {
                // First RX write confirms the central is communicating; unblock HOST wait
                connectAwait.get()?.let { hostReady ->
                    if (!hostReady.isCompleted && connectedDevice != null) hostReady.complete(true)
                }
                handleIncomingChunk(value)
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            android.util.Log.i("BleTransportEngine", "[BLE] [GATT] Server onServiceAdded: status=$status, uuid=${service.uuid}")
            serviceAddAwait.get()?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            android.util.Log.i("BleTransportEngine", "[BLE] [GATT] Server onDescriptorWriteRequest: uuid=${descriptor.uuid}, len=${value.size}")
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (e: SecurityException) {
                    return
                }
            }
            // Central subscribed to TX notifications -> ready to receive frames.
            if (descriptor.uuid == CCCD_UUID) {
                mark { copy(notificationsEnabled = true) }
                connectAwait.get()?.let { hostReady ->
                    if (!hostReady.isCompleted && connectedDevice != null) hostReady.complete(true)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            updateMtu(mtu)
            mark { copy(mtuNegotiated = true, mtu = mtuBytes) }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS && isSessionActive()) {
                reportLinkPacketLoss(1)
            }
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            val txStr = if (txPhy == BluetoothDevice.PHY_LE_2M) "2M" else if (txPhy == BluetoothDevice.PHY_LE_1M) "1M" else "CODED($txPhy)"
            val rxStr = if (rxPhy == BluetoothDevice.PHY_LE_2M) "2M" else if (rxPhy == BluetoothDevice.PHY_LE_1M) "1M" else "CODED($rxPhy)"
            android.util.Log.i("BleTransportEngine", "[BLE] [PHY] Server onPhyUpdate: status=$status, TX PHY=$txStr, RX PHY=$rxStr")
        }

        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            val txStr = if (txPhy == BluetoothDevice.PHY_LE_2M) "2M" else if (txPhy == BluetoothDevice.PHY_LE_1M) "1M" else "CODED($txPhy)"
            val rxStr = if (rxPhy == BluetoothDevice.PHY_LE_2M) "2M" else if (rxPhy == BluetoothDevice.PHY_LE_1M) "1M" else "CODED($rxPhy)"
            android.util.Log.i("BleTransportEngine", "[BLE] [PHY] Server onPhyRead: status=$status, TX PHY=$txStr, RX PHY=$rxStr")
        }
    }

    // ------------------------------------------------------------------
    // DEVICE (GATT client) callbacks
    // ------------------------------------------------------------------

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setEngineError("GATT connection failed (status $status)")
                connectAwait.get()?.complete(false)
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mark { copy(connected = true) }
                try {
                    // Low latency voice: request high connection priority (7.5ms - 11.25ms interval)
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

                    // 2M PHY for double bit-rate on Android 8.0+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            gatt.setPreferredPhy(
                                BluetoothDevice.PHY_LE_2M_MASK,
                                BluetoothDevice.PHY_LE_2M_MASK,
                                BluetoothDevice.PHY_OPTION_NO_PREFERRED
                            )
                        } catch (_: SecurityException) {}
                    }

                    // Request MTU first
                    val requested = gatt.requestMtu(requestMtuOverride)
                    if (!requested) {
                        // Fall back to service discovery immediately if MTU request rejected
                        gatt.discoverServices()
                    }
                } catch (e: SecurityException) {
                    setEngineError("Security exception requesting MTU: ${e.message}")
                    connectAwait.get()?.complete(false)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mark { copy(connected = false) }
                connectAwait.get()?.complete(false)
                writeAwait.get()?.complete(BluetoothGatt.GATT_FAILURE)
                if (isSessionActive()) {
                    baseScope.launch { finishSession("peer disconnected") }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            updateMtu(if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_ATT_MTU)
            mark { copy(mtuNegotiated = true, mtu = mtuBytes) }
            // Step 2: Now that MTU is negotiated, discover services sequentially!
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                setEngineError("Security exception discovering services: ${e.message}")
                connectAwait.get()?.complete(false)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val sUuids = gatt.services.map { it.uuid }
            android.util.Log.i("BleTransportEngine", "[BLE] [GATT] Client onServicesDiscovered: status=$status, services=$sUuids")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setEngineError("BLE Service discovery failed (status $status)")
                connectAwait.get()?.complete(false)
                return
            }
            val service = gatt.getService(SERVICE_UUID) ?: run {
                android.util.Log.e("BleTransportEngine", "[BLE] [GATT] Target $SERVICE_UUID not found in discovered services $sUuids")
                setEngineError("iTantra BLE Service not found on peer")
                connectAwait.get()?.complete(false)
                return
            }
            val rx = service.getCharacteristic(RX_CHAR_UUID)
            val tx = service.getCharacteristic(TX_CHAR_UUID)
            if (rx == null || tx == null) {
                setEngineError("iTantra BLE characteristics not found on peer")
                connectAwait.get()?.complete(false)
                return
            }
            rxCharacteristic = rx
            txCharacteristic = tx
            mark { copy(serviceReady = true, characteristicsReady = true) }
            try {
                // Re-assert high connection priority after service discovery
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    setEngineError("CCCD descriptor not found on peer")
                    connectAwait.get()?.complete(false)
                    return
                }
                android.util.Log.i("BleTransportEngine", "[BLE] [GATT] Client writing CCCD enable descriptor")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, NOTIFICATION_ENABLE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = NOTIFICATION_ENABLE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            } catch (e: SecurityException) {
                setEngineError("Security exception writing descriptor: ${e.message}")
                connectAwait.get()?.complete(false)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            android.util.Log.i("BleTransportEngine", "[BLE] [GATT] Client onDescriptorWrite: uuid=${descriptor.uuid}, status=$status")
            if (descriptor.uuid == CCCD_UUID) {
                val ok = status == BluetoothGatt.GATT_SUCCESS
                mark {
                    copy(notificationsEnabled = ok)
                }
                if (!ok) {
                    setEngineError("Failed to enable BLE notifications (status $status)")
                }
                connectAwait.get()?.let { ready ->
                    ready.complete(ok)
                }
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { handleIncomingChunk(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingChunk(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeAwait.get()?.complete(status)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            val txStr = if (txPhy == BluetoothDevice.PHY_LE_2M) "2M" else if (txPhy == BluetoothDevice.PHY_LE_1M) "1M" else "CODED($txPhy)"
            val rxStr = if (rxPhy == BluetoothDevice.PHY_LE_2M) "2M" else if (rxPhy == BluetoothDevice.PHY_LE_1M) "1M" else "CODED($rxPhy)"
            android.util.Log.i("BleTransportEngine", "[BLE] [PHY] Client onPhyUpdate: status=$status, TX PHY=$txStr, RX PHY=$rxStr")
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            val txStr = if (txPhy == BluetoothDevice.PHY_LE_2M) "2M" else if (txPhy == BluetoothDevice.PHY_LE_1M) "1M" else "CODED($txPhy)"
            val rxStr = if (rxPhy == BluetoothDevice.PHY_LE_2M) "2M" else if (rxPhy == BluetoothDevice.PHY_LE_1M) "1M" else "CODED($rxPhy)"
            android.util.Log.i("BleTransportEngine", "[BLE] [PHY] Client onPhyRead: status=$status, TX PHY=$txStr, RX PHY=$rxStr")
        }
    }
}