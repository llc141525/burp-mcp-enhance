package net.portswigger.mcp.logging

import java.time.Instant

data class LogEntry(
    val ts: Instant,
    val level: String,
    val cat: String,
    val msg: String,
    val err: String? = null
) {
    fun toJsonLine(): String {
        val sb = StringBuilder(256)
        sb.append("{\"ts\":\"")
        sb.append(ts.toString())
        sb.append("\",\"level\":\"")
        sb.append(escapeJson(level))
        sb.append("\",\"cat\":\"")
        sb.append(escapeJson(cat))
        sb.append("\",\"msg\":\"")
        sb.append(escapeJson(msg))
        sb.append("\"")
        if (err != null) {
            sb.append(",\"err\":\"")
            sb.append(escapeJson(err))
            sb.append("\"")
        }
        sb.append("}")
        return sb.toString()
    }

    companion object {
        fun escapeJson(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
