package net.portswigger.mcp

import net.portswigger.mcp.logging.LogWriter

class HealthMonitor(
    private val serverCheck: () -> Boolean,
    private val onUnhealthy: suspend () -> Unit,
    private val logWriter: LogWriter
) {
    private var consecutiveFailures = 0
    private val unhealthyThreshold = 3

    fun reset() {
        consecutiveFailures = 0
    }

    suspend fun check() {
        val healthy = try {
            serverCheck()
        } catch (e: Exception) {
            logWriter.log("WARN", "heartbeat", "Health check threw: ${e.message}", e)
            false
        }

        if (healthy) {
            if (consecutiveFailures > 0) {
                logWriter.log("INFO", "heartbeat", "Server recovered after $consecutiveFailures failed checks")
            }
            consecutiveFailures = 0
        } else {
            consecutiveFailures++
            logWriter.log("WARN", "heartbeat", "Health check failed ($consecutiveFailures/$unhealthyThreshold)")
            if (consecutiveFailures >= unhealthyThreshold) {
                logWriter.log("ERROR", "heartbeat", "Server deemed unhealthy after $consecutiveFailures consecutive failures, triggering recovery")
                consecutiveFailures = 0
                onUnhealthy()
            }
        }
    }
}
