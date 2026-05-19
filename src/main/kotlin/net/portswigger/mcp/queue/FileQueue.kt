package net.portswigger.mcp.queue

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FileQueue(private val tempDir: Path = Files.createTempDirectory("mcp-files-"), private val fileTtlMs: Long = 600_000) {

    private val fileMetadata = ConcurrentHashMap<String, FileMetadata>()
    private val accessCount = AtomicInteger(0)
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mcp-filequeue-cleanup").apply { isDaemon = true }
    }

    init {
        cleanupExecutor.scheduleAtFixedRate(::cleanupExpired, 60, 60, TimeUnit.SECONDS)
        tempDir.toFile().mkdirs()
    }

    data class FileMetadata(
        val fileId: String,
        val originalName: String,
        val size: Long,
        val mimeType: String,
        val createdAt: Long = System.currentTimeMillis(),
        var lastAccessedAt: Long = System.currentTimeMillis(),
        var accessCount: Int = 0
    )

    fun store(content: ByteArray, originalName: String = "response", mimeType: String = "application/octet-stream"): String {
        val fileId = UUID.randomUUID().toString()
        val targetFile = tempDir.resolve(fileId)
        Files.write(targetFile, content)

        fileMetadata[fileId] = FileMetadata(
            fileId = fileId,
            originalName = originalName,
            size = content.size.toLong(),
            mimeType = mimeType
        )

        return fileId
    }

    fun store(content: String, originalName: String = "response", mimeType: String = "text/plain"): String {
        return store(content.toByteArray(), originalName, mimeType)
    }

    fun read(fileId: String, offset: Int = 0, limit: Int = -1): ByteArray? {
        val meta = fileMetadata[fileId] ?: return null
        val file = tempDir.resolve(fileId).toFile()
        if (!file.exists()) return null

        meta.lastAccessedAt = System.currentTimeMillis()
        meta.accessCount++
        accessCount.incrementAndGet()

        val bytes = file.readBytes()
        if (offset >= bytes.size) return ByteArray(0)
        val end = if (limit == -1 || (offset + limit) > bytes.size) bytes.size else (offset + limit)
        return bytes.copyOfRange(offset, end)
    }

    fun readAsString(fileId: String, offset: Int = 0, limit: Int = -1): String? {
        return read(fileId, offset, limit)?.let { String(it) }
    }

    fun delete(fileId: String): Boolean {
        fileMetadata.remove(fileId)
        return tempDir.resolve(fileId).toFile().delete()
    }

    fun exists(fileId: String): Boolean {
        val meta = fileMetadata[fileId]
        return meta != null && tempDir.resolve(fileId).toFile().exists()
    }

    fun getMetadata(fileId: String): FileMetadata? = fileMetadata[fileId]

    fun stats(): FileQueueStats {
        val totalFiles = fileMetadata.size
        val totalSize = fileMetadata.values.sumOf { it.size }
        return FileQueueStats(
            totalFiles = totalFiles,
            totalSizeBytes = totalSize,
            totalAccesses = accessCount.get()
        )
    }

    fun cleanupExpired() {
        val cutoff = System.currentTimeMillis() - fileTtlMs
        fileMetadata.entries.removeAll { (fileId, meta) ->
            if (meta.lastAccessedAt < cutoff) {
                tempDir.resolve(fileId).toFile().delete()
                true
            } else false
        }
    }

    fun shutdown() {
        cleanupExecutor.shutdown()
        try {
            cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            cleanupExecutor.shutdownNow()
        }
        // Clean up all files
        fileMetadata.keys.forEach { fileId ->
            tempDir.resolve(fileId).toFile().delete()
        }
        fileMetadata.clear()
        tempDir.toFile().delete()
    }
}

data class FileQueueStats(
    val totalFiles: Int,
    val totalSizeBytes: Long,
    val totalAccesses: Int
)
