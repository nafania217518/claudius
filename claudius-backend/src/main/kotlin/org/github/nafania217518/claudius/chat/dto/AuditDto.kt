package org.github.nafania217518.claudius.chat.dto

// M7: one tool decision: allow / deny / approved / rejected.
data class AuditDto(val tool: String, val arguments: String, val decision: String, val createdAt: Long)
