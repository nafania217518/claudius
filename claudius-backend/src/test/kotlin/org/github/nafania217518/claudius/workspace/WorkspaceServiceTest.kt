package org.github.nafania217518.claudius.workspace

import io.kotest.matchers.shouldBe
import kotlin.test.assertFailsWith
import org.github.nafania217518.claudius.chat.SessionStore
import org.github.nafania217518.claudius.workspace.dto.toDto
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

// M2: real registry + store over a temp workspaces dir (not mocks) — the CRUD/persistence
// round-trip is the behaviour under test. Same-package, so Workspace/Registry/Service are
// unqualified; only the chat-pkg SessionStore is imported.
class WorkspaceServiceTest {

     // One registry shared by the service and store, so a delete's store.drop() and the
     // service's registry.delete() hit the same dir + db.
    private fun setup(dir: Path): Triple<WorkspaceService, WorkspaceRegistry, SessionStore> {
        val registry = WorkspaceRegistry(dir.toString())
        val store = SessionStore(registry, ObjectMapper())
        return Triple(WorkspaceService(registry, store), registry, store)
     }

    @Test
    fun `create makes a directory and a registry row`(@TempDir dir: Path) {
        val (service, registry, _) = setup(dir)
        val ws = service.create("proj")
        registry.find(ws.id) shouldBe ws
        Files.isDirectory(dir.resolve("proj")) shouldBe true
     }

    @Test
    fun `create sanitizes the dir but keeps the display name`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        val ws = service.create("my/proj: 1")
        ws.name shouldBe "my/proj: 1"
        ws.dir shouldBe "myproj1"
     }

    @Test
    fun `create falls back to a uuid dir when the name strips to empty`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        val ws = service.create("///")
        ws.dir.length shouldBe 36
        Files.isDirectory(dir.resolve(ws.dir)) shouldBe true
     }

    @Test
    fun `create rejects a blank name`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        assertFailsWith<IllegalArgumentException> { service.create("   ") }
     }

    @Test
    fun `create rejects a duplicate name`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        service.create("dup")
        assertFailsWith<IllegalArgumentException> { service.create("dup") }
     }

     // "." survives sanitize, so ".." reaches resolveDir, whose traversal guard throws.
     @Test
    fun `create rejects a dir that escapes the workspaces dir`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        assertFailsWith<IllegalArgumentException> { service.create("..") }
     }

     // M5: "." sanitizes to itself and resolves to the workspaces dir; a delete there would
     // wipe every workspace and the registry. Any dot-prefixed dir is refused.
     @Test
    fun `a workspace named a single dot is rejected`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        assertFailsWith<IllegalArgumentException> { service.create(".") }
     }

     @Test
    fun `a name that sanitizes to a dot-prefixed dir is rejected`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        assertFailsWith<IllegalArgumentException> { service.create(".claudius") }
     }

     @Test
    fun `resolveDir rejects the workspaces dir itself`(@TempDir dir: Path) {
        val (_, registry, _) = setup(dir)
        assertFailsWith<IllegalArgumentException> { registry.resolveDir(".") }
     }

     // The UNIQUE dir column used to surface as an unmapped 500; now a 400-mapped require.
     @Test
    fun `two names that sanitize to the same dir are rejected`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        service.create("a b")
        assertFailsWith<IllegalArgumentException> { service.create("ab") }
     }

     @Test
    fun `names differing only by case are rejected`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        service.create("Foo")
        assertFailsWith<IllegalArgumentException> { service.create("foo") }
     }

    @Test
    fun `list returns the created workspaces`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        service.create("a")
        service.create("b")
        service.list().map { it.name }.toSet() shouldBe setOf("a", "b")
     }

     // Rename touches the display name only; the on-disk dir (and its .claudius history) stays.
     @Test
    fun `rename changes the display name but not the dir`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        val ws = service.create("old")
        service.rename(ws.id, "new")
        service.list().single().let {
            it.name shouldBe "new"
            it.dir shouldBe "old"
        }
     }

     @Test
    fun `rename rejects a blank name`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        val ws = service.create("keep")
        assertFailsWith<IllegalArgumentException> { service.rename(ws.id, "   ") }
     }

     @Test
    fun `rename rejects a duplicate name`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        val ws1 = service.create("first")
        service.create("second")
        assertFailsWith<IllegalArgumentException> { service.rename(ws1.id, "second") }
     }

     @Test
    fun `rename of an unknown id throws`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        assertFailsWith<IllegalArgumentException> { service.rename("nope", "x") }
     }

     // A live store forces the .claudius dir inside the workspace, so delete walks a real tree.
     @Test
    fun `delete wipes the directory, the row, and the cached template`(@TempDir dir: Path) {
        val (service, registry, store) = setup(dir)
        val ws = service.create("proj")
        store.appendUser(ws.id, "hi")
        service.delete(ws.id)
        registry.find(ws.id) shouldBe null
        Files.exists(dir.resolve("proj")) shouldBe false
     }

     @Test
    fun `delete of a workspace whose dir is missing drops the row`(@TempDir dir: Path) {
        val (service, registry, _) = setup(dir)
        registry.insert(Workspace("ghost", "ghost", "ghost", 1L))
        service.delete("ghost")
        registry.find("ghost") shouldBe null
     }

     @Test
    fun `delete of an unknown id throws`(@TempDir dir: Path) {
        val (service, _, _) = setup(dir)
        assertFailsWith<NoSuchElementException> { service.delete("nope") }
     }

    @Test
    fun `dirOf returns the resolved dir of a registered workspace`(@TempDir dir: Path) {
        val (service, registry, _) = setup(dir)
        val ws = service.create("Alpha")
        registry.dirOf(ws.id) shouldBe registry.resolveDir(ws.dir)
    }

    @Test
    fun `dirOf throws NoSuchElementException for an unknown workspace`(@TempDir dir: Path) {
        val (_, registry, _) = setup(dir)
        assertFailsWith<NoSuchElementException> { registry.dirOf("nope") }.message shouldBe "unknown workspace: nope"
    }

    // M10: a pre-M10 registry DB gets the last-model columns on first use.
    @Test
    fun `an old workspace database gains the last model columns`(@TempDir dir: Path) {
        val data = Files.createDirectories(dir.resolve(".claudius"))
        java.sql.DriverManager.getConnection("jdbc:sqlite:${data.resolve("workspaces.db")}").use {
            it.createStatement().execute(
                "CREATE TABLE workspace (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, dir TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL)")
            it.createStatement().execute("INSERT INTO workspace VALUES ('w1', 'old', 'old', 1)")
        }
        val registry = WorkspaceRegistry(dir.toString())
        registry.find("w1")!!.lastModel shouldBe null
        registry.setLastModel("w1", "p1", "m1")
        registry.find("w1")!!.let {
            it.lastProviderId shouldBe "p1"
            it.lastModel shouldBe "m1"
        }
    }

    // M13: the permission mode.
    @Test
    fun `a workspace starts in ASK permission mode`(@TempDir dir: Path) {
        val (service, registry, _) = setup(dir)
        val ws = service.create("proj")
        ws.permissionMode shouldBe PermissionMode.ASK
        ws.toDto().permissionMode shouldBe PermissionMode.ASK
        service.setPermissionMode(ws.id, PermissionMode.BYPASS).permissionMode shouldBe PermissionMode.BYPASS
        registry.find(ws.id)!!.permissionMode shouldBe PermissionMode.BYPASS
        assertFailsWith<NoSuchElementException> { service.setPermissionMode("nope", PermissionMode.ASK) }
    }

    @Test
    fun `an old registry without the permission_mode column gets it on startup`(@TempDir dir: Path) {
        val data = Files.createDirectories(dir.resolve(".claudius"))
        java.sql.DriverManager.getConnection("jdbc:sqlite:${data.resolve("workspaces.db")}").use {
            it.createStatement().execute(
                "CREATE TABLE workspace (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, dir TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, last_provider_id TEXT, last_model TEXT, context_tokens INTEGER NOT NULL DEFAULT 0, context_limit INTEGER)")
            it.createStatement().execute("INSERT INTO workspace (id, name, dir, created_at) VALUES ('w1', 'old', 'old', 1)")
        }
        val registry = WorkspaceRegistry(dir.toString())
        registry.find("w1")!!.permissionMode shouldBe PermissionMode.ASK
        registry.setPermissionMode("w1", PermissionMode.ACCEPT_EDITS)
        registry.find("w1")!!.permissionMode shouldBe PermissionMode.ACCEPT_EDITS
    }
}
