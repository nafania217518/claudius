# claudius — Architecture (draft)

Web reimplementation of `claude-code` (reference: the `claude-code` CLI, TS/Bun/Ink,
~500k LOC). Backend **Kotlin + Spring AI** (`claudius-backend/`), frontend **React**
(`claudius-frontend/`). This doc maps each reference subsystem to its target in claudius and
gives a build order. Design intent: [../README.md](../README.md). The Spring AI API reality
(v2.0.1) is crawled to
[spring-ai-reference/](spring-ai-reference/); §2.3/§2.7/§2.8 cite it.

---

## 0. As built (M12, 2026-10-09)

This doc is the port map from the reference. What exists today is smaller:

- **Backend:** one `chat` package (`ChatSocket` WebSocket, `ChatService` runs,
  `SessionStore` SQLite per workspace, `tool/` with `Tool`, `ToolRegistry`, `CurrentTime`) and one
  `workspace` package (registry DB + CRUD). M6/M7 add workspace file tools (`fileRead`, `glob`,
  `grep`, `fileWrite`, `fileEdit`) behind one path guard (`resolveInWorkspace`). No `QueryEngine`/`SessionRegistry` yet: the workspace
  *is* the session, and `ChatService` holds the per-run state (`runId` → stop signal, busy
  workspaces).
- **Loop:** `AgentLoop.run` (started by `ChatService.startTurn`/`runTurn`) is the §2.1 loop with
  steps 2, 3, 5 and 6 plus compaction (M11); there is no recovery cascade. Tools are executed by
  claudius from an autoRun-only map — **not** via
  `ToolCallingManager`: in Spring AI 2.0.1 `DefaultToolCallingManager` resolves callbacks from the
  prompt options before the resolver, so a resolver-only filter does not gate anything. An
  iteration cap (`claudius.chat.max-iterations`) replaces Spring's per-tool limit exception.
- **Transport (M12, §2.9):** one WebSocket per tab at `/api/ws` (`ChatSocket`). Up: `subscribe`
  (one workspace per socket, `after` = the last seen seq), `chat`, `stop`, `answer`, `pong`. Down:
  `ping` every 1 s, `state` (the workspace's active run or null, with its model label), `started`
  (a run that began on the followed workspace: run id, user message, model label), `runs` (the
  workspace ids with an active run, on connect and on every start/end), `event` (`runId`, `seq`,
  the event name and data), `error`. A run is detached from connections: it starts on a worker, writes
  its events to an in-memory `RunEvents` log, and every subscribed socket gets the replay plus the
  live events; `done` is always last. A closed tab or a dropped socket never stops a run; Stop
  does (it cancels the provider stream, the summarizer stream and a running bash process). No
  pong for 5 s closes the socket, not the run. Each run is checkpointed to the session DB; after
  a restart a run that was mid-call is saved as interrupted, and one waiting on a permission is
  restored and asks again. Until M11 this was SSE + REST commands.
- **Permissions (M7):** `ToolRegistry.permission` → allow (autoRun) / ask (known, mutating) / deny
  (unknown). An ask emits a `permission` event and blocks the loop on a per-call future answered by
  the `answer` socket frame (M12); Stop rejects it. The UI shows Approve/Reject on the tool card. Every decision lands in the workspace's `audit`
  table. **M13:** a per-workspace permission mode (`ASK` / `ACCEPT_EDITS` / `BYPASS`, column
  `permission_mode`, `PUT …/permission-mode`) turns an ask into an allow for edits or for everything;
  `AgentLoop.permission(name, depth, mode)` applies it before the sub-agent rule. No remembered
  per-call rules yet (§2.4).
- **Bash sandbox (M8, decides §2.3):** the backend runs in Docker, so each `bash` command is confined
  by Landlock via `landrun` inside the backend container (rw on its workspace + a temp dir, no TCP,
  clean env), not by a container per command (that needs the host Docker socket). claudius data
  (registry, session DBs, temp dirs) lives in `<workspaces-dir>/.claudius/`, outside every workspace.
  Limits and the rejected options: [roadmap.md](roadmap.md) M8.
- **Sub-agents (M9, §2.5 sync path):** `AgentLoop` is split out of `ChatService`. The `agent` tool runs
  the same loop at depth 1 with a fresh context, the workspace's permission mode (an ask is a deny
  there, so a sub-agent can write in `ACCEPT_EDITS`/`BYPASS`) and the parent's `Run`; its progress
  rides `tool_output`, its report is the `tool_result`. Calls without a dialog run in parallel
  (`claudius.chat.max-parallel-tools`). Background agents and teams are not built.
- **LLM gateway (M10, §2.8 minimal):** a `provider` registry (Ollama, OpenAI-compatible,
  Anthropic; keys stored server-side in the registry DB, returned masked) and `ChatModelFactory`
  (one cached Spring AI `ChatModel` per provider). The provider + model are chosen per message and
  remembered per workspace; `AgentLoop` builds generic `ToolCallingChatOptions`. No retry
  wrapper, fallback model, prompt caching or usage aggregation yet.
- **Context (M11, §2.6 minimal):** one strategy, not the cascade: a streamed LLM summary stored as a
  `summary` row; the model context is the summary plus the rows after it. It runs before a turn
  when the next turn would not fit (context + max(last two turns, reserved output) > window),
  inside a tool loop before a call would overflow, or on request. The window is the model's, capped
  by a per-workspace limit. No circuit breaker, no tool-result clearing, no file re-injection;
  memory (§2.7) is M13.
- **Frontend:** `useChat` (one socket, rAF-coalesced stream, stop, transcript load, the list of
  running workspaces) + `useWorkspaces` (the active workspace is kept in localStorage);
  `Message` renders markdown (react-markdown + GFM, no raw HTML) with a `<details>` thinking
  blocks and tool and sub-agent chips in arrival order (each call records the thinking and answer
  length at call time; Approve/Reject sit under the chip that waits), the model label and
  the turn's error; `Inspector` is the right-hand panel with Tools and Agents tabs that shows the
  selected call's arguments, live output and result (a chip click selects it);
  the sidebar marks workspaces with a run in progress; a Settings panel manages providers, a
  picker next to Send selects the model, and a usage bar shows the context size with a Compact
  button.

Milestone status and acceptance numbers: [roadmap.md](roadmap.md). Review:
[review-2026-10-09.md](review-2026-10-09.md).

---

## 1. Shape of the system

The reference is a TUI agent: a streaming generator loop that calls an LLM, runs the tools it
asks for, gates mutations behind a user-approval callback, and keeps a long session alive via
context compaction + persistent memory. claudius replaces only two layers — the Ink TUI becomes
a **React web app**, and the process-local transport becomes an **HTTP + WebSocket/SSE backend**.
Everything between the model and the UI is the same architecture, ported 1:1.

```
┌─────────────────────────────────────────────────────────────────────┐
│ React frontend (claudius-frontend)                                   │
│  chat transcript · live tool-call cards · plan/progress · usage bar  │
│  approve/reject dialogs · sub-agent chips · settings panel           │
└───────────────────────────────┬─────────────────────────────────────┘
        messages up (WS/SSE stream)   │   commands down (REST + WS)
┌───────────────────────────────▼─────────────────────────────────────┐
│ Spring backend (claudius-backend)                                    │
│                                                                      │
│  SessionService ── owns per-session QueryEngine (state, usage, abort)│
│       │                                                              │
│       ▼                                                              │
│  AgentLoop (Flux<Message> pipeline) ◀── ContextReducer (compaction)  │
│       │  call model (stream)        │  LLM gateway (Spring AI        │
│       ▼                             │  ChatClient + retry/fallback)  │
│  ToolDispatcher ── Tool registry   │                                 │
│       │  per-call → PermissionGate ──approve/reject──▶ user (WS)     │
│       ▼                                                              │
│  SubAgentOrchestrator (child QueryEngine per worker)                 │
│       │                                                              │
│  MemoryService (project/team store + recall)  ·  UsageService        │
└─────────────────────────────────────────────────────────────────────┘
```

Layer cut mirrors the reference: UI → orchestration (`QueryEngine`/`coordinator`) → agent loop
(`query.ts`) → tool/permission/service/memory layers. claudius keeps this cut; it does not
invent a new one.

---

## 2. Subsystem-by-subsystem

### 2.1 Agent loop (`src/query.ts` → `AgentLoop`)

The heart. Reference: async generator `query()` (`query.ts:219`) → inner `queryLoop()`
(`while(true)` at `:307`), yielding a stream of messages. Each iteration:

1. **Context reduction** — snip → microcompact → context-collapse → autocompact
   (`:401`–`:468`); if compaction fires, emit boundary/summary and continue.
2. **Model call** — `for await (… deps.callModel({…}))` (`:659`); stream the assistant
   message, collect `tool_use` blocks; backfill tool inputs on a *clone* before yielding
   (`:761`) so the prompt-cache prefix stays byte-identical.
3. **Interruption** (`:1015`) — on abort: drain `getRemainingResults()`, synthesize a
   `tool_result` for *every* outstanding `tool_use` (a dangling tool_use breaks the API),
   yield the interruption message, return `aborted_streaming`.
4. **Recovery cascade** (`:1062`–`:1220`) — prompt-too-long/413 → one-shot context-collapse
   drain (`:1089`) → reactive compact, guarded by `hasAttemptedReactiveCompact` (`:1119`) →
   `max_output_tokens` escalation 8k→64k (`:1199`). No stop hooks on a PTL failure
   (`:1168`).
5. **Tool run** — if `needsFollowUp` (any `tool_use` block, `:558`; the model's
   `stop_reason` is *not* trusted, `:553`), run tools and continue.
6. **Exit** — no tool calls → stop hooks → terminal.

**Target:** a Kotlin coroutine/`Flux` pipeline — `Flux<Message> loop()` doing exactly
(1)–(6). `deps.callModel` and the compaction steps are **injected functions** (reference:
`query/deps.ts` `productionDeps()`), which is the seam that makes the loop testable without a
live LLM. The recovery cascade becomes a small typed state machine (same guards, same
ordering). Interruption = a cooperative cancel token (the `AbortController` equivalent); the
"synthesize a result for every outstanding tool_use" invariant is the one thing that must not
be lost in the port. Tools that run as they arrive become a bounded-concurrency runner:
concurrency-safe + read-only in parallel, destructive serialized (see 2.3).

### 2.2 QueryEngine / session (`src/QueryEngine.ts` → `QueryEngineService`)

Per-session orchestrator (`QueryEngine.ts:184`): mutable message history, `abortController`,
permission-denial log, total usage, file-read cache (`FileStateCache`), model/thinking
config. `submitMessage()` (`:209`) wraps `canUseTool` to record denials, resolves the model,
fetches system-prompt parts + coordinator context, then drives the loop; `interrupt()`
(`:1158`); `ask()` (`:1186`) is the one-shot headless entry.

**Target:** a Spring `@Service` that **creates a stateful `QueryEngine` object per session**
(not a stateless bean — the reference is deliberately stateful per session). State lives in
the object; a `SessionRegistry` maps `sessionId → engine`. `ask()` covers headless/task
runs (background sub-agents use it). This is the natural owner of the WS/SSE stream to the
frontend.

### 2.3 Tool system (`src/Tool.ts`, `src/tools.ts`, `src/tools/*`)

A tool is a self-describing object (`Tool.ts:362`): `name`, JSON `inputSchema`,
`call(args, ctx, canUseTool, …)` runner, `checkPermissions` gate, capability flags
(`isReadOnly` `:404`, `isConcurrencySafe` `:402`, `isDestructive?` `:406`, `isEnabled`),
and UI hooks (`userFacingName`, `render*`). `buildTool` spreads **fail-closed defaults**
(`:757`) — a tool that omits a flag is least-permissive. `getAllBaseTools()` (`tools.ts:193`)
is the ordered registry (order is prompt-cache-sensitive); `assembleToolPool`/`getMergedTools`
merge in MCP tools (built-ins win on name clash) and filter deny rules.

**Target:** a Kotlin `Tool` interface (claudius's own — not Spring AI's `ToolCallback`) with a
`call` coroutine and a Jackson/JSON schema; fail-closed capability flags
(`isReadOnly`/`isConcurrencySafe`/`isDestructive`) as interface defaults — `ToolCallback`
carries only `returnDirect`, no capability flags, so they can't come from Spring AI. Spring AI
auto-discovers `ToolCallback` beans into a registry (`StaticToolCallbackResolver`), but
claudius **bypasses `ToolCallingAdvisor`** and drives `ToolCallingManager.executeToolCalls`
itself: the auto-advisor runs its own tool loop and would steal the per-call
`PermissionService` gate + compaction that §2.1/§2.6 must host. Spring AI does **not** resolve
local↔MCP name clashes (built-ins win), so claudius keeps its own merge rule. The `render*`
UI hooks don't cross the wire — they become **serializable event shapes** (tool-call card,
progress tail, result) streamed to React. Ship the small core set first: `FileRead`,
`FileEdit`, `FileWrite`, `Glob`, `Grep`, `Bash`, `Agent` (spawn), `TaskOutput`/`TaskStop`,
`AskUserQuestion`, plus `WebFetch`/`WebSearch`. Everything else (REPL, MCP, cron, skills,
plan-mode, web browser) is additive later. See
[spring-ai-reference/tool-calling.md](spring-ai-reference/tool-calling.md).
`BashTool` executes inside a working directory that is a copy/sandbox of the user's project
(the reference runs real shells; for a web backend, run commands per-session in a
chroot/container-like boundary — this is a security requirement, not an optimization).
**Decided in M8:** Landlock per command via `landrun` inside the backend container (§0).

### 2.4 Permissions / approve-reject (`hooks/useCanUseTool`, `utils/permissions`)

The core user story. Three layers: (a) the `CanUseToolFn` contract
(`useCanUseTool.tsx:27`): `(tool, input, ctx, msg, id) → PermissionDecision` — allow /
ask / deny; (b) a `PermissionContext` built per tool-call with a **resolve-once guard**
so a decision can't be double-applied; (c) the interactive handler that races automated
checks (hooks, bash classifier) against the user prompt, then **audits** every decision.
Modes: `default` / `plan` / `acceptEdits` / `bypassPermissions`
(`types/permissions.ts:16`); `'auto'` (LLM classifier) is feature-gated and **deferred**.

**Target:** a `PermissionService` implementing the gate in the backend (server is the
authority — the frontend can only *display* and *answer*), with the interactive step
modeled as a one-shot `CompletableFuture<Decision>` backed by a WS message
`ToolUseConfirm { toolName, input, onAllow/onReject }` the React side renders as a
dialog. An `ask` decision may optionally persist a rule (the `PermissionUpdate` union →
Kotlin sealed class) so the user isn't re-asked. Every decision lands in an audit log
(`logPermissionDecision` equivalent) — this is also what the "review reasoning after the
task" story reads. Default `PermissionMode` = `default` (ask on mutation); never expose
`bypassPermissions` in the UI initially.

### 2.5 Sub-agents (`src/tools/AgentTool/`, `coordinator/`)

`AgentTool` (alias `Task`, `AgentTool.tsx:196`) spawns a child `QueryEngine` with its own
agentId/abortController and a filtered tool subset (`resolveAgentTools`, wildcard `'*'`
after the disallow filter). Inputs: `description`/`prompt`/`subagent_type?`/`model?`/
`run_in_background?`; outputs: sync `completed` result or async `async_launched` +
`agentId` + `outputFile`. `SendMessageTool` messages peers; `TeamCreate/Delete` form teams.

**Target:** a `SubAgentOrchestrator` Spring service. Sync sub-agent = a `CompletableFuture`
(the parent loop waits, stream collapsed into one result); async = agentId + a
completion **event** pushed over the session stream — *never poll* (the reference's
`TaskOutputTool` polling exists; don't port the polling, port the event). Teams → a
`TaskService` + per-agent mailboxes when multi-agent coordination is needed; defer behind
the same feature flag the reference uses. Fork semantics (child reuses parent's system
prompt = cache-identical prefix) matter only if prompt caching is kept — see 2.8. UI:
sub-agent **chips** with a live output tail, status pill, and a stop button
(`TaskStopTool` → orchestrator `kill`).

### 2.6 Context management / compaction (`src/services/compact/`)

Four-tier cascade: snip → microcompact (time-based clear at a 60-min gap, keep the 5 most
recent) → context-collapse (flag-gated) → autocompact. `autoCompactIfNeeded`
(`autoCompact.ts:241`) has a circuit breaker (`consecutiveFailures >= 3`) and first tries
`trySessionMemoryCompaction` — **prune only, no LLM call** — the fast path; only when that
isn't enough does it fork a text-only agent to stream a summary (`compact.ts:1136`, with a
`CanUseToolFn` that denies *all* tool use). Threshold = context window − 13,000 token
buffer. Message grouping never tears a `tool_use`/`tool_result` pair apart.

**Target:** a chain of `CompactStrategy` beans, same order and same circuit breaker, invoked
at the top of each loop iteration (2.1, step 1). Threshold and keep-recent counts are
`@ConfigurationProperties` (the README's "adjustable token limits"). Re-inject the most
recent files after compaction (≤5 files × 5k tokens). The session-memory fast path is the
seam where 2.7's write side feeds this — implement them together.

### 2.7 Memory (`src/memdir/` read side, `src/services/*Memory*` write side)

Read: `MEMORY.md` index (truncated 200 lines / 25 KB) loaded once per session into the
system prompt; `findRelevantMemories` scans memory-file headers and lets a small side-query
select ≤5 certain memories ("prefer empty over noise"), excluding the index and already
surfaced. 4-type taxonomy: `user`/`feedback`/`project`/`reference`. Paths are
project-keyed, and every path key is **sanitized server-side** (the reference rejects
path-traversal inputs outright).

Write: four forked-agent subsystems share one `runForkedAgent` primitive with a
restrictive `CanUseToolFn`: `extractMemories` (fired from stop-hooks: a background agent
reads the transcript and writes memory files — it may only write *inside* the memory
directory), `SessionMemory` (periodic notes that feed the 2.6 fast path), `teamMemorySync`
(ETAG pull/push), `autoDream` (scheduled consolidation with a cross-process lock).
**Invariants:** a secret scanner (gitleaks-style) is a mandatory gate before anything
syncs — log rule ids, never secret values; memory files are `0o600`.

**Target:** a `MemoryService` keyed by project (+ team later), keeping the index+topic-file
split. **Not** Spring AI's `ChatMemory` — that is per-conversation message-windowing
(`MessageWindowChatMemory`), and its JDBC/Cassandra/Mongo repos silently drop tool-call
messages, breaking invariant 4. Recall starts **keyword-first** over the headers
(preserving the ≤5 cap + selectivity bias); when embeddings arrive, use `EmbeddingModel` +
`VectorStore` with `topK(5)` set explicitly (the default is 4) and a `similarityThreshold`
for the "prefer empty over noise" gate, in a `RedisVectorStore` or PgVector keyed by a
`project` metadata field. Implement **extractMemories + SessionMemory first** (they unlock
both the "remember coding standards" story and the compact fast path); **defer team sync and
autoDream** — add when multi-user is on the roadmap. The write side's `runForkedAgent` is
just a sub-agent (2.5) with a restricted tool allow-list, so no new machinery is needed. See
[spring-ai-reference/vector-databases.md](spring-ai-reference/vector-databases.md) and
[spring-ai-reference/chat-memory.md](spring-ai-reference/chat-memory.md).

### 2.8 LLM gateway (`src/services/api/`)

`queryModelWithStreaming` (`claude.ts:752`) is the `deps.callModel` target: it yields
`StreamEvent | AssistantMessage | Error`. Client factory handles Anthropic/Bedrock/Vertex
(`client.ts:88`) with OAuth refresh; retry = exponential backoff + 25% jitter,
`MAX_529_RETRIES=3` → fallback model (`withRetry.ts:170`); one `cache_control` marker per
request (5m/1h TTL). Usage is aggregated (`updateUsage`, `:2924`).

**Target:** `ChatClient.prompt().stream()` returning `Flux<ChatResponse>` per provider,
wired as the injected `callModel` (this keeps the loop unit-testable — a fake `ChatModel`
is a VCR). `ClientFactory` bean per provider with per-call headers. `cache_control` is
**not** a `ChatClient` surface — set it per-provider via `AnthropicCacheOptions` /
`AnthropicCacheTtl` (`FIVE_MINUTES` / `ONE_HOUR`, strategy-driven; `NONE` means "unset",
not "off"). Retry is the Anthropic SDK's `maxRetries` (`spring.ai.anthropic.max-retries`,
default 2), **not** the reference's `MAX_529_RETRIES=3` — that constant, and the
fallback-model + OAuth-refresh logic, are not Spring AI built-ins, so claudius wraps them.
A `UsageService` is **claudius-level** aggregation over each
`ChatResponse.getMetadata().getUsage()` (prompt / generation / total tokens) plus
`RateLimit`: Micrometer emits `gen_ai.client.token.usage` but no cost metric, no session
tag, and no stream, so don't read cost from it — aggregate and push to the React usage
panel (README story 3). "Configurable provider/model and token limits" = this section +
`@ConfigurationProperties` (e.g. `spring.ai.anthropic.chat.max-tokens`, default 4096). See
[spring-ai-reference/chat-models.md](spring-ai-reference/chat-models.md) and
[spring-ai-reference/observability.md](spring-ai-reference/observability.md).

### 2.9 Transport (`bridge/`, `server/`, `remote/`, `src/cli/transports/`)

The reference's remote pieces: `bridge` = backend connection manager with a
`'ready'|'connected'|'reconnecting'|'failed'` state machine; `remote` = the UI-driven
session client; `server` = local session host; `upstreamproxy` = ant-only (omit).

**Target (decided 2026-10-09, see §0):** the backend exposes **REST for commands** (submit
message, cancel, approve/reject, answer) and an **SSE stream per run** carrying the loop's
message stream, tool-call events, permission prompts, and sub-agent notifications — the one
channel the React frontend renders. No WebSocket and no reconnect state machine: a run is one
HTTP request, and a dropped connection cancels the run (the stop signal is wired to the sink's
cancel). Drop the ant/cloud auth and the proxy.

### 2.10 Frontend (`ink/`, `components/`, `screens/` → React)

The Ink renderer becomes a React app with four surfaces: a **transcript** (message +
reasoning history — story 7), **live tool-call cards** with a progress tail and a
status pill (story 2), **approval dialogs** driven by `ToolUseConfirm` events (story 6),
and a **settings panel** for provider/model/limits + permission mode (story 3). Sub-agents
render as chips with a live output tail (story 5). The `render*` hooks from 2.3 are the
backend's contract; React owns all layout. No terminal concepts (vim mode, keybindings,
voice) cross into claudius.

---

## 3. Mapping to the 7 user stories

| Story | Where it lives |
| --- | --- |
| 1. End-to-end task ("refactor X to use DI") | 2.1 loop + 2.3 (`FileEdit`/`Bash`/`Agent`) + 2.4 (writes/commands gated) |
| 2. Live plan / tool call / reasoning | 2.1 stream + 2.9 WS/SSE + 2.10 cards/chips |
| 3. Configurable provider/model + token limits | 2.8 `ClientFactory` + `@ConfigurationProperties` + 2.10 settings panel |
| 4. Remember coding standards across sessions | 2.7 (`extractMemories` + `SessionMemory`) |
| 5. Parallel sub-agents | 2.5 `SubAgentOrchestrator` (bounded concurrency; teams deferred) |
| 6. Approve/reject mutating tool calls | 2.4 `PermissionService` + `ToolUseConfirm` over WS + audit log |
| 7. Review history + reasoning after task | 2.2 session history + 2.4 audit log + 2.10 transcript |

## 4. Build order (what ships first)

1. **Skeleton** — backend module (Spring Boot + Spring AI), frontend module (Vite + React),
   WS channel, fake `callModel`. Verifies 2.9/2.10 end-to-end with no LLM.
2. **Loop + one tool** — `AgentLoop` pipeline + `FileRead`/`FileEdit`/`Bash` +
   `PermissionService` in `default` mode. Verifies stories 1 and 6 minimally.
3. **LLM gateway** — real `callModel` (Anthropic first), retry/fallback, `UsageService`.
   Story 3.
4. **Sub-agents** — `SubAgentOrchestrator` sync path + chips. Story 5.
5. **Compaction + memory** — `CompactStrategy` chain, `SessionMemory`,
   `extractMemories`. Stories 4 + long-session stability.
6. **Extras** — `WebFetch`/`WebSearch`, MCP integration, teams, autoDream,
   `'auto'` permission mode — each additive, each behind a flag as in the reference.

## 5. Deliberately deferred (ponytail: add when needed)

- **`'auto'`/classifier permission mode, team sync, autoDream, MCP, REPL/skills tools,
  context-collapse** — all feature-flagged in the reference; claudius ships the flag
  *off*, same as upstream. Add when multi-user or scale forces it.
- **Tool-schema deferral (`ToolSearchTool`)** — a token/cost win with ~50 tools; with the
  ~10-tool core it's unnecessary.
- **Prompt-cache byte-stability backfill (2.1 step 2)** — keep the *invariant*
  (don't mutate the cached prefix), not the machinery, until caching is enabled.
- **Session resume/rewind, telemetry, plugins, IDE integration** — out of scope for v1;
  the reference docs mark them ant/cloud-only.

## 6. Key invariants that must survive the port

1. **No dangling `tool_use`** — every outstanding tool call gets a `tool_result` before the
   next model call (synthesized on cancel, `query.ts:1019`).
2. **The permission server is authoritative** — the frontend answers a prompt; it can never
   be the gate.
3. **Fail-closed tools** — a tool that omits a capability flag is treated as mutating and
   concurrent-unsafe.
4. **Compaction never tears a `tool_use`/`tool_result` pair**; autocompact's first attempt
   is LLM-free (session memory).
5. **Sub-agent results are events, not polls** — completion is pushed; `TaskOutput` polling
   is not ported.
6. **Secrets never sync** — the write-side secret scanner is mandatory before any memory
   leaves the box.
7. **The loop is injectable** — `callModel`, compaction, and permissions are injected
   functions; that's what makes the whole thing testable without a live model.
