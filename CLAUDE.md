# Burp MCP Server — CLAUDE.md

## Quick Commands

```bash
.\gradlew.bat test              # Run all tests
.\gradlew.bat test --tests "*.ToolsKtTest"  # Tool integration tests
.\gradlew.bat shadowJar         # Build production JAR → build/libs/burp-mcp-all.jar
.\gradlew.bat test --tests "*.KtorServerManagerTest"  # Server lifecycle tests
```

## Token-Efficient Exploration

**Core principle: LSP > Grep > Read fragments > Agent. Agent only as last resort with hard limits.**

| Priority | Tool | When | Token Cost |
|----------|------|------|------------|
| 1. LSP | `findReferences`, `goToDefinition`, `hover` | Tracing call chains | ~500-1K |
| 2. Grep | pattern search with `*.kt` glob | Finding patterns across codebase | ~1-3K |
| 3. Read | targeted read with offset/limit | Known file, known area | ~500 |
| 4. Agent:Explore | quick/medium search + word limit | Unknown scope | 5-50K |

**Agent rules:**
1. Always add word limits: "Report in under 300 words."
2. Max 2 parallel Explore agents, only for genuinely independent tasks.
3. Check `git status/diff` first — modified files list already tells you what's relevant.
4. Skip Explore agent when Grep or git status already shows the relevant files.

## Code Style

- **Immutability:** Use `copy()` on data classes, never mutate in place
- **Explicit error handling:** Every public function handles failures, no silent catches
- **Naming:** Backtick test names describing behavior (`` `returns empty list when no results` ``)
- **Coroutines:** `Dispatchers.IO` for blocking I/O, `Dispatchers.Default` for CPU work

## Architecture

```
ExtensionBase (DI wiring, entry point)
├── KtorServerManager — server lifecycle, SSE, CORS, health monitor, auto-restart
│   └── MCP SDK Server → registerTools (Tools.kt)
├── Database — SQLite with WAL, dedup, BLOB store, schema migration, pruning
├── Exporter — background poller: Burp API → SQLite (incremental, every 30s)
├── MessageQueue + FileQueue — async task offloading + large response storage
├── HealthMonitor — 3-strike rule with auto-restart trigger
├── LogWriter — JSONL file logging + Burp UI dual-write
└── ConfigUi — Swing UI panels (dashboard, server config, targets, installation)
```

Key files:
- `ExtensionBase.kt` — wires the full dependency graph (poor man's DI)
- `KtorServerManager.kt` — owns all infrastructure lifecycle (create on start, destroy on stop)
- `Exporter.kt` + `Database.kt` — read-path optimization (cache Burp data locally)
- `HealthMonitor.kt` — runtime health checks with recovery
- `logging/LogWriter.kt` — persistent file-based logging with rotation

See [CODEMAP.md](./CODEMAP.md) for the complete codebase map.

## Implemented Features

- **Health monitoring:** 3-strike rule → auto-restart; persistent retry with exponential backoff (1s→2s→4s→30s→60s→...→300s)
- **File-based logging:** Persistent JSONL logs in `~/.burp-mcp/logs/` with rotation (7-day retention)
- **SSE connection tracking:** Atomic counter + ConcurrentHashMap session management
- **Database context dedup:** SHA-256 `dedup_key` (method+URL) + `hit_count`; 5-minute window merges
- **Database BLOB store:** `large_responses` table replaces filesystem temp storage
- **Database retention:** Auto-prunes old data (100K HTTP, 10K scanner, expired blobs) per export cycle
- **Schema migration:** Safe ALTER TABLE for dedup columns on existing databases
- **Security:** DNS rebinding protection (Origin/Host/Referer triple check), CSP headers, browser UA blocking
- **Tool timeout:** 120s timeout on all MCP tool calls; null-safe error messages
- **Ktor SSE:** Native SSE endpoint with `ServerSSESession.heartbeat` keepalive, 3600s read/write timeout

## Known Performance Priorities

1. **Exporter incremental filter:** Filters all history in-memory after fetching from Burp API — paginated fetch would be better
2. **Database batch writes:** `addBatch()` used but auto-commit toggle is per-call, not per-transaction
3. **Scanner issues:** `lastKnownScannerIssueCount` guard prevents redundant exports, but `issues.size` still fetches all each cycle

## External Tools

### MiniMax CLI (mmx)

Official CLI replacing the custom MCP server. Already configured:

```bash
mmx search query "..."       # Web search
mmx text chat --message "..."  # Text generation
mmx vision describe <image>    # Image understanding
mmx quota show                 # Usage quotas
```

Auth configured at `~/.mmx/config.json`, region `cn`.

### 分流约定

MiniMax M2.7 便宜，DeepSeek 贵。把不依赖深度推理的活分流给 mmx，省 Token。

**丢给 MiniMax（绿区/黄区）：**

| 场景 | 命令 | 说明 |
|------|------|------|
| 联网搜索 | `mmx search query "..."` | 查文档、找包、搜资料 |
| 读文件提取信息 | `mmx text chat --message "分析这个文件: $(cat file)"` | 大文件摘要，不占上下文 |
| Git diff 摘要 | `mmx text chat --message "分析这个 diff: $(git diff)"` | 变更摘要、风险评估 |
| 错误归类 | `mmx text chat --message "归类这些错误: ..."` | 编译/测试/lint 错误按根因分组 |
| 依赖/配置分析 | `mmx text chat --message "分析依赖: $(cat requirements.txt)"` | 提取包清单、找漏洞 |
| 图片 OCR/分析 | `mmx vision describe <image>` | 截图提取文字、验证码识别 |

**自己处理（红区 — 不走 MiniMax）：**

- 代码生成和修改（写代码、修 bug、重构）
- 安全漏洞判断和利用链分析
- 架构设计和复杂推理
- 任何需要多轮上下文理解的任务

**原则：信息提取走 MiniMax，决策和创造走 DeepSeek。**
