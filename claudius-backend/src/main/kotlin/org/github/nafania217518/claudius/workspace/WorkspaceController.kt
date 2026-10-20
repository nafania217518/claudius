package org.github.nafania217518.claudius.workspace

import org.github.nafania217518.claudius.chat.SessionStore
import org.github.nafania217518.claudius.chat.dto.MessageDto
import org.github.nafania217518.claudius.workspace.dto.ContextLimitRequest
import org.github.nafania217518.claudius.workspace.dto.CreateWorkspaceRequest
import org.github.nafania217518.claudius.workspace.dto.PermissionModeRequest
import org.github.nafania217518.claudius.workspace.dto.RenameWorkspaceRequest
import org.github.nafania217518.claudius.workspace.dto.WorkspaceDto
import org.github.nafania217518.claudius.workspace.dto.toDto
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

// M2: workspace CRUD + transcript load. Blocking JDBC runs off the event loop via boundedElastic.
// ponytail: this controller reaches into the chat pkg's SessionStore for /messages (one session
// per workspace) — the workspace⇄chat cycle is fine in a single module.
@RestController
@RequestMapping("/api/workspaces")
class WorkspaceController(
    private val service: WorkspaceService,
    private val store: SessionStore,
) {
    @GetMapping
    fun list(): Mono<List<WorkspaceDto>> =
        Mono.fromCallable { service.list().map { it.toDto() } }
             .subscribeOn(Schedulers.boundedElastic())

    @PostMapping
    fun create(@RequestBody req: CreateWorkspaceRequest): Mono<WorkspaceDto> =
        Mono.fromCallable { service.create(req.name).toDto() }
             .subscribeOn(Schedulers.boundedElastic())

    @PutMapping("/{id}")
    fun rename(@PathVariable id: String, @RequestBody req: RenameWorkspaceRequest): Mono<WorkspaceDto> =
        Mono.fromCallable { service.rename(id, req.name).toDto() }
             .subscribeOn(Schedulers.boundedElastic())

    // M11: PUT {"contextLimit": n | null}; null clears it.
    @PutMapping("/{id}/context-limit")
    fun contextLimit(@PathVariable id: String, @RequestBody req: ContextLimitRequest): Mono<WorkspaceDto> =
        Mono.fromCallable { service.setContextLimit(id, req.contextLimit).toDto() }
             .subscribeOn(Schedulers.boundedElastic())

    // M13: PUT {"permissionMode": "ASK" | "ACCEPT_EDITS" | "BYPASS"}; an unknown value is a 400 (decoding error).
    @PutMapping("/{id}/permission-mode")
    fun permissionMode(@PathVariable id: String, @RequestBody req: PermissionModeRequest): Mono<WorkspaceDto> =
        Mono.fromCallable { service.setPermissionMode(id, req.permissionMode).toDto() }
             .subscribeOn(Schedulers.boundedElastic())

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): Mono<Void> =
        Mono.fromCallable { service.delete(id) }
             .subscribeOn(Schedulers.boundedElastic())
             .then()

    // GET /api/workspaces/{id}/messages — the transcript the frontend loads to resume a thread.
    @GetMapping("/{id}/messages")
    fun messages(@PathVariable id: String): Mono<List<MessageDto>> =
        Mono.fromCallable { store.history(id) }
             .subscribeOn(Schedulers.boundedElastic())
}
