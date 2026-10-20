package org.github.nafania217518.claudius.chat.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

// A single message row, one per turn. thinking is the model's reasoning trace (M3); tools is
// the turn's tool-call/result trace (M4, objects since M5). Both are display-only — reload
// renders them, they are never fed back into model context (see ChatService.toMessage).
// ponytail: a single JSON column per turn, not per-tool rows; upgrade when per-tool history
// (re-streaming tool context, or a tool-result the user edits) actually matters.
data class MessageDto(
    val role: String,
    val content: String,
    val thinking: String = "",
    val createdAt: Long,
    val tools: List<ToolInvocationDto> = emptyList(),
    // M10: "<provider name>/<model>" that answered; "" for user turns and pre-M10 rows.
    val model: String = "",
    // M11: the context size (tokens) before and after a compaction; summary rows only.
    // Long? for the same reason as ToolInvocationDto.compacted; omitted from the wire when null.
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val tokensBefore: Long? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val tokensAfter: Long? = null,
    // M12: the turn's error (provider failure, iteration cap, restart). Display-only, never replayed.
    // Omitted from the wire when empty; an old JSON without it (null) loads as "".
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    @param:JsonSetter(nulls = Nulls.AS_EMPTY)
    val error: String = "",
)
