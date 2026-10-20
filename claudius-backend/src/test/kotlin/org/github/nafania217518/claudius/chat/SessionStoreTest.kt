package org.github.nafania217518.claudius.chat

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.assertFailsWith
import org.github.nafania217518.claudius.chat.dto.ToolInvocationDto
import org.github.nafania217518.claudius.workspace.Workspace
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

// M2: one session per workspace, backed by a per-workspace SQLite file. The store hands
// back MessageDto (wire shape); ChatService rebuilds Spring AI Message from role. Real
// SQLite in a temp dir, not a mock -- the round-trip IS the behaviour under test.
class SessionStoreTest {

      // Register one workspace whose dir lives under the temp workspaces dir, then wrap a
       // real store on it. wsDir defaults to the id so tests stay one-liners.
    private fun store(dir: Path, id: String, wsDir: String = id): SessionStore {
        val registry = WorkspaceRegistry(dir.toString())
        registry.insert(Workspace(id, id, wsDir, System.currentTimeMillis()))
        return SessionStore(registry, ObjectMapper())
        }

        // Pre-create a `message` table at the store's DB path with a given schema, then let a
        // fresh store open it. used to exercise the store's load-time migration.
        private fun preCreateLegacyTable(
            dir: Path,
            columns: String,
            seedNullTools: Boolean = false,
            ): SessionStore {
            val registry = WorkspaceRegistry(dir.toString())
            registry.insert(Workspace("w1", "w1", "w1", System.currentTimeMillis()))
            val db = registry.resolveDir("w1").resolve(".claudius").resolve("chat-history.db")
            Files.createDirectories(db.parent)
            val legacy =
                JdbcTemplate(SQLiteDataSource().apply { url = "jdbc:sqlite:${db.toAbsolutePath()}" })
            legacy.execute(
                """CREATE TABLE message ($columns)""".trimIndent())
            if (seedNullTools) {
                legacy.update(
                    "INSERT INTO message (role, content, created_at) VALUES (?, ?, ?)",
                    "assistant",
                    "hi",
                    System.currentTimeMillis(),
                  )
             }
            return SessionStore(registry, ObjectMapper())
         }

        @Test
    fun `appendUser adds a user turn to the history`(@TempDir dir: Path) {
        val store = store(dir, "w1")
        store.appendUser("w1", "hello")
        val history = store.history("w1")
        history shouldHaveSize 1
        history[0].role shouldBe "user"
        history[0].content shouldBe "hello"
         }

         @Test
    fun `appendAssistant stores a non-blank turn`(@TempDir dir: Path) {
        val store = store(dir, "w1")
        store.appendAssistant("w1", "Hi!")
        val history = store.history("w1")
        history shouldHaveSize 1
        history[0].role shouldBe "assistant"
        history[0].content shouldBe "Hi!"
          }

          @Test
    fun `appendAssistant ignores blank text so an empty reply does not pollute history`(@TempDir dir: Path) {
        val store = store(dir, "w1")
        store.appendAssistant("w1", "          ")
        store.history("w1").shouldBeEmpty()
          }

           // M3: a reasoning-only turn (blank answer, a trace) still persists -- the guard is
           // blank-AND-blank, so a trace alone keeps the row.
         @Test
    fun `a reasoning-only turn persists its trace even with a blank answer`(@TempDir dir: Path) {
        val store = store(dir, "w1")
        store.appendAssistant("w1", "", "thoughts")
        val history = store.history("w1")
        history shouldHaveSize 1
        history[0].role shouldBe "assistant"
        history[0].content shouldBe ""
        history[0].thinking shouldBe "thoughts"
          }

           // M5: the tools trace is a list of objects in a JSON TEXT column; "" means none.
          @Test
    fun `a tool trace round-trips through the store as objects`(@TempDir dir: Path) {
        val store = store(dir, "w1")
        val trace = listOf(ToolInvocationDto("call_1", "currentTime", "{}", "12:00:00Z"))
        store.appendAssistant("w1", "the time is now", tools = trace)
        store.history("w1")[0].tools shouldBe trace
         }

         @Test
    fun `an empty tools column reloads as an empty list`(@TempDir dir: Path) {
        val store = store(dir, "w1")
        store.appendAssistant("w1", "hi")
        store.history("w1")[0].tools shouldBe emptyList()
          }

          // M4: a pre-M4 DB predates the `tools` column; the store adds it once so a persisted
          // workspace still loads. The legacy table omits `tools`, so the ALTER branch runs.
          @Test
    fun `a pre-M4 DB without the tools column is migrated on load`(@TempDir dir: Path) {
        val store =
            preCreateLegacyTable(
                dir,
                """
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    thinking TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                    """,
             )
        store.appendUser("w1", "hello")
        store.history("w1")[0].tools shouldBe emptyList()
          }

          // A foreign schema that already has a nullable `tools` column holding a SQL NULL
          // degrades to "" on read -- the store never throws on a null trace.
          @Test
    fun `a null tools value in a legacy row degrades to empty on read`(@TempDir dir: Path) {
        val store =
            preCreateLegacyTable(
                dir,
                """
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    thinking TEXT NOT NULL DEFAULT '',
                    tools TEXT,
                    created_at INTEGER NOT NULL
                    """,
                seedNullTools = true,
             )
        store.history("w1")[0].tools shouldBe emptyList()
          }

          @Test
    fun `turns are appended in order across multiple rounds`(@TempDir dir: Path) {
        val store = store(dir, "w1")
        store.appendUser("w1", "q1")
        store.appendAssistant("w1", "a1")
        store.appendUser("w1", "q2")
        store.appendAssistant("w1", "a2")
        val history = store.history("w1")
        history shouldHaveSize 4
        history.map { it.content } shouldBe listOf("q1", "a1", "q2", "a2")
         }

         @Test
    fun `workspaces are isolated by id`(@TempDir dir: Path) {
        val store = store(dir, "w1")
        store(dir, "w2").appendUser("w2", "b")
        store.appendUser("w1", "a")
        store.history("w1") shouldHaveSize 1
        store.history("w2") shouldHaveSize 1
         }

         @Test
    fun `history returns a snapshot that does not leak internal storage`(@TempDir dir: Path) {
        val store = store(dir, "w1")
        store.appendUser("w1", "hello")
        val first = store.history("w1")
        store.appendUser("w1", "world")
        store.history("w1") shouldHaveSize 2
        first shouldHaveSize 1
         }


         @Test
    fun `history on an unknown workspace throws`(@TempDir dir: Path) {
        val store = SessionStore(WorkspaceRegistry(dir.toString()), ObjectMapper())
        assertFailsWith<NoSuchElementException> { store.history("nope") }
          }

    @Test
    fun `drop deletes the session db`(@TempDir dir: Path) {
        val registry = WorkspaceRegistry(dir.toString())
        registry.insert(Workspace("w1", "w1", "w1", 1L))
        val store = SessionStore(registry, ObjectMapper())
        store.appendUser("w1", "q1")
        store.drop("w1")
        Files.exists(registry.sessionDb("w1")) shouldBe false
        store.history("w1") shouldHaveSize 0
    }

    @Test
    fun `the session db lives outside the workspace dir`(@TempDir dir: Path) {
        val registry = WorkspaceRegistry(dir.toString())
        registry.insert(Workspace("w1", "w1", "w1", 1L))
        SessionStore(registry, ObjectMapper()).appendUser("w1", "hi")
        registry.sessionDb("w1") shouldBe dir.resolve(".claudius").resolve("sessions").resolve("w1.db")
        Files.exists(registry.sessionDb("w1")) shouldBe true
        Files.exists(registry.dirOf("w1").resolve(".claudius")) shouldBe false
    }

    @Test
    fun `a legacy in-workspace session db is moved on first use`(@TempDir dir: Path) {
        val first = store(dir, "w1")
        first.appendUser("w1", "old")
        first.drop("w1")
        // Rebuild the legacy layout: a db inside the workspace dir, none in the data dir.
        val registry = WorkspaceRegistry(dir.toString())
        val legacy = registry.dirOf("w1").resolve(".claudius").resolve("chat-history.db")
        Files.createDirectories(legacy.parent)
        JdbcTemplate(SQLiteDataSource().apply { url = "jdbc:sqlite:$legacy" }).execute(
            "CREATE TABLE message (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, " +
                "thinking TEXT NOT NULL DEFAULT '', tools TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)")
        JdbcTemplate(SQLiteDataSource().apply { url = "jdbc:sqlite:$legacy" })
            .update("INSERT INTO message (role, content, created_at) VALUES ('user', 'legacy', 1)")
        val store = SessionStore(registry, ObjectMapper())
        store.history("w1").single().content shouldBe "legacy"
        Files.exists(registry.sessionDb("w1")) shouldBe true
        Files.exists(registry.dirOf("w1").resolve(".claudius")) shouldBe false
    }

    @Test
    fun `an assistant turn with only a tool trace is persisted`(@TempDir dir: Path) {
        val s = store(dir, "w1")
        val trace = listOf(ToolInvocationDto("c1", "fileRead", "{}", "x"))
        s.appendAssistant("w1", "", "", trace)
        s.history("w1").single().tools shouldBe trace
    }

    @Test
    fun `audit rows round-trip in order`(@TempDir dir: Path) {
        val s = store(dir, "w1")
        s.appendAudit("w1", "fileRead", "{}", "allow")
        s.appendAudit("w1", "fileWrite", "{\"path\":\"a\"}", "rejected")
        s.audit("w1").map { Triple(it.tool, it.arguments, it.decision) } shouldBe
            listOf(Triple("fileRead", "{}", "allow"), Triple("fileWrite", "{\"path\":\"a\"}", "rejected"))
    }

    @Test
    fun `an old database gains the run table and the error column`(@TempDir dir: Path) {
        val store = preCreateLegacyTable(
            dir,
            "id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, thinking TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL",
            seedNullTools = true,
        )
        store.history("w1").single().error shouldBe ""
        store.saveRun("w1", "r1", "running", Checkpoint(answer = "a"))
        store.saveRun("w1", "r1", "waiting", Checkpoint(answer = "ab", pending = listOf(PendingCall("c", "n", "{}"))))
        store.runs("w1") shouldBe listOf(RunRow("r1", "waiting", Checkpoint(answer = "ab", pending = listOf(PendingCall("c", "n", "{}")))))
        // Old JSON (no error field) loads with the mapper that has no Kotlin module.
        ObjectMapper().readValue("""{"role":"user","content":"hi","thinking":"","createdAt":1,"tools":[],"model":""}""", org.github.nafania217518.claudius.chat.dto.MessageDto::class.java).error shouldBe ""
    }
}
