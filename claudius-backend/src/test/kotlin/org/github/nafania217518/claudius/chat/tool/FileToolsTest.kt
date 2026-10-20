package org.github.nafania217518.claudius.chat.tool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// Real temp-dir workspaces: the file system IS the behaviour under test.
class FileToolsTest {
    private fun write(dir: Path, rel: String, text: String) =
        dir.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    @Test
    fun `fileRead returns the file content`(@TempDir dir: Path) {
        write(dir, "a.txt", "hello")
        FileRead().run("""{"path":"a.txt"}""", dir) shouldBe "hello"
    }

    @Test
    fun `fileRead truncates a large file`(@TempDir dir: Path) {
        write(dir, "big.txt", "x".repeat(100_005))
        val out = FileRead().run("""{"path":"big.txt"}""", dir)
        out shouldBe "x".repeat(100_000) + "\n[truncated: 100005 chars total]"
    }

    @Test
    fun `fileRead refuses a path outside the workspace`(@TempDir dir: Path) {
        shouldThrow<IllegalArgumentException> { FileRead().run("""{"path":"../a.txt"}""", dir) }
    }

    @Test
    fun `fileRead fails on a missing file`(@TempDir dir: Path) {
        shouldThrow<IllegalArgumentException> { FileRead().run("""{"path":"nope.txt"}""", dir) }
    }

    @Test
    fun `a missing argument is rejected`(@TempDir dir: Path) {
        shouldThrow<IllegalArgumentException> { FileRead().run("", dir) }.message shouldBe "missing argument: path"
    }

    @Test
    fun `glob matches by file name when the pattern has no slash`(@TempDir dir: Path) {
        write(dir, "a.kt", ""); write(dir, "src/b.kt", ""); write(dir, "c.md", "")
        Glob().run("""{"pattern":"*.kt"}""", dir) shouldBe "a.kt\nsrc/b.kt"
    }

    @Test
    fun `glob matches a relative path pattern`(@TempDir dir: Path) {
        write(dir, "a.kt", ""); write(dir, "src/b.kt", "")
        Glob().run("""{"pattern":"src/*.kt"}""", dir) shouldBe "src/b.kt"
    }

    @Test
    fun `glob never lists the dot-claudius dir`(@TempDir dir: Path) {
        write(dir, ".claudius/chat-history.db", ""); write(dir, "a.db", "")
        Glob().run("""{"pattern":"*.db"}""", dir) shouldBe "a.db"
    }

    @Test
    fun `glob reports no matches`(@TempDir dir: Path) {
        Glob().run("""{"pattern":"*.kt"}""", dir) shouldBe "no matches"
    }

    @Test
    fun `glob truncates after 200 lines`(@TempDir dir: Path) {
        repeat(201) { write(dir, "f%03d.txt".format(it), "") }
        Glob().run("""{"pattern":"*.txt"}""", dir) shouldEndWith "f199.txt\n[truncated]"
    }

    @Test
    fun `grep returns path line and text for each match`(@TempDir dir: Path) {
        write(dir, "src/a.kt", "one\nfun two\nthree")
        Grep().run("""{"pattern":"fun \\w+"}""", dir) shouldBe "src/a.kt:2: fun two"
    }

    @Test
    fun `grep filters files by glob`(@TempDir dir: Path) {
        write(dir, "a.kt", "hit"); write(dir, "a.md", "hit")
        Grep().run("""{"pattern":"hit","glob":"*.kt"}""", dir) shouldBe "a.kt:1: hit"
    }

    @Test
    fun `grep skips a binary file`(@TempDir dir: Path) {
        Files.write(dir.resolve("bin.dat"), byteArrayOf(0xC3.toByte(), 0x28, 'h'.code.toByte(), 'i'.code.toByte()))
        write(dir, "big.txt", "hi\n" + "x".repeat(1_000_001))
        Grep().run("""{"pattern":"hi"}""", dir) shouldBe "no matches"
    }

    @Test
    fun `grep reports no matches`(@TempDir dir: Path) {
        write(dir, "a.kt", "nothing")
        Grep().run("""{"pattern":"zzz"}""", dir) shouldBe "no matches"
    }

    @Test
    fun `grep truncates after 200 lines`(@TempDir dir: Path) {
        write(dir, "a.txt", "hit\n".repeat(300))
        Grep().run("""{"pattern":"hit"}""", dir) shouldEndWith "a.txt:200: hit\n[truncated]"
    }

    @Test
    fun `fileWrite creates the file and its parent dirs`(@TempDir dir: Path) {
        FileWrite().run("""{"path":"x/y/z.txt","content":"héllo"}""", dir) shouldBe "wrote 6 bytes to x/y/z.txt"
        Files.readString(dir.resolve("x/y/z.txt")) shouldBe "héllo"
    }

    @Test
    fun `fileWrite is not autoRun`() {
        FileWrite().autoRun shouldBe false
        FileEdit().autoRun shouldBe false
        FileRead().autoRun shouldBe true
        Glob().autoRun shouldBe true
        Grep().autoRun shouldBe true
    }

    @Test
    fun `fileWrite refuses the dot-claudius dir`(@TempDir dir: Path) {
        shouldThrow<IllegalArgumentException> { FileWrite().run("""{"path":".claudius/x","content":"x"}""", dir) }
        Files.exists(dir.resolve(".claudius/x")) shouldBe false
    }

    @Test
    fun `fileEdit replaces a unique occurrence`(@TempDir dir: Path) {
        val f = write(dir, "a.txt", "foo bar baz")
        FileEdit().run("""{"path":"a.txt","old_string":"bar","new_string":"qux"}""", dir) shouldBe "edited a.txt"
        Files.readString(f) shouldBe "foo qux baz"
    }

    @Test
    fun `fileEdit fails when old_string is missing`(@TempDir dir: Path) {
        write(dir, "a.txt", "foo")
        shouldThrow<IllegalArgumentException> {
            FileEdit().run("""{"path":"a.txt","old_string":"bar","new_string":"x"}""", dir)
        }.message shouldBe "old_string not found in a.txt"
        shouldThrow<IllegalArgumentException> {
            FileEdit().run("""{"path":"a.txt","old_string":"","new_string":"x"}""", dir)
        }
    }

    @Test
    fun `fileEdit fails when old_string is not unique`(@TempDir dir: Path) {
        val f = write(dir, "a.txt", "ab ab")
        shouldThrow<IllegalArgumentException> {
            FileEdit().run("""{"path":"a.txt","old_string":"ab","new_string":"x"}""", dir)
        }.message shouldContain "occurs 2 times in a.txt"
        Files.readString(f) shouldNotContain "x"
    }

    // isRegularFile() follows links: a link inside the workspace must not leak a file outside it.
    @Test
    fun `grep does not follow a symlink to a file outside the workspace`(@TempDir dir: Path) {
        val ws = Files.createDirectories(dir.resolve("ws"))
        write(dir, "secret.txt", "token=abc")
        Files.createSymbolicLink(ws.resolve("link.txt"), dir.resolve("secret.txt"))
        Grep().run("""{"pattern":"token"}""", ws) shouldBe "no matches"
    }

    @Test
    fun `fileWrite does not write through a dangling symlink to outside the workspace`(@TempDir dir: Path) {
        val ws = Files.createDirectories(dir.resolve("ws"))
        Files.createSymbolicLink(ws.resolve("link.txt"), dir.resolve("outside.txt"))
        shouldThrow<IllegalArgumentException> { FileWrite().run("""{"path":"link.txt","content":"x"}""", ws) }
        Files.exists(dir.resolve("outside.txt")) shouldBe false
    }

    // Live bug (M9 smoke): Java's glob needs a "/" for "**/", so "**/*.kt" missed every file at the
    // workspace root and a sub-agent reported an empty workspace.
    @Test
    fun `glob double-star pattern matches files at the workspace root`(@TempDir dir: Path) {
        write(dir, "a.kt", ""); write(dir, "src/b.kt", ""); write(dir, "c.md", "")
        Glob().run("""{"pattern":"**/*.kt"}""", dir) shouldBe "a.kt\nsrc/b.kt"
    }

    @Test
    fun `grep double-star glob filter matches files at the workspace root`(@TempDir dir: Path) {
        write(dir, "a.kt", "val x = 1"); write(dir, "src/b.kt", "val x = 2")
        Grep().run("""{"pattern":"val x","glob":"**/*.kt"}""", dir) shouldBe "a.kt:1: val x = 1\nsrc/b.kt:1: val x = 2"
    }
}
