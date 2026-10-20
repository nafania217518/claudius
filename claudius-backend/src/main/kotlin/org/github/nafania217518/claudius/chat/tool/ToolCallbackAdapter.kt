package org.github.nafania217518.claudius.chat.tool

import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition

// Bridges a claudius Tool to Spring AI's ToolCallback so the model request can
// advertise it. Only the definition is used; the loop executes through ToolRegistry.run,
// which knows the workspace dir and the permission gate, so call() refuses.
class ToolCallbackAdapter(val tool: Tool) : ToolCallback {
    private val definition =
        DefaultToolDefinition.builder()
            .name(tool.name)
            .description(tool.description)
            .inputSchema(tool.inputSchema)
            .build()

    override fun getToolDefinition() = definition

    override fun call(toolInput: String): String =
        throw UnsupportedOperationException("claudius runs tools through ToolRegistry")
}
