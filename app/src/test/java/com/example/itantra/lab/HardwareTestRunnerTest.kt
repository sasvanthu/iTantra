package com.example.itantra.lab

import com.example.itantra.codec.RetroSpeechCodec
import com.example.itantra.transport.NetworkSimulator
import com.example.itantra.transport.SimulatedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM verification of the hardware-test lab harness: the exact [HardwareTestRunner]
 * that both phones run in Phase 5, exercised end-to-end over paired
 * [SimulatedTransport]s so the harness logic is proven before the radios are.
 */
class HardwareTestRunnerTest {

    private class SimPair(
        val host: SimulatedTransport,
        val device: SimulatedTransport,
        val hostSim: NetworkSimulator
    )

    private fun enginePair(): SimPair {
        val hostSim = NetworkSimulator()
        val host = SimulatedTransport(hostSim, ackTimeoutMs = 150, maxRetries = 6)
        val device = SimulatedTransport(NetworkSimulator(), ackTimeoutMs = 150, maxRetries = 6)
        host.bindPeer(device)
        return SimPair(host, device, hostSim)
    }

    private suspend fun SimPair.up() = coroutineScope {
        val results = listOf(
            async { host.startHost(0) },
            async { device.connect("peer", 0) }
        ).awaitAll()
        assertTrue("host handshake", results[0])
        assertTrue("device handshake", results[1])
    }

    /** One Phone-A-sends / Phone-B-verifies round trip over a live pair. */
    private suspend fun roundTrip(
        pair: SimPair,
        runner: HardwareTestRunner,
        scope: CoroutineScope,
        case: TestCase
    ): Pair<ProbeResult, ProbeResult> {
        val (job, received) = runner.collector(pair.device, scope)
        val probe = runner.probeSend(pair.host, case, "BLE", "HOST", mtu = 247)
        runner.awaitSize(received, 1)
        val verdict = runner.verify(pair.device, received[0], case, "BLE", "DEVICE")
        job.cancel()
        return probe to verdict
    }

    @Test
    fun `raw probe round trips BLE TEST MESSAGE exactly`() = runBlocking {
        val pair = enginePair(); pair.up()
        val runner = HardwareTestRunner(RetroSpeechCodec())
        val (probe, verdict) = roundTrip(pair, runner, this, HardwareTestSuite.BLE_RAW)

        assertTrue("sender sends", probe.sentOk)
        assertEquals("same case decoded", probe.caseName, verdict.caseName)
        assertEquals("received payload is the codec bytes", probe.codecBytes, verdict.receivedBytes)
        assertTrue("crc", verdict.crcPass == true)
        assertTrue("decode exact", verdict.decodePass == true)
        assertEquals("pass", "PASS", verdict.status)
        // Non-BLE probe estimates fragments at the given MTU.
        assertTrue("fragments estimated at mtu 247", probe.fragments > 0)
        assertTrue(!probe.fragmentMeasured)
    }

    @Test
    fun `all unicode cases survive the wire byte-for-byte`() = runBlocking {
        val pair = enginePair(); pair.up()
        val runner = HardwareTestRunner(RetroSpeechCodec())
        for (case in HardwareTestSuite.UNICODE) {
            val (probe, verdict) = roundTrip(pair, runner, this, case)
            assertTrue("${case.name} sends", probe.sentOk)
            assertTrue("${case.name} crc", verdict.crcPass == true)
            assertTrue("${case.name} decode exact", verdict.decodePass == true)
            assertEquals("${case.name} pass", "PASS", verdict.status)
        }
    }

    @Test
    fun `size sweep produces eight passing rows with fragments`() = runBlocking {
        val pair = enginePair(); pair.up()
        val runner = HardwareTestRunner(RetroSpeechCodec())
        val rows = runner.runSweep(pair.host, mtu = 247)
        assertEquals(HardwareTestSuite.SWEEP_SIZES.size, rows.size)
        rows.zip(HardwareTestSuite.SWEEP_SIZES).forEach { (row, size) ->
            assertEquals("size $size", size, row.sizeBytes)
            assertEquals("size $size pass", "PASS", row.pass)
            assertTrue("size $size fragments", row.fragments > 0)
        }
    }

    @Test
    fun `duplicate injection delivers exactly once`() = runBlocking {
        val pair = enginePair(); pair.up()
        val runner = HardwareTestRunner(RetroSpeechCodec())
        val check = runner.duplicateExperiment(
            sender = pair.host,
            receiver = pair.device,
            senderSim = pair.hostSim,
            codec = RetroSpeechCodec(),
            t = HardwareTestSuite.BLE_RAW,
            scope = this
        )
        assertEquals("one application delivery", 1, check.payloadsDelivered)
        assertTrue("duplicates detected at the receiver", check.duplicatesDetected >= 1)
        assertTrue("check passes", check.okay)
        assertEquals("PASS", check.pass)
    }

    @Test
    fun `disconnect then reconnect starts a fresh epoch and delivers again`() = runBlocking {
        val pair = enginePair(); pair.up()
        val runner = HardwareTestRunner(RetroSpeechCodec())

        val (_, first) = roundTrip(pair, runner, this, HardwareTestSuite.UNICODE[0])
        assertTrue("first delivery decodes", first.decodePass == true)

        val beforeEpoch = pair.device.session.value!!.epoch

        pair.host.disconnect()
        pair.device.disconnect()
        coroutineScope {
            val results = listOf(
                async { pair.host.startHost(0) },
                async { pair.device.connect("peer", 0) }
            ).awaitAll()
            assertTrue(results[0] && results[1])
        }

        val afterEpoch = pair.device.session.value!!.epoch
        assertTrue("epoch must advance on reconnect", afterEpoch != beforeEpoch)

        val (_, second) = roundTrip(pair, runner, this, HardwareTestSuite.UNICODE[0])
        assertTrue("second delivery decodes", second.decodePass == true)
        assertEquals("PASS", second.status)
    }
}