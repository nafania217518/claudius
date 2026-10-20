package org.github.nafania217518.claudius.workspace

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

// Maps service-level exceptions to HTTP: blank/duplicate name → 400, unknown id → 404.
@RestControllerAdvice
class WorkspaceExceptionHandler {
    // ponytail: every require/throw site carries a non-null message, so e.message is always
     // present — no ?: fallback (an unused branch would be un-coverable).
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.message)

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.message)
}
