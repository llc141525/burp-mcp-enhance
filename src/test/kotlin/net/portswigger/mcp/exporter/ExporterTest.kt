package net.portswigger.mcp.exporter

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.logging.Logging
import burp.api.montoya.proxy.Proxy
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.db.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ExporterTest {

    private val api = mockk<MontoyaApi>(relaxed = true)
    private val mockLogging = mockk<Logging>(relaxed = true)
    private lateinit var database: Database
    private lateinit var exporter: Exporter

    @BeforeEach
    fun setup() {
        every { api.logging() } returns mockLogging

        database = Database(":memory:")
        exporter = Exporter(
            api = api,
            database = database,
            pollIntervalMs = 30_000,
            maxBodySize = 8192
        )
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    @Test
    fun `exportProxyHttpHistory should only process new entries on subsequent exports`() = runBlocking {
        val entry1 = createMockProxyEntry(1000, "http://example.com/old")
        val entry2 = createMockProxyEntry(2000, "http://example.com/new")
        val proxyMock = mockk<Proxy>(relaxed = true)

        // First export — processes everything since lastProxyTimestampMs starts at 0
        every { api.proxy() } returns proxyMock
        every { proxyMock.history() } returns listOf(entry1)
        exporter.exportProxyHttpHistory()
        assertEquals(1, database.stats().proxyHttpCount)

        // Second export — only entry2 is newer than the previous max timestamp
        every { proxyMock.history() } returns listOf(entry1, entry2)
        exporter.exportProxyHttpHistory()
        assertEquals(2, database.stats().proxyHttpCount, "Only the new entry should be added")
    }

    @Test
    fun `exportProxyHttpHistory should handle empty history gracefully`() = runBlocking {
        every { api.proxy() } returns mockk<Proxy>(relaxed = true).apply {
            every { history() } returns emptyList()
        }

        exporter.exportProxyHttpHistory()

        assertEquals(0, database.stats().proxyHttpCount)
    }

    @Test
    fun `exportProxyHttpHistory should handle first export correctly`() = runBlocking {
        val entry = createMockProxyEntry(1000, "http://example.com/test")
        every { api.proxy() } returns mockk<Proxy>(relaxed = true).apply {
            every { history() } returns listOf(entry)
        }

        exporter.exportProxyHttpHistory()

        assertEquals(1, database.stats().proxyHttpCount)
    }

    private fun createMockProxyEntry(timestampSeconds: Long, url: String): ProxyHttpRequestResponse {
        val mockEntry = mockk<ProxyHttpRequestResponse>(relaxed = true)
        val zonedDateTime = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(timestampSeconds), ZoneId.systemDefault()
        )
        every { mockEntry.time() } returns zonedDateTime

        val mockRequest = mockk<HttpRequest>(relaxed = true)
        every { mockEntry.request() } returns mockRequest
        every { mockRequest.body() } returns null

        val path = java.net.URI(url).rawPath
        val mockService = mockk<HttpService>(relaxed = true)
        every { mockRequest.httpService() } returns mockService
        every { mockService.host() } returns "example.com"
        every { mockService.port() } returns 80
        every { mockService.secure() } returns false
        every { mockRequest.path() } returns path
        every { mockRequest.method() } returns "GET"
        every { mockRequest.headers() } returns emptyList()

        return mockEntry
    }
}
