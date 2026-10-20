package org.github.nafania217518.claudius.chat.tool

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// M8: the real Landlock policy. Linux + landrun only: `./gradlew landlockTest`.
@Tag("landlock")
class BashLandlockTest {
    private fun setup(dir: Path): Pair<Bash, WorkspaceRegistry> {
        val registry = WorkspaceRegistry(dir.toString())
        return Bash(registry, "landrun", "landrun", 30) to registry
    }

    private fun cmd(c: String) = """{"command":${tools.jackson.databind.ObjectMapper().writeValueAsString(c)}}"""

    @Test
    fun `a sandboxed command reads and writes its own workspace`(@TempDir dir: Path) {
        val (bash, _) = setup(dir)
        val ws = Files.createDirectories(dir.resolve("a"))
        Files.writeString(ws.resolve("in.txt"), "hello")
        bash.run(cmd("cat in.txt > out.txt && cat out.txt"), ws) shouldBe "hello"
        Files.readString(ws.resolve("out.txt")) shouldBe "hello"
    }

    @Test
    fun `a sandboxed command cannot read another workspace`(@TempDir dir: Path) {
        val (bash, _) = setup(dir)
        val a = Files.createDirectories(dir.resolve("a"))
        val b = Files.createDirectories(dir.resolve("b"))
        Files.writeString(b.resolve("secret.txt"), "s3cret")
        bash.run(cmd("cat ../b/secret.txt"), a) shouldContain "Permission denied"
    }

    @Test
    fun `a sandboxed command cannot read the claudius data dir`(@TempDir dir: Path) {
        val (bash, registry) = setup(dir)
        val a = Files.createDirectories(dir.resolve("a"))
        Files.writeString(registry.dataDir().resolve("x.db"), "history")
        bash.run(cmd("cat ${registry.dataDir().toRealPath()}/x.db"), a) shouldContain "Permission denied"
    }

    @Test
    fun `a sandboxed command cannot open a TCP connection`(@TempDir dir: Path) {
        val (bash, _) = setup(dir)
        val a = Files.createDirectories(dir.resolve("a"))
        bash.run(cmd("bash -c 'exec 3<>/dev/tcp/1.1.1.1/80' && echo CONNECTED"), a) shouldContain "[exit code"
    }

    @Test
    fun `the sandbox probe passes on a Landlock kernel`(@TempDir dir: Path) {
        val (bash, _) = setup(dir)
        val a = Files.createDirectories(dir.resolve("a"))
        bash.run(cmd("echo ok"), a) shouldBe "ok\n"
    }
}
