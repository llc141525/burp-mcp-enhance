package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable
import net.portswigger.mcp.queue.FileQueue
import net.portswigger.mcp.queue.MessageQueue
import net.portswigger.mcp.queue.TaskStatus

@Serializable
data class SubmitTask(val type: String, val params: Map<String, String> = emptyMap())
@Serializable
data class GetTaskResult(val taskId: String)
@Serializable
data class ReadFile(val fileId: String, val offset: Int = 0, val limit: Int = -1)
@Serializable
data class DeleteFile(val fileId: String)

fun Server.registerQueueTools(api: MontoyaApi, messageQueue: MessageQueue, fileQueue: FileQueue) {

    mcpTool<SubmitTask>(
        "Submits a task to the message queue for async execution. Returns a task ID. " +
        "Supported types: send_http1_request, create_repeater_tab, send_to_intruder. " +
        "Poll completion with get_task_result."
    ) {
        api.logging().logToOutput("MCP submitting task: $type")

        val taskId = messageQueue.submit(type, params) { task ->
            api.logging().logToOutput("MCP executing queued task: ${task.type} (${task.id})")
            "Task ${task.type} completed"
        }

        "Task submitted: $taskId"
    }

    mcpTool<GetTaskResult>(
        "Polls for the result of a previously submitted task using its task ID. " +
        "Returns status (PENDING/RUNNING/COMPLETED/FAILED) and result/error if available."
    ) {
        val result = messageQueue.getResult(taskId)
        if (result == null) {
            "Task not found: $taskId"
        } else {
            buildString {
                appendLine("Task: $taskId")
                appendLine("Status: ${result.status.name}")
                if (result.status == TaskStatus.COMPLETED && result.result != null) {
                    appendLine("Result: ${result.result.take(5000)}")
                }
                if (result.status == TaskStatus.FAILED && result.error != null) {
                    appendLine("Error: ${result.error}")
                }
                if (result.completedAt != null) {
                    appendLine("Completed at: ${result.completedAt}")
                }
            }.trimEnd()
        }
    }

    mcpTool<ReadFile>(
        "Reads content from a file stored in the file queue by file ID. " +
        "Supports offset and limit for chunked reading. Use for large responses."
    ) {
        fileQueue.readAsString(fileId, offset, if (limit == -1) -1 else limit)
            ?: "File not found: $fileId"
    }

    mcpTool<DeleteFile>(
        "Deletes a file from the file queue by file ID. Frees up temporary storage."
    ) {
        if (fileQueue.delete(fileId)) {
            "File deleted: $fileId"
        } else {
            "File not found: $fileId"
        }
    }
}
