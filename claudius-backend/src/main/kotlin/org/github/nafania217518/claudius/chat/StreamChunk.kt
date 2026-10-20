package org.github.nafania217518.claudius.chat

// The firehose the SSE controller emits: a tagged chunk the controller names an event by.
// ponytail: one string payload per kind keeps the controller a pure kind→event map; the
// TOOL_CALL/PERMISSION/TOOL_OUTPUT/TOOL_RESULT kinds carry a pre-serialized JSON in `text` so the controller never
// touches the tool shape. ERROR carries plain error text; the stream still completes after it.
data class StreamChunk(val kind: Kind, val text: String = "") {
    enum class Kind { TOKEN, REASONING, TOOL_CALL, PERMISSION, TOOL_OUTPUT, TOOL_RESULT, ERROR, COMPACTING, COMPACTED, USAGE }
}
