package org.github.nafania217518.claudius.chat.tool

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ToolRegistryTest {
    private val dir: Path = Path.of(".")

     // A mutating tool: it must NOT autoRun, so run() refuses it. The fail-closed flag
     // contract itself is asserted in ToolTest.
    private class UnsafeTool(val ran: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean()) : Tool {
        override val name = "writeFile"
        override val description = "writes a file"
        override val inputSchema = """{"type":"object"}"""
        override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String { ran.set(true); return "wrote" }
     }

    private class BoomTool : Tool {
        override val name = "boom"
        override val description = "throws"
        override val inputSchema = """{"type":"object"}"""
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val isDestructive = false
        override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String = throw IllegalStateException("kaboom")
     }

    @Test
    fun `the registry advertises every tool to the model`() {
        val registry = ToolRegistry(listOf(CurrentTime(), UnsafeTool()))
        registry
             .callbacks()
             .map { it.toolDefinition.name() }
             .shouldContainExactlyInAnyOrder(listOf("currentTime", "writeFile"))
     }

    @Test
    fun `run executes an autoRun tool`() {
        val registry = ToolRegistry(listOf(CurrentTime(), UnsafeTool()))
        registry.run("currentTime", "{}", dir).startsWith("20") shouldBe true
     }

    @Test
    fun `run refuses a non-autoRun tool with an error result`() {
        val registry = ToolRegistry(listOf(CurrentTime(), UnsafeTool()))
        registry.run("writeFile", "{}", dir) shouldBe "error: tool 'writeFile' is not available"
     }

    @Test
    fun `run refuses an unknown tool name with an error result`() {
        val registry = ToolRegistry(listOf(CurrentTime()))
        registry.run("nope", "{}", dir) shouldBe "error: tool 'nope' is not available"
     }

    @Test
    fun `run turns a tool exception into an error result`() {
        val registry = ToolRegistry(listOf(BoomTool()))
        registry.run("boom", "{}", dir) shouldContain "error: kaboom"
     }

    @Test
    fun `permission is ALLOW for an autoRun tool, ASK for a mutating tool, DENY for an unknown tool`() {
        val registry = ToolRegistry(listOf(CurrentTime(), UnsafeTool()))
        registry.permission("currentTime") shouldBe Permission.ALLOW
        registry.permission("writeFile") shouldBe Permission.ASK
        registry.permission("nope") shouldBe Permission.DENY
    }

    @Test
    fun `run executes a mutating tool only when approved`() {
        val tool = UnsafeTool()
        val registry = ToolRegistry(listOf(tool))
        registry.run("writeFile", "{}", dir) shouldBe "error: tool 'writeFile' is not available"
        tool.ran.get() shouldBe false
        registry.run("writeFile", "{}", dir, approved = true) shouldBe "wrote"
        tool.ran.get() shouldBe true
    }

    @Test
    fun `run maps an empty result to no output`() {
        val empty = object : Tool {
            override val name = "empty"
            override val description = "returns nothing"
            override val inputSchema = """{"type":"object"}"""
            override val isReadOnly = true
            override val isConcurrencySafe = true
            override val isDestructive = false
            override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit) = ""
        }
        ToolRegistry(listOf(empty)).run("empty", "{}", dir) shouldBe "(no output)"
    }

    @Test
    fun `a tool exception without a message falls back to the class name`() {
        val bare = object : Tool {
            override val name = "bare"
            override val description = "throws bare"
            override val inputSchema = """{"type":"object"}"""
            override val isReadOnly = true
            override val isConcurrencySafe = true
            override val isDestructive = false
            override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String = throw IllegalStateException()
        }
        ToolRegistry(listOf(bare)).run("bare", "{}", dir) shouldBe "error: IllegalStateException"
    }
}
