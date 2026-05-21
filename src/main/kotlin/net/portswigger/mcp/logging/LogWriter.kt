package net.portswigger.mcp.logging

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LogWriter(
    private val logDir: File,
    private val maxFileSize: Long = 10L * 1024 * 1024,
    private val maxFileCount: Int = 7,
    private val maxTotalSize: Long = 50L * 1024 * 1024
) {
    private val lock = Any()
    private var currentWriter: PrintWriter? = null
    private var currentFileDate: LocalDate? = null
    private var currentFileSize: Long = 0

    @Volatile
    private var started = false

    companion object {
        @Volatile
        var instance: LogWriter? = null
            private set
    }

    fun start() {
        if (started) return
        started = true
        instance = this
        logDir.mkdirs()
    }

    fun log(level: String, cat: String, msg: String, error: Throwable? = null) {
        if (!started) return
        val errStr = error?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString()
        }
        val entry = LogEntry(Instant.now(), level, cat, msg, errStr)
        synchronized(lock) {
            try {
                writeEntry(entry)
            } catch (_: Exception) {
            }
        }
    }

    fun shutdown() {
        if (!started) return
        started = false
        instance = null
        synchronized(lock) {
            try {
                currentWriter?.flush()
                currentWriter?.close()
                currentWriter = null
                currentFileSize = 0
            } catch (_: Exception) {
            }
        }
    }

    private fun writeEntry(entry: LogEntry) {
        val today = LocalDate.now(ZoneId.of("UTC"))
        if (currentWriter == null || today != currentFileDate || currentFileSize >= maxFileSize) {
            rotate(today)
        }
        val line = entry.toJsonLine()
        currentWriter?.let { w ->
            w.println(line)
            currentFileSize += line.length.toLong() + System.lineSeparator().length.toLong()
        }
    }

    private fun rotate(today: LocalDate) {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (_: Exception) {
        }

        // Find a unique filename for today (avoid truncating existing same-day rotation)
        val base = "burp-mcp-$today"
        var seq = 0
        var file: File
        do {
            val suffix = if (seq == 0) "" else "-$seq"
            file = File(logDir, "$base$suffix.jsonl")
            seq++
        } while (file.exists())
        currentWriter = PrintWriter(file, Charsets.UTF_8)
        currentFileDate = today
        currentFileSize = 0L

        prune()
    }

    private fun prune() {
        try {
            val files = logDir.listFiles { f -> f.name.startsWith("burp-mcp-") && f.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            // Prune excess by count
            if (files.size > maxFileCount) {
                for (f in files.drop(maxFileCount)) {
                    f.delete()
                }
            }

            // Prune excess by total size
            val kept = logDir.listFiles { f -> f.name.startsWith("burp-mcp-") && f.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            var runningTotal = kept.sumOf { it.length() }
            for (f in kept.reversed()) {
                if (runningTotal <= maxTotalSize) break
                runningTotal -= f.length()
                f.delete()
            }
        } catch (_: Exception) {
        }
    }
}
