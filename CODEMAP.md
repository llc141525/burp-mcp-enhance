# Burp MCP Server — Code Map

## Project Overview

**burp-mcp** — a Burp Suite extension that embeds a Model Context Protocol (MCP) server via Ktor/Netty, exposing Burp's pentesting capabilities as MCP tools for AI assistants (Claude Desktop, etc.).

- **Language:** Kotlin 2.2.21, JVM 21
- **Framework:** Ktor 3.3.1 (Netty), MCP Kotlin SDK 0.7.4
- **Build:** Gradle 9.2.0, shadow JAR
- **Version:** 1.2.2
- **Package:** `net.portswigger.mcp`

---

## Directory Structure

```
src/main/kotlin/net/portswigger/mcp/
├── ExtensionBase.kt           # Burp extension entry point (BurpExtender)
├── KtorServerManager.kt       # Ktor HTTP/SSE server lifecycle + CORS + security
├── ServerManager.kt           # Server lifecycle interface (sealed state machine)
├── SwingDispatcher.kt         # Swing EDT coroutine dispatcher
│
├── config/
│   ├── McpConfig.kt           # Persisted configuration with reactive listeners
│   ├── ConfigUi.kt            # Main config tab UI (layout orchestrator)
│   ├── ConfigValidation.kt    # Server host/port validation
│   ├── TargetValidation.kt    # Auto-approve target validation
│   ├── Design.kt              # Design system (colors, typography, components)
│   ├── Dialogs.kt             # Modal dialog factories
│   ├── Anchor.kt              # Clickable hyperlink label
│   ├── StatusDashboardPanel.kt # Live status dashboard (server, exporter, queue, db)
│   └── components/
│       ├── ServerConfigurationPanel.kt  # Server toggle, approval checkboxes
│       ├── AdvancedOptionsPanel.kt      # Host/port/keepalive/max-size
│       ├── AutoApproveTargetsPanel.kt   # Target list management
│       ├── InstallationPanel.kt         # Provider install buttons
│       ├── ResponsiveColumnsPanel.kt    # Responsive 1/2-column layout
│       └── WarningLabel.kt             # Warning-colored label
│
├── db/
│   └── Database.kt            # SQLite wrapper (schema, CRUD, stats)
│
├── exporter/
│   └── Exporter.kt            # Background poller: Burp API → SQLite cache
│
├── providers/
│   ├── Provider.kt            # MCP client provider interface + impls
│   └── ProxyJarManager.kt     # Bundled proxy JAR management
│
├── queue/
│   ├── Task.kt                # Task data model
│   ├── MessageQueue.kt        # In-memory async task queue
│   └── FileQueue.kt           # Temp file storage for large responses
│
├── schema/
│   ├── JsonSchema.kt          # JSON Schema generation from Kotlin types
│   └── serialization.kt      # Burp type → serializable data class mappers
│
├── security/
│   ├── HttpRequestSecurity.kt       # Outbound HTTP approval gating
│   ├── HistoryAccessSecurity.kt     # Proxy history access approval gating
│   └── SecurityUtils.kt            # Dialog parenting helper
│
└── tools/
    ├── McpTool.kt             # MCP tool DSL (mcpTool, mcpPaginatedTool)
    ├── Tools.kt               # All Burp MCP tool registrations
    ├── QueueTools.kt          # Async task queue tools (submit_task, etc.)
    └── ExporterTools.kt       # SQLite-backed list/detail tools

src/test/kotlin/net/portswigger/mcp/
├── KtorServerManagerTest.kt   # Server lifecycle tests
├── McpServerIntegrationTest.kt # SSE client integration tests
├── ProxyEndToEndTest.kt       # Full proxy chain stdio→SSE→server tests
├── TestSseMcpClient.kt        # Test SSE MCP client helper
├── TestStdioMcpClient.kt      # Test stdio MCP client helper
├── config/
│   ├── McpConfigTest.kt       # Config persistence + listeners
│   └── TargetValidationTest.kt # Target validation edge cases
├── db/
│   └── DatabaseTest.kt        # SQLite CRUD + pagination + upsert
├── exporter/
│   └── ExporterTest.kt        # Incremental export logic
├── queue/
│   ├── MessageQueueTest.kt    # Async queue lifecycle
│   └── FileQueueTest.kt       # File store + TTL cleanup
├── security/
│   └── HttpRequestSecurityTest.kt # Permission gating tests
└── tools/
    └── ToolsKtTest.kt         # Full integration tests for all tools
```

---

## Module Dependencies

```
ExtensionBase
├── McpConfig ──────── TargetValidation, ConfigValidation
├── KtorServerManager ── Database, Exporter, MessageQueue, FileQueue
│   └── Server (MCP SDK)
│       ├── mcpTool DSL (JsonSchema)
│       ├── registerTools (Tools.kt)
│       │   ├── Tools.kt ─── HttpRequestSecurity, HistoryAccessSecurity
│       │   ├── QueueTools.kt ── MessageQueue, FileQueue
│       │   └── ExporterTools.kt ── Database, Exporter
│       └── heartbeatPing
├── ConfigUi ────── dashboard + all UI panels + Design system + Dialogs
└── Providers ────── ClaudeDesktopProvider, ProxyJarManager
```

---

## Key Data Flows

### 1. MCP Tool Request Flow
```
AI Client → SSE → Ktor Server → MCP SDK Server
  → registerTools callback → deserialize args → execute
  → return CallToolResult → SSE → AI Client
```

### 2. Exporter Polling Flow (every 30s)
```
Exporter.start() → coroutine loop
  → exportProxyHttpHistory()  [Dispatchers.IO]
    → api.proxy().history() → filter by timestamp → map → Database.upsertProxyHttpHistory()
  → exportScannerIssues()     [Dispatchers.IO]
    → api.siteMap().issues() → map → Database.upsertScannerIssues()
```

### 3. Exporter Tool Query Flow (two-tier)
```
Tier 1: list_proxy_http_history(count, offset)
  → Database.listProxyHttpHistory() → light summary (id, method, status, url, ...)

Tier 2: get_proxy_http_detail(ids=[...])
  → Database.getProxyHttpDetail(ids) → full request/response
```

### 4. Async Task Flow
```
submit_task(type, params) → MessageQueue.submit()
  → coroutine processes → TaskResult stored
  → get_task_result(taskId) polls for result

Large responses >100KB → FileQueue.store() → returns fileId
  → read_file(fileId, offset, limit)
```

### 5. Security Approval Flow
```
Tool call requiring HTTP request
  → HttpRequestSecurity.checkHttpRequestPermission()
    → if requireHttpRequestApproval=false → allow
    → if auto-approve target match → allow
    → else → show Swing approval dialog

Tool call requiring history access
  → HistoryAccessSecurity.checkHistoryAccessPermission()
    → same three-tier pattern
```

---

## Performance Optimizations (implemented)

### Database Retention (auto-prune)
- **`Database.pruneProxyHttpHistory(maxRows=100_000)`** — keeps only the most recent N entries
- **`Database.pruneScannerIssues(maxRows=10_000)`** — same for scanner issues
- **`Database.pruneBlobs()`** — expires old blob entries
- **`Database.pruneAll()`** — runs all prunes
- **Trigger:** Called from `Exporter.start()` after each export cycle

### Context Deduplication
- **Schema:** `proxy_http_history` has `dedup_key` (SHA-256 of METHOD|URL) and `hit_count` columns
- **Window:** 5-minute dedup window — same endpoint within 5 minutes merges
- **Behavior:** Duplicate entries increment `hit_count` instead of creating new rows
- **Exposure:** `ProxyHttpSummary.hitCount` shown in list tools; detail tools show hit counts > 1

### SQLite BLOB Store
- **Table:** `large_responses(id TEXT PK, data BLOB, content_type, original_size, created_at, expires_at)`
- **Methods:** `storeBlob()`, `readBlob()`, `readBlobAsString()`, `deleteBlob()`, `pruneBlobs()`, `clearBlobs()`
- **TTL:** Default 10 minutes, configurable per blob
- **Auto-prune:** Called every export cycle via `pruneAll()`

### Heartbeat Protection
- **Min delay:** `heartbeatPing` enforces minimum 100ms interval (`coerceAtLeast(100)`)
- **Prevents:** Accidental CPU spin from zero-interval configuration

---

## Performance-Critical Code Paths

### Exporter (Exporter.kt)
- **Incremental export:** `lastProxyTimestampMs` tracks newest exported item; `lastKnownScannerIssueCount` avoids redundant scanner exports
- **Cycle gate:** `isExportCycleRunning` prevents overlapping cycles
- **Body truncation:** Max 8KB per request/response body
- **I/O dispatch:** All export work runs on `Dispatchers.IO`
- **Poll interval:** Default 30s, runs on `Dispatchers.IO`

### Database (Database.kt)
- **WAL mode:** For concurrent read/write performance
- **Batch writes:** `addBatch()` / `executeBatch()` with manual auto-commit management
- **Indexes:** `ORDER BY id DESC` with LIMIT/OFFSET for pagination

### Tool Execution (McpTool.kt)
- **Timeout:** Default 120s per tool call
- **Response truncation:** `DEFAULT_MAX_RESPONSE_SIZE = 100_000` chars
- **Pagination:** `mcpPaginatedTool` with offset/count + `joinWithSizeLimit()`
- **Float→Int coercion:** `normalizeJsonElement()` fixes schema mismatch

### Server (KtorServerManager.kt)
- **Lifecycle thread pool:** 2 threads (`mcp-server-lifecycle`, daemon)
- **Heartbeat:** Configurable interval (default 30s), TCP keepalive at transport level
- **Auto-restart:** Exponential backoff (1s→2s→4s), max 3 attempts
- **CORS + Security:** Origin/Host/Referer validation, browser UA blocking

### Queue
- **MessageQueue:** In-memory, ConcurrentHashMap + ConcurrentLinkedQueue, results expire after 5min
- **FileQueue:** Temp directory, 10min TTL, 60s cleanup interval

---

## Configuration (McpConfig)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Server auto-start |
| `host` | String | 127.0.0.1 | Bind address |
| `port` | Int | 9876 | Bind port |
| `strictLocalhostMode` | Boolean | true | DNS rebinding protection |
| `keepaliveEnabled` | Boolean | true | Heartbeat pings |
| `keepaliveIntervalSec` | Int | 30 | Heartbeat interval |
| `maxResponseSizeKb` | Int | 100 | Max tool response size |
| `requireHttpRequestApproval` | Boolean | true | HTTP request permission gate |
| `requireHistoryAccessApproval` | Boolean | true | History access permission gate |
| `configEditingTooling` | Boolean | false | Allow config-modifying tools |
| `autoApproveTargets` | String | "" | Newline-separated target list |

---

## Testing Structure

- **Framework:** JUnit 5 + MockK
- **Pattern:** `@Nested` inner classes for tool category grouping (ToolsKtTest)
- **Mock backing store:** `mutableMapOf<String, Any>()` for `PersistedObject`
- **Port allocation:** `ServerSocket(0).use { it.localPort }` for all server tests
- **Polling pattern:** `delay(100)` loops for async state transitions
- **Test levels:**
  1. Pure unit (no server): TargetValidation, McpConfig, MessageQueue, FileQueue, Database, Exporter, HttpRequestSecurity
  2. Server lifecycle: KtorServerManager (real server, no MCP client)
  3. Full integration with MCP client: ToolsKtTest, McpServerIntegrationTest, ProxyEndToEndTest

---

## Security Architecture

### DNS Rebinding Protection (`KtorServerManager.kt`)
- `isValidOrigin()`: Only localhost origins when strict mode
- `isValidHost()`: Only localhost hosts when strict mode
- `isValidReferer()`: Only localhost referers when strict mode
- `isBrowserRequest()`: Blocks browser User-Agent strings
- Security headers: X-Frame-Options, X-Content-Type-Options, CSP, Referrer-Policy

### Approval Gates
- `HttpRequestSecurity`: Three-tier (disabled → auto-approve → dialog)
- `HistoryAccessSecurity`: Three-tier (disabled → always-allow → dialog)
- Auto-approve supports exact host, host:port, and wildcard domains (`*.example.com`)

---

## Build & Run

```bash
# Build shadow JAR
./gradlew shadowJar

# Run tests
./gradlew test

# Run specific test
./gradlew test --tests "net.portswigger.mcp.tools.ToolsKtTest"

# Build proxy JAR (required for end-to-end tests)
./gradlew :mcp-proxy:shadowJar
```
