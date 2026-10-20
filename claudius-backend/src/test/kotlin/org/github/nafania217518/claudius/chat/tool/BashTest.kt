package org.github.nafania217518.claudius.chat.tool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// M8: sandbox=none, so these run on macOS and Linux. The Landlock policy itself is in
// BashLandlockTest (tag "landlock", Linux only).
class BashTest {
    private fun bash(dir: Path, timeout: Long = 120, sandbox: String = "none", landrun: String = "landrun") =
        Bash(WorkspaceRegistry(dir.resolve("ws").toString()), sandbox, landrun, timeout)

    private fun cmd(c: String) = """{"command":${tools.jackson.databind.ObjectMapper().writeValueAsString(c)}}"""

    private fun ws(dir: Path): Path = Files.createDirectories(dir.resolve("ws").resolve("a"))

    @Test
    fun `bash returns the command output`(@TempDir dir: Path) {
        bash(dir).run(cmd("echo hi"), ws(dir)) shouldBe "hi\n"
    }

    @Test
    fun `bash reports a non-zero exit code`(@TempDir dir: Path) {
        bash(dir).run(cmd("echo oops; exit 3"), ws(dir)) shouldBe "oops\n\n[exit code 3]"
        bash(dir).run(cmd("exit 2"), ws(dir)) shouldBe "[exit code 2]"
    }

    @Test
    fun `bash streams output chunks while the command runs`(@TempDir dir: Path) {
        var firstAt = 0L
        val chunks = mutableListOf<String>()
        bash(dir).run(cmd("echo first; sleep 1; echo second"), ws(dir)) {
            if (firstAt == 0L) firstAt = System.nanoTime()
            synchronized(chunks) { chunks += it }
        }
        val endAt = System.nanoTime()
        chunks.first() shouldBe "first\n"
        ((endAt - firstAt) > 500_000_000L) shouldBe true
    }

    @Test
    fun `bash caps long output`(@TempDir dir: Path) {
        val out = bash(dir).run(cmd("head -c 40000 /dev/zero | tr '\\000' a"), ws(dir))
        out shouldStartWith "a".repeat(30_000) + "\n[output truncated: 40000 chars total]"
        out.length shouldBe 30_000 + "\n[output truncated: 40000 chars total]".length
    }

    @Test
    fun `bash kills a command and its children when it exceeds the timeout`(@TempDir dir: Path) {
        val ws = ws(dir)
        val start = System.currentTimeMillis()
        val out = bash(dir, timeout = 1).run(cmd("sleep 30 & echo \$! > child.pid; sleep 30"), ws)
        (System.currentTimeMillis() - start < 10_000) shouldBe true
        out shouldEndWith "[timed out after 1s]"
        val pid = Files.readString(ws.resolve("child.pid")).trim().toLong()
        val end = System.currentTimeMillis() + 3000
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.currentTimeMillis() < end) Thread.sleep(50)
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false) shouldBe false
    }

    @Test
    fun `bash runs in the workspace dir`(@TempDir dir: Path) {
        val ws = ws(dir)
        bash(dir).run(cmd("pwd -P"), ws).trim() shouldBe ws.toRealPath().toString()
    }

    @Test
    fun `bash does not pass the backend environment to the command`(@TempDir dir: Path) {
        val names = bash(dir).run(cmd("env"), ws(dir)).lines().filter { "=" in it }.map { it.substringBefore("=") }
        val shellOwn = setOf("PWD", "OLDPWD", "SHLVL", "_")
        names.filter { it !in shellOwn }.sorted() shouldContainExactly listOf("HOME", "LANG", "PATH", "TMPDIR")
    }

    @Test
    fun `the per-command temp dir is removed after the command`(@TempDir dir: Path) {
        val tmp = Path.of(bash(dir).run(cmd("touch \"\$TMPDIR/x\"; echo \$TMPDIR"), ws(dir)).trim())
        tmp.parent shouldBe dir.resolve("ws").resolve(".claudius").resolve("tmp")
        Files.exists(tmp) shouldBe false
    }

    @Test
    fun `bash is not autoRun`(@TempDir dir: Path) {
        bash(dir).autoRun shouldBe false
        ToolRegistry(listOf(bash(dir))).permission("bash") shouldBe Permission.ASK
    }

    @Test
    fun `bash requires a command argument`(@TempDir dir: Path) {
        shouldThrow<IllegalArgumentException> { bash(dir).run("{}", ws(dir)) }.message shouldBe "missing argument: command"
    }

    @Test
    fun `the landrun argv allows only the workspace, a per-command temp dir and system dirs`() {
        landrunArgv("/bin/landrun", Path.of("/w"), Path.of("/t"), "ls") shouldBe listOf(
            "/bin/landrun", "--best-effort", "--ignore-missing",
            "--rox", "/usr", "--rox", "/bin", "--rox", "/lib", "--rox", "/lib64", "--rox", "/sbin",
            "--ro", "/etc", "--rw", "/dev/null", "--rw", "/t", "--rw", "/w",
            "--env", "PATH", "--env", "HOME=/t", "--env", "TMPDIR=/t", "--env", "LANG=C.UTF-8",
            "--", "sh", "-c", "ls",
        )
    }

    @Test
    fun `a missing landrun binary makes bash fail closed`(@TempDir dir: Path) {
        val b = bash(dir, sandbox = "landrun", landrun = "/nonexistent/landrun")
        ToolRegistry(listOf(b)).run("bash", cmd("echo hi"), ws(dir), approved = true) shouldContain "sandbox unavailable"
    }
}
