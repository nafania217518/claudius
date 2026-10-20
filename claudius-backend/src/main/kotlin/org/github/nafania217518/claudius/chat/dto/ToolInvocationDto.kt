package org.github.nafania217518.claudius.chat.dto

import com.fasterxml.jackson.annotation.JsonInclude

// M5: one tool call or result, on the wire and in the store. A TOOL_CALL event leaves
// result "", a TOOL_RESULT leaves arguments ""; the persisted trace merges both.
data class ToolInvocationDto(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String,
    // M11: true = folded into a mid-turn summary: shown in the transcript, never replayed.
    // ponytail: Boolean? (null = no), not Boolean: the mapper has no Kotlin module, so a trace
    // stored before M11 (no field) cannot default a primitive. Omitted from the wire when null.
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val compacted: Boolean? = null,
    // Thinking and answer lengths when the model made this call. The UI uses them to restore the order.
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val thinkingAt: Int? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val answerAt: Int? = null,
)
