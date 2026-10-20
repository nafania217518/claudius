package org.github.nafania217518.claudius.provider

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.file.Files

// HTTP slice over a real registry in a temp dir; the workspace advice maps 400/404.
@WebFluxTest(ProviderController::class)
class ProviderControllerTest {
    @Autowired
    private lateinit var web: WebTestClient

    @TestConfiguration
    class Config {
        @Bean
        fun providerService() =
            ProviderService(
                ProviderRegistry(WorkspaceRegistry(Files.createTempDirectory("claudius-prov").toString()), "http://localhost:11434", "m"),
                ChatModelFactory(),
            )
    }

    private fun post(body: ProviderRequest) =
        web.post().uri("/api/providers").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()

    private fun create(body: ProviderRequest): ProviderDto =
        post(body).expectStatus().isOk.expectBody(ProviderDto::class.java).returnResult().responseBody!!

    private fun list(): List<ProviderDto> =
        web.get().uri("/api/providers").exchange().expectStatus().isOk
            .returnResult(ProviderDto::class.java).responseBody.collectList().block()!!

    @Test
    fun `the provider list masks api keys`() {
        create(ProviderRequest("long", ProviderType.OPENAI, apiKey = "sk-abcdefgh1234"))
        create(ProviderRequest("short", ProviderType.OPENAI, apiKey = "sk-1"))
        val keys = list().associate { it.name to it.apiKey }
        keys["Ollama"] shouldBe ""
        keys["long"] shouldBe "…1234"
        keys["short"] shouldBe "…"
        web.get().uri("/api/providers").exchange().expectBody(String::class.java)
            .consumeWith { it.responseBody!! shouldNotContain "sk-abcdefgh" }
    }

    @Test
    fun `the provider REST endpoints create update delete and list models`() {
        FakeServer(200, """{"models":[{"name":"q"}]}""").use { srv ->
            val p = create(ProviderRequest("crud", ProviderType.OLLAMA, srv.url, defaultModel = "q"))
            web.put().uri("/api/providers/${p.id}").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ProviderRequest("crud2", ProviderType.OLLAMA, srv.url, models = listOf(ModelLimits("q", 50, null)))).exchange()
                .expectStatus().isOk.expectBody(ProviderDto::class.java)
                .consumeWith { it.responseBody!!.models shouldBe listOf(ModelLimits("q", 50, null)) }
            web.get().uri("/api/providers/${p.id}/models").exchange().expectStatus().isOk
                .expectBody(String::class.java).consumeWith { it.responseBody shouldBe """["q"]""" }
            web.delete().uri("/api/providers/${p.id}").exchange().expectStatus().isNoContent
            web.delete().uri("/api/providers/${p.id}").exchange().expectStatus().isNotFound
            web.get().uri("/api/providers/${p.id}/models").exchange().expectStatus().isNotFound
        }
        post(ProviderRequest(" ", ProviderType.OLLAMA)).expectStatus().isBadRequest
    }

    @Test
    fun `a failing model list is a 502 without the api key in the message`() {
        FakeServer(401, "bad key sk-topsecret99").use { srv ->
            val p = create(ProviderRequest("fail", ProviderType.OPENAI, srv.url, "sk-topsecret99"))
            web.get().uri("/api/providers/${p.id}/models").exchange().expectStatus().isEqualTo(502)
                .expectBody(String::class.java).consumeWith {
                    it.responseBody!! shouldNotContain "topsecret"
                    it.responseBody!! shouldBe "the provider returned HTTP 401"
                }
        }
    }

    @Test
    fun `models are fetched before saving from the type, base url and key`() {
        FakeServer(200, """{"data":[{"id":"b"},{"id":"a","context_length":8}]}""").use { srv ->
            web.post().uri("/api/providers/models").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("type" to "OPENAI", "baseUrl" to srv.url, "apiKey" to "sk-pre")).exchange()
                .expectStatus().isOk.expectBody(String::class.java).consumeWith {
                    it.responseBody shouldBe """[{"id":"a","contextWindow":8,"maxOutputTokens":null},{"id":"b","contextWindow":null,"maxOutputTokens":null}]"""
                }
            srv.headers["authorization"] shouldBe "Bearer sk-pre"
        }
        // A blank base url is the type default; nothing listens there in a test, so only the
        // 502 path is checked for a dead host.
        web.post().uri("/api/providers/models").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("type" to "OLLAMA", "baseUrl" to "http://127.0.0.1:1")).exchange()
            .expectStatus().isEqualTo(502)
    }
}
