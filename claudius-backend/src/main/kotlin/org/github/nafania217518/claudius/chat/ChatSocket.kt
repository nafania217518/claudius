package org.github.nafania217518.claudius.chat

import org.github.nafania217518.claudius.chat.dto.ChatRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

// M12: /api/ws, JSON text frames. One workspace subscription per socket: the socket follows
// that workspace's active run (replay + live) and every run that starts there later.
// Client: subscribe, chat, stop, answer, pong. Server: ping, state, started, runs, event, error.
// `runs` lists the workspaces with an active run: on connect and whenever a run starts or ends.
// The server pings every pingMs; a socket silent for timeoutMs is closed (its runs go on).
@Component
class ChatSocket(
    private val chatService: ChatService,
    private val mapper: ObjectMapper,
    @Value("\${claudius.ws.ping-ms:1000}") private val pingMs: Long,
    @Value("\${claudius.ws.timeout-ms:5000}") private val timeoutMs: Long,
) : WebSocketHandler {
    override fun handle(session: WebSocketSession): Mono<Void> {
        val out = Sinks.many().unicast().onBackpressureBuffer<String>()
        val send: (Any) -> Unit = { out.emitNext(mapper.writeValueAsString(it), Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1))) }
        val state = SocketState(send)
        val offStart = chatService.onStart { state.started(it) }
        val runs: (Set<String>) -> Unit = { send(mapOf("type" to "runs", "workspaceIds" to it)) }
        val offRuns = chatService.onRuns(runs)
        runs(chatService.activeIds())
        val lastSeen = AtomicLong(System.currentTimeMillis())
        val pings = Flux.interval(Duration.ofMillis(pingMs)).subscribe {
            if (System.currentTimeMillis() - lastSeen.get() > timeoutMs) session.close().subscribe()
            else send(mapOf("type" to "ping"))
        }
        val input = session.receive()
            .map { it.payloadAsText }
            .doOnNext { lastSeen.set(System.currentTimeMillis()) }
            // JDBC inside start(): off the event loop, one frame at a time per socket.
            .concatMap { text -> Mono.fromRunnable<Void> { onFrame(text, state, send) }.subscribeOn(Schedulers.boundedElastic()) }
            .doFinally {
                pings.dispose()
                offStart()
                offRuns()
                state.follow(null, null, -1)
                out.tryEmitComplete()
            }
            .then()
        return Mono.`when`(input, session.send(out.asFlux().map(session::textMessage)))
    }

    private fun onFrame(text: String, state: SocketState, send: (Any) -> Unit) {
        val f = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            return send(error(null, "bad frame: ${e.message}"))
        }
        when (f.str("type")) {
            "subscribe" -> state.subscribe(f.str("workspaceId")!!, f["after"]?.asInt() ?: -1, chatService)
            "chat" -> {
                val runId = f.str("runId")
                try {
                    val events = chatService.start(
                        ChatRequest(f.str("workspaceId")!!, f.str("message")!!, runId!!, f.str("providerId"), f.str("model"),
                            f["compact"]?.asBoolean() ?: false),
                    )
                    state.followIfNew(events)
                } catch (e: Exception) {
                    send(error(runId, e.message ?: e.javaClass.simpleName))
                }
            }
            "stop" -> chatService.cancel(f.str("runId")!!)
            "answer" -> chatService.answer(f.str("runId")!!, f.str("id")!!, f["approved"]?.asBoolean() ?: false)
            "pong" -> {}
            else -> send(error(null, "unknown frame type: ${f.str("type")}"))
        }
    }

    private fun JsonNode.str(name: String): String? = get(name)?.takeUnless { it.isNull }?.asString()

    private fun error(runId: String?, message: String) =
        listOfNotNull("type" to "error", runId?.let { "runId" to it }, "message" to message).toMap()

    // The socket's one subscription. Lock order: this → RunEvents (event listeners take no lock here).
    private class SocketState(private val send: (Any) -> Unit) {
        private var workspaceId: String? = null
        private var run: RunEvents? = null
        private var off: () -> Unit = {}

        @Synchronized
        fun subscribe(workspaceId: String, after: Int, chatService: ChatService) {
            val active = chatService.active(workspaceId)
            send(mapOf("type" to "state", "workspaceId" to workspaceId, "runId" to active?.runId,
                "lastSeq" to (active?.lastSeq() ?: -1), "model" to active?.label))
            follow(workspaceId, active, after)
        }

        // A run started on the followed workspace (by any socket).
        @Synchronized
        fun started(events: RunEvents) {
            if (events.workspaceId != workspaceId) return
            send(mapOf("type" to "started", "workspaceId" to workspaceId, "runId" to events.runId,
                "message" to events.message, "model" to events.label))
            followIfNew(events)
        }

        // The chat sender: auto-subscribed to the run's workspace.
        @Synchronized
        fun followIfNew(events: RunEvents) {
            if (run !== events) follow(events.workspaceId, events, -1)
        }

        @Synchronized
        fun follow(workspaceId: String?, events: RunEvents?, after: Int) {
            off()
            this.workspaceId = workspaceId
            run = events
            off = events?.subscribe(after) { e ->
                send(mapOf("type" to "event", "workspaceId" to events.workspaceId, "runId" to events.runId,
                    "seq" to e.seq, "event" to e.event, "data" to e.data))
            } ?: {}
        }
    }
}

@Configuration
class ChatSocketConfig {
    @Bean
    fun chatSocketMapping(socket: ChatSocket): HandlerMapping = SimpleUrlHandlerMapping(mapOf("/api/ws" to socket), -1)
}
