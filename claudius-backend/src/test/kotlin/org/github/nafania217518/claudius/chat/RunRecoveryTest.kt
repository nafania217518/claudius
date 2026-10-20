package org.github.nafania217518.claudius.chat

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.github.nafania217518.claudius.chat.dto.ChatRequest
import org.github.nafania217518.claudius.chat.dto.ToolInvocationDto
import org.github.nafania217518.claudius.chat.tool.CurrentTime
import org.github.nafania217518.claudius.chat.tool.FileWrite
import org.github.nafania217518.claudius.chat.tool.ToolRegistry
import org.github.nafania217518.claudius.provider.ChatModelFactory
import org.github.nafania217518.claudius.provider.ProviderRegistry
import org.github.nafania217518.claudius.provider.ProviderService
import org.github.nafania217518.claudius.workspace.Workspace
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.ollama.api.OllamaChatOptions
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

// M12: run checkpoints and startup recovery. A "restart" is a fresh registry/store/service over
// the same data dir. Real temp-dir SQLite; a mock ChatModel.
class RunRecoveryTest {
    private val model = mockk<ChatModel>()
    private val factory = mockk<ChatModelFactory>()
    private val prompts = Collections.synchronizedList(mutableListOf<Prompt>())
    private lateinit var store: SessionStore
    private lateinit var providers: ProviderService
    private val mapper = ObjectMapper()
    private val writeArgs = """{"path":"out.txt","content":"hi"}"""

    // A fresh server over dir. spy: wrap the store in a mockk spy (to record checkpoints).
    private fun boot(dir: Path, spy: Boolean = false): ChatService {
        val registry = WorkspaceRegistry(dir.toString())
        if (registry.find("w1") == null) registry.insert(Workspace("w1", "w1", "w1", 0))
        Files.createDirectories(dir.resolve("w1"))
        store = SessionStore(registry, mapper).let { if (spy) spyk(it) else it }
        every { factory.model(any()) } returns model
        every { model.options } returns OllamaChatOptions.builder().build()
        providers = ProviderService(ProviderRegistry(registry, "http://localhost:11434", "llama-x"), ChatModelFactory())
        val loop = AgentLoop(store, ToolRegistry(listOf(CurrentTime(), FileWrite())), mapper, 25, checkpointMs = 50)
        return ChatService(store, registry, providers, factory, loop, Compactor(store, registry, loop), mapper)
    }

    private fun replies(vararg r: Flux<ChatResponse>) {
        every { model.stream(any<Prompt>()) } answers { prompts.add(firstArg()); r[prompts.size - 1] }
    }

    private fun delta(text: String) = ChatResponse(listOf(Generation(AssistantMessage(text))))

    private fun call(name: String, id: String, args: String = "{}") = ChatResponse(
        listOf(Generation(AssistantMessage.builder().content("").toolCalls(listOf(AssistantMessage.ToolCall(id, "function", name, args))).build())),
    )

    private fun pollUntil(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        cond() shouldBe true
    }

    private fun events(s: ChatService): MutableList<RunEvents.Event> {
        val list = Collections.synchronizedList(mutableListOf<RunEvents.Event>())
        s.active("w1")!!.subscribe(-1) { list.add(it) }
        return list
    }

    private val done = ToolInvocationDto("c1", "currentTime", "{}", "now")

    // Before the "restart": a turn asked for fileWrite (c2) after currentTime (c1) ran.
    private fun parkedRun(dir: Path): ChatService {
        boot(dir)
        store.appendUser("w1", "q")
        store.saveRun("w1", "r1", "waiting",
            Checkpoint(providers.list().first().id, "llama-x", "a", "th", listOf(done), listOf(PendingCall("c2", "fileWrite", writeArgs))))
        return boot(dir).also { it.recover() }
    }

    @Test
    fun `a run's progress is checkpointed after each model call and tool result`(@TempDir dir: Path) {
        val s = boot(dir, spy = true)
        val saved = Collections.synchronizedList(mutableListOf<Pair<String, Checkpoint>>())
        every { store.saveRun(any(), any(), any(), any()) } answers { saved.add(thirdArg<String>() to arg(3)); callOriginal() }
        replies(Flux.just(delta("a"), call("currentTime", "c1")), Flux.just(delta("b")))
        s.chat(ChatRequest("w1", "q", "r1")).collectList().block()
        saved.map { it.first } shouldContainExactly listOf("running", "running", "running", "running", "done")
        saved[1].second.answer shouldBe "a"
        saved[1].second.pending shouldBe listOf(PendingCall("c1", "currentTime", "{}"))
        saved[2].second.pending.shouldBeEmpty()
        saved[2].second.trace.single().name shouldBe "currentTime"
        saved[3].second.answer shouldBe "ab"
        saved[3].second.model shouldBe "llama-x"
    }

    @Test
    fun `on startup a run that was running is saved as interrupted with its partial turn and an error`(@TempDir dir: Path) {
        boot(dir)
        store.appendUser("w1", "q")
        store.saveRun("w1", "r1", "running", Checkpoint(providers.list().first().id, "llama-x", "part", "th", listOf(done)))
        val s = boot(dir)
        s.recover()
        val row = store.history("w1").last()
        row.role shouldBe "assistant"
        row.content shouldBe "part"
        row.thinking shouldBe "th"
        row.tools shouldBe listOf(done)
        row.model shouldBe "Ollama/llama-x"
        row.error shouldBe "interrupted by a server restart"
        store.runs("w1").single().status shouldBe "interrupted"
        s.active("w1") shouldBe null
    }

    @Test
    fun `on startup a run waiting on a permission is restored and asks again`(@TempDir dir: Path) {
        val s = parkedRun(dir)
        s.active("w1")!!.runId shouldBe "r1"
        val ev = events(s)
        pollUntil { ev.any { it.event == "permission" } }
        ev.map { it.event } shouldContainExactly listOf("tool_call", "tool_result", "reasoning", "token", "tool_call", "permission")
        ev.map { it.seq } shouldBe ev.indices.toList()
        mapper.readValue(ev.last().data, ToolInvocationDto::class.java).id shouldBe "c2"
        s.cancel("r1")
        pollUntil { s.active("w1") == null }
    }

    @Test
    fun `answering a restored permission runs the tool and continues the turn to done`(@TempDir dir: Path) {
        replies(Flux.just(delta("!")))
        val s = parkedRun(dir)
        val ev = events(s)
        pollUntil { ev.any { it.event == "permission" } }
        s.answer("r1", "c2", true)
        pollUntil { ev.any { it.event == "done" } }
        Files.readString(dir.resolve("w1/out.txt")) shouldBe "hi"
        val p = prompts.single()
        p.userMessages.last().text shouldBe "q"
        (p.instructions.last() as ToolResponseMessage).responses.map { it.id() } shouldBe listOf("c1", "c2")
        val row = store.history("w1").last()
        row.content shouldBe "a!"
        row.tools.map { it.id } shouldBe listOf("c1", "c2")
        store.runs("w1").single().status shouldBe "done"
        s.active("w1") shouldBe null
    }

    @Test
    fun `rejecting a restored permission continues the turn with the rejection`(@TempDir dir: Path) {
        replies(Flux.just(delta("ok")))
        val s = parkedRun(dir)
        val ev = events(s)
        pollUntil { ev.any { it.event == "permission" } }
        s.answer("r1", "c2", false)
        pollUntil { ev.any { it.event == "done" } }
        Files.exists(dir.resolve("w1/out.txt")) shouldBe false
        (prompts.single().instructions.last() as ToolResponseMessage).responses.last().responseData() shouldBe
            "error: the user rejected this call"
        store.history("w1").last().content shouldBe "aok"
    }

    @Test
    fun `stopping a restored run saves the partial turn`(@TempDir dir: Path) {
        replies()
        val s = parkedRun(dir)
        val ev = events(s)
        pollUntil { ev.any { it.event == "permission" } }
        s.cancel("r1")
        pollUntil { ev.any { it.event == "done" } }
        val row = store.history("w1").last()
        row.content shouldBe "a"
        row.tools.map { it.id } shouldBe listOf("c1", "c2")
        verify(exactly = 0) { model.stream(any<Prompt>()) }
    }

    @Test
    fun `a finished run leaves no active checkpoint`(@TempDir dir: Path) {
        val s = boot(dir)
        replies(Flux.just(delta("hi")))
        s.chat(ChatRequest("w1", "q", "r1")).collectList().block()
        store.runs("w1").map { it.status } shouldBe listOf("done")
        boot(dir).also { it.recover() }.active("w1") shouldBe null
        store.history("w1").size shouldBe 2
    }

    @Test
    fun `an assistant row keeps the turn's error`(@TempDir dir: Path) {
        val s = boot(dir)
        replies(Flux.concat(Flux.just(delta("x")), Flux.error(RuntimeException("boom"))))
        s.chat(ChatRequest("w1", "q", "r1")).collectList().block()
        val row = store.history("w1").last()
        row.content shouldBe "x"
        row.error shouldBe "boom"
        store.history("w1").first().error shouldBe ""
        row shouldNotBe null
    }

    @Test
    fun `the streamed answer and thinking are checkpointed at least once a second while a model call streams`(@TempDir dir: Path) {
        val s = boot(dir, spy = true)
        val saved = Collections.synchronizedList(mutableListOf<Checkpoint>())
        every { store.saveRun(any(), any(), any(), any()) } answers { saved.add(arg(3)); callOriginal() }
        replies(Flux.just(delta("a"), delta("b"), delta("c"), delta("d")).delayElements(java.time.Duration.ofMillis(100)))
        s.chat(ChatRequest("w1", "q", "r1")).collectList().block()
        // Mid-call checkpoints hold the partial text (start = "", end = "abcd").
        saved.map { it.answer }.filter { it.isNotEmpty() && it != "abcd" }.isNotEmpty() shouldBe true
        // A "restart" after a mid-call checkpoint keeps that text.
        store.saveRun("w1", "r1", "running", saved.first { it.answer == "ab" || it.answer == "abc" })
        boot(dir).recover()
        store.history("w1").last().content.startsWith("ab") shouldBe true
    }
}
