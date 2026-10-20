package org.github.nafania217518.claudius.chat

import org.github.nafania217518.claudius.chat.dto.MessageDto
import org.github.nafania217518.claudius.chat.dto.ToolInvocationDto
import org.github.nafania217518.claudius.chat.tool.Permission
import org.github.nafania217518.claudius.chat.tool.Tool
import org.github.nafania217518.claudius.chat.tool.ToolCallbackAdapter
import org.github.nafania217518.claudius.chat.tool.parseArgs
import org.github.nafania217518.claudius.chat.tool.requireString
import org.github.nafania217518.claudius.workspace.PermissionMode
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.github.nafania217518.claudius.chat.tool.ToolRegistry
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.MessageAggregator
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

// What one loop produced. error: a model/loop failure (the partial result is still kept).
internal data class LoopResult(
    val answer: String,
    val thinking: String,
    val trace: List<ToolInvocationDto>,
    val capped: Boolean,
    val error: String? = null,
    // M11: the context size after the last top-level model call (tokens).
    val contextTokens: Long = 0,
)

// M10: which model a run talks to — the provider's ChatModel, the model name and the
// provider's max tokens (null = the model's default). Sub-agents use the same.
// label: "<provider name>/<model>", recorded on the assistant row.
// M11: contextWindow = the effective window W in tokens (null = unknown).
data class ModelChoice(
    val chatModel: ChatModel,
    val model: String,
    val maxTokens: Int?,
    val label: String = model,
    val contextWindow: Int? = null,
) {
    // M11: R, the reserved output: min(max output ?: 4096, W/4); null when W is unknown.
    fun reserve(): Long? = contextWindow?.let { minOf((maxTokens ?: 4096).toLong(), it / 4L) }
}

// The agent loop. One call of run() is one user turn. How it works:
//   1. Stream one model call with the tool definitions attached. Forward each text and
//      thinking delta as a TOKEN or REASONING event while it streams.
//   2. When the model made tool calls, run them. Calls that need no approval run in parallel.
//      Calls that need approval run one at a time and wait for the user's answer. Emit
//      TOOL_CALL before and TOOL_RESULT after each call.
//   3. Add the model's reply and the tool results to the context, then go to step 1.
//   4. Stop when the model made no tool call, when Stop was pressed, or at maxIterations.
// Before a call that would not fit the window, the loop asks the compact callback to fold the
// earlier tool rounds into a summary (M11). After each call and tool result it saves a
// checkpoint, so a restart can finish a run that waits on a permission (M12).
// A sub-agent (depth 1) runs the same loop with a fresh context; it never asks (M9).
// M13: the workspace's permission mode turns ASK into ALLOW for some or all tools.
@Component
class AgentLoop(
    private val sessions: SessionStore,
    private val tools: ToolRegistry,
    private val mapper: ObjectMapper,
    @Value("\${claudius.chat.max-iterations:50}") val maxIterations: Int,
    @Value("\${claudius.chat.max-parallel-tools:4}") private val maxParallel: Int = 4,
    // M12: the most often a streaming model call checkpoints its answer/thinking so far.
    @Value("\${claudius.chat.checkpoint-ms:1000}") private val checkpointMs: Long = 1000,
) {
    // Never throws: a failure becomes LoopResult.error.
    internal fun run(
        messages: List<Message>,
        run: Run,
        workspaceId: String,
        dir: Path,
        emit: (StreamChunk) -> Unit,
        choice: ModelChoice,
        depth: Int = 0,
        // M11: mid-turn compaction: summarizes the folded tool calls and the answer so far (+ the persisted context)
        // and returns the summary text, or null on failure (the callback reports it). Also gets the
        // prompt estimate before and the estimate of the new loop list for a summary (after).
        compact: ((List<ToolInvocationDto>, String, Long, (String) -> Long) -> String?)? = null,
        // M12: a restored run's progress: the loop first executes its pending calls, then goes on.
        initial: Checkpoint? = null,
        // M12: called with the progress + status (running/waiting) after each model call and tool result.
        save: ((Checkpoint, String) -> Unit)? = null,
        // M13: the workspace's permission mode; sub-agents inherit it.
        mode: PermissionMode = PermissionMode.ASK,
    ): LoopResult {
        var loop = messages
        val answer = StringBuilder(initial?.answer.orEmpty())
        val thinking = StringBuilder(initial?.thinking.orEmpty())
        val trace = initial?.trace.orEmpty().toMutableList()
        var pending = false
        val snap = { status: String, todo: List<PendingCall>, done: List<ToolInvocationDto> ->
            save?.invoke(Checkpoint(answer = answer.toString(), thinking = thinking.toString(), trace = trace + done, pending = todo), status)
        }
        // M11: the size of the last model call's context; an estimate until a call returns.
        val defs = toolChars()
        var tokens = estimate(loop, defs)
        var added = 0L // chars/4 of the tool results since the last model call
        var round = 0 // trace entries of the last tool round
        var savedAt = System.currentTimeMillis()
        var savedSize = -1
        try {
            val restored = initial?.pending.orEmpty()
            if (restored.isNotEmpty()) {
                // One at a time, in order: an ASK call asks again.
                restored.forEachIndexed { i, c ->
                    val perm = permission(c.name, depth, mode)
                    if (perm == Permission.ASK) snap("waiting", restored.drop(i), emptyList())
                    val at = thinking.length to answer.length
                    val result = execute(AssistantMessage.ToolCall(c.id, "function", c.name, c.arguments), c.id, perm, run, workspaceId, dir, emit, choice, at, mode)
                    trace.add(ToolInvocationDto(c.id, c.name, c.arguments, result, thinkingAt = at.first, answerAt = at.second))
                    snap("running", restored.drop(i + 1), emptyList())
                }
                // ponytail: this turn's tool calls replay as one call/result pair (as a stored row
                // does), not round by round; the answer so far is not replayed.
                loop = loop + MessageDto("assistant", "", createdAt = 0, tools = trace).toMessages()
                tokens = estimate(loop, defs)
            }
            for (i in 0 until maxIterations) {
                if (run.stopped.get()) break
                val window = choice.contextWindow
                if (i > 0 && depth == 0 && compact != null && window != null && tokens + added + choice.reserve()!! > window) {
                    val folded = trace.subList(0, trace.size - round).filter { it.compacted != true }
                    val next = { summary: String -> listOf(loop.first(), UserMessage(Compactor.SUMMARY_PREFIX + summary)) + loop.takeLast(2) }
                    compact(folded, answer.toString(), tokens + added) { estimate(next(it), defs) }?.let { summary ->
                        for (j in 0 until trace.size - round) trace[j] = trace[j].copy(compacted = true)
                        loop = next(summary)
                        tokens = estimate(loop, defs)
                        added = 0
                    }
                }
                val live = {
                    choice.chatModel
                        .stream(Prompt(loop, options(choice, depth, mode)))
                        .takeUntilOther(run.signal.asMono())
                        .doOnNext {
                            forwardLive(it, emit, answer, thinking)
                            // M12: the partial text survives a restart (throttled, only when it changed).
                            val now = System.currentTimeMillis()
                            val size = answer.length + thinking.length
                            if (save != null && size != savedSize && now - savedAt >= checkpointMs) {
                                savedAt = now
                                savedSize = size
                                snap("running", emptyList(), emptyList())
                            }
                        }
                }
                // The merged response (text + every chunk's tool calls) reaches only the callback;
                // blockLast() would return the last raw chunk. The callback also fires when Stop
                // ends the stream via takeUntilOther (a completion); the stop check breaks then.
                // M11: the aggregate keeps the last non-zero usage of the streamed chunks.
                var response: ChatResponse? = null
                // A malformed Ollama tool call is retried once, only if nothing reached the client yet.
                val before = answer.length + thinking.length
                try {
                    MessageAggregator().aggregate(live()) { response = it }.blockLast()
                } catch (e: Exception) {
                    if (!malformedToolCall(e) || answer.length + thinking.length != before) throw e
                    MessageAggregator().aggregate(live()) { response = it }.blockLast()
                }
                if (run.stopped.get()) break
                val output = response?.result?.output
                // Every call of this round shares the text sizes at the moment the model made it.
                val at = thinking.length to answer.length
                tokens = response?.let(::usage)?.takeIf { it > 0 } ?: estimate(loop + listOfNotNull(output), defs)
                added = 0
                val calls = output?.toolCalls.orEmpty()
                // Ollama may send no id; the wire and the trace need one.
                val ids = calls.map { it.id?.ifBlank { null } ?: UUID.randomUUID().toString() }
                val results = arrayOfNulls<String>(calls.size)
                val progress = { status: String ->
                    snap(status,
                        calls.indices.filter { results[it] == null }.map { PendingCall(ids[it], calls[it].name, calls[it].arguments) },
                        calls.indices.filter { results[it] != null }.map { ToolInvocationDto(ids[it], calls[it].name, calls[it].arguments, results[it]!!, thinkingAt = at.first, answerAt = at.second) })
                }
                progress("running")
                pending = calls.isNotEmpty()
                if (!pending) break
                // Every call in this cycle is executed and echoed back as a tool_result
                // before the re-stream, so no persisted tool_call is ever dangling.
                val perms = calls.map { permission(it.name, depth, mode) }
                val exec: (Int) -> Unit = { i ->
                    results[i] = execute(calls[i], ids[i], perms[i], run, workspaceId, dir, emit, choice, at, mode)
                }
                // M9: calls that need no dialog run in parallel (bounded); ASK calls run after
                // them on this thread, one dialog at a time. Results keep the model's order.
                val gate = Semaphore(maxParallel)
                Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                    calls.indices
                        .filter { perms[it] != Permission.ASK }
                        .map { i -> pool.submit { gate.acquire(); try { exec(i) } finally { gate.release() } } }
                        .forEach { it.get() }
                }
                progress("running")
                calls.indices.filter { perms[it] == Permission.ASK }.forEach { progress("waiting"); exec(it); progress("running") }
                val responses = calls.indices.map { i ->
                    trace.add(ToolInvocationDto(ids[i], calls[i].name, calls[i].arguments, results[i]!!, thinkingAt = at.first, answerAt = at.second))
                    ToolResponseMessage.ToolResponse(ids[i], calls[i].name, results[i]!!)
                }
                loop = loop + listOf(output!!, ToolResponseMessage.builder().responses(responses).build())
                added = responses.sumOf { it.responseData.length } / 4L
                round = responses.size
            }
        } catch (e: Exception) {
            return LoopResult(answer.toString(), thinking.toString(), trace, false,
                if (malformedToolCall(e)) MALFORMED_TOOL_CALL else e.message ?: e.javaClass.simpleName, tokens)
        }
        return LoopResult(answer.toString(), thinking.toString(), trace, pending, contextTokens = tokens)
    }

    // M11: the text size of the top-level tool definitions (name + description + input schema).
    // Every request carries them, so the context estimates count them.
    internal fun toolChars(): Int =
        (tools.callbacks() + agentCallback).sumOf {
            it.toolDefinition.name().length + it.toolDefinition.description().length + it.toolDefinition.inputSchema().length
        }

    // M9: agent is ALLOW at the top level only. A sub-agent never asks: anything that would
    // still ask (or agent itself, so no recursion) is DENY there.
    // M13: the mode first: BYPASS allows every ASK tool, ACCEPT_EDITS allows the file edits.
    private fun permission(name: String, depth: Int, mode: PermissionMode): Permission {
        if (name == AGENT) return if (depth == 0) Permission.ALLOW else Permission.DENY
        var p = tools.permission(name)
        if (p == Permission.ASK && (mode == PermissionMode.BYPASS || (mode == PermissionMode.ACCEPT_EDITS && name in EDITS))) p = Permission.ALLOW
        return if (depth > 0 && p == Permission.ASK) Permission.DENY else p
    }

    // One tool call: TOOL_CALL, (dialog), run, audit, TOOL_RESULT. Returns the result.
    private fun execute(
        call: AssistantMessage.ToolCall,
        id: String,
        permission: Permission,
        run: Run,
        workspaceId: String,
        dir: Path,
        emit: (StreamChunk) -> Unit,
        choice: ModelChoice,
        at: Pair<Int, Int>,
        mode: PermissionMode,
    ): String {
        val dto = ToolInvocationDto(id, call.name, call.arguments, "", thinkingAt = at.first, answerAt = at.second)
        emit(emit, StreamChunk.Kind.TOOL_CALL, dto)
        // M8: live output deltas, streamed only (the result carries the whole output).
        val onOutput: (String) -> Unit = { delta ->
            emit(emit, StreamChunk.Kind.TOOL_OUTPUT, ToolInvocationDto(id, call.name, "", delta))
        }
        val (result, decision) = when (permission) {
            Permission.ALLOW ->
                // ALLOW comes from autoRun or the mode, so the registry's fail-closed check gets approved = true.
                if (call.name == AGENT) subAgent(call.arguments, run, workspaceId, dir, onOutput, choice, mode) to "allow"
                else run.interruptible { tools.run(call.name, call.arguments, dir, approved = true, onOutput = onOutput) } to "allow"
            Permission.DENY -> tools.run(call.name, call.arguments, dir, onOutput = onOutput) to "deny"
            Permission.ASK ->
                if (run.ask(id) { emit(emit, StreamChunk.Kind.PERMISSION, dto) }) {
                    run.interruptible { tools.run(call.name, call.arguments, dir, approved = true, onOutput = onOutput) } to "approved"
                } else {
                    "error: the user rejected this call" to "rejected"
                }
        }
        sessions.appendAudit(workspaceId, call.name, call.arguments, decision)
        emit(emit, StreamChunk.Kind.TOOL_RESULT, ToolInvocationDto(id, call.name, "", result))
        return result
    }

    // M9: a child loop with a fresh context, on the same Run (Stop ends it). Its answer tokens
    // and tool calls become output deltas of the agent call; its reasoning is dropped.
    // ponytail: the child's TOOL_CALL json is parsed back for the progress line; a direct
    // hook is the upgrade if that ever shows up in a profile.
    private fun subAgent(arguments: String, run: Run, workspaceId: String, dir: Path, onOutput: (String) -> Unit, choice: ModelChoice, mode: PermissionMode): String {
        val prompt =
            try {
                parseArgs(arguments).requireString("prompt")
            } catch (e: Exception) {
                return "error: ${e.message}"
            }
        val child = run(
            listOf(SystemMessage(SUBAGENT_PROMPT), UserMessage(prompt)), run, workspaceId, dir,
            emit = { c ->
                when (c.kind) {
                    StreamChunk.Kind.TOKEN -> onOutput(c.text)
                    StreamChunk.Kind.TOOL_CALL ->
                        mapper.readValue(c.text, ToolInvocationDto::class.java).let { onOutput("\n› ${it.name} ${it.arguments}\n") }
                    else -> {}
                }
            },
            choice = choice,
            depth = 1,
            mode = mode,
        )
        return when {
            child.error != null -> "error: ${child.error}"
            child.capped && !run.stopped.get() ->
                "error: sub-agent stopped after $maxIterations model calls" + child.answer.ifBlank { null }?.let { "\n$it" }.orEmpty()
            else -> child.answer.ifBlank { "(no output)" }
        }
    }

    private fun emit(emit: (StreamChunk) -> Unit, kind: StreamChunk.Kind, dto: ToolInvocationDto) =
        emit(StreamChunk(kind, mapper.writeValueAsString(dto)))

    // The model request carries the tool surface: advertised callbacks so the model knows it
    // can call them. Preserves the model's own config (thinking, base options) by mutating its
    // options rather than a fresh builder.
    // M9: the top level also gets agent; a sub-agent gets only the tools it may run (M13: per the mode).
    // M10: generic over providers (no Ollama cast); sets the chosen model and max tokens.
    private fun options(choice: ModelChoice, depth: Int, mode: PermissionMode): ToolCallingChatOptions {
        val callbacks =
            if (depth == 0) tools.callbacks() + agentCallback
            else tools.callbacks().filter { permission(it.toolDefinition.name(), depth, mode) == Permission.ALLOW }
        return baseOptions(choice, callbacks)
    }

    // Forward a live reasoning and/or answer delta; a frame may carry both. Tool-argument
    // fragments ride on toolCalls, not on output.text, so they are never forwarded as prose.
    private fun forwardLive(
        response: ChatResponse,
        emit: (StreamChunk) -> Unit,
        answer: StringBuilder,
        thinking: StringBuilder,
    ) {
        val gen = response.result ?: return
        // gen.metadata / gen.output are non-null: the Generation ctor coerces null metadata to
        // ChatGenerationMetadata.NULL and always carries an output. ponytail: bare access.
        val thinkingDelta = gen.metadata.get<String>("thinking")
        val text = gen.output.text.orEmpty()
        if (!thinkingDelta.isNullOrEmpty()) {
            thinking.append(thinkingDelta)
            emit(StreamChunk(StreamChunk.Kind.REASONING, thinkingDelta))
        }
        if (text.isNotEmpty()) {
            answer.append(text)
            emit(StreamChunk(StreamChunk.Kind.TOKEN, text))
        }
    }

    internal companion object {
        const val AGENT = "agent"
        // M13: the tools ACCEPT_EDITS runs without a dialog.
        val EDITS = setOf("fileWrite", "fileEdit")
        const val MALFORMED_TOOL_CALL =
            "the model returned a malformed tool call (Ollama could not parse it); try again or use another model"

        // ponytail: string match on Spring AI 2.0.1's NPE text (OllamaChatModel does not null-check
        // ChatResponse.message()); drop it when Spring AI null-checks it.
        fun malformedToolCall(e: Exception) = e is NullPointerException && e.message?.contains("ChatResponse.message()") == true

        // Advertised only: execute() runs agent itself (a registry Tool would be a bean cycle).
        private val agentCallback = ToolCallbackAdapter(object : Tool {
            override val name = AGENT
            override val description =
                "Launch a sub-agent for an independent research or analysis task. It starts with NO context: " +
                    "give it a complete, self-contained prompt. It has the workspace's permissions, but a call that " +
                    "would need the user's approval is denied there. Several agent calls in one reply run in parallel. " +
                    "Returns the sub-agent's final report."
            override val inputSchema =
                """{"type":"object","properties":{""" +
                    """"description":{"type":"string","description":"A short (3-5 word) description of the task"},""" +
                    """"prompt":{"type":"string","description":"The complete, self-contained task for the sub-agent"}},""" +
                    """"required":["description","prompt"]}"""
            override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit) =
                throw UnsupportedOperationException("AgentLoop runs agent")
        })

        const val SUBAGENT_PROMPT =
            "You are a claudius sub-agent. Do the task in the user message using the tools available, " +
                "then reply with a concise, self-contained report. The caller sees only your final answer."
    }
}

// The model's own options (thinking, base options) with the chosen model and max tokens.
// M11: shared with the Compactor, which adds no tools.
internal fun baseOptions(choice: ModelChoice, callbacks: List<ToolCallback> = emptyList()): ToolCallingChatOptions {
    val builder = (choice.chatModel.options as? ToolCallingChatOptions)?.mutate() ?: ToolCallingChatOptions.builder()
    builder.model(choice.model)
    choice.maxTokens?.let { builder.maxTokens(it) }
    builder.toolCallbacks(callbacks)
    return builder.build()
}

// M11: prompt + completion tokens of a (aggregated) response; 0 when the provider reports none.
// ponytail: each provider counts with its own tokenizer, so sizes are only comparable within a model.
internal fun usage(r: ChatResponse): Long =
    r.metadata.usage.let { (it.promptTokens ?: 0).toLong() + (it.completionTokens ?: 0) }

// M11: a token estimate of a model context, chars / 4 (text, tool calls, tool results, plus
// extraChars, e.g. the tool definitions).
// ponytail: chars/4 is a rough English average; a real tokenizer per model is the upgrade.
internal fun estimate(messages: List<Message>, extraChars: Int = 0): Long =
    (extraChars + messages.sumOf { m ->
        m.text.orEmpty().length +
            ((m as? AssistantMessage)?.toolCalls?.sumOf { it.name.length + it.arguments.length } ?: 0) +
            ((m as? ToolResponseMessage)?.responses?.sumOf { it.responseData.length } ?: 0)
    }).toLong() / 4
