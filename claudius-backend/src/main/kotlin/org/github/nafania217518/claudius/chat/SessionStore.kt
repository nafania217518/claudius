package org.github.nafania217518.claudius.chat

import org.github.nafania217518.claudius.chat.dto.AuditDto
import org.github.nafania217518.claudius.chat.dto.MessageDto
import org.github.nafania217518.claudius.chat.dto.ToolInvocationDto
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.sqlite.SQLiteDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

// One session per workspace: the workspace IS the session, so each workspace keeps its
// own session DB at registry.sessionDb(id) — M8: outside the workspace dir, so a sandboxed
// bash command cannot rewrite history or audit. The store hands back wire DTOs, not Spring AI
// Message — ChatService rebuilds Message from role.
// ponytail: one SQLiteDataSource/JdbcTemplate per workspace, no pooling, serialized
// writes — fine for chat volume; a shared pool / R2DBC + a session table for M3+.
// M3: the message table also keeps a `thinking` column — the reasoning trace for an
// assistant turn ("" for user turns and thinking-off models).
// M4: a `tools` column holds the turn's tool-call/result trace as a JSON array ("" when
// none), display-only like thinking. M5: it is parsed to/from List<ToolInvocationDto>.
// M11: a stored message row; coversUntil is set on summary rows only.
// M12: a run's progress. pending = the current round's calls not yet executed.
data class PendingCall(val id: String = "", val name: String = "", val arguments: String = "")

data class Checkpoint(
    val providerId: String = "",
    val model: String = "",
    val answer: String = "",
    val thinking: String = "",
    val trace: List<ToolInvocationDto> = emptyList(),
    val pending: List<PendingCall> = emptyList(),
)

internal data class RunRow(val runId: String, val status: String, val checkpoint: Checkpoint)

internal data class Row(val id: Long, val coversUntil: Long?, val dto: MessageDto)

@Component
class SessionStore(private val registry: WorkspaceRegistry, private val mapper: ObjectMapper) {
    private val templates = ConcurrentHashMap<String, JdbcTemplate>()

    private fun template(workspaceId: String): JdbcTemplate =
        templates.computeIfAbsent(workspaceId) {
            val db = registry.sessionDb(workspaceId)
            // M8: pre-M8 DBs lived inside the workspace dir; move one out on first use.
            val legacy = registry.dirOf(workspaceId).resolve(".claudius")
            Files.createDirectories(db.parent)
            if (!Files.exists(db) && Files.exists(legacy.resolve("chat-history.db"))) {
                Files.move(legacy.resolve("chat-history.db"), db)
                runCatching { Files.delete(legacy) } // only if empty
            }
            val ds = SQLiteDataSource().apply { url = "jdbc:sqlite:${db.toAbsolutePath()}" }
            JdbcTemplate(ds).also {
                it.execute(
                        """
                     CREATE TABLE IF NOT EXISTS message (
                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                         role TEXT NOT NULL,
                         content TEXT NOT NULL,
                         thinking TEXT NOT NULL DEFAULT '',
                         tools TEXT NOT NULL DEFAULT '',
                         model TEXT NOT NULL DEFAULT '',
                         created_at INTEGER NOT NULL
                       )
                       """.trimIndent())
                // M7: an append-only log of every tool decision.
                it.execute(
                    """
                    CREATE TABLE IF NOT EXISTS audit (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        tool TEXT NOT NULL,
                        arguments TEXT NOT NULL,
                        decision TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent())
                   // ponytail: a pre-M4 DB predates the `tools` column; add it once so a
                   // persisted workspace loads. A fresh table already has it, so the
                   // ALTER is the no-op branch there.
                if (it.queryForList("PRAGMA table_info(message)")
                         .none { c -> c["name"] == "tools" }
                    ) {
                    it.execute("ALTER TABLE message ADD COLUMN tools TEXT NOT NULL DEFAULT ''")
                }
                // M10: same for the `model` column.
                if (it.queryForList("PRAGMA table_info(message)").none { c -> c["name"] == "model" }) {
                    it.execute("ALTER TABLE message ADD COLUMN model TEXT NOT NULL DEFAULT ''")
                }
                // M11: summary rows record the last row id they cover.
                if (it.queryForList("PRAGMA table_info(message)").none { c -> c["name"] == "covers_until" }) {
                    it.execute("ALTER TABLE message ADD COLUMN covers_until INTEGER")
                }
                // M12: the turn's error text.
                if (it.queryForList("PRAGMA table_info(message)").none { c -> c["name"] == "error" }) {
                    it.execute("ALTER TABLE message ADD COLUMN error TEXT NOT NULL DEFAULT ''")
                }
                // M12: one checkpoint row per run.
                it.execute("CREATE TABLE IF NOT EXISTS run (run_id TEXT PRIMARY KEY, status TEXT, checkpoint TEXT, updated_at INTEGER)")
                // M11: summary rows record the context size before and after.
                for (c in listOf("tokens_before", "tokens_after")) {
                    if (it.queryForList("PRAGMA table_info(message)").none { r -> r["name"] == c }) {
                        it.execute("ALTER TABLE message ADD COLUMN $c INTEGER")
                    }
                }
            }
        }

      // Ordered by id (insertion order = transcript order); the frontend's load path.
    // M11: summary rows included.
    fun history(workspaceId: String): List<MessageDto> = rows(workspaceId).map { it.dto }

    // M11: rows with their id and covers_until (summary rows only), in id order.
    internal fun rows(workspaceId: String): List<Row> =
        template(workspaceId).query(
            "SELECT id, covers_until, tokens_before, tokens_after, role, content, thinking, tools, model, error, created_at FROM message ORDER BY id",
            { rs, _ ->
                Row(
                    rs.getLong("id"),
                    (rs.getObject("covers_until") as Number?)?.toLong(),
                    MessageDto(
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("thinking"),
                        rs.getLong("created_at"),
                        parseTools(rs.getString("tools")),
                        rs.getString("model"),
                        (rs.getObject("tokens_before") as Number?)?.toLong(),
                        (rs.getObject("tokens_after") as Number?)?.toLong(),
                        rs.getString("error"),
                    ),
                )
            },
        )

    // M11: a summary row that covers every row up to coversUntil. Returns the row.
    fun appendSummary(workspaceId: String, text: String, coversUntil: Long, model: String = "", before: Long? = null, after: Long? = null): MessageDto {
        val dto = MessageDto("summary", text, "", System.currentTimeMillis(), emptyList(), model, before, after)
        template(workspaceId).update(
            "INSERT INTO message (role, content, model, covers_until, tokens_before, tokens_after, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            dto.role, text, model, coversUntil, before, after, dto.createdAt,
        )
        return dto
    }

    fun appendUser(workspaceId: String, text: String) =
        template(workspaceId).update(
                "INSERT INTO message (role, content, created_at) VALUES (?, ?, ?)",
                "user", text, System.currentTimeMillis(),
            )

      // Skips an empty turn (no answer, no thinking, no tools) so a stream that emits nothing
      // leaves no row — but a reasoning-only turn (answer blank, trace present) persists.
    fun appendAssistant(
        workspaceId: String,
        content: String,
        thinking: String = "",
        tools: List<ToolInvocationDto> = emptyList(),
        model: String = "",
        error: String = "",
    ) {
        if (content.isBlank() && thinking.isBlank() && tools.isEmpty() && error.isBlank()) return
        val trace = if (tools.isEmpty()) "" else mapper.writeValueAsString(tools)
        template(workspaceId).update(
                "INSERT INTO message (role, content, thinking, tools, model, error, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "assistant", content, thinking, trace, model, error, System.currentTimeMillis(),
            )
     }

    // M12: upsert the run's status + checkpoint.
    @Synchronized
    internal fun saveRun(workspaceId: String, runId: String, status: String, checkpoint: Checkpoint) {
        template(workspaceId).update(
            "INSERT OR REPLACE INTO run (run_id, status, checkpoint, updated_at) VALUES (?, ?, ?, ?)",
            runId, status, mapper.writeValueAsString(checkpoint), System.currentTimeMillis(),
        )
    }

    // ponytail: done/interrupted rows are kept forever; prune them if the table ever matters.
    internal fun runs(workspaceId: String): List<RunRow> =
        template(workspaceId).query("SELECT run_id, status, checkpoint FROM run ORDER BY updated_at", { rs, _ ->
            RunRow(rs.getString("run_id"), rs.getString("status"), mapper.readValue(rs.getString("checkpoint"), Checkpoint::class.java))
        })

    // M9: parallel tool calls audit concurrently; one writer at a time avoids SQLITE_BUSY.
    @Synchronized
    fun appendAudit(workspaceId: String, tool: String, arguments: String, decision: String) =
        template(workspaceId).update(
            "INSERT INTO audit (tool, arguments, decision, created_at) VALUES (?, ?, ?, ?)",
            tool, arguments, decision, System.currentTimeMillis(),
        )

    fun audit(workspaceId: String): List<AuditDto> =
        template(workspaceId).query(
            "SELECT tool, arguments, decision, created_at FROM audit ORDER BY id",
            { rs, _ -> AuditDto(rs.getString("tool"), rs.getString("arguments"), rs.getString("decision"), rs.getLong("created_at")) },
        )

      // "" / NULL (legacy rows) → no trace.
    private fun parseTools(json: String?): List<ToolInvocationDto> =
        if (json.isNullOrEmpty()) emptyList()
        else mapper.readValue(json, mapper.typeFactory.constructCollectionType(List::class.java, ToolInvocationDto::class.java))

      // A deleted workspace drops its cached template; a re-add rebuilds it.
    // M8: also deletes the session DB file, which no longer goes with the workspace dir.
    fun drop(workspaceId: String) {
        templates.remove(workspaceId)
        Files.deleteIfExists(registry.sessionDb(workspaceId))
    }
}
