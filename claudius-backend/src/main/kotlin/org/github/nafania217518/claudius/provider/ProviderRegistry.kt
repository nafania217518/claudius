package org.github.nafania217518.claudius.provider

import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.sqlite.SQLiteDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.util.UUID

// M10: the provider table, in the registry DB file next to the workspace table.
// An empty table is seeded once with the configured Ollama (spring.ai.ollama.*).
// The seed gets created_at 0, so it stays first even when a create triggers the seeding.
// ponytail: its own JdbcTemplate over the same file; SQLite serializes the writers.
@Component
class ProviderRegistry(
    registry: WorkspaceRegistry,
    @Value("\${spring.ai.ollama.base-url:http://localhost:11434}") private val seedUrl: String,
    @Value("\${spring.ai.ollama.chat.model:qwen3.8:27b-mlx}") private val seedModel: String,
) {
    private val jdbc: JdbcTemplate by lazy {
        val ds = SQLiteDataSource().apply { url = "jdbc:sqlite:${registry.dataDir().resolve("workspaces.db")}" }
        JdbcTemplate(ds).also {
            it.execute(
                """
                CREATE TABLE IF NOT EXISTS provider (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE,
                    type TEXT NOT NULL,
                    base_url TEXT NOT NULL,
                    api_key TEXT NOT NULL,
                    default_model TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent())
            // A provider's models and their limits (null = unknown).
            it.execute(
                """
                CREATE TABLE IF NOT EXISTS provider_model (
                    provider_id TEXT NOT NULL,
                    model TEXT NOT NULL,
                    context_window INTEGER,
                    max_output_tokens INTEGER,
                    PRIMARY KEY (provider_id, model)
                )
                """.trimIndent())
            if (it.queryForObject("SELECT COUNT(*) FROM provider", Int::class.java) == 0) {
                insert(it, Provider(UUID.randomUUID().toString(), "Ollama", ProviderType.OLLAMA, seedUrl, "", seedModel, 0))
            }
        }
    }

    fun list(): List<Provider> = jdbc.query("SELECT * FROM provider ORDER BY created_at", rowMapper)

    fun find(id: String): Provider? = jdbc.query("SELECT * FROM provider WHERE id = ?", rowMapper, id).singleOrNull()

    fun insert(p: Provider) = insert(jdbc, p)

    fun update(p: Provider) =
        jdbc.update(
            "UPDATE provider SET name = ?, type = ?, base_url = ?, api_key = ?, default_model = ? WHERE id = ?",
            p.name, p.type.name, p.baseUrl, p.apiKey, p.defaultModel, p.id,
        )

    // Also deletes the provider's models.
    fun delete(id: String) {
        jdbc.update("DELETE FROM provider_model WHERE provider_id = ?", id)
        jdbc.update("DELETE FROM provider WHERE id = ?", id)
    }

    fun models(id: String): List<ModelLimits> =
        jdbc.query(
            "SELECT model, context_window, max_output_tokens FROM provider_model WHERE provider_id = ? ORDER BY model",
            { rs, _ -> ModelLimits(rs.getString("model"), rs.intOrNull("context_window"), rs.intOrNull("max_output_tokens")) },
            id,
        )

    // ponytail: delete + insert, no transaction; a crash in between loses the list (re-fetchable).
    fun setModels(id: String, models: List<ModelLimits>) {
        jdbc.update("DELETE FROM provider_model WHERE provider_id = ?", id)
        models.forEach {
            jdbc.update("INSERT INTO provider_model VALUES (?, ?, ?, ?)", id, it.id, it.contextWindow, it.maxOutputTokens)
        }
    }

    private fun java.sql.ResultSet.intOrNull(col: String) = (getObject(col) as Number?)?.toInt()

    private fun insert(t: JdbcTemplate, p: Provider) =
        t.update(
            "INSERT INTO provider (id, name, type, base_url, api_key, default_model, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            p.id, p.name, p.type.name, p.baseUrl, p.apiKey, p.defaultModel, p.createdAt,
        )

    private val rowMapper = RowMapper<Provider> { rs, _ ->
        Provider(
            rs.getString("id"), rs.getString("name"), ProviderType.valueOf(rs.getString("type")),
            rs.getString("base_url"), rs.getString("api_key"), rs.getString("default_model"),
            rs.getLong("created_at"),
        )
    }
}
