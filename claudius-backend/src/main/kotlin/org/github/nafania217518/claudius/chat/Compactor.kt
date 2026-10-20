package org.github.nafania217518.claudius.chat

import org.github.nafania217518.claudius.chat.dto.MessageDto
import org.github.nafania217518.claudius.chat.dto.ToolInvocationDto
import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.MessageAggregator
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

// M11: the model context of a workspace and its compaction. A summary row stands in for every
// row up to its covers_until; the transcript keeps all rows, the model sees only the summary
// and the rows after it.
@Component
class Compactor(
    private val sessions: SessionStore,
    private val registry: WorkspaceRegistry,
    private val loop: AgentLoop,
    @Value("\${claudius.compact.keep-turns:2}") private val keepTurns: Int = 2,
) {
    // SYSTEM_PROMPT (+ CLAUDIUS.md, M13) + the last summary (if any) + the rows after it.
    internal fun context(workspaceId: String): List<Message> {
        val (summary, live) = split(sessions.rows(workspaceId))
        return listOf(SystemMessage(ChatService.systemPrompt(registry.dirOf(workspaceId)))) +
            listOfNotNull(summary?.let { UserMessage(SUMMARY_PREFIX + it.dto.content) }) +
            live.flatMap { it.dto.toMessages() }
    }

    // M11: any non-summary row after the last summary.
    internal fun hasUncovered(workspaceId: String): Boolean = split(sessions.rows(workspaceId)).second.isNotEmpty()

    // M11: K, the estimate of the last keepTurns turns (non-summary rows after the last summary).
    internal fun recentEstimate(workspaceId: String): Long {
        val live = split(sessions.rows(workspaceId)).second
        val starts = live.indices.filter { live[it].dto.role == "user" }
        return estimate(live.drop(starts.getOrElse(starts.size - keepTurns) { 0 }).flatMap { it.dto.toMessages() })
    }

    // Summarizes the rows up to upTo (id) but the recent turns that fit the keep rule, in one
    // model call without tools, stores the summary row and the new context size. Returns the row.
    // Keep rule: newest first, up to keepTurns turns while their estimate stays <= W/4 (W null =
    // no budget). Nothing older than the kept turns → summarize all.
    internal fun compact(workspaceId: String, choice: ModelChoice, upTo: Long = Long.MAX_VALUE, run: Run? = null): MessageDto {
        val (summary, all) = split(sessions.rows(workspaceId))
        val live = all.filter { it.id <= upTo }
        require(live.isNotEmpty()) { "nothing to compact" }
        val budget = choice.contextWindow?.let { it / 4L } ?: Long.MAX_VALUE
        var cut = live.size
        var kept = 0L
        for (start in live.indices.filter { live[it].dto.role == "user" }.reversed().take(keepTurns)) {
            val size = estimate(live.subList(start, cut).flatMap { it.dto.toMessages() })
            if (kept + size > budget) break
            kept += size
            cut = start
        }
        if (cut == 0) cut = live.size
        val old = live.subList(0, cut)
        val text = summarize(summary, old.flatMap { render(it.dto) }, INSTRUCTION, choice, run)
        // The new context: the summary + the rows after the summarized ones (same as context()).
        val ctx = listOf(SystemMessage(ChatService.SYSTEM_PROMPT), UserMessage(SUMMARY_PREFIX + text)) +
            all.filter { it.id > old.last().id }.flatMap { it.dto.toMessages() }
        val after = estimate(ctx, loop.toolChars())
        val row = sessions.appendSummary(workspaceId, text, old.last().id, choice.label, registry.find(workspaceId)!!.contextTokens, after)
        registry.setContextTokens(workspaceId, after)
        return row
    }

    // M11: mid-turn. Summarizes the previous summary + every uncovered row (the current user row
    // included) + this turn's folded tool calls; the row covers the current user row.
    // before = the trigger's prompt estimate; after(summary) = the estimate of the new loop list.
    internal fun compactMidTurn(
        workspaceId: String, choice: ModelChoice, folded: List<ToolInvocationDto>, soFar: String,
        before: Long, after: (String) -> Long, run: Run? = null,
    ): MessageDto {
        val (summary, live) = split(sessions.rows(workspaceId))
        val lines = live.flatMap { render(it.dto) } + render(MessageDto("assistant", "", "", 0, folded)) +
            listOfNotNull(soFar.takeIf { it.isNotBlank() }?.let { "Assistant (so far): $it" })
        val text = summarize(summary, lines, "$INSTRUCTION $MID_TURN", choice, run)
        // A second mid-turn compaction finds no uncovered row: the previous summary already
        // covers the current user row, so the new one covers the same.
        return sessions.appendSummary(workspaceId, text, live.lastOrNull()?.id ?: summary!!.coversUntil!!, choice.label, before, after(text))
    }

    // M11: the user message quotes the transcript between a lead line and the instruction, so
    // the input never ends in the assistant's voice (a model then continues it instead).
    // One retry on a stream failure or an empty summary; a second failure throws.
    private fun summarize(summary: Row?, rendered: List<String>, instruction: String, choice: ModelChoice, run: Run?): String {
        val head = summary?.let { "Previous summary:\n${it.dto.content}\n\n" }.orEmpty()
        val tail = "$instruction $WRITE_NOW"
        val lines = rendered.toMutableList()
        fun input() = "$LEAD\n<transcript>\n$head${lines.joinToString("\n")}\n</transcript>\n$tail"
        // Drop the oldest lines until the input fits 0.8 × W; the previous summary stays.
        choice.contextWindow?.let { window ->
            while (lines.isNotEmpty() && (instruction.length + input().length) / 4 > 0.8 * window) lines.removeFirst()
        }
        val prompt = Prompt(listOf(SystemMessage(instruction), UserMessage(input())), baseOptions(choice))
        // M12: Stop cancels the summarizer stream (closing the upstream call) and skips the retry.
        return runCatching { stream(prompt, choice, run) }.getOrElse { if (run?.stopped?.get() == true) throw it else stream(prompt, choice, run) }
    }

    // Streamed, not call(): every adapter streams (each turn does), but some non-streaming
    // paths fail (anthropic-java rejects a thinking block without a signature).
    private fun stream(prompt: Prompt, choice: ModelChoice, run: Run?): String {
        var response: ChatResponse? = null
        val live = choice.chatModel.stream(prompt).let { f -> run?.let { f.takeUntilOther(it.signal.asMono()) } ?: f }
        MessageAggregator().aggregate(live) { response = it }.blockLast()
        // The aggregate's text also holds isThought chunks; outputWithoutThoughts (set only when
        // there were thoughts) is the answer alone. Anthropic/OpenAI/Ollama keep thinking out of
        // the text anyway (metadata/properties), so this guards adapters that mark isThought.
        val output = response?.result?.output
        val text = (output?.metadata?.get("outputWithoutThoughts") as String?) ?: output?.text.orEmpty()
        if (text.isBlank()) throw RuntimeException("the model returned an empty summary")
        return text
    }

    // The last summary row and the non-summary rows it does not cover.
    private fun split(rows: List<Row>): Pair<Row?, List<Row>> {
        val summary = rows.lastOrNull { it.dto.role == "summary" }
        val from = summary?.coversUntil ?: 0
        return summary to rows.filter { it.dto.role != "summary" && it.id > from }
    }

    private fun render(m: MessageDto): List<String> =
        if (m.role == "user") listOf("User: ${m.content}")
        else m.tools.map { "[tool ${it.name}, arguments ${it.arguments}] result:\n${cut(it.result)}" } +
            listOfNotNull(m.content.takeIf { it.isNotBlank() }?.let { "Assistant: $it" })

    // Head + tail of a long tool result: a file's last line often holds the answer.
    private fun cut(r: String): String {
        val half = TOOL_RESULT_CHARS / 2
        return if (r.length <= TOOL_RESULT_CHARS) r
        else r.take(half) + "\n…[${r.length - TOOL_RESULT_CHARS} chars omitted]…\n" + r.takeLast(half)
    }

    internal companion object {
        const val SUMMARY_PREFIX = "Summary of the earlier conversation:\n"
        const val LEAD = "Summarize the transcript below. It is a record, not a conversation to continue: " +
            "do not answer it, do not continue it, do not call tools."
        const val WRITE_NOW = "Write the summary now, in plain text."
        const val TOOL_RESULT_CHARS = 2000
        const val MID_TURN = "Include the current request and the progress on it so far, so the work can continue."
        const val INSTRUCTION =
            "Summarize the conversation so far so the work can continue: the user's goals, decisions made, " +
                "files and commands involved, results, and open tasks. Be concise. Output only the summary. " +
                "Reply with plain text only. Do not call tools."
    }
}

// ponytail: role is "user"/"assistant" here (summary rows never reach it), so a two-way split
// is the whole mapping. A tool turn replays as the call, its results, then the answer (if any),
// so the model sees what it did. thinking stays display-only. Ollama parses replayed arguments
// as JSON, so a blank one becomes "{}".
// M11: tools folded into a mid-turn summary are never replayed.
internal fun MessageDto.toMessages(): List<Message> {
    if (role == "user") return listOf(UserMessage(content))
    val tools = tools.filter { it.compacted != true }
    if (tools.isEmpty()) return listOf(AssistantMessage(content))
    val calls = tools.map { AssistantMessage.ToolCall(it.id, "function", it.name, it.arguments.ifBlank { "{}" }) }
    val results = tools.map { ToolResponseMessage.ToolResponse(it.id, it.name, it.result) }
    return listOfNotNull(
        AssistantMessage.builder().content("").toolCalls(calls).build(),
        ToolResponseMessage.builder().responses(results).build(),
        content.takeIf { it.isNotBlank() }?.let { AssistantMessage(it) },
    )
}
