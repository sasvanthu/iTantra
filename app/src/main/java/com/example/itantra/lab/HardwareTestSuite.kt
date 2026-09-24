package com.example.itantra.lab

import com.example.itantra.codec.Language

/**
 * The canonical Phase-5 hardware test cases. Both the sending and the
 * receiving phone share this suite so the receiver can verify that what
 * arrived over the radio decodes back to exactly the expected text.
 */
object HardwareTestSuite {

    val BLE_RAW: TestCase =
        TestCase("BLE RAW", "BLE TEST MESSAGE", Language.ENGLISH)

    /** Spec #5: run these payload sizes over the negotiated MTU. */
    val SWEEP_SIZES: List<Int> = listOf(10, 20, 50, 100, 200, 500, 1000, 2000)

    /** Spec #6: unicode must survive transport byte-for-byte. */
    val UNICODE: List<TestCase> = listOf(
        TestCase("EN", "I need help.", Language.ENGLISH),
        TestCase("TA", "எனக்கு உதவி தேவை.", Language.TAMIL),
        TestCase("HI", "मुझे मदद चाहिए।", Language.HINDI)
    )

    /** Spec #12: real-language transmission runs (recorded in the report). */
    val SPEECH_PHRASES: List<TestCase> = listOf(
        TestCase("EN", "I need help near the railway station.", Language.ENGLISH),
        TestCase("TA", "எனக்கு ரயில் நிலையம் அருகில் உதவி தேவை.", Language.TAMIL),
        TestCase("HI", "मुझे रेलवे स्टेशन के पास मदद चाहिए।", Language.HINDI)
    )

    /** Spec #15: first mesh demo message. */
    const val MESH_SOURCE_MESSAGE: String = "HELP FROM NODE A"

    /** Deterministic ASCII payloads of exactly [n] bytes for the sweep. */
    fun payloadBytes(n: Int): ByteArray = ByteArray(n) { ('A' + (it % 26)).code.toByte() }
}