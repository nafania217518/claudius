package org.github.nafania217518.claudius.chat.tool

import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

// M4: the one read-only tool. Proves the loop end-to-end with zero security surface —
// no file, no sandbox, no approval. No args (empty object schema), so the JSON the model
// supplies is ignored.
@Component
class CurrentTime : Tool {
    override val name = "currentTime"
    override val description = "Return the current date and time as an ISO-8601 UTC string."
    override val inputSchema = """{"type":"object","properties":{},"required":[]}"""
    override val isReadOnly = true
    override val isConcurrencySafe = true
    override val isDestructive = false

    override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String =
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
}
