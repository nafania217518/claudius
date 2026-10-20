package org.github.nafania217518.claudius.chat.tool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspacePathTest {
    @Test
    fun `a relative path resolves inside the workspace`(@TempDir dir: Path) {
        resolveInWorkspace(dir, "a/b.txt") shouldBe dir.toRealPath().resolve("a/b.txt")
    }

    @Test
    fun `a dot-dot path that escapes the workspace is rejected`(@TempDir dir: Path) {
        shouldThrow<IllegalArgumentException> { resolveInWorkspace(dir, "../x.txt") }
    }

    @Test
    fun `an absolute path outside the workspace is rejected`(@TempDir dir: Path) {
        shouldThrow<IllegalArgumentException> { resolveInWorkspace(dir.resolve("ws").also { Files.createDirectories(it) }, "/etc/passwd") }
    }

    @Test
    fun `a symlink that points outside the workspace is rejected`(@TempDir dir: Path) {
        val ws = Files.createDirectories(dir.resolve("ws"))
        val outside = Files.createDirectories(dir.resolve("outside"))
        Files.createSymbolicLink(ws.resolve("link"), outside)
        shouldThrow<IllegalArgumentException> { resolveInWorkspace(ws, "link/secret.txt") }
    }

    @Test
    fun `a path under the dot-claudius dir is rejected`(@TempDir dir: Path) {
        shouldThrow<IllegalArgumentException> { resolveInWorkspace(dir, ".claudius/chat-history.db") }
    }

    @Test
    fun `a not-yet-existing nested path inside the workspace resolves`(@TempDir dir: Path) {
        resolveInWorkspace(dir, "x/y/z.txt") shouldBe dir.toRealPath().resolve("x/y/z.txt")
    }

    // A dangling link does not "exist" when followed, so the nearest-existing-ancestor walk
    // used to stop at its parent and let fileWrite create the target outside the workspace.
    @Test
    fun `a dangling symlink that points outside the workspace is rejected`(@TempDir dir: Path) {
        val ws = Files.createDirectories(dir.resolve("ws"))
        Files.createSymbolicLink(ws.resolve("link.txt"), dir.resolve("outside.txt"))
        shouldThrow<IllegalArgumentException> { resolveInWorkspace(ws, "link.txt") }
    }
}
