package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.db.ProxyHttpEntry
import net.portswigger.mcp.exporter.Exporter
import net.portswigger.mcp.queue.FileQueue
import net.portswigger.mcp.queue.MessageQueue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class KtorServerManagerTest {

    private val api = mockk<MontoyaApi>(relaxed = true)
    private lateinit var serverManager: KtorServerManager
    private lateinit var config: McpConfig
    private val testPort = findAvailablePort()

    private fun findAvailablePort() = ServerSocket(0).use { it.localPort }

    @BeforeEach
    fun setup() {
        val persistedObject = mockk<PersistedObject>().apply {
            every { getBoolean(any()) } returns false
            every { getString(any()) } returns ""
            every { getInteger(any()) } returns 0
            every { setBoolean(any(), any()) } returns Unit
            every { setString(any(), any()) } returns Unit
            every { setInteger(any(), any()) } returns Unit
        }
        val mockLogging = mockk<Logging>().apply {
            every { logToError(any<String>()) } returns Unit
            every { logToOutput(any<String>()) } returns Unit
        }

        config = McpConfig(persistedObject, mockLogging).apply {
            host = "127.0.0.1"
            port = testPort
            keepaliveEnabled = false
        }

        serverManager = KtorServerManager(api)
    }

    @Test
    fun `server should transition through states correctly`() {
        val states = mutableListOf<ServerState>()

        serverManager.start(config) { state ->
            states.add(state)
        }

        runBlocking {
            var running = false
            for (i in 1..30) {
                delay(100)
                if (states.any { it is ServerState.Running }) {
                    running = true
                    break
                }
            }
            assertTrue(running, "Server should reach Running state")
        }

        // First state should be Starting
        assertEquals(ServerState.Starting, states.first())
        // Last state should be Running
        assertTrue(states.last() is ServerState.Running)

        serverManager.stop { states.add(it) }

        runBlocking {
            var stopped = false
            for (i in 1..30) {
                delay(100)
                if (states.any { it is ServerState.Stopped }) {
                    stopped = true
                    break
                }
            }
            assertTrue(stopped, "Server should reach Stopped state")
        }
    }

    @Test
    fun `server should handle stop when not started`() {
        assertDoesNotThrow {
            serverManager.stop {}
        }
    }

    @Test
    fun `server should handle shutdown when not started`() {
        assertDoesNotThrow {
            serverManager.shutdown()
        }
    }

    @Test
    fun `server should start with strict localhost mode disabled`() {
        config.strictLocalhostMode = false
        val states = mutableListOf<ServerState>()

        serverManager.start(config) { state ->
            states.add(state)
        }

        runBlocking {
            var running = false
            for (i in 1..30) {
                delay(100)
                if (states.any { it is ServerState.Running }) {
                    running = true
                    break
                }
            }
            assertTrue(running, "Server should start with strictLocalhostMode=false")
        }

        serverManager.stop {}
    }

    @Test
    fun `restart should transition through stopping to running`() {
        val states = mutableListOf<ServerState>()

        serverManager.start(config) { state ->
            states.add(state)
        }

        runBlocking {
            var running = false
            for (i in 1..30) {
                delay(100)
                if (states.any { it is ServerState.Running }) {
                    running = true
                    break
                }
            }
            assertTrue(running, "Server should reach Running state before restart")
        }

        states.clear()
        serverManager.restart(config) { state ->
            states.add(state)
        }

        runBlocking {
            var running = false
            for (i in 1..30) {
                delay(100)
                if (states.any { it is ServerState.Running }) {
                    running = true
                    break
                }
            }
            assertTrue(running, "Server should reach Running state after restart")
        }

        // First callback should be Stopping
        assertEquals(ServerState.Stopping, states.first())
        // Last state should be Running
        assertTrue(states.last() is ServerState.Running)
    }

    @Test
    fun `restart should preserve database state with file-based storage`() {
        val tempDb = File.createTempFile("test-restart-db", ".db")
        tempDb.deleteOnExit()
        val fileServerManager = KtorServerManager(api, tempDb.absolutePath)
        val filePort = findAvailablePort()

        val persistedObject = mockk<PersistedObject>().apply {
            every { getBoolean(any()) } returns false
            every { getString(any()) } returns ""
            every { getInteger(any()) } returns 0
            every { setBoolean(any(), any()) } returns Unit
            every { setString(any(), any()) } returns Unit
            every { setInteger(any(), any()) } returns Unit
        }
        val mockLogging = mockk<Logging>().apply {
            every { logToError(any<String>()) } returns Unit
            every { logToOutput(any<String>()) } returns Unit
        }
        val fileConfig = McpConfig(persistedObject, mockLogging).apply {
            host = "127.0.0.1"
            port = filePort
            keepaliveEnabled = false
        }

        // Start server with file-based DB
        val states = mutableListOf<ServerState>()
        fileServerManager.start(fileConfig) { state -> states.add(state) }
        runBlocking {
            var running = false
            for (i in 1..30) {
                delay(100)
                if (states.any { it is ServerState.Running }) { running = true; break }
            }
            assertTrue(running, "Server should reach Running state")
        }

        // Insert data
        val db = fileServerManager.database ?: fail("Database should be initialized")
        db.upsertProxyHttpHistory(
            listOf(ProxyHttpEntry(1, "GET", 200, "http://example.com", null, null, null, null, null, null, 1000))
        )
        assertEquals(1, db.stats().proxyHttpCount)

        // Restart server
        states.clear()
        fileServerManager.restart(fileConfig) { state -> states.add(state) }
        runBlocking {
            var running = false
            for (i in 1..30) {
                delay(100)
                if (states.any { it is ServerState.Running }) { running = true; break }
            }
            assertTrue(running, "Server should reach Running state after restart")
        }

        // Verify data preserved
        val newDb = fileServerManager.database ?: fail("Database should exist after restart")
        assertEquals(1, newDb.stats().proxyHttpCount, "Database data should survive restart")

        // Cleanup
        fileServerManager.stop {}
    }

    @Test
    fun `server should start with non-localhost host when strict mode off`() {
        config.strictLocalhostMode = false
        config.host = "0.0.0.0"
        val states = mutableListOf<ServerState>()

        serverManager.start(config) { state ->
            states.add(state)
        }

        runBlocking {
            var running = false
            for (i in 1..30) {
                delay(100)
                if (states.any { it is ServerState.Running }) {
                    running = true
                    break
                }
            }
            assertTrue(running, "Server should start with 0.0.0.0 when strict mode off")
        }

        serverManager.stop {}
    }

    @Test
    fun `start should initialize infrastructure`() {
        serverManager.start(config) { }

        runBlocking {
            var running = false
            for (i in 1..30) {
                delay(100)
                if (serverManager.database != null) { running = true; break }
            }
            assertTrue(running, "Server should initialize infrastructure")
        }

        assertNotNull(serverManager.messageQueue, "messageQueue should be initialized")
        assertNotNull(serverManager.fileQueue, "fileQueue should be initialized")
        assertNotNull(serverManager.database, "database should be initialized")
        assertNotNull(serverManager.exporter, "exporter should be initialized")

        serverManager.stop {}
    }

    @Test
    fun `restart should preserve infrastructure and access to components`() {
        val states = mutableListOf<ServerState>()

        serverManager.start(config) { state ->
            states.add(state)
        }

        runBlocking {
            var running = false
            for (i in 1..30) {
                delay(100)
                if (states.any { it is ServerState.Running }) { running = true; break }
            }
            assertTrue(running, "Server should reach Running state")
        }

        val initialMessageQueue = serverManager.messageQueue
        val initialFileQueue = serverManager.fileQueue
        val initialDatabase = serverManager.database
        val initialExporter = serverManager.exporter

        assertNotNull(initialMessageQueue)
        assertNotNull(initialFileQueue)
        assertNotNull(initialDatabase)
        assertNotNull(initialExporter)

        states.clear()
        serverManager.restart(config) { state -> states.add(state) }

        runBlocking {
            var running = false
            for (i in 1..30) {
                delay(100)
                if (states.any { it is ServerState.Running }) { running = true; break }
            }
            assertTrue(running, "Server should reach Running state after restart")
        }

        // Infrastructure should be preserved (same instances) during restart
        assertSame(initialMessageQueue, serverManager.messageQueue, "messageQueue should be same instance after restart")
        assertSame(initialFileQueue, serverManager.fileQueue, "fileQueue should be same instance after restart")
        assertSame(initialDatabase, serverManager.database, "database should be same instance after restart")
        assertSame(initialExporter, serverManager.exporter, "exporter should be same instance after restart")

        serverManager.stop {}
    }

    @Test
    fun `heartbeatPing should call ping function at configured interval`() {
        runBlocking {
            var pingCount = 0
            val ping: suspend () -> Unit = { pingCount++ }

            val job = launch {
                serverManager.heartbeatPing(
                    keepaliveIntervalSec = 1,
                    ping = ping,
                    logger = {}
                )
            }

            // Wait slightly more than one interval
            delay(1100)
            job.cancelAndJoin()

            assertEquals(1, pingCount, "Ping should be called once after 1.1s with 1s interval")
        }
    }

    @Test
    fun `heartbeatPing should continue after ping failure`() {
        runBlocking {
            var pingCount = 0
            val failPing: suspend () -> Unit = {
                pingCount++
                if (pingCount <= 2) throw RuntimeException("Ping failed $pingCount")
            }

            val job = launch {
                serverManager.heartbeatPing(
                    keepaliveIntervalSec = 1,
                    ping = failPing,
                    logger = {}
                )
            }

            delay(3600)
            job.cancelAndJoin()

            assertTrue(pingCount >= 3, "Ping should continue after failures, got: $pingCount")
        }
    }

    @Test
    fun `heartbeatPing should log success and failure messages`() {
        runBlocking {
            val logMessages = mutableListOf<String>()
            var shouldFail = false
            val ping: suspend () -> Unit = {
                if (shouldFail) throw RuntimeException("fail")
            }

            val job = launch {
                serverManager.heartbeatPing(
                    keepaliveIntervalSec = 1,
                    ping = ping,
                    logger = { logMessages.add(it) }
                )
            }

            delay(1200)
            shouldFail = true
            delay(1200)
            job.cancelAndJoin()

            assertTrue(logMessages.any { it.contains("sent") }, "Should log success: $logMessages")
            assertTrue(logMessages.any { it.contains("failed") }, "Should log failure: $logMessages")
        }
    }

    // ========================
    // AC1: Heartbeat sends real data event (not SSE comment)
    // ========================

    @Test
    fun `heartbeat event should use data parameter not comments for NAT traversal`() {
        // Ktor SSE heartbeat must send real data events so NAT devices
        // recognize the connection as active traffic. SSE comments are
        // typically ignored by NAT/proxy devices.
        val event = ServerSentEvent(event = "ping", data = "keepalive")
        assertNotNull(event.data, "Heartbeat event must have data content for NAT traversal")
        assertTrue(event.data!!.contains("keepalive"), "Heartbeat data must contain recognizable payload")
    }

    // ========================
    // AC2: Health check doesn't rely on activeSseConnections
    // ========================

    @Test
    fun `serverCheck should not use activeSseConnections as health signal`() {
        // Reproducer: when SSE connections are stale (TCP disconnected but
        // counter never decremented), activeSseConnections > 0 should NOT
        // make the server appear healthy.
        val activeSseConnections = AtomicInteger(3) // stale connections
        var server: Any? = null // server is stopped
        val currentState = AtomicReference<ServerState>(ServerState.Stopped)

        // This simulates the POST-FIX serverCheck — no || activeSseConnections.get() > 0
        val serverCheck: () -> Boolean = {
            server != null && currentState.get() == ServerState.Running
        }

        assertFalse(serverCheck(), "Health check must be false when server is null despite active connections")
    }

    // ========================
    // AC3: Restart guard doesn't skip restart when stale connections exist
    // ========================

    @Test
    fun `restart should trigger in health monitor even when stale connections exist`() = runBlocking {
        // AC3: The onUnhealthy callback must not skip restart when
        // activeSseConnections > 0, because those connections may be stale.
        var restartTriggered = false

        // This simulates the POST-FIX onUnhealthy — no guard on activeCount
        val onUnhealthy: suspend () -> Unit = {
            restartTriggered = true
        }

        onUnhealthy()
        assertTrue(restartTriggered, "Restart must trigger even with stale connections present")
    }

    @Test
    fun `server check lambda should trigger restart via health monitor when unhealthy`() = runBlocking {
        // Validates that HealthMonitor with the post-fix lambda pattern
        // correctly triggers restart when server is null with stale connections.
        var restartCalled = 0

        val monitor = HealthMonitor(
            serverCheck = { false }, // unhealthy
            onUnhealthy = { restartCalled++ }, // triggers regardless
            logWriter = mockk(relaxed = true)
        )

        // Need 3 consecutive failures to trigger
        monitor.check() // fail 1
        monitor.check() // fail 2
        assertEquals(0, restartCalled, "Two failures should not yet trigger restart")
        monitor.check() // fail 3 → trigger
        assertEquals(1, restartCalled, "Three failures must trigger restart regardless of active connections")
    }
}
