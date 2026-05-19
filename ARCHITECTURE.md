# Burp MCP Server — 重构方案 v2

## 目录
1. [问题分析](#1-问题分析)
2. [设计原则](#2-设计原则)
3. [架构总览](#3-架构总览)
4. [UI 重设计](#4-ui-重设计)
5. [Phase A：Bug 修复](#5-phase-a-bug-修复)
6. [Phase B：Host 可配置 + 安全策略重构](#6-phase-b-host-可配置--安全策略重构)
7. [Phase C：消息队列 + 文件队列](#7-phase-c-消息队列--文件队列)
8. [Phase D：Burp Exporter + SQLite 持久化](#8-phase-d-burp-exporter--sqlite-持久化)
9. [Phase E：UI 重做](#9-phase-e-ui-重做)
10. [路线图](#10-路线图)

---

## 1. 问题分析

### 现状痛点

| 痛点 | 根因 | 影响 |
|------|------|------|
| **频繁断连** | SSE 无 keepalive、大 payload 截断、无重连 | Claude 工具调用失败 |
| **Burp 卡顿** | AI 实时同步调 Burp API，event loop 被阻塞 | Burp UI 响应变慢 |
| **Host 锁定 localhost** | 安全代码强制 host ∈ {localhost, 127.0.0.1} | 无法绑定其他 IP |
| **UI 布局差** | 左侧空白无内容，右侧配置散乱 | 操作体验差 |
| **大响应无缓存** | 每次查询都走 Burp API，无中间缓存 | 重复查询，断连后丢失 |

### 架构现状

```
AI Client ◄──SSE──► Ktor Server (plugin/) ──sync──► Burp Montoya API
                    只能 127.0.0.1:9876              实时阻塞调用
                    无 keepalive                      无缓存
                    无重连                             大 payload 截断
```

### 目标架构

```
AI Client ◄──SSE──► Ktor Server ──msg_queue──► Burp Montoya API
                    可绑定任何 IP    │              异步非阻塞
                    有 keepalive     ├── 读：走 SQLite 缓存
                    自动重连          └── 写：走消息队列
                                    │
                                    ├── 文件队列（超大响应）
                                    │     │
                                    │     └── /tmp/mcp-files/{uuid}.json
                                    │
                                    └── SQLite DB（持久的查询缓存）
                                          │
                                          └── Burp Exporter（异步写入）
```

---

## 2. 设计原则

1. **不向后兼容** — 这是 fork，大胆改
2. **Host 自由绑定** — 移除 localhost-only 限制，用户可配任何地址
3. **Actor Model** — 替代单一线程池。每个子系统独立 Actor（HttpActor / IntruderActor / ExporterActor），互不阻塞
4. **读/写分离** — 读走 SQLite 缓存，写走 Actor 消息队列
5. **SQLite BLOB 替代文件队列** — 超大响应直接存 BLOB，无需文件系统 + 反序列化
6. **上下文去重** — 同一 endpoint 多次请求合并为一条 + hit_count，防 AI 上下文污染
7. **Retention Policy** — 自动清理旧数据，默认保留最近 100,000 条
8. **UI 填充** — 左侧改状态看板，展示各 Actor、DB、队列实时状态
9. **TDD 驱动** — 所有改动先写测试

---

## 3. 架构总览

### Actor 模型

Actor 模型替代 `newSingleThreadExecutor()`。每个 Actor 拥有独立的 `Channel<Task>` 邮箱和协程，消息在 Actor 内部串行处理，Actor 之间不阻塞。

```
┌──────────────────────────────────────────────────────────┐
│                   Burp Suite JVM                          │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐  │
│  │  Burp MCP Server (单体插件)                          │  │
│  │                                                     │  │
│  │  ┌──────────────┐  ┌──────────────┐                 │  │
│  │  │ Ktor MCP     │  │ SQLite DB    │                 │  │
│  │  │ Server (SSE) │◄─┤ (读缓存)     │                 │  │
│  │  └──────┬───────┘  └──────────────┘                 │  │
│  │         │                                            │  │
│  │         │  Actor 派发                                 │  │
│  │         ▼                                            │  │
│  │  ┌──────────────────┐  ┌────────────────┐           │  │
│  │  │  HttpActor       │  │ IntruderActor  │           │  │
│  │  │  Channel<Task>   │  │ Channel<Task>  │           │  │
│  │  │  send_http       │  │ 长时间扫描     │           │  │
│  │  │  create_repeater │  │ 独立协程       │           │  │
│  │  └────────┬─────────┘  └───────┬────────┘           │  │
│  │           │                    │                      │  │
│  │  ┌────────▼────────────────────▼────────┐           │  │
│  │  │     Burp Montoya API                  │           │  │
│  │  └───────────────────────────────────────┘           │  │
│  │                                                     │  │
│  │  ┌──────────────────────────────────────────────┐   │  │
│  │  │  ExporterActor                               │   │  │
│  │  │  Channel<Unit> (tick)                        │   │  │
│  │  │  每 30s 轮询 Burp → 增量导出 → 写入 SQLite   │   │  │
│  │  │  去重 + 截断 + Retention                     │   │  │
│  │  └──────────────────────────────────────────────┘   │  │
│  └─────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

**Actor 间关系：**
- `HttpActor` → 处理 `send_http_request`, `create_repeater_tab`, `send_to_intruder`。内部串行避免 Burp API 并发冲突。
- `IntruderActor` → 处理长时间扫描任务。与 HttpActor 并行互不干扰。
- `ExporterActor` → 定时 tick，不处理用户请求。增量导出 Burp API 数据到 SQLite，绝不阻塞。
- 本地工具（编解码、随机字符串）→ 直接在 Ktor 协程执行，不进 Actor。

### 数据通道（v2）

| 通道 | 用途 | 实现 | 特点 |
|------|------|------|------|
| **Actor 消息队列** | 写操作（send_http、repeater、intruder） | `Channel<Task>` | 每个 Actor 独立通道，Actor 内串行，Actor 间并行 |
| **SQLite BLOB** | 超大响应（>100KB） | `large_responses` 表 BLOB 列 | 存原始 bytes，无需序列化 + 反序列化 |
| **SQLite 行存** | 读缓存（history、scanner、websocket） | 结构化行 + 索引 | 增量导出，去重合并，分页查询，Retention |

---

## 4. UI 重设计

### 当前 UI 布局（差）

```
┌──────────────────────────────────────────────────┐
│ ┌─────────────────┐ ┌──────────────────────────┐ │
│ │ "Burp MCP"      │ │  Enabled [toggle]        │ │
│ │ "Integrate..."  │ │  Config Editing [toggle]  │ │
│ │ "Learn more..." │ │  Advanced Options         │ │
│ │                 │ │  ├─ Server host           │ │
│ │    ← 空白！      │ │  ├─ Server port          │ │
│ │                 │ │  Auto Approve Targets    │ │
│ │                 │ │  Installation            │ │
│ └─────────────────┘ └──────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

### 目标 UI 布局

```
┌──────────────────────────────────────────────────┐
│ ┌─────────────────┐ ┌──────────────────────────┐ │
│ │ 📊 状态看板      │ │  Enabled [toggle]        │ │
│ │                 │ │  Config Editing [toggle]  │ │
│ │  MCP Server     │ │                           │ │
│ │  ● 运行中        │ │  消息队列                  │ │
│ │  127.0.0.1:9876 │ │  ├─ 待处理: 3              │ │
│ │                 │ │  ├─ 完成: 142              │ │
│ │  SQLite 缓存     │ │  ├─ 失败: 1               │ │
│ │  ● 已连接        │ │                           │ │
│ │  条目: 1,234     │ │  文件队列                  │ │
│ │  上次导出: 5s前   │ │  ├─ 待读取: 2             │ │
│ │                 │ │  ├─ 磁盘占用: 45MB         │ │
│ │  Exporter       │ │                           │ │
│ │  ● 运行中        │ │  高级选项                  │ │
│ │  间隔: 30s      │ │  ├─ Server host: [____]   │ │
│ │                 │ │  ├─ Server port: [____]   │ │
│ │  最近错误: 无    │ │  ├─ Keepalive: [____]s   │ │
│ │                 │ │                           │ │
│ │  AI Client      │ │  自动审批目标              │ │
│ │  ● 已连接        │ │  安装                     │ │
│ │  Claude Desktop │ │                           │ │
│ └─────────────────┘ └──────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

### 状态看板（左面板，替换无意义的文案）

| 区块 | 展示内容 | 数据来源 |
|------|----------|----------|
| **MCP Server** | 运行状态、绑定地址、连接数 | `KtorServerManager` |
| **SQLite 缓存** | 连接状态、总条目数、上次导出时间 | `Exporter` |
| **消息队列** | 待处理数、已完成数、失败数 | `MessageQueue` |
| **文件队列** | 待读取数、磁盘占用 | `FileQueue` |
| **Exporter** | 运行状态、导出间隔、上次错误 | `Exporter` |
| **AI Client** | 连接状态、客户端名 | MCP SDK session |

---

## 5. Phase A：Bug 修复

按 TDD 方式实施，先写测试再改代码。

### A1: 大 Payload 截断

**方案：** `McpTool.kt` 的 `mcpPaginatedTool` 添加累积大小检查，超过 100KB 截断。

**文件：** `src/main/kotlin/net/portswigger/mcp/tools/McpTool.kt`

### A2: 整数参数解析失败

**方案：** 使用 `Json { coerceInputValues = true; ignoreUnknownKeys = true }`

**文件：** `src/main/kotlin/net/portswigger/mcp/tools/McpTool.kt`

### A3: SSE Keepalive

**方案：** `KtorServerManager.kt` 添加 `CoroutineScope` 周期性 ping（默认 30s）。

**文件：** `src/main/kotlin/net/portswigger/mcp/KtorServerManager.kt`

### A4: 自动重连

**方案：** ServerState.Failed 时指数退避重试（1s → 2s → 4s），最多 3 次。

**文件：** `src/main/kotlin/net/portswigger/mcp/KtorServerManager.kt`

### A5: 线程池

**方案：** `newSingleThreadExecutor()` → `newFixedThreadPool(2)`

**文件：** `src/main/kotlin/net/portswigger/mcp/KtorServerManager.kt`

### A6: 配置字段

**方案：** `McpConfig.kt` + UI 添加 `keepaliveEnabled`、`keepaliveIntervalSec`、`maxResponseSizeKb`

**文件：** `McpConfig.kt`, `AdvancedOptionsPanel.kt`, `ConfigUi.kt`

---

## 6. Phase B：Host 可配置 + 安全策略重构

### 现状限制

Host 被锁定在 `{localhost, 127.0.0.1}`，三处代码共同限制：

1. **`KtorServerManager.kt`** `isValidOrigin()` / `isValidHost()` / `isValidReferer()` — 只允许 localhost
2. **`McpConfig.kt`** `host by storage.string("127.0.0.1")` — 默认 127.0.0.1
3. **`KtorServerManager.kt`** CORS `allowHost("localhost:${config.port}")` — 静态绑定

### 修改方案

1. 保留默认 `127.0.0.1`，但允许用户修改
2. 当 host 改为非 localhost 时：
   - CORS `allowHost` 动态更新
   - Origin/Host/Referer 验证放宽（或添加 "严格模式" 开关）
   - 可选：添加 Bearer Token 认证，替换 DNS rebinding 防护
3. UI 中 host 字段在 Server 停止时可自由编辑，运行时给出提示

### 文件

| 文件 | 改动 |
|------|------|
| `KtorServerManager.kt` | CORS 配置动态化，安全验证增加宽松模式 |
| `McpConfig.kt` | 添加 `strictLocalhostMode` 开关（默认 true） |
| `ConfigUi.kt` | 允许编辑 host，运行时提示需要重启 |
| `AdvancedOptionsPanel.kt` | host 字段不再禁用 |

---

## 7. Phase C：Actor Model + 消息队列 + BLOB

这是解决断连和卡顿的**核心**。用 Actor 模型替代单线程池，用 SQLite BLOB 替代文件队列。

### 7.1 Actor 模型设计

```kotlin
// 通用 Actor 骨架
abstract class Actor<T>(private val name: String, scope: CoroutineScope) {
    protected val mailbox = Channel<Task>(Channel.UNLIMITED)
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            logging.logToOutput("Actor $name started")
            for (task in mailbox) {
                try {
                    process(task)
                } catch (e: Exception) {
                    logging.logToError("Actor $name task ${task.id} failed: ${e.message}")
                    completeTask(task.id, error = e.message)
                }
            }
        }
    }

    fun enqueue(task: Task) {
        mailbox.trySend(task)
    }

    protected abstract suspend fun process(task: Task)

    fun stop() { job?.cancel() }
}
```

**三个 Actor 各司其职：**

| Actor | 邮箱类型 | 处理的内容 | 互斥关系 |
|-------|----------|-----------|----------|
| `HttpActor` | `Channel<Task>` | send_http_request, create_repeater_tab, send_to_intruder | 内部串行（避免 Burp API 冲突），与其他 Actor 并行 |
| `IntruderActor` | `Channel<Task>` | intruder 扫描任务（可运行数分钟） | 完全独立，不阻塞任何其他操作 |
| `ExporterActor` | `Channel<Unit>` (tick) | 每 30s 轮询 Burp → 增量导出 → 写入 SQLite | 用 `Channel.UNLIMITED` 但实际只发 tick，不积压 |

**为什么 Actor 优于线程池？**

| 场景 | `newSingleThreadExecutor()` | Actor 模型 |
|------|---------------------------|------------|
| send_http(timeout=60) 执行中 | 整个系统阻塞 60s | 仅 HttpActor 阻塞，IntruderActor 和 ExporterActor 正常运行 |
| intruder 扫描 5 分钟 | 系统卡死 5 分钟 | 仅 IntruderActor 忙，其他正常 |
| 导出历史数据 | 队列排队等到天荒地老 | ExporterActor 独立 tick，准时导出 |
| 需要增加新子系统 | 改共享线程池配置 | 加一个新 Actor 即可 |

### 7.2 消息队列（Actor 写操作）

```
AI Client 调用 submit_task(type="send_http_request", params={...})
  → Ktor Server 收到请求
    → ActorDispatcher 根据 type 派发到对应 Actor
    → HttpActor.enqueue(task)
    → 立即返回：{ "status": "queued", "task_id": "uuid" }
    → AI Client 轮询：get_task_result(task_id)
    → HttpActor 处理完毕后，results 可查询
```

**Task 数据结构：**

```kotlin
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val type: TaskType,           // SEND_HTTP, CREATE_REPEATER, INTRUDER
    val params: JsonObject,       // 工具参数
    val status: TaskStatus,       // QUEUED / PROCESSING / DONE / FAILED
    val result: String? = null,
    val error: String? = null,
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null
)

class ActorDispatcher(private val api: MontoyaApi, scope: CoroutineScope) {
    val httpActor = HttpActor(api, scope)
    val intruderActor = IntruderActor(api, scope)
    val exporterActor = ExporterActor(api, scope)

    fun dispatch(task: Task) = when (task.type) {
        SEND_HTTP, CREATE_REPEATER -> httpActor.enqueue(task)
        INTRUDER -> intruderActor.enqueue(task)
    }
}
```

### 7.3 SQLite BLOB（替代文件队列）

**为什么不继续用文件队列：**
1. 大 JSON 序列化/反序列化耗时严重
2. 文件系统操作（创建、清理、权限）增加复杂度
3. AI 读取文件后还要反序列化 → 多一次无用开销
4. SQLite BLOB 直接存 bytes，AI 通过 `read_blob` 按 offset+limit 读，不需要反序列化

**表结构：**

```sql
CREATE TABLE large_responses (
    id TEXT PRIMARY KEY,              -- UUID
    data BLOB NOT NULL,               -- 原始 bytes
    content_type TEXT NOT NULL,       -- "application/json"
    original_size INTEGER NOT NULL,   -- 原始大小（bytes）
    created_at TEXT NOT NULL,          -- ISO 8601
    expires_at TEXT NOT NULL           -- 自动过期时间
);
CREATE INDEX idx_large_responses_expires ON large_responses(expires_at);
```

**BLOB 生命周期：**

```
创建: 响应 > 100KB → 写入 large_responses (BLOB) → 返回 blob_id
读取: AI 调用 read_blob(blob_id, offset=0, limit=32000) → 读片段
清理: DELETE FROM large_responses WHERE expires_at < NOW()
      读取后 10min 过期 / 或主动 delete_blob(blob_id)
```

### 7.4 新增 MCP 工具

```kotlin
@Serializable
data class SubmitTask(
    val type: String,              // "send_http_request" | "create_repeater" | "intruder"
    val params: JsonObject         // 原始工具参数
)

@Serializable
data class GetTaskResult(
    val taskId: String
)

@Serializable
data class ReadBlob(
    val blobId: String,
    val offset: Int = 0,
    val limit: Int = 32000
)

@Serializable
data class DeleteBlob(
    val blobId: String
)
```

### 7.5 文件

| 文件 | 说明 |
|------|------|
| **新建** `actor/Actor.kt` | 抽象 Actor 基类 |
| **新建** `actor/HttpActor.kt` | HTTP 操作 Actor |
| **新建** `actor/IntruderActor.kt` | Intruder 扫描 Actor |
| **新建** `actor/ExporterActor.kt` | 数据导出 Actor |
| **新建** `actor/ActorDispatcher.kt` | Actor 注册和派发 |
| **新建** `actor/Task.kt` | Task 数据模型 |
| **新建** `db/BlobStore.kt` | BLOB 读写 + 过期清理 |
| **新建** `tools/ActorTools.kt` | submit_task, get_task_result, read_blob, delete_blob |
| **修改** `KtorServerManager.kt` | 启动时初始化 ActorDispatcher |

---

## 8. Phase D：Burp Exporter + SQLite 持久化 + 去重 + Retention

### 8.1 动机

如果直接走 Actor 消息队列，为什么还需要 SQLite？

| 场景 | Actor 消息队列 | SQLite 缓存 |
|------|---------------|-------------|
| `send_http_request` | ✅ 完美 | ❌ 不需要 |
| `get_proxy_http_history` | ❌ 每次都查询 Burp API | ✅ 一次导出，多次查询 |
| `get_scanner_issues` | ❌ 同上 | ✅ 同上 |
| 断连后恢复 | ❌ 数据丢失 | ✅ 数据持久化 |
| Burp 重启 | ❌ 队列清空 | ✅ 缓存还在 |

**结论：** Actor 消息队列解决"写"的问题。SQLite 缓存解决"读"的问题。两者互补。

### 8.2 上下文去重（核心设计）

**问题：** Burp 爬虫/扫描器会反复请求同一 endpoint（如 `/api/check`），导致列表中出现几十行重复 URL，AI 的 token 和注意力全浪费了。

**方案：** dedup_key + hit_count + 去重窗口

```
导出时：
  URL: POST /api/login  body: {user,pass}
    ↓
  计算 dedup_key = sha256(method + "|" + url)
    ↓
  SELECT id FROM proxy_http_history
    WHERE dedup_key = ? AND exported_at > (NOW - 5min)
    ↓
  如果存在 → UPDATE hit_count = hit_count + 1, last_seen = NOW
  如果不存在 → INSERT 新行
```

**效果：**

```
去空前（Burp 扫描产生的垃圾）：
  GET  /api/check  (第 1 次)
  GET  /api/check  (第 2 次, 重试)
  GET  /api/check  (第 3 次, 重试)
  POST /api/login
  GET  /api/check  (第 4 次, 重试)

去重后（AI 看到的）：
  GET  /api/check       hit_count: 4  ← 一行解决
  POST /api/login       hit_count: 1
```

**设计要点：**
- 去重窗口：5 分钟（可配）。超出窗口的同 URL 重新计数（可能是新的测试阶段）
- `hit_count` 让 AI 知道这个端点的热度——高命中 = 核心功能
- 详情查询 `get_proxy_http_detail(id)` 返回 hit_count 最多的那条记录

### 8.3 Retention Policy

**问题：** 不限制的话 SQLite 会无限增长，磁盘满、查询慢。

**方案：** 每次导出后自动清理超出配额的数据。

```sql
-- 保留最近 100,000 条 HTTP 历史
DELETE FROM proxy_http_history
WHERE id <= (SELECT id FROM proxy_http_history
             ORDER BY id DESC LIMIT 1 OFFSET 99999);

-- 保留最近 10,000 条扫描问题
DELETE FROM scanner_issues
WHERE id <= (SELECT id FROM scanner_issues
             ORDER BY id DESC LIMIT 1 OFFSET 9999);

-- 保留最近 10,000 条 WebSocket
DELETE FROM proxy_websocket_history
WHERE id <= (SELECT id FROM proxy_websocket_history
             ORDER BY id DESC LIMIT 1 OFFSET 9999);

-- BLOB 过期清理
DELETE FROM large_responses WHERE expires_at < datetime('now');
```

**配置：** 默认 100,000 条，可在 UI 中调整。

### 8.4 Exporter Actor 设计

Exporter 在 Phase C 定义的 `ExporterActor` 中实现：

```kotlin
class ExporterActor(
    private val api: MontoyaApi,
    private val db: Database,
    scope: CoroutineScope
) : Actor<Unit>(name = "exporter", scope) {

    private var lastHttpId = 0L
    private var lastIssueId = 0L
    private var lastWsId = 0L

    override suspend fun process(tick: Unit) {
        // 1. 增量导出 HTTP 历史
        val newHttpEntries = api.proxy().history()
            .filter { it.id() > lastHttpId }
        for (entry in newHttpEntries) {
            val dedupKey = sha256("${entry.method()}|${entry.url()}")
            if (isDuplicate(dedupKey)) {
                incrementHitCount(dedupKey)
            } else {
                insertWithTruncation(entry, dedupKey)
            }
        }
        if (newHttpEntries.isNotEmpty()) {
            lastHttpId = newHttpEntries.last().id()
        }

        // 2. 执行 Retention
        db.prune("proxy_http_history", config.maxRows)  // 默认 100000

        // 3. 导出 scanner issues + websocket（同理）
        // ...
    }

    private fun isDuplicate(dedupKey: String): Boolean {
        return db.query(
            "SELECT COUNT(*) FROM proxy_http_history WHERE dedup_key = ? AND exported_at > datetime('now', '-5 minutes')",
            dedupKey
        ) > 0
    }
}
```

**导出间隔：** 默认 30s，可配。通过 `Channel<Unit>` tick 驱动。

### 8.5 SQLite Schema（完整版）

```sql
-- HTTP 历史（带去重）
CREATE TABLE proxy_http_history (
    id INTEGER PRIMARY KEY,
    dedup_key TEXT NOT NULL,              -- sha256(method + "|" + url)
    url TEXT NOT NULL, method TEXT NOT NULL,
    host TEXT NOT NULL, port INTEGER NOT NULL, protocol TEXT NOT NULL,
    response_status INTEGER,
    content_type TEXT,                    -- 请求 Content-Type
    param_names TEXT,                     -- JSON 数组 ["userId","force"]
    request_headers TEXT, request_body TEXT,
    response_headers TEXT, response_body TEXT,
    mime_type TEXT, elapsed INTEGER,
    hit_count INTEGER DEFAULT 1,          -- 去重合并次数
    first_seen TEXT NOT NULL,             -- 首次导出时间
    last_seen TEXT NOT NULL,              -- 最近导出时间
    exported_at TEXT NOT NULL
);
CREATE INDEX idx_history_dedup ON proxy_http_history(dedup_key, exported_at);
CREATE INDEX idx_history_list ON proxy_http_history(method, url);

-- 扫描问题
CREATE TABLE scanner_issues (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL, severity TEXT NOT NULL,
    confidence TEXT NOT NULL, host TEXT NOT NULL,
    url TEXT NOT NULL, detail TEXT, remediation TEXT,
    exported_at TEXT NOT NULL
);

-- WebSocket 历史
CREATE TABLE proxy_websocket_history (
    id INTEGER PRIMARY KEY,
    url TEXT NOT NULL, direction TEXT NOT NULL,
    payload TEXT, payload_type TEXT NOT NULL,
    exported_at TEXT NOT NULL
);

-- 超大响应 BLOB
CREATE TABLE large_responses (
    id TEXT PRIMARY KEY,
    data BLOB NOT NULL,
    content_type TEXT NOT NULL,
    original_size INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);
CREATE INDEX idx_blob_expires ON large_responses(expires_at);

-- 导出状态跟踪
CREATE TABLE export_state (
    entity TEXT PRIMARY KEY,
    last_id INTEGER NOT NULL,
    exported_at TEXT NOT NULL
);
```

### 8.6 两级查询设计（核心创新）

**问题：** 当前 `get_proxy_http_history` 返回完整请求/响应，50 条记录就能塞爆 AI 上下文。
**解决方案：** 把查询拆为两级，让 AI 先"扫描"再"钻取"。

#### 第一级：列表扫描（超轻量，但信息密度高）

```
AI: "show me the proxy history"
→ list_proxy_http_history(count=30)
→ Returns (紧凑表格):
  ┌──────┬──────┬──────┬──────────────────────┬──────────────────┬────────────────────┬───────────┐
  │ id   │ meth │ stat │ url                  │ content_type     │ param_names        │ hits      │
  ├──────┼──────┼──────┼──────────────────────┼──────────────────┼────────────────────┼───────────┤
  │ 1    │ GET  │ 200  │ /api/login           │ application/json │ [callback]         │ 1         │
  │ 2    │ POST │ 200  │ /api/admin/user/del  │ application/json │ [userId, force]    │ 1         │
  │ 3    │ GET  │ 302  │ /upload/image        │ text/html        │ []                 │ 5         │
  │ 4    │ POST │ 200  │ /graphql             │ application/json │ [query]            │ 1         │
  │ 5    │ POST │ 500  │ /api/import          │ multipart/form   │ [file, format]     │ 1         │
  │ 6    │ GET  │ 403  │ /api/admin/users     │ text/html        │ [page, limit]      │ 12        │
  └──────┴──────┴──────┴──────────────────────┴──────────────────┴────────────────────┴───────────┘
  Response size: ~1.5KB for 30 entries (vs ~300KB for full data)
  💡 hits 列 = 该 endpoint 在去重窗口内被命中的次数，高热度 = 核心功能
```

**参数字段含义：**
- `content_type`：请求的 Content-Type，帮助 AI 快速区分 JSON / GraphQL / multipart / form
- `param_names`：参数名列表（仅名，不包含值）
  - GET 请求：URL query string 的 key 名
  - POST/PUT 请求：body 的顶层 key 名（JSON）或字段名（form）
  - **只暴露 param 名，不暴露 param 值**，防 token 浪费
- `hits`：去重窗口内命中次数，AI 可用它判断接口的核心程度

#### 第二级：详情钻取（按需）

```
AI: "show me detail for id=2 and id=4"
→ get_proxy_http_detail(ids=[2, 4])
→ Returns full request/response for each:
  Entry #2 POST /api/admin/user/delete (hit_count: 1)
    Request Headers:  {Content-Type: application/json, ...}
    Request Body:     {"userId": 123, "force": true}
    Response Status:  200
    Response Headers: {Content-Type: application/json, ...}
    Response Body:    {"success": true, "deletedRows": 5}

  Entry #4 POST /graphql (hit_count: 1)
    Request Headers:  {Content-Type: application/json, ...}
    Request Body:     {"query": "mutation { deleteUser(id: 123) { id } }"}
    Response Status:  200 OK
    Response Headers: ...
    Response Body:    {"data": {"deleteUser": {"id": 123}}}
```

### 8.7 工具迁移

| 旧工具 | 新工具 | 数据源 | 说明 |
|--------|--------|--------|------|
| `get_proxy_http_history` | → `list_proxy_http_history` | SQLite（轻量列表） | id + method + status + url + content_type + param_names + hits |
| — | **新增** `get_proxy_http_detail` | SQLite（完整数据） | 按 ID 数组查询，body 截断 + 略去重后的内容 |
| `get_scanner_issues` | → `list_scanner_issues` | SQLite | id + name + severity + url |
| — | **新增** `get_scanner_issue_detail` | SQLite | 完整 detail + remediation |
| `get_proxy_websocket_history` | → `list_proxy_websocket_history` | SQLite | id + url + direction |
| — | **新增** `get_proxy_websocket_detail` | SQLite | 完整 payload |
| `send_http_request` | → `submit_task` | Actor 消息队列 | HttpActor |
| `create_repeater_tab` | → `submit_task` | Actor 消息队列 | HttpActor |
| `send_to_intruder` | → `submit_task` | Actor 消息队列 | IntruderActor |
| 大响应（>100KB） | → `read_blob` | SQLite BLOB | 自动切换，不通知 AI |
| `url_encode/decode` | 不变 | 本地 | 无依赖 |
| `base64_encode/decode` | 不变 | 本地 | 无依赖 |

### 8.8 AI 交互示例（完整版）

```
AI 扫描阶段:
  list_proxy_http_history(count=30)
  → 看到条目 #15: POST /api/admin/user/delete (params: [userId, force], hits: 3)
    条目 #16: POST /graphql (params: [query], hits: 1)

AI 钻取阶段:  
  get_proxy_http_detail(ids=[15, 16])
  → 发现 /api/admin/user/delete 没有鉴权
  → 发现 /graphql 启用了内省查询

AI 操作阶段:
  submit_task(type="send_http_request", params={
    target: /api/admin/user/delete,
    method: POST,
    body: {"userId": 999, "force": true}
  })
  → get_task_result(task_id)
  → 返回 200, 成功删除 userId=999
```

**关键设计约束：**
- 列表工具**永远不**返回 body/headers 详情
- 详情工具**必须**按 ID 查询，不接受无条件查询
- body 截断是硬限制（可配，默认 8KB），超过截断 + 追加 `[truncated: X bytes]`
- 列表支持 `host`、`method`、`status`、`min_hits` 过滤（SQL WHERE）

### 8.9 文件

| 文件 | 说明 |
|------|------|
| **新建** `db/Database.kt` | SQLite 初始化 + 连接池 + 健康检查 |
| **新建** `db/SqliteReader.kt` | SQLite 查询（列表 + 详情），WHERE 过滤 |
| **新建** `db/SqliteWriter.kt` | ExporterActor 写入，INSERT / 去重 / UPDATE |
| **新建** `db/BlobStore.kt` | BLOB 读写 + 过期清理 |
| **新建** `db/Retention.kt` | 保留策略执行 |
| **内置** `actor/ExporterActor.kt` | Phase C 已建，这里实现导出逻辑 |
| **修改** `tools/ListTools.kt` | list_proxy_http_history 等列表工具 |
| **修改** `tools/DetailTools.kt` | get_proxy_http_detail 等详情工具 |

---

## 9. Phase E：UI 重做

### 左面板：从"无意义文案"改为"状态看板"

```
┌───────────────────────────────────────────┐
│  📊 MCP Server 状态                       │
│  ────────────────────────                  │
│  ● ● ● ●                                  │
│  Server  Exporter  AI     DB              │
│  运行中   运行中    已连接    OK             │
│                                          │
│  📋 消息队列                               │
│  ────────────────────────                  │
│  待处理:  3  ████████░░░░                │
│  已完成: 142 ████████████████████         │
│  失败:    1  ██░░                        │
│                                          │
│  📁 文件队列                               │
│  ────────────────────────                  │
│  待读取: 2  | 磁盘占用: 45MB              │
│  文件数: 12 | 最旧文件: 3m ago            │
│                                          │
│  💾 SQLite 缓存                            │
│  ────────────────────────                  │
│  HTTP 历史:   1,234 条                    │
│  扫描结果:    56 条                       │
│  WebSocket:   89 条                       │
│  上次导出:    5 秒前                       │
└──────────────────────────────────────────┘
```

### 右面板：原有的配置项

- **Enabled toggle** — 保留
- **Config Editing toggle** — 保留
- **消息队列设置** — 新增（并发数、超时）
- **文件队列设置** — 新增（临时目录、过期时间）
- **Exporter 设置** — 新增（导出间隔、截断大小）
- **高级选项** — 保留（Host/Port/Keepalive 现在都可编辑）
- **自动审批目标** — 保留
- **安装** — 保留

### 更新频率

- 队列状态：实时更新（每 1s 刷新）
- SQLite 缓存统计：每 5s 刷新
- Exporter 状态：每 5s 刷新
- AI Client 连接：实时更新

---

## 10. 路线图

```
Phase A (Bug 修复)       ████████████░░░░░   2-3 天
  A1-A6 TDD 驱动

Phase B (Host 可配置)    ██████░░░░░░░░░░░   1 天
  安全策略重构，Host 自由绑定

Phase C (消息+文件队列)   ██████████████░░░   3-4 天
  核心异步架构：MessageQueue + FileQueue

Phase D (Exporter+SQLite) ████████████░░░░░  3-4 天
  读缓存 + 持久化

Phase E (UI 重做)         ██████████░░░░░░░  2-3 天
  状态看板 + 配置面板优化
```

**执行顺序：** A → B → C → D → E

C（消息队列）是最核心的改动，做完就能解决大部分断连问题。D（SQLite 缓存）解决重复查询的性能问题。E（UI）留到最后，因为到那时所有数据源都有了。
