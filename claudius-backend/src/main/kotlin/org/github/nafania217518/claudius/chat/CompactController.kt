package org.github.nafania217518.claudius.chat

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

// M11: manual compaction. Unknown workspace → 404 and "nothing to compact" → 400 (via
// WorkspaceExceptionHandler); a busy workspace → 409.
@RestController
class CompactController(private val chatService: ChatService) {
    @PostMapping("/api/workspaces/{id}/compact")
    fun compact(@PathVariable id: String): Mono<CompactResult> =
        Mono.fromCallable { chatService.compact(id) }.subscribeOn(Schedulers.boundedElastic())

    @ExceptionHandler(IllegalStateException::class)
    fun conflict(e: IllegalStateException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(e.message)
}
