package net.portswigger.mcp

import kotlinx.coroutines.*
import net.portswigger.mcp.logging.LogWriter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class HealthMonitorTest {

    private lateinit var tempDir: File
    private lateinit var logWriter: LogWriter

    @BeforeEach
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "burp-mcp-health-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        logWriter = LogWriter(tempDir)
        logWriter.start()
    }

    @AfterEach
    fun cleanup() {
        logWriter.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `should not trigger callback when server is healthy`() = runBlocking {
        var triggered = false
        val monitor = HealthMonitor(
            serverCheck = { true },
            onUnhealthy = { triggered = true },
            logWriter = logWriter
        )

        repeat(5) { monitor.check() }

        assertFalse(triggered, "Callback should not be triggered when healthy")
    }

    @Test
    fun `should trigger callback after 3 consecutive failures`() = runBlocking {
        var triggerCount = 0
        val monitor = HealthMonitor(
            serverCheck = { false },
            onUnhealthy = { triggerCount++ },
            logWriter = logWriter
        )

        // 2 failures should NOT trigger
        monitor.check()
        monitor.check()
        assertEquals(0, triggerCount, "Should not trigger after 2 failures")

        // 3rd failure SHOULD trigger
        monitor.check()
        assertEquals(1, triggerCount, "Should trigger after 3 consecutive failures")
    }

    @Test
    fun `should reset failure count on recovery`() = runBlocking {
        var triggerCount = 0
        var healthy = false
        val monitor = HealthMonitor(
            serverCheck = { healthy },
            onUnhealthy = { triggerCount++ },
            logWriter = logWriter
        )

        // 2 failures
        monitor.check()
        monitor.check()

        // Recover
        healthy = true
        monitor.check()

        // 2 more failures (should NOT trigger because count was reset)
        healthy = false
        monitor.check()
        monitor.check()

        assertEquals(0, triggerCount, "Should not trigger because failure count was reset by recovery")
    }

    @Test
    fun `should trigger again after recovery and another 3 failures`() = runBlocking {
        var triggerCount = 0
        var healthy = false
        val monitor = HealthMonitor(
            serverCheck = { healthy },
            onUnhealthy = { triggerCount++ },
            logWriter = logWriter
        )

        // 3 failures -> trigger
        repeat(3) { monitor.check() }
        assertEquals(1, triggerCount)

        // Recover
        healthy = true
        monitor.check()

        // 3 more failures -> trigger again
        healthy = false
        repeat(3) { monitor.check() }
        assertEquals(2, triggerCount, "Should trigger again after recovery and 3 more failures")
    }

    @Test
    fun `should treat exception in check as failure`() = runBlocking {
        var triggerCount = 0
        val monitor = HealthMonitor(
            serverCheck = { throw RuntimeException("check failed") },
            onUnhealthy = { triggerCount++ },
            logWriter = logWriter
        )

        repeat(3) { monitor.check() }
        assertEquals(1, triggerCount, "Exception in check should count as failure")
    }

    @Test
    fun `should reset consecutive failures after triggering`() = runBlocking {
        var triggerCount = 0
        val monitor = HealthMonitor(
            serverCheck = { false },
            onUnhealthy = { triggerCount++ },
            logWriter = logWriter
        )

        // Trigger first time
        repeat(3) { monitor.check() }
        assertEquals(1, triggerCount)

        // Need another 3 failures to trigger again
        monitor.check()
        monitor.check()
        assertEquals(1, triggerCount, "Should not trigger again until 3 more failures")
        monitor.check()
        assertEquals(2, triggerCount, "Should trigger on 3rd failure after reset")
    }
}
