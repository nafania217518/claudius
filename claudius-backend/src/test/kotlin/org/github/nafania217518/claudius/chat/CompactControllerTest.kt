package org.github.nafania217518.claudius.chat

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.github.nafania217518.claudius.chat.dto.MessageDto
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Bean
import org.springframework.test.web.reactive.server.WebTestClient

// M11: the compact endpoint's HTTP contract over a mock service (the logic is in CompactionTest).
@WebFluxTest(CompactController::class)
class CompactControllerTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @TestConfiguration
    class Config {
        @Bean
        fun chatService() =
            mockk<ChatService> {
                every { compact("w1") } returns CompactResult(MessageDto("summary", "S", "", 5, emptyList(), "Ollama/m"), 42)
                every { compact("sized") } returns
                    CompactResult(MessageDto("summary", "S", "", 5, emptyList(), "Ollama/m", 900, 120), 120)
                every { compact("busy") } throws IllegalStateException("a run is already active for workspace busy")
                every { compact("nope") } throws NoSuchElementException("unknown workspace: nope")
                every { compact("empty") } throws IllegalArgumentException("nothing to compact")
            }
    }

    private fun post(id: String) = webTestClient.post().uri("/api/workspaces/$id/compact").exchange()

    @Test
    fun `the compact endpoint compacts a workspace and returns the new context size`() {
        post("w1").expectStatus().isOk.expectBody(String::class.java).consumeWith {
            it.responseBody shouldBe
                """{"summary":{"role":"summary","content":"S","thinking":"","createdAt":5,"tools":[],"model":"Ollama/m"},"contextTokens":42}"""
        }
    }

    @Test
    fun `the compact endpoint is 409 while a run is active, 404 for an unknown workspace and 400 when empty`() {
        post("busy").expectStatus().isEqualTo(409)
        post("nope").expectStatus().isNotFound
        post("empty").expectStatus().isBadRequest.expectBody(String::class.java).consumeWith { it.responseBody shouldBe "nothing to compact" }
    }

    @Test
    fun `the compact endpoint returns the summary with its before and after sizes`() {
        post("sized").expectStatus().isOk.expectBody(String::class.java).consumeWith {
            it.responseBody shouldBe
                """{"summary":{"role":"summary","content":"S","thinking":"","createdAt":5,"tools":[],"model":"Ollama/m","tokensBefore":900,"tokensAfter":120},"contextTokens":120}"""
        }
    }
}
