package net.portswigger.mcp.db

import java.sql.Connection
import java.sql.DriverManager

class Database(dbPath: String = ":memory:") {

    init {
        Class.forName("org.sqlite.JDBC")
    }

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath").also { conn ->
        conn.autoCommit = true
        conn.createStatement().apply {
            execute("PRAGMA journal_mode=WAL")
            close()
        }
        createTables(conn)
    }

    private fun createTables(conn: Connection) {
        conn.createStatement().apply {
            execute("""
                CREATE TABLE IF NOT EXISTS proxy_http_history (
                    id INTEGER PRIMARY KEY,
                    method TEXT NOT NULL,
                    status INTEGER,
                    url TEXT NOT NULL,
                    request_headers TEXT,
                    request_body TEXT,
                    response_headers TEXT,
                    response_body TEXT,
                    content_type TEXT,
                    param_names TEXT,
                    captured_at INTEGER NOT NULL
                )
            """.trimIndent())
            execute("""
                CREATE TABLE IF NOT EXISTS scanner_issues (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    url TEXT NOT NULL,
                    detail TEXT,
                    remediation TEXT,
                    captured_at INTEGER NOT NULL
                )
            """.trimIndent())
            close()
        }
    }

    fun upsertProxyHttpHistory(entries: List<ProxyHttpEntry>) {
        connection.autoCommit = false
        try {
            val stmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO proxy_http_history " +
                "(id, method, status, url, request_headers, request_body, response_headers, response_body, " +
                "content_type, param_names, captured_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
            try {
                for (entry in entries) {
                    stmt.setInt(1, entry.id)
                    stmt.setString(2, entry.method)
                    if (entry.status != null) stmt.setInt(3, entry.status) else stmt.setNull(3, java.sql.Types.INTEGER)
                    stmt.setString(4, entry.url)
                    stmt.setString(5, entry.requestHeaders)
                    stmt.setString(6, entry.requestBody)
                    stmt.setString(7, entry.responseHeaders)
                    stmt.setString(8, entry.responseBody)
                    stmt.setString(9, entry.contentType)
                    stmt.setString(10, entry.paramNames)
                    stmt.setLong(11, entry.capturedAt)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            } finally {
                stmt.close()
            }
            connection.commit()
        } finally {
            connection.autoCommit = true
        }
    }

    fun upsertScannerIssues(entries: List<ScannerIssueEntry>) {
        connection.autoCommit = false
        try {
            val stmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO scanner_issues " +
                "(id, name, severity, url, detail, remediation, captured_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
            )
            try {
                for (entry in entries) {
                    stmt.setInt(1, entry.id)
                    stmt.setString(2, entry.name)
                    stmt.setString(3, entry.severity)
                    stmt.setString(4, entry.url)
                    stmt.setString(5, entry.detail)
                    stmt.setString(6, entry.remediation)
                    stmt.setLong(7, entry.capturedAt)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            } finally {
                stmt.close()
            }
            connection.commit()
        } finally {
            connection.autoCommit = true
        }
    }

    fun listProxyHttpHistory(offset: Int = 0, count: Int = 30): List<ProxyHttpSummary> {
        connection.autoCommit = true
        val stmt = connection.prepareStatement(
            "SELECT id, method, status, url, content_type, param_names FROM proxy_http_history ORDER BY id DESC LIMIT ? OFFSET ?"
        )
        try {
            stmt.setInt(1, count)
            stmt.setInt(2, offset)
            val rs = stmt.executeQuery()
            try {
                val results = mutableListOf<ProxyHttpSummary>()
                while (rs.next()) {
                    results.add(
                        ProxyHttpSummary(
                            id = rs.getInt("id"),
                            method = rs.getString("method"),
                            status = rs.getObject("status") as? Int,
                            url = rs.getString("url"),
                            contentType = rs.getString("content_type"),
                            paramNames = rs.getString("param_names")?.split(",")?.filter { it.isNotEmpty() }
                        )
                    )
                }
                return results
            } finally {
                rs.close()
            }
        } finally {
            stmt.close()
        }
    }

    fun getProxyHttpDetail(ids: List<Int>): List<ProxyHttpEntry> {
        if (ids.isEmpty()) return emptyList()
        connection.autoCommit = true
        val placeholders = ids.joinToString(",") { "?" }
        val stmt = connection.prepareStatement(
            "SELECT * FROM proxy_http_history WHERE id IN ($placeholders) ORDER BY id DESC"
        )
        try {
            ids.forEachIndexed { index, id -> stmt.setInt(index + 1, id) }
            val rs = stmt.executeQuery()
            try {
                val results = mutableListOf<ProxyHttpEntry>()
                while (rs.next()) {
                    results.add(
                        ProxyHttpEntry(
                            id = rs.getInt("id"),
                            method = rs.getString("method"),
                            status = rs.getObject("status") as? Int,
                            url = rs.getString("url"),
                            requestHeaders = rs.getString("request_headers"),
                            requestBody = rs.getString("request_body"),
                            responseHeaders = rs.getString("response_headers"),
                            responseBody = rs.getString("response_body"),
                            contentType = rs.getString("content_type"),
                            paramNames = rs.getString("param_names"),
                            capturedAt = rs.getLong("captured_at")
                        )
                    )
                }
                return results
            } finally {
                rs.close()
            }
        } finally {
            stmt.close()
        }
    }

    fun listScannerIssues(offset: Int = 0, count: Int = 30): List<ScannerIssueSummary> {
        connection.autoCommit = true
        val stmt = connection.prepareStatement(
            "SELECT id, name, severity, url FROM scanner_issues ORDER BY id DESC LIMIT ? OFFSET ?"
        )
        try {
            stmt.setInt(1, count)
            stmt.setInt(2, offset)
            val rs = stmt.executeQuery()
            try {
                val results = mutableListOf<ScannerIssueSummary>()
                while (rs.next()) {
                    results.add(
                        ScannerIssueSummary(
                            id = rs.getInt("id"),
                            name = rs.getString("name"),
                            severity = rs.getString("severity"),
                            url = rs.getString("url")
                        )
                    )
                }
                return results
            } finally {
                rs.close()
            }
        } finally {
            stmt.close()
        }
    }

    fun getScannerIssueDetail(ids: List<Int>): List<ScannerIssueEntry> {
        if (ids.isEmpty()) return emptyList()
        connection.autoCommit = true
        val placeholders = ids.joinToString(",") { "?" }
        val stmt = connection.prepareStatement(
            "SELECT * FROM scanner_issues WHERE id IN ($placeholders) ORDER BY id DESC"
        )
        try {
            ids.forEachIndexed { index, id -> stmt.setInt(index + 1, id) }
            val rs = stmt.executeQuery()
            try {
                val results = mutableListOf<ScannerIssueEntry>()
                while (rs.next()) {
                    results.add(
                        ScannerIssueEntry(
                            id = rs.getInt("id"),
                            name = rs.getString("name"),
                            severity = rs.getString("severity"),
                            url = rs.getString("url"),
                            detail = rs.getString("detail"),
                            remediation = rs.getString("remediation"),
                            capturedAt = rs.getLong("captured_at")
                        )
                    )
                }
                return results
            } finally {
                rs.close()
            }
        } finally {
            stmt.close()
        }
    }

    fun getMaxProxyHttpId(): Int? {
        connection.autoCommit = true
        val stmt = connection.createStatement()
        try {
            val rs = stmt.executeQuery("SELECT MAX(id) FROM proxy_http_history")
            try {
                return if (rs.next()) {
                    val value = rs.getObject(1)
                    (value as? Int) ?: (value as? Long)?.toInt()
                } else null
            } finally {
                rs.close()
            }
        } finally {
            stmt.close()
        }
    }

    fun getMaxScannerIssueId(): Int? {
        connection.autoCommit = true
        val stmt = connection.createStatement()
        try {
            val rs = stmt.executeQuery("SELECT MAX(id) FROM scanner_issues")
            try {
                return if (rs.next()) {
                    val value = rs.getObject(1)
                    (value as? Int) ?: (value as? Long)?.toInt()
                } else null
            } finally {
                rs.close()
            }
        } finally {
            stmt.close()
        }
    }

    fun stats(): DbStats {
        connection.autoCommit = true
        val stmt = connection.createStatement()
        try {
            val httpRs = stmt.executeQuery("SELECT COUNT(*) FROM proxy_http_history")
            val httpCount = if (httpRs.next()) httpRs.getInt(1) else 0
            httpRs.close()

            val scannerRs = stmt.executeQuery("SELECT COUNT(*) FROM scanner_issues")
            val scannerCount = if (scannerRs.next()) scannerRs.getInt(1) else 0
            scannerRs.close()

            return DbStats(proxyHttpCount = httpCount, scannerIssueCount = scannerCount)
        } finally {
            stmt.close()
        }
    }

    fun clearProxyHttpHistory() {
        connection.createStatement().use { stmt ->
            stmt.execute("DELETE FROM proxy_http_history")
        }
    }

    fun clearScannerIssues() {
        connection.createStatement().use { stmt ->
            stmt.execute("DELETE FROM scanner_issues")
        }
    }

    fun clearAll() {
        clearProxyHttpHistory()
        clearScannerIssues()
    }

    fun close() {
        connection.close()
    }
}

data class ProxyHttpSummary(
    val id: Int,
    val method: String,
    val status: Int?,
    val url: String,
    val contentType: String?,
    val paramNames: List<String>?
)

data class ProxyHttpEntry(
    val id: Int,
    val method: String,
    val status: Int?,
    val url: String,
    val requestHeaders: String?,
    val requestBody: String?,
    val responseHeaders: String?,
    val responseBody: String?,
    val contentType: String?,
    val paramNames: String?,
    val capturedAt: Long
)

data class ScannerIssueSummary(
    val id: Int,
    val name: String,
    val severity: String,
    val url: String
)

data class ScannerIssueEntry(
    val id: Int,
    val name: String,
    val severity: String,
    val url: String,
    val detail: String?,
    val remediation: String?,
    val capturedAt: Long
)

data class DbStats(
    val proxyHttpCount: Int,
    val scannerIssueCount: Int
)
