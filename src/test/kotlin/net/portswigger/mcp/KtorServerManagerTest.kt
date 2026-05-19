package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.db.ProxyHttpEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.ServerSocket

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
}
