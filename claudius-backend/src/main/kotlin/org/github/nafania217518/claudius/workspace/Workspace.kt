package org.github.nafania217518.claudius.workspace

// M13: what a tool call still needs a click for. ASK: every write, edit and command.
// ACCEPT_EDITS: writes and edits run, commands ask. BYPASS: nothing asks (the sandbox still applies).
enum class PermissionMode { ASK, ACCEPT_EDITS, BYPASS }

// One workspace = one working dir + one chat thread. `dir` is the name inside the
// workspaces dir (not an absolute path); the registry/service resolve it to an
// absolute path from `claudius.workspaces-dir`.
data class Workspace(
    val id: String,
    val name: String,
    val dir: String,
    val createdAt: Long,
    // M10: the provider/model the last chat used; null until the first chat.
    val lastProviderId: String? = null,
    val lastModel: String? = null,
    // M11: the size (tokens) of the model context after the last turn or compaction.
    val contextTokens: Long = 0,
    // M11: the user's cap on the context window (tokens); null = the model's window only.
    val contextLimit: Long? = null,
    // M13: the workspace's permission mode.
    val permissionMode: PermissionMode = PermissionMode.ASK,
)
