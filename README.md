# Burp Suite MCP Server -- 魔改增强版

[English](#english) | [中文](#中文)

---

<a id="english"></a>

# Burp Suite MCP Server -- Enhanced Edition

**If your AI keeps disconnecting or Burp freezes under load, you're in the right place.**

This is a hard fork of the official [PortSwigger mcp-server](https://github.com/PortSwigger/mcp-server). The official version has two unfixable design flaws that make it unusable in real work. This fork replaces both from the ground up.

## The Two Problems This Fork Fixes

### 1. AI Disconnects Every Few Minutes

The official server uses SSE (Server-Sent Events) -- a long-lived HTTP connection. SSE was never designed for request-response. Under load it drops. Heartbeat self-requests time out. A slow tool call blocks the event loop and kills the connection. You spend more time reconnecting than actually using the tool.

**What we did:** Replaced SSE with Streamable HTTP (MCP 2025-03-26 spec). A single POST endpoint. Pure request-response. No persistent connection means nothing to disconnect.

```
Official: SSE ----- keep alive ----- keep alive ----- drop
This:     POST -> done  POST -> done  POST -> done
```

### 2. Burp Freezes on Large Data

The official server calls Burp API in real time on every query. During real penetration testing, Burp accumulates thousands of proxy records. Every query blocks Burp's event loop. Burp becomes unresponsive or crashes. This is not a minor slowdown -- it makes the tool unusable past the first few requests.

**What we did:** Decoupled architecture. A background exporter polls Burp API incrementally and writes to local SQLite. MCP tools read from cache, not Burp API. Query time drops from seconds to milliseconds. Burp never blocks.

```
Official: AI query -> Burp API (real-time) -> Burp freezes
This:     AI query -> SQLite cache -> instant
                     ^
                Background exporter (incremental sync)
                     ^
                Burp API
```

### Other Problems That Got Fixed Along the Way

| Problem | Cause | Fix |
|---------|-------|-----|
| **Scanner results invisible to AI** | No scanner issue query API | Full scanner issue sync + query tools |
| **Large responses time out** | Huge HTTP body blocks the tool call | Async task queue + file-based chunked reading |
| **WSL / Docker / remote VM unreachable** | Hardcoded localhost check | `strictLocalhost` toggle |
| **Float-vs-int type mismatch** | AI sends `20.0` but server expects `20` | `normalizeJsonElement` auto-converts |

### What's In the Box

**SQLite Cache Layer**
- Proxy history and scanner issues cached locally
- Paginated queries, detail lookup by ID
- Incremental sync pulls only new data
- Clear cache selectively (all / HTTP only / scanner only)

**Background Exporter**
- Coroutine-driven polling, default every 5 seconds
- SHA-256 dedup (method + URL), 5-minute window merging
- Auto-prune at 100K HTTP records, 10K scanner issues

**Async Task System**
- `submit_task` -- enqueue and get a task ID immediately
- `get_task_result` -- poll for results
- `read_file` / `delete_file` -- manage large response files
- Task types: send HTTP request, create Repeater tab, send to Intruder

**Better UX**
- Real-time status dashboard -- server, exporter, queue, database at a glance
- Chinese UI -- all UI text in Chinese
- Restart button -- no need to reload the extension
- Auto-Approve management -- 4 tools to add/remove/list/clear auto-approve targets

## Quick Start

### Prerequisites

- **Java 21+** (mandatory -- proxy JAR targets Java 21)
- `jar` command available

### Build

```bash
git clone https://github.com/<your-fork>/burp-mcp-enhance
cd burp-mcp-enhance
./gradlew embedProxyJar
```

Output: `build/libs/burp-mcp-all.jar` (stdio proxy JAR embedded).

### Load into Burp

1. Open Burp Suite -> Extensions tab
2. Add -> Extension Type = Java
3. Select `build/libs/burp-mcp-all.jar` -> Next
4. Enable the server in Burp's MCP tab

### Configure MCP Client

The extension listens on `127.0.0.1:9876`.

#### Streamable HTTP (Recommended, MCP 2025-03-26)

Single POST endpoint. No persistent connections. Never disconnects.

```json
{
  "mcpServers": {
    "burp": {
      "type": "http",
      "url": "http://127.0.0.1:9876/mcp"
    }
  }
}
```

Works with Claude Desktop, Cursor, and any Streamable HTTP-capable client.

#### SSE (Legacy, less stable)

```json
{
  "mcpServers": {
    "burp": {
      "type": "sse",
      "url": "http://127.0.0.1:9876/sse"
    }
  }
}
```

#### stdio Proxy (for clients that only support stdio)

Uses the bundled `mcp-proxy-all.jar` as a stdio-SSE bridge. Requires Java 21:

```json
{
  "mcpServers": {
    "burp": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/mcp-proxy-all.jar",
        "--sse-url",
        "http://127.0.0.1:9876/sse"
      ]
    }
  }
}
```

> Extract the proxy JAR via Burp UI: "Extract Proxy Jar" button, or click "Install to Claude Desktop" for auto-configuration.

## Configuration

| Option | Description | Default |
|--------|-------------|---------|
| Server Host | Bind address | `127.0.0.1` |
| Server Port | Listen port | `9876` |
| Strict localhost | Disable for WSL/remote | On |
| Keepalive ping | SSE heartbeat | On |
| Keepalive interval | Heartbeat interval (s) | 30s |
| Max response size | Single response limit (KB) | 100KB |
| HTTP request approval | Confirm before sending HTTP | On |
| History access approval | Confirm before reading history | On |

## MCP Tools

### Core Tools
- `send_http1_request` -- Send HTTP/1.1 request
- `get_proxy_http_history` -- Get proxy HTTP history
- `get_websocket_history` -- Get WebSocket history
- `create_repeater_tab` -- Create Repeater tab
- `send_to_intruder` -- Send to Intruder
- `set_editor_text` -- Set editor content
- `set_selection` -- Set selected text
- `get_collaborator_payloads` -- Generate Collaborator payloads
- `get_collaborator_interactions` -- Query Collaborator interactions

### Data Query (cached)
- `list_proxy_http_history` -- Paginated HTTP records from local cache
- `get_proxy_http_detail` -- Full request/response detail
- `list_scanner_issues` -- Scanner issue summary
- `get_scanner_issue_detail` -- Full scanner issue detail
- `exporter_stats` -- Cache status

### Async Tasks
- `submit_task` -- Submit background task
- `get_task_result` -- Poll task result

### File Management
- `read_file` -- Read temp file
- `delete_file` -- Delete temp file

### Auto-Approve Management
- `add_auto_approve_target` -- Add auto-approve target
- `remove_auto_approve_target` -- Remove auto-approve target
- `list_auto_approve_targets` -- List all auto-approve targets
- `clear_auto_approve_targets` -- Clear all auto-approve targets

### Database
- `clear_database` -- Clear cache (all / HTTP / scanner)

## Architecture

```
+---------------------------------------------------+
|                   Burp Suite                       |
|  +----------------------------------------------+ |
|  |          MCP Server Extension                | |
|  |  +------------------+  +-------------------+ | |
|  |  | POST /mcp        |  | GET+POST /sse     | | |
|  |  | (Streamable HTTP)|  | (SSE legacy)      | | |
|  |  +------------------+  +-------------------+ | |
|  |  +-------------+  +-----------------------+  | |
|  |  | Exporter    |->|  SQLite Database      |  | |
|  |  | (background)|  |  (local cache)        |  | |
|  |  +-------------+  +-----------------------+  | |
|  +----------------------------------------------+ |
|          ^                  ^                     |
|   HTTP POST /mcp      SSE GET /sse                |
+----------+------------------+---------------------+
           |                  |
    +------+------+    +------+------+
    | MCP Client  |    | MCP Client  |
    | (Claude etc)|    | (legacy)    |
    +-------------+    +-------------+
```

## Development

Tools are defined under `src/main/kotlin/net/portswigger/mcp/tools/`. Add a new tool:

```kotlin
@Serializable
data class MyToolArgs(val param: String)

// Register in Tools.kt
mcpTool<MyToolArgs>("tool description") {
    // your logic
}
```

## Build Commands

| Command | Description |
|---------|-------------|
| `./gradlew embedProxyJar` | Build distributable JAR (proxy embedded) |
| `./gradlew test` | Run tests |
| `./gradlew shadowJar` | Build JAR only, no proxy |

---

<a id="中文"></a>

# Burp Suite MCP Server -- 魔改增强版

**AI 频繁断连？Burp 数据量大就卡死？这个版本把这两个问题彻底解决了。**

基于 PortSwigger 官方 [mcp-server](https://github.com/PortSwigger/mcp-server) 深度魔改。原版有两个设计层级的硬伤，在实际渗透测试中根本没法用。这个版本从底层替换了这两套方案。

## 本版解决的两大问题

### 1. AI 几分钟就断一次

原版用 SSE（Server-Sent Events）长连接。SSE 本来就不是为请求-响应设计的，负载一高就断。自请求心跳超时、某次工具调用慢了卡住事件循环，连接直接挂。实际用起来大半时间在重连，而不是在真正用工具。

**怎么修的：** 替换成 Streamable HTTP（MCP 2025-03-26 新标准）。一个 POST 端点，纯请求-响应。没有长连接，永远不会"断连"。

```
原版：   SSE ----- 保活 ----- 保活 ----- 断开
本版：   POST -> 结束  POST -> 结束  POST -> 结束
```

### 2. Burp 数据量大直接卡死

原版每次查询都实时调 Burp API。挖洞时 Burp 里成百上千条代理记录，查一次卡一次。Burp 事件循环被阻塞，界面无响应甚至崩溃。这不是"有点慢"的问题，是超过几十条请求就直接不能用了。

**怎么修的：** 解耦架构。后台导出器轮询 Burp API，增量写入本地 SQLite。MCP 工具读缓存，不走 Burp API。查询从秒级降到毫秒级。Burp 永远不阻塞。

```
原版： AI 查询 -> Burp API（实时）-> Burp 卡死
本版： AI 查询 -> SQLite 缓存 -> 毫秒返回
                 ^
            后台导出器（增量同步）
                 ^
            Burp API
```

### 顺带修了的其他问题

| 痛点 | 原版根因 | 本版改进 |
|------|---------|---------|
| **扫描结果查不了** | 没提供扫描查询接口 | 全量扫描问题同步 + 查询工具 |
| **大响应直接超时** | 返回结果太大阻塞调用 | 异步任务队列 + 文件分块读取 |
| **WSL/Docker/远程用不了** | 写死了 localhost 检查 | `strictLocalhost` 开关 |
| **参数类型对不上** | AI 发 `20.0` 但系统要 `20` | `normalizeJsonElement` 自动转 |

### 功能一览

**SQLite 缓存层**
- 代理 HTTP 历史 + 扫描问题自动缓存到本地 SQLite
- 分页查询、按 ID 获取详情
- SHA-256 去重（method + URL），5 分钟窗口合并
- 自动清理：10 万 HTTP 记录、1 万扫描问题

**后台导出器**
- 协程驱动后台轮询，默认每 5 秒同步一次
- 游标增量同步，只拉取新数据
- 自动去重，支持扫描问题同步

**异步任务系统**
- `submit_task` -- 提交后台任务，立即返回 ID
- `get_task_result` -- 轮询获取结果
- `read_file` / `delete_file` -- 管理大文件
- 支持 HTTP 请求、创建 Repeater、发送到 Intruder

**更好的 UI**
- 实时状态仪表板 -- 服务器、导出器、队列、数据库一目了然
- 全中文界面
- 重启按钮 -- 不需要重载扩展
- Auto-Approve 管理 -- 4 个工具管理自动放行列表

## 快速开始

### 前提条件

- **Java 21+**（必须，代理 JAR 编译目标 Java 21）
- `jar` 命令可用

### 构建

```bash
git clone https://github.com/<your-fork>/burp-mcp-enhance
cd burp-mcp-enhance
./gradlew embedProxyJar
```

产物：`build/libs/burp-mcp-all.jar`（内嵌 stdio 代理）。

### 加载到 Burp

1. 打开 Burp Suite -> Extensions 标签
2. Add -> Extension Type = Java
3. 选择 `build/libs/burp-mcp-all.jar` -> Next
4. 在 Burp 的 MCP 标签页启用服务器

### 配置 MCP 客户端

扩展启动后在 `127.0.0.1:9876` 提供服务。

#### Streamable HTTP（推荐，MCP 2025-03-26 新标准）

一个 POST 端点，无持久连接，永不掉线：

```json
{
  "mcpServers": {
    "burp": {
      "type": "http",
      "url": "http://127.0.0.1:9876/mcp"
    }
  }
}
```

适用于 Claude Desktop、Cursor 等支持 Streamable HTTP 的客户端。

#### SSE 直连（向后兼容，稳定性较差）

```json
{
  "mcpServers": {
    "burp": {
      "type": "sse",
      "url": "http://127.0.0.1:9876/sse"
    }
  }
}
```

#### stdio 代理（仅支持 stdio 的旧客户端）

使用内置 `mcp-proxy-all.jar` 桥接 stdio-SSE。需要 Java 21：

```json
{
  "mcpServers": {
    "burp": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/mcp-proxy-all.jar",
        "--sse-url",
        "http://127.0.0.1:9876/sse"
      ]
    }
  }
}
```

> 可在 Burp UI 中点击"提取服务器代理 jar"获取，或点击"安装到 Claude Desktop"自动配置。

## 配置说明

| 选项 | 说明 | 默认值 |
|------|------|--------|
| 服务器主机 | 监听地址 | `127.0.0.1` |
| 服务器端口 | 监听端口 | `9876` |
| 严格 localhost 模式 | WSL/远程环境需关闭 | 开启 |
| 启用保活心跳 | SSE 连接保活 | 开启 |
| 保活间隔 | 心跳间隔（秒） | 30s |
| 最大响应大小 | 单次响应上限（KB） | 100KB |
| HTTP 请求审批 | 发送 HTTP 前需确认 | 开启 |
| 历史记录访问审批 | 读取历史前需确认 | 开启 |

## MCP 工具清单

### 核心工具
- `send_http1_request` -- 发送 HTTP/1.1 请求
- `get_proxy_http_history` -- 获取代理 HTTP 历史
- `get_websocket_history` -- 获取 WebSocket 历史
- `create_repeater_tab` -- 创建 Repeater 标签
- `send_to_intruder` -- 发送到 Intruder
- `set_editor_text` -- 设置编辑器内容
- `set_selection` -- 设置选中文本
- `get_collaborator_payloads` -- 生成 Collaborator 负载
- `get_collaborator_interactions` -- 查询 Collaborator 交互

### 数据查询工具（需缓存）
- `list_proxy_http_history` -- 从本地缓存分页列出 HTTP 记录
- `get_proxy_http_detail` -- 获取完整请求/响应详情
- `list_scanner_issues` -- 列出扫描问题摘要
- `get_scanner_issue_detail` -- 获取扫描问题完整详情
- `exporter_stats` -- 查看缓存状态

### 异步任务工具
- `submit_task` -- 提交后台任务
- `get_task_result` -- 查询任务结果

### 文件管理工具
- `read_file` -- 读取临时文件
- `delete_file` -- 删除临时文件

### Auto-Approve 管理工具
- `add_auto_approve_target` -- 添加自动放行目标
- `remove_auto_approve_target` -- 移除自动放行目标
- `list_auto_approve_targets` -- 列出所有自动放行目标
- `clear_auto_approve_targets` -- 清除所有自动放行目标

### 数据库管理工具
- `clear_database` -- 清除缓存（全部/HTTP 历史/扫描问题）

## 架构说明

```
+---------------------------------------------------+
|                   Burp Suite                       |
|  +----------------------------------------------+ |
|  |          MCP Server Extension                | |
|  |  +------------------+  +-------------------+ | |
|  |  | POST /mcp        |  | GET+POST /sse     | | |
|  |  | (Streamable HTTP)|  | (SSE 旧版)        | | |
|  |  +------------------+  +-------------------+ | |
|  |  +-------------+  +-----------------------+  | |
|  |  | Exporter    |->|  SQLite Database      |  | |
|  |  | (后台同步)   |  |  (本地缓存)           |  | |
|  |  +-------------+  +-----------------------+  | |
|  +----------------------------------------------+ |
|          ^                  ^                     |
|   HTTP POST /mcp      SSE GET /sse                |
+----------+------------------+---------------------+
           |                  |
    +------+------+    +------+------+
    | MCP Client  |    | MCP Client  |
    | (Claude等)  |    | (旧版/代理) |
    +-------------+    +-------------+
```

## 开发

工具定义在 `src/main/kotlin/net/portswigger/mcp/tools/`，新增工具只需创建 `@Serializable` 数据类并注册：

```kotlin
@Serializable
data class MyToolArgs(val param: String)

// 在 Tools.kt 中注册
mcpTool<MyToolArgs>("工具描述") {
    // 处理逻辑
}
```

## 构建命令

| 命令 | 说明 |
|------|------|
| `./gradlew embedProxyJar` | 构建可分发的 JAR（含内嵌代理） |
| `./gradlew test` | 运行测试 |
| `./gradlew shadowJar` | 仅构建 JAR 本体，不含代理 |
