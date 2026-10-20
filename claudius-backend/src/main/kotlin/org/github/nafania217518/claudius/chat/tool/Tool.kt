package org.github.nafania217518.claudius.chat.tool

import java.nio.file.Path

// M4: claudius's own Tool seam, layered on Spring AI's ToolCallback. Spring AI carries
// no read-only/concurrency/destructive flags (ToolMetadata is only returnDirect), so the
// fail-closed safety flags live here: a new tool is treated as unsafe until it proves
// otherwise. ToolRegistry.run admits only autoRun tools, so a non-autoRun tool is
// refused → a failed tool_result, never a silently-run side effect.
// M7: a non-autoRun tool runs only after the user approves it (ToolRegistry.permission = ASK).
interface Tool {
    val name: String
    val description: String
    val inputSchema: String

    // Fail-closed defaults: a mutating tool must flip these explicitly.
    val isReadOnly: Boolean get() = false
    val isConcurrencySafe: Boolean get() = false
    val isDestructive: Boolean get() = true

    // The loop may invoke this with no permission gate: nothing to read, nothing to
    // corrupt, nothing to destroy. This is the seam M5's gate checks.
    val autoRun: Boolean get() = isReadOnly && isConcurrencySafe && !isDestructive

    // The tool body. arguments is the model-supplied JSON ("" for a no-arg tool);
    // workspaceDir is the workspace root; paths resolve through resolveInWorkspace.
    // Returns the string the loop feeds back as a tool_result.
    // M8: onOutput takes live output deltas (bash); other tools ignore it.
    fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit = {}): String
}
