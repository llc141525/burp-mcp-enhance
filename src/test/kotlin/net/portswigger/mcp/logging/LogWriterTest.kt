package net.portswigger.mcp.logging

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant

class LogWriterTest {

    private lateinit var tempDir: File
    private lateinit var logWriter: LogWriter

    @BeforeEach
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "burp-mcp-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        logWriter = LogWriter(tempDir, maxFileSize = 1024, maxFileCount = 3, maxTotalSize = 4096)
        logWriter.start()
    }

    @AfterEach
    fun cleanup() {
        logWriter.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `should write log entries to file`() {
        logWriter.log("INFO", "test", "hello world")
        logWriter.log("ERROR", "test", "something broke", RuntimeException("boom"))

        // Shutdown to flush
        logWriter.shutdown()

        val files = tempDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(files.isNotEmpty(), "Should create at least one log file")

        val content = files.first().readText()
        assertTrue(content.contains("hello world"))
        assertTrue(content.contains("something broke"))
        assertTrue(content.contains("RuntimeException"))
        assertTrue(content.contains("\"level\":\"INFO\""))
        assertTrue(content.contains("\"level\":\"ERROR\""))
        assertTrue(content.contains("\"cat\":\"test\""))
    }

    @Test
    fun `should handle concurrent writes without data loss`() {
        // Use a writer with generous limits to avoid rotation/pruning during test
        val writer = LogWriter(tempDir, maxFileSize = 10 * 1024 * 1024, maxFileCount = 50, maxTotalSize = 100 * 1024 * 1024)
        writer.start()

        val n = 100
        val threads = (1..n).map { i ->
            Thread { writer.log("INFO", "test", "message-$i") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        writer.shutdown()

        val files = tempDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        val allContent = files.joinToString("\n") { it.readText() }
        val count = allContent.lines().count { it.contains("message-") }
        assertEquals(n, count, "All $n concurrent writes should be persisted")
    }

    @Test
    fun `should rotate file when exceeding max size`() {
        // Write enough data to exceed 1024 byte max file size
        val bigMessage = "x".repeat(200)
        repeat(20) { i ->
            logWriter.log("INFO", "test", "rotation-test-$i $bigMessage")
        }

        logWriter.shutdown()

        val files = tempDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(files.size >= 2, "Should have at least 2 files due to rotation, got ${files.size}")
    }

    @Test
    fun `should prune old files when exceeding max count`() {
        // Create 5 files manually (simulating old rotated files)
        val oldFiles = (1..5).map { i ->
            File(tempDir, "burp-mcp-2026-05-${10 + i}.jsonl").also {
                it.writeText("""{"ts":"2026-05-${10 + i}T00:00:00Z","level":"INFO","cat":"test","msg":"old-$i"}""" + "\n")
                it.setLastModified(1000L * i) // ensure distinct modification times
            }
        }

        // Start a new writer that prunes to 3
        val writer2 = LogWriter(tempDir, maxFileSize = 10 * 1024 * 1024, maxFileCount = 3, maxTotalSize = 50 * 1024 * 1024)
        writer2.start()
        writer2.log("INFO", "test", "new entry")
        writer2.shutdown()

        val remaining = tempDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(remaining.size <= 3, "Should prune to max 3 files, got ${remaining.size}")
    }

    @Test
    fun `should not throw when logging after shutdown`() {
        logWriter.shutdown()

        assertDoesNotThrow {
            logWriter.log("INFO", "test", "after shutdown")
        }
    }

    @Test
    fun `should set and clear companion instance`() {
        val writer = LogWriter(tempDir)
        writer.start()
        assertNotNull(LogWriter.instance)
        writer.shutdown()
        assertNull(LogWriter.instance)
    }

    @Test
    fun `should escape special characters in json`() {
        logWriter.log("INFO", "test", "line1\nline2\twith\"quotes\"and\\backslash")
        logWriter.shutdown()

        val files = tempDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        val content = files.first().readText()
        assertTrue(content.contains("\\n"))
        assertTrue(content.contains("\\t"))
        assertTrue(content.contains("\\\""))
        assertTrue(content.contains("\\\\"))
    }

    @Test
    fun `should include error stack trace in err field`() {
        val ex = IllegalStateException("test error")
        logWriter.log("ERROR", "test", "error occurred", ex)
        logWriter.shutdown()

        val files = tempDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        val content = files.first().readText()
        assertTrue(content.contains("IllegalStateException"))
        assertTrue(content.contains("test error"))
        assertTrue(content.contains("\"err\":\""))
    }

    @Test
    fun `should include timestamp in ISO format`() {
        val before = Instant.now()
        logWriter.log("INFO", "test", "timestamped")
        logWriter.shutdown()

        val files = tempDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        val content = files.first().readText()
        // Verify ts field is present with ISO format
        assertTrue(content.contains("\"ts\":\"202"))
    }
}
