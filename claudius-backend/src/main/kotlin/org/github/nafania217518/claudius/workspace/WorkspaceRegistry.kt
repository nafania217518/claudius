package org.github.nafania217518.claudius.workspace

import org.sqlite.SQLiteDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

// M2: the id→(name, dir) registry. One SQLite db at <workspaces-dir>/.claudius/workspaces.db
// — a hidden dir OUTSIDE the workspace dirs, so deleting a workspace dir never loses the registry.
// ponytail: single JdbcTemplate + SQLiteDataSource, no pooling — fine for chat volume; a shared
// pool / R2DBC if throughput matters.
@Component
class WorkspaceRegistry(
    @Value("\${claudius.workspaces-dir:./workspaces}") dir: String,
) {
    private val workspacesDir: Path = Path.of(dir)

    private val jdbc: JdbcTemplate by lazy {
        val ds = SQLiteDataSource().apply { url = "jdbc:sqlite:${dataDir().resolve("workspaces.db")}" }
        JdbcTemplate(ds).also {
            it.execute(
                 """
                 CREATE TABLE IF NOT EXISTS workspace (
                     id TEXT PRIMARY KEY,
                     name TEXT NOT NULL UNIQUE,
                     dir TEXT NOT NULL UNIQUE,
                     created_at INTEGER NOT NULL
                 )
                 """.trimIndent())
            // M10: a pre-M10 DB lacks the last-model columns; add them once.
            val cols = it.queryForList("PRAGMA table_info(workspace)").map { c -> c["name"] }
            if ("last_provider_id" !in cols) {
                it.execute("ALTER TABLE workspace ADD COLUMN last_provider_id TEXT")
                it.execute("ALTER TABLE workspace ADD COLUMN last_model TEXT")
            }
            // M11: the context size of the workspace's model context.
            if ("context_tokens" !in cols) {
                it.execute("ALTER TABLE workspace ADD COLUMN context_tokens INTEGER NOT NULL DEFAULT 0")
            }
            // M11: the user's context limit (NULL = none).
            if ("context_limit" !in cols) {
                it.execute("ALTER TABLE workspace ADD COLUMN context_limit INTEGER")
            }
            // M13: the permission mode.
            if ("permission_mode" !in cols) {
                it.execute("ALTER TABLE workspace ADD COLUMN permission_mode TEXT NOT NULL DEFAULT 'ASK'")
            }
        }
    }

    // M8: claudius's own data (registry, session DBs, bash temp dirs). Outside every workspace
    // dir, so a sandboxed command (rw on its workspace only) cannot touch history or audit.
    fun dataDir(): Path = Files.createDirectories(workspacesDir.resolve(".claudius"))

    fun sessionDb(id: String): Path = dataDir().resolve("sessions").resolve("$id.db")

    fun list(): List<Workspace> =
        jdbc.query("SELECT * FROM workspace ORDER BY created_at", rowMapper)

    fun find(id: String): Workspace? =
        jdbc.query("SELECT * FROM workspace WHERE id = ?", rowMapper, id).singleOrNull()

    fun insert(ws: Workspace) =
        jdbc.update(
             "INSERT INTO workspace (id, name, dir, created_at) VALUES (?, ?, ?, ?)",
             ws.id, ws.name, ws.dir, ws.createdAt,
        )

    fun rename(id: String, name: String) =
        jdbc.update("UPDATE workspace SET name = ? WHERE id = ?", name, id)

    // M10: remember the workspace's last provider/model choice.
    fun setLastModel(id: String, providerId: String, model: String) =
        jdbc.update("UPDATE workspace SET last_provider_id = ?, last_model = ? WHERE id = ?", providerId, model, id)

    // M11: remember the context size after a turn or compaction.
    fun setContextTokens(id: String, tokens: Long) =
        jdbc.update("UPDATE workspace SET context_tokens = ? WHERE id = ?", tokens, id)

    fun setContextLimit(id: String, limit: Long?) =
        jdbc.update("UPDATE workspace SET context_limit = ? WHERE id = ?", limit, id)

    // M13: the workspace's permission mode.
    fun setPermissionMode(id: String, mode: PermissionMode) =
        jdbc.update("UPDATE workspace SET permission_mode = ? WHERE id = ?", mode.name, id)

    fun delete(id: String) =
        jdbc.update("DELETE FROM workspace WHERE id = ?", id)

    fun nameExists(name: String): Boolean =
        jdbc.query(
             "SELECT name FROM workspace WHERE name = ?",
             { rs, _ -> rs.getString("name") },
             name,
        ).isNotEmpty()

    // Case-insensitive: macOS/Windows file systems fold case, so "Foo" and "foo" share a dir.
    fun dirExists(dir: String): Boolean =
        jdbc.query(
             "SELECT dir FROM workspace WHERE LOWER(dir) = LOWER(?)",
             { rs, _ -> rs.getString("dir") },
             dir,
        ).isNotEmpty()

    // ponytail: dir is sanitized at create, but re-check it can't escape the workspaces dir —
    // delete walks the tree, so a traversal here would be a data-loss bug.
    fun resolveDir(dir: String): Path {
        val resolved = workspacesDir.resolve(dir).normalize()
        require(resolved.startsWith(workspacesDir.normalize())) { "workspace dir escapes workspaces dir: $dir" }
        require(resolved != workspacesDir.normalize()) { "workspace dir is the workspaces dir itself: $dir" }
        return resolved
    }

    // The registered workspace's dir, through the traversal guard.
    fun dirOf(id: String): Path =
        resolveDir(find(id)?.dir ?: throw NoSuchElementException("unknown workspace: $id"))

    private val rowMapper = RowMapper<Workspace> { rs, _ ->
        Workspace(rs.getString("id")!!, rs.getString("name")!!, rs.getString("dir")!!, rs.getLong("created_at"),
            rs.getString("last_provider_id"), rs.getString("last_model"), rs.getLong("context_tokens"),
            (rs.getObject("context_limit") as Number?)?.toLong(), PermissionMode.valueOf(rs.getString("permission_mode")!!))
    }
}
