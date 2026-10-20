package org.github.nafania217518.claudius.chat

import org.github.nafania217518.claudius.chat.dto.ChatRequest
import org.github.nafania217518.claudius.chat.dto.MessageDto
import org.github.nafania217518.claudius.chat.dto.ToolInvocationDto
import org.github.nafania217518.claudius.provider.ChatModelFactory
import org.github.nafania217518.claudius.provider.ProviderService
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.github.nafania217518.claudius.provider.Provider
import org.github.nafania217518.claudius.workspace.Workspace
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

// M11: the compact endpoint's response.
data class CompactResult(val summary: MessageDto, val contextTokens: Long)

@Service
class ChatService(
    private val sessions: SessionStore,
    private val registry: WorkspaceRegistry,
    private val providers: ProviderService,
    private val models: ChatModelFactory,
    private val loop: AgentLoop,
    private val compactor: Compactor,
    private val mapper: ObjectMapper,
) {
    private val runs = ConcurrentHashMap<String, Pair<Run, RunEvents>>()
    private val active = ConcurrentHashMap<String, RunEvents>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val started = CopyOnWriteArraySet<(RunEvents) -> Unit>()
    private val runsListeners = CopyOnWriteArraySet<(Set<String>) -> Unit>()

    // Idempotent: an unknown runId is a no-op.
    fun cancel(runId: String) {
        runs[runId]?.first?.stop()
    }

    // Idempotent: an unknown runId or call id is a no-op.
    fun answer(runId: String, id: String, approved: Boolean) {
        runs[runId]?.first?.answer(id, approved)
    }

    // M12: the workspace's active run (until `done`), or null.
    fun active(workspaceId: String): RunEvents? = active[workspaceId]

    // M12: listeners told (synchronously, in start) about every new run. Returns the unsubscribe.
    fun onStart(listener: (RunEvents) -> Unit): () -> Unit {
        started.add(listener)
        return { started.remove(listener) }
    }

    // M12: listeners told the workspace ids with an active run, whenever a run starts or ends.
    fun onRuns(listener: (Set<String>) -> Unit): () -> Unit {
        runsListeners.add(listener)
        return { runsListeners.remove(listener) }
    }

    // M12: the workspace ids with an active run.
    fun activeIds(): Set<String> = active.keys.toSet()

    // M12: on startup, close or restore the runs a server stop left behind.
    // `running` (inside a model call or tool): its partial turn is saved with an error, not resumed.
    // `waiting` (parked on a permission): restored as the workspace's active run; it asks again.
    fun recover() {
        for (ws in registry.list()) for (r in sessions.runs(ws.id)) {
            val p = providers.find(r.checkpoint.providerId)
            val choice = p?.let { choiceOf(it, r.checkpoint.model, ws) } ?: lastChoice(ws)
            when {
                r.status == "waiting" && choice != null -> {
                    busy.add(ws.id)
                    launch(ws, r.runId, "", choice.label) { run, emit ->
                        replay(r.checkpoint, emit)
                        runTurn(ws.id, r.runId, p?.id ?: ws.lastProviderId!!, run, emit, choice, r.checkpoint)
                    }
                }
                // ponytail: a waiting run whose provider (and fallback) is gone ends like a running one.
                r.status == "running" || r.status == "waiting" -> {
                    val cp = r.checkpoint
                    sessions.appendAssistant(ws.id, cp.answer, cp.thinking, cp.trace, "${p?.name ?: cp.providerId}/${cp.model}", INTERRUPTED)
                    sessions.saveRun(ws.id, r.runId, "interrupted", cp)
                }
            }
        }
    }

    // M12: a restored run's log starts with its progress so far, as synthetic events.
    private fun replay(cp: Checkpoint, emit: (StreamChunk) -> Unit) {
        for (t in cp.trace) {
            emit(StreamChunk(StreamChunk.Kind.TOOL_CALL, mapper.writeValueAsString(t.copy(result = ""))))
            emit(StreamChunk(StreamChunk.Kind.TOOL_RESULT, mapper.writeValueAsString(t.copy(arguments = ""))))
        }
        if (cp.thinking.isNotEmpty()) emit(StreamChunk(StreamChunk.Kind.REASONING, cp.thinking))
        if (cp.answer.isNotEmpty()) emit(StreamChunk(StreamChunk.Kind.TOKEN, cp.answer))
    }

    // M11: compact on request (POST /api/workspaces/{id}/compact). Same busy lock as a chat.
    // The workspace's last model compacts; without one, the first provider's default model.
    fun compact(workspaceId: String): CompactResult {
        val ws = registry.find(workspaceId) ?: throw NoSuchElementException("unknown workspace: $workspaceId")
        check(busy.add(workspaceId)) { "a run is already active for workspace $workspaceId" }
        try {
            val choice = lastChoice(ws)
                ?: providers.list().firstOrNull()?.let { choiceOf(it, it.defaultModel, ws) }
                ?: throw IllegalArgumentException("no provider configured")
            val summary = compactor.compact(workspaceId, choice)
            return CompactResult(summary, registry.find(workspaceId)!!.contextTokens)
        } finally {
            busy.remove(workspaceId)
        }
    }

    // One user turn → the agent loop, streamed as tagged chunks. The loop re-streams the
    // model until it stops calling tools (or maxIterations), forwards live TOKEN/REASONING
    // deltas, runs each tool_call through the registry, and emits TOOL_CALL/TOOL_RESULT.
    // ponytail: the store's JDBC is blocking, so the loop runs on boundedElastic. One run per
    // workspace at a time: a second chat throws IllegalStateException → 409.
    // M11: a context bigger than the chosen model's window is a 400 unless request.compact.
    // M12: a detached run: it starts on a worker now and no subscriber can stop it (only cancel).
    // Validation throws synchronously: 404 unknown workspace, 400 bad choice/too big, 409 busy.
    fun start(request: ChatRequest): RunEvents {
        val ws = registry.find(request.workspaceId) ?: throw NoSuchElementException("unknown workspace: ${request.workspaceId}")
        // Read before resolve() remembers the new choice: the last model made the current context.
        val last = lastChoice(ws)
        val (providerId, choice) = resolve(request, ws)
        val window = choice.contextWindow
        require(window == null || ws.contextTokens <= window || request.compact) {
            "current context is bigger than context window. should compact first"
        }
        check(busy.add(request.workspaceId)) { "a run is already active for workspace ${request.workspaceId}" }
        // Only now: a rejected chat (400/409) must not change the model that compacts on the retry.
        registry.setLastModel(ws.id, providerId, choice.model)
        return launch(ws, request.runId, request.message, choice.label) { run, emit -> startTurn(request, providerId, run, emit, choice, last ?: choice, ws.contextTokens) }
    }

    // A detached run on a worker; the caller holds the workspace's busy lock.
    private fun launch(ws: Workspace, runId: String, message: String, label: String, body: (Run, (StreamChunk) -> Unit) -> Unit): RunEvents {
        val run = Run()
        val events = RunEvents(ws.id, runId, message, label)
        runs[runId] = run to events
        active[ws.id] = events
        runsChanged()
        started.forEach { it(events) }
        val emit: (StreamChunk) -> Unit = { events.append(it.kind.name.lowercase(), it.text) }
        Schedulers.boundedElastic().schedule {
            try {
                body(run, emit)
            } catch (e: Exception) {
                emit(StreamChunk(StreamChunk.Kind.ERROR, e.message ?: e.javaClass.simpleName))
            } finally {
                // Free before `done`: a client may start the next chat as soon as it sees it.
                runs.remove(runId)
                active.remove(ws.id)
                runsChanged()
                busy.remove(ws.id)
                events.append("done", "")
            }
        }
        return events
    }

    private fun runsChanged() {
        val ids = activeIds()
        runsListeners.forEach { it(ids) }
    }

    // The run's events (without `done`) as chunks. Cancelling this view does NOT stop the run.
    fun chat(request: ChatRequest): Flux<StreamChunk> {
        val events = start(request)
        return Flux.create { sink ->
            val off = events.subscribe(-1) { e ->
                if (e.event == "done") sink.complete()
                else sink.next(StreamChunk(StreamChunk.Kind.valueOf(e.event.uppercase()), e.data))
            }
            sink.onDispose(off)
        }
    }

    // M11: a failure is an ERROR chunk and returns null; the turn then runs uncompacted.
    // A COMPACTING chunk goes first, so the client can show progress.
    private fun compacted(emit: (StreamChunk) -> Unit, body: () -> MessageDto): MessageDto? =
        try {
            emit(StreamChunk(StreamChunk.Kind.COMPACTING, "{}"))
            body().also { emit(StreamChunk(StreamChunk.Kind.COMPACTED, mapper.writeValueAsString(it))) }
        } catch (e: Exception) {
            emit(StreamChunk(StreamChunk.Kind.ERROR, "compaction failed: ${e.message}"))
            null
        }

    // Never throws: a model/tool failure becomes an ERROR chunk. Whatever was produced
    // (also on cancel) is persisted as the assistant turn.
    // M11: then the context size is stored and sent as a USAGE chunk.
    // M11: compacts before the turn when asked, or when the next turn would not fit:
    // ctx + max(K, R) > W. It runs after appendUser and covers only the earlier rows, so the
    // summary sits between the new user row and its reply. `last` is the model that compacts.
    private fun startTurn(request: ChatRequest, providerId: String, run: Run, emit: (StreamChunk) -> Unit, choice: ModelChoice, last: ModelChoice, ctx: Long) {
        val workspaceId = request.workspaceId
        val window = choice.contextWindow
        // An automatic trigger with nothing uncovered is skipped; an explicit one reports it.
        val compact = request.compact ||
            (window != null && ctx + maxOf(compactor.recentEstimate(workspaceId), choice.reserve()!!) > window &&
                compactor.hasUncovered(workspaceId))
        val before = sessions.rows(workspaceId).lastOrNull()?.id ?: 0
        sessions.appendUser(workspaceId, request.message)
        sessions.saveRun(workspaceId, request.runId, "running", Checkpoint(providerId, choice.model))
        if (compact) compacted(emit) { compactor.compact(workspaceId, last, before, run) }
        runTurn(workspaceId, request.runId, providerId, run, emit, choice, null)
    }

    // The agent loop from the persisted context (+ a restored run's progress), then the assistant
    // row, the context size and the `done` checkpoint.
    private fun runTurn(workspaceId: String, runId: String, providerId: String, run: Run, emit: (StreamChunk) -> Unit, choice: ModelChoice, initial: Checkpoint?) {
        val save = { cp: Checkpoint, status: String -> sessions.saveRun(workspaceId, runId, status, cp.copy(providerId = providerId, model = choice.model)) }
        val dir = registry.dirOf(workspaceId)
        val mode = registry.find(workspaceId)!!.permissionMode
        val midTurn: (List<ToolInvocationDto>, String, Long, (String) -> Long) -> String? = { folded, soFar, before, after ->
            compacted(emit) { compactor.compactMidTurn(workspaceId, choice, folded, soFar, before, after, run) }?.content
        }
        val result = loop.run(compactor.context(workspaceId), run, workspaceId, dir, emit, choice, compact = midTurn, initial = initial, save = save, mode = mode)
        val errors = listOfNotNull(
            "stopped after ${loop.maxIterations} model calls".takeIf { result.capped && !run.stopped.get() },
            result.error,
            "stopped".takeIf { run.stopped.get() },
        )
        errors.forEach { emit(StreamChunk(StreamChunk.Kind.ERROR, it)) }
        sessions.appendAssistant(workspaceId, result.answer, result.thinking, result.trace, choice.label, errors.joinToString("\n"))
        registry.setContextTokens(workspaceId, result.contextTokens)
        save(Checkpoint(answer = result.answer, thinking = result.thinking, trace = result.trace), "done")
        emit(StreamChunk(StreamChunk.Kind.USAGE, """{"contextTokens":${result.contextTokens}}"""))
    }

    // M10: pick the provider/model before the stream starts, so a bad choice is a synchronous
    // IllegalArgumentException (→ 400). The workspace's last model only applies to its last
    // provider; another provider starts at its default model. chat() remembers the choice.
    private fun resolve(request: ChatRequest, ws: Workspace): Pair<String, ModelChoice> {
        // An unknown explicit providerId is a 400; a deleted last provider falls back to the first.
        val p =
            request.providerId?.let { providers.find(it) ?: throw IllegalArgumentException("unknown provider: $it") }
                ?: ws.lastProviderId?.let(providers::find)
                ?: providers.list().firstOrNull() ?: throw IllegalArgumentException("no provider configured")
        val model = request.model ?: ws.lastModel?.takeIf { p.id == ws.lastProviderId } ?: p.defaultModel
        require(model.isNotBlank()) { "no model selected" }
        return p.id to choiceOf(p, model, ws)
    }

    // M11: the workspace's last provider + model while that provider still exists.
    private fun lastChoice(ws: Workspace): ModelChoice? {
        val p = ws.lastProviderId?.let(providers::find) ?: return null
        return choiceOf(p, ws.lastModel ?: return null, ws)
    }

    // M11: contextWindow = W, the smaller of the model window and the workspace limit (either may be unknown).
    private fun choiceOf(p: Provider, model: String, ws: Workspace): ModelChoice {
        val limits = providers.limits(p.id).find { it.id == model }
        val window = listOfNotNull(limits?.contextWindow?.toLong(), ws.contextLimit).minOrNull()?.toInt()
        return ModelChoice(models.model(p), model, limits?.maxOutputTokens, "${p.name}/$model", window)
    }

    internal companion object {
        const val INTERRUPTED = "interrupted by a server restart"
        // M13: the work method: plan in PLAN.md, do, tick, verify, report.
        const val SYSTEM_PROMPT =
            "You are claudius, a helpful coding assistant. You work inside the user's workspace directory; " +
                "tool paths are relative to it. Shell commands run via the bash tool in a sandbox " +
                "(workspace only, no network). For a task with several steps: write the steps as a checklist " +
                "in PLAN.md first, do them one by one, tick each one in PLAN.md when it is done, verify the " +
                "result (run the tests when the project has tests), and end with a short report: what you did, " +
                "what you checked, what is left. Reply concisely."
        const val CLAUDIUS_MD_CHARS = 16_000

        // M13: SYSTEM_PROMPT + the workspace's CLAUDIUS.md (cut at CLAUDIUS_MD_CHARS) when it has one.
        internal fun systemPrompt(dir: Path): String {
            val file = dir.resolve("CLAUDIUS.md")
            if (!Files.isRegularFile(file)) return SYSTEM_PROMPT
            return SYSTEM_PROMPT + "\n\nWorkspace instructions (CLAUDIUS.md):\n" + Files.readString(file).take(CLAUDIUS_MD_CHARS)
        }
    }
}

// M12: startup recovery, apart from ChatService so a web slice test with a mock service skips it.
@Component
class RunRecovery(private val chatService: ChatService) {
    @EventListener(ApplicationReadyEvent::class)
    fun ready() = chatService.recover()
}
