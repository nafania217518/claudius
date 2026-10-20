# Project
Web application similar to claude-code, which leverages AI agents to perform coding tasks through a rich GUI. The application will be built using Kotlin, Spring AI, React and will implement an agent loop, context management, tool calling, sub-agents, and memory.

## Run

**Docker (one container, backend + frontend):** start Ollama on the host and pull the model, then

```bash
docker compose up --build
```

Open http://localhost:8080. Workspaces live in the `claudius-data` volume (`docker compose down -v`
deletes them). Add OpenAI-compatible or Anthropic providers under the gear in the header, and
pick the provider and model next to Send. `CLAUDIUS_MODEL=<model> docker compose up --build` sets
the model of the seeded Ollama provider on a fresh volume.

**Hand over a task (M13):** the **Permissions** select in the header decides what the model may do
without asking in this workspace: *Ask* (every write, edit and command), *Accept edits* (writes and
edits run, commands ask) or *Bypass permissions* (nothing asks; bash stays in its sandbox). For a
multi-step task the model writes the steps to `PLAN.md`, ticks them off, runs the tests when there
are tests and ends with a short report. A `CLAUDIUS.md` in the workspace root (stack, style, test
command) is added to the system prompt on every turn.

**Dev (hot reload):** `./start_backend.sh` (8080) and `./start_frontend.sh` (Vite on 5173, proxies
`/api`). The bash sandbox needs Linux + landrun, so outside Docker run the backend with
`SPRING_APPLICATION_JSON='{"claudius":{"bash":{"sandbox":"none"}}}' ./start_backend.sh` for
unsandboxed commands (each still needs approval unless the workspace bypasses permissions). In the Claude desktop app the `backend-nosandbox`
entry of `.claude/launch.json` does the same.

## Architecture

### Current (as built, M12 — 2026-10-09)

| Layer | What is in the code |
| --- | --- |
| Backend | Kotlin 2.3 + Spring Boot 4.1 (WebFlux) + Spring AI 2.0.1 (Ollama, OpenAI-compatible, Anthropic); Jackson DTOs |
| Providers | Managed in the UI (`/api/providers`): base URL, API key (stored server-side, returned masked), **Fetch models** with per-model limits (context window, max output tokens) prefilled from the API where it reports them and editable; the provider + model are picked per message and remembered per workspace |
| Agent loop | `AgentLoop` (run by `ChatService`): manual loop over `chatModel.stream`, claudius-owned tool execution, permission gate (read-only tools run; `fileWrite`/`fileEdit` wait for the user), iteration cap, per-run stop signal; tool turns replayed into context; calls without a dialog run in parallel |
| Context | Context size per workspace from the provider's usage; window = the model's, capped by an optional workspace limit (≥ 65536); older turns summarized before a turn that would not fit, inside a long tool loop, with the Compact button, or after a compact-first prompt; the transcript keeps everything |
| Tools | `currentTime`, `fileRead`, `glob`, `grep`, `fileWrite`, `fileEdit` (paths confined by `resolveInWorkspace`), `bash` (Landlock sandbox via `landrun`: workspace + temp dir only, no TCP, live output), `agent` (sync sub-agent: fresh context, the workspace's permission mode minus asking, parallel) |
| Transport | One WebSocket per tab at `/api/ws` (server ping every 1 s): `subscribe`/`chat`/`stop`/`answer` up, `state` (with the run's model), `started` (a run starts on the followed workspace), `runs` (workspaces with an active run) + numbered run events (`token`/`reasoning`/`tool_call`/`permission`/`tool_output`/`tool_result`/`compacting`/`compacted`/`usage`/`error`/`done`) down. Runs are detached from connections: a closed tab or a dropped socket does not stop a run; a reconnect replays the missed events. Runs are checkpointed: after a server restart a run that was mid-call is saved as interrupted, and one waiting on a permission asks again. REST for workspaces, providers, messages, compact |
| Storage | `<workspaces-dir>/.claudius/`: registry DB + providers + one SQLite session DB per workspace (`sessions/<id>.db`: messages + tool-decision audit), outside every workspace dir |
| Deploy | One image (root `Dockerfile`): the backend serves the built frontend from `classpath:/static` next to `/api` on port 8080; non-root, landrun built from source. Local run: `docker compose up --build` (see [Run](#run)) |
| Frontend | React 19 + Vite 8 + Tailwind v4; plain `fetch` + a small WebSocket client (reconnect with backoff, ping watchdog, resubscribe from the last seq); `useState` hooks; markdown rendering (react-markdown + GFM + line breaks, no raw HTML); tool and sub-agent calls are chips that open a right-hand inspector (Tools / Agents tabs: arguments, live output, result); both side panels collapse and resize (saved in localStorage); no router, no virtualization |
| Tests | kotest matchers + MockK + `StepVerifier` (fake `ChatModel` VCR), JaCoCo 90% gate; Vitest + RTL + jsdom, 90% gate |

Decisions that differ from the target research below: Jackson instead of kotlinx.serialization;
**one WebSocket per tab with detached runs** (M12; SSE + REST commands until M11): a run
outlives its connection, and a reconnect resumes it from the last seen event; no Zod/Zustand/TanStack Query/React Router until the UI has more than one screen.
Current state and acceptance per milestone: [docs/roadmap.md](docs/roadmap.md). Review and
rationale: [docs/review-2026-10-09.md](docs/review-2026-10-09.md).

### Target stack (research, 2026-09-21)

The lists below are the researched target, not the current code. Adopt an item when a milestone
needs it.

#### Backend
folder: `claudius-backend`

**Kotlin + Spring AI** — owns the agent loop, tool execution, permission checks,
sub-agent orchestration, context/compaction, and memory.

- **Transport:** **one WebSocket per tab** (M12) — see "Current" above.
  A native WebSocket per run is the upgrade if bidirectional streaming is ever needed.
- **REST contract:** **OpenAPI via springdoc-openapi** — the source of truth the
  frontend validates its envelopes against.
- **DTOs / wire shape:** **kotlinx.serialization** DTOs + **shape-pinning tests**
  (golden JSON), not codegen; **JSON Schema** (first-party **kotlinx-schema**) as a
  one-way doc only.
- **No Kotlin↔JS schema sharing** — the frontend re-declares the same shapes with
  Zod; **tRPC is rejected** (Kotlin backend).
- *(Backend-specific versions/deps are provisional pending a dedicated
  backend-design research pass; the transport + contract side is settled in
  `docs/frontend-design/routing-data-fetching.md` and
  `docs/frontend-design/type-safety-schema.md`.)*

#### Frontend
folder: `claudius-frontend`

**React SPA** that live-streams the agent GUI over WebSocket/SSE. Full research:
`docs/frontend-design/` (13 categories, all live-verified 2026-09-21).

- **Build / framework:** Vite + React 19 + React Router 7 (SPA, no RSC/SSR).
- **Style:** Tailwind CSS v4 (`@tailwindcss/vite`, CSS-var dark mode).
- **Components:** shadcn/ui + Radix/Base UI (copy-in, dark-first, tiny, best a11y
  for the approve/reject dialogs); Mantine v9.6.2 as a batteries-included runner-up.
- **State:** Zustand (UI/session/settings, split slices + `persist`) + TanStack
  Query (REST/server state) + a dedicated `useAgentStream` hook for the WS/SSE
  firehose (kept out of the global store).
- **Transport:** **SSE per run** for the server→client stream; **REST commands**
  (`POST /api/chat/{runId}/cancel`, later `/answer`) for stop + approve/reject; TanStack
  Query for ordinary REST once there is more than a handful of calls.
- **Rendering:** **react-virtuoso** virtualizes the long transcript (auto-scroll +
  variable heights); **Streamdown** for streaming markdown (built-in
  `rehype-harden` XSS sanitization); **Shiki + `@shikijs/stream`** for code
  (refractor for high-volume virtualized rows).
- **Plan comments:** custom read-only gutter annotations (v1); TipTap/CodeMirror as
  an upgrade.
- **Composer:** `<textarea>` + preview; Lexical only if mentions/inline formatting
  land.
- **Schema:** **Zod v4** (or Valibot) validates every wire envelope + `z.infer`
  types; OpenAPI/springdoc-openapi for REST; no Kotlin↔JS schema sharing.
- **Testing:** Vitest + React Testing Library + jsdom (unit/integration of the
  streaming reducer, formatters, and approve/reject dialog) + Playwright (E2E
  run→see tool calls→approve) + MSW (mock WS/SSE).
- **Cross-cutting rules:** coalesce streaming token updates, keep the stream out of
  the global store, memoize with the React Compiler, focus-trap approve/reject
  modals, mark the transcript `role="log"`/`aria-live`, and **sanitize all
  agent-produced HTML/markdown**.

## User Stories
- As a developer, I want to ask the agent to "Refactor the UserService class to use dependency injection" and see it modify files, run tests, and report results.

- As a user, I want to see the agent’s plan and progress in real time — its thinking process, and each tool call’s parameters, logs, and results.

- As a user, I want to see the background tasks that are currently running.

- As a user, I want to see the parameters, logs, and results of both running and stopped tasks.

- As a user, I want the agent to ask me one or more questions interactively and let me answer — each question can offer several options, an input field for a custom answer, and multi-select.

- As a user, I want to stop the current agent, its sub-agents, and background tasks — and individual tool calls — at any time.

- As a power user, I want to configure several LLM providers and their models via the interface, choose which model from which provider to use for the next call, and adjust token limits.

- As a power user, I want an auto mode that automatically selects which model to use from my configured settings.

- As a power user, I want to set a request-routing policy that sends requests to different models by rules I choose (round-robin, cheapest-first, usage-based, etc.).

- As a power user, I want models that fail to respond (limit reached, downtime, etc.) to be marked and skipped for a cooldown period I set (5h, 1d, 1w, 1m, …), and to override or clear that flag at any time.

- As a team member, I want the agent to remember our project’s coding standards across sessions.

- As a tester, I want to spawn multiple sub-agents to analyze different parts of the codebase in parallel.

- As a user, I want to create and manage workspaces that persist session information and hold all related files (a working directory).

- As a learner, I want to review the conversation history and the agent’s reasoning steps after the task is done.

*TBD*
