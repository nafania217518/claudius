package org.github.nafania217518.claudius.workspace

import org.github.nafania217518.claudius.chat.SessionStore
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.util.UUID

// M2: CRUD over the registry. One workspace = one dir under workspaces-dir + one chat thread.
@Service
class WorkspaceService(
    private val registry: WorkspaceRegistry,
    private val store: SessionStore,
) {
    fun list(): List<Workspace> = registry.list()

    fun create(name: String): Workspace {
        require(name.isNotBlank()) { "workspace name must not be blank" }
        require(!registry.nameExists(name)) { "a workspace named '$name' already exists" }
        val dir = sanitize(name)
        // M5: "." / ".claudius" resolve to the workspaces dir or its registry; a delete
        // there wipes everything. dirExists is case-insensitive so macOS cannot collide.
        require(!dir.startsWith(".")) { "workspace name '$name' maps to a hidden dir" }
        require(!registry.dirExists(dir)) { "a workspace dir '$dir' already exists" }
        Files.createDirectories(registry.resolveDir(dir))
        val ws = Workspace(UUID.randomUUID().toString(), name, dir, System.currentTimeMillis())
        registry.insert(ws)
        return ws
    }

    fun rename(id: String, newName: String): Workspace {
        require(newName.isNotBlank()) { "workspace name must not be blank" }
        require(registry.find(id) != null) { "no such workspace: $id" }
        require(!registry.nameExists(newName)) { "a workspace named '$newName' already exists" }
        registry.rename(id, newName)
        return registry.find(id)!!
    }

    // M11: the user's cap on the context window; null clears it.
    fun setContextLimit(id: String, limit: Long?): Workspace {
        require(limit == null || limit >= MIN_CONTEXT_LIMIT) { "context limit must be at least $MIN_CONTEXT_LIMIT tokens" }
        if (registry.find(id) == null) throw NoSuchElementException("no such workspace: $id")
        registry.setContextLimit(id, limit)
        return registry.find(id)!!
    }

    // M13: the workspace's permission mode.
    fun setPermissionMode(id: String, mode: PermissionMode): Workspace {
        if (registry.find(id) == null) throw NoSuchElementException("no such workspace: $id")
        registry.setPermissionMode(id, mode)
        return registry.find(id)!!
    }

    fun delete(id: String) {
        val ws = registry.find(id) ?: throw NoSuchElementException("no such workspace: $id")
        val dir = registry.resolveDir(ws.dir)
        if (Files.exists(dir)) {
            // ponytail: walkFileTree is post-order (children before parents) so the nested
            // .claudius/ clears cleanly; a plain Files.walk is pre-order and leaves the dir.
            Files.walkFileTree(
                 dir,
                 object : SimpleFileVisitor<Path>() {
                      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                          Files.deleteIfExists(file)
                          return FileVisitResult.CONTINUE
                       }
                      override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                          Files.deleteIfExists(d)
                          return FileVisitResult.CONTINUE
                       }
                  },
             )
        }
        registry.delete(id)
        store.drop(id)
    }

    private companion object {
        const val MIN_CONTEXT_LIMIT = 65536L
    }

    // ponytail: strip path-hostile chars; an all-stripped name falls back to a UUID.
    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "").ifEmpty { UUID.randomUUID().toString() }
}
