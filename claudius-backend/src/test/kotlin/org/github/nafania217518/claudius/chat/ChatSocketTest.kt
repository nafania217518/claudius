package org.github.nafania217518.claudius.chat

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.github.nafania217518.claudius.provider.ChatModelFactory
import org.github.nafania217518.claudius.provider.Provider
import org.github.nafania217518.claudius.provider.ProviderRequest
import org.github.nafania217518.claudius.provider.ProviderService
import org.github.nafania217518.claudius.provider.ProviderType
import org.github.nafania217518.claudius.workspace.Workspace
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// M12: the WebSocket transport + detached runs, end to end on a random port. A fake ChatModel
// answers every provider unless `fake` is null (then the real adapters talk to a fake server).
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["claudius.bash.sandbox=none", "claudius.ws.ping-ms=200", "claudius.ws.timeout-ms=1000"],
)
class ChatSocketTest {
    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun testFactory() = TestFactory()
    }

    class TestFactory : ChatModelFactory() {
        @Volatile
        var fake: ChatModel? = null

        override fun model(p: Provider): ChatModel = fake ?: super.model(p)
    }

    @LocalServerPort
    var port = 0

    @Autowired
    lateinit var factory: TestFactory

    @Autowired
    lateinit var registry: WorkspaceRegistry

    @Autowired
    lateinit var sessions: SessionStore

    @Autowired
    lateinit var providers: ProviderService

    private val mapper = ObjectMapper()
    private val model = mockk<ChatModel>()
    private val calls = AtomicInteger()

    @BeforeEach
    fun setUp() {
        every { model.options } returns OllamaChatOptions.builder().build()
        factory.fake = model
    }

    // A fresh workspace per test: the Spring context (and its runs) is shared.
    private fun workspace(): String {
        val id = "w" + UUID.randomUUID().toString().take(8)
        Files.createDirectories(registry.dataDir().parent.resolve(id))
        registry.insert(Workspace(id, id, id, System.currentTimeMillis()))
        return id
    }

    private fun delta(text: String) = ChatResponse(listOf(Generation(AssistantMessage(text))))

    private fun toolCall(name: String, args: String) = ChatResponse(
        listOf(Generation(AssistantMessage.builder().content("").toolCalls(listOf(AssistantMessage.ToolCall("c1", "function", name, args))).build())),
    )

    // n tokens, one every `every` ms.
    private fun slow(n: Int, every: Long = 100) {
        every { model.stream(any<Prompt>()) } answers {
            calls.incrementAndGet()
            Flux.range(0, n).delayElements(Duration.ofMillis(every)).map { delta("t$it ") }
        }
    }

    private fun json(vararg pairs: Pair<String, Any?>) = mapper.writeValueAsString(mapOf(*pairs))

    private fun chat(ws: String, runId: String = UUID.randomUUID().toString(), vararg extra: Pair<String, Any?>) =
        json("type" to "chat", "workspaceId" to ws, "runId" to runId, "message" to "hi", *extra)

    private fun subscribe(ws: String, after: Int = -1) = json("type" to "subscribe", "workspaceId" to ws, "after" to after)

    private fun pollUntil(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        cond() shouldBe true
    }

    // A test client: collects every non-ping frame; answers pings unless autoPong is false.
    // `runs` frames go to their own queue.
    inner class Ws(private val autoPong: Boolean = true) : AutoCloseable {
        val frames = LinkedBlockingQueue<JsonNode>()
        val pings = LinkedBlockingQueue<Long>()
        val runs = LinkedBlockingQueue<JsonNode>()
        val closed = CountDownLatch(1)
        private val out = Sinks.many().unicast().onBackpressureBuffer<String>()
        private val conn = ReactorNettyWebSocketClient()
            .execute(URI("ws://localhost:$port/api/ws")) { s ->
                s.send(out.asFlux().map(s::textMessage)).and(
                    s.receive().map { it.payloadAsText }.doOnNext {
                        val n = mapper.readTree(it)
                        if (n["type"].asString() == "ping") {
                            pings.add(System.currentTimeMillis())
                            if (autoPong) send(json("type" to "pong"))
                        } else if (n["type"].asString() == "runs") runs.add(n)
                        else frames.add(n)
                    }.then(),
                )
            }
            .doFinally { closed.countDown() }
            .subscribe()

        fun send(text: String) {
            out.emitNext(text, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)))
        }

        fun next(timeoutMs: Long = 5000): JsonNode = frames.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("no frame")

        fun nextOf(type: String): JsonNode {
            while (true) next().let { if (it["type"].asString() == type) return it }
        }

        // Event frames up to and including `done`.
        fun untilDone(timeoutMs: Long = 5000): List<JsonNode> {
            val list = mutableListOf<JsonNode>()
            while (list.lastOrNull()?.get("event")?.asString() != "done") {
                val f = next(timeoutMs)
                if (f["type"].asString() == "event") list.add(f)
            }
            return list
        }

        override fun close() = conn.dispose()
    }

    private fun seqs(events: List<JsonNode>) = events.map { it["seq"].asInt() }

    @Test
    fun `a chat over the websocket streams the run's events with increasing seq and ends with done`() {
        val ws = workspace()
        every { model.stream(any<Prompt>()) } returns Flux.just(delta("Hel"), delta("lo"))
        Ws().use { c ->
            c.send(chat(ws, "r1"))
            // An auto-subscribe from chat sends no state frame (never a null runId while streaming).
            val first = c.next()
            first["type"].asString() shouldBe "event"
            val events = listOf(first) + c.untilDone()
            seqs(events) shouldBe events.indices.toList()
            events.map { it["event"].asString() } shouldBe listOf("token", "token", "usage", "done")
            events[0]["data"].asString() shouldBe "Hel"
            events[0]["workspaceId"].asString() shouldBe ws
            events[0]["runId"].asString() shouldBe "r1"
        }
    }

    @Test
    fun `a rejected chat returns an error frame and starts no run`() {
        val ws = workspace()
        slow(20)
        Ws().use { c ->
            // Unknown provider.
            c.send(chat(ws, "bad", "providerId" to "nope"))
            c.next().let {
                it["type"].asString() shouldBe "error"
                it["runId"].asString() shouldBe "bad"
                it["message"].asString() shouldBe "unknown provider: nope"
            }
            // Unknown workspace.
            c.send(chat("nope", "nows"))
            c.next()["message"].asString() shouldBe "unknown workspace: nope"
            // Compact first.
            val p = providers.create(ProviderRequest("small", ProviderType.OLLAMA, defaultModel = "m", models = listOf(org.github.nafania217518.claudius.provider.ModelLimits("m", 10, null))))
            registry.setContextTokens(ws, 100)
            c.send(chat(ws, "big", "providerId" to p.id))
            c.next()["message"].asString() shouldBe "current context is bigger than context window. should compact first"
            registry.setContextTokens(ws, 0)
            calls.get() shouldBe 0
            // Busy.
            c.send(chat(ws, "first"))
            c.nextOf("event")
            c.send(chat(ws, "second"))
            val err = c.nextOf("error")
            err["runId"].asString() shouldBe "second"
            err["message"].asString() shouldBe "a run is already active for workspace $ws"
            calls.get() shouldBe 1
            c.send(json("type" to "stop", "runId" to "first"))
            c.untilDone()
        }
    }

    @Test
    fun `closing the socket does not stop the run`() {
        val ws = workspace()
        slow(5)
        Ws().use { c ->
            c.send(chat(ws))
            c.nextOf("event")
        }
        pollUntil { sessions.history(ws).size == 2 }
        sessions.history(ws)[1].content shouldBe "t0 t1 t2 t3 t4 "
    }

    @Test
    fun `a new socket subscribing to the workspace replays the active run's events and continues live`() {
        val ws = workspace()
        slow(6)
        Ws().use { a ->
            a.send(chat(ws, "r4"))
            a.nextOf("event"); a.nextOf("event")
            Ws().use { b ->
                b.send(subscribe(ws))
                b.next().let {
                    it["type"].asString() shouldBe "state"
                    it["workspaceId"].asString() shouldBe ws
                    it["runId"].asString() shouldBe "r4"
                }
                val replayed = b.untilDone()
                seqs(replayed) shouldBe replayed.indices.toList()
                replayed.map { it["data"].asString() } shouldContain "t5 "
            }
        }
    }

    @Test
    fun `subscribe with after replays only newer events`() {
        val ws = workspace()
        slow(6)
        Ws().use { a ->
            a.send(chat(ws, "r5"))
            a.nextOf("event"); a.nextOf("event"); a.nextOf("event")
            Ws().use { b ->
                b.send(subscribe(ws, after = 1))
                b.nextOf("state")
                seqs(b.untilDone()).first() shouldBe 2
            }
        }
    }

    @Test
    fun `subscribing to a workspace without an active run reports no run`() {
        val ws = workspace()
        Ws().use { c ->
            c.send(subscribe(ws))
            val state = c.next()
            state["type"].asString() shouldBe "state"
            state["workspaceId"].asString() shouldBe ws
            state["runId"].isNull shouldBe true
        }
    }

    @Test
    fun `the state frame carries the last seq of the run log`() {
        val ws = workspace()
        slow(6)
        Ws().use { a ->
            a.send(subscribe(ws))
            a.nextOf("state")["lastSeq"].asInt() shouldBe -1
            a.send(chat(ws, "r9"))
            a.nextOf("event"); a.nextOf("event")
            Ws().use { b ->
                b.send(subscribe(ws))
                val state = b.nextOf("state")
                val replayed = b.untilDone()
                // lastSeq = the highest seq at subscribe time: the replay reaches it.
                (state["lastSeq"].asInt() >= 1) shouldBe true
                replayed.map { it["seq"].asInt() } shouldContain state["lastSeq"].asInt()
            }
        }
    }

    @Test
    fun `two sockets on the same workspace both receive the run's events`() {
        val ws = workspace()
        slow(3)
        Ws().use { a ->
            Ws().use { b ->
                b.send(subscribe(ws))
                b.nextOf("state")
                a.send(chat(ws))
                val ea = a.untilDone()
                val eb = b.untilDone()
                eb.map { it["seq"].asInt() to it["data"].asString() } shouldBe ea.map { it["seq"].asInt() to it["data"].asString() }
            }
        }
    }

    @Test
    fun `the state frame carries the active run's model label`() {
        val ws = workspace()
        slow(6)
        Ws().use { a ->
            a.send(subscribe(ws))
            a.nextOf("state")["model"].isNull shouldBe true
            a.send(chat(ws))
            a.nextOf("event")
            Ws().use { b ->
                b.send(subscribe(ws))
                b.nextOf("state")["model"].asString() shouldContain "/"
            }
        }
    }

    @Test
    fun `a socket following a workspace gets a started frame with the message and model before the new run's events`() {
        val ws = workspace()
        slow(3)
        Ws().use { a ->
            Ws().use { b ->
                b.send(subscribe(ws))
                b.nextOf("state")
                a.send(chat(ws, "r-started"))
                val f = b.next()
                f["type"].asString() shouldBe "started"
                f["workspaceId"].asString() shouldBe ws
                f["runId"].asString() shouldBe "r-started"
                f["message"].asString() shouldBe "hi"
                f["model"].asString() shouldContain "/"
                b.untilDone().first()["seq"].asInt() shouldBe 0
            }
        }
    }

    @Test
    fun `every socket gets a runs frame on connect and when a run starts and ends`() {
        val ws = workspace()
        slow(3)
        Ws().use { a ->
            Ws().use { b ->
                fun ids(c: Ws) = c.runs.poll(5, TimeUnit.SECONDS)!!["workspaceIds"].values().map { it.asString() }
                (ws in ids(a)) shouldBe false
                ids(b)
                a.send(chat(ws))
                // Runs of earlier tests may still end: wait for the frames that name this one.
                while (ws !in ids(b)) Unit
                while (ws in ids(b)) Unit
            }
        }
    }

    @Test
    fun `switching the subscription to another workspace does not stop the run`() {
        val ws = workspace()
        val other = workspace()
        slow(5)
        Ws().use { c ->
            c.send(chat(ws))
            c.nextOf("event")
            c.send(subscribe(other))
            c.nextOf("state")["runId"].isNull shouldBe true
            pollUntil { sessions.history(ws).size == 2 }
            sessions.history(ws)[1].content shouldBe "t0 t1 t2 t3 t4 "
            // No more events of the old run reach the socket.
            c.frames.filter { it["type"].asString() == "event" }.size shouldBe 0
        }
    }

    // A slow streaming HTTP server: writes `head` and then `chunk` every 100 ms until the client
    // closes the connection; `closedAt` is when a write fails.
    class SlowServer(head: String, chunk: String) : AutoCloseable {
        val closedAt = AtomicLong(0)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                ex.requestBody.readAllBytes()
                val sse = !ex.requestURI.path.endsWith("/api/chat")
                ex.responseHeaders.add("Content-Type", if (sse) "text/event-stream" else "application/x-ndjson")
                ex.sendResponseHeaders(200, 0)
                try {
                    ex.responseBody.write(head.toByteArray()); ex.responseBody.flush()
                    repeat(300) {
                        ex.responseBody.write(chunk.toByteArray()); ex.responseBody.flush()
                        Thread.sleep(100)
                    }
                } catch (e: IOException) {
                    closedAt.set(System.currentTimeMillis())
                }
                runCatching { ex.close() }
            }
            executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun slowServer(type: ProviderType): SlowServer = when (type) {
        ProviderType.OLLAMA -> SlowServer(
            "",
            """{"model":"m","created_at":"2024-01-01T00:00:00Z","message":{"role":"assistant","content":"x"},"done":false}""" + "\n",
        )
        ProviderType.OPENAI -> SlowServer(
            "",
            """data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"x"},"finish_reason":null}]}""" + "\n\n",
        )
        ProviderType.ANTHROPIC -> SlowServer(
            "event: message_start\ndata: " +
                """{"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","model":"m","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}}""" +
                "\n\nevent: content_block_start\ndata: " +
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""" + "\n\n",
            "event: content_block_delta\ndata: " +
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}""" + "\n\n",
        )
    }

    @ParameterizedTest
    @EnumSource(ProviderType::class)
    fun `stop over the websocket ends the run and closes the upstream provider connection`(type: ProviderType) {
        factory.fake = null
        val ws = workspace()
        slowServer(type).use { server ->
            val p = providers.create(ProviderRequest("slow-$type", type, baseUrl = server.url, apiKey = "k", defaultModel = "m"))
            Ws().use { c ->
                c.send(chat(ws, "r9-$type", "providerId" to p.id))
                c.nextOf("event")["event"].asString() shouldBe "token"
                val stopAt = System.currentTimeMillis()
                c.send(json("type" to "stop", "runId" to "r9-$type"))
                c.untilDone(3000)
                pollUntil(3000) { server.closedAt.get() > 0 }
                (server.closedAt.get() - stopAt) shouldBeLessThan 2000
            }
        }
    }

    @Test
    fun `stop during a compaction cancels the summarizer stream`() {
        val ws = workspace()
        sessions.appendUser(ws, "q0")
        sessions.appendAssistant(ws, "a0")
        val cancelled = AtomicBoolean(false)
        every { model.stream(any<Prompt>()) } answers {
            val p = firstArg<Prompt>()
            if (p.systemMessage.text!!.startsWith(Compactor.INSTRUCTION)) {
                Flux.never<ChatResponse>().doOnCancel { cancelled.set(true) }
            } else {
                calls.incrementAndGet()
                Flux.just(delta("ok"))
            }
        }
        Ws().use { c ->
            c.send(chat(ws, "r10", "compact" to true))
            c.nextOf("event")["event"].asString() shouldBe "compacting"
            c.send(json("type" to "stop", "runId" to "r10"))
            c.untilDone(3000).last()["event"].asString() shouldBe "done"
            cancelled.get() shouldBe true
            calls.get() shouldBe 0
        }
    }

    @Test
    fun `stop kills a running bash command`() {
        val ws = workspace()
        every { model.stream(any<Prompt>()) } answers {
            if (calls.incrementAndGet() == 1) Flux.just(toolCall("bash", """{"command":"sleep 31"}""")) else Flux.just(delta("after"))
        }
        fun sleeping() = ProcessHandle.current().descendants().filter { it.isAlive && it.info().commandLine().orElse("").contains("sleep 31") }.count()
        Ws().use { c ->
            c.send(chat(ws, "r11"))
            while (c.nextOf("event")["event"].asString() != "permission") Unit
            c.send(json("type" to "answer", "runId" to "r11", "id" to "c1", "approved" to true))
            pollUntil { sleeping() > 0 }
            val stopAt = System.currentTimeMillis()
            c.send(json("type" to "stop", "runId" to "r11"))
            c.untilDone(5000)
            (System.currentTimeMillis() - stopAt) shouldBeLessThan 3000
            pollUntil(2000) { sleeping() == 0L }
        }
    }

    @Test
    fun `an answer over the websocket resolves a pending permission and a reconnecting socket sees the pending permission`() {
        val ws = workspace()
        every { model.stream(any<Prompt>()) } answers {
            if (calls.incrementAndGet() == 1) Flux.just(toolCall("bash", """{"command":"echo approved-ran"}""")) else Flux.just(delta("after"))
        }
        Ws().use { a ->
            a.send(chat(ws, "r12"))
            while (a.nextOf("event")["event"].asString() != "permission") Unit
        }
        Ws().use { b ->
            b.send(subscribe(ws))
            b.nextOf("state")["runId"].asString() shouldBe "r12"
            var e: JsonNode
            do e = b.nextOf("event") while (e["event"].asString() != "permission")
            e["data"].asString() shouldContain "echo approved-ran"
            b.send(json("type" to "answer", "runId" to "r12", "id" to "c1", "approved" to true))
            val rest = b.untilDone()
            rest.first { it["event"].asString() == "tool_result" }["data"].asString() shouldContain "approved-ran"
        }
    }

    @Test
    fun `the server pings every second and closes a socket that stops answering`() {
        val ws = workspace()
        slow(20)
        // A silent socket is closed after the timeout (1 s in this test; 5 s by default).
        Ws(autoPong = false).use { c ->
            c.send(chat(ws))
            c.closed.await(3, TimeUnit.SECONDS) shouldBe true
            c.pings.size shouldNotBe 0
        }
        // The run is unaffected.
        pollUntil(5000) { sessions.history(ws).size == 2 }
        // A socket that answers stays open past the timeout, and pings arrive at the interval.
        Ws().use { c ->
            c.closed.await(1500, TimeUnit.MILLISECONDS) shouldBe false
            val p = c.pings.toList()
            p shouldHaveAtLeastSize 5
            (p.last() - p.first()) / (p.size - 1) shouldBeLessThan 400
        }
    }

    @Test
    fun `the old sse chat endpoints are gone`() {
        val client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
        client.post().uri("/api/chat").bodyValue("""{"workspaceId":"w","message":"m","runId":"r"}""")
            .header("Content-Type", "application/json").exchange().expectStatus().isNotFound
        client.post().uri("/api/chat/r/cancel").exchange().expectStatus().isNotFound
        client.post().uri("/api/chat/r/answer").bodyValue("""{"id":"c","approved":true}""")
            .header("Content-Type", "application/json").exchange().expectStatus().isNotFound
    }

    companion object {
        @TempDir
        @JvmStatic
        lateinit var dir: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(r: DynamicPropertyRegistry) {
            r.add("claudius.workspaces-dir") { dir.toString() }
        }
    }
}
