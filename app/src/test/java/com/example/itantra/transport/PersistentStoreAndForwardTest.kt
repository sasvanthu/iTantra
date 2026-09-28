package com.example.itantra.transport

import com.example.itantra.codec.Language
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersistentStoreAndForwardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `messages survive simulated device restart via disk persistence`() {
        val storageDir = tempFolder.newFolder("dtn_storage")
        var fakeClock = 10_000L

        // 1. Initial app run: enqueue messages into persistent queue
        val queue1 = StoreAndForwardQueue(
            maxQueueSize = 10,
            storageDir = storageDir,
            nowMillis = { fakeClock }
        )

        val payload1 = "Urgent SOS medical alert".toByteArray(Charsets.UTF_8)
        val payload2 = "Routine supply check".toByteArray(Charsets.UTF_8)

        queue1.enqueue(payload1, Language.HINDI, isEmergency = true, priority = 3)
        queue1.enqueue(payload2, Language.ENGLISH, isEmergency = false, priority = 1)
        assertEquals(2, queue1.size)

        val filesOnDisk = storageDir.listFiles { _, name -> name.startsWith("dtn_") && name.endsWith(".bin") }
        assertEquals("2 files must exist on disk", 2, filesOnDisk?.size)

        // 2. Simulate complete app restart / power cycle: create new instance
        val queue2 = StoreAndForwardQueue(
            maxQueueSize = 10,
            storageDir = storageDir,
            nowMillis = { fakeClock }
        )

        assertEquals("Queue must recover 2 messages from disk", 2, queue2.size)

        // Peak next must return the highest priority message (SOS emergency priority 3)
        val next = queue2.peekNext()
        assertNotNull(next)
        assertEquals(3, next!!.priorityLevel)
        assertEquals(Language.HINDI, next.language)
        assertArrayEquals(payload1, next.data)

        // 3. Mark forwarded -> file must be deleted from disk
        assertTrue(queue2.markForwarded(next))
        assertEquals(1, queue2.size)

        val filesAfterForward = storageDir.listFiles { _, name -> name.startsWith("dtn_") && name.endsWith(".bin") }
        assertEquals("1 file remaining after forwarding", 1, filesAfterForward?.size)
    }

    @Test
    fun `expired messages on disk are purged upon reboot`() {
        val storageDir = tempFolder.newFolder("dtn_expired")
        var fakeClock = 10_000L

        val queue1 = StoreAndForwardQueue(
            maxQueueSize = 10,
            maxMessageAgeMs = 5_000L, // 5 second TTL
            storageDir = storageDir,
            nowMillis = { fakeClock }
        )

        val data = "Transient data".toByteArray()
        queue1.enqueue(data, Language.TAMIL, isEmergency = false, priority = 1)
        assertEquals(1, queue1.size)

        // Advance clock by 10 seconds (exceeding 5s TTL)
        fakeClock += 10_000L

        // Reboot app
        val queue2 = StoreAndForwardQueue(
            maxQueueSize = 10,
            maxMessageAgeMs = 5_000L,
            storageDir = storageDir,
            nowMillis = { fakeClock }
        )

        assertEquals("Expired message must not be restored", 0, queue2.size)
        assertEquals(1, queue2.stats.value.expired)

        val files = storageDir.listFiles { _, name -> name.startsWith("dtn_") && name.endsWith(".bin") }
        assertEquals("Expired file must be deleted from disk", 0, files?.size)
    }

    @Test
    fun `displaced messages are deleted from disk when queue is full`() {
        val storageDir = tempFolder.newFolder("dtn_displace")

        val queue = StoreAndForwardQueue(
            maxQueueSize = 2,
            storageDir = storageDir
        )

        val low1 = "low 1".toByteArray()
        val low2 = "low 2".toByteArray()
        val critical = "CRITICAL RESCUE".toByteArray()

        queue.enqueue(low1, Language.ENGLISH, isEmergency = false, priority = 0)
        queue.enqueue(low2, Language.ENGLISH, isEmergency = false, priority = 1)
        assertEquals(2, queue.size)

        // Enqueue critical -> must displace lowest priority and keep disk files <= 2
        val result = queue.enqueue(critical, Language.HINDI, isEmergency = true, priority = 3)
        assertTrue(result is StoreAndForwardResult.Accepted && result.droppedToMakeRoom)
        assertEquals(2, queue.size)

        val files = storageDir.listFiles { _, name -> name.startsWith("dtn_") && name.endsWith(".bin") }
        assertEquals("Disk storage must remain strictly bounded", 2, files?.size)

        val next = queue.peekNext()
        assertEquals(3, next!!.priorityLevel)
        assertArrayEquals(critical, next.data)
    }

    @Test
    fun `clear purges memory and disk`() {
        val storageDir = tempFolder.newFolder("dtn_clear")
        val queue = StoreAndForwardQueue(storageDir = storageDir)

        queue.enqueue("msg1".toByteArray(), Language.ENGLISH, false, 1)
        queue.enqueue("msg2".toByteArray(), Language.ENGLISH, false, 2)
        assertEquals(2, queue.size)

        queue.clear()
        assertEquals(0, queue.size)

        val files = storageDir.listFiles { _, name -> name.startsWith("dtn_") && name.endsWith(".bin") }
        assertEquals(0, files?.size)
    }
}
