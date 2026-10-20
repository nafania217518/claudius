package org.github.nafania217518.claudius.chat.dto

// runId is a client-generated UUID; a websocket `stop` frame stops that run.
data class ChatRequest(
    val workspaceId: String,
    val message: String,
    val runId: String,
    // M10: null = the workspace's last choice (or the first provider's default model).
    val providerId: String? = null,
    val model: String? = null,
    // M11: true = compact the context before this turn.
    val compact: Boolean = false,
)
