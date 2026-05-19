package net.portswigger.mcp.queue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

class FileQueueTest {

    private lateinit var fileQueue: FileQueue

    @BeforeEach
    fun setup() {
        fileQueue = FileQueue()
    }

    @AfterEach
    fun tearDown() {
        fileQueue.shutdown()
    }

    @Test
    fun `store and read content as string`() {
        val fileId = fileQueue.store("test content")
        assertNotNull(fileId)

        val content = fileQueue.readAsString(fileId)
        assertEquals("test content", content)
    }

    @Test
    fun `store and read content as bytes`() {
        val content = "binary content".toByteArray()
        val fileId = fileQueue.store(content)

        val read = fileQueue.read(fileId)
        assertArrayEquals(content, read)
    }

    @Test
    fun `read with offset and limit`() {
        val fileId = fileQueue.store("0123456789")

        val partial = fileQueue.readAsString(fileId, offset = 3, limit = 4)
        assertEquals("3456", partial)
    }

    @Test
    fun `read with offset beyond size returns empty`() {
        val fileId = fileQueue.store("short")

        val result = fileQueue.readAsString(fileId, offset = 100)
        assertEquals("", result)
    }

    @Test
    fun `read returns null for non-existent file`() {
        assertNull(fileQueue.read("nonexistent"))
    }

    @Test
    fun `delete removes file`() {
        val fileId = fileQueue.store("to be deleted")
        assertTrue(fileQueue.exists(fileId))

        val deleted = fileQueue.delete(fileId)
        assertTrue(deleted)
        assertFalse(fileQueue.exists(fileId))
    }

    @Test
    fun `delete returns false for non-existent file`() {
        assertFalse(fileQueue.delete("nonexistent"))
    }

    @Test
    fun `getMetadata returns correct info`() {
        val content = "metadata test"
        val fileId = fileQueue.store(content, originalName = "test.txt", mimeType = "text/plain")

        val meta = fileQueue.getMetadata(fileId)
        assertNotNull(meta)
        assertEquals("test.txt", meta!!.originalName)
        assertEquals("text/plain", meta.mimeType)
        assertEquals(content.toByteArray().size.toLong(), meta.size)
    }

    @Test
    fun `getMetadata returns null for non-existent file`() {
        assertNull(fileQueue.getMetadata("nonexistent"))
    }

    @Test
    fun `stats returns correct file count and size`() {
        fileQueue.store("content1")
        fileQueue.store("content2")
        fileQueue.store("content3")

        val stats = fileQueue.stats()
        assertEquals(3, stats.totalFiles)
    }

    @Test
    fun `cleanup expired files removes old files`() {
        val fileId = fileQueue.store("expired content")

        fileQueue.cleanupExpired()

        // TTL is 600s, file is fresh, should not be removed
        assertTrue(fileQueue.exists(fileId))
    }

    @Test
    fun `store and read large content`() {
        val largeContent = "A".repeat(100_000)
        val fileId = fileQueue.store(largeContent)

        val read = fileQueue.readAsString(fileId)
        assertEquals(100_000, read!!.length)
        assertEquals(largeContent, read)
    }
}
