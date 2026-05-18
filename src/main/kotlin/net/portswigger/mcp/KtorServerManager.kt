package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.*
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.tools.registerTools
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class KtorServerManager(private val api: MontoyaApi) : ServerManager {

    private var server: EmbeddedServer<*, *>? = null
    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "mcp-server-lifecycle").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var restartJob: Job? = null

    private val currentState = AtomicReference<ServerState>(ServerState.Stopped)
    private val maxRestartAttempts = 3

    override fun start(config: McpConfig, callback: (ServerState) -> Unit) {
        currentState.set(ServerState.Starting)
        callback(ServerState.Starting)

        executor.submit {
            try {
                server?.stop(1000, 5000)
                server = null

                val mcpServer = Server(
                    serverInfo = Implementation("burp-suite", "1.1.2"), options = ServerOptions(
                        capabilities = ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = false)
                        )
                    )
                )

                server = embeddedServer(Netty, port = config.port, host = config.host) {
                    install(CORS) {
                        allowHost("localhost:${config.port}")
                        allowHost("127.0.0.1:${config.port}")

                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)

                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Accept)
                        allowHeader("Last-Event-ID")

                        allowCredentials = false
                        allowNonSimpleContentTypes = true
                        maxAgeInSeconds = 3600
                    }

                    intercept(ApplicationCallPipeline.Call) {
                        val origin = call.request.header("Origin")
                        val host = call.request.header("Host")
                        val referer = call.request.header("Referer")
                        val userAgent = call.request.header("User-Agent")

                        if (origin != null && !isValidOrigin(origin)) {
                            api.logging().logToOutput("Blocked DNS rebinding attack from origin: $origin")
                            call.respond(HttpStatusCode.Forbidden)
                            return@intercept
                        } else if (isBrowserRequest(userAgent)) {
                            api.logging().logToOutput("Blocked browser request without Origin header")
                            call.respond(HttpStatusCode.Forbidden)
                            return@intercept
                        }

                        if (host != null && !isValidHost(host, config.port)) {
                            api.logging().logToOutput("Blocked DNS rebinding attack from host: $host")
                            call.respond(HttpStatusCode.Forbidden)
                            return@intercept
                        }

                        if (referer != null && !isValidReferer(referer)) {
                            api.logging().logToOutput("Blocked suspicious request from referer: $referer")
                            call.respond(HttpStatusCode.Forbidden)
                            return@intercept
                        }

                        call.response.header("X-Frame-Options", "DENY")
                        call.response.header("X-Content-Type-Options", "nosniff")
                        call.response.header("Referrer-Policy", "same-origin")
                        call.response.header("Content-Security-Policy", "default-src 'none'")
                    }

                    mcp {
                        mcpServer
                    }

                    mcpServer.registerTools(api, config)
                }.apply {
                    start(wait = false)
                }

                api.logging().logToOutput("Started MCP server on ${config.host}:${config.port}")
                currentState.set(ServerState.Running)
                callback(ServerState.Running)

                // Start keepalive heartbeat
                startHeartbeat(config, mcpServer)

            } catch (e: Exception) {
                api.logging().logToError(e)
                currentState.set(ServerState.Failed(e))
                callback(ServerState.Failed(e))

                // Schedule automatic restart with exponential backoff
                scheduleRestart(config, callback)
            }
        }
    }

    private fun startHeartbeat(config: McpConfig, mcpServer: Server) {
        heartbeatJob?.cancel()
        if (!config.keepaliveEnabled) return

        heartbeatJob = scope.launch {
            while (isActive) {
                delay(config.keepaliveIntervalSec * 1000L)
                try {
                    mcpServer.ping()
                    api.logging().logToOutput("MCP keepalive ping sent")
                } catch (e: Exception) {
                    api.logging().logToOutput("MCP keepalive ping failed: ${e.message}")
                }
            }
        }
    }

    private fun scheduleRestart(config: McpConfig, callback: (ServerState) -> Unit) {
        restartJob?.cancel()

        val attempts = currentState.get()
        if (attempts is ServerState.Failed && attempts.exception is InterruptedException) {
            api.logging().logToOutput("Server interrupted, not scheduling restart")
            return
        }

        restartJob = scope.launch {
            for (i in 1..maxRestartAttempts) {
                val delayMs = 1000L * (1L shl (i - 1))
                api.logging().logToOutput("Automatic restart attempt $i/$maxRestartAttempts in ${delayMs}ms")
                delay(delayMs)

                if (!isActive) return@launch

                api.logging().logToOutput("Automatic restart attempt $i/$maxRestartAttempts")
                start(config, callback)
            }
            api.logging().logToOutput("Max restart attempts reached ($maxRestartAttempts)")
        }
    }

    override fun stop(callback: (ServerState) -> Unit) {
        currentState.set(ServerState.Stopping)
        callback(ServerState.Stopping)

        heartbeatJob?.cancel()
        heartbeatJob = null
        restartJob?.cancel()
        restartJob = null

        executor.submit {
            try {
                server?.stop(1000, 5000)
                server = null
                api.logging().logToOutput("Stopped MCP server")
                currentState.set(ServerState.Stopped)
                callback(ServerState.Stopped)
            } catch (e: Exception) {
                api.logging().logToError(e)
                currentState.set(ServerState.Failed(e))
                callback(ServerState.Failed(e))
            }
        }
    }

    override fun shutdown() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        restartJob?.cancel()
        restartJob = null
        scope.cancel()

        server?.stop(1000, 5000)
        server = null

        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)
    }

    private fun isValidOrigin(origin: String): Boolean {
        try {
            val url = URI(origin).toURL()
            val hostname = url.host.lowercase()

            val allowedHosts = setOf("localhost", "127.0.0.1")

            return hostname in allowedHosts
        } catch (_: Exception) {
            return false
        }
    }

    private fun isBrowserRequest(userAgent: String?): Boolean {
        if (userAgent == null) return false

        val userAgentLower = userAgent.lowercase()
        val browserIndicators = listOf(
            "mozilla/", "chrome/", "safari/", "webkit/", "gecko/", "firefox/", "edge/", "opera/", "browser"
        )

        return browserIndicators.any { userAgentLower.contains(it) }
    }

    private fun isValidHost(host: String, expectedPort: Int): Boolean {
        try {
            val parts = host.split(":")
            val hostname = parts[0].lowercase()
            val port = if (parts.size > 1) parts[1].toIntOrNull() else null

            val allowedHosts = setOf("localhost", "127.0.0.1")
            if (hostname !in allowedHosts) {
                return false
            }

            if (port != null && port != expectedPort) {
                return false
            }

            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun isValidReferer(referer: String): Boolean {
        try {
            val url = URI(referer).toURL()
            val hostname = url.host.lowercase()

            val allowedHosts = setOf("localhost", "127.0.0.1")
            return hostname in allowedHosts

        } catch (_: Exception) {
            return false
        }
    }
}
