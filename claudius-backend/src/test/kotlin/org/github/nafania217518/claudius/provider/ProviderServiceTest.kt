package org.github.nafania217518.claudius.provider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProviderServiceTest {
    private fun service(dir: Path) =
        ProviderService(ProviderRegistry(WorkspaceRegistry(dir.toString()), "http://ollama:11434", "llama-x"), ChatModelFactory())

    @Test
    fun `an empty registry is seeded with the configured Ollama provider`(@TempDir dir: Path) {
        val p = service(dir).list().single()
        p.name shouldBe "Ollama"
        p.type shouldBe ProviderType.OLLAMA
        p.baseUrl shouldBe "http://ollama:11434"
        p.defaultModel shouldBe "llama-x"
        p.apiKey shouldBe ""
    }

    @Test
    fun `a provider round-trips through the registry`(@TempDir dir: Path) {
        val s = service(dir)
        val p = s.create(ProviderRequest("oai", ProviderType.OPENAI, "https://x/v1", "sk-123456789", "gpt"))
        s.find(p.id) shouldBe p
        s.list().map { it.name } shouldBe listOf("Ollama", "oai")
    }

    @Test
    fun `a blank base url falls back to the provider type default`(@TempDir dir: Path) {
        val s = service(dir)
        s.create(ProviderRequest("a", ProviderType.ANTHROPIC, " ")).baseUrl shouldBe "https://api.anthropic.com"
        s.create(ProviderRequest("o", ProviderType.OPENAI)).baseUrl shouldBe "https://api.openai.com/v1"
    }

    @Test
    fun `a provider with a duplicate name, a bad base url or a non-positive max tokens is rejected`(@TempDir dir: Path) {
        val s = service(dir)
        shouldThrow<IllegalArgumentException> { s.create(ProviderRequest("Ollama", ProviderType.OLLAMA)) }
        shouldThrow<IllegalArgumentException> { s.create(ProviderRequest(" ", ProviderType.OLLAMA)) }
        shouldThrow<IllegalArgumentException> { s.create(ProviderRequest("x", ProviderType.OLLAMA, "ftp://h")) }
        val other = s.create(ProviderRequest("z", ProviderType.OLLAMA))
        shouldThrow<IllegalArgumentException> { s.update(other.id, ProviderRequest("Ollama", ProviderType.OLLAMA)) }
        // Saving a provider under its own name is not a duplicate.
        s.update(other.id, ProviderRequest("z", ProviderType.OLLAMA, defaultModel = "m")).defaultModel shouldBe "m"
    }

    @Test
    fun `updating a provider without an api key keeps the stored key`(@TempDir dir: Path) {
        val s = service(dir)
        val p = s.create(ProviderRequest("oai", ProviderType.OPENAI, apiKey = "sk-secret-key"))
        s.update(p.id, ProviderRequest("oai2", ProviderType.OPENAI)).apiKey shouldBe "sk-secret-key"
        s.update(p.id, ProviderRequest("oai2", ProviderType.OPENAI, apiKey = "")).apiKey shouldBe ""
        s.find(p.id)!!.createdAt shouldBe p.createdAt
    }

    @Test
    fun `models are listed from the Ollama tags endpoint`(@TempDir dir: Path) {
        FakeServer(200, """{"models":[{"name":"b:1"},{"name":"a:2"}]}""").use { srv ->
            val s = service(dir)
            val p = s.create(ProviderRequest("local", ProviderType.OLLAMA, srv.url))
            s.models(p.id) shouldBe listOf("a:2", "b:1")
            srv.requests.first().path shouldBe "/api/tags"
        }
    }

    @Test
    fun `models are listed from an OpenAI-compatible endpoint with a bearer key`(@TempDir dir: Path) {
        FakeServer(200, """{"data":[{"id":"gpt-b"},{"id":"gpt-a"}]}""").use { srv ->
            val s = service(dir)
            val p = s.create(ProviderRequest("oai", ProviderType.OPENAI, srv.url + "/v1", "sk-key"))
            s.models(p.id) shouldBe listOf("gpt-a", "gpt-b")
            srv.path shouldBe "/v1/models"
            srv.headers["authorization"] shouldBe "Bearer sk-key"
        }
    }

    @Test
    fun `models are listed from the Anthropic endpoint with its key and version headers`(@TempDir dir: Path) {
        FakeServer(200, """{"data":[{"id":"claude-z"},{"id":"claude-a"}]}""").use { srv ->
            val s = service(dir)
            val p = s.create(ProviderRequest("ant", ProviderType.ANTHROPIC, srv.url, "ak-key"))
            s.models(p.id) shouldBe listOf("claude-a", "claude-z")
            srv.path shouldBe "/v1/models"
            srv.headers["x-api-key"] shouldBe "ak-key"
            srv.headers["anthropic-version"] shouldBe "2023-06-01"
        }
    }

    @Test
    fun `a failing model list throws without the api key in the message`(@TempDir dir: Path) {
        val s = service(dir)
        FakeServer(500, "boom sk-very-secret").use { srv ->
            val p = s.create(ProviderRequest("oai", ProviderType.OPENAI, srv.url, "sk-very-secret"))
            shouldThrow<ModelListException> { s.models(p.id) }.message!! shouldNotContain "sk-very-secret"
        }
        FakeServer(200, "not json").use { srv ->
            val p = s.create(ProviderRequest("oai2", ProviderType.OPENAI, srv.url, "sk-very-secret"))
            shouldThrow<ModelListException> { s.models(p.id) }
        }
        shouldThrow<NoSuchElementException> { s.models("nope") }
    }

    // ---- per-model limits ----

    @Test
    fun `a fetch for an existing provider without a key uses the stored key`(@TempDir dir: Path) {
        FakeServer(200, """{"data":[{"id":"gpt"}]}""").use { srv ->
            val s = service(dir)
            val p = s.create(ProviderRequest("oai", ProviderType.OPENAI, apiKey = "sk-stored"))
            s.fetch(ModelFetchRequest(ProviderType.OPENAI, srv.url, null, p.id)).map { it.id } shouldBe listOf("gpt")
            srv.headers["authorization"] shouldBe "Bearer sk-stored"
            // An explicit key wins over the stored one.
            s.fetch(ModelFetchRequest(ProviderType.OPENAI, srv.url, "sk-new", p.id))
            srv.headers["authorization"] shouldBe "Bearer sk-new"
        }
    }

    @Test
    fun `Anthropic models carry their context window and max output and all pages are fetched`(@TempDir dir: Path) {
        FakeServer { r ->
            200 to
                if ("after_id=m2" in r.query) """{"data":[{"id":"m3","max_input_tokens":null,"max_tokens":null}],"has_more":false}"""
                else """{"data":[{"id":"m2","max_input_tokens":200000,"max_tokens":64000},{"id":"m1","max_input_tokens":1000000,"max_tokens":128000}],"has_more":true,"last_id":"m2"}"""
        }.use { srv ->
            service(dir).fetch(ModelFetchRequest(ProviderType.ANTHROPIC, srv.url, "ak")) shouldBe listOf(
                ModelLimits("m1", 1000000, 128000),
                ModelLimits("m2", 200000, 64000),
                ModelLimits("m3", null, null),
            )
            srv.requests.map { it.query } shouldBe listOf("limit=1000", "limit=1000&after_id=m2")
        }
    }

    @Test
    fun `OpenAI-compatible models carry limits only when the server reports them`(@TempDir dir: Path) {
        FakeServer(
            200,
            """{"data":[{"id":"plain","created":1,"owned_by":"x"},""" +
                """{"id":"router","context_length":131072,"top_provider":{"max_completion_tokens":8192}}]}""",
        ).use { srv ->
            service(dir).fetch(ModelFetchRequest(ProviderType.OPENAI, srv.url)) shouldBe listOf(
                ModelLimits("plain", null, null),
                ModelLimits("router", 131072, 8192),
            )
            srv.headers["authorization"] shouldBe null
        }
    }

    @Test
    fun `Ollama models carry the context window from the show endpoint`(@TempDir dir: Path) {
        FakeServer { r ->
            when (r.path) {
                "/api/tags" -> 200 to """{"models":[{"name":"qwen"},{"name":"bare"}]}"""
                else ->
                    if ("qwen" in r.body) 200 to """{"model_info":{"general.architecture":"qwen3_5","qwen3_5.context_length":262144}}"""
                    else 200 to """{"model_info":{}}"""
            }
        }.use { srv ->
            service(dir).fetch(ModelFetchRequest(ProviderType.OLLAMA, srv.url)) shouldBe listOf(
                ModelLimits("bare", null, null),
                ModelLimits("qwen", 262144, null),
            )
            srv.requests.filter { it.path == "/api/show" }.map { it.method }.distinct() shouldBe listOf("POST")
            srv.requests.none { "authorization" in it.headers } shouldBe true
        }
    }

    @Test
    fun `an Ollama provider with a key sends it as a bearer token`(@TempDir dir: Path) {
        FakeServer { r -> if (r.path == "/api/tags") 200 to """{"models":[{"name":"q"}]}""" else 200 to """{"model_info":{}}""" }
            .use { srv ->
                service(dir).fetch(ModelFetchRequest(ProviderType.OLLAMA, srv.url, "ok-cloud"))
                srv.requests.map { it.headers["authorization"] } shouldBe listOf("Bearer ok-cloud", "Bearer ok-cloud")
            }
        // Chat side: the factory's Ollama model sends the key on the streaming chat call.
        FakeServer(500, "{}").use { srv ->
            val m = ChatModelFactory().model(Provider("o", "o", ProviderType.OLLAMA, srv.url, "ok-cloud", "q", 0))
            runCatching { m.stream(org.springframework.ai.chat.prompt.Prompt("hi")).blockLast() }
            srv.requests.last().headers["authorization"] shouldBe "Bearer ok-cloud"
        }
    }

    @Test
    fun `a provider saves its models with their limits and lists them`(@TempDir dir: Path) {
        val s = service(dir)
        val p = s.create(ProviderRequest("oai", ProviderType.OPENAI, models = listOf(ModelLimits("b", 10, null), ModelLimits("a", null, 5))))
        s.limits(p.id) shouldBe listOf(ModelLimits("a", null, 5), ModelLimits("b", 10, null))
        s.update(p.id, ProviderRequest("oai", ProviderType.OPENAI, models = listOf(ModelLimits("c", 1, 1))))
        s.limits(p.id) shouldBe listOf(ModelLimits("c", 1, 1))
    }

    @Test
    fun `updating a provider without models keeps the stored models`(@TempDir dir: Path) {
        val s = service(dir)
        val p = s.create(ProviderRequest("oai", ProviderType.OPENAI, models = listOf(ModelLimits("a", 10, 5))))
        s.update(p.id, ProviderRequest("oai2", ProviderType.OPENAI))
        s.limits(p.id) shouldBe listOf(ModelLimits("a", 10, 5))
    }

    @Test
    fun `invalid model limits are rejected`(@TempDir dir: Path) {
        val s = service(dir)
        fun create(vararg m: ModelLimits) = s.create(ProviderRequest("x", ProviderType.OPENAI, models = m.toList()))
        shouldThrow<IllegalArgumentException> { create(ModelLimits(" ", null, null)) }
        shouldThrow<IllegalArgumentException> { create(ModelLimits("a", null, null), ModelLimits("a", 1, 1)) }
        shouldThrow<IllegalArgumentException> { create(ModelLimits("a", 0, null)) }
        shouldThrow<IllegalArgumentException> { create(ModelLimits("a", null, -1)) }
        s.list().map { it.name } shouldBe listOf("Ollama")
    }

    @Test
    fun `deleting a provider deletes its models`(@TempDir dir: Path) {
        val s = service(dir)
        val p = s.create(ProviderRequest("oai", ProviderType.OPENAI, models = listOf(ModelLimits("a", 10, 5))))
        s.delete(p.id)
        s.limits(p.id) shouldBe emptyList()
    }
}
