package org.github.nafania217518.claudius.chat.tool

import org.springframework.ai.tool.ToolCallback
import org.springframework.stereotype.Component
import java.nio.file.Path

// The loop's permission decision for one tool name.
enum class Permission { ALLOW, ASK, DENY }

// The loop's tool surface. Two views over the same tools:
//   - callbacks(): every tool's ToolCallback, advertised to the model in the request.
//   - run(): the executor. An autoRun tool always runs; a known non-autoRun tool runs only
//     when approved; anything else is a failed tool_result. A tool exception never escapes.
// ponytail: Spring's ToolCallingManager resolved tools from the prompt options first, so
// its autoRun-only resolver was bypassed. We run tools ourselves instead.
@Component
class ToolRegistry(tools: List<Tool>) {
    private val callbacks: List<ToolCallback> = tools.map { ToolCallbackAdapter(it) }
    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    fun callbacks(): List<ToolCallback> = callbacks

    fun permission(name: String): Permission {
        val tool = byName[name] ?: return Permission.DENY
        return if (tool.autoRun) Permission.ALLOW else Permission.ASK
    }

    fun run(name: String, arguments: String, workspaceDir: Path, approved: Boolean = false,
            onOutput: (String) -> Unit = {}): String {
        val tool = byName[name]?.takeIf { it.autoRun || approved }
            ?: return "error: tool '$name' is not available"
        return try {
            tool.run(arguments, workspaceDir, onOutput).ifEmpty { "(no output)" }
        } catch (e: Exception) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
