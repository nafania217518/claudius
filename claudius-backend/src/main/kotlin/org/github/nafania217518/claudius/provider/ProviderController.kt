package org.github.nafania217518.claudius.provider

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

// M10: provider CRUD + model list. Blocking JDBC/HTTP runs on boundedElastic. 400/404 map
// through WorkspaceExceptionHandler; a failing model list is a 502.
@RestController
@RequestMapping("/api/providers")
class ProviderController(private val service: ProviderService) {
    @GetMapping
    fun list(): Mono<List<ProviderDto>> = blocking { service.list().map { it.toDto(service.limits(it.id)) } }

    @PostMapping
    fun create(@RequestBody req: ProviderRequest): Mono<ProviderDto> = blocking { service.create(req).let { it.toDto(service.limits(it.id)) } }

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody req: ProviderRequest): Mono<ProviderDto> =
        blocking { service.update(id, req).let { it.toDto(service.limits(it.id)) } }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String): Mono<Void> = blocking { service.delete(id) }.then()

    @GetMapping("/{id}/models")
    fun models(@PathVariable id: String): Mono<List<String>> = blocking { service.models(id) }

    @PostMapping("/models")
    fun fetch(@RequestBody req: ModelFetchRequest): Mono<List<ModelLimits>> = blocking { service.fetch(req) }

    @ExceptionHandler(ModelListException::class)
    fun badGateway(e: ModelListException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(e.message)

    private fun <T : Any> blocking(f: () -> T): Mono<T> = Mono.fromCallable(f).subscribeOn(Schedulers.boundedElastic())
}
