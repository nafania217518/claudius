package org.github.nafania217518.claudius.workspace.dto

import org.github.nafania217518.claudius.workspace.PermissionMode
import org.github.nafania217518.claudius.workspace.Workspace

// M1 idiom: plain Jackson DTOs (not kotlinx.serialization).
data class WorkspaceDto(
    val id: String,
    val name: String,
    val dir: String,
    val createdAt: Long,
    val lastProviderId: String? = null,
    val lastModel: String? = null,
    val contextTokens: Long = 0,
    val contextLimit: Long? = null,
    val permissionMode: PermissionMode = PermissionMode.ASK,
)

data class CreateWorkspaceRequest(val name: String)

data class RenameWorkspaceRequest(val name: String)

data class ContextLimitRequest(val contextLimit: Long?)

data class PermissionModeRequest(val permissionMode: PermissionMode)

fun Workspace.toDto() = WorkspaceDto(id, name, dir, createdAt, lastProviderId, lastModel, contextTokens, contextLimit, permissionMode)
