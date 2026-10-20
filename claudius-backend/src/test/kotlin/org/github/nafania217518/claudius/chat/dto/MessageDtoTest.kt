package org.github.nafania217518.claudius.chat.dto

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// M3: the wire DTO carries a `thinking` trace; M4 adds a `tools` trace (the turn's tool
// call/result JSON, or "" for a text-only turn). These pin the data-class behaviour --
// the two defaulted fields, copy, and the generated equals/hashCode/toString/componentN
// accessors -- so a transcript round-trip stays honest.
class MessageDtoTest {
    private val T = listOf(ToolInvocationDto("c1", "currentTime", "{}", "now"))

     @Test
    fun `thinking and tools default to empty for a user turn`() {
        val m = MessageDto(role = "user", content = "hi", createdAt = 0L)
        m.role shouldBe "user"
        m.content shouldBe "hi"
        m.thinking shouldBe ""
        m.tools shouldBe emptyList()
        m.createdAt shouldBe 0L
     }

     @Test
    fun `carries the trace when supplied`() {
        val m =
            MessageDto(
                role = "assistant",
                content = "ans",
                thinking = "trace",
                createdAt = 5L,
                tools = T,
              )
        m.thinking shouldBe "trace"
        m.tools shouldBe T
        m.createdAt shouldBe 5L
     }

     @Test
    fun `copy overrides only the named fields and keeps the rest`() {
        val m = MessageDto(role = "user", content = "hi", createdAt = 1L)
        val c = m.copy(content = "bye")
        c.role shouldBe "user"
        c.content shouldBe "bye"
        c.thinking shouldBe ""
        c.tools shouldBe emptyList()
        c.createdAt shouldBe 1L
        c.copy(thinking = "t").thinking shouldBe "t"
        c.copy(tools = T).tools shouldBe T
     }

     @Test
    fun `equals distinguishes every field and rejects foreign types`() {
        val base = MessageDto("user", "hi", "trace", 1L, T)
        (base == base) shouldBe true
        base.equals("nope") shouldBe false
        base.equals(null) shouldBe false
        (base != MessageDto("assistant", "hi", "trace", 1L, T)) shouldBe true
        (base != MessageDto("user", "bye", "trace", 1L, T)) shouldBe true
        (base != MessageDto("user", "hi", "other", 1L, T)) shouldBe true
        (base != MessageDto("user", "hi", "trace", 2L, T)) shouldBe true
        (base != MessageDto("user", "hi", "trace", 1L, emptyList())) shouldBe true
        base.hashCode() shouldBe MessageDto("user", "hi", "trace", 1L, T).hashCode()
     }

     @Test
    fun `toString and componentN expose every field`() {
        val m = MessageDto("user", "hi", "trace", 7L, T)
        m.toString().contains("user").shouldBe(true)
        m.toString().contains("trace").shouldBe(true)
        m.component1() shouldBe "user"
        m.component2() shouldBe "hi"
        m.component3() shouldBe "trace"
        m.component4() shouldBe 7L
        m.component5() shouldBe T
     }
}
