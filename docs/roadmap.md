# Roadmap

`claudius` builds from **simple chat → full-featured agent**. Each milestone is a runnable
end-to-end slice on top of the previous one. This list is **living** — append new milestones
from the template at the bottom; don't renumber existing ones.

Stack: Kotlin + Spring AI 2.0.1 (Spring Boot 4.1.1, WebFlux; Ollama, OpenAI-compatible, Anthropic) backend · React 19 + Vite
frontend. See [../README.md](../README.md) for the full design.

## Arc

| # | Milestone | Ships end-to-end | Backend seam | Frontend seam |
|---|-----------|------------------|--------------|---------------|
| **M1** | **Chat MVP** ✅ | Multi-turn chat, live-streamed, browser ↔ Ollama | `ChatClient` (Ollama) + SSE stream + in-memory session | Chat UI + composer + streaming render + Vite proxy |
| **M2** | **Workspaces** ✅ | Create/manage workspaces (a working dir); sessions persist and reload across restarts | `Workspace` + persistent `SessionStore` scoped to workspace; REST CRUD + session list/load | Workspace switcher + session list + load transcript |
| **M3** | **Enable thinking** ✅ | Reasoning trace per turn — live + persisted | `ChatService` reads `metadata["thinking"]` + `event: reasoning` SSE + thinking rows in store | Collapsible "thinking" block per turn + `reasoning` event |
| **M4** | **Loop + read-only tools** ✅ | Agent loop calls a tool (`CurrentTime`), feeds result back; UI shows call + result | Manual `ToolCallingManager` loop + fail-closed `Tool` iface + `CurrentTime` + `tool_call`/`tool_result` SSE + fake-`ChatModel` VCR | Tool-call card in transcript |
| **M5** | **Run control** ✅ | Stop a run at any time; errors surface instead of hanging; tool cards survive reload | `runId` + `POST /api/chat/{runId}/cancel`; stop signal wired to the sink cancel; `event: error` + `done` always; iteration cap; claudius-owned fail-closed tool execution; 409 on a busy workspace; `tools` as a list | Stop button; `error` event; finalize on any stream end; stale-load guard |
| **M6** | **Read-only workspace tools** ✅ | The agent reads, lists and searches files in its workspace; earlier tool results stay in context | `fileRead`/`glob`/`grep` behind `resolveInWorkspace`; tool rows replayed into model context | Tool card shows arguments + running state |
| **M7** | **Permission gate + first mutating tools** ✅ | The user approves or rejects each `fileWrite`/`fileEdit`; every decision is audited | `ToolRegistry.permission` (allow/ask/deny); `event: permission` + `POST /api/chat/{runId}/answer`; `audit` table | Approve/Reject dialog on `Dialog.tsx` |
| **M8** | **Bash in a sandbox** ✅ | The agent runs shell commands in its workspace, each approved by the user, with live output | `bash` tool; Landlock per command via `landrun`; session DB moved out of the workspace dir; backend `Dockerfile` | Live output pane on the tool card |
| **M9** | **Sub-agents** ✅ | The model delegates independent research to sub-agents that run in parallel and report back | `AgentLoop` split out of `ChatService`; `agent` tool (fresh context, read-only tools, no recursion); parallel calls without a dialog | Sub-agent chips with status + live output |
| **M10** | **Provider/model config** ✅ | Add Ollama, OpenAI-compatible and Anthropic providers in the UI; pick the provider + model per message; the workspace remembers the last choice | `provider` package: registry table, REST CRUD + model list, `ChatModelFactory`; `AgentLoop` gets the model per run (generic options, no Ollama cast) | Settings panel + model picker + model label per turn |
| **M11** | **Context tracking + compaction** ✅ | Each workspace tracks its context size against the model's window (or a smaller workspace limit); old turns are summarized before a turn that would not fit, inside a long tool loop, on demand, or after a yes to the compact-first prompt | `context_tokens` per workspace from the provider's usage; `Compactor` (summary rows with `covers_until`, streamed summary, input cut to the window); `compacted`/`usage` SSE; `POST /api/workspaces/{id}/compact` | Usage bar + Compact button + compact-first dialog + collapsible summary block |
| **M12** | **WebSocket transport + detached runs** ✅ | A run survives a closed tab or a dropped connection; a reconnect resumes it; two tabs see the same run; Stop also cancels the provider call | `/api/ws` (`ChatSocket`, ping 1 s), `RunEvents` log with seq + replay, runs detached from connections, Stop interrupts tools | `api/socket.ts` (reconnect, watchdog, resubscribe from the last seq), "reconnecting…" status |
| **M13** | **Autonomous tasks** ✅ | Hand claudius a high-level task; it plans in `PLAN.md`, works through the steps, verifies, reports; a per-workspace permission mode (ask / accept edits / bypass) decides what still needs a click; `CLAUDIUS.md` holds the workspace's standing instructions | `permission_mode` on `workspace` + `PUT …/permission-mode`; `AgentLoop.permission(name, depth, mode)`; work-method system prompt + `CLAUDIUS.md` appended | Permission mode select in the header (bypass in red) |

---

## M1 — Chat MVP ✅ DONE (acceptance closed 2026-09-25)

- **Ships:** type in the browser → Ollama streams a reply live → multi-turn within a session.
- **Backend:** `chat/` package — in-memory `SessionStore` (per-session message list), `ChatService`
  driving `chatModel.stream(Prompt)` (Spring AI 2.0.1: text accessor is `.text`/`getText()`, not
   `.content`), `ChatController` SSE endpoint
   (`POST /api/chat` → `Flux<ServerSentEvent<String>>`, `event: token` per delta + `event: done`).
- **Frontend:** `App` chat layout, `MessageList`/`Message`/`Composer`, `api/chat.ts` (POST +
  hand-rolled SSE reader), `hooks/useChat.ts` (rAF-coalesced deltas), Vite `/api` proxy.
- **Verified:** `./gradlew test` (kotest `StepVerifier` slice, no live Ollama) + `contextLoads` pass;
   `curl -N` streamed `event: token` ×3 + `event: done` against `qwen3.8:27b-mlx`; a second turn on the
  same session recalled the first; Vite proxy (5173→8080) streamed end-to-end and the app rendered.
- **Acceptance (M1 gate, all 4 met):**
     1. **Coverage of core, from reports (not guessed):** backend `chat`+`chat/dto` = **100%** on
       instruction/branch/line/complexity/method/class (overall report 96.4% instr / 94.6% line — the only
       uncovered code is the `@SpringBootApplication` entry-point, not core). Frontend = **100%** on
       statements/branch/functions/lines across all 7 files (`useChat`, `chat`, `Message`, `MessageList`,
        `Composer`, `App`, `main`).
     2. **All tests pass:** backend 12 / frontend 23 — 0 failures, 0 errors.
     3. **No ignored tests:** 0 `skipped` (backend JUnit XML), 0 `.skip`/`it.todo` (frontend).
     4. **Live Docker-browser chat:** the reported `…`/garbled-render bug (empty-content coalescing in
        `useChat.flush`) is fixed and verified — all three turns rendered full, coherent assistant text.
- **Note:** `application.yaml` defaults `spring.ai.ollama.chat.model=qwen3.8:27b-mlx`; override
   with `SPRING_AI_OLLAMA_CHAT_MODEL=<installed>`.
- **Deferred:** everything below M1 in the arc. Jackson is used for DTOs (not kotlinx.serialization).

## M2 — Workspaces ✅ DONE (acceptance closed 2026-09-28)

- **Ships:** the user creates, renames, lists, and deletes workspaces, each backed by a working
  directory; sessions started in a workspace persist and can be reloaded after a restart.
- **Backend:** a `Workspace` (id, name, working-dir path) and a persistent `SessionStore`
  (JPA or file-backed, replacing M1's in-memory map) scoped to a workspace; REST CRUD for
  workspaces plus list/load a workspace's sessions. The agent *acting inside* the working dir
  (file/bash tools) is a later milestone — M2 is data model + persistence + UI only.
- **Frontend:** a workspace switcher, a per-workspace session list, and loading a past session's
  transcript into the M1 chat view.
- **Done-when:** create a workspace, start a session in it, restart the app, and the workspace and
  its session history reappear and can be resumed; deleting a workspace removes it.
- **Deferred:** the agent reading/writing files or running commands in the working dir (needs tool
  calling — a later milestone); session search and cross-session memory (cf. reference `memdir/`).
- **Design (the workspace IS the session):** one chat thread per workspace — the workspace *is* the
  session, so no multi-session-per-workspace model. M1's in-memory map is replaced by a
  SQLite-backed `SessionStore`: one `chat-history.db` per workspace, one `JdbcTemplate`/
  `SQLiteDataSource` each (no pooling, `org.xerial:sqlite-jdbc:3.50.3.0`), blocking JDBC run on
  `Schedulers.boundedElastic()`.
- **Storage layout:** the registry DB lives at `<workspaces-dir>/.claudius/workspaces.db` — a
  hidden dir *outside* the workspace dirs, so deleting a workspace never loses the registry. Per-workspace
  history lives at `<workspaces-dir>/<dir>/.claudius/chat-history.db`. `claudius.workspaces-dir`
  defaults to `./workspaces` (override `CLAUDIUS_WORKSPACES_DIR`).
- **Verified:** `./gradlew test` — backend `chat`/`chat/dto`/`workspace`/`workspace/dto` = **100%** on
  every Jacoco dimension; the only sub-100% code is the root `claudius` `@SpringBootApplication`
  entry point (same accepted exclusion as M1). Frontend `workspaces.ts`/`Sidebar.tsx`/`useChat.ts`/
  `App.tsx` = **100%** on statements/branch/functions/lines across all 11 files. Restart-resume is
  proven at the store level: the `SessionStore` round-trips through SQLite and the registry re-reads
  from disk after the store is dropped; delete removes the dir post-order (a `ponytail:` — one
  `chat-history.db`/workspace, upgrade to per-session tables when multi-session lands).
- **Acceptance (M2 gate, all 4 met):**
  1. **Coverage of core, from reports (not guessed):** the four M2/core packages are 100% on
     instruction/branch/line/complexity/method/class; frontend is 100% on every dimension, all files.
  2. **All tests pass:** backend 37 / frontend 44 — 0 failures, 0 errors.
  3. **No ignored tests:** 0 `skipped` (backend JUnit XML), 0 `.skip`/`it.todo` (frontend).
  4. **Done-when:** create/rename/list/delete a workspace (REST + UI); a workspace and its transcript
     survive a store drop and reload on re-open; deleting a workspace removes its dir.
- **Note:** a live "boot → chat → restart → resume" smoke needs Ollama up; the persistence half is
  exercised by the SQLite round-trip tests above, so a live run is belt-and-suspenders, not a gate.

---

## M3 — Enable thinking ✅ DONE (acceptance closed 2026-10-04)

- **Ships:** the user sees the model's reasoning trace per turn — a collapsible "thinking" block
  above each assistant answer, live as it streams and persisted after a reload. Targets the
  "see the agent's thinking process" and "review reasoning after task" user stories.
- **Backend:** the seam is `ChatService.chat` — today it reads only `it.result?.output?.text`, so
  the Ollama 0.12+ reasoning trace is streamed to the backend and silently discarded. M3 *also* reads
   `it.result?.metadata?.get("thinking")` per streamed `ChatResponse` (Spring AI 2.0.1 surfaces
  reasoning in response metadata, on the stream; `spring.ai.ollama.chat.think` /
   `OllamaChatOptions.enableThinking()` opts in, auto for `qwen3` models), emits a new
   `event: reasoning` on the existing `POST /api/chat` firehose, and persists a thinking row in
   `SessionStore`/`MessageDto` (a new role or a `thinking` flag) so it reloads.
- **Frontend:** `MessageList`/`Message` render a collapsed "thinking" block per turn (expand on
  click); `api/chat.ts` handles the `reasoning` event and coalesces it like `token`.
- **Done-when:** a turn shows its reasoning trace live, and after a store drop + reload the trace
  reappears. `ponytail:` same live-run caveat as M2 — reasoning needs Ollama up; the persist half is
  proven by a fake-`ChatModel` VCR test that emits `reasoning` deltas + rows, so a live run is
  belt-and-suspenders, not a gate.
- **Deferred:** a thinking on/off + effort toggle in the UI, per-provider reasoning, GPT-OSS
   `.thinkLow/Medium/High` levels, and thinking-vs-answer separation in the store (cf. reference
   `memdir/`).
- **Note:** the specific `qwen3.8:27b-mlx` tag may need `enableThinking()` to emit a trace (docs
  auto-think `qwen3:*-thinking` variants); the done-when live-run confirms it and the fix is a
  config toggle, not a re-architecture.
- **Verified:** `./gradlew test jacocoTestReport` (backend) + `npx vitest run --coverage`
  (frontend) both GREEN. Backend core `chat`/`chat/dto` = **100%** on every Jacoco dimension
   (instruction 521/521 + 54/54, branch 32/32, line 86/86 + 9/9, complexity 46/46 + 9/9, method
  30/30 + 9/9, class 5/5 + 2/2); the only sub-100% code is the root `@SpringBootApplication` entry
  point (same accepted exclusion as M1/M2). The M3 reasoning path is fully covered on both halves:
  the backend reads `metadata["thinking"]` per streamed `ChatResponse` and emits a new
   `event: reasoning` (a present-but-empty trace contributes no chunk, and a reasoning-only turn
  still persists its trace), persisted to `MessageDto.thinking` + the store's `thinking` column;
  the frontend renders a native `<details>` "thinking" block per assistant turn (`Message.tsx` /
   `MessageList.tsx` = **100%** every dimension) and coalesces `reasoning` deltas through the
   parallel `traceRef`/`reasoningPending` buffers in `useChat.ts` so a turn's trace and answer land
  in one render.
- **Acceptance (M3 gate, all 4 met):**
  1. **Coverage of core, from reports (not guessed):** backend `chat`/`chat/dto` are 100% on
     instruction/branch/line/complexity/method/class; the M3 frontend surface (`Message.tsx` /
     `MessageList.tsx`) is 100% on every dimension, and `chat.ts` / `useChat.ts` are 100% on
     statements/functions/lines with branch below 100% only on the pre-existing error/edge guards
     the coverage config explicitly accepts as a 90% floor (`ErrorBoundary`, SSE guards).
  2. **All tests pass:** backend 43 / frontend 59 — 0 failures, 0 errors.
  3. **No ignored tests:** 0 `skipped` (backend JUnit XML), 0 `.skip`/`it.todo` (frontend).
  4. **Done-when:** a turn streams its reasoning trace live as `event: reasoning` deltas and the
     trace persists (a `thinking` column) so it reappears after a store drop + reload.
- **Note (M3):** same live-run caveat as M2 — emitting a trace needs Ollama up with a thinking model;
  the persist half is proven by a fake-`ChatModel` VCR test that emits `reasoning` deltas + a
  `thinking` row and reads it back, so a live run is belt-and-suspenders, not a gate.

## M4 — Loop + read-only tools (`CurrentTime` only) ✅ DONE (acceptance closed 2026-10-07)

- **Ships:** the agent loop fires, the model can call a tool, the result is fed back, and the UI
  shows the call + result — one trivial read-only tool, `CurrentTime`, proving the loop end-to-end
  with zero security surface.
- **Backend:** a manual `ToolCallingManager` loop (stream → detect `tool_call` blocks → run →
  append `tool_result` → re-stream; loop-exit = a turn returns no `tool_call` block, don't trust
   `stop_reason`; invariant = no dangling `tool_call`, synthesize a `tool_result` on cancel), built
  bypassing the auto-registered `ToolCallingAdvisor`; a claudius `Tool` interface with **fail-closed**
  defaults (`isReadOnly`/`isConcurrencySafe`/`isDestructive`); `CurrentTime` as the one registered
  tool (read-only + concurrency-safe → auto-allow, no permission gate yet since nothing mutates); new
   `tool_call`/`tool_result` SSE events; tool rows in `SessionStore`; a fake-`ChatModel` VCR so
  tests don't need live Ollama. `OllamaChatModel` supports tool calls in streaming mode (Ollama
  0.4.6+).
- **Frontend:** a tool-call card in the transcript showing the invocation + result.
- **Done-when:** a turn invokes `CurrentTime`, the result is fed back, and the UI shows call +
  result.
- **Deferred:** the permission/approval gate (nothing mutates yet, so nothing to gate — it lands with
  the first mutating tool), real file/command tools (`FileRead`/`Glob`/`Grep`/`Bash`, cf. reference
   `src/tools/`), and sub-agents — each its own later milestone. More read-only tools slot in behind
  the same seam without touching the loop.
- **Note:** `CurrentTime` is chosen to isolate "does the loop work" from "is the tool safe" — no file
  access, no sandbox, no approval — so a later mutating tool is the only new variable when the gate
  lands.
- **Verified:** `./gradlew test` (backend) + `npx vitest run --coverage` (frontend) both GREEN.
  Backend core `chat` = **100%** on every Jacoco dimension; the only sub-100% code is the two
  non-core artifacts — the root `@SpringBootApplication` entry point (`ClaudiusBackendApplicationKt`,
  same accepted exclusion as M1–M3) and `chat/tool/Tool$DefaultImpls`, a Kotlin constant-fold
  trampoline for the interface's `isReadOnly`/`isConcurrencySafe`/`isDestructive` default `false`s
  whose logic is 100% covered in `Tool` itself. Report-side exclusion of these is infeasible on the
  current toolchain — Gradle 9.7.1 removed the JaCoCo `classExcludes` API — so they are documented
  here rather than dropped from the report. The M4 frontend surface is **100%** on every dimension
  for `Message.tsx` (the tool-call card), and `useChat.ts` / `chat.ts` / `workspaces.ts` are 100%
  on statements/functions/lines with branch below 100% only on the pre-existing error/edge guards
  the coverage config explicitly accepts as a 90% floor (same accepted sub-floor partials as M3).
- **Acceptance (M4 gate, all 4 met):**
  1. **Coverage of core, from reports (not guessed):** backend `chat` is 100% on
     instruction/branch/line/complexity/method/class; the M4 frontend surface (`Message.tsx` tool
     card) is 100% on every dimension, and `chat.ts` / `useChat.ts` / `workspaces.ts` are 100% on
     statements/functions/lines with branch below 100% only on pre-existing error/edge guards the
     config accepts as a 90% floor (not M4-introduced).
  2. **All tests pass:** backend 61 / frontend 67 — 0 failures, 0 errors.
  3. **No ignored tests:** 0 `skipped` (backend JUnit XML), 0 `.skip`/`it.todo` (frontend).
  4. **Done-when:** a turn invokes `CurrentTime`, the result is fed back into the loop, and the UI
     shows the call + result as a tool-call card in the transcript.
- **Note (M4):** same live-run caveat as M2/M3 — a real loop needs Ollama up with a tool-capable
  model; the loop + tool-execution + VCR half is proven by a fake-`ChatModel` test that emits a
  `tool_call` block, runs `CurrentTime`, and re-streams the `tool_result`, so a live run is
  belt-and-suspenders, not a gate.

---

## M5 — Run control ✅ DONE (acceptance closed 2026-10-09)

- **Ships:** the user can stop a run at any time; a model error shows as an error row instead of a
  hung composer; tool cards reappear after a reload; a workspace cannot run two turns at once.
  Prompted by [review-2026-10-09.md](review-2026-10-09.md) findings 1–8, 10, 11, 13.
- **Backend:** `ChatRequest.runId` (client UUID); `POST /api/chat/{runId}/cancel` (204); a per-run
  stop signal (`Sinks.empty`) wired to `FluxSink.onCancel` and `takeUntilOther` on the model
  stream, so a disconnect or a cancel ends the loop and persists the partial answer;
  `StreamChunk.Kind.ERROR` → `event: error`, and `done` is always the last event;
  `claudius.chat.max-iterations` (default 25) replaces `unlimitedTotalToolCalls()`;
  `ToolCallingManager` is gone — `ToolRegistry.run` executes autoRun tools only and turns
  unknown/non-autoRun/throwing tools into an error `tool_result` (Spring AI resolves callbacks from
  the prompt options first, so the old resolver filter gated nothing); 409 when a run is active for
  the workspace; `MessageDto.tools: List<ToolInvocationDto>` persisted once (the M4 code
  double-encoded it, so cards never reloaded). `WorkspaceService` rejects dot-prefixed and
  colliding dirs (a workspace named `.` deleted every workspace).
- **Frontend:** `useChat.stop()` (abort + cancel endpoint) and a Stop button in `Composer`;
  `error` event → `error`; `finally` clears `streaming` even when the body closes without `done`;
  `rafId`/buffers reset after an error; a sequence guard on `loadTranscript`; `normalizeTools`
  deleted; dev proxy timeout 10 min.
- **Done-when:** Stop mid-stream ends with `done`, the composer re-enables and the backend makes no
  further model call; a model error shows an error and re-enables the composer; a tool turn's card
  is still there after a reload; creating a workspace named `.` is a 400.
- **Deferred:** replaying tool rows into model context, the tool card's running state and
  arguments (M6); the permission gate (M7); per-tool-call ids from Ollama are passed through as-is
  (`""` when null — verify with a live model).
- **Verified:** `./gradlew check` (backend, includes the new JaCoCo gate) + `npm run format:check && npm run
  lint && npm run build && npx vitest run --coverage` (frontend) both GREEN. Backend core from the JaCoCo
  XML: `chat` **100%** line (142/142), 97.3% branch; `chat/tool` **100%** line (30/30), 90% branch (the
  `Tool$DefaultImpls` trampoline, same accepted artifact as M4); `chat/dto`, `workspace`, `workspace/dto`
  **100%** line. The only sub-rule code is the root `@SpringBootApplication` entry point, outside the
  package rule. Frontend all files: 95.5% statements / 92.7% branch / 97.6% functions / 95.5% lines
  against the 90% floor.
- **Acceptance (M5 gate, all 4 met):**
  1. **Coverage of core, from reports (not guessed):** every `chat*`/`workspace*` package is 100% line;
     the build fails below 90% (`jacocoTestCoverageVerification` in `check`). Frontend ≥ 92.7% on every
     dimension.
  2. **All tests pass:** backend 85 / frontend 80 — 0 failures, 0 errors.
  3. **No ignored tests:** 0 `skipped` (backend JUnit XML), 0 `.skip`/`it.todo` (frontend).
  4. **Done-when:** proven by tests on both halves (cancel via `thenCancel` and via `runId`; `error` chunk
     then `done`; iteration cap; fail-closed `run`; 409 on a busy workspace; a store failure releases the
     workspace; tool trace round-trips as objects; `.` / `.claudius` / case-colliding names → 400; frontend
     finalize-without-`done`, Stop → abort + cancel with no error row, stale load ignored) plus a live
     backend smoke without Ollama (see the note).
- **Note (M5):** live smoke with Ollama *down* exercises the new paths end-to-end: `POST /api/chat` streams
  `event:error` then `event:done` instead of hanging; `POST /api/chat/{runId}/cancel` is 204; creating a
  workspace named `.` is 400. A smoke with Ollama *up* (Stop mid-stream) is belt-and-suspenders, as in
  M2–M4; the cancel path is proven by the `thenCancel` and `runId` tests.

---

## M6 — Read-only workspace tools ✅ DONE (acceptance closed 2026-10-09)

- **Ships:** the agent reads, lists and searches files inside its workspace dir. A later turn still
  sees the earlier turns' tool calls and results. Closes review findings 9 and 17.
- **Backend:** `chat/tool/WorkspacePath.kt` `resolveInWorkspace(root, path)` is the one path guard:
  it rejects `..`, absolute paths, symlinks that resolve outside the workspace (also dangling ones),
  and anything under `.claudius/`. `WorkspaceRegistry.dirOf(id)` gives the loop the workspace root;
  `Tool.run(arguments, workspaceDir)`. Tools (`FileTools.kt`): `fileRead` {path} (100 000-char cap),
  `glob` {pattern} (a pattern without `/` matches the file name), `grep` {pattern, glob?} (Java regex,
  skips links, files > 1 MB and non-UTF-8 files). Glob and grep cap at 200 lines and never list
  `.claudius/` or follow links. All three are autoRun. `ToolArgs.kt` parses the model's JSON.
  `ChatService` replays a persisted tool turn as an `AssistantMessage` with the tool calls, one
  `ToolResponseMessage`, then the answer; a blank argument replays as `{}` (Ollama parses it as JSON).
  A call with no model id gets a UUID. `appendAssistant` keeps a tool-only turn (a stopped tool turn
  lost its trace before). `ToolCallbackAdapter.call` now refuses: only `ToolRegistry.run` executes.
- **Frontend:** `Message.tsx` renders every tool card with its arguments (hidden when empty or `{}`)
  and `running…` until the result lands.
- **Done-when:** the model reads a workspace file, and a later turn answers from that tool result
  without a new call.
- **Deferred:** line ranges for `fileRead`, `.gitignore`-aware walks, a parallel runner for
  concurrency-safe tools (2.1), the stale "running…" card after Stop (live view only; a reload
  shows the persisted trace).

## M7 — Permission gate + first mutating tools ✅ DONE (acceptance closed 2026-10-09)

- **Ships:** the model can write and edit workspace files, but only after the user approves each call
  in a dialog. Stop rejects a pending call. Every decision lands in an audit table.
- **Backend:** `ToolRegistry.permission(name)` → `ALLOW` (autoRun), `ASK` (known, not autoRun), `DENY`
  (unknown). `ToolRegistry.run(…, approved)` stays fail-closed: a non-autoRun tool runs only with
  `approved = true`. On `ASK` the loop registers a `CompletableFuture` for the call id, emits
  `event: permission` (the `ToolInvocationDto` shape), and blocks until
  `POST /api/chat/{runId}/answer {id, approved}` (204; unknown run or id is a no-op). `Run.stop()`
  completes every pending answer with `false`, so Stop or a disconnect never leaves the loop waiting.
  A rejection feeds `error: the user rejected this call` back to the model. `SessionStore` gains an
  `audit` table (`tool`, `arguments`, `decision` ∈ allow/deny/approved/rejected) + `appendAudit`/
  `audit`. Tools: `fileWrite` {path, content} and `fileEdit` {path, old_string, new_string}
  (old_string must occur exactly once) — both keep the fail-closed flags.
- **Frontend:** `chat.ts` `onPermission` + `answerChat`; `useChat` exposes `approval` and a stable
  `answer(approved)`, cleared on stream end and on Stop; `App` shows `Dialog` ("Allow fileWrite?",
  arguments in a `<pre>`, Approve/Reject). `Dialog` gains `children` and `cancelLabel`.
- **Done-when:** a `fileWrite` call waits for the user; Approve writes the file and audits `approved`;
  Reject (or Stop) writes nothing and audits `rejected`.
- **Deferred:** the `PermissionService` / `AgentLoop` split from the backlog — one decision function
  in `ToolRegistry` and one loop in `ChatService` cover it today; split when M9 sub-agents need a
  second loop caller or when permission rules/modes (`acceptEdits`, remembered "always allow") land.
  A REST read of the audit table (the store method exists). A wait timeout for an unanswered call
  (Stop and disconnect already end it).
- **Defect found in the live smoke (fixed, both milestones):** SSE events reached the browser only when
  the turn ended — or never, while the loop waited for approval. `Flux.create` +
  `subscribeOn(boundedElastic())` scheduled each downstream `request(n)` on the worker that the
  blocking loop occupies; Netty asks one event at a time, so demand piled up behind the turn.
  Fix: `subscribeOn(Schedulers.boundedElastic(), false)`. StepVerifier's default unbounded demand hid
  it; the regression tests use a one-at-a-time subscriber and a model that emits on another thread.
- **Verified (M6 + M7):** `./gradlew check` (backend) + `npm run format:check && npm run lint && npm run
  build && npx vitest run --coverage` (frontend) both GREEN. Backend from the JaCoCo XML: `chat`,
  `chat/tool`, `chat/dto`, `workspace`, `workspace/dto` all **100%** line; branch 97.0% (`chat`),
  97.8% (`chat/tool`), 98.0% (`workspace`). Frontend all files: 95.88% statements / 92.56% branch /
  97.72% functions / 95.88% lines against the 90% floor.
- **Acceptance (M6 + M7 gate, all 4 met):**
  1. **Coverage of core, from reports:** every `chat*`/`workspace*` package is 100% line; frontend ≥ 92.5%
     on every dimension.
  2. **All tests pass:** backend 136 / frontend 98 — 0 failures, 0 errors.
  3. **No ignored tests:** 0 `skipped` (backend JUnit XML), 0 `.skip`/`it.todo` (frontend).
  4. **Done-when, live with Ollama up (`qwen3.8:27b-mlx`) through the Vite proxy in the browser:**
     "read notes.txt … then create summary.txt" → a `fileRead` card (arguments + result, audited
     `allow`), a `fileWrite` card in `running…` with the Approve/Reject dialog, Approve → the file holds
     the exact text and the audit says `approved`; a follow-up "without calling any tool, what was the
     second line of notes.txt" answered `answer=42` from the replayed tool result. An earlier run
     stopped at the dialog persisted the trace and audited `rejected`.

---

## M8 — Bash in a sandbox ✅ DONE (acceptance closed 2026-10-09)

- **Ships:** the model runs shell commands (`sh -c`) in its workspace. Every command needs the user's
  approval (the M7 gate), and its output streams live onto the tool card.
- **Decision (Architecture §2.3, the open question):** Landlock per command via the `landrun` CLI
  (v0.1.17, built from source in the image). The backend itself runs in Docker, so a container per
  command would need the host Docker socket (= host root) or a privileged daemon. bubblewrap needs
  user namespaces, which Docker's default seccomp profile blocks (verified: it fails in a default
  container and works only with `seccomp=unconfined`). Landlock needs no namespaces and no extra
  container privileges; Landlock ABI 8 is available in a default container here (Docker Desktop,
  kernel 7.0). Rejected: Docker socket / socket proxy (host escape), bubblewrap (host seccomp +
  AppArmor profiles), sidecar runner (all workspaces in one runner, a second service).
- **Backend:** `chat/tool/Bash.kt` (`bash` {command}; fail-closed flags → ASK). Policy, repeated flags so a
  comma in a path cannot widen it: rw on the workspace (real path) + a per-command temp dir
  (`<workspaces-dir>/.claudius/tmp/<uuid>`, deleted afterwards) + `/dev/null`; read/exec on `/usr`,
  `/bin`, `/lib`, `/lib64`, `/sbin`; read on `/etc`; no TCP. The environment is rebuilt from scratch
  (PATH, HOME/TMPDIR = temp dir, LANG), so backend secrets never reach a command. A fail-closed probe
  runs once: `--best-effort` silently drops Landlock on a kernel without it, so a sandboxed `cat` of a
  file outside the policy must fail, or every bash call errors "sandbox unavailable". Timeout
  (`claudius.bash.timeout-seconds`, 120) kills the process and its descendants; output capped at
  30 000 chars; `Tool.run` gains `onOutput` → `event: tool_output` (live only, not persisted).
  `claudius.bash.sandbox=none` runs commands unsandboxed (still approved, still a clean env) for local
  dev outside Docker; it logs a WARN. **Session DBs moved** from `<workspace>/.claudius/chat-history.db`
  to `<workspaces-dir>/.claudius/sessions/<id>.db` (moved on first use; deleted with the workspace):
  Landlock cannot deny a subdir inside an allowed dir, and the DB holds the history and the audit.
- **Image:** a `Dockerfile` with a landrun stage, a build stage, a `landlock-test` stage (runs
  `./gradlew landlockTest` on Linux), and a runtime on `eclipse-temurin:25-jre` as a non-root user,
  data at `/data/workspaces`, Ollama at `host.docker.internal:11434` by default. CI builds both
  targets. **Since 2026-10-09 (after M8):** the Dockerfile sits at the repo root and builds one image
  with the frontend too (`node:22-slim` stage → `classpath:/static`, served by Spring Boot's welcome
  page next to `/api`, guarded by `FrontendServingTest`); `docker-compose.yml` runs it locally.
- **Frontend:** `tool_output` → `onToolOutput`; `useChat` appends deltas to the tool's live `output`;
  `Message` shows the last 4000 chars in a scrolling `<pre>` while the tool runs, the result after.
- **Done-when:** in the backend container, an approved command streams its output live, reads its own
  workspace, and gets "Permission denied" for another workspace, the claudius data dir and TCP.
- **Known limits (documented, not fixed):** Landlock does not cover UDP, so DNS lookups and other UDP
  work; no PID/mount namespace (a command sees the container's process list, though `/proc/1/environ`
  is denied); no CPU/memory limit per command beyond the timeout (the container's limits apply); a
  detached grandchild can outlive the timeout (bounded reader join); Stop waits for a running command
  (≤ the timeout). Upgrade path: a cgroup/pid namespace per command, or a microVM runner.
- **Deferred:** read-only command auto-allow (the reference's 2000-line classifier), background
  commands, a persistent cwd/env across commands, Stop killing a running command.
- **Verified:** `./gradlew check` (backend, macOS) GREEN three times in a row; `docker build --target
  landlock-test` runs the 5 Landlock tests on Linux — 5/5 pass (as root, so the denials are Landlock,
  not Unix permissions). Backend from the JaCoCo XML: `chat` 100% line / 97.1% branch, `chat/tool`
  97.2% / 89.7%, `workspace` 97.8% / 98.0%, `chat/dto` and `workspace/dto` 100% line. Frontend all
  files: 95.72% statements / 92.12% branch / 97.77% functions / 95.72% lines.
- **Acceptance (M8 gate, all 4 met):**
  1. **Coverage of core, from reports:** every `chat*`/`workspace*` package ≥ 97.2% line (gate 90%).
  2. **All tests pass:** backend 152 (`test`) + 5 (`landlockTest`) / frontend 103 — 0 failures, 0 errors.
  3. **No ignored tests:** 0 `skipped`; the Landlock tests are a separate task, not skipped ones.
  4. **Done-when, live:** the backend image with Ollama (`qwen3.8:27b-mlx`) through the Vite proxy: the
     model called `bash`, the Approve dialog showed the command, `line 1…4` streamed one per second,
     `notes.txt` was read, and `../m8-other/secret.txt`, `ls ../.claudius` and a TCP connect to
     1.1.1.1:80 each failed with "Permission denied". The session DB sat in `.claudius/sessions/`.
- **Note:** the agent's first full `check` run had one failure that it did not record; three
  `check --rerun-tasks` runs after review were clean. The timing-based Bash tests are the suspects.

---

## M9 — Sub-agents ✅ DONE (acceptance closed 2026-10-09)

- **Ships:** the model can hand an independent research or analysis task to a sub-agent. Several
  sub-agents in one reply run in parallel; each shows as a chip with a running/done status, its live
  progress, and its final report.
- **Backend:** `chat/AgentLoop.kt` — the loop split out of `ChatService` (which keeps the run
  registry, the SSE stream, history and persistence); `Run` is its own file. The `agent` tool
  `{description, prompt}` is handled by `AgentLoop` (a registry `Tool` would be a bean cycle) and is
  advertised at the top level only. A sub-agent is the same loop at depth 1 with a fresh context
  (`SUBAGENT_PROMPT` + the prompt), the same `Run` (Stop and disconnect end it), and only the autoRun
  tools; any other call there is DENY, so a sub-agent never asks and never recurses. Its tokens and
  `› tool args` lines stream as `tool_output` on the agent call; its reasoning is dropped; its answer
  is the `tool_result` (cap → `error: sub-agent stopped after N model calls`, failure → `error: …`).
  Calls that need no dialog (ALLOW/DENY) run in parallel, bounded by `claudius.chat.max-parallel-tools`
  (4); ASK calls follow on the loop thread, one dialog at a time; results go back in the model's
  order. `SessionStore.appendAudit` is `@Synchronized` (parallel audit writes to SQLite). No new SSE
  events.
- **Frontend:** `Message.tsx` renders `agent` calls as `<details>` chips in one row above the other
  tool cards: "agent · description" + `running…`/`done`; the body shows the live output tail while
  running and the report when done.
- **Done-when:** two agent calls in one reply run at the same time, each reports back, and the parent
  combines the reports.
- **Defect found in the live smoke (fixed):** `glob`/`grep` with `**/*.kt` missed every file at the
  workspace root (Java's glob needs a `/` for `**/`), so a sub-agent reported an empty workspace. A
  leading `**/` now also matches at the root; two regression tests.
- **Deferred:** background sub-agents (`run_in_background` + a completion event), agent types and
  per-type tool sets, sub-agents with write tools (needs nested approval), persisting the child
  transcript, forked context (reuse of the parent prefix).
- **Verified:** `./gradlew check` GREEN (three runs in a row by the agent, again after the glob fix);
  frontend all four commands GREEN. Backend from the JaCoCo XML: `chat` 98.8% line / 93.6% branch,
  `chat/tool` 97.2% / 89.7%, `workspace` 97.8% / 98.0%, `chat/dto` and `workspace/dto` 100% line.
  Frontend all files: 95.89% statements / 92.7% branch / 97.82% functions / 95.89% lines;
  `Message.tsx` 100%.
- **Acceptance (M9 gate, all 4 met):**
  1. **Coverage of core, from reports:** every `chat*`/`workspace*` package ≥ 97.2% line (gate 90%).
  2. **All tests pass:** backend 168 (+ 5 Landlock) / frontend 108 — 0 failures, 0 errors.
  3. **No ignored tests:** 0 `skipped`.
  4. **Done-when, live** in the single container with Ollama (`qwen3.8:27b-mlx`): one reply launched two
     agent calls; both chips showed `running…` together, then `done` with their reports; the parent
     combined them. After the glob fix, a sub-agent's `glob **/*.kt` found the root `Main.kt`.
- **Note:** whether two sub-agents also *generate* in parallel depends on Ollama's request
  concurrency (`OLLAMA_NUM_PARALLEL`); claudius sends them in parallel either way.

---

## M10 — Provider/model config ✅ DONE (acceptance closed 2026-10-09)

- **Ships:** the user adds providers in a Settings panel (Ollama, OpenAI-compatible, Anthropic) with
  a base URL and an API key, clicks **Fetch models**, and gets a models table prefilled with the
  limits the API reports (context window, max output tokens); every limit stays editable, per
  model. A picker next to Send selects the provider and model for each message. The workspace remembers the last choice. Each assistant turn
  shows which model answered.
- **Backend:** new `provider` package. `ProviderRegistry` keeps a `provider` table in the registry
  DB (`<workspaces-dir>/.claudius/`, so bash cannot read the keys). An empty table is seeded with one
  Ollama provider from `spring.ai.ollama.base-url` + `spring.ai.ollama.chat.model`, so the compose
  env vars still work on a fresh volume. `ProviderController`: `GET/POST /api/providers`,
  `PUT/DELETE /api/providers/{id}`, `GET /api/providers/{id}/models`, and `POST /api/providers/models`
  (fetch before save from `{type, baseUrl, apiKey | providerId}`; with `providerId` the stored key is
  used, so an edit never sends the key back to the browser; 502 when the provider fails, never
  echoing its body). Per-model limits live in `provider_model(provider_id, model, context_window,
  max_output_tokens)`; a provider's `models` list replaces them on save. Limits from the APIs:
  Anthropic `/v1/models` gives `max_input_tokens` + `max_tokens` (paged, all pages fetched); Ollama
  gives the context window via one `/api/show` per model (parallel); OpenAI's `/v1/models` gives no
  limits (OpenRouter's `context_length` / `top_provider.max_completion_tokens` are read when present).
  An Ollama key is sent as `Authorization: Bearer` (ollama.com), on model calls and chat. API keys leave the backend only masked (`…a1b2`); a PUT without `apiKey` keeps the stored key.
  `ChatModelFactory` builds and caches one `OllamaChatModel`/`OpenAiChatModel`/`AnthropicChatModel`
  per provider (evicted on update/delete). The starter is replaced by the plain modules
  `spring-ai-ollama`, `spring-ai-openai`, `spring-ai-anthropic`, so a missing key never breaks
  startup. `ChatRequest` gains `providerId?`/`model?`; `ChatService` resolves them before the
  stream (explicit → workspace's last → first provider + default model; an unknown explicit
  provider is a 400; a deleted last provider falls back to the first) and saves the choice on the
  workspace (`last_provider_id`, `last_model`). `AgentLoop` takes a `ModelChoice` per run and builds
  options with `ToolCallingChatOptions.mutate().model().maxTokens().toolCallbacks()` (max tokens =
  the chosen model's max output limit; the context window is stored for M11) — the
  `OllamaChatOptions` cast is gone (review finding 12). Sub-agents use the parent's choice. The
  assistant row records `"<provider name>/<model>"` (new `model` column).
- **Frontend:** `api/providers.ts`; `ProvidersPanel` (list, add, edit, delete with confirm, inline
  backend errors; the key field is a password input, empty on edit = keep, "Clear key" = remove;
  "Fetch models" fills a models table with a context window, a max output, a Default radio and Remove
  per row; a re-fetch keeps edited rows; "Add model" adds a manual row); `ModelPicker` (provider
  `<select>` + model `<input list>` suggesting the provider's saved models, else its live list; a
  failed list still allows a typed model). `Message` shows the model label.
- **Done-when:** one workspace talks to the same local model through all three adapters, with a tool
  call each, and the transcript shows which provider answered each turn.
- **Deferred:** reasoning for the OpenAI/Anthropic adapters (only Ollama's `thinking` metadata is
  read; with a tight max output the thinking can use the whole budget, and the turn ends empty, as
  any empty turn does), key encryption at rest (the keys sit in plain text in the registry DB, outside every
  workspace), per-provider retries/timeouts, a token-usage display (M11).
- **Verified:** `./gradlew check` GREEN (three runs); frontend all four commands GREEN.
  Backend from the JaCoCo XML: `chat` 98.9% line / 94.7% branch, `chat/tool` 97.3% / 90.0%,
  `provider` 99.5% / 94.7%, `workspace` 98.0% / 98.1%, `chat/dto` 100%, `workspace/dto` 90.9% line.
  Frontend all files: 97.42% statements / 93.95% branch / 97.42% lines; `ModelPicker.tsx` 94.7% and
  `ProvidersPanel.tsx` 95.2% branch. Landlock tests GREEN in Docker
  (`docker build --target landlock-test .`).
- **Defects found in review and the live smoke (fixed, test first):** a workspace whose last
  provider was deleted got a 400 on every chat (backend and picker now fall back to the first
  provider); a streaming turn showed its model label only after a reload.
- **Acceptance (M10 gate, all 4 met):**
  1. **Coverage of core, from reports:** every `chat*`/`workspace*`/`provider*` package ≥ 90% line.
  2. **All tests pass:** backend 201 (+ 5 Landlock) / frontend 141 — 0 failures, 0 errors.
  3. **No ignored tests:** 0 `skipped`.
  4. **Done-when, live** in the single container against the host's Ollama 0.34
     (`qwen3.8:27b-mlx`), which also serves OpenAI-compatible (`/v1`) and Anthropic-compatible
     (`/v1/messages`) APIs: the seeded Ollama provider plus an `OPENAI` provider
     (`…:11434/v1`) and an `ANTHROPIC` provider (`…:11434`, dummy key) each
     listed the model and answered a `currentTime` tool turn in the same workspace (the Anthropic
     turn replayed the earlier tool rows). Each assistant row carries its provider/model label. A
     chat without a provider used the workspace's last choice; an unknown provider was a 400; the
     raw keys did not appear in the container log. The UI listed masked keys, started the picker
     from the workspace's last choice, and sent through a switched provider. Per-model limits:
     a fetch before save returned the context window 262144 from Ollama's `/api/show` and no limits
     from the OpenAI-/Anthropic-compatible endpoints (Ollama does not report them there); an
     unreachable URL was a 502; an edit fetch with only `providerId` used the stored key. A max
     output of 64 cut a reply after "1 2 3 4". In the UI: type + URL → Fetch models → context window
     prefilled → max output typed → Default → Save stored both limits.

---

## M11 — Context tracking + compaction ✅ DONE (acceptance closed 2026-10-09)

- **Ships:** a usage bar above the composer shows the workspace's context size against its window
  ("Context: 25.4k / 65.5k tokens (39%) limit"). The window is the selected model's context window,
  or a per-workspace limit (≥ 65536) when that is smaller. Old turns are summarized (1) before a turn
  when the next turn would not fit, (2) inside a tool loop before a model call would overflow, (3)
  with the Compact button, or (4) after the user answers Yes to "Current context is bigger than
  context window. Should compact first. Proceed?", shown when the window is smaller than the context
  (No keeps the text and sends nothing). The transcript keeps every message: a collapsible "Context
  compacted" block marks each summary, and tool cards folded into a summary carry a "compacted" tag.
- **Backend:**
  - **Size:** `workspace.context_tokens`, stored after each turn from the last top-level call's
    usage (prompt + completion), else chars/4 of the messages plus the advertised tool definitions.
  - **Window:** W = min(model `contextWindow`, `workspace.context_limit`), whichever is known.
    `PUT /api/workspaces/{id}/context-limit` (`null` clears; < 65536 → 400).
  - **Trigger before a turn:** compact when `ctx + max(K, R) > W`, where K = the size of the last
    two turns and R = the reserved output = min(model max output ?: 4096, W/4). So it compacts only
    when a turn like the recent ones would not fit (the old fixed 80% rule compacted every turn when
    the fixed overhead was large). An automatic trigger with nothing to compact is skipped silently.
  - **Inside a turn (depth 0):** before each model call after the first, the estimate (last usage +
    the new tool results) + R > W → summarize the history and this turn's earlier tool rounds,
    continue with the summary + the last round. Those tool invocations get `compacted: true`; the
    model context skips them, the transcript keeps them. Sub-agents never compact.
  - **`Compactor`:** summary rows with `covers_until`; the model context is SYSTEM_PROMPT + the last
    summary + the rows after it. It keeps the last two turns while they fit W/4 and summarizes the
    rest (all of it when nothing older exists). The summarizer input is a quoted `<transcript>` (the
    previous summary, the rows, tools as `[tool name, arguments …] result:`, long results as head +
    tail, mid-turn also "Assistant (so far): …") with the instruction before and after it and "do
    not call tools"; it is cut from the oldest end to 0.8 × W; one streamed call, one retry. The
    compacting model is the workspace's last model (it held the context), else the chosen one.
    The summary row sits between the turn's user message and its reply.
  - **Wire:** `ChatRequest.compact`; a context over W without it is a 400 "current context is
    bigger than context window. should compact first" before the stream (the last model is saved
    only after the checks); SSE `compacted` (the summary row, also mid-turn) and `usage`
    (`{"contextTokens": n}`); `POST /api/workspaces/{id}/compact` (409 while busy, 400 "nothing to
    compact"). Config: `claudius.compact.keep-turns` (2).
- **Frontend:** `UsageBar` (size / window / percent, a "limit" marker, a "Context limit" input with
  a 65536 minimum, Compact); App checks W before a send and shows the Yes/No dialog; `compacted`
  inserts the summary right before the in-progress reply; `usage` updates the workspace; `Message`
  renders `summary` rows as a `<details>` block and tags compacted tool cards.
- **Done-when:** a long tool loop that would overflow the window compacts inside the turn and still
  finishes with the right answer; a switch to a smaller window goes through the prompt.
- **Defects found in review and the live smoke (fixed, test first):**
  - `MessageAggregator().aggregate(...).blockLast()` returned the last raw chunk, not the aggregate,
    so tool calls streamed in separate chunks were lost (since M4; Ollama sends them in one chunk).
  - A rejected compact-first chat already saved the small model as the last choice, so the retry
    compacted with the small model.
  - `chatModel.call()` failed on the Anthropic adapter against Ollama's Anthropic-compatible API
    ("`signature` is not set"); compaction now streams.
  - The estimate ignored the tool definitions (~1000 tokens): 250 estimated vs 1249 measured.
  - After Yes, the composer kept the sent text.
  - The fixed 80% trigger compacted on every turn when the system prompt + tool definitions alone
    passed it (replaced by the fit rule above).
  - Mid-turn, the summarizer **continued the transcript instead of summarizing it**: the input ended
    in the assistant's voice, so the model wrote tool calls (Ollama's parser then sent a chunk
    without `message`, and Spring AI's `OllamaChatModel` threw an NPE) or invented a value
    ("CODE-5 = emerald"). Fixed by the quoted-transcript prompt, a retry, plain-text tool lines.
  - Tool results cut to their first 2000 chars lost the end of each file (now head + tail), and the
    mid-turn input omitted the assistant's text so far.
  - A second mid-turn compaction in one turn failed with "List is empty" (no uncovered rows left).
- **Deferred:** a single tool round larger than the window still overflows (it is always kept);
  passing W to Ollama as `num_ctx` (Ollama loaded the model with 131072 here); the claude-code fast
  path (clear old tool results without a model call); re-injecting recent files; per-model
  tokenizers (chars/4 and each provider's own count are rough).
- **Verified:** `./gradlew check` GREEN (several runs, also with `--rerun-tasks`); frontend all four
  commands GREEN; Landlock tests GREEN in Docker. Backend from the JaCoCo XML: `chat` 98.8% line /
  91.6% branch, `chat/tool` 97.3% / 90.0%, `provider` 99.5% / 94.7%, `workspace` 98.3% / 98.5%,
  `chat/dto` 100%, `workspace/dto` 92.9% line. Frontend all files: 97.74% statements / 93.64%
  branch / 97.74% lines.
- **Acceptance (M11 gate, all 4 met):**
  1. **Coverage of core, from reports:** every `chat*`/`workspace*`/`provider*` package ≥ 90% line.
  2. **All tests pass:** backend 243 (+ 5 Landlock) / frontend 159 — 0 failures, 0 errors.
  3. **No ignored tests:** 0 `skipped`.
  4. **Done-when, live** in the single container against the host's Ollama 0.34
     (`qwen3.8:27b-mlx`), with a logging proxy in front of Ollama for the summarizer requests:
     - All three adapters reported a context size (`usage`: 1072 / 1103 / 642).
     - Switch to a 500-token model: a 400 with the exact message without `compact`; in the UI the
       dialog, No kept the text, Yes cleared it; the Anthropic-compatible last model streamed the
       summary; the reply used a kept turn.
     - A 20000-token model and five ~5k-token files read one per step: one mid-turn compaction
       after the third read; the summary kept the codes already seen; the turn listed all five
       codes correctly; the two folded tool cards carry the `compacted` tag.
     - Seven such files: three mid-turn compactions in one turn; each summary carried every code
       seen so far (2, then 4, then 6); the turn listed all seven codes correctly; six folded tool
       cards carry the tag and only the last round stayed verbatim; no chunk without `message`.
     - The next turn at 19795 / 20000 compacted before it started (the oversized last turn was
       summarized whole): 1206 tokens after, and the answer came from the summary.
     - Workspace limit: 1000 → 400; 65536 → the bar showed "/ 65.5k … limit".

---

## M11 follow-up — UX polish ✅ DONE (2026-10-09)

- **Ships:** one composer button (Send, Stop while a reply streams, "Compacting…" while a
  compaction runs); the Compact button stays disabled until the compaction ends; a "Compacting
  context…" chip appears at once (on the click, and on the new SSE event `compacting` before every
  pre-turn, `compact=true` and mid-turn compaction) and turns into the summary; the summary line
  shows the estimate, e.g. "Context compacted · ~443 tokens saved (1.1k → 638)"; the provider form
  (add and edit) opens in its own dialog behind "Add provider".
- **Backend:** `StreamChunk.Kind.COMPACTING` (`event: compacting`, data `{}`); summary rows store
  `tokens_before` / `tokens_after` (new nullable columns), exposed as `tokensBefore` /
  `tokensAfter` on `MessageDto` (omitted when null).
- **Verified:** backend 250 / frontend 166 tests GREEN. Live in the container: the chip and the
  disabled buttons on Compact, then "~443 tokens saved (1.1k → 638)"; Send became Stop and back;
  the Add provider dialog opened and Escape closed only it; a `compact=true` turn streamed
  `compacting` → `compacted` → `done`.

---

## M12 — WebSocket transport + detached runs ✅ DONE (acceptance closed 2026-10-09)

- **Ships:** the browser talks to claudius over one WebSocket per tab. Closing the tab or losing
  the connection no longer stops a run: a reopened tab shows the run in progress (replayed, then
  live) or its saved result; two tabs on one workspace see the same run; the status shows
  "reconnecting…" while the socket is down. Stop ends the run and the provider request.
- **Backend:** `chat/ChatSocket.kt` (`WebSocketHandler` on `/api/ws`; ping every
  `claudius.ws.ping-ms` = 1000; no frame for `claudius.ws.timeout-ms` = 5000 closes the socket,
  not the run; frames handled one at a time per socket). `chat/RunEvents.kt`: the per-run event log
  (`seq` from 0) with replay + listeners under one lock. `ChatService.start` validates
  synchronously and runs the turn detached on boundedElastic; one run per workspace; the
  workspace is freed before `done`. Stop cancels the model stream (`takeUntilOther`, verified to
  close the HTTP connection for the Ollama, OpenAI and Anthropic adapters against a slow fake
  server), the summarizer stream, and interrupts running tools (`Run.interruptible`; `bash` kills
  its process tree). Removed: the SSE `POST /api/chat`, `/cancel`, `/answer`.
- **Protocol:** up `subscribe {workspaceId, after}` / `chat {workspaceId, runId, message,
  providerId?, model?, compact?}` (auto-subscribes, no `state` frame) / `stop {runId}` / `answer
  {runId, id, approved}` / `pong`; down `ping` / `state {workspaceId, runId|null, lastSeq, model|null}` / `started
  {workspaceId, runId, message, model}` (a run starts on the followed workspace) / `runs {workspaceIds}` (on connect
  and when any run starts or ends) / `event {workspaceId, runId, seq, event, data}` (data = the old SSE data) / `error {runId?, message}`.
- **Frontend:** `api/socket.ts` (send queue while connecting, ping→pong, 3 s watchdog, reconnect
  with backoff 0.5/1/2/5 s, status); `useChat` over the socket (subscribe after the transcript
  loads; `state` with a run → streaming + replay; `state` null after a drop → reload the
  transcript; a replayed unanswered permission shows the dialog); a workspace switch only
  re-subscribes; nothing stops a run on unload; Vite proxies `/api` with `ws: true`.
- **Done-when:** close the tab during a run, reopen it, and the run is still streaming; Stop ends
  it and Ollama logs the request as cancelled.
- **Found in the live smoke, fixed after it:** the `qwen3.5:9b-mlx` model sometimes writes broken
  tool-call markup in a plain reply; Ollama's parser fails ("expected element type <function> but
  have <parameter>"), ends the stream with `{"error": ...}` (a chunk without `message`), and Spring
  AI 2.0.1's `OllamaChatModel` throws an NPE (it does not null-check `message`). `AgentLoop` now
  maps that NPE to "the model returned a malformed tool call (Ollama could not parse it); try
  again or use another model", and retries that model call once when nothing (answer or
  thinking) reached the client yet for it. Tests: `a provider stream that fails with a malformed
  tool call ends the run with a clear error`, `a malformed tool call before any output is retried
  once`, `a malformed tool call that repeats on the retry ends the run with a clear error`.
  Live: reproduced 3 of 4 times with thinking on, by asking the model to echo
  `<tool_call><parameter=path>…</function></tool_call>` verbatim; the UI showed the clear error.
  The retry did not fire live: qwen streams thinking first, so the guard skips it.
  ponytail: detection is a string match on the NPE text. Spring AI fixes the NPE in 2.0.2
  (spring-projects/spring-ai#7066, unreleased on 2026-10-09; 2.1.0-M1 still has it). After the
  upgrade the chunk becomes empty text, the match never fires, and the run ends with an empty or
  partial answer and no error: drop the match then, and read Ollama's stream `error` instead.
- **Restart safety (follow-up, same milestone):** each run keeps a checkpoint in the workspace's
  session DB (`run` table: status `running`/`waiting`/`done`/`interrupted`, the answer, thinking,
  trace and the round's pending calls), written at the start, after each top-level model call
  and tool result, at most once a second while a call streams (`claudius.chat.checkpoint-ms`),
  and when the run parks on a permission. On startup `RunRecovery` saves a `running` run as an
  assistant row with error "interrupted by a server restart" (the partial text up to the last
  second), and restores a `waiting` run: a rebuilt event log from seq 0, the permission asked
  again, and the turn continued once answered (no timeout, as the user chose). Assistant rows now
  keep the turn's error (`error`, display-only). The `state` frame carries `lastSeq`; a client
  whose last seq is ahead (the server restarted) reloads the transcript and replays from -1.
- **Deferred:** a restart or `kill -9` can lose up to 1 s of streamed text (the checkpoint
  interval, `claudius.chat.checkpoint-ms`; upgrade: checkpoint on graceful shutdown and/or per
  token batch); a restored run replays its earlier tool rounds to the model as one flattened
  round; `done`/`interrupted` run rows are kept; no auth on the socket.
  Small-model tool-call crash: a small model (e.g. `qwen3.5:9b-mlx`) sometimes writes broken
  tool-call markup; Ollama's parser fails and sends a chunk without `message`, and Spring AI's
  `OllamaChatModel` throws an NPE, so the run ends with a cryptic error and an empty answer
  (upgrade: map it to a clear "malformed tool call" error, retry the call once when nothing was
  streamed yet).
- **Verified:** `./gradlew check` GREEN (also `--rerun-tasks`); frontend all four commands GREEN;
  Landlock tests GREEN in Docker. Backend from the JaCoCo XML: `chat` 98.0% line / 89.4% branch,
  `chat/tool` 97.3% / 90.2%, `provider` 99.5% / 95.5%, `workspace` 98.3% / 98.5%, `chat/dto`
  100%, `workspace/dto` 92.9% line. Frontend all files: 97.88% statements / 94.53% branch.
- **Acceptance (M12 gate, all 4 met):**
  1. **Coverage of core, from reports:** every `chat*`/`workspace*`/`provider*` package ≥ 90% line.
  2. **All tests pass:** backend 266 (+ 5 Landlock) / frontend 160 — 0 failures, 0 errors.
  3. **No ignored tests:** 0 `skipped`.
  4. **Done-when, live** in the single container with Ollama: the old `POST /api/chat` is a 404. A
     long reply streamed in tab A; tab A was closed; a new tab opened the workspace in the
     streaming state with the reply so far (2545 chars) and kept growing live (5610 after 3 s).
     Stop: idle in 0.1 s, the partial reply saved (6098 chars), Ollama logged "Request terminated:
     context canceled" at that moment. Two tabs on one workspace both streamed the same run.
     `docker compose pause` for 6 s: the tab showed "reconnecting…", reconnected right after
     `unpause`, and kept streaming; after a Stop from the other tab, the tab held the full saved
     reply (9768 chars) exactly once (no gap, no duplicate from the replay).
     Restart safety: `docker compose restart` during a streamed essay → the tab showed "interrupted
     by a server restart" under the saved partial reply (3857 chars). `docker compose restart`
     while a `fileWrite` approval was open → the prompt came back (rebuilt log, `lastSeq` 2), the
     approval wrote `note.txt` ("restored ok"), and the model confirmed.

---

## M12 follow-up — UX session, 55 use cases ✅ DONE (2026-10-09)

- **Method:** a live session in the dev build (Ollama `qwen3.8:27b-mlx` / `qwen3.5:9b-mlx`) that
  mimics a user: workspace CRUD and dialogs, chat and Stop, reload and switching workspaces while
  runs stream (rapid switching, two runs at once, two tabs), every tool incl. approvals, bash and
  sub-agents, provider settings, usage bar and compaction, error paths, phone width, light mode.
  Not covered on purpose: container stop/restart. Findings and the full list: [ux-session-2026-10-09.md](ux-session-2026-10-09.md).
- **Fixed (defects):** a run started in another tab was invisible in a tab already on that
  workspace (new `started` frame + client handling); clicking outside the approval dialog (e.g. the
  sidebar) silently rejected the call (Approve/Reject now sit on the tool card, no modal); a reload
  lost the active workspace (localStorage); a stopped or failed turn showed the "…" loading
  placeholder forever; run errors showed in the top banner, far from the turn (now on the turn);
  a stopped turn looked complete (now marked `stopped`); the sidebar did not show which workspaces
  run (new `runs` frame + dot); a joined run had no model label (`state.model`); phone width
  overflowed (stacked layout, wrapping composer); scrolling up during a stream was overridden.
- **Fixed (readability):** replies render markdown (headings, lists, fenced code, tables; single
  newlines are line breaks; raw HTML stays text); workspace API errors carry the backend text;
  the delete dialog says what it deletes (no duplicated title); the rename field is selected on
  open; the status reads `compacting…`; the compact-first prompt is plain English; long tool
  results scroll inside the card; messages are trimmed; `ChatService.turn` overloads are
  `startTurn`/`runTurn`; `AgentLoop` and `useChat` open with a "how it works" block.
- **Left open (low):** a long workspace name wraps; a compaction that fails because the last model
  is gone only reports the error on the turn and then runs over the window (by design the last
  model compacts); the README/Architecture target lists still name Streamdown/Shiki.
- **Inspector (same day):** a right-hand panel with Tools and Agents tabs lists every call of the
  transcript; the chat shows calls as chips (`name · status`, `agent · description · status`) and a
  chip click selects the call in the panel (arguments, live output while it runs, result; prompt
  and report for a sub-agent). Without a click the panel shows the latest call of the tab, and a
  new run drops the selection. Approve/Reject stay in the chat under the chips.
- **Turn order (same day):** a turn used to render one thinking block, then the chips, then the
  answer, so a second thinking (after a tool call) merged into the first. Every tool call now
  records `thinkingAt`/`answerAt` (the thinking and answer length when the model made the call,
  on `ToolInvocationDto`, the `tool_call` event and the stored row); `Message` cuts the thinking
  and the answer at those offsets and renders the parts in arrival order (thinking → chip →
  thinking → answer). Rows without offsets keep the old order.
- **Adaptive layout (same day):** both side panels collapse to a rail (`«`/`»` button) and resize
  by a drag handle (`Splitter`, 160–600px, desktop only); `useLayout` keeps widths and open state
  in localStorage (`claudius.layout`) and starts phones (< 640px) with both panels closed. Chat
  text is 14px (`text-sm`); the composer input stays 16px on phones so iOS does not zoom on focus.
  The chat column had `mx-auto` without `w-full`, so wide content (a `pre`, a table) grew it past
  a phone screen; fixed, and wide tables scroll inside the bubble. Checked at 375, 768 and 1024px.
- **Verified:** backend 273 tests GREEN (`./gradlew check`, JaCoCo gate), frontend 200 tests GREEN
  (lint, prettier, tsc, 98.0% lines). Live: the two-tab run, the inline approval with a workspace
  switch while waiting, Stop → `stopped`, markdown, phone width.

---

## M13 — Autonomous tasks ✅ DONE (acceptance closed 2026-10-10)

- **Ships:** the user hands claudius a high-level task and gets a result. The model writes the
  steps to `PLAN.md`, does them, ticks them off, verifies (runs the tests when there are tests) and
  ends with a short report. A per-workspace **permission mode** decides what still needs a click:
  `ask` (today: every write, edit and command), `accept edits` (writes and edits run, commands ask),
  `bypass` (nothing asks; the bash sandbox still applies). A `CLAUDIUS.md` in the workspace root
  is appended to the system prompt, so standing instructions (stack, style, test command) survive
  across turns and compactions. Sub-agents inherit the mode, so in `accept edits` or `bypass` they
  can implement a sub-task; a call that would ask is still denied inside a sub-agent.
- **Backend:** `workspace.permission_mode TEXT NOT NULL DEFAULT 'ASK'` (`PermissionMode` enum
  `ASK | ACCEPT_EDITS | BYPASS` on `Workspace`/`WorkspaceDto`); `PUT /api/workspaces/{id}/permission-mode
  {"permissionMode": …}` (400 on an unknown value). `AgentLoop.run(…, mode)` and
  `permission(name, depth, mode)`: an `ASK` tool becomes `ALLOW` in `BYPASS`, and in `ACCEPT_EDITS`
  when it is `fileWrite`/`fileEdit`; the sub-agent rule (`ASK` → `DENY` at depth 1) applies after
  that. `execute` passes `approved = true` on `ALLOW`, so the fail-closed registry runs the tool; the
  audit row says `allow`. `SYSTEM_PROMPT` gains the work method (plan in `PLAN.md`, do, tick, verify,
  report). `Compactor.context` appends `CLAUDIUS.md` (cut at 16 000 chars) when the workspace has
  one. `max-iterations` default 25 → 50: a multi-file task needs more tool rounds.
- **Frontend:** `Workspace.permissionMode`, `setPermissionMode` in `api/workspaces.ts`,
  `useWorkspaces.setMode`; a `<select aria-label="Permissions">` in the app header (Ask / Accept
  edits / Bypass permissions), red when bypass is on.
- **Done-when:** with `bypass` on, "create hello.txt with hi and run cat hello.txt" finishes with no
  dialog and two `allow` audit rows; with `accept edits`, the write runs and the `cat` asks; with
  `ask`, both ask. A workspace with `CLAUDIUS.md` "always answer in uppercase" gets an uppercase
  reply. A multi-step task leaves a `PLAN.md` with ticked boxes.
- **Deferred:** an `auto` mode with a bash allow-list (prefixes like `npm test`); bypass covers the
  need today and bash stays sandboxed. A Plan tab in the inspector (the `fileWrite` chips show
  `PLAN.md` for now). Writer sub-agents with their own approval dialog.
- **Verified:** backend 287 tests GREEN (`./gradlew check`, JaCoCo: `chat` 98.0%, `workspace` 98.4%,
  `chat/tool` 97.3% lines); frontend 208 tests GREEN (lint, prettier, tsc). Live with Ollama
  `qwen3.8:27b-mlx` on the dev backend (port 8081 via the new `backend-nosandbox-8081` +
  `frontend-8081` launch entries; `CLAUDIUS_API` sets the Vite proxy target): bypass → `fileWrite`
  and `bash` ran with no dialog, audit `allow`/`allow`; accept edits → the write ran, `cat` showed
  Approve/Reject, audit `allow`/`approved`; a `CLAUDIUS.md` "Always answer in UPPERCASE letters."
  gave an uppercase reply; `PUT …/permission-mode` with a bad value → 400. The header select
  shows the mode, red for bypass, and old registry rows migrated to `ASK`.
- **Decision — cloudflare/computer (checked 2026-10-10):** not adopted. `@cloudflare/computer` 0.5.0
  (published 2026-10-09) is a SQLite-backed virtual filesystem inside a Cloudflare Durable Object
  with container / Dynamic Worker execution backends. Its README, package README and docs index
  all mark it **preview only, not suitable for production**, APIs unstable; CONTRIBUTING accepts no
  outside pull requests. It is TypeScript on the Workers platform: adopting it means rewriting the
  Kotlin backend as a Worker, moving workspaces into Durable Object storage (in-memory on the
  container side, FUSE I/O slower than disk, ~10 GB cap) and losing the local-first setup (Ollama
  and MLX models on the user's machine, Docker volume for data). claudius keeps its own workspace
  dir + `landrun` sandbox (M8). Revisit if a hosted multi-tenant deployment becomes a goal and the
  package reaches a stable release.

---

## M13+ (open backlog)

Add a new `## M{n} — <title>` section from the template below. Keep the arc table row in sync.

| # | Milestone | Ships | Backend seam | Frontend seam |
|---|-----------|-------|--------------|---------------|
| M14 | Memory view | edit `CLAUDIUS.md` and `PLAN.md` in the UI | — | Plan / instructions tab in the inspector |

### Milestone template

```
## M{n} — <title>
- **Ships:** <one line: the end-to-end slice the user sees>
- **Backend:** <seam / component names, tied to Architecture § or reference file>
- **Frontend:** <seam / component names>
- **Done-when:** <observable acceptance criteria>
- **Deferred:** <what stays out of this milestone and where it lands>
```
