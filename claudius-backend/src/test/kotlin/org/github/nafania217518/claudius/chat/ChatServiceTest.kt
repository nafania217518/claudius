package org.github.nafania217518.claudius.chat

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.assertFailsWith
import org.github.nafania217518.claudius.chat.dto.ChatRequest
import org.github.nafania217518.claudius.chat.dto.ToolInvocationDto
import org.github.nafania217518.claudius.chat.tool.CurrentTime
import org.github.nafania217518.claudius.chat.tool.FileRead
import org.github.nafania217518.claudius.chat.tool.FileWrite
import org.github.nafania217518.claudius.chat.tool.Tool
import org.github.nafania217518.claudius.chat.tool.ToolRegistry
import org.github.nafania217518.claudius.provider.ChatModelFactory
import org.github.nafania217518.claudius.provider.ModelLimits
import org.github.nafania217518.claudius.provider.ProviderRequest
import org.github.nafania217518.claudius.provider.ProviderService
import org.github.nafania217518.claudius.provider.ProviderRegistry
import org.github.nafania217518.claudius.provider.ProviderType
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.github.nafania217518.claudius.workspace.PermissionMode
import org.github.nafania217518.claudius.workspace.Workspace
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.ollama.api.OllamaChatOptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Collections
import java.util.concurrent.CountDownLatch
import reactor.test.StepVerifier
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Slice test: no live Ollama. Mocks ChatModel.stream and asserts the tagged chunk
// stream + per-workspace append. The store is a REAL temp-dir SQLite (not a mock) —
// the persistence round-trip is part of the behaviour under test. JUnit5 + kotest
// matchers (no kotest runner dep).
class ChatServiceTest {

    private fun req(message: String, runId: String = "r1") = ChatRequest("w1", message, runId)

    private fun delta(text: String) =
        ChatResponse(listOf(Generation(AssistantMessage(text))))

         // A reasoning frame: an empty answer with a "thinking" trace on generation
         // metadata — the shape Ollama emits for a thinking model's trace.
    private fun reasoning(text: String, answer: String = "") =
        ChatResponse(
            listOf(
                Generation(
                    AssistantMessage(answer),
                    ChatGenerationMetadata.builder().metadata("thinking", text).build(),
                   ),
               ),
           )

          // A frame that carries generation metadata but no "thinking" key — a token delta on
          // a non-thinking model: the `metadata?` is present yet `.get("thinking")` is null, so
          // it contributes no REASONING chunk, only its answer text.
    private fun metaNoThinking(text: String) =
        ChatResponse(
            listOf(
                Generation(
                    AssistantMessage(text),
                    ChatGenerationMetadata.builder().metadata("other", "v").build(),
                   ),
               ),
           )

         // Built reflectively: Kotlin sees id as non-null, but the Java-side Ollama converter
         // can still pass null; the loop must degrade that to "".
    private fun toolCallRef(id: String?, name: String, args: String = "{}"): AssistantMessage.ToolCall =
        AssistantMessage.ToolCall::class.java
            .getConstructor(String::class.java, String::class.java, String::class.java, String::class.java)
            .newInstance(id, "function", name, args)

         // M4: a turn that asks the model to call a tool. Empty answer + a single tool_call;
         // the loop must execute it and feed the result back.
    private fun toolCall(name: String = "currentTime", id: String? = "call_1", args: String = "{}") =
        ChatResponse(
            listOf(
                Generation(
                    AssistantMessage
                           .builder()
                           .content("")
                           .toolCalls(listOf(toolCallRef(id, name, args)))
                           .build(),
                   ),
               ),
           )

         // One registered workspace over a temp workspaces dir → a real per-workspace store.
         // id defaults to "w1" so the common path stays a one-liner.
    // The last registered registry; service() hands it to the loop for dirOf.
    private lateinit var registry: WorkspaceRegistry

    private fun registeredStore(dir: Path, id: String = "w1"): SessionStore {
        registry = WorkspaceRegistry(dir.toString())
        registry.insert(Workspace(id, id, id, System.currentTimeMillis()))
        return SessionStore(registry, ObjectMapper())
        }

         // M4: the loop's real tool surface — the fail-closed registry + CurrentTime — so a
         // tool_call test exercises the actual executor, not a mock.
    private fun service(
        model: ChatModel,
        store: SessionStore,
        tools: List<Tool> = listOf(CurrentTime()),
        maxIterations: Int = 25,
    ): ChatService {
          // The loop reads chatModel.options to build the tool surface; the strict mock only
          // stubs stream(), so answer getOptions() with a real Ollama options the loop can mutate.
        every { model.options } returns OllamaChatOptions.builder().build()
        return chatService(store, providers(model), AgentLoop(store, ToolRegistry(tools), ObjectMapper(), maxIterations))
        }

    // M11: every turn now ends with a USAGE chunk. These tests predate it, so they see the
    // stream without it; CompactionTest covers USAGE.
    private fun chatService(store: SessionStore, providers: ProviderService, loop: AgentLoop) =
        object : ChatService(store, registry, providers, factory, loop, Compactor(store, registry, loop), ObjectMapper()) {
            override fun chat(request: ChatRequest) = super.chat(request).filter { it.kind != StreamChunk.Kind.USAGE }
        }

    // M10: a real provider registry over the same data dir (seeded "Ollama"/"llama-x"); the
    // factory hands back the mock model for every provider.
    private lateinit var providers: ProviderService
    private val factory = mockk<ChatModelFactory>()

    private fun providers(model: ChatModel): ProviderService {
        every { factory.model(any()) } returns model
        return ProviderService(ProviderRegistry(registry, "http://localhost:11434", "llama-x"), ChatModelFactory())
            .also { providers = it }
    }

    private fun pollUntil(timeoutMs: Long = 2000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        cond() shouldBe true
    }

        @Test
    fun `streams token deltas and appends user and assistant turns`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns
            Flux.fromIterable(listOf(delta("H"), delta("i"), delta("!")))

        StepVerifier
               .create(service.chat(req("hello")))
               .expectNext(
                  StreamChunk(StreamChunk.Kind.TOKEN, "H"),
                  StreamChunk(StreamChunk.Kind.TOKEN, "i"),
                  StreamChunk(StreamChunk.Kind.TOKEN, "!"),
                )
               .verifyComplete()

        val history = store.history("w1")
        history shouldHaveSize 2
        history[0].role shouldBe "user"
        history[0].content shouldBe "hello"
        history[1].role shouldBe "assistant"
        history[1].content shouldBe "Hi!"
        }

        @Test
    fun `second turn sees the full history from the first`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)
        val promptSlot = slot<Prompt>()

        every { chatModel.stream(capture(promptSlot)) } returns
            Flux.fromIterable(listOf(delta("ok")))

        service.chat(req("q1")).collectList().block()!!
        service.chat(req("q2", "r2")).collectList().block()!!

        val prompt = promptSlot.captured
        prompt.getUserMessages().map { it.text } shouldBe listOf("q1", "q2")
        store.history("w1") shouldHaveSize 4
        }

        @Test
    fun `a reply of only empty deltas emits nothing and appends no assistant turn`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns
            Flux.fromIterable(listOf(delta(""), delta("")))

        service.chat(req("q")).collectList().block() shouldBe emptyList()
        store.history("w1") shouldHaveSize 1
        }

        @Test
    fun `metadata-only and null-output chunks contribute nothing and do not crash`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

             // Real Ollama streams emit metadata-only frames and, on partial/aborted
             // chunks, a generation with a null output/text. The null-guards must
             // swallow all three shapes and still yield only the real deltas.
        every { chatModel.stream(any<Prompt>()) } returns
            Flux.fromIterable(
                listOf(
                    delta("Hi"),
                    ChatResponse(emptyList()),
                    ChatResponse(listOf(Generation(AssistantMessage(null)))),
                       // A present-but-empty "thinking" trace: the isNullOrEmpty()
                       // isEmpty() sub-branch — must contribute no REASONING chunk.
                    reasoning(""),
                    delta("!"),
                    ),
                 )

        service
               .chat(req("q"))
               .collectList()
               .block()!!
               .shouldBe(listOf(StreamChunk(StreamChunk.Kind.TOKEN, "Hi"), StreamChunk(StreamChunk.Kind.TOKEN, "!")))
        store.history("w1") shouldHaveSize 2
        store.history("w1")[1].content shouldBe "Hi!"
        }

        @Test
    fun `a frame with metadata but no thinking key forwards only its answer text`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns
            Flux.fromIterable(listOf(metaNoThinking("Hi"), delta("!")))

        StepVerifier
               .create(service.chat(req("q")))
               .expectNext(
                  StreamChunk(StreamChunk.Kind.TOKEN, "Hi"),
                  StreamChunk(StreamChunk.Kind.TOKEN, "!"),
                )
               .verifyComplete()

        val history = store.history("w1")
        history[1].content shouldBe "Hi!"
        history[1].thinking shouldBe ""
        }

        @Test
    fun `a reasoning delta streams as a reasoning chunk and its trace is persisted`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns
            Flux.fromIterable(listOf(reasoning("let me think"), reasoning(" about it"), delta("answer")))

        StepVerifier
               .create(service.chat(req("q")))
               .expectNext(
                  StreamChunk(StreamChunk.Kind.REASONING, "let me think"),
                  StreamChunk(StreamChunk.Kind.REASONING, " about it"),
                  StreamChunk(StreamChunk.Kind.TOKEN, "answer"),
                )
               .verifyComplete()

        val history = store.history("w1")
        history shouldHaveSize 2
        history[1].content shouldBe "answer"
        history[1].thinking shouldBe "let me think about it"
        }

        @Test
    fun `a reasoning-only turn with no answer text still persists its trace`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns
            Flux.fromIterable(listOf(reasoning("just thinking")))

        service
               .chat(req("q"))
               .collectList()
               .block()!!
               .shouldBe(listOf(StreamChunk(StreamChunk.Kind.REASONING, "just thinking")))

        val history = store.history("w1")
        history shouldHaveSize 2
        history[1].content shouldBe ""
        history[1].thinking shouldBe "just thinking"
        }

        // M5: a frame can carry thinking AND text at once; both must be forwarded.
        @Test
    fun `a chunk carrying both thinking and text forwards both`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns
            Flux.fromIterable(listOf(reasoning("hmm", answer = "Hi")))

        StepVerifier
               .create(service.chat(req("q")))
               .expectNext(
                  StreamChunk(StreamChunk.Kind.REASONING, "hmm"),
                  StreamChunk(StreamChunk.Kind.TOKEN, "Hi"),
                )
               .verifyComplete()

        val history = store.history("w1")
        history[1].content shouldBe "Hi"
        history[1].thinking shouldBe "hmm"
        }

        // M4: the loop reads the model's OWN options to attach the tool surface. When the model
        // hands back a non-Ollama options (a foreign provider), the `as?` misses and the loop
        // falls back to a fresh Ollama builder rather than crashing.
        @Test
    fun `a non-ollama model options falls back to a fresh builder`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = chatService(store, providers(chatModel), AgentLoop(store, ToolRegistry(listOf(CurrentTime())), ObjectMapper(), 25))

        every { chatModel.options } returns mockk<ChatOptions>(relaxed = true)
        every { chatModel.stream(any<Prompt>()) } returns
            Flux.fromIterable(listOf(delta("ok")))

        service
               .chat(req("q"))
               .collectList()
               .block()!!
               .map { it.kind } shouldBe listOf(StreamChunk.Kind.TOKEN)
        }

        // An empty model stream aggregates to no response; the loop breaks on its first cycle
        // with no chunks and no assistant turn — only the user turn persists.
        @Test
    fun `an empty model stream emits nothing and appends no assistant turn`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns Flux.empty()

        service
               .chat(req("q"))
               .collectList()
               .block() shouldBe emptyList()
        store.history("w1") shouldHaveSize 1
        }

        // M4: the loop VCR. First cycle the model asks for the currentTime tool; the real
        // executor runs it and echoes a tool_result; the second cycle answers in prose.
    // The aggregate merges the chunks; the last raw chunk alone would hold only call_b.
    @Test
    fun `tool calls streamed in separate chunks are all executed`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)
        val prompts = mutableListOf<Prompt>()
        every { chatModel.stream(capture(prompts)) } returnsMany listOf(
            Flux.just(toolCall(id = "call_a"), toolCall(id = "call_b")),
            Flux.just(delta("The time "), delta("is now.")),
        )

        val chunks = service.chat(req("q")).collectList().block()!!

        chunks.filter { it.kind == StreamChunk.Kind.TOOL_RESULT }.map { dto(it).id }.sorted() shouldBe listOf("call_a", "call_b") // parallel: any order
        prompts[1].instructions.filterIsInstance<ToolResponseMessage>().single().responses.map { it.id() } shouldBe
            listOf("call_a", "call_b")
        val history = store.history("w1")
        history[1].tools.map { it.id } shouldBe listOf("call_a", "call_b")
        history[1].content shouldBe "The time is now."
    }

        // Sequential stubbing: the first stream() returns the tool call, the second the answer.
        @Test
    fun `a tool call is executed and its result fed back before the final answer`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(
                Flux.fromIterable(listOf(toolCall())),
                Flux.fromIterable(listOf(delta("The time is now."))),
                )

        val chunks = service.chat(req("what time is it?")).collectList().block()!!

          // Order on the wire: the call, its result, then the answer's tokens.
        chunks.map { it.kind } shouldContainExactly
            listOf(StreamChunk.Kind.TOOL_CALL, StreamChunk.Kind.TOOL_RESULT, StreamChunk.Kind.TOKEN)
        chunks[0].text shouldContain "currentTime"
        chunks[0].text shouldContain "call_1"
          // The real CurrentTime ran — the result is an ISO-8601 UTC instant.
        chunks[1].text shouldContain "call_1"
        chunks[1].text shouldContain "20"
        chunks[1].text shouldContain "\"arguments\":\"\""

        val history = store.history("w1")
        history shouldHaveSize 2
        history[1].content shouldBe "The time is now."
        }

        @Test
    fun `a tool trace is persisted as objects and reloads as objects`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(
                Flux.fromIterable(listOf(toolCall())),
                Flux.fromIterable(listOf(delta("ok"))),
                )

        val chunks = service.chat(req("q")).collectList().block()!!
        val result = ObjectMapper().readValue(chunks[1].text, ToolInvocationDto::class.java).result
        result shouldContain "T"

        store.history("w1")[1].tools shouldBe
            listOf(ToolInvocationDto("call_1", "currentTime", "{}", result, thinkingAt = 0, answerAt = 0))
        }

        @Test
    fun `a tool call records how much thinking and answer text came before it`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(
                Flux.just(reasoning("think one"), toolCall()),
                Flux.just(reasoning("think two"), delta("done")),
                )

        val chunks = service.chat(req("q")).collectList().block()!!
        chunks.single { it.kind == StreamChunk.Kind.TOOL_CALL }.text shouldContain "\"thinkingAt\":9"
        val tool = store.history("w1")[1].tools.single()
        tool.thinkingAt shouldBe 9
        tool.answerAt shouldBe 0
        }

        @Test
    fun `an old tool entry without offsets still loads`() {
        val dto = ObjectMapper().readValue("""{"id":"a","name":"n","arguments":"{}","result":"r"}""", ToolInvocationDto::class.java)
        dto.thinkingAt shouldBe null
        dto.answerAt shouldBe null
        }

        // M7: an Ollama tool call may carry a null id; the loop generates one.
        @Test
    fun `a null tool-call id gets a generated id shared by call and result`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(
                Flux.fromIterable(listOf(toolCall(id = null))),
                Flux.fromIterable(listOf(delta("ok"))),
                )

        val chunks = service.chat(req("q")).collectList().block()!!
        val id = store.history("w1")[1].tools.single().id
        id.isNotEmpty() shouldBe true
        chunks[0].text shouldContain id
        chunks[1].text shouldContain id
        }

        @Test
    fun `a non-autoRun tool call that the user rejects yields a failed tool_result and the tool never runs`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val ran = AtomicBoolean(false)
        val unsafe = object : Tool {
            override val name = "writeFile"
            override val description = "writes"
            override val inputSchema = """{"type":"object"}"""
            override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String { ran.set(true); throw AssertionError("ran") }
        }
        val service = service(chatModel, store, listOf(CurrentTime(), unsafe))

        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(
                Flux.fromIterable(listOf(toolCall("writeFile"))),
                Flux.fromIterable(listOf(delta("ok"))),
                )

        StepVerifier
               .create(service.chat(req("q")))
               .expectNextMatches { it.kind == StreamChunk.Kind.TOOL_CALL }
               .expectNextMatches { it.kind == StreamChunk.Kind.PERMISSION }
               .then { service.answer("r1", "call_1", false) }
               .expectNextMatches { it.kind == StreamChunk.Kind.TOOL_RESULT && it.text.contains("rejected") }
               .expectNext(StreamChunk(StreamChunk.Kind.TOKEN, "ok"))
               .verifyComplete()
        ran.get() shouldBe false
        }

        @Test
    fun `a tool that throws yields an error tool_result and the turn continues`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val boom = object : Tool {
            override val name = "boom"
            override val description = "throws"
            override val inputSchema = """{"type":"object"}"""
            override val isReadOnly = true
            override val isConcurrencySafe = true
            override val isDestructive = false
            override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String = throw IllegalStateException("kaboom")
        }
        val service = service(chatModel, store, listOf(boom))

        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(
                Flux.fromIterable(listOf(toolCall("boom"))),
                Flux.fromIterable(listOf(delta("ok"))),
                )

        val chunks = service.chat(req("q")).collectList().block()!!
        chunks.map { it.kind } shouldContainExactly
            listOf(StreamChunk.Kind.TOOL_CALL, StreamChunk.Kind.TOOL_RESULT, StreamChunk.Kind.TOKEN)
        chunks[1].text shouldContain "error: kaboom"
        store.history("w1")[1].content shouldBe "ok"
        }

        @Test
    // M12: the stream is only a view of the detached run: cancelling it leaves the run going,
    // and cancel(runId) stops it.
    fun `cancelling the stream does not stop the run and cancel by runId persists the partial answer`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns
            Flux.just(delta("Hel")).concatWith(Flux.never())

        StepVerifier
               .create(service.chat(req("q")))
               .expectNext(StreamChunk(StreamChunk.Kind.TOKEN, "Hel"))
               .thenCancel()
               .verify()
        service.active("w1") shouldNotBe null
        service.cancel("r1")

        // M11: the context size is the turn's last write.
        pollUntil { store.history("w1").size == 2 && store.history("w1")[1].content == "Hel" && registry.find("w1")!!.contextTokens > 0 }
        verify(exactly = 1) { chatModel.stream(any<Prompt>()) }

          // The workspace is free again: a new chat is accepted.
        every { chatModel.stream(any<Prompt>()) } returns Flux.just(delta("again"))
        service.chat(req("q2", "r2")).collectList().block()!!.map { it.text } shouldBe listOf("again")
        }

        @Test
    fun `cancel by runId ends the stream with completion and persists the partial answer`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)
        val calls = AtomicInteger()

        every { chatModel.stream(any<Prompt>()) } answers {
            calls.incrementAndGet()
            Flux.just(delta("Hel")).concatWith(Flux.never())
        }

        val future = service.chat(req("q")).collectList().toFuture()
        pollUntil { calls.get() == 1 }
        service.cancel("r1")

        future.get(2, TimeUnit.SECONDS) shouldBe listOf(StreamChunk(StreamChunk.Kind.TOKEN, "Hel"), StreamChunk(StreamChunk.Kind.ERROR, "stopped"))
        store.history("w1")[1].content shouldBe "Hel"
        }

        @Test
    fun `a stopped run ends with a stopped error and the saved assistant row records it`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)
        val calls = AtomicInteger()
        every { chatModel.stream(any<Prompt>()) } answers {
            calls.incrementAndGet()
            Flux.just(delta("Hel")).concatWith(Flux.never())
        }

        val future = service.chat(req("q")).collectList().toFuture()
        pollUntil { calls.get() == 1 }
        service.cancel("r1")

        future.get(2, TimeUnit.SECONDS)!!.last() shouldBe StreamChunk(StreamChunk.Kind.ERROR, "stopped")
        store.history("w1")[1].error shouldBe "stopped"
        }

        @Test
    fun `cancel of an unknown runId is a no-op`(@TempDir dir: Path) {
        val service = service(mockk<ChatModel>(), registeredStore(dir))
        service.cancel("nope")
        }

        @Test
    fun `a model error emits an error chunk and completes`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns Flux.error(RuntimeException("ollama down"))

        StepVerifier
               .create(service.chat(req("q")))
               .expectNextMatches { it.kind == StreamChunk.Kind.ERROR && it.text.contains("ollama down") }
               .verifyComplete()
        // M12: the error alone is kept on an assistant row.
        store.history("w1") shouldHaveSize 2
        store.history("w1")[1].error shouldBe "ollama down"
        }

        @Test
    fun `a provider stream that fails with a malformed tool call ends the run with a clear error`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns Flux.concat(Flux.just(delta("Hi")), Flux.error(malformedNpe()))

        service.chat(req("q")).collectList().block()!! shouldContainExactly listOf(
            StreamChunk(StreamChunk.Kind.TOKEN, "Hi"),
            StreamChunk(StreamChunk.Kind.ERROR, AgentLoop.MALFORMED_TOOL_CALL),
        )
        verify(exactly = 1) { chatModel.stream(any<Prompt>()) }
        store.history("w1")[1].content shouldBe "Hi"
        store.history("w1")[1].error shouldBe AgentLoop.MALFORMED_TOOL_CALL
        }

        @Test
    fun `a malformed tool call before any output is retried once`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val service = service(chatModel, registeredStore(dir))

        every { chatModel.stream(any<Prompt>()) } returnsMany listOf(Flux.error(malformedNpe()), Flux.just(delta("ok")))

        service.chat(req("q")).collectList().block()!! shouldContainExactly listOf(StreamChunk(StreamChunk.Kind.TOKEN, "ok"))
        verify(exactly = 2) { chatModel.stream(any<Prompt>()) }
        }

        @Test
    fun `a malformed tool call that repeats on the retry ends the run with a clear error`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val service = service(chatModel, registeredStore(dir))

        every { chatModel.stream(any<Prompt>()) } answers { Flux.error(malformedNpe()) }

        service.chat(req("q")).collectList().block()!! shouldContainExactly listOf(StreamChunk(StreamChunk.Kind.ERROR, AgentLoop.MALFORMED_TOOL_CALL))
        verify(exactly = 2) { chatModel.stream(any<Prompt>()) }
        }

    private fun malformedNpe() = NullPointerException(
        "Cannot invoke \"org.springframework.ai.ollama.api.OllamaApi\$Message.thinking()\" because the return value of " +
            "\"org.springframework.ai.ollama.api.OllamaApi\$ChatResponse.message()\" is null")

        @Test
    fun `a model error without a message falls back to the exception class name`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val service = service(chatModel, registeredStore(dir))

        every { chatModel.stream(any<Prompt>()) } returns Flux.error(IllegalStateException())

        service.chat(req("q")).collectList().block()!!.single().text shouldBe "IllegalStateException"
        }

        @Test
    fun `the loop stops after max iterations with an error chunk`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store, maxIterations = 2)

        every { chatModel.stream(any<Prompt>()) } answers { Flux.fromIterable(listOf(toolCall())) }

        val chunks = service.chat(req("q")).collectList().block()!!
        chunks.map { it.kind } shouldContainExactly
            listOf(
                StreamChunk.Kind.TOOL_CALL,
                StreamChunk.Kind.TOOL_RESULT,
                StreamChunk.Kind.TOOL_CALL,
                StreamChunk.Kind.TOOL_RESULT,
                StreamChunk.Kind.ERROR,
            )
        chunks.last().text shouldContain "2 model calls"
        verify(exactly = 2) { chatModel.stream(any<Prompt>()) }
        }

        @Test
    fun `a second chat on a busy workspace throws IllegalStateException`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)
        val calls = AtomicInteger()

        every { chatModel.stream(any<Prompt>()) } answers {
            calls.incrementAndGet()
            Flux.just(delta("Hel")).concatWith(Flux.never())
        }

        val future = service.chat(req("q")).collectList().toFuture()
        pollUntil { calls.get() == 1 }
        assertFailsWith<IllegalStateException> { service.chat(req("q2", "r2")) }

        service.cancel("r1")
        future.get(2, TimeUnit.SECONDS)
        every { chatModel.stream(any<Prompt>()) } returns Flux.just(delta("ok"))
        service.chat(req("q3", "r3")).collectList().block()!! shouldHaveSize 1
        }
    // A store failure before the loop (unknown workspace, JDBC error) must not leave the
    // workspace marked busy: the next chat must see the same store error, not a 409.
    @Test
    fun `a store failure releases the workspace for the next chat`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val service = service(chatModel, registeredStore(dir))

        shouldThrow<NoSuchElementException> {
            service.chat(ChatRequest("unknown", "q", "r1")).collectList().block()
        }
        shouldThrow<NoSuchElementException> {
            service.chat(ChatRequest("unknown", "q", "r2")).collectList().block()
        }
    }

    // ---- M6/M7: replay, workspace tools, permission gate ----

    private fun wsDir(dir: Path): Path = Files.createDirectories(dir.resolve("w1"))

    private fun tools() = listOf(CurrentTime(), FileRead(), FileWrite())

    @Test
    fun `a previous turn's tool call and result are replayed into the next prompt`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)
        val prompts = mutableListOf<Prompt>()

        every { chatModel.stream(capture(prompts)) } returnsMany
            listOf(
                Flux.just(toolCall()),
                Flux.just(delta("It is noon.")),
                Flux.just(delta("ok")),
            )

        service.chat(req("time?")).collectList().block()!!
        service.chat(req("again", "r2")).collectList().block()!!

        val msgs = prompts.last().instructions
        val call = msgs.filterIsInstance<AssistantMessage>().first { it.hasToolCalls() }
        call.toolCalls.single().id() shouldBe "call_1"
        call.toolCalls.single().name() shouldBe "currentTime"
        val i = msgs.indexOf(call)
        val response = msgs[i + 1] as ToolResponseMessage
        response.responses.single().id() shouldBe "call_1"
        response.responses.single().responseData() shouldContain "20"
        (msgs[i + 2] as AssistantMessage).text shouldBe "It is noon."
        (msgs[i + 3] as UserMessage).text shouldBe "again"
    }

    @Test
    fun `a blank tool argument is replayed as an empty JSON object`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)
        val prompts = mutableListOf<Prompt>()
        store.appendUser("w1", "q")
        store.appendAssistant("w1", "", "", listOf(ToolInvocationDto("c1", "currentTime", "", "t")))

        every { chatModel.stream(capture(prompts)) } returns Flux.just(delta("ok"))
        service.chat(req("q2")).collectList().block()!!

        val call = prompts.single().instructions.filterIsInstance<AssistantMessage>().first { it.hasToolCalls() }
        call.toolCalls.single().arguments() shouldBe "{}"
        // A tool-only turn replays no trailing answer: the next message is the new user turn.
        val msgs = prompts.single().instructions
        msgs[msgs.indexOf(call) + 2] shouldBe UserMessage("q2")
    }

    @Test
    fun `a read-only workspace tool runs without approval and is audited as allow`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        Files.writeString(wsDir(dir).resolve("a.txt"), "file body")
        val service = service(chatModel, store, tools())

        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(Flux.just(toolCall("fileRead", args = """{"path":"a.txt"}""")), Flux.just(delta("ok")))

        val chunks = service.chat(req("q")).collectList().block()!!
        chunks.map { it.kind } shouldContainExactly
            listOf(StreamChunk.Kind.TOOL_CALL, StreamChunk.Kind.TOOL_RESULT, StreamChunk.Kind.TOKEN)
        chunks[1].text shouldContain "file body"
        store.audit("w1").single().decision shouldBe "allow"
    }

    @Test
    fun `a mutating tool asks for approval and runs when approved`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val ws = wsDir(dir)
        val service = service(chatModel, store, tools())
        val args = """{"path":"out.txt","content":"hi"}"""

        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(Flux.just(toolCall("fileWrite", args = args)), Flux.just(delta("done")))

        StepVerifier
               .create(service.chat(req("q")))
               .expectNextMatches { it.kind == StreamChunk.Kind.TOOL_CALL }
               .expectNext(StreamChunk(StreamChunk.Kind.PERMISSION, ObjectMapper().writeValueAsString(ToolInvocationDto("call_1", "fileWrite", args, "", thinkingAt = 0, answerAt = 0))))
               .then { service.answer("r1", "call_1", true) }
               .expectNextMatches { it.kind == StreamChunk.Kind.TOOL_RESULT && it.text.contains("wrote 2 bytes") }
               .expectNext(StreamChunk(StreamChunk.Kind.TOKEN, "done"))
               .verifyComplete()

        Files.readString(ws.resolve("out.txt")) shouldBe "hi"
        store.audit("w1").single().decision shouldBe "approved"
    }

    // Live bug: Netty requests one event at a time. subscribeOn queued each later request()
    // behind the loop's blocked worker, so the browser saw only the first event of a turn and
    // nothing while the loop waited for approval. StepVerifier's default unbounded demand hid it.
    @Test
    fun `chunks reach a one-at-a-time subscriber while the loop waits for approval`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        wsDir(dir)
        val service = service(chatModel, store, tools())

        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(
                Flux.just(delta("a"), delta("b"), toolCall("fileWrite", args = """{"path":"o.txt","content":"x"}""")),
                Flux.just(delta("done")),
            )

        StepVerifier
               .create(service.chat(req("q")), 1)
               .expectNext(StreamChunk(StreamChunk.Kind.TOKEN, "a"))
               .thenRequest(1)
               .expectNext(StreamChunk(StreamChunk.Kind.TOKEN, "b"))
               .thenRequest(1)
               .expectNextMatches { it.kind == StreamChunk.Kind.TOOL_CALL }
               .thenRequest(1)
               .expectNextMatches { it.kind == StreamChunk.Kind.PERMISSION }
               .then { service.answer("r1", "call_1", false) }
               .thenRequest(Long.MAX_VALUE)
               .expectNextMatches { it.kind == StreamChunk.Kind.TOOL_RESULT }
               .expectNext(StreamChunk(StreamChunk.Kind.TOKEN, "done"))
               .expectComplete()
               .verify(java.time.Duration.ofSeconds(5))
    }

    // Live bug: Ollama emits on a reactor-netty thread and the stream stays open while the
    // model generates. Each delta must reach a one-at-a-time subscriber before the stream ends.
    @Test
    fun `deltas emitted on another thread reach a one-at-a-time subscriber before the model stream ends`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returns
            Flux.concat(
                Flux.just(delta("a"), delta("b")).delayElements(java.time.Duration.ofMillis(20)),
                Flux.never(),
            )

        StepVerifier
               .create(service.chat(req("q")), 1)
               .expectNext(StreamChunk(StreamChunk.Kind.TOKEN, "a"))
               .thenRequest(1)
               .expectNext(StreamChunk(StreamChunk.Kind.TOKEN, "b"))
               .thenCancel()
               .verify(java.time.Duration.ofSeconds(5))
        // M12: a cancelled view does not stop the detached run; stop it by runId.
        service.cancel("r1")
        // The worker persists after the cancel; wait for its last write before the temp dir goes.
        pollUntil { registry.find("w1")!!.contextTokens > 0 }
    }

    @Test
    fun `a rejected mutating tool does not run and feeds a rejection back to the model`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val ws = wsDir(dir)
        val service = service(chatModel, store, tools())
        val prompts = mutableListOf<Prompt>()

        every { chatModel.stream(capture(prompts)) } returnsMany
            listOf(
                Flux.just(toolCall("fileWrite", args = """{"path":"out.txt","content":"hi"}""")),
                Flux.just(delta("ok")),
            )

        StepVerifier
               .create(service.chat(req("q")))
               .expectNextMatches { it.kind == StreamChunk.Kind.TOOL_CALL }
               .expectNextMatches { it.kind == StreamChunk.Kind.PERMISSION }
               .then { service.answer("r1", "call_1", false) }
               .expectNextMatches { it.kind == StreamChunk.Kind.TOOL_RESULT }
               .expectNext(StreamChunk(StreamChunk.Kind.TOKEN, "ok"))
               .verifyComplete()

        Files.exists(ws.resolve("out.txt")) shouldBe false
        (prompts.last().instructions.last() as ToolResponseMessage).responses.single().responseData() shouldBe
            "error: the user rejected this call"
        store.audit("w1").single().decision shouldBe "rejected"
    }

    @Test
    fun `cancel while waiting for approval ends the run without running the tool`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val ws = wsDir(dir)
        val service = service(chatModel, store, tools())

        every { chatModel.stream(any<Prompt>()) } returns
            Flux.just(toolCall("fileWrite", args = """{"path":"out.txt","content":"hi"}"""))

        StepVerifier
               .create(service.chat(req("q")))
               .expectNextMatches { it.kind == StreamChunk.Kind.TOOL_CALL }
               .expectNextMatches { it.kind == StreamChunk.Kind.PERMISSION }
               .then { service.cancel("r1") }
               .expectNextMatches { it.kind == StreamChunk.Kind.TOOL_RESULT && it.text.contains("rejected") }
               .expectNext(StreamChunk(StreamChunk.Kind.ERROR, "stopped"))
               .verifyComplete()

        Files.exists(ws.resolve("out.txt")) shouldBe false
        verify(exactly = 1) { chatModel.stream(any<Prompt>()) }
        store.history("w1")[1].tools.single().result shouldBe "error: the user rejected this call"
    }

    @Test
    fun `an unknown tool is denied and audited as deny`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store)

        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(Flux.just(toolCall("rm")), Flux.just(delta("ok")))

        val chunks = service.chat(req("q")).collectList().block()!!
        chunks[1].text shouldContain "error: tool 'rm' is not available"
        store.audit("w1").single().decision shouldBe "deny"
    }

    @Test
    fun `answer for an unknown run is a no-op`(@TempDir dir: Path) {
        service(mockk<ChatModel>(), registeredStore(dir)).answer("nope", "c1", true)
    }

    // M8: a tool's live output streams between its tool_call and its tool_result.
    @Test
    fun `tool output chunks stream as tool_output events before the result`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val talky = object : Tool {
            override val name = "talky"
            override val description = "d"
            override val inputSchema = "{}"
            override val isReadOnly = true
            override val isConcurrencySafe = true
            override val isDestructive = false
            override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String {
                onOutput("a")
                onOutput("b")
                return "ab"
            }
        }
        val service = service(chatModel, store, listOf(talky))
        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(Flux.just(toolCall("talky")), Flux.just(delta("done")))

        val chunks = service.chat(req("go")).collectList().block()!!

        chunks.map { it.kind } shouldContainExactly listOf(
            StreamChunk.Kind.TOOL_CALL, StreamChunk.Kind.TOOL_OUTPUT, StreamChunk.Kind.TOOL_OUTPUT,
            StreamChunk.Kind.TOOL_RESULT, StreamChunk.Kind.TOKEN,
        )
        chunks[1].text shouldBe """{"id":"call_1","name":"talky","arguments":"","result":"a"}"""
        chunks[2].text shouldContain "\"result\":\"b\""
    }

    // ---- M9: sub-agents ----

    private fun isChild(p: Prompt) = p.instructions.first().text == AgentLoop.SUBAGENT_PROMPT

    private fun agentCall(id: String, prompt: String) =
        toolCallRef(id, "agent", """{"description":"look around","prompt":"$prompt"}""")

    private fun calls(vararg c: AssistantMessage.ToolCall) =
        ChatResponse(listOf(Generation(AssistantMessage.builder().content("").toolCalls(c.toList()).build())))

    // Parent prompts get the scripted replies in order; a child prompt (told apart by its
    // system message) gets child(prompt). Returns every prompt the model saw.
    private fun fake(model: ChatModel, parent: List<Flux<ChatResponse>>, child: (Prompt) -> Flux<ChatResponse>): MutableList<Prompt> {
        val prompts = Collections.synchronizedList(mutableListOf<Prompt>())
        val n = AtomicInteger()
        every { model.stream(any<Prompt>()) } answers {
            val p = firstArg<Prompt>()
            prompts.add(p)
            if (isChild(p)) child(p) else parent[n.getAndIncrement()]
        }
        return prompts
    }

    private fun dto(c: StreamChunk) = ObjectMapper().readValue(c.text, ToolInvocationDto::class.java)

    private fun resultOf(chunks: List<StreamChunk>, id: String) =
        chunks.filter { it.kind == StreamChunk.Kind.TOOL_RESULT }.map { dto(it) }.single { it.id == id }.result

    private fun toolNames(p: Prompt) = (p.options as OllamaChatOptions).toolCallbacks!!.map { it.toolDefinition.name() }

    private fun oneAgent(model: ChatModel, child: (Prompt) -> Flux<ChatResponse>) =
        fake(model, listOf(Flux.just(calls(agentCall("a1", "inspect"))), Flux.just(delta("ok"))), child)

    @Test
    fun `the agent tool is advertised to the model at the top level only`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val service = service(chatModel, registeredStore(dir), tools())
        val prompts = oneAgent(chatModel) { Flux.just(delta("r")) }

        service.chat(req("q")).collectList().block()!!

        toolNames(prompts.first { !isChild(it) }) shouldContain "agent"
        toolNames(prompts.first { isChild(it) }) shouldContainExactly listOf("currentTime", "fileRead")
    }

    @Test
    fun `the agent tool runs a sub-agent and returns its answer as the tool result`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val service = service(chatModel, registeredStore(dir), tools())
        val prompts = oneAgent(chatModel) { Flux.just(delta("child "), delta("report")) }

        val chunks = service.chat(req("q")).collectList().block()!!

        resultOf(chunks, "a1") shouldBe "child report"
        (prompts.last().instructions.last() as ToolResponseMessage).responses.single().responseData() shouldBe "child report"
        chunks.last() shouldBe StreamChunk(StreamChunk.Kind.TOKEN, "ok")
    }

    @Test
    fun `a sub-agent sees only its prompt, not the parent conversation`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val service = service(chatModel, registeredStore(dir), tools())
        val prompts = oneAgent(chatModel) { Flux.just(delta("r")) }

        service.chat(req("secret parent question")).collectList().block()!!

        val msgs = prompts.first { isChild(it) }.instructions
        msgs shouldHaveSize 2
        msgs[1] shouldBe UserMessage("inspect")
    }

    @Test
    fun `a sub-agent can use read-only tools`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        Files.writeString(wsDir(dir).resolve("a.txt"), "file body")
        val service = service(chatModel, store, tools())
        val prompts = oneAgent(chatModel) {
            if (it.instructions.size == 2) Flux.just(toolCall("fileRead", "c1", """{"path":"a.txt"}"""))
            else Flux.just(delta("read it"))
        }

        val chunks = service.chat(req("q")).collectList().block()!!

        val second = prompts.filter { isChild(it) }[1].instructions.last() as ToolResponseMessage
        second.responses.single().responseData() shouldContain "file body"
        resultOf(chunks, "a1") shouldBe "read it"
    }

    @Test
    fun `a sub-agent calling a mutating tool is denied without asking`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val ws = wsDir(dir)
        val service = service(chatModel, store, tools())
        oneAgent(chatModel) {
            if (it.instructions.size == 2) Flux.just(toolCall("fileWrite", "c1", """{"path":"o.txt","content":"x"}"""))
            else Flux.just(delta("could not"))
        }

        val chunks = service.chat(req("q")).collectList().block()!!

        chunks.none { it.kind == StreamChunk.Kind.PERMISSION } shouldBe true
        Files.exists(ws.resolve("o.txt")) shouldBe false
        store.audit("w1").single { it.tool == "fileWrite" }.decision shouldBe "deny"
    }

    @Test
    fun `sub-agent progress streams as tool_output on the agent call`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        Files.writeString(wsDir(dir).resolve("a.txt"), "body")
        val service = service(chatModel, store, tools())
        oneAgent(chatModel) {
            if (it.instructions.size == 2) Flux.just(delta("hi"), toolCall("fileRead", "c1", """{"path":"a.txt"}"""))
            else Flux.just(delta(" done"))
        }

        val chunks = service.chat(req("q")).collectList().block()!!

        val out = chunks.filter { it.kind == StreamChunk.Kind.TOOL_OUTPUT }.map { dto(it) }
        out.map { it.id }.toSet() shouldBe setOf("a1")
        out.map { it.result } shouldContainExactly listOf("hi", "\n› fileRead {\"path\":\"a.txt\"}\n", " done")
        val kinds = chunks.map { it.kind }
        (kinds.lastIndexOf(StreamChunk.Kind.TOOL_OUTPUT) < kinds.indexOf(StreamChunk.Kind.TOOL_RESULT)) shouldBe true
        // Child events never surface as top-level chunks.
        chunks.filter { it.kind == StreamChunk.Kind.TOOL_CALL }.map { dto(it).id } shouldBe listOf("a1")
        chunks.filter { it.kind == StreamChunk.Kind.TOKEN }.map { it.text } shouldBe listOf("ok")
    }

    // Child A blocks until child B's stream completes: sequential execution would time A out.
    private fun twoAgents(model: ChatModel): MutableList<Prompt> {
        val latch = CountDownLatch(1)
        return fake(
            model,
            listOf(Flux.just(calls(agentCall("a1", "A"), agentCall("b1", "B"))), Flux.just(delta("ok"))),
        ) {
            if ((it.instructions[1] as UserMessage).text == "A") {
                Mono.fromCallable { delta(if (latch.await(3, TimeUnit.SECONDS)) "a" else "timeout") }.flux()
            } else {
                Flux.just(delta("b")).doOnComplete { latch.countDown() }
            }
        }
    }

    @Test
    fun `parallel agent calls in one reply run concurrently`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val service = service(chatModel, registeredStore(dir), tools())
        twoAgents(chatModel)

        val chunks = service.chat(req("q")).collectList().block(java.time.Duration.ofSeconds(10))!!

        resultOf(chunks, "a1") shouldBe "a"
        resultOf(chunks, "b1") shouldBe "b"
    }

    @Test
    fun `results of parallel calls are fed back in call order`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store, tools())
        val prompts = twoAgents(chatModel)

        service.chat(req("q")).collectList().block(java.time.Duration.ofSeconds(10))!!

        val fed = prompts.last { !isChild(it) }.instructions.last() as ToolResponseMessage
        fed.responses.map { it.id() } shouldContainExactly listOf("a1", "b1")
        store.history("w1")[1].tools.map { it.id } shouldContainExactly listOf("a1", "b1")
    }

    @Test
    fun `a call that needs approval runs after the parallel calls`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        wsDir(dir)
        val service = service(chatModel, store, tools())
        val prompts = fake(
            chatModel,
            listOf(
                Flux.just(calls(toolCallRef("w1c", "fileWrite", """{"path":"o.txt","content":"x"}"""), agentCall("a1", "A"))),
                Flux.just(delta("ok")),
            ),
        ) { Flux.just(delta("r")) }

        val chunks = service
            .chat(req("q"))
            .doOnNext { if (it.kind == StreamChunk.Kind.PERMISSION) service.answer("r1", "w1c", false) }
            .collectList()
            .block(java.time.Duration.ofSeconds(10))!!

        val agentResult = chunks.indexOfFirst { it.kind == StreamChunk.Kind.TOOL_RESULT && dto(it).id == "a1" }
        val permission = chunks.indexOfFirst { it.kind == StreamChunk.Kind.PERMISSION }
        (agentResult in 0 until permission) shouldBe true
        // The model still gets the results in its own call order.
        (prompts.last().instructions.last() as ToolResponseMessage).responses.map { it.id() } shouldContainExactly listOf("w1c", "a1")
    }

    @Test
    fun `stop ends running sub-agents`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store, tools())
        val childStarted = AtomicBoolean(false)
        val prompts = oneAgent(chatModel) {
            childStarted.set(true)
            Flux.just(delta("x")).concatWith(Flux.never())
        }

        val future = service.chat(req("q")).collectList().toFuture()
        pollUntil { childStarted.get() }
        service.cancel("r1")

        future.get(2, TimeUnit.SECONDS)
        prompts.count { !isChild(it) } shouldBe 1
        prompts.count { isChild(it) } shouldBe 1
    }

    @Test
    fun `a sub-agent that hits the iteration cap returns an error result`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val service = service(chatModel, registeredStore(dir), tools(), maxIterations = 2)
        oneAgent(chatModel) { Flux.just(delta("partial"), toolCall()) }

        val chunks = service.chat(req("q")).collectList().block()!!

        resultOf(chunks, "a1") shouldBe "error: sub-agent stopped after 2 model calls\npartialpartial"
        chunks.last() shouldBe StreamChunk(StreamChunk.Kind.TOKEN, "ok")
    }

    @Test
    fun `a failing sub-agent becomes an error result, not a failed turn`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val service = service(chatModel, registeredStore(dir), tools())
        oneAgent(chatModel) { Flux.error(RuntimeException("child down")) }

        val chunks = service.chat(req("q")).collectList().block()!!

        resultOf(chunks, "a1") shouldBe "error: child down"
        chunks.none { it.kind == StreamChunk.Kind.ERROR } shouldBe true
        chunks.last() shouldBe StreamChunk(StreamChunk.Kind.TOKEN, "ok")
    }

    @Test
    fun `the agent call is audited as allow`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(chatModel, store, tools())
        oneAgent(chatModel) { Flux.just(delta("r")) }

        service.chat(req("q")).collectList().block()!!

        store.audit("w1").single { it.tool == "agent" }.decision shouldBe "allow"
    }

    // ---- M10: per-message model choice ----

    private fun captureModel(model: ChatModel): () -> Prompt {
        val prompt = slot<Prompt>()
        every { model.stream(capture(prompt)) } returns Flux.just(delta("ok"))
        return { prompt.captured }
    }

    private fun Prompt.chosen() = options as ToolCallingChatOptions

    @Test
    fun `a new workspace falls back to the first provider's default model`(@TempDir dir: Path) {
        val model = mockk<ChatModel>()
        val service = service(model, registeredStore(dir))
        val prompt = captureModel(model)
        service.chat(req("q")).collectList().block()
        prompt().chosen().model shouldBe "llama-x"
        registry.find("w1")!!.lastModel shouldBe "llama-x"
    }

    @Test
    fun `a chat with an explicit provider and model uses them and remembers them for the workspace`(@TempDir dir: Path) {
        val model = mockk<ChatModel>()
        val service = service(model, registeredStore(dir))
        val p = providers.create(ProviderRequest("oai", ProviderType.OPENAI, defaultModel = "gpt"))
        val prompt = captureModel(model)
        service.chat(ChatRequest("w1", "q", "r1", p.id, "gpt-x")).collectList().block()
        prompt().chosen().model shouldBe "gpt-x"
        verify { factory.model(p) }
        registry.find("w1")!!.let {
            it.lastProviderId shouldBe p.id
            it.lastModel shouldBe "gpt-x"
        }
    }

    @Test
    fun `a chat without a model uses the workspace's last provider and model`(@TempDir dir: Path) {
        val model = mockk<ChatModel>()
        val service = service(model, registeredStore(dir))
        val p = providers.create(ProviderRequest("oai", ProviderType.OPENAI, defaultModel = "gpt"))
        val prompt = captureModel(model)
        service.chat(ChatRequest("w1", "q1", "r1", p.id, "gpt-x")).collectList().block()
        service.chat(req("q2", "r2")).collectList().block()
        prompt().chosen().model shouldBe "gpt-x"
        // Another provider without a model: its default, not the workspace's last model.
        service.chat(ChatRequest("w1", "q3", "r3", providers.list().first().id)).collectList().block()
        prompt().chosen().model shouldBe "llama-x"
    }

    @Test
    fun `an unknown provider is a 400 before the stream starts`(@TempDir dir: Path) {
        val model = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(model, store)
        assertFailsWith<IllegalArgumentException> { service.chat(ChatRequest("w1", "q", "r1", "nope")) }
            .message shouldBe "unknown provider: nope"
        val blank = providers.create(ProviderRequest("blank", ProviderType.OLLAMA))
        assertFailsWith<IllegalArgumentException> { service.chat(ChatRequest("w1", "q", "r1", blank.id)) }
            .message shouldBe "no model selected"
        providers.list().forEach { providers.delete(it.id) }
        assertFailsWith<IllegalArgumentException> { service.chat(req("q")) }.message shouldBe "no provider configured"
        // Nothing ran: the workspace is free and no turn was stored.
        store.history("w1") shouldHaveSize 0
    }

    @Test
    fun `the request max tokens come from the chosen model's max output limit`(@TempDir dir: Path) {
        val model = mockk<ChatModel>()
        val service = service(model, registeredStore(dir))
        val p = providers.create(
            ProviderRequest("capped", ProviderType.OLLAMA, defaultModel = "m1", models = listOf(ModelLimits("m1", 1000, 77), ModelLimits("m2", 1000, null))),
        )
        val prompt = captureModel(model)
        service.chat(ChatRequest("w1", "q", "r1", p.id)).collectList().block()
        prompt().chosen().model shouldBe "m1"
        prompt().chosen().maxTokens shouldBe 77
        // No value, or no row at all: the model's own default.
        service.chat(ChatRequest("w1", "q", "r2", p.id, "m2")).collectList().block()
        prompt().chosen().maxTokens shouldBe null
        service.chat(ChatRequest("w1", "q", "r3", p.id, "m3")).collectList().block()
        prompt().chosen().maxTokens shouldBe null
    }

    @Test
    fun `the assistant row records which model answered`(@TempDir dir: Path) {
        val model = mockk<ChatModel>()
        val store = registeredStore(dir)
        val service = service(model, store)
        captureModel(model)
        service.chat(req("q")).collectList().block()
        store.history("w1").map { it.model } shouldBe listOf("", "Ollama/llama-x")
    }

    @Test
    fun `a workspace whose last provider was deleted falls back to the first provider's default model`(@TempDir dir: Path) {
        val model = mockk<ChatModel>()
        val service = service(model, registeredStore(dir))
        val p = providers.create(ProviderRequest("oai", ProviderType.OPENAI, defaultModel = "gpt"))
        val prompt = captureModel(model)
        service.chat(ChatRequest("w1", "q1", "r1", p.id, "gpt-x")).collectList().block()
        providers.delete(p.id)
        service.chat(req("q2", "r2")).collectList().block()
        prompt().chosen().model shouldBe "llama-x"
        registry.find("w1")!!.let {
            it.lastProviderId shouldBe providers.list().first().id
            it.lastModel shouldBe "llama-x"
        }
    }

    // ---- M13: permission modes ----

    // An ASK tool named bash; the real Bash needs landrun.
    private val fakeBash = object : Tool {
        override val name = "bash"
        override val description = "runs"
        override val inputSchema = """{"type":"object"}"""
        override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit) = "ran"
    }

    private val write = """{"path":"out.txt","content":"hi"}"""

    @Test
    fun `in BYPASS mode an ASK tool runs without a dialog and audits allow`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val ws = wsDir(dir)
        registry.setPermissionMode("w1", PermissionMode.BYPASS)
        val service = service(chatModel, store, tools() + fakeBash)
        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(Flux.just(calls(toolCallRef("c1", "fileWrite", write), toolCallRef("c2", "bash", "{}"))), Flux.just(delta("done")))

        val chunks = service.chat(req("q")).collectList().block()!!

        chunks.none { it.kind == StreamChunk.Kind.PERMISSION } shouldBe true
        Files.readString(ws.resolve("out.txt")) shouldBe "hi"
        resultOf(chunks, "c2") shouldBe "ran"
        store.audit("w1").map { it.decision } shouldBe listOf("allow", "allow")
    }

    @Test
    fun `in ACCEPT_EDITS mode fileWrite runs without a dialog but bash still asks`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val ws = wsDir(dir)
        registry.setPermissionMode("w1", PermissionMode.ACCEPT_EDITS)
        val service = service(chatModel, store, tools() + fakeBash)
        every { chatModel.stream(any<Prompt>()) } returnsMany
            listOf(Flux.just(calls(toolCallRef("c1", "fileWrite", write), toolCallRef("c2", "bash", "{}"))), Flux.just(delta("done")))

        val chunks = service.chat(req("q"))
            .doOnNext { if (it.kind == StreamChunk.Kind.PERMISSION) service.answer("r1", dto(it).id, false) }
            .collectList().block(java.time.Duration.ofSeconds(5))!!

        chunks.filter { it.kind == StreamChunk.Kind.PERMISSION }.map { dto(it).name } shouldBe listOf("bash")
        Files.readString(ws.resolve("out.txt")) shouldBe "hi"
        store.audit("w1").associate { it.tool to it.decision } shouldBe mapOf("fileWrite" to "allow", "bash" to "rejected")
    }

    @Test
    fun `in ASK mode fileWrite still asks`(@TempDir dir: Path) {
        val chatModel = mockk<ChatModel>()
        val store = registeredStore(dir)
        val ws = wsDir(dir)
        registry.find("w1")!!.permissionMode shouldBe PermissionMode.ASK
        val service = service(chatModel, store, tools())
        every { chatModel.stream(any<Prompt>()) } returnsMany listOf(Flux.just(toolCall("fileWrite", args = write)), Flux.just(delta("done")))

        val chunks = service.chat(req("q"))
            .doOnNext { if (it.kind == StreamChunk.Kind.PERMISSION) service.answer("r1", "call_1", false) }
            .collectList().block(java.time.Duration.ofSeconds(5))!!

        chunks.count { it.kind == StreamChunk.Kind.PERMISSION } shouldBe 1
        Files.exists(ws.resolve("out.txt")) shouldBe false
        store.audit("w1").single().decision shouldBe "rejected"
    }

    @Test
    fun `a sub-agent inherits the mode but never asks`(@TempDir dir: Path) {
        // BYPASS: the child's fileWrite runs.
        val a = Files.createDirectories(dir.resolve("a"))
        val modelA = mockk<ChatModel>()
        val storeA = registeredStore(a)
        val wsA = wsDir(a)
        registry.setPermissionMode("w1", PermissionMode.BYPASS)
        val serviceA = service(modelA, storeA, tools() + fakeBash)
        val promptsA = oneAgent(modelA) {
            if (it.instructions.size == 2) Flux.just(toolCall("fileWrite", "c1", write)) else Flux.just(delta("wrote"))
        }
        val chunksA = serviceA.chat(req("q")).collectList().block()!!
        chunksA.none { it.kind == StreamChunk.Kind.PERMISSION } shouldBe true
        Files.readString(wsA.resolve("out.txt")) shouldBe "hi"
        resultOf(chunksA, "a1") shouldBe "wrote"
        storeA.audit("w1").single { it.tool == "fileWrite" }.decision shouldBe "allow"
        toolNames(promptsA.first { isChild(it) }) shouldContain "fileWrite"

        // ACCEPT_EDITS: the child's bash would ask, so it is denied.
        val b = Files.createDirectories(dir.resolve("b"))
        val modelB = mockk<ChatModel>()
        val storeB = registeredStore(b)
        wsDir(b)
        registry.setPermissionMode("w1", PermissionMode.ACCEPT_EDITS)
        val serviceB = service(modelB, storeB, tools() + fakeBash)
        val promptsB = oneAgent(modelB) {
            if (it.instructions.size == 2) Flux.just(toolCall("bash", "c1")) else Flux.just(delta("could not"))
        }
        val chunksB = serviceB.chat(req("q")).collectList().block()!!
        chunksB.none { it.kind == StreamChunk.Kind.PERMISSION } shouldBe true
        (promptsB.filter { isChild(it) }[1].instructions.last() as ToolResponseMessage).responses.single().responseData() shouldBe
            "error: tool 'bash' is not available"
        storeB.audit("w1").single { it.tool == "bash" }.decision shouldBe "deny"
    }

    @Test
    fun `the system prompt tells the model to plan in PLAN·md, verify and report`() {
        ChatService.SYSTEM_PROMPT shouldContain "PLAN.md"
        ChatService.SYSTEM_PROMPT shouldContain "verify"
        ChatService.SYSTEM_PROMPT shouldContain "report"
    }
}
