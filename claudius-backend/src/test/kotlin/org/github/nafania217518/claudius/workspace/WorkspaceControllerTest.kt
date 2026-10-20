package org.github.nafania217518.claudius.workspace

import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.github.nafania217518.claudius.chat.SessionStore
import org.github.nafania217518.claudius.chat.dto.MessageDto
import org.github.nafania217518.claudius.chat.dto.ToolInvocationDto
import org.github.nafania217518.claudius.workspace.dto.CreateWorkspaceRequest
import org.github.nafania217518.claudius.workspace.dto.RenameWorkspaceRequest
import org.github.nafania217518.claudius.workspace.dto.WorkspaceDto
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files

// HTTP slice for the workspace CRUD + transcript-load contract. Real registry/store/service
// over a temp workspaces dir (not mocks) keep the persistence round-trip in scope; the
// @RestControllerAdvice is auto-loaded so 400/404 map through the real handler.
@WebFluxTest(WorkspaceController::class)
class WorkspaceControllerTest {
     @Autowired
    private lateinit var webTestClient: WebTestClient

     // Seeding a transcript has no HTTP endpoint, so reach the real store to append turns.
     @Autowired
    private lateinit var store: SessionStore

     @TestConfiguration
    class Config {
         @Bean
        fun workspaceRegistry() =
            WorkspaceRegistry(Files.createTempDirectory("claudius-ws").toString())

         @Bean
        fun sessionStore(registry: WorkspaceRegistry) = SessionStore(registry, ObjectMapper())

         @Bean
        fun workspaceService(registry: WorkspaceRegistry, store: SessionStore) =
            WorkspaceService(registry, store)
     }

     // POST /api/workspaces, returning the created id so the other endpoints can target it.
     private fun create(name: String): String =
        webTestClient
             .post()
             .uri("/api/workspaces")
             .contentType(MediaType.APPLICATION_JSON)
             .bodyValue(CreateWorkspaceRequest(name))
             .exchange()
             .expectStatus()
             .isOk
             .expectBody(WorkspaceDto::class.java)
             .returnResult()
             .responseBody!!
             .id

     @Test
    fun `create then list returns the new workspace`() {
        create("proj")
        webTestClient
             .get()
             .uri("/api/workspaces")
             .exchange()
             .expectStatus()
             .isOk
             .returnResult(WorkspaceDto::class.java)
             .responseBody.collectList().block()!!.map { it.name } shouldContainElement "proj"
     }

     @Test
    fun `rename via put changes the display name`() {
        val id = create("old")
        webTestClient
             .put()
             .uri("/api/workspaces/$id")
             .contentType(MediaType.APPLICATION_JSON)
             .bodyValue(RenameWorkspaceRequest("new"))
             .exchange()
             .expectStatus()
             .isOk
             .expectBody(WorkspaceDto::class.java)
             .consumeWith { it.responseBody!!.name shouldBe "new" }
     }

     @Test
    fun `delete removes the workspace and its transcript`() {
        val id = create("gone")
        webTestClient
             .delete()
             .uri("/api/workspaces/$id")
             .exchange()
             .expectStatus()
             .isOk
        webTestClient
             .get()
             .uri("/api/workspaces/$id/messages")
             .exchange()
             .expectStatus()
             .isNotFound
     }

     @Test
    fun `messages endpoint returns the transcript in order`() {
        val id = create("chat")
        store.appendUser(id, "hi")
        store.appendAssistant(id, "yo")
        webTestClient
             .get()
             .uri("/api/workspaces/$id/messages")
             .exchange()
             .expectStatus()
             .isOk
             .returnResult(MessageDto::class.java)
             .responseBody.collectList().block()!!.let { msgs ->
                 msgs shouldHaveSize 2
                 msgs.map { m -> m.role } shouldBe listOf("user", "assistant")
                 msgs.map { m -> m.content } shouldBe listOf("hi", "yo")
              }
     }

     // M5: the tools trace is a JSON array of objects on the wire, never a string.
     @Test
    fun `messages returns tools as an array`() {
        val id = create("tools")
        store.appendAssistant(id, "now", tools = listOf(ToolInvocationDto("c1", "currentTime", "{}", "12:00")))
        webTestClient
             .get()
             .uri("/api/workspaces/$id/messages")
             .exchange()
             .expectStatus()
             .isOk
             .expectBody(String::class.java)
             .consumeWith {
                 it.responseBody!! shouldContain
                     """"tools":[{"id":"c1","name":"currentTime","arguments":"{}","result":"12:00"}]"""
              }
     }

     // Covers the @RestControllerAdvice badRequest (400) branch.
     @Test
    fun `create with a blank name is a bad request`() {
        webTestClient
             .post()
             .uri("/api/workspaces")
             .contentType(MediaType.APPLICATION_JSON)
             .bodyValue(CreateWorkspaceRequest("   "))
             .exchange()
             .expectStatus()
             .isBadRequest
     }

     // Covers the @RestControllerAdvice notFound (404) branch: an unregistered id.
     @Test
    fun `messages for an unknown workspace is not found`() {
        webTestClient
             .get()
             .uri("/api/workspaces/nope/messages")
             .exchange()
             .expectStatus()
             .isNotFound
     }

    private fun putLimit(id: String, body: String) =
        webTestClient.put().uri("/api/workspaces/$id/context-limit")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()

    @Test
    fun `a workspace context limit is stored and returned`() {
        val id = create("limited")
        putLimit(id, """{"contextLimit":70000}""").expectStatus().isOk
            .expectBody(WorkspaceDto::class.java).consumeWith { it.responseBody!!.contextLimit shouldBe 70000L }
        webTestClient.get().uri("/api/workspaces").exchange()
            .returnResult(WorkspaceDto::class.java).responseBody.collectList().block()!!
            .single { it.id == id }.contextLimit shouldBe 70000L
        putLimit(id, """{"contextLimit":null}""").expectStatus().isOk
            .expectBody(WorkspaceDto::class.java).consumeWith { it.responseBody!!.contextLimit shouldBe null }
        putLimit("nope", """{"contextLimit":null}""").expectStatus().isNotFound
    }

    @Test
    fun `a context limit below 64k is rejected`() {
        val id = create("tiny")
        putLimit(id, """{"contextLimit":65535}""").expectStatus().isBadRequest
            .expectBody(String::class.java).consumeWith { it.responseBody shouldBe "context limit must be at least 65536 tokens" }
        putLimit(id, """{"contextLimit":65536}""").expectStatus().isOk
    }

    // M13: PUT /api/workspaces/{id}/permission-mode.
    private fun putMode(id: String, body: String) =
        webTestClient.put().uri("/api/workspaces/$id/permission-mode")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()

    @Test
    fun `PUT permission-mode saves the mode and returns the workspace`() {
        val id = create("modes")
        putMode(id, """{"permissionMode":"BYPASS"}""").expectStatus().isOk
            .expectBody(WorkspaceDto::class.java).consumeWith { it.responseBody!!.permissionMode shouldBe PermissionMode.BYPASS }
        webTestClient.get().uri("/api/workspaces").exchange()
            .returnResult(WorkspaceDto::class.java).responseBody.collectList().block()!!
            .single { it.id == id }.permissionMode shouldBe PermissionMode.BYPASS
        putMode("nope", """{"permissionMode":"ASK"}""").expectStatus().isNotFound
    }

    @Test
    fun `PUT permission-mode rejects an unknown value with 400`() {
        val id = create("badmode")
        putMode(id, """{"permissionMode":"YOLO"}""").expectStatus().isBadRequest
    }
}
