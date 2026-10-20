package org.github.nafania217518.claudius.chat.tool

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import io.kotest.assertions.throwables.shouldThrow
import java.nio.file.Path

class ToolTest {
     @Test
    fun `a non-overriding tool is fail-closed`() {
        val tool: Tool = object : Tool {
            override val name = "unsafe"
            override val description = "a bare tool"
            override val inputSchema = """{"type":"object"}"""
            override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit) = "ran"
         }
        tool.isReadOnly shouldBe false
        tool.isConcurrencySafe shouldBe false
        tool.isDestructive shouldBe true
        tool.autoRun shouldBe false
     }

     @Test
    fun `currentTime is the one auto-run tool`() {
        val t = CurrentTime()
        t.name shouldBe "currentTime"
        t.autoRun shouldBe true
     }

     // Fail-closed is an AND over three flags: flipping ANY one safe flag to unsafe must
     // turn autoRun off. These two isolate the 2nd and 3rd operands of the compound, which
     // the all-false (bare) and all-safe (CurrentTime) cases short-circuit past.
     @Test
    fun `a destructive tool does not auto-run even when read-only and concurrency-safe`() {
        val tool: Tool = object : Tool {
            override val name = "danger"
            override val description = "a destructive tool"
            override val inputSchema = """{"type":"object"}"""
            override val isReadOnly = true
            override val isConcurrencySafe = true
            override val isDestructive = true
            override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit) = "ran"
         }
        tool.autoRun shouldBe false
     }

     @Test
    fun `a non-concurrency-safe tool does not auto-run even when read-only`() {
        val tool: Tool = object : Tool {
            override val name = "serial"
            override val description = "a serial tool"
            override val inputSchema = """{"type":"object"}"""
            override val isReadOnly = true
            override val isConcurrencySafe = false
            override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit) = "ran"
         }
        tool.autoRun shouldBe false
     }

     @Test
    fun `currentTime returns an ISO instant ignoring args`() {
        CurrentTime().run("{}", Path.of(".")).startsWith("20") shouldBe true
     }

     @Test
    fun `adapter bridges the tool to a callback`() {
        val cb = ToolCallbackAdapter(CurrentTime())
        cb.toolDefinition.name() shouldBe "currentTime"
        cb.toolDefinition.description() shouldBe CurrentTime().description
     }

     @Test
    fun `the callback adapter refuses to execute a tool directly`() {
        shouldThrow<UnsupportedOperationException> { ToolCallbackAdapter(CurrentTime()).call("{}") }
     }
}
