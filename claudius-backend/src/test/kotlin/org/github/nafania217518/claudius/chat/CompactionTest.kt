package org.github.nafania217518.claudius.chat

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.github.nafania217518.claudius.chat.dto.ChatRequest
import org.github.nafania217518.claudius.chat.dto.MessageDto
import org.github.nafania217518.claudius.chat.dto.ToolInvocationDto
import org.github.nafania217518.claudius.chat.tool.CurrentTime
import org.github.nafania217518.claudius.chat.tool.ToolRegistry
import org.github.nafania217518.claudius.provider.ChatModelFactory
import org.github.nafania217518.claudius.provider.ModelLimits
import org.github.nafania217518.claudius.provider.Provider
import org.github.nafania217518.claudius.provider.ProviderRegistry
import org.github.nafania217518.claudius.provider.ProviderRequest
import org.github.nafania217518.claudius.provider.ProviderService
import org.github.nafania217518.claudius.provider.ProviderType
import org.github.nafania217518.claudius.workspace.Workspace
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.jdbc.core.JdbcTemplate
import org.sqlite.SQLiteDataSource
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

// M11: context tracking + compaction. Real temp-dir registry/store/providers; a mock ChatModel
// answers stream() (the turn) and call() (the summarizer, "S1", "S2", ...).
class CompactionTest {
    private lateinit var registry: WorkspaceRegistry
    private lateinit var store: SessionStore
    private lateinit var providers: ProviderService
    private val model = mockk<ChatModel>()
    private val factory = mockk<ChatModelFactory>()
    private val prompts = mutableListOf<Prompt>()
    private val summaries = mutableListOf<Prompt>()
    private lateinit var turn: () -> Flux<ChatResponse>
    private var summarize: () -> Flux<ChatResponse> = { Flux.just(ChatResponse(listOf(Generation(AssistantMessage("S${summaries.size}"))))) }

    private fun reply(text: String, usage: Usage?) =
        if (usage == null) ChatResponse(listOf(Generation(AssistantMessage(text))))
        else ChatResponse(listOf(Generation(AssistantMessage(text))), ChatResponseMetadata.builder().usage(usage).build())

    private fun setup(dir: Path, usage: Usage? = null): ChatService {
        registry = WorkspaceRegistry(dir.toString())
        registry.insert(Workspace("w1", "w1", "w1", 0))
        Files.createDirectories(dir.resolve("w1"))
        store = SessionStore(registry, ObjectMapper())
        every { factory.model(any()) } returns model
        every { model.options } returns OllamaChatOptions.builder().build()
        turn = { Flux.just(reply("ok", usage)) }
        // One stream() fake: the summarizer prompt (its system message) goes to summarize, the rest to turn.
        every { model.stream(any<Prompt>()) } answers {
            val p = firstArg<Prompt>()
            if (p.systemMessage.text!!.startsWith(Compactor.INSTRUCTION)) summaries.add(p).let { summarize() } else prompts.add(p).let { turn() }
        }
        // Compaction must not depend on call(): some adapters fail there (see the stream test).
        every { model.call(any<Prompt>()) } throws RuntimeException("`signature` is not set")
        providers = ProviderService(ProviderRegistry(registry, "http://localhost:11434", "llama-x"), ChatModelFactory())
        val loop = AgentLoop(store, ToolRegistry(listOf(CurrentTime())), ObjectMapper(), 25)
        return ChatService(
            store, registry, providers, factory,
            loop, Compactor(store, registry, loop, 2), ObjectMapper(),
        )
    }

    private fun turns(vararg n: Int) = n.forEach {
        store.appendUser("w1", "q$it")
        store.appendAssistant("w1", "a$it")
    }

    private fun windowed(window: Int?, vararg models: String = arrayOf("m"), maxOut: Int? = null): Provider =
        providers.create(ProviderRequest("p", ProviderType.OLLAMA, defaultModel = models[0], models = models.map { ModelLimits(it, window, maxOut) }))

    private fun ChatService.run(r: ChatRequest) = chat(r).collectList().block()!!

    private fun userTexts(p: Prompt) = p.userMessages.map { it.text }

    private fun input() = summaries.last().userMessages.single().text!!

    private fun tokens() = registry.find("w1")!!.contextTokens

    private fun estimate(vararg texts: String) = (ChatService.SYSTEM_PROMPT.length + texts.sumOf { it.length } + toolDefs()).toLong() / 4

    // The definitions the top-level turn advertised (name + description + input schema), read
    // back from a captured turn prompt — the same set every request carries.
    private fun toolDefs(): Int =
        (prompts.first().options as ToolCallingChatOptions).toolCallbacks!!.sumOf {
            it.toolDefinition.name().length + it.toolDefinition.description().length + it.toolDefinition.inputSchema().length
        }

    @Test
    fun `a turn records the workspace context size from the model usage`(@TempDir dir: Path) {
        val s = setup(dir, DefaultUsage(100, 20))
        s.run(ChatRequest("w1", "hello", "r1"))
        tokens() shouldBe 120
    }

    @Test
    fun `a turn without usage estimates the context size from the text`(@TempDir dir: Path) {
        val s = setup(dir)
        s.run(ChatRequest("w1", "hello", "r1"))
        tokens() shouldBe estimate("hello", "ok")
    }

    @Test
    fun `a turn emits a usage event with the context size`(@TempDir dir: Path) {
        val s = setup(dir, DefaultUsage(100, 20))
        s.run(ChatRequest("w1", "hello", "r1")).last() shouldBe StreamChunk(StreamChunk.Kind.USAGE, """{"contextTokens":120}""")
    }

    @Test
    fun `compaction summarizes older turns and keeps the recent ones verbatim`(@TempDir dir: Path) {
        val s = setup(dir)
        store.appendUser("w1", "q1")
        store.appendAssistant("w1", "a1", tools = listOf(ToolInvocationDto("c1", "bash", """{"cmd":"ls"}""", "x".repeat(3000))))
        turns(2, 3)
        val r = s.compact("w1")
        r.summary.role shouldBe "summary"
        r.summary.content shouldBe "S1"
        val p = summaries.single()
        p.systemMessage.text!! shouldStartWith "Summarize the conversation so far so the work can continue"
        (p.options as ToolCallingChatOptions).model shouldBe "llama-x"
        input() shouldContain "User: q1"
        input() shouldContain "[tool bash, arguments {\"cmd\":\"ls\"}] result:\n" + "x".repeat(1000)
        input() shouldNotContain "x".repeat(1001)
        input() shouldContain "Assistant: a1"
        input() shouldNotContain "q2"
        val rows = store.rows("w1")
        rows.last().coversUntil shouldBe rows[1].id
    }

    @Test
    fun `after compaction the model context is the summary plus the turns after it`(@TempDir dir: Path) {
        val s = setup(dir)
        turns(1, 2, 3)
        val r = s.compact("w1")
        tokens() shouldBe r.contextTokens
        s.run(ChatRequest("w1", "q4", "r1"))
        userTexts(prompts.last()) shouldBe listOf("Summary of the earlier conversation:\nS1", "q2", "q3", "q4")
        prompts.last().systemMessage.text shouldBe ChatService.SYSTEM_PROMPT
    }

    @Test
    fun `the transcript keeps every message and includes the summary`(@TempDir dir: Path) {
        val s = setup(dir)
        turns(1, 2, 3)
        s.compact("w1")
        s.run(ChatRequest("w1", "q4", "r1"))
        store.history("w1").map { it.role to it.content } shouldBe listOf(
            "user" to "q1", "assistant" to "a1", "user" to "q2", "assistant" to "a2", "user" to "q3", "assistant" to "a3",
            "summary" to "S1", "user" to "q4", "assistant" to "ok",
        )
    }

    @Test
    fun `a second compaction folds the previous summary into the new one`(@TempDir dir: Path) {
        val s = setup(dir)
        turns(1, 2, 3)
        s.compact("w1")
        turns(4, 5)
        s.compact("w1")
        input() shouldContain "<transcript>\nPrevious summary:\nS1"
        input() shouldContain "User: q3"
        input() shouldNotContain "q1"
        input() shouldNotContain "q4"
        s.run(ChatRequest("w1", "q6", "r1"))
        userTexts(prompts.last()) shouldBe listOf("Summary of the earlier conversation:\nS2", "q4", "q5", "q6")
    }

    @Test
    fun `a chat whose context exceeds the chosen model's window is rejected with the compact-first message`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = windowed(1000)
        turns(1, 2, 3)
        registry.setContextTokens("w1", 1001)
        assertFailsWith<IllegalArgumentException> { s.chat(ChatRequest("w1", "q", "r1", p.id)) }
            .message shouldBe "current context is bigger than context window. should compact first"
        // compact = true is the way out.
        s.run(ChatRequest("w1", "q", "r1", p.id, compact = true)).map { it.kind } shouldContain StreamChunk.Kind.COMPACTED
    }

    @Test
    fun `a chat with compact true compacts first and then runs the turn`(@TempDir dir: Path) {
        val s = setup(dir)
        turns(1, 2, 3)
        val chunks = s.run(ChatRequest("w1", "q4", "r1", compact = true))
        chunks[1].kind shouldBe StreamChunk.Kind.COMPACTED
        val dto = ObjectMapper().readValue(chunks[1].text, MessageDto::class.java)
        dto.role shouldBe "summary"
        dto.content shouldBe "S1"
        chunks shouldContain StreamChunk(StreamChunk.Kind.TOKEN, "ok")
        userTexts(prompts.single()) shouldBe listOf("Summary of the earlier conversation:\nS1", "q2", "q3", "q4")
        store.history("w1").takeLast(3).map { it.role } shouldBe listOf("user", "summary", "assistant")
    }

    @Test
    fun `a turn compacts before it starts only when the next turn would not fit`(@TempDir dir: Path) {
        val s = setup(dir)
        // W 1000, R = min(100, 250) = 100; the last two turns are tiny, so R decides.
        val p = windowed(1000, maxOut = 100)
        turns(1, 2, 3)
        registry.setContextTokens("w1", 900)
        s.run(ChatRequest("w1", "q4", "r1", p.id)).map { it.kind } shouldNotContain StreamChunk.Kind.COMPACTED
        registry.setContextTokens("w1", 901)
        s.run(ChatRequest("w1", "q5", "r2", p.id)).map { it.kind } shouldContain StreamChunk.Kind.COMPACTED
        summaries shouldHaveSize 1
        // K decides: the last two turns are ~500 tokens, so ctx 600 + 500 > 1000.
        turns(6)
        store.appendUser("w1", "q7")
        store.appendAssistant("w1", "x".repeat(2000))
        registry.setContextTokens("w1", 600)
        s.run(ChatRequest("w1", "q8", "r3", p.id)).map { it.kind } shouldContain StreamChunk.Kind.COMPACTED
        summaries shouldHaveSize 2
    }

    @Test
    fun `no compaction while the next turn still fits even above 80 percent`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = windowed(1381, maxOut = 50)
        turns(1, 2, 3)
        registry.setContextTokens("w1", 1256)
        s.run(ChatRequest("w1", "q4", "r1", p.id)).map { it.kind } shouldNotContain StreamChunk.Kind.COMPACTED
        summaries shouldHaveSize 0
    }

    @Test
    fun `compaction keeps only the recent turns that fit a quarter of the window`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = windowed(1000)
        registry.setLastModel("w1", p.id, "m")
        turns(1)
        store.appendUser("w1", "q2:" + "x".repeat(2000)) // ~500 tokens > 250 = W/4
        store.appendAssistant("w1", "a2")
        turns(3)
        s.compact("w1")
        input() shouldContain "User: q1"
        input() shouldContain "q2:"
        input() shouldNotContain "q3"
        val rows = store.rows("w1")
        rows.last().coversUntil shouldBe rows[3].id
    }

    @Test
    fun `the effective window is the smaller of the model window and the workspace limit`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = windowed(100_000)
        turns(1, 2, 3)
        registry.setContextLimit("w1", 70_000)
        registry.setContextTokens("w1", 70_001)
        assertFailsWith<IllegalArgumentException> { s.chat(ChatRequest("w1", "q", "r1", p.id)) }
            .message shouldBe "current context is bigger than context window. should compact first"
        // The model window is the smaller one now.
        registry.setContextLimit("w1", 200_000)
        registry.setContextTokens("w1", 100_001)
        assertFailsWith<IllegalArgumentException> { s.chat(ChatRequest("w1", "q", "r2", p.id)) }
    }

    @Test
    fun `a workspace limit alone sets the window when the model window is unknown`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = windowed(null)
        turns(1, 2, 3)
        registry.setContextLimit("w1", 70_000)
        registry.setContextTokens("w1", 70_001)
        assertFailsWith<IllegalArgumentException> { s.chat(ChatRequest("w1", "q", "r1", p.id)) }
        // Just under: the turn fits after an automatic compaction (ctx + R > W).
        registry.setContextTokens("w1", 69_000)
        s.run(ChatRequest("w1", "q", "r2", p.id)).map { it.kind } shouldContain StreamChunk.Kind.COMPACTED
    }

    @Test
    fun `a compaction before a turn is stored after the new user message and covers only earlier rows`(@TempDir dir: Path) {
        val s = setup(dir)
        turns(1, 2, 3)
        s.run(ChatRequest("w1", "q4", "r1", compact = true))
        val rows = store.rows("w1")
        rows.takeLast(3).map { it.dto.role } shouldBe listOf("user", "summary", "assistant")
        val user = rows[rows.size - 3]
        user.dto.content shouldBe "q4"
        (rows[rows.size - 2].coversUntil!! < user.id) shouldBe true
        input() shouldNotContain "q4"
        userTexts(prompts.single()) shouldBe listOf("Summary of the earlier conversation:\nS1", "q2", "q3", "q4")
    }

    @Test
    fun `a model without a known context window is never compacted automatically`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = windowed(null)
        turns(1, 2, 3)
        registry.setContextTokens("w1", 1_000_000_000)
        s.run(ChatRequest("w1", "q4", "r1", p.id)).map { it.kind } shouldNotContain StreamChunk.Kind.COMPACTED
        summaries shouldHaveSize 0
    }

    @Test
    fun `a failed compaction emits an error and the turn runs uncompacted`(@TempDir dir: Path) {
        val s = setup(dir)
        summarize = { Flux.error(RuntimeException("model down")) }
        turns(1, 2, 3)
        val chunks = s.run(ChatRequest("w1", "q4", "r1", compact = true))
        chunks[1] shouldBe StreamChunk(StreamChunk.Kind.ERROR, "compaction failed: model down")
        userTexts(prompts.single()) shouldBe listOf("q1", "q2", "q3", "q4")
    }

    @Test
    fun `the compact endpoint compacts a workspace and returns the new context size`(@TempDir dir: Path) {
        val s = setup(dir)
        turns(1, 2, 3)
        val r = s.compact("w1")
        r.summary shouldBe store.history("w1").last()
        r.contextTokens shouldBe tokens()
        // Unknown → 404, empty → 400.
        assertFailsWith<NoSuchElementException> { s.compact("nope") }
        registry.insert(Workspace("w2", "w2", "w2", 0))
        assertFailsWith<IllegalArgumentException> { s.compact("w2") }.message shouldBe "nothing to compact"
        // Busy → 409, and the workspace is free again after the run.
        turn = { Flux.never() }
        val running = s.chat(ChatRequest("w1", "q", "r9")).subscribe()
        assertFailsWith<IllegalStateException> { s.compact("w1") }
        running.dispose()
        s.cancel("r9")
        val end = System.currentTimeMillis() + 2000
        while (runCatching { s.compact("w1") }.isFailure && System.currentTimeMillis() < end) Thread.sleep(20)
        summaries shouldHaveSize 2
    }

    @Test
    fun `compaction uses the workspace's last model when it still exists`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = windowed(null, "old", "new")
        s.run(ChatRequest("w1", "q1", "r1", p.id, "old"))
        turns(2, 3)
        s.run(ChatRequest("w1", "q4", "r2", p.id, "new", compact = true))
        (summaries.single().options as ToolCallingChatOptions).model shouldBe "old"
        // Its provider is gone: the chosen model compacts.
        registry.setLastModel("w1", "gone", "old")
        s.run(ChatRequest("w1", "q5", "r3", p.id, "new", compact = true))
        (summaries.last().options as ToolCallingChatOptions).model shouldBe "new"
    }

    @Test
    fun `the summarizer input is cut from the oldest end to fit the compacting model's window`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = windowed(450, "small")
        registry.setLastModel("w1", p.id, "small")
        store.appendSummary("w1", "PREV", 0)
        (1..6).forEach {
            store.appendUser("w1", "q$it:" + "x".repeat(200))
            store.appendAssistant("w1", "a$it")
        }
        s.compact("w1")
        input() shouldContain "<transcript>\nPrevious summary:\nPREV"
        input() shouldNotContain "q1:"
        input() shouldContain "q4:"
        ((input().length + summaries.last().systemMessage.text!!.length) / 4 <= 360) shouldBe true
    }

    @Test
    fun `an old database gains the context and summary columns`(@TempDir dir: Path) {
        val data = Files.createDirectories(dir.resolve(".claudius/sessions"))
        JdbcTemplate(SQLiteDataSource().apply { url = "jdbc:sqlite:${dir.resolve(".claudius/workspaces.db")}" }).run {
            execute("CREATE TABLE workspace (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, dir TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, last_provider_id TEXT, last_model TEXT)")
            update("INSERT INTO workspace (id, name, dir, created_at) VALUES ('w1', 'w1', 'w1', 0)")
        }
        JdbcTemplate(SQLiteDataSource().apply { url = "jdbc:sqlite:${data.resolve("w1.db")}" }).run {
            execute("CREATE TABLE message (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, thinking TEXT NOT NULL DEFAULT '', tools TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)")
            update("INSERT INTO message (role, content, created_at) VALUES ('user', 'old', 0)")
        }
        val reg = WorkspaceRegistry(dir.toString())
        reg.find("w1")!!.contextTokens shouldBe 0
        reg.setContextTokens("w1", 42)
        reg.find("w1")!!.contextTokens shouldBe 42
        val st = SessionStore(reg, ObjectMapper())
        st.appendSummary("w1", "S", 1)
        st.rows("w1").map { it.coversUntil } shouldBe listOf(null, 1L)
        st.history("w1").map { it.role } shouldBe listOf("user", "summary")
        st.appendSummary("w1", "S", 1, "", 10, 5)
        st.history("w1").map { it.tokensBefore to it.tokensAfter } shouldBe listOf(null to null, null to null, 10L to 5L)
    }

    @Test
    fun `a rejected compact-first chat keeps the workspace's last model, so the retry compacts with the big model`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = providers.create(
            ProviderRequest("p", ProviderType.OLLAMA, defaultModel = "big", models = listOf(ModelLimits("big", 10_000, null), ModelLimits("small", 100, null))),
        )
        registry.setLastModel("w1", p.id, "big")
        turns(1, 2, 3)
        registry.setContextTokens("w1", 500)
        assertFailsWith<IllegalArgumentException> { s.chat(ChatRequest("w1", "q", "r1", p.id, "small")) }
        registry.find("w1")!!.lastModel shouldBe "big"
        s.run(ChatRequest("w1", "q", "r2", p.id, "small", compact = true))
        (summaries.single().options as ToolCallingChatOptions).model shouldBe "big"
        registry.find("w1")!!.lastModel shouldBe "small"
    }

    private fun streamOnlySummarizer(vararg chunks: AssistantMessage) {
        summarize = { Flux.fromIterable(chunks.map { ChatResponse(listOf(Generation(it))) }) }
    }

    @Test
    fun `compaction reads the summary from the stream that every adapter supports`(@TempDir dir: Path) {
        val s = setup(dir)
        streamOnlySummarizer(AssistantMessage("Goals: "), AssistantMessage("ship M11."))
        turns(1, 2, 3)
        s.compact("w1").summary.content shouldBe "Goals: ship M11."
        store.history("w1").last().content shouldBe "Goals: ship M11."
    }

    // MessageAggregator appends isThought chunks to the text too; outputWithoutThoughts excludes them.
    @Test
    fun `thinking text never becomes part of the summary`(@TempDir dir: Path) {
        val s = setup(dir)
        val thought = AssistantMessage.builder().content("hmm, let me think").properties(mapOf("isThought" to true)).build()
        val answer = AssistantMessage.builder().content("The summary.").properties(mapOf("isThought" to false)).build()
        streamOnlySummarizer(thought, answer)
        turns(1, 2, 3)
        s.compact("w1").summary.content shouldBe "The summary."
    }

    @Test
    fun `the context estimate counts the advertised tool definitions`(@TempDir dir: Path) {
        val s = setup(dir)
        s.run(ChatRequest("w1", "q1", "r1")) // the turn shows which tools are advertised
        toolDefs() shouldBeGreaterThan 100
        turns(2, 3)
        // Live: q1/ok, q2/a2, q3/a3 → turn 1 is summarized, turns 2 and 3 are kept.
        s.compact("w1").contextTokens shouldBe estimate("Summary of the earlier conversation:\nS1", "q2", "a2", "q3", "a3")
    }

    // A tool-call reply (currentTime) with the given usage.
    private fun callTool(id: String, usage: Usage?, text: String = ""): ChatResponse {
        val msg = AssistantMessage.builder().content(text).toolCalls(listOf(AssistantMessage.ToolCall(id, "function", "currentTime", "{}"))).build()
        return if (usage == null) ChatResponse(listOf(Generation(msg)))
        else ChatResponse(listOf(Generation(msg)), ChatResponseMetadata.builder().usage(usage).build())
    }

    // W 1000, R 100. Calls 1 and 2 call a tool (usage 500, then 950); call 3+ answers.
    // Before call 2: 500 + R fits; before call 3: 950 + R does not → a mid-turn compaction.
    private fun longToolLoop(dir: Path): Pair<ChatService, Provider> {
        val s = setup(dir)
        val p = windowed(1000, maxOut = 100)
        turn = {
            when (prompts.size) {
                1 -> Flux.just(callTool("c1", DefaultUsage(480, 20)))
                2 -> Flux.just(callTool("c2", DefaultUsage(930, 20)))
                else -> Flux.just(reply("done", null))
            }
        }
        return s to p
    }

    @Test
    fun `a long tool loop compacts mid-turn before the next model call would overflow`(@TempDir dir: Path) {
        val (s, p) = longToolLoop(dir)
        val chunks = s.run(ChatRequest("w1", "q1", "r1", p.id))
        summaries shouldHaveSize 1
        summaries.single().systemMessage.text shouldBe Compactor.INSTRUCTION + " " + Compactor.MID_TURN
        input() shouldContain "User: q1"
        input() shouldContain "[tool currentTime"
        chunks.map { it.kind } shouldContain StreamChunk.Kind.COMPACTED
        chunks shouldContain StreamChunk(StreamChunk.Kind.TOKEN, "done")
        // Call 3 sees: system, the summary, the last round (c2's call and result).
        val third = prompts[2].instructions
        third.size shouldBe 4
        third[1].text shouldBe "Summary of the earlier conversation:\nS1"
        (third[2] as AssistantMessage).toolCalls.single().id() shouldBe "c2"
        // Stored at once, right after the user row and covering it.
        val rows = store.rows("w1")
        rows.map { it.dto.role } shouldBe listOf("user", "summary", "assistant")
        rows[1].coversUntil shouldBe rows[0].id
    }

    @Test
    fun `tools summarized mid-turn stay in the transcript but are not replayed`(@TempDir dir: Path) {
        val (s, p) = longToolLoop(dir)
        s.run(ChatRequest("w1", "q1", "r1", p.id))
        store.history("w1").last().tools.map { it.id to it.compacted } shouldBe listOf("c1" to true, "c2" to null)
        s.run(ChatRequest("w1", "q2", "r2", p.id))
        val replayed = prompts.last().instructions.filterIsInstance<AssistantMessage>().flatMap { it.toolCalls }.map { it.id() }
        replayed shouldBe listOf("c2")
    }

    @Test
    fun `a mid-turn compaction failure is reported and the loop continues`(@TempDir dir: Path) {
        val (s, p) = longToolLoop(dir)
        summarize = { Flux.error(RuntimeException("model down")) }
        val chunks = s.run(ChatRequest("w1", "q1", "r1", p.id))
        chunks shouldContain StreamChunk(StreamChunk.Kind.ERROR, "compaction failed: model down")
        chunks shouldContain StreamChunk(StreamChunk.Kind.TOKEN, "done")
        userTexts(prompts[2]) shouldBe listOf("q1")
        store.history("w1").last().tools.map { it.compacted } shouldBe listOf(null, null)
    }

    @Test
    fun `a sub-agent never compacts`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = windowed(1000, maxOut = 100)
        var sub = 0
        val agentCall = AssistantMessage.builder().content("")
            .toolCalls(listOf(AssistantMessage.ToolCall("a1", "function", "agent", """{"description":"d","prompt":"look"}"""))).build()
        turn = {
            val last = prompts.last()
            if (last.systemMessage.text == AgentLoop.SUBAGENT_PROMPT) {
                sub++
                if (sub <= 3) Flux.just(callTool("s$sub", DefaultUsage(980, 10))) else Flux.just(reply("sub done", null))
            } else if (prompts.count { it.systemMessage.text != AgentLoop.SUBAGENT_PROMPT } == 1) {
                Flux.just(ChatResponse(listOf(Generation(agentCall))))
            } else {
                Flux.just(reply("done", null))
            }
        }
        val chunks = s.run(ChatRequest("w1", "q1", "r1", p.id))
        sub shouldBe 4
        summaries shouldHaveSize 0
        chunks.map { it.kind } shouldNotContain StreamChunk.Kind.COMPACTED
    }

    @Test
    fun `the summarizer input renders tools as plain text and forbids tool calls`(@TempDir dir: Path) {
        val s = setup(dir)
        store.appendUser("w1", "q1")
        store.appendAssistant("w1", "a1", tools = listOf(ToolInvocationDto("c1", "fileRead", """{"path":"a"}""", "body")))
        turns(2, 3)
        s.compact("w1")
        summaries.single().systemMessage.text!! shouldContain "Reply with plain text only. Do not call tools."
        input() shouldContain "[tool fileRead, arguments {\"path\":\"a\"}] result:\nbody"
        input() shouldNotContain "fileRead("
    }

    @Test
    fun `long tool results keep their head and tail in the summarizer input`(@TempDir dir: Path) {
        val s = setup(dir)
        store.appendUser("w1", "q1")
        store.appendAssistant("w1", "a1", tools = listOf(ToolInvocationDto("c1", "fileRead", "{}", "H".repeat(1500) + "T".repeat(1499) + "Z")))
        turns(2, 3)
        s.compact("w1")
        input() shouldContain "H".repeat(1000) + "\n…[1000 chars omitted]…\n" + "T".repeat(999) + "Z"
        input() shouldNotContain "H".repeat(1001)
    }

    @Test
    fun `a mid-turn summary includes the assistant text produced so far`(@TempDir dir: Path) {
        val (s, p) = longToolLoop(dir)
        turn = {
            when (prompts.size) {
                1 -> Flux.just(callTool("c1", DefaultUsage(480, 20), "CODE-1 = amber"))
                2 -> Flux.just(callTool("c2", DefaultUsage(930, 20), " CODE-2 = birch"))
                else -> Flux.just(reply("done", null))
            }
        }
        s.run(ChatRequest("w1", "q1", "r1", p.id))
        input() shouldContain "Assistant (so far): CODE-1 = amber CODE-2 = birch"
    }

    @Test
    fun `the summarizer input is a quoted transcript followed by the instruction`(@TempDir dir: Path) {
        val s = setup(dir)
        turns(1, 2, 3)
        s.compact("w1")
        summaries.single().systemMessage.text shouldBe Compactor.INSTRUCTION
        input() shouldStartWith Compactor.LEAD + "\n<transcript>\nUser: q1\nAssistant: a1\n</transcript>\n"
        input().substringAfter("</transcript>") shouldContain Compactor.INSTRUCTION
        input() shouldEndWith "Write the summary now, in plain text."
        // Mid-turn: the transcript ends in the assistant's voice, the instruction still comes last.
        val (s2, p) = longToolLoop(dir.resolve("mid").also { Files.createDirectories(it) })
        s2.run(ChatRequest("w1", "q1", "r1", p.id))
        input().substringAfter("</transcript>") shouldContain Compactor.MID_TURN
        input() shouldEndWith "Write the summary now, in plain text."
    }

    @Test
    fun `a failed summarizer call is retried once`(@TempDir dir: Path) {
        val s = setup(dir)
        turns(1, 2, 3)
        summarize = { if (summaries.size == 1) Flux.error(RuntimeException("EOF")) else Flux.just(reply("S", null)) }
        s.compact("w1").summary.content shouldBe "S"
        summaries shouldHaveSize 2
        summaries[0] shouldBe summaries[1]
        // An empty summary is retried too.
        turns(4, 5)
        summarize = { if (summaries.size == 3) Flux.just(reply("", null)) else Flux.just(reply("S2", null)) }
        s.compact("w1").summary.content shouldBe "S2"
        summaries shouldHaveSize 4
        // A second failure is the error path.
        turns(6, 7)
        summarize = { Flux.error(RuntimeException("down")) }
        assertFailsWith<RuntimeException> { s.compact("w1") }.message shouldBe "down"
        summaries shouldHaveSize 6
    }

    @Test
    fun `a second mid-turn compaction in the same turn succeeds`(@TempDir dir: Path) {
        val (s, p) = longToolLoop(dir)
        turn = {
            when (prompts.size) {
                1 -> Flux.just(callTool("c1", DefaultUsage(480, 20)))
                2 -> Flux.just(callTool("c2", DefaultUsage(930, 20)))
                3 -> Flux.just(callTool("c3", DefaultUsage(930, 20)))
                else -> Flux.just(reply("done", null))
            }
        }
        val chunks = s.run(ChatRequest("w1", "q1", "r1", p.id))
        chunks.map { it.kind } shouldNotContain StreamChunk.Kind.ERROR
        summaries shouldHaveSize 2
        input() shouldContain "Previous summary:\nS1"
        input() shouldContain "[tool currentTime, arguments {}]"
        val rows = store.rows("w1")
        rows.map { it.dto.role } shouldBe listOf("user", "summary", "summary", "assistant")
        rows[2].coversUntil shouldBe rows[0].id
        rows.last().dto.tools.map { it.id to it.compacted } shouldBe listOf("c1" to true, "c2" to true, "c3" to null)
    }

    @Test
    fun `an automatic compaction with nothing to compact is skipped without an error`(@TempDir dir: Path) {
        val s = setup(dir)
        val p = windowed(1000, maxOut = 100)
        // A turn that left no assistant row after a mid-turn summary: nothing is uncovered.
        store.appendUser("w1", "q1")
        store.appendSummary("w1", "S0", store.rows("w1").last().id)
        registry.setContextTokens("w1", 950)
        val chunks = s.run(ChatRequest("w1", "q2", "r1", p.id))
        chunks.map { it.kind } shouldNotContain StreamChunk.Kind.ERROR
        chunks.map { it.kind } shouldNotContain StreamChunk.Kind.COMPACTED
        summaries shouldHaveSize 0
        // An explicit request still reports it.
        store.appendSummary("w1", "S1", store.rows("w1").last().id)
        s.run(ChatRequest("w1", "q3", "r2", p.id, compact = true)) shouldContain
            StreamChunk(StreamChunk.Kind.ERROR, "compaction failed: nothing to compact")
    }

    private fun kinds(chunks: List<StreamChunk>) = chunks.map { it.kind }.filter { it != StreamChunk.Kind.TOKEN && it != StreamChunk.Kind.TOOL_CALL && it != StreamChunk.Kind.TOOL_RESULT }

    @Test
    fun `a compaction emits a compacting event before the summary`(@TempDir dir: Path) {
        val s = setup(dir)
        val compacting = StreamChunk(StreamChunk.Kind.COMPACTING, "{}")
        turns(1, 2, 3)
        // compact = true: compacting, then compacted.
        s.run(ChatRequest("w1", "q4", "r1", compact = true)).take(2).map { it.kind } shouldBe
            listOf(StreamChunk.Kind.COMPACTING, StreamChunk.Kind.COMPACTED)
        // A failure: compacting, then error.
        summarize = { Flux.error(RuntimeException("model down")) }
        s.run(ChatRequest("w1", "q5", "r2", compact = true)).take(2) shouldBe
            listOf(compacting, StreamChunk(StreamChunk.Kind.ERROR, "compaction failed: model down"))
        // Automatic pre-turn: compacting, then compacted.
        summarize = { Flux.just(ChatResponse(listOf(Generation(AssistantMessage("S${summaries.size}"))))) }
        val p = windowed(1000, maxOut = 100)
        registry.setContextTokens("w1", 950)
        s.run(ChatRequest("w1", "q6", "r3", p.id)).take(2).map { it.kind } shouldBe
            listOf(StreamChunk.Kind.COMPACTING, StreamChunk.Kind.COMPACTED)
    }

    @Test
    fun `a mid-turn compaction emits a compacting event before the summary and a skipped one emits nothing`(@TempDir dir: Path) {
        val (s, p) = longToolLoop(dir)
        kinds(s.run(ChatRequest("w1", "q1", "r1", p.id))) shouldBe
            listOf(StreamChunk.Kind.COMPACTING, StreamChunk.Kind.COMPACTED, StreamChunk.Kind.USAGE)
        // Skipped automatic trigger: nothing uncovered → no compacting event.
        val s2 = setup(dir.resolve("b").also { Files.createDirectories(it) })
        val p2 = windowed(1000, maxOut = 100)
        store.appendUser("w1", "q1")
        store.appendSummary("w1", "S0", store.rows("w1").last().id)
        registry.setContextTokens("w1", 950)
        s2.run(ChatRequest("w1", "q2", "r1", p2.id)).map { it.kind } shouldNotContain StreamChunk.Kind.COMPACTING
    }

    @Test
    fun `a summary row records the context size before and after`(@TempDir dir: Path) {
        val s = setup(dir)
        s.run(ChatRequest("w1", "q1", "r1")) // captures the tool definitions
        turns(2, 3)
        // Manual: before = the stored context size, after = the new stored one.
        registry.setContextTokens("w1", 777)
        val manual = s.compact("w1")
        manual.summary.tokensBefore shouldBe 777
        manual.summary.tokensAfter shouldBe manual.contextTokens
        store.history("w1").last().tokensBefore shouldBe 777
        store.history("w1").last().tokensAfter shouldBe manual.contextTokens
        // compact = true: the same, and the compacted event carries them.
        registry.setContextTokens("w1", 555)
        val dto = ObjectMapper().readValue(s.run(ChatRequest("w1", "q4", "r2", compact = true))[1].text, MessageDto::class.java)
        dto.tokensBefore shouldBe 555
        dto.tokensAfter shouldBe estimate("Summary of the earlier conversation:\nS2", "q4")
        store.history("w1").first { it.content == "S2" }.tokensAfter shouldBe dto.tokensAfter
        // Non-summary rows carry none, and their JSON omits the fields.
        store.history("w1").first().tokensBefore shouldBe null
        ObjectMapper().writeValueAsString(store.history("w1").first()) shouldNotContain "tokens"
    }

    @Test
    fun `a mid-turn summary row records the context size before and after`(@TempDir dir: Path) {
        val (s, p) = longToolLoop(dir)
        s.run(ChatRequest("w1", "q1", "r1", p.id))
        val summary = store.history("w1")[1]
        // before = the trigger's number: the last usage (950) + the new tool results' chars/4.
        val c2 = store.history("w1").last().tools.single { it.id == "c2" }
        summary.tokensBefore shouldBe 950 + c2.result.length / 4L
        // after = the estimate of the new loop list (what call 3 sees) + the tool definitions.
        summary.tokensAfter shouldBe estimate(prompts[2].instructions, toolDefs())
    }

    @Test
    fun `an old message JSON without the size fields still loads`() {
        val dto = ObjectMapper().readValue("""{"role":"summary","content":"S","thinking":"","createdAt":5,"tools":[],"model":""}""", MessageDto::class.java)
        dto.tokensBefore shouldBe null
        dto.tokensAfter shouldBe null
    }

    // ---- M13: CLAUDIUS.md ----

    private val claudiusMd = "\n\nWorkspace instructions (CLAUDIUS.md):\n"

    @Test
    fun `the context appends CLAUDIUS·md from the workspace when it exists`(@TempDir dir: Path) {
        val s = setup(dir)
        Files.writeString(dir.resolve("w1/CLAUDIUS.md"), "always answer in uppercase")
        s.run(ChatRequest("w1", "q", "r1"))
        prompts.single().systemMessage.text shouldBe ChatService.SYSTEM_PROMPT + claudiusMd + "always answer in uppercase"
    }

    @Test
    fun `the context is the base prompt when the workspace has no CLAUDIUS·md`(@TempDir dir: Path) {
        val s = setup(dir)
        Files.createDirectories(dir.resolve("w1/CLAUDIUS.md")) // a dir, not a file
        s.run(ChatRequest("w1", "q", "r1"))
        prompts.single().systemMessage.text shouldBe ChatService.SYSTEM_PROMPT
    }

    @Test
    fun `CLAUDIUS·md is cut at 16000 characters`(@TempDir dir: Path) {
        val s = setup(dir)
        Files.writeString(dir.resolve("w1/CLAUDIUS.md"), "x".repeat(20_000))
        s.run(ChatRequest("w1", "q", "r1"))
        prompts.single().systemMessage.text shouldBe ChatService.SYSTEM_PROMPT + claudiusMd + "x".repeat(16_000)
    }
}
